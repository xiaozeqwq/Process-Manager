package com.xiaoze_qwq_.processmanager;

import com.xiaoze_qwq_.processmanager.config.Config;
import com.xiaoze_qwq_.processmanager.logging.OutputLogger;
import com.xiaoze_qwq_.processmanager.monitor.StatusMonitor;
import com.xiaoze_qwq_.processmanager.network.*;
import com.xiaoze_qwq_.processmanager.util.ANSITextProcessor;
import com.xiaoze_qwq_.processmanager.util.LogEntry;
import com.xiaoze_qwq_.processmanager.util.RingBuffer;
import dev.architectury.networking.NetworkManager;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
    private Config.InstanceConfig config;
    private final Logger LOGGER = ProcessManager.LOGGER;

    // 直接写操作系统 stdout，绕过 Minecraft / 加载器的日志重定向
    private static final PrintStream RAW_CONSOLE = new PrintStream(
            new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);

    private final RingBuffer<LogEntry> outputRing;
    private final Map<ServerPlayer, Boolean> immersiveSubscribers = new ConcurrentHashMap<>();
    private final Set<ServerPlayer> statusSubscribers = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean restartScheduled = new AtomicBoolean(false);
    private final AtomicLong currentPid = new AtomicLong(-1);
    private final ReentrantReadWriteLock processLock = new ReentrantReadWriteLock();

    private Process process;
    private BufferedWriter processWriter;
    private InputStream processInputStream;
    private ExecutorService outputReaderExecutor;
    private ScheduledExecutorService scheduler;
    private Thread waitForExitThread;
    private OutputLogger outputLogger;
    private StatusMonitor statusMonitor;

    /** 发送停止命令的时刻（毫秒），用于 gracefulStop 模式下判定正常退出 */
    private volatile long gracefulStopSentAt = 0L;

    private final AtomicBoolean immersiveConsole = new AtomicBoolean(false);
    private final AtomicInteger consoleLastPrintedIndex = new AtomicInteger(0);

    // 当前操作状态
    private volatile OperationState operationState = OperationState.IDLE;

    public ProcessInstance(String id, Config.InstanceConfig config) {
        this.instanceId = id;
        this.config = config;
        this.outputRing = new RingBuffer<>(config.historyLimit);
        this.outputLogger = new OutputLogger(Paths.get(config.logDirectory), config);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PM-" + id + "-Scheduler");
            t.setDaemon(true);
            return t;
        });
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
    public boolean start() {
        if (!startOperation(OperationState.STARTING)) return false;
        try {
            startInternal();
        } finally {
            endOperation();
        }
        return true;
    }

    private void startInternal() {
        processLock.writeLock().lock();
        try {
            if (running.get()) {
                LOGGER.warn("[{}] process already running", instanceId);
                return;
            }
            killPreviousProcess();
            gracefulStopSentAt = 0L;
            outputRing.clear();
            consoleLastPrintedIndex.set(0);
            if (outputLogger != null) outputLogger.closeAndFinalize();
            outputLogger = new OutputLogger(Paths.get(config.logDirectory), config);
            outputLogger.startNewLog();

            List<String> command = tokenizeCommand(config.command);
            if (command.isEmpty()) {
                LOGGER.error("[{}] No launch command configured. Set 'command' in the instance config.", instanceId);
                return;
            }

            File workDir = null;
            if (config.workingDirectory != null && !config.workingDirectory.isEmpty()) {
                workDir = new File(config.workingDirectory);
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            if (workDir != null) pb.directory(workDir);
            pb.redirectErrorStream(true);
            config.environment.forEach(pb.environment()::put);

            process = pb.start();
            currentPid.set(process.pid());
            processWriter = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            processInputStream = process.getInputStream();
            running.set(true);

            outputReaderExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "PM-" + instanceId + "-OutputReader");
                t.setDaemon(true);
                return t;
            });
            outputReaderExecutor.submit(this::readOutputLoop);

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
        } finally {
            processLock.writeLock().unlock();
        }
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
            while (running.get() && !Thread.currentThread().isInterrupted() && (line = reader.readLine()) != null) {
                long now = System.currentTimeMillis();
                String raw = line;
                String plain = ANSITextProcessor.toPlainText(raw);
                outputRing.add(new LogEntry(raw, now));
                processLock.readLock().lock();
                try {
                    if (outputLogger != null) outputLogger.writeLine(plain);
                } finally {
                    processLock.readLock().unlock();
                }
                if (immersiveConsole.get()) {
                    printToConsole(raw, now);
                }
                broadcastToSubscribers(raw);
            }
        } catch (IOException e) {
            if (running.get()) LOGGER.error("[{}] Output reader error", instanceId, e);
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
        boolean normalExit;
        if ("gracefulStop".equalsIgnoreCase(config.exitDetectionMode)) {
            long sentAt = gracefulStopSentAt;
            normalExit = sentAt > 0L
                    && (System.currentTimeMillis() - sentAt) <= config.gracefulStopTimeoutSeconds * 1000L;
            LOGGER.info("[{}] Graceful-stop exit check: normalExit={}", instanceId, normalExit);
        } else {
            normalExit = exitCode == config.exitCode;
        }
        gracefulStopSentAt = 0L;
        cleanupAfterExit();
        if (normalExit) {
            LOGGER.info("[{}] Normal exit detected, not restarting.", instanceId);
            closeLogger();
            sendInstanceRemovedPacket();
            return;
        }
        if (config.autoRestart) {
            LOGGER.info("[{}] Auto-restarting in 3s...", instanceId);
            scheduleRestart();
        } else {
            closeLogger();
            sendInstanceRemovedPacket();
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

    private void closeLogger() {
        processLock.writeLock().lock();
        try {
            if (outputLogger != null) {
                try {
                    outputLogger.closeAndFinalize();
                } catch (Exception e) {
                    LOGGER.error("[{}] Error closing logger", instanceId, e);
                }
                outputLogger = null;
            }
        } finally {
            processLock.writeLock().unlock();
        }
    }

    private void scheduleRestart() {
        if (restartScheduled.compareAndSet(false, true)) {
            scheduler.schedule(() -> {
                restartScheduled.set(false);
                if (!running.get()) start();
            }, 3, TimeUnit.SECONDS);
        }
    }

    public void stop(boolean sendExit) {
        if (!startOperation(sendExit ? OperationState.STOPPING : OperationState.FORCE_STOPPING)) return;
        try {
            stopInternal(sendExit, true);
        } finally {
            endOperation();
        }
    }

    private void stopInternal(boolean sendExit, boolean broadcastRemoved) {
        processLock.writeLock().lock();
        try {
            if (!running.get() && process == null) return;
            // 1. 先置停止标志，使输出读取循环尽快退出
            running.set(false);

            // 2. 终止进程，使输出流自然关闭
            if (sendExit && processWriter != null && process != null && process.isAlive()
                    && config.stopCommand != null && !config.stopCommand.isBlank()) {
                try {
                    gracefulStopSentAt = System.currentTimeMillis();
                    processWriter.write(config.stopCommand);
                    processWriter.newLine();
                    processWriter.flush();
                    long giveUp = System.currentTimeMillis() + 5000;
                    while (process.isAlive() && System.currentTimeMillis() < giveUp) Thread.sleep(100);
                    if (process.isAlive()) process.destroy();
                } catch (Exception e) {
                    process.destroy();
                }
            } else {
                if (process != null && process.isAlive()) {
                    process.destroy();
                }
            }
            if (process != null && process.isAlive()) {
                try { process.waitFor(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                if (process.isAlive()) {
                    process.destroyForcibly();
                    try { process.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                }
            }

            // 3. 关闭输出输入流，解除 readLine 阻塞
            if (processInputStream != null) {
                try { processInputStream.close(); } catch (IOException ignored) {}
                processInputStream = null;
            }

            // 4. 关闭输出读取线程池并等待最多 1 秒
            if (outputReaderExecutor != null) {
                outputReaderExecutor.shutdownNow();
                try { outputReaderExecutor.awaitTermination(1, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                outputReaderExecutor = null;
            }

            if (waitForExitThread != null) {
                waitForExitThread.interrupt();
                waitForExitThread = null;
            }
            if (processWriter != null) {
                try { processWriter.close(); } catch (IOException ignored) {}
                processWriter = null;
            }

            immersiveSubscribers.clear();
            statusSubscribers.clear();
            currentPid.set(-1);
            closeLogger();
            if (statusMonitor != null) statusMonitor.stop();
            if (broadcastRemoved) sendInstanceRemovedPacket();
        } finally {
            processLock.writeLock().unlock();
        }
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
        immersiveSubscribers.put(player, canInput);
        List<LogEntry> history = outputRing.getLast(Math.min(200, outputRing.size()));
        for (LogEntry entry : history) {
            ProcessManager.sendToPlayer(player, new OutputPacket(instanceId, entry.message));
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

    public void addStatusSubscriber(ServerPlayer player) {
        statusSubscribers.add(player);
    }

    public void removeStatusSubscriber(ServerPlayer player) {
        statusSubscribers.remove(player);
    }

    public void broadcastStatus() {
        if (statusSubscribers.isEmpty()) return;
        StatusDataPacket pkt = buildStatusPacket();
        ProcessManager.executeOnServerThread(() -> {
            for (ServerPlayer player : List.copyOf(statusSubscribers)) {
                if (player.isRemoved()) statusSubscribers.remove(player);
                else NetworkManager.sendToPlayer(player, pkt);
            }
        });
    }

    private StatusDataPacket buildStatusPacket() {
        boolean running = isRunning();
        String uptime = statusMonitor != null ? statusMonitor.getUptimeFormatted() : "N/A";
        double cpu = statusMonitor != null ? statusMonitor.getCpuUsage() : -1;
        long mem = statusMonitor != null ? statusMonitor.getMemoryMB() : -1;
        double down = statusMonitor != null ? statusMonitor.getDownloadSpeedKBps() : -1;
        double up = statusMonitor != null ? statusMonitor.getUploadSpeedKBps() : -1;
        long rx = statusMonitor != null ? statusMonitor.getTotalRxMB() : -1;
        long tx = statusMonitor != null ? statusMonitor.getTotalTxMB() : -1;
        return new StatusDataPacket(instanceId, running, uptime, cpu, mem, down, up, rx, tx);
    }

    private void broadcastToSubscribers(String raw) {
        if (immersiveSubscribers.isEmpty()) return;
        OutputPacket pkt = new OutputPacket(instanceId, raw);
        ProcessManager.executeOnServerThread(() -> {
            for (ServerPlayer player : List.copyOf(immersiveSubscribers.keySet())) {
                if (player.isRemoved()) immersiveSubscribers.remove(player);
                else NetworkManager.sendToPlayer(player, pkt);
            }
        });
    }

    // ==================== 控制台沉浸模式 ====================
    public void setImmersiveConsole(boolean enabled) {
        if (immersiveConsole.compareAndSet(!enabled, enabled)) {
            if (enabled) printHistoryToConsole();
        }
    }

    public boolean isImmersiveConsole() { return immersiveConsole.get(); }

    private void printHistoryToConsole() {
        CompletableFuture.runAsync(() -> {
            List<LogEntry> all = outputRing.getLast(outputRing.size());
            int last = consoleLastPrintedIndex.get();
            if (last >= all.size()) return;
            all.subList(last, all.size()).forEach(e -> printToConsole(e.message, e.timestamp));
            consoleLastPrintedIndex.set(all.size());
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
    }

    public void resizeBuffer(int newSize) {
        outputRing.resize(newSize);
    }

    public void updateConfig(Config.InstanceConfig newConfig) {
        processLock.writeLock().lock();
        try {
            this.config = newConfig;
            resizeBuffer(newConfig.historyLimit);
            OutputLogger oldLogger = outputLogger;
            if (oldLogger != null) {
                oldLogger.closeAndFinalize();
            }
            outputLogger = new OutputLogger(Paths.get(newConfig.logDirectory), newConfig);
            if (running.get()) {
                outputLogger.startNewLog();
            }
        } finally {
            processLock.writeLock().unlock();
        }
    }

    /**
     * 将启动命令字符串拆分为参数列表，支持单引号与双引号包裹含空格的参数。
     */
    static List<String> tokenizeCommand(String command) {
        List<String> tokens = new ArrayList<>();
        if (command == null) return tokens;
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        boolean tokenStarted = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (inSingle) {
                if (c == '\'') inSingle = false;
                else current.append(c);
            } else if (inDouble) {
                if (c == '"') inDouble = false;
                else if (c == '\\' && i + 1 < command.length()) current.append(command.charAt(++i));
                else current.append(c);
            } else if (c == '\'') {
                inSingle = true;
                tokenStarted = true;
            } else if (c == '"') {
                inDouble = true;
                tokenStarted = true;
            } else if (Character.isWhitespace(c)) {
                if (tokenStarted || current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    tokenStarted = false;
                }
            } else {
                current.append(c);
            }
        }
        if (tokenStarted || current.length() > 0) tokens.add(current.toString());
        return tokens;
    }
}