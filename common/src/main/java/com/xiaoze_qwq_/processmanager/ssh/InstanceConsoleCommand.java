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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 将 SSH 会话桥接到某个 {@link ProcessInstance} 的控制台。
 * 不提供 shell：进程输出经此会话下发，客户端输入原样回传给进程，进程退出即结束会话。
 * 输出通过有界队列交给独立写线程，避免阻塞进程输出读取线程。
 */
public class InstanceConsoleCommand implements Command {
    private static final byte[] EOF = new byte[0];
    private static final byte[] EXIT_MESSAGE = "\r\n[process exited]\r\n".getBytes(StandardCharsets.UTF_8);
    private static final int QUEUE_CAPACITY = 256;

    private final ProcessInstance instance;
    private final BlockingQueue<byte[]> outputQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final CountDownLatch writerDone = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private final AtomicBoolean destroyed = new AtomicBoolean(false);
    private final AtomicBoolean exitReported = new AtomicBoolean(false);

    private InputStream in;
    private OutputStream out;
    private OutputStream err;
    private ExitCallback callback;

    private Thread inputThread;
    private Thread writerThread;
    private volatile boolean exitRequested;
    private volatile int exitCode = -1;

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

        startWriter();
        writeString("Connected to instance " + instance.getId() + " console.\r\n");
        List<String> history = instance.getRecentOutput(100);
        for (String line : history) {
            writeString(line + "\r\n");
        }

        instance.addConsoleListener(listener);
        if (!instance.isRunning()) {
            finish(-1);
            return;
        }

        inputThread = new Thread(this::readInput, "PM-SSH-Input");
        inputThread.setDaemon(true);
        inputThread.start();
    }

    private void startWriter() {
        if (writerThread != null) return;
        writerThread = new Thread(this::writeLoop, "PM-SSH-Writer");
        writerThread.setDaemon(true);
        writerThread.start();
    }

    private void writeLoop() {
        OutputStream target = out;
        try {
            if (target == null) return;
            while (true) {
                byte[] data = outputQueue.take();
                if (data == EOF) break;
                target.write(data);
                target.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // 客户端断开，等待 destroy() 收尾
        } finally {
            writerDone.countDown();
            notifyExit();
        }
    }

    private void readInput() {
        InputStream source = in;
        if (source == null) return;
        byte[] buf = new byte[1024];
        try {
            int n;
            while (!closed.get() && (n = source.read(buf)) != -1) {
                if (n <= 0) continue;
                byte[] data = new byte[n];
                System.arraycopy(buf, 0, data, 0, n);
                if (!instance.writeRawInput(data)) {
                    finish(-1);
                    break;
                }
            }
        } catch (IOException ignored) {
            // 客户端断开，等待 destroy() 收尾
        }
    }

    @Override
    public void destroy(ChannelSession channel) {
        closed.set(true);
        destroyed.set(true);
        instance.removeConsoleListener(listener);
        interrupt(inputThread);
        if (finished.compareAndSet(false, true)) {
            enqueue(EOF);
        }
    }

    private void finish(int code) {
        if (!finished.compareAndSet(false, true)) return;
        closed.set(true);
        instance.removeConsoleListener(listener);
        interrupt(inputThread);
        exitCode = code;
        exitRequested = true;
        enqueue(EXIT_MESSAGE);
        enqueue(EOF);
        if (writerThread == null || writerDone.getCount() == 0L || !writerThread.isAlive()) {
            notifyExit();
        } else {
            startExitFallback();
        }
    }

    private void startExitFallback() {
        Thread fallback = new Thread(() -> {
            try {
                writerDone.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            notifyExit();
        }, "PM-SSH-Exit");
        fallback.setDaemon(true);
        fallback.start();
    }

    private void notifyExit() {
        if (!exitRequested) return;
        if (!exitReported.compareAndSet(false, true)) return;
        ExitCallback cb = callback;
        if (cb != null) {
            cb.onExit(exitCode, "process exited", false);
        }
    }

    private void interrupt(Thread thread) {
        if (thread != null) thread.interrupt();
    }

    private void writeString(String s) {
        write(s.getBytes(StandardCharsets.UTF_8), -1);
    }

    private void write(byte[] data, int length) {
        if (destroyed.get() || finished.get() || data == null) return;
        int len = length < 0 ? data.length : length;
        if (len <= 0) return;
        byte[] copy = new byte[len];
        System.arraycopy(data, 0, copy, 0, len);
        enqueue(copy);
    }

    private void enqueue(byte[] data) {
        if (outputQueue.offer(data)) return;
        outputQueue.poll();
        outputQueue.offer(data);
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
