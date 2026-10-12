package com.xiaoze_qwq_.processmanager.ssh;

import com.xiaoze_qwq_.processmanager.ProcessManager;
import com.xiaoze_qwq_.processmanager.ProcessInstance;
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
import java.util.List;

/**
 * 内嵌 SSH 服务端。仅监听回环地址，外网流量由 {@link ProtocolSniffHandler}
 * 从 Minecraft 端口嗅探后桥接进来。认证复用 PM 密码。
 */
public class SshServerHost {
    private final String user;
    private SshServer sshd;
    private int localPort = -1;

    public SshServerHost(String user) {
        this.user = (user == null || user.isEmpty()) ? "pm" : user;
    }

    public void start() throws IOException {
        Path hostKey = Platform.getConfigFolder().resolve("pm_ssh_hostkey.ser");

        sshd = SshServer.setUpDefaultServer();
        sshd.setHost("127.0.0.1");
        sshd.setPort(0);
        sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(hostKey));
        sshd.setPasswordAuthenticator((username, password, session) -> authenticate(username, password));

        ShellCommandFactory factory = new ShellCommandFactory();
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
        if (password == null || !user.equals(username)) return false;
        if (!Config.hasPassword()) return false;
        try {
            return Config.verifyPassword(password);
        } catch (Throwable t) {
            return false;
        }
    }

    public int getLocalPort() {
        return localPort;
    }

    public void stop() {
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

    private static final class ShellCommandFactory implements ShellFactory, CommandFactory {
        @Override
        public Command createShell(ChannelSession channel) {
            return new PtyShellCommand(defaultShellCommand(), Config.getSshWorkingDirectory());
        }

        @Override
        public Command createCommand(ChannelSession channel, String command) {
            List<String> parts = ProcessInstance.tokenizeCommand(command);
            if (parts.isEmpty()) {
                return new PtyShellCommand(defaultShellCommand(), Config.getSshWorkingDirectory());
            }
            return new PtyShellCommand(parts.toArray(new String[0]), Config.getSshWorkingDirectory());
        }

        private static String[] defaultShellCommand() {
            String configured = Config.getSshShell();
            if (configured != null && !configured.trim().isEmpty()) {
                List<String> parts = ProcessInstance.tokenizeCommand(configured);
                if (!parts.isEmpty()) {
                    return parts.toArray(new String[0]);
                }
            }
            String shell = System.getenv("SHELL");
            if (shell == null || shell.isEmpty()) {
                shell = "/bin/sh";
            }
            return new String[]{shell};
        }
    }
}
