package com.xiaoze_qwq_.processmanager.ssh;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 在真实伪终端（pty）中运行命令的 SSH {@link Command}。
 * 使用 pty4j 分配 PTY，使 TUI 程序（如 htop、vim、headlessmc 等）能够获得真正的终端语义。
 */
public class PtyShellCommand implements Command {
    private final String[] command;
    private final String workingDirectory;

    private InputStream in;
    private OutputStream out;
    private OutputStream err;
    private ExitCallback callback;
    private volatile PtyProcess process;
    private Thread inPump;
    private Thread outPump;

    public PtyShellCommand(String[] command, String workingDirectory) {
        this.command = command;
        this.workingDirectory = workingDirectory;
    }

    @Override
    public void setInputStream(InputStream in) {
        this.in = in;
    }

    @Override
    public void setOutputStream(OutputStream out) {
        this.out = out;
    }

    @Override
    public void setErrorStream(OutputStream err) {
        this.err = err;
    }

    @Override
    public void setExitCallback(ExitCallback callback) {
        this.callback = callback;
    }

    @Override
    public void start(ChannelSession channel, Environment env) throws IOException {
        Map<String, String> environment = new HashMap<>(env.getEnv());
        String term = environment.get(Environment.ENV_TERM);
        if (term == null || term.isEmpty()) {
            environment.put(Environment.ENV_TERM, "xterm-256color");
        }
        int cols = parseInt(environment.get(Environment.ENV_COLUMNS), 80);
        int rows = parseInt(environment.get(Environment.ENV_LINES), 24);

        PtyProcessBuilder builder = new PtyProcessBuilder(command)
                .setEnvironment(environment)
                .setInitialColumns(cols)
                .setInitialRows(rows)
                .setRedirectErrorStream(true);
        if (workingDirectory != null && !workingDirectory.isEmpty()) {
            builder.setDirectory(workingDirectory);
        }

        process = builder.start();
        OutputStream stdin = process.getOutputStream();
        InputStream stdout = process.getInputStream();

        outPump = new Thread(() -> {
            transfer(stdout, out);
            int exit = -1;
            try {
                exit = process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (callback != null) {
                callback.onExit(exit);
            }
        }, "PM-SSH-Out");
        outPump.setDaemon(true);
        outPump.start();

        inPump = new Thread(() -> transfer(in, stdin), "PM-SSH-In");
        inPump.setDaemon(true);
        inPump.start();
    }

    @Override
    public void destroy(ChannelSession channel) {
        PtyProcess p = process;
        if (p != null) {
            p.destroy();
        }
        if (inPump != null) inPump.interrupt();
        if (outPump != null) outPump.interrupt();
    }

    private static void transfer(InputStream in, OutputStream out) {
        if (in == null || out == null) return;
        byte[] buffer = new byte[8192];
        try {
            int n;
            while ((n = in.read(buffer)) >= 0) {
                out.write(buffer, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {
        }
    }

    private static int parseInt(String value, int fallback) {
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
