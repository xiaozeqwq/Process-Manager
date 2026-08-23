package com.xiaoze_qwq_.mis;

import com.xiaoze_qwq_.mis.config.MISConfig;
import com.xiaoze_qwq_.mis.logging.MCCOutputLogger;
import com.xiaoze_qwq_.mis.monitor.StatusMonitor;
import com.xiaoze_qwq_.mis.network.*;
import com.xiaoze_qwq_.mis.util.ANSITextProcessor;
import com.xiaoze_qwq_.mis.util.LogEntry;
import com.xiaoze_qwq_.mis.util.RingBuffer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
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

public class MCCInstance {
    public enum OperationState {
        IDLE,
        STARTING,
        STOPPING,
        RESTARTING,
        FORCE_STOPPING;

        public String getTranslationKey() {
            return "mis.status." + name().toLowerCase();
        }
    }

    private final String instanceId;
    private MISConfig.InstanceConfig config;
    private final Logger LOGGER = MCCInServer.LOGGER;
    private final RingBuffer<LogEntry> outputRing;
    private final Set<ServerPlayer> immersiveSubscribers = ConcurrentHashMap.newKeySet();
    private final Set<ServerPlayer> statusSubscribers = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean restartScheduled = new AtomicBoolean(false);
    private final AtomicLong currentPid = new AtomicLong(-1);
    private final ReentrantReadWriteLock processLock = new ReentrantReadWriteLock();

    private Process mccProcess;
    private BufferedWriter processWriter;
    private InputStream processInputStream;
    private ExecutorService outputReaderExecutor;
    private ScheduledExecutorService scheduler;
    private Thread waitForExitThread;
    private MCCOutputLogger outputLogger;
    private StatusMonitor statusMonitor;

    private final AtomicBoolean immersiveConsole = new AtomicBoolean(false);
    private final AtomicInteger consoleLastPrintedIndex = new AtomicInteger(0);

    private final org.apache.logging.log4j.Logger ALONE_LOGGER;

    // 当前操作状态
    private volatile OperationState operationState = OperationState.IDLE;

    public MCCInstance(String id, MISConfig.InstanceConfig config) {
        this.instanceId = id;
        this.config = config;
        this.outputRing = new RingBuffer<>(config.historyLimit);
        this.outputLogger = new MCCOutputLogger(Paths.get(config.logDirectory), config);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MIS-" + id + "-Scheduler");
            t.setDaemon(true);
            return t;
        });
        this.ALONE_LOGGER = createAloneLogger();
    }

    private org.apache.logging.log4j.Logger createAloneLogger() {
        org.apache.logging.log4j.core.LoggerContext ctx =
                (org.apache.logging.log4j.core.LoggerContext) org.apache.logging.log4j.LogManager.getContext(false);
        org.apache.logging.log4j.core.config.Configuration cfg = ctx.getConfiguration();
        org.apache.logging.log4j.core.layout.PatternLayout layout =
                org.apache.logging.log4j.core.layout.PatternLayout.newBuilder().withPattern("%m%n").build();
        org.apache.logging.log4j.core.appender.ConsoleAppender appender =
                org.apache.logging.log4j.core.appender.ConsoleAppender.newBuilder()
                        .setName("MISAloneConsole-" + instanceId)
                        .setLayout(layout)
                        .setTarget(org.apache.logging.log4j.core.appender.ConsoleAppender.Target.SYSTEM_OUT)
                        .build();
        appender.start();
        org.apache.logging.log4j.core.config.LoggerConfig loggerConfig =
                new org.apache.logging.log4j.core.config.LoggerConfig("MISAloneLogger-" + instanceId,
                        org.apache.logging.log4j.Level.INFO, false);
        loggerConfig.addAppender(appender, org.apache.logging.log4j.Level.INFO, null);
        cfg.addLogger("MISAloneLogger-" + instanceId, loggerConfig);
        ctx.updateLoggers();
        return org.apache.logging.log4j.LogManager.getLogger("MISAloneLogger-" + instanceId);
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
                LOGGER.warn("[{}] MCC already running", instanceId);
                return;
            }
            killPreviousProcess();
            outputRing.clear();
            consoleLastPrintedIndex.set(0);
            if (outputLogger != null) outputLogger.closeAndFinalize();
            outputLogger = new MCCOutputLogger(Paths.get(config.logDirectory), config);
            outputLogger.startNewLog();

            String path = config.executablePath;
            String osName = System.getProperty("os.name").toLowerCase();
            if (osName.contains("win") && !path.endsWith(".exe")) path += ".exe";

            File workDir = null;
            if (!config.workingDirectory.isEmpty()) {
                workDir = new File(config.workingDirectory);
            }

            File execFile;
            if (workDir != null) {
                execFile = new File(workDir, path);
            } else {
                execFile = new File(path);
            }

            if (!execFile.exists()) {
                LOGGER.error("[{}] Executable not found: {}", instanceId, execFile.getAbsolutePath());
                return;
            }
            if (!execFile.canExecute() && !osName.contains("win")) {
                try {
                    Runtime.getRuntime().exec("chmod +x " + execFile.getAbsolutePath());
                } catch (IOException e) {
                    LOGGER.error("[{}] chmod failed", instanceId, e);
                }
            }

            ProcessBuilder pb = new ProcessBuilder(execFile.getAbsolutePath());
            pb.directory(workDir != null ? workDir : execFile.getParentFile());
            pb.redirectErrorStream(true);
            config.environment.forEach(pb.environment()::put);

            mccProcess = pb.start();
            currentPid.set(mccProcess.pid());
            processWriter = new BufferedWriter(new OutputStreamWriter(mccProcess.getOutputStream(), StandardCharsets.UTF_8));
            processInputStream = mccProcess.getInputStream();
            running.set(true);

            outputReaderExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "MIS-" + instanceId + "-OutputReader");
                t.setDaemon(true);
                return t;
            });
            outputReaderExecutor.submit(this::readOutputLoop);

            waitForExitThread = new Thread(() -> {
                try {
                    int exitCode = mccProcess.waitFor();
                    LOGGER.info("[{}] MCC exited with code {}", instanceId, exitCode);
                    running.set(false);
                    handleProcessExit(exitCode);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "MIS-" + instanceId + "-WaitForExit");
            waitForExitThread.setDaemon(true);
            waitForExitThread.start();

            statusMonitor = new StatusMonitor(this);
            if (config.enableProcessMonitor || config.enableNetworkMonitor) {
                statusMonitor.start();
            }

            LOGGER.info("[{}] MCC process started: {}", instanceId, execFile.getAbsolutePath());
        } catch (IOException e) {
            LOGGER.error("[{}] Failed to start MCC", instanceId, e);
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
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(processInputStream, StandardCharsets.UTF_8))) {
            String line;
            while (running.get() && (line = reader.readLine()) != null) {
                long now = System.currentTimeMillis();
                String raw = line;
                String plain = ANSITextProcessor.toPlainText(raw);
                outputRing.add(new LogEntry(raw, now));
                if (outputLogger != null) outputLogger.writeLine(plain);
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
        if (exitCode == config.exitCode) {
            LOGGER.info("[{}] Expected exit code, not restarting.", instanceId);
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

    private void sendInstanceRemovedPacket() {
        MCCInServer.sendToAllPlayers(new InstanceRemovedPacket(instanceId));
    }

    private void closeLogger() {
        if (outputLogger != null) {
            try {
                outputLogger.closeAndFinalize();
            } catch (Exception e) {
                LOGGER.error("[{}] Error closing logger", instanceId, e);
            }
            outputLogger = null;
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
            stopInternal(sendExit);
        } finally {
            endOperation();
        }
    }

    private void stopInternal(boolean sendExit) {
        processLock.writeLock().lock();
        try {
            if (!running.get() && mccProcess == null) return;
            running.set(false);

            if (processInputStream != null) {
                try { processInputStream.close(); } catch (IOException ignored) {}
                processInputStream = null;
            }
            if (outputReaderExecutor != null) {
                outputReaderExecutor.shutdownNow();
                outputReaderExecutor = null;
            }

            if (sendExit && processWriter != null && mccProcess != null && mccProcess.isAlive()) {
                try {
                    processWriter.write("/exit " + config.exitCode);
                    processWriter.newLine();
                    processWriter.flush();
                    long giveUp = System.currentTimeMillis() + 5000;
                    while (mccProcess.isAlive() && System.currentTimeMillis() < giveUp) Thread.sleep(100);
                    if (mccProcess.isAlive()) mccProcess.destroy();
                } catch (Exception e) {
                    mccProcess.destroy();
                }
            } else {
                if (mccProcess != null && mccProcess.isAlive()) {
                    mccProcess.destroy();
                }
            }
            if (mccProcess != null && mccProcess.isAlive()) {
                try { mccProcess.waitFor(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                if (mccProcess.isAlive()) {
                    mccProcess.destroyForcibly();
                    try { mccProcess.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                }
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
            sendInstanceRemovedPacket();
        } finally {
            processLock.writeLock().unlock();
        }
    }

    public void restart() {
        if (!startOperation(OperationState.RESTARTING)) return;
        CompletableFuture.runAsync(() -> {
            try {
                LOGGER.info("[{}] Restarting instance...", instanceId);
                stopInternal(true);
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
        immersiveSubscribers.add(player);
        List<LogEntry> history = outputRing.getLast(outputRing.size());
        for (LogEntry entry : history) {
            MCCInServer.sendToPlayer(player, new MCCOutputPacket(instanceId, entry.message));
        }
        MCCInServer.sendToPlayer(player, new ImmersiveStatusPacket(instanceId, true, canInput));
    }

    public void removeImmersiveSubscriber(ServerPlayer player) {
        immersiveSubscribers.remove(player);
        MCCInServer.sendToPlayer(player, new ImmersiveStatusPacket(instanceId, false, false));
    }

    public boolean isSubscribed(ServerPlayer player) {
        return immersiveSubscribers.contains(player);
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
        MCCInServer.executeOnServerThread(() -> {
            for (ServerPlayer player : List.copyOf(statusSubscribers)) {
                if (player.isRemoved()) statusSubscribers.remove(player);
                else PacketDistributor.sendToPlayer(player, pkt);
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
        MCCOutputPacket pkt = new MCCOutputPacket(instanceId, raw);
        MCCInServer.executeOnServerThread(() -> {
            for (ServerPlayer player : List.copyOf(immersiveSubscribers)) {
                if (player.isRemoved()) immersiveSubscribers.remove(player);
                else PacketDistributor.sendToPlayer(player, pkt);
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
        boolean alone = config.outputAlone;
        StringBuilder sb = new StringBuilder();
        if (alone) {
            if (config.outputWithTime) {
                String time = new SimpleDateFormat(config.outputTimeFormat).format(new Date(timestamp));
                sb.append('[').append(time).append("] ");
            }
            sb.append('[').append(instanceId).append("] ").append(message);
            ALONE_LOGGER.info(sb.toString());
        } else {
            System.out.println("[" + instanceId + "] " + message);
        }
    }

    // ==================== 工具方法 ====================
    public boolean isRunning() { return running.get(); }
    public String getId() { return instanceId; }
    public MISConfig.InstanceConfig getConfig() { return config; }
    public List<String> getRecentOutput(int lines) {
        return outputRing.getLast(Math.min(lines, outputRing.size())).stream().map(e -> e.message).toList();
    }
    public Process getProcess() { return mccProcess; }
    public long getPid() { return currentPid.get(); }
    public RingBuffer<LogEntry> getOutputRing() { return outputRing; }
    public StatusMonitor getStatusMonitor() { return statusMonitor; }
    public ScheduledExecutorService getScheduler() { return scheduler; }
    public void removeDisconnectedPlayer(UUID uuid) {
        immersiveSubscribers.removeIf(p -> p.getUUID().equals(uuid));
        statusSubscribers.removeIf(p -> p.getUUID().equals(uuid));
    }

    public void resizeBuffer(int newSize) {
        outputRing.resize(newSize);
    }

    public void updateConfig(MISConfig.InstanceConfig newConfig) {
        this.config = newConfig;
        resizeBuffer(newConfig.historyLimit);
    }
}