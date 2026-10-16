package com.xiaoze_qwq_.processmanager.ssh;

import com.xiaoze_qwq_.processmanager.InstanceManager;
import com.xiaoze_qwq_.processmanager.ProcessInstance;
import com.xiaoze_qwq_.processmanager.ProcessManager;
import com.xiaoze_qwq_.processmanager.config.Config;
import dev.architectury.platform.Platform;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.command.CommandFactory;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.shell.ShellFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Path;

/**
 * 内嵌 SSH 服务端。仅监听回环地址，外网流量由 {@link ProtocolSniffHandler}
 * 从 Minecraft 端口嗅探后桥接进来。每个已启用 SSH 的实例拥有独立的用户名与 bcrypt 密码，
 * 登录后直接接入对应实例的控制台（不提供 shell）。
 */
public final class SshServerHost {
    private static SshServerHost instance;

    private SshServer sshd;
    private int localPort = -1;

    private SshServerHost() {
    }

    public static synchronized void start() throws IOException {
        if (instance != null) return;
        if (!Config.hasConfiguredSshInstance()) return;
        SshServerHost host = new SshServerHost();
        host.doStart();
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

    private void doStart() throws IOException {
        Path hostKey = Platform.getConfigFolder().resolve("pm_ssh_hostkey.ser");

        sshd = SshServer.setUpDefaultServer();
        sshd.setHost("127.0.0.1");
        sshd.setPort(0);
        sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(hostKey));
        sshd.setPasswordAuthenticator((username, password, session) -> authenticate(username, password));

        ConsoleCommandFactory factory = new ConsoleCommandFactory();
        sshd.setShellFactory(factory);
        sshd.setCommandFactory(factory);

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
    }

    private boolean authenticate(String username, String password) {
        if (username == null || password == null) return false;
        Config.InstanceConfig cfg = Config.findInstanceBySshUser(username);
        if (cfg == null || !cfg.sshEnabled) return false;
        try {
            return Config.verifyInstanceSshPassword(cfg.id, password);
        } catch (Throwable t) {
            return false;
        }
    }

    private void doStop() {
        SshServer server = sshd;
        sshd = null;
        if (server != null) {
            try {
                server.stop();
            } catch (Throwable t) {
                ProcessManager.LOGGER.warn("Failed to stop embedded SSH server", t);
            }
        }
    }

    /** 无论 shell 还是 exec 请求，都接入会话所属实例的控制台。 */
    private static final class ConsoleCommandFactory implements ShellFactory, CommandFactory {
        @Override
        public Command createShell(ChannelSession channel) {
            return createCommand(channel, null);
        }

        @Override
        public Command createCommand(ChannelSession channel, String command) {
            String username = channel.getSession() != null ? channel.getSession().getUsername() : null;
            Config.InstanceConfig cfg = Config.findInstanceBySshUser(username);
            if (cfg == null) {
                throw new IllegalStateException("No SSH-configured instance for user '" + username + "'");
            }
            ProcessInstance target = InstanceManager.getInstance().getInstance(cfg.id);
            if (target == null) {
                throw new IllegalStateException("Instance '" + cfg.id + "' is not loaded");
            }
            return new InstanceConsoleCommand(target);
        }
    }
}
