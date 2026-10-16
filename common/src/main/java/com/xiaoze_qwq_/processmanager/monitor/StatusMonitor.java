package com.xiaoze_qwq_.processmanager.monitor;

import com.xiaoze_qwq_.processmanager.ProcessInstance;
import com.xiaoze_qwq_.processmanager.ProcessManager;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 单实例状态监控。CPU/内存按被托管进程的整个进程树统计（兼容通过启动脚本拉起的真实进程），
 * 运行时长取进程真实启动时间，网络指标来自主机级 {@link HostNetworkMonitor}（所有实例共享）。
 */
public class StatusMonitor {
    private final ProcessInstance instance;
    private final OperatingSystem os;
    private ScheduledFuture<?> monitorTask;
    private boolean initialized = false;

    private volatile double cpuUsage = -1;
    private volatile long memoryMB = -1;
    private volatile int mccPid = -1;

    private volatile long prevCpuMs = -1;
    private volatile long prevSampleMs = 0;
    private volatile long trackedPid = -1;
    private volatile long processStartMs = -1;

    public StatusMonitor(ProcessInstance instance) {
        this.instance = instance;
        OperatingSystem operatingSystem = null;
        try {
            operatingSystem = SharedSystemInfo.get().getOperatingSystem();
        } catch (Throwable t) {
            ProcessManager.LOGGER.warn("Failed to init OSHI for instance " + instance.getId(), t);
        }
        this.os = operatingSystem;
        this.initialized = operatingSystem != null;
    }

    public void start() {
        if (!initialized) return;
        if (instance.getConfig().enableNetworkMonitor) {
            HostNetworkMonitor.get().ensureStarted();
        }
        resetProcessStats();
        monitorTask = instance.getScheduler().scheduleAtFixedRate(() -> {
            try {
                if (instance.getConfig().enableProcessMonitor) updateProcessStats();
                instance.broadcastStatus();
            } catch (Throwable t) {
                ProcessManager.LOGGER.warn("Status update failed for instance " + instance.getId(), t);
            }
        }, 0, instance.getConfig().statusRefreshSeconds, TimeUnit.SECONDS);
    }

    public void stop() {
        if (monitorTask != null) monitorTask.cancel(false);
    }

    private void resetProcessStats() {
        cpuUsage = -1;
        memoryMB = -1;
        mccPid = -1;
        prevCpuMs = -1;
        prevSampleMs = 0;
        trackedPid = -1;
        processStartMs = -1;
    }

    private void updateProcessStats() {
        long pid = instance.getPid();
        if (pid <= 0) {
            resetProcessStats();
            return;
        }
        if (pid != trackedPid) {
            trackedPid = pid;
            prevCpuMs = -1;
            prevSampleMs = 0;
            processStartMs = -1;
        }

        long totalMemBytes = 0;
        long totalCpuMs = 0;
        long rootStartMs = -1;
        for (int p : collectTreePids(pid)) {
            OSProcess sp = os.getProcess(p);
            if (sp == null) continue;
            long rss = sp.getResidentSetSize();
            if (rss > 0) totalMemBytes += rss;
            totalCpuMs += Math.max(0, sp.getKernelTime()) + Math.max(0, sp.getUserTime());
            if (p == (int) pid) {
                long start = sp.getStartTime();
                if (start > 0) rootStartMs = start;
            }
        }

        long now = System.currentTimeMillis();
        if (prevCpuMs >= 0 && prevSampleMs > 0) {
            long deltaCpu = totalCpuMs - prevCpuMs;
            long deltaWall = now - prevSampleMs;
            cpuUsage = deltaWall > 0 ? Math.max(0, deltaCpu) * 100.0 / deltaWall : 0.0;
        } else {
            cpuUsage = 0.0;
        }
        prevCpuMs = totalCpuMs;
        prevSampleMs = now;

        memoryMB = totalMemBytes / (1024 * 1024);
        mccPid = (int) pid;
        if (rootStartMs > 0) processStartMs = rootStartMs;
    }

    private static List<Integer> collectTreePids(long rootPid) {
        List<Integer> pids = new ArrayList<>();
        pids.add((int) rootPid);
        ProcessHandle.of(rootPid).ifPresent(handle -> handle.descendants()
                .forEach(child -> pids.add((int) child.pid())));
        return pids;
    }

    public double getCpuUsage() { return cpuUsage; }
    public long getMemoryMB() { return memoryMB; }
    public int getMccPid() { return mccPid; }

    public double getDownloadSpeedKBps() { return HostNetworkMonitor.get().getDownloadSpeedKBps(); }
    public double getUploadSpeedKBps() { return HostNetworkMonitor.get().getUploadSpeedKBps(); }
    public long getTotalRxMB() { return HostNetworkMonitor.get().getTotalRxMB(); }
    public long getTotalTxMB() { return HostNetworkMonitor.get().getTotalTxMB(); }

    public String getUptimeFormatted() {
        if (!instance.isRunning() || processStartMs <= 0) return "N/A";
        long elapsed = Math.max(0, System.currentTimeMillis() - processStartMs);
        Duration d = Duration.ofMillis(elapsed);
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
                sb.append("Host net: ↓ ").append(String.format("%.1f", getDownloadSpeedKBps())).append(" KB/s ↑ ").append(String.format("%.1f", getUploadSpeedKBps())).append(" KB/s\n");
                sb.append("Host total: ↓ ").append(getTotalRxMB()).append(" MB ↑ ").append(getTotalTxMB()).append(" MB\n");
            }
        }
        return sb.toString();
    }
}
