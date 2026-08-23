package com.xiaoze_qwq_.mis.monitor;

import com.xiaoze_qwq_.mis.MCCInstance;
import com.xiaoze_qwq_.mis.MCCInServer;
import oshi.SystemInfo;
import oshi.hardware.NetworkIF;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class StatusMonitor {
    private final MCCInstance instance;
    private SystemInfo systemInfo;
    private OperatingSystem os;
    private ScheduledFuture<?> monitorTask;
    private boolean initialized = false;

    private volatile double cpuUsage = -1;
    private volatile long memoryMB = -1;
    private volatile int mccPid = -1;

    private volatile long lastTotalRx = 0;
    private volatile long lastTotalTx = 0;
    private volatile long lastTime = 0;
    private volatile double downloadSpeedKBps = 0.0;
    private volatile double uploadSpeedKBps = 0.0;
    private volatile long totalRxMB = 0;
    private volatile long totalTxMB = 0;
    private volatile Instant mccStartTime = null;

    public StatusMonitor(MCCInstance instance) {
        this.instance = instance;
        try {
            systemInfo = new SystemInfo();
            os = systemInfo.getOperatingSystem();
            initialized = true;
        } catch (Throwable t) {
            MCCInServer.LOGGER.warn("Failed to init OSHI for instance " + instance.getId(), t);
        }
    }

    public void start() {
        if (!initialized) return;
        updateNetworkStats();
        lastTime = System.currentTimeMillis();
        monitorTask = instance.getScheduler().scheduleAtFixedRate(() -> {
            if (instance.getConfig().enableProcessMonitor) updateProcessStats();
            if (instance.getConfig().enableNetworkMonitor) updateNetworkStats();
            // 每次更新后向所有状态订阅者推送最新数据
            instance.broadcastStatus();
        }, 0, instance.getConfig().statusRefreshSeconds, TimeUnit.SECONDS);
    }

    public void stop() {
        if (monitorTask != null) monitorTask.cancel(false);
    }

    private void updateProcessStats() {
        long pid = instance.getPid();
        if (pid <= 0) {
            cpuUsage = -1; memoryMB = -1; mccPid = -1;
            return;
        }
        OSProcess process = os.getProcess((int) pid);
        if (process != null) {
            cpuUsage = process.getProcessCpuLoadCumulative() * 100;
            memoryMB = process.getResidentSetSize() / (1024 * 1024);
            mccPid = (int) pid;
            if (mccStartTime == null) mccStartTime = Instant.now();
        } else {
            cpuUsage = -1; memoryMB = -1; mccPid = -1;
        }
    }

    private synchronized void updateNetworkStats() {
        try {
            List<NetworkIF> netIfs = systemInfo.getHardware().getNetworkIFs();
            long nowRx = 0, nowTx = 0;
            for (NetworkIF net : netIfs) {
                if (!net.getName().startsWith("lo") && net.getBytesRecv() > 0) {
                    nowRx += net.getBytesRecv();
                    nowTx += net.getBytesSent();
                }
            }
            long now = System.currentTimeMillis();
            long elapsedMs = now - lastTime;
            if (lastTotalRx > 0 && elapsedMs > 0) {
                downloadSpeedKBps = (nowRx - lastTotalRx) / 1024.0 / (elapsedMs / 1000.0);
                uploadSpeedKBps = (nowTx - lastTotalTx) / 1024.0 / (elapsedMs / 1000.0);
            }
            lastTotalRx = nowRx; lastTotalTx = nowTx; lastTime = now;
            totalRxMB = lastTotalRx / (1024 * 1024);
            totalTxMB = lastTotalTx / (1024 * 1024);
        } catch (Exception e) {
            MCCInServer.LOGGER.warn("Network stats update failed for " + instance.getId(), e);
        }
    }

    public double getCpuUsage() { return cpuUsage; }
    public long getMemoryMB() { return memoryMB; }
    public int getMccPid() { return mccPid; }
    public double getDownloadSpeedKBps() { return downloadSpeedKBps; }
    public double getUploadSpeedKBps() { return uploadSpeedKBps; }
    public long getTotalRxMB() { return totalRxMB; }
    public long getTotalTxMB() { return totalTxMB; }

    public String getUptimeFormatted() {
        if (mccStartTime == null || !instance.isRunning()) return "N/A";
        Duration d = Duration.between(mccStartTime, Instant.now());
        long h = d.toHours(), m = d.toMinutesPart(), s = d.toSecondsPart();
        return h > 0 ? String.format("%dh %dm %ds", h, m, s) : (m > 0 ? String.format("%dm %ds", m, s) : String.format("%ds", s));
    }

    public String getFormattedStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Instance ").append(instance.getId()).append(" Status ===\n");
        sb.append("Running: ").append(instance.isRunning()).append("\n");
        if (instance.isRunning()) {
            sb.append("Uptime: ").append(getUptimeFormatted()).append("\n");
            if (instance.getConfig().enableProcessMonitor) {
                sb.append("PID: ").append(mccPid).append("\n");
                sb.append("CPU: ").append(cpuUsage >= 0 ? String.format("%.1f%%", cpuUsage) : "N/A").append("\n");
                sb.append("Memory: ").append(memoryMB >= 0 ? memoryMB + " MB" : "N/A").append("\n");
            }
            if (instance.getConfig().enableNetworkMonitor) {
                sb.append("Net: ↓ ").append(String.format("%.1f", downloadSpeedKBps)).append(" KB/s ↑ ").append(String.format("%.1f", uploadSpeedKBps)).append(" KB/s\n");
                sb.append("Total: ↓ ").append(totalRxMB).append(" MB ↑ ").append(totalTxMB).append(" MB\n");
            }
        }
        return sb.toString();
    }
}