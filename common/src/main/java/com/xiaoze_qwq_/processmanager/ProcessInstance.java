package com.xiaoze_qwq_.processmanager;

import com.xiaoze_qwq_.processmanager.config.Config;
import com.xiaoze_qwq_.processmanager.monitor.StatusMonitor;
import com.xiaoze_qwq_.processmanager.network.*;
import com.xiaoze_qwq_.processmanager.util.CommandLineTokenizer;
import com.xiaoze_qwq_.processmanager.util.LogEntry;
import com.xiaoze_qwq_.processmanager.util.RingBuffer;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import dev.architectury.networking.NetworkManager;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ProcessInstance {
    public enum OperationState {
        IDLE,
        STARTING,
        STOPPING,
        RESTARTING,
        FORCE_STOPPING;

        public String getTranslationKey() {
            return "pm.status." + name().toLowerCase();
        }
    }

    private final String instanceId;
    private volatile Config.InstanceConfig config;
    private final Logger LOGGER = ProcessManager.LOGGER;

    // 直接写操作系统 stdout，绕过 Minecraft / 加载器的日志重定向
    private static final PrintStream RAW_CONSOLE = new PrintStream(
            new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);

    private final RingBuffer<LogEntry> outputRing;
    private final Map<ServerPlayer, Boolean> immersiveSubscribers = new ConcurrentHashMap<>();
    private final Set<ServerPlayer> statusSubscribers = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean restartScheduled = new AtomicBoolean(false);
    private final AtomicBoolean manualStopRequested = new AtomicBoolean(false);
    private final AtomicLong currentPid = new AtomicLong(-1);
    private final ReentrantReadWriteLock processLock = new ReentrantReadWriteLock();

    private Process process;
    private BufferedWriter processWriter;
    private OutputStream processRawOutput;
    private InputStream processInputStream;
    private boolean ptyMode = false;
    private ExecutorService outputReaderExecutor;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> pendingRestart;
    private Thread waitForExitThread;
    private StatusMonitor statusMonitor;

    /** 发送停止命令的时刻（毫秒），用于 gracefulStop 模式下判定正常退出 */
    private volatile long gracefulStopSentAt = 0L;

    private final AtomicBoolean immersiveConsole = new AtomicBoolean(false);
    /** 进程本次运行累计输出的行数（单调递增，重启时归零）。 */
    private final AtomicLong emittedLines = new AtomicLong(0);
    /** 已打印到服务端控制台的行数，用于沉浸模式补历史时避免重复。 */
    private final AtomicLong consolePrintedLines = new AtomicLong(0);

    /** 通过 SSH 接入本实例控制台的订阅者。 */
    public interface ConsoleOutputListener {
        void onConsoleOutput(byte[] data, int length);

        void onConsoleClosed(int exitCode);
    }

    private final Set<ConsoleOutputListener> consoleListeners = ConcurrentHashMap.newKeySet();

    /** 待下发给玩家的输出行，按固定间隔合并成单个数据包发送。 */
    private static final long OUTPUT_FLUSH_INTERVAL_MS = 50L;
    private static final int OUTPUT_FLUSH_MAX_LINES = 64;
    /** 状态界面超过该时长没有请求即视为已关闭，自动退订。 */
    private static final long STATUS_SUBSCRIPTION_TIMEOUT_MS = 15_000L;
    private final Queue<String> pendingOutput = new ConcurrentLinkedQueue<>();
    private final Map<UUID, Long> statusRequestTimes = new ConcurrentHashMap<>();
    private ScheduledFuture<?> outputFlushTask;

    // 当前操作状态
    private volatile OperationState operationState = OperationState.IDLE;

    public ProcessInstance(String id, Config.InstanceConfig config) {
        this.instanceId = id;
        this.config = config;
        this.outputRing = new RingBuffer<>(config.historyLimit);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PM-" + id + "-Scheduler");
            t.setDaemon(true);
            return t;
        });
        this.outputFlushTask = scheduler.scheduleWithFixedDelay(
                this::flushPendingOutput, OUTPUT_FLUSH_INTERVAL_MS, OUTPUT_FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    // ==================== 操作状态控制 ====================
    public synchronized boolean startOperation(OperationState newState) {
        if (operationState != OperationState.IDLE) {
            return false;
        }
        operationState = newState;
        return true;
    }

    public void endOperation() {
        operationState = OperationState.IDLE;
    }

    public OperationState getOperationState() {
        return operationState;
    }

    public boolean canStartNewOperation() {
        return operationState == OperationState.IDLE;
    }

    // ==================== 进程启动/停止 ====================
    /** 启动进程；返回是否实际拉起成功（配置缺失或启动异常时返回 false）。 */
    public boolean start() {
        if (!startOperation(OperationState.STARTING)) return false;
        try {
            return startInternal();
        } finally {
            endOperation();
        }
    }

    private boolean startInternal() {
        processLock.writeLock().lock();
        try {
            if (running.get()) {
                LOGGER.warn("[{}] process already running", instanceId);
                return false;
            }
            cancelPendingRestart();
            manualStopRequested.set(false);
            killPreviousProcess();
            gracefulStopSentAt = 0L;
            outputRing.clear();
            emittedLines.set(0);
            consolePrintedLines.set(0);

            List<String> command = tokenizeCommand(config.command);
            if (command.isEmpty()) {
                LOGGER.error("[{}] No launch command configured. Set 'command' in the instance config.", instanceId);
                return false;
            }

            File workDir = null;
            if (config.workingDirectory != null && !config.workingDirectory.isEmpty()) {
                workDir = new File(config.workingDirectory);
            }

            ptyMode = config.sshEnabled;
            if (ptyMode) {
                process = startPtyProcess(command, workDir);
            } else {
                ProcessBuilder pb = new ProcessBuilder(command);
                if (workDir != null) pb.directory(workDir);
                pb.redirectErrorStream(true);
                config.environment.forEach(pb.environment()::put);
                process = pb.start();
            }
            currentPid.set(process.pid());
            processRawOutput = process.getOutputStream();
            processWriter = new BufferedWriter(new OutputStreamWriter(processRawOutput, StandardCharsets.UTF_8));
            processInputStream = process.getInputStream();
            running.set(true);

            outputReaderExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "PM-" + instanceId + "-OutputReader");
                t.setDaemon(true);
                return t;
            });
            outputReaderExecutor.submit(ptyMode ? this::readRawOutputLoop : this::readOutputLoop);

            waitForExitThread = new Thread(() -> {
                try {
                    int exitCode = process.waitFor();
                    LOGGER.info("[{}] process exited with code {}", instanceId, exitCode);
                    running.set(false);
                    handleProcessExit(exitCode);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "PM-" + instanceId + "-WaitForExit");
            waitForExitThread.setDaemon(true);
            waitForExitThread.start();

            statusMonitor = new StatusMonitor(this);
            if (config.enableProcessMonitor || config.enableNetworkMonitor) {
                statusMonitor.start();
            }

            LOGGER.info("[{}] process started: {}", instanceId, String.join(" ", command));
            return true;
        } catch (IOException e) {
            LOGGER.error("[{}] Failed to start process", instanceId, e);
            running.set(false);
            if (outputReaderExecutor != null) {
                outputReaderExecutor.shutdownNow();
                outputReaderExecutor = null;
            }
            if (processInputStream != null) {
                try { processInputStream.close(); } catch (IOException ignored) {}
                processInputStream = null;
            }
            if (processWriter != null) {
                try { processWriter.close(); } catch (IOException ignored) {}
                processWriter = null;
            }
            return false;
        } finally {
            processLock.writeLock().unlock();
        }
    }

    private Process startPtyProcess(List<String> command, File workDir) throws IOException {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.putAll(config.environment);
        env.putIfAbsent("TERM", "xterm-256color");
        PtyProcessBuilder builder = new PtyProcessBuilder(command.toArray(new String[0]));
        if (workDir != null) builder.setDirectory(workDir.getAbsolutePath());
        builder.setEnvironment(env);
        builder.setRedirectErrorStream(true);
        builder.setInitialColumns(80);
        builder.setInitialRows(24);
        return builder.start();
    }

    private void readOutputLoop() {
        // 仅在获取共享状态引用时加锁，读取完成后立即释放，避免长时间持有锁
        InputStream in;
        processLock.readLock().lock();
        try {
            if (!running.get() || processInputStream == null) return;
            in = processInputStream;
        } finally {
            processLock.readLock().unlock();
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while (!Thread.currentThread().isInterrupted() && (line = reader.readLine()) != null) {
                long now = System.currentTimeMillis();
                String raw = line;
                outputRing.add(new LogEntry(raw, now));
                emittedLines.incrementAndGet();
                if (immersiveConsole.get()) {
                    printToConsole(raw, now);
                    consolePrintedLines.incrementAndGet();
                }
                queueForSubscribers(raw);
            }
        } catch (IOException e) {
            if (running.get()) LOGGER.error("[{}] Output reader error", instanceId, e);
        }
    }

    private void readRawOutputLoop() {
        InputStream in;
        processLock.readLock().lock();
        try {
            if (!running.get() || processInputStream == null) return;
            in = processInputStream;
        } finally {
            processLock.readLock().unlock();
        }
        byte[] buf = new byte[8192];
        ByteArrayOutputStream lineBuf = new ByteArrayOutputStream(256);
        try {
            int n;
            while (!Thread.currentThread().isInterrupted() && (n = in.read(buf)) != -1) {
                if (n == 0) continue;
                notifyConsoleOutput(buf, n);
                for (int i = 0; i < n; i++) {
                    byte b = buf[i];
                    if (b == '\n') {
                        emitRawLine(lineBuf.toByteArray());
                        lineBuf.reset();
                    } else {
                        lineBuf.write(b);
                    }
                }
            }
            if (lineBuf.size() > 0) emitRawLine(lineBuf.toByteArray());
        } catch (IOException e) {
            if (running.get()) LOGGER.error("[{}] Output reader error", instanceId, e);
        }
    }

    private void emitRawLine(byte[] bytes) {
        if (bytes.length == 0) return;
        int len = bytes.length;
        if (bytes[len - 1] == '\r') len--;
        String raw = new String(bytes, 0, len, StandardCharsets.UTF_8);
        long now = System.currentTimeMillis();
        outputRing.add(new LogEntry(raw, now));
        emittedLines.incrementAndGet();
        if (immersiveConsole.get()) {
            printToConsole(raw, now);
            consolePrintedLines.incrementAndGet();
        }
        queueForSubscribers(raw);
    }

    public boolean isSshEnabled() { return config.sshEnabled; }

    public void addConsoleListener(ConsoleOutputListener listener) {
        if (listener != null) consoleListeners.add(listener);
    }

    public void removeConsoleListener(ConsoleOutputListener listener) {
        if (listener != null) consoleListeners.remove(listener);
    }

    private void notifyConsoleOutput(byte[] data, int length) {
        for (ConsoleOutputListener listener : consoleListeners) {
            try {
                listener.onConsoleOutput(data, length);
            } catch (Exception e) {
                LOGGER.warn("[{}] Console listener output error", instanceId, e);
            }
        }
    }

    public void notifyConsoleClosed(int exitCode) {
        for (ConsoleOutputListener listener : consoleListeners) {
            try {
                listener.onConsoleClosed(exitCode);
            } catch (Exception e) {
                LOGGER.warn("[{}] Console listener close error", instanceId, e);
            }
        }
        consoleListeners.clear();
    }

    public boolean writeRawInput(byte[] data) {
        OutputStream out = processRawOutput;
        if (!running.get() || out == null || data == null || data.length == 0) return false;
        try {
            out.write(data);
            out.flush();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public boolean resizeConsole(int columns, int rows) {
        if (columns <= 0 || rows <= 0) return false;
        Process p = process;
        if (!(p instanceof PtyProcess pty)) return false;
        try {
            pty.setWinSize(new WinSize(columns, rows));
            return true;
        } catch (Exception e) {
            LOGGER.warn("[{}] Failed to resize PTY", instanceId, e);
            return false;
        }
    }

    private void killPreviousProcess() {
        long pid = currentPid.get();
        if (pid > 0) {
            ProcessHandle.of(pid).ifPresent(ph -> {
                if (ph.isAlive()) {
                    ph.destroy();
                    try { ph.onExit().get(5, TimeUnit.SECONDS); } catch (Exception e) { ph.destroyForcibly(); }
                }
            });
        }
    }

    private void handleProcessExit(int exitCode) {
        awaitOutputReader();
        notifyConsoleClosed(exitCode);
        Config.InstanceConfig snapshot = config;
        boolean manualStop = manualStopRequested.get();
        boolean normalExit;
        if (manualStop) {
            normalExit = true;
            LOGGER.info("[{}] Manual stop requested, exit code {} is expected.", instanceId, exitCode);
        } else if ("gracefulStop".equalsIgnoreCase(snapshot.exitDetectionMode)) {
            long sentAt = gracefulStopSentAt;
            normalExit = sentAt > 0L
                    && (System.currentTimeMillis() - sentAt) <= snapshot.gracefulStopTimeoutSeconds * 1000L;
            LOGGER.info("[{}] Graceful-stop exit check: normalExit={}", instanceId, normalExit);
        } else {
            normalExit = exitCode == snapshot.exitCode;
        }
        gracefulStopSentAt = 0L;
        cleanupAfterExit();
        if (normalExit) {
            LOGGER.info("[{}] Normal exit detected, not restarting.", instanceId);
            sendInstanceRemovedPacket();
            return;
        }
        if (snapshot.autoRestart) {
            LOGGER.info("[{}] Auto-restarting in 3s...", instanceId);
            scheduleRestart();
        } else {
            sendInstanceRemovedPacket();
        }
    }

    private void awaitOutputReader() {
        ExecutorService reader;
        processLock.readLock().lock();
        try {
            reader = outputReaderExecutor;
        } finally {
            processLock.readLock().unlock();
        }
        if (reader == null) return;
        try {
            reader.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void cleanupAfterExit() {
        processLock.writeLock().lock();
        try {
            if (processInputStream != null) {
                try { processInputStream.close(); } catch (IOException ignored) {}
                processInputStream = null;
            }
            if (outputReaderExecutor != null) {
                outputReaderExecutor.shutdownNow();
                outputReaderExecutor = null;
            }
            if (statusMonitor != null) {
                statusMonitor.stop();
                statusMonitor = null;
            }
            if (processWriter != null) {
                try { processWriter.close(); } catch (IOException ignored) {}
                processWriter = null;
            }
            processRawOutput = null;
            ptyMode = false;
            process = null;
            waitForExitThread = null;
            currentPid.set(-1);
        } finally {
            processLock.writeLock().unlock();
        }
    }

    private void sendInstanceRemovedPacket() {
        ProcessManager.sendToAllPlayers(new InstanceRemovedPacket(instanceId));
    }

    private void scheduleRestart() {
        if (isSchedulerShutdown()) return;
        if (restartScheduled.compareAndSet(false, true)) {
            try {
                pendingRestart = scheduler.schedule(() -> {
                    restartScheduled.set(false);
                    pendingRestart = null;
                    if (!running.get() && !manualStopRequested.get() && !isSchedulerShutdown()) start();
                }, 3, TimeUnit.SECONDS);
            } catch (RejectedExecutionException e) {
                restartScheduled.set(false);
                pendingRestart = null;
                LOGGER.warn("[{}] Scheduler is shut down; auto-restart skipped.", instanceId);
            }
        }
    }

    private void cancelPendingRestart() {
        ScheduledFuture<?> pending = pendingRestart;
        pendingRestart = null;
        restartScheduled.set(false);
        if (pending != null) pending.cancel(false);
    }

    private boolean isSchedulerShutdown() {
        return scheduler.isShutdown();
    }

    public void stop(boolean sendExit) {
        if (!startOperation(sendExit ? OperationState.STOPPING : OperationState.FORCE_STOPPING)) return;
        try {
            stopInternal(sendExit, true);
        } finally {
            endOperation();
        }
    }

    /**
     * 停止进程。锁只保护状态切换与流引用，等待进程退出在锁外完成，
     * 避免长时间持锁阻塞输出读取线程导致管道写满。
     */
    private void stopInternal(boolean sendExit, boolean broadcastRemoved) {
        Process target;
        BufferedWriter writer;
        InputStream input;
        ExecutorService reader;
        Thread waitThread;
        String stopCommand;
        boolean stopCommandSent = false;
        processLock.writeLock().lock();
        try {
            if (!running.get() && process == null) return;
            running.set(false);
            manualStopRequested.set(true);
            cancelPendingRestart();
            target = process;
            writer = processWriter;
            input = processInputStream;
            reader = outputReaderExecutor;
            waitThread = waitForExitThread;
            stopCommand = config.stopCommand;
            if (sendExit && writer != null && target != null && target.isAlive()
                    && stopCommand != null && !stopCommand.isBlank()) {
                gracefulStopSentAt = System.currentTimeMillis();
                try {
                    writer.write(stopCommand);
                    writer.newLine();
                    writer.flush();
                    stopCommandSent = true;
                } catch (IOException e) {
                    LOGGER.warn("[{}] Failed to send stop command", instanceId, e);
                }
            }
        } finally {
            processLock.writeLock().unlock();
        }

        if (target != null) {
            try {
                if (!stopCommandSent) target.destroy();
                if (!target.waitFor(5, TimeUnit.SECONDS) && target.isAlive()) {
                    target.destroy();
                    if (!target.waitFor(2, TimeUnit.SECONDS) && target.isAlive()) {
                        target.destroyForcibly();
                        target.waitFor(5, TimeUnit.SECONDS);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (input != null) {
            try { input.close(); } catch (IOException ignored) {}
        }
        if (reader != null) {
            reader.shutdownNow();
            try { reader.awaitTermination(1, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        }
        if (waitThread != null) waitThread.interrupt();
        if (writer != null) {
            try { writer.close(); } catch (IOException ignored) {}
        }

        processLock.writeLock().lock();
        try {
            if (processInputStream == input) processInputStream = null;
            if (outputReaderExecutor == reader) outputReaderExecutor = null;
            if (waitForExitThread == waitThread) waitForExitThread = null;
            if (processWriter == writer) processWriter = null;
            processRawOutput = null;
            ptyMode = false;
            if (process == target) process = null;
            immersiveSubscribers.clear();
            statusSubscribers.clear();
            statusRequestTimes.clear();
            currentPid.set(-1);
        } finally {
            processLock.writeLock().unlock();
        }
        StatusMonitor monitor = statusMonitor;
        if (monitor != null) monitor.stop();
        if (broadcastRemoved) sendInstanceRemovedPacket();
    }

    public void restart() {
        if (!startOperation(OperationState.RESTARTING)) return;
        CompletableFuture.runAsync(() -> {
            try {
                LOGGER.info("[{}] Restarting instance...", instanceId);
                stopInternal(true, false);
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                startInternal();
                LOGGER.info("[{}] Restart sequence completed for instance", instanceId);
            } finally {
                endOperation();
            }
        }, scheduler);
    }

    public void forceStop() {
        stop(false);
    }

    // ==================== 命令输入 ====================
    public void sendCommand(String command) {
        processLock.readLock().lock();
        try {
            if (!running.get() || processWriter == null) return;
            processWriter.write(command);
            processWriter.newLine();
            processWriter.flush();
        } catch (IOException e) {
            LOGGER.error("[{}] Failed to send command", instanceId, e);
        } finally {
            processLock.readLock().unlock();
        }
    }

    // ==================== 输出订阅 ====================
    public void addImmersiveSubscriber(ServerPlayer player, boolean canInput) {
        if (config.sshEnabled) {
            ProcessManager.sendToPlayer(player, new ImmersiveStatusPacket(instanceId, false, false));
            return;
        }
        immersiveSubscribers.put(player, canInput);
        List<LogEntry> history = outputRing.getLast(Math.min(200, outputRing.size()));
        if (!history.isEmpty()) {
            StringBuilder batch = new StringBuilder();
            for (LogEntry entry : history) {
                if (batch.length() > 0) batch.append('\n');
                batch.append(entry.message);
            }
            ProcessManager.sendToPlayer(player, new OutputPacket(instanceId, batch.toString()));
        }
        ProcessManager.sendToPlayer(player, new ImmersiveStatusPacket(instanceId, true, canInput));
    }

    public void removeImmersiveSubscriber(ServerPlayer player) {
        immersiveSubscribers.remove(player);
        ProcessManager.sendToPlayer(player, new ImmersiveStatusPacket(instanceId, false, false));
    }

    public boolean isSubscribed(ServerPlayer player) {
        return immersiveSubscribers.containsKey(player);
    }

    public boolean isImmersiveInputAllowed(ServerPlayer player) {
        return Boolean.TRUE.equals(immersiveSubscribers.get(player));
    }

    /** 状态订阅：记录最后一次请求时间，超时无请求会被自动移除。 */
    public void addStatusSubscriber(ServerPlayer player) {
        statusRequestTimes.put(player.getUUID(), System.currentTimeMillis());
        statusSubscribers.add(player);
    }

    public void removeStatusSubscriber(ServerPlayer player) {
        statusRequestTimes.remove(player.getUUID());
        statusSubscribers.remove(player);
    }

    public void broadcastStatus() {
        long now = System.currentTimeMillis();
        if (statusSubscribers.isEmpty()) return;
        StatusDataPacket pkt = buildStatusPacket();
        ProcessManager.executeOnServerThread(() -> {
            for (ServerPlayer player : List.copyOf(statusSubscribers)) {
                Long lastRequest = statusRequestTimes.get(player.getUUID());
                if (player.isRemoved()
                        || lastRequest == null
                        || now - lastRequest > STATUS_SUBSCRIPTION_TIMEOUT_MS) {
                    removeStatusSubscriber(player);
                    continue;
                }
                NetworkManager.sendToPlayer(player, pkt);
            }
        });
    }

    private StatusDataPacket buildStatusPacket() {
        boolean running = isRunning();
        Config.InstanceConfig snapshot = config;
        StatusMonitor monitor = statusMonitor;
        String uptime = monitor != null ? monitor.getUptimeFormatted() : "N/A";
        double cpu = monitor != null ? monitor.getCpuUsage() : -1;
        long mem = monitor != null ? monitor.getMemoryMB() : -1;
        double down = monitor != null ? monitor.getDownloadSpeedKBps() : -1;
        double up = monitor != null ? monitor.getUploadSpeedKBps() : -1;
        long rx = monitor != null ? monitor.getTotalRxMB() : -1;
        long tx = monitor != null ? monitor.getTotalTxMB() : -1;
        return new StatusDataPacket(instanceId, running, uptime, cpu, mem, down, up, rx, tx, snapshot.chartMaxPoints);
    }

    /** 把一行输出放入待发送队列，由定时任务按批次合并下发。 */
    private void queueForSubscribers(String raw) {
        if (immersiveSubscribers.isEmpty()) return;
        if (pendingOutput.size() >= OUTPUT_FLUSH_MAX_LINES * 4) {
            pendingOutput.poll();
        }
        pendingOutput.add(raw);
    }

    /** 定时把积压的输出合并成一条数据包发送，最多每次 64 行。 */
    private void flushPendingOutput() {
        if (pendingOutput.isEmpty()) return;
        List<String> batch = new ArrayList<>(OUTPUT_FLUSH_MAX_LINES);
        String line;
        while (batch.size() < OUTPUT_FLUSH_MAX_LINES && (line = pendingOutput.poll()) != null) {
            batch.add(line);
        }
        if (batch.isEmpty()) return;
        String payload = String.join("\n", batch);
        OutputPacket pkt = new OutputPacket(instanceId, payload);
        ProcessManager.executeOnServerThread(() -> {
            for (ServerPlayer player : List.copyOf(immersiveSubscribers.keySet())) {
                if (player.isRemoved()) immersiveSubscribers.remove(player);
                else NetworkManager.sendToPlayer(player, pkt);
            }
        });
    }

    // ==================== 控制台沉浸模式 ====================
    public void setImmersiveConsole(boolean enabled) {
        if (config.sshEnabled) return;
        if (immersiveConsole.compareAndSet(!enabled, enabled)) {
            if (enabled) printHistoryToConsole();
        }
    }

    public boolean isImmersiveAvailable() { return !config.sshEnabled; }

    public boolean isImmersiveConsole() { return immersiveConsole.get(); }

    private void printHistoryToConsole() {
        CompletableFuture.runAsync(() -> {
            List<LogEntry> all = outputRing.getLast(outputRing.size());
            long printed = consolePrintedLines.get();
            long pending = emittedLines.get() - printed;
            if (pending <= 0 || all.isEmpty()) return;
            int from = (int) Math.max(0, all.size() - pending);
            all.subList(from, all.size()).forEach(e -> printToConsole(e.message, e.timestamp));
            consolePrintedLines.addAndGet(pending);
        });
    }

    private void printToConsole(String message, long timestamp) {
        StringBuilder sb = new StringBuilder();
        if (config.outputAlone && config.outputWithTime) {
            String time = new SimpleDateFormat(config.outputTimeFormat).format(new Date(timestamp));
            sb.append('[').append(time).append("] ");
        }
        sb.append('[').append(instanceId).append("] ").append(message);
        if (config.outputAlone) {
            RAW_CONSOLE.println(sb);
        } else {
            System.out.println(sb);
        }
    }

    // ==================== 工具方法 ====================
    public boolean isRunning() { return running.get(); }
    public String getId() { return instanceId; }
    public Config.InstanceConfig getConfig() { return config; }
    public List<String> getRecentOutput(int lines) {
        return outputRing.getLast(Math.min(lines, outputRing.size())).stream().map(e -> e.message).toList();
    }
    public Process getProcess() {
        processLock.readLock().lock();
        try {
            return process;
        } finally {
            processLock.readLock().unlock();
        }
    }
    public StatusMonitor getStatusMonitor() {
        processLock.readLock().lock();
        try {
            return statusMonitor;
        } finally {
            processLock.readLock().unlock();
        }
    }
    public long getPid() { return currentPid.get(); }
    public RingBuffer<LogEntry> getOutputRing() { return outputRing; }
    public ScheduledExecutorService getScheduler() { return scheduler; }
    public void removeDisconnectedPlayer(UUID uuid) {
        immersiveSubscribers.keySet().removeIf(p -> p.getUUID().equals(uuid));
        statusSubscribers.removeIf(p -> p.getUUID().equals(uuid));
        statusRequestTimes.remove(uuid);
    }

    public void resizeBuffer(int newSize) {
        outputRing.resize(newSize);
    }

    /** 关闭实例持有的调度器与输出读线程，供服务器停止或实例移除时调用。 */
    public void shutdown() {
        manualStopRequested.set(true);
        cancelPendingRestart();
        if (outputFlushTask != null) {
            outputFlushTask.cancel(false);
            outputFlushTask = null;
        }
        try {
            stopInternal(false, false);
        } catch (Exception e) {
            LOGGER.warn("[{}] Error while stopping instance during shutdown", instanceId, e);
        }
        pendingOutput.clear();
        immersiveSubscribers.clear();
        statusSubscribers.clear();
        statusRequestTimes.clear();
        shutdownExecutor(scheduler);
    }

    private static void shutdownExecutor(ExecutorService executor) {
        if (executor == null) return;
        executor.shutdownNow();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void updateConfig(Config.InstanceConfig newConfig) {
        processLock.writeLock().lock();
        try {
            this.config = newConfig;
            resizeBuffer(newConfig.historyLimit);
        } finally {
            processLock.writeLock().unlock();
        }
    }

    /**
     * 将启动命令字符串拆分为参数列表，支持单引号与双引号包裹含空格的参数。
     */
    public static List<String> tokenizeCommand(String command) {
        return CommandLineTokenizer.tokenize(command);
    }
}