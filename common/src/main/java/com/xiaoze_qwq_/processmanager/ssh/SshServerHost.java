package com.xiaoze_qwq_.processmanager.ssh;

import com.xiaoze_qwq_.processmanager.InstanceManager;
import com.xiaoze_qwq_.processmanager.ProcessInstance;
import com.xiaoze_qwq_.processmanager.ProcessManager;
import com.xiaoze_qwq_.processmanager.config.Config;
import dev.architectury.platform.Platform;
import org.apache.sshd.core.CoreModuleProperties;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.password.UserAuthPasswordFactory;
import org.apache.sshd.server.auth.pubkey.RejectAllPublickeyAuthenticator;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.command.CommandFactory;
import org.apache.sshd.server.forward.RejectAllForwardingFilter;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.server.shell.ShellFactory;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.session.SessionListener;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内嵌 SSH 服务端。仅监听回环地址，外网流量由 {@link ProtocolSniffHandler}
 * 从 Minecraft 端口嗅探后桥接进来。用户名不参与认证，密码只对应唯一启用 SSH 的实例；
 * 登录后直接接入该实例的控制台，不提供 shell、命令执行或端口转发。
 */
public final class SshServerHost {
    private static final int MAX_AUTH_REQUESTS = 3;
    private static final Duration AUTH_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(10);
    private static final Object DUMMY_LOCK = new Object();
    private static volatile String dummyHash;

    private static SshServerHost instance;

    private final Set<Session> sessions = ConcurrentHashMap.newKeySet();
    private final Map<Session, Long> sessionStarts = new ConcurrentHashMap<>();
    private final Map<Session, String> sessionIps = new ConcurrentHashMap<>();
    private final Map<Session, String> sessionInstances = new ConcurrentHashMap<>();

    private SshServer sshd;
    private int localPort = -1;

    private SshServerHost() {
    }

    public static synchronized void start() throws IOException {
        if (instance != null) return;
        if (!Config.hasConfiguredSshInstance()) return;
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
        SshServerHost host;
        synchronized (SshServerHost.class) {
            host = instance;
        }
        if (host != null) host.doDisconnectAll();
    }

    public static synchronized int getActiveSessionCount() {
        return instance != null ? instance.sessions.size() : 0;
    }

    private void doStart() throws IOException {
        Path hostKey = Platform.getConfigFolder().resolve("pm_ssh_hostkey_ec.ser");

        sshd = SshServer.setUpDefaultServer();
        sshd.setHost("127.0.0.1");
        sshd.setPort(0);
        sshd.setKeyPairProvider(createHostKeyProvider(hostKey));
        sshd.setUserAuthFactories(Collections.singletonList(UserAuthPasswordFactory.INSTANCE));
        sshd.setPasswordAuthenticator(this::authenticate);
        sshd.setPublickeyAuthenticator(RejectAllPublickeyAuthenticator.INSTANCE);
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

    private boolean authenticate(String username, String password, ServerSession session) {
        String ip = peerIp(session);
        if (!SshGuard.allowNewConnection(ip)) {
            SshAudit.authFail(ip, null, "rate_limited");
            return false;
        }
        if (SshGuard.isLocked(ip)) {
            SshAudit.authFail(ip, null, "locked");
            return false;
        }

        Config.InstanceConfig cfg = Config.getSshEnabledInstance();
        String candidate = password == null ? "" : password;
        if (cfg == null || cfg.sshPasswordHash == null || cfg.sshPasswordHash.isEmpty()) {
            Config.bcryptVerify(candidate, dummyHash());
            SshAudit.authFail(ip, null, "no_instance");
            return false;
        }

        if (!SshGuard.acquireBcrypt()) {
            SshAudit.authFail(ip, cfg.id, "auth_busy");
            return false;
        }
        boolean ok;
        try {
            ok = Config.bcryptVerify(candidate, cfg.sshPasswordHash);
        } finally {
            SshGuard.releaseBcrypt();
        }
        if (!ok) {
            SshGuard.recordFailure(ip);
            SshAudit.authFail(ip, cfg.id, "bad_password");
            return false;
        }
        if (!SshGuard.tryAcquireSession(ip)) {
            SshAudit.authFail(ip, cfg.id, "session_limit");
            return false;
        }

        sessions.add(session);
        sessionStarts.put(session, System.currentTimeMillis());
        sessionIps.put(session, ip);
        sessionInstances.put(session, cfg.id);
        SshGuard.recordSuccess(ip);
        SshAudit.authOk(ip, cfg.id);
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
        public void sessionClosed(Session session) {
            releaseTracking(session);
        }
    }

    /** 仅允许交互式 shell 请求接入唯一启用实例的控制台；exec 命令一律拒绝。 */
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
            Config.InstanceConfig cfg = Config.getSshEnabledInstance();
            if (cfg == null) {
                SshAudit.bridgeRejected(peerIp(channel.getSession()), "no_enabled_instance");
                throw new IllegalStateException("SSH console is not available");
            }
            ProcessInstance target = InstanceManager.getInstance().getInstance(cfg.id);
            if (target == null) {
                SshAudit.bridgeRejected(peerIp(channel.getSession()), "instance_not_loaded");
                throw new IllegalStateException("SSH console is not available");
            }
            return new InstanceConsoleCommand(target);
        }
    }
}
