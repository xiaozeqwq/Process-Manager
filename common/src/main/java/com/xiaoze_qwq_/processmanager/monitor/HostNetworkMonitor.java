package com.xiaoze_qwq_.processmanager.monitor;

import com.xiaoze_qwq_.processmanager.ProcessManager;
import oshi.hardware.NetworkIF;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 主机级网络监控：周期性采样整机所有物理网卡的累计收发字节。
 * <p>
 * 操作系统无法按进程提供网络字节统计，因此本模组展示的网络速率与总流量是「主机级」共享数据，
 * 所有实例读到同一份数值。该监控在主机上只运行一份。
 */
public final class HostNetworkMonitor {
    private static final HostNetworkMonitor INSTANCE = new HostNetworkMonitor();
    private static final long SAMPLE_INTERVAL_MS = 2000L;

    public static HostNetworkMonitor get() {
        return INSTANCE;
    }

    private final ScheduledExecutorService executor;

    private List<NetworkIF> interfaces;
    private long lastRx = 0;
    private long lastTx = 0;
    private long lastTime = 0;
    private volatile boolean started = false;
    private volatile double downloadSpeedKBps = 0.0;
    private volatile double uploadSpeedKBps = 0.0;
    private volatile long totalRxMB = 0;
    private volatile long totalTxMB = 0;

    private HostNetworkMonitor() {
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PM-HostNet");
            t.setDaemon(true);
            return t;
        });
    }

    /** 懒启动采样线程（仅启动一次）。 */
    public synchronized void ensureStarted() {
        if (started) return;
        try {
            interfaces = SharedSystemInfo.get().getHardware().getNetworkIFs();
        } catch (Throwable t) {
            ProcessManager.LOGGER.warn("Failed to enumerate network interfaces for host network monitor", t);
            interfaces = List.of();
        }
        sample();
        lastTime = System.currentTimeMillis();
        started = true;
        executor.scheduleAtFixedRate(this::sample, SAMPLE_INTERVAL_MS, SAMPLE_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private synchronized void sample() {
        try {
            long nowRx = 0;
            long nowTx = 0;
            if (interfaces != null) {
                for (NetworkIF net : interfaces) {
                    net.updateAttributes();
                    if (isLoopback(net)) continue;
                    nowRx += Math.max(0, net.getBytesRecv());
                    nowTx += Math.max(0, net.getBytesSent());
                }
            }
            long now = System.currentTimeMillis();
            long elapsedMs = now - lastTime;
            if (lastTime > 0 && elapsedMs > 0) {
                downloadSpeedKBps = Math.max(0, nowRx - lastRx) / 1024.0 / (elapsedMs / 1000.0);
                uploadSpeedKBps = Math.max(0, nowTx - lastTx) / 1024.0 / (elapsedMs / 1000.0);
            }
            lastRx = nowRx;
            lastTx = nowTx;
            lastTime = now;
            totalRxMB = nowRx / (1024 * 1024);
            totalTxMB = nowTx / (1024 * 1024);
        } catch (Throwable t) {
            ProcessManager.LOGGER.warn("Host network stats update failed", t);
        }
    }

    private static boolean isLoopback(NetworkIF net) {
        String name = net.getName();
        return name != null && (name.startsWith("lo") || name.equals("lo0"));
    }

    public double getDownloadSpeedKBps() { return downloadSpeedKBps; }
    public double getUploadSpeedKBps() { return uploadSpeedKBps; }
    public long getTotalRxMB() { return totalRxMB; }
    public long getTotalTxMB() { return totalTxMB; }
}
