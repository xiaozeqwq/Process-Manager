package com.xiaoze_qwq_.processmanager.ssh;

import com.xiaoze_qwq_.processmanager.ProcessInstance;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.Signal;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 将 SSH 会话桥接到某个 {@link ProcessInstance} 的控制台。
 * 不提供 shell：进程输出经此会话下发，客户端输入原样回传给进程，进程退出即结束会话。
 */
public class InstanceConsoleCommand implements Command {
    private final ProcessInstance instance;

    private InputStream in;
    private OutputStream out;
    private OutputStream err;
    private ExitCallback callback;

    private Thread inputThread;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private final ProcessInstance.ConsoleOutputListener listener = new ProcessInstance.ConsoleOutputListener() {
        @Override
        public void onConsoleOutput(byte[] data, int length) {
            write(data, length);
        }

        @Override
        public void onConsoleClosed(int exitCode) {
            finish(exitCode);
        }
    };

    public InstanceConsoleCommand(ProcessInstance instance) {
        this.instance = instance;
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
        int[] size = readSize(env);
        instance.resizeConsole(size[0], size[1]);
        env.addSignalListener((ch, signal) -> {
            if (signal == Signal.WINCH) {
                int[] s = readSize(env);
                instance.resizeConsole(s[0], s[1]);
            }
        }, Signal.WINCH);

        writeString("Connected to console of instance '" + instance.getId() + "'.\r\n");
        List<String> history = instance.getRecentOutput(100);
        for (String line : history) {
            writeString(line + "\r\n");
        }

        instance.addConsoleListener(listener);
        if (!instance.isRunning()) {
            finish(-1);
            return;
        }

        inputThread = new Thread(this::readInput, "PM-SSH-" + instance.getId() + "-Input");
        inputThread.setDaemon(true);
        inputThread.start();
    }

    private void readInput() {
        InputStream source = in;
        if (source == null) return;
        byte[] buf = new byte[1024];
        try {
            int n;
            while (!closed.get() && (n = source.read(buf)) != -1) {
                if (n > 0) {
                    byte[] data = new byte[n];
                    System.arraycopy(buf, 0, data, 0, n);
                    if (!instance.writeRawInput(data)) break;
                }
            }
        } catch (IOException ignored) {
            // 客户端断开，等待 destroy() 收尾
        }
    }

    @Override
    public void destroy(ChannelSession channel) {
        closed.set(true);
        instance.removeConsoleListener(listener);
        Thread t = inputThread;
        inputThread = null;
        if (t != null) t.interrupt();
    }

    private void finish(int exitCode) {
        if (!closed.compareAndSet(false, true)) return;
        writeString("\r\n[process exited]\r\n");
        instance.removeConsoleListener(listener);
        Thread t = inputThread;
        inputThread = null;
        if (t != null) t.interrupt();
        ExitCallback cb = callback;
        if (cb != null) {
            cb.onExit(exitCode, "process exited", true);
        }
    }

    private void writeString(String s) {
        write(s.getBytes(StandardCharsets.UTF_8), -1);
    }

    private void write(byte[] data, int length) {
        OutputStream target = out;
        if (target == null) return;
        int len = length < 0 ? data.length : length;
        try {
            target.write(data, 0, len);
            target.flush();
        } catch (IOException ignored) {
        }
    }

    private static int[] readSize(Environment env) {
        int cols = 80;
        int rows = 24;
        Map<String, String> e = env.getEnv();
        if (e != null) {
            cols = parseInt(e.get(Environment.ENV_COLUMNS), cols);
            rows = parseInt(e.get(Environment.ENV_LINES), rows);
        }
        return new int[]{cols, rows};
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isEmpty()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
