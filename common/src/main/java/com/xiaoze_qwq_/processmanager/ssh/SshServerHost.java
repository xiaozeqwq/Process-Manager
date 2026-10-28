package com.xiaoze_qwq_.processmanager.ssh;

import com.xiaoze_qwq_.processmanager.InstanceManager;
import com.xiaoze_qwq_.processmanager.ProcessInstance;
import com.xiaoze_qwq_.processmanager.ProcessManager;
import com.xiaoze_qwq_.processmanager.config.Config;
import dev.architectury.platform.Platform;
import org.apache.sshd.core.CoreModuleProperties;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.password.UserAuthPasswordFactory;
import org.apache.sshd.server.auth.pubkey.UserAuthPublicKeyFactory;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.command.CommandFactory;
import org.apache.sshd.server.config.keys.AuthorizedKeysAuthenticator;
import org.apache.sshd.server.forward.RejectAllForwardingFilter;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.server.shell.ShellFactory;
import org.apache.sshd.common.AttributeRepository;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.session.SessionListener;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.PublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内嵌 SSH 服务端。仅监听回环地址，外网流量由 {@link ProtocolSniffHandler}
 * 从 Minecraft 端口嗅探后桥接进来。客户端以实例编号或实例名称作为用户名，
 * 认证方式由目标实例配置决定（密码或公钥）；登录后直接接入该实例的控制台，
 * 不提供 shell、命令执行或端口转发。
 */
public final class SshServerHost {
    private static final int MAX_AUTH_REQUESTS = 10;
    private static final Duration AUTH_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(10);
    private static final Object DUMMY_LOCK = new Object();
    private static final AttributeRepository.AttributeKey<String> SESSION_INSTANCE_ID =
            new AttributeRepository.AttributeKey<>();
    private static volatile String dummyHash;

    private static SshServerHost instance;

    private final Set<Session> sessions = ConcurrentHashMap.newKeySet();
    private final Map<Session, Long> sessionStarts = new ConcurrentHashMap<>();
    private final Map<Session, String> sessionIps = new ConcurrentHashMap<>();
    private final Map<Session, String> sessionInstances = new ConcurrentHashMap<>();
    private final Map<String, AuthorizedKeysAuthenticator> keyAuthenticators = new ConcurrentHashMap<>();

    private SshServer sshd;
    private int localPort = -1;

    private SshServerHost() {
    }

    public static synchronized void start() throws IOException {
        if (instance != null) return;
        if (!Config.hasEnabledSshInstance()) return;
        SshServerHost host = new SshServerHost();
        try {
            host.doStart();
        } catch (IOException | RuntimeException e) {
            host.doStop();
            throw e;
        }
        instance = host;
    }

    public static synchronized void stop() {
        SshServerHost host = instance;
        instance = null;
        if (host != null) host.doStop();
    }

    public static synchronized int getPort() {
        return instance != null ? instance.localPort : -1;
    }

    public static void disconnectAllSessions() {
        SshServerHost host = current();
        if (host != null) host.doDisconnectAll();
    }

    public static void disconnectInstance(String instanceId) {
        SshServerHost host = current();
        if (host != null) host.doDisconnectInstance(instanceId);
    }

    public static void disconnectDisabledInstances() {
        SshServerHost host = current();
        if (host != null) host.doDisconnectDisabledInstances();
    }

    public static synchronized int getActiveSessionCount(String instanceId) {
        if (instance == null) return 0;
        int count = 0;
        for (String id : instance.sessionInstances.values()) {
            if (id.equals(instanceId)) count++;
        }
        return count;
    }

    private static SshServerHost current() {
        synchronized (SshServerHost.class) {
            return instance;
        }
    }

    private void doStart() throws IOException {
        Path hostKey = Platform.getConfigFolder().resolve("pm_ssh_hostkey_ec.ser");

        sshd = SshServer.setUpDefaultServer();
        sshd.setHost("127.0.0.1");
        sshd.setPort(0);
        sshd.setKeyPairProvider(createHostKeyProvider(hostKey));
        sshd.setUserAuthFactories(List.of(UserAuthPasswordFactory.INSTANCE, UserAuthPublicKeyFactory.INSTANCE));
        sshd.setPasswordAuthenticator(this::authenticatePassword);
        sshd.setPublickeyAuthenticator(this::authenticatePublicKey);
        sshd.setForwardingFilter(RejectAllForwardingFilter.INSTANCE);
        sshd.setSubsystemFactories(Collections.emptyList());

        ConsoleCommandFactory factory = new ConsoleCommandFactory();
        sshd.setShellFactory(factory);
        sshd.setCommandFactory(factory);

        CoreModuleProperties.SERVER_IDENTIFICATION.set(sshd, "PM_Console");
        CoreModuleProperties.MAX_AUTH_REQUESTS.set(sshd, MAX_AUTH_REQUESTS);
        CoreModuleProperties.AUTH_TIMEOUT.set(sshd, AUTH_TIMEOUT);
        CoreModuleProperties.IDLE_TIMEOUT.set(sshd, IDLE_TIMEOUT);
        sshd.addSessionListener(new AuditSessionListener());

        sshd.start();

        for (SocketAddress address : sshd.getBoundAddresses()) {
            if (address instanceof InetSocketAddress) {
                localPort = ((InetSocketAddress) address).getPort();
                break;
            }
        }
        if (localPort <= 0) {
            throw new IOException("Could not determine embedded SSH server port");
        }
        restrictFilePermissions(hostKey);
    }

    private SimpleGeneratorHostKeyProvider createHostKeyProvider(Path hostKey) {
        SimpleGeneratorHostKeyProvider provider = new SimpleGeneratorHostKeyProvider(hostKey);
        provider.setAlgorithm("EC");
        provider.setKeySize(256);
        return provider;
    }

    private boolean authenticatePassword(String username, String password, ServerSession session) {
        String ip = peerIp(session);
        if (SshGuard.isLocked(ip)) {
            SshAudit.authFail(ip, null, "locked");
            return false;
        }

        Config.InstanceConfig cfg = Config.findSshInstanceWithFallback(username);
        String candidate = password == null ? "" : password;
        boolean passwordMode = cfg != null
                && cfg.sshEnabled
                && Config.SSH_AUTH_PASSWORD.equals(Config.normalizeSshAuthMode(cfg.sshAuthMode));
        if (!passwordMode || cfg.sshPasswordHash == null || cfg.sshPasswordHash.isEmpty()) {
            Config.bcryptVerify(candidate, dummyHash());
            if (cfg == null) SshGuard.recordFailure(ip);
            SshAudit.authFail(ip, cfg == null ? null : cfg.id,
                    cfg == null ? "unknown_instance" : "auth_mode_mismatch");
            return false;
        }

        if (!SshGuard.acquireBcrypt()) {
            SshAudit.authFail(ip, cfg.id, "auth_busy");
            return false;
        }
        boolean ok;
        try {
            ok = Config.verifyInstanceSshPassword(cfg.id, candidate);
        } finally {
            SshGuard.releaseBcrypt();
        }
        if (!ok) {
            SshGuard.recordFailure(ip);
            SshAudit.authFail(ip, cfg.id, "bad_password");
            return false;
        }
        if (!registerSession(session, cfg.id, ip)) {
            SshAudit.authFail(ip, cfg.id, "session_limit");
            return false;
        }
        session.setAttribute(SESSION_INSTANCE_ID, cfg.id);
        SshGuard.recordSuccess(ip);
        SshAudit.authOk(ip, cfg.id);
        return true;
    }

    private boolean authenticatePublicKey(String username, PublicKey key, ServerSession session) {
        String ip = peerIp(session);
        if (SshGuard.isLocked(ip)) {
            SshAudit.authFail(ip, null, "locked");
            return false;
        }

        Config.InstanceConfig cfg = Config.findSshInstance(username);
        boolean publicKeyMode = cfg != null
                && cfg.sshEnabled
                && Config.SSH_AUTH_PUBLICKEY.equals(Config.normalizeSshAuthMode(cfg.sshAuthMode));
        if (!publicKeyMode) {
            if (cfg == null) SshGuard.recordFailure(ip);
            SshAudit.authFail(ip, cfg == null ? null : cfg.id,
                    cfg == null ? "unknown_instance" : "auth_mode_mismatch");
            return false;
        }

        Path keysPath = Config.getSshAuthorizedKeysPath(cfg.id);
        if (!Files.isRegularFile(keysPath)) {
            SshAudit.authFail(ip, cfg.id, "no_public_keys");
            return false;
        }
        AuthorizedKeysAuthenticator authenticator = keyAuthenticators.computeIfAbsent(
                cfg.id, id -> new AuthorizedKeysAuthenticator(Config.getSshAuthorizedKeysPath(id)));
        if (!authenticator.authenticate(username, key, session)) {
            SshAudit.authFail(ip, cfg.id, "bad_public_key");
            return false;
        }
        session.setAttribute(SESSION_INSTANCE_ID, cfg.id);
        return true;
    }

    /** 认证成功事件：公钥认证在签名验证完成后才登记会话，避免探测请求提前占位。 */
    private void onAuthenticated(Session session) {
        String instanceId = session.getAttribute(SESSION_INSTANCE_ID);
        if (instanceId == null) return;
        if (sessions.contains(session)) return;
        String ip = peerIp(session);
        if (!(session instanceof ServerSession serverSession)) return;
        if (!registerSession(serverSession, instanceId, ip)) {
            SshAudit.authFail(ip, instanceId, "session_limit");
            try {
                session.close(true);
            } catch (Throwable ignored) {
            }
            return;
        }
        SshGuard.recordSuccess(ip);
        SshAudit.authOk(ip, instanceId);
    }

    private boolean registerSession(ServerSession session, String instanceId, String ip) {
        if (!sessions.add(session)) return true;
        if (!SshGuard.tryAcquireSession(ip)) {
            sessions.remove(session);
            return false;
        }
        sessionStarts.put(session, System.currentTimeMillis());
        sessionIps.put(session, ip);
        sessionInstances.put(session, instanceId);
        return true;
    }

    private void releaseTracking(Session session) {
        if (!sessions.remove(session)) return;
        Long start = sessionStarts.remove(session);
        String ip = sessionIps.remove(session);
        String instanceId = sessionInstances.remove(session);
        SshGuard.releaseSession(ip == null ? peerIp(session) : ip);
        long duration = start == null ? 0L : System.currentTimeMillis() - start;
        SshAudit.sessionClosed(ip == null ? peerIp(session) : ip, instanceId, duration);
    }

    private void doDisconnectAll() {
        for (Session session : new ArrayList<>(sessions)) {
            releaseTracking(session);
            try {
                session.close(true);
            } catch (Throwable ignored) {
            }
        }
    }

    private void doDisconnectInstance(String instanceId) {
        if (instanceId == null) return;
        for (Session session : new ArrayList<>(sessions)) {
            String currentId = sessionInstances.get(session);
            if (!instanceId.equals(currentId) && !instanceId.equals(session.getAttribute(SESSION_INSTANCE_ID))) {
                continue;
            }
            releaseTracking(session);
            try {
                session.close(true);
            } catch (Throwable ignored) {
            }
        }
    }

    private void doDisconnectDisabledInstances() {
        for (Session session : new ArrayList<>(sessions)) {
            String instanceId = sessionInstances.get(session);
            Config.InstanceConfig cfg = instanceId == null ? null : Config.getInstanceConfig(instanceId);
            if (cfg != null && cfg.sshEnabled) continue;
            releaseTracking(session);
            try {
                session.close(true);
            } catch (Throwable ignored) {
            }
        }
    }

    private void doStop() {
        SshServer server = sshd;
        sshd = null;
        doDisconnectAll();
        if (server != null) {
            try {
                server.stop();
            } catch (Throwable t) {
                ProcessManager.LOGGER.warn("Failed to stop embedded SSH server", t);
            }
        }
    }

    private static String dummyHash() {
        String value = dummyHash;
        if (value != null) return value;
        synchronized (DUMMY_LOCK) {
            if (dummyHash == null) {
                dummyHash = Config.bcryptHash("pm-dummy-" + UUID.randomUUID());
            }
            return dummyHash;
        }
    }

    private static String peerIp(Session session) {
        if (session == null) return "unknown";
        try {
            SocketAddress address = session.getRemoteAddress();
            if (address instanceof InetSocketAddress inet) {
                String mapped = SshBridgeRegistry.lookup(inet.getPort());
                if (mapped != null) return mapped;
                if (inet.getAddress() != null) {
                    if (inet.getAddress().isLoopbackAddress()) return "local";
                    return inet.getAddress().getHostAddress();
                }
            }
        } catch (Throwable ignored) {
        }
        return "unknown";
    }

    private static void restrictFilePermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
        }
    }

    private final class AuditSessionListener implements SessionListener {
        @Override
        public void sessionEvent(Session session, Event event) {
            if (event == Event.KexCompleted) {
                enforceConnectionLimits(session);
            } else if (event == Event.Authenticated) {
                onAuthenticated(session);
            }
        }

        @Override
        public void sessionClosed(Session session) {
            releaseTracking(session);
        }
    }

    /** 在 KEX 完成时限流：此时桥接已注册来源 IP，避免把回环地址当成所有客户端。 */
    private static void enforceConnectionLimits(Session session) {
        String ip = peerIp(session);
        if (SshGuard.isLocked(ip)) {
            SshAudit.authFail(ip, null, "locked");
            closeQuietly(session);
            return;
        }
        if (!SshGuard.allowNewConnection(ip)) {
            SshAudit.authFail(ip, null, "rate_limited");
            closeQuietly(session);
        }
    }

    private static void closeQuietly(Session session) {
        try {
            session.close(true);
        } catch (Throwable ignored) {
        }
    }

    /** 仅允许交互式 shell 请求接入用户名指定实例的控制台；exec 命令一律拒绝。 */
    private static final class ConsoleCommandFactory implements ShellFactory, CommandFactory {
        @Override
        public Command createShell(ChannelSession channel) {
            return createCommand(channel, null);
        }

        @Override
        public Command createCommand(ChannelSession channel, String command) {
            if (command != null && !command.isBlank()) {
                SshAudit.bridgeRejected(peerIp(channel.getSession()), "exec_denied");
                throw new IllegalStateException("Only the interactive console is supported");
            }
            ServerSession session = channel.getSession();
            String instanceId = session.getAttribute(SESSION_INSTANCE_ID);
            if (instanceId == null) {
                SshAudit.bridgeRejected(peerIp(session), "no_authenticated_instance");
                throw new IllegalStateException("SSH console is not available");
            }
            Config.InstanceConfig cfg = Config.getInstanceConfig(instanceId);
            if (cfg == null || !cfg.sshEnabled) {
                SshAudit.bridgeRejected(peerIp(session), "instance_disabled");
                throw new IllegalStateException("SSH console is not available");
            }
            ProcessInstance target = InstanceManager.getInstance().getInstance(instanceId);
            if (target == null || !target.isRunning()) {
                SshAudit.bridgeRejected(peerIp(session), "instance_not_running");
                throw new IllegalStateException("SSH console is not available");
            }
            return new InstanceConsoleCommand(target);
        }
    }
}
