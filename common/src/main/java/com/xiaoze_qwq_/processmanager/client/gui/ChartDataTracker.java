package com.xiaoze_qwq_.processmanager.client.gui;

import java.util.LinkedList;

/**
 * 图表数据追踪器，用于存储历史数据点（点数上限可配置，默认 60 个点）
 */
public class ChartDataTracker {
    private int maxPoints;

    private final LinkedList<Double> downloadSpeeds = new LinkedList<>();
    private final LinkedList<Double> uploadSpeeds = new LinkedList<>();
    private final LinkedList<Double> cpuUsages = new LinkedList<>();
    private final LinkedList<Double> memoryUsages = new LinkedList<>();

    public ChartDataTracker() {
        this(60);
    }

    public ChartDataTracker(int maxPoints) {
        this.maxPoints = Math.max(10, maxPoints);
    }

    /**
     * 添加一组新的数据点
     * @param download 下载速度 (KB/s)
     * @param upload 上传速度 (KB/s)
     * @param cpu CPU 占用率 (%)
     * @param memory 内存占用 (MB)
     */
    public synchronized void addDataPoint(double download, double upload, double cpu, double memory) {
        addToQueue(downloadSpeeds, download);
        addToQueue(uploadSpeeds, upload);
        addToQueue(cpuUsages, cpu);
        addToQueue(memoryUsages, memory);
    }

    private void addToQueue(LinkedList<Double> queue, double value) {
        queue.add(value);
        while (queue.size() > maxPoints) {
            queue.removeFirst();
        }
    }

    /**
     * 调整数据点容量（扩容或缩容），保留最新数据。
     */
    public synchronized void resize(int newCapacity) {
        int cap = Math.max(10, newCapacity);
        if (cap == maxPoints) return;
        maxPoints = cap;
        while (downloadSpeeds.size() > cap) downloadSpeeds.removeFirst();
        while (uploadSpeeds.size() > cap) uploadSpeeds.removeFirst();
        while (cpuUsages.size() > cap) cpuUsages.removeFirst();
        while (memoryUsages.size() > cap) memoryUsages.removeFirst();
    }

    public synchronized int getMaxPoints() {
        return maxPoints;
    }

    public synchronized LinkedList<Double> getDownloadSpeeds() { return new LinkedList<>(downloadSpeeds); }
    public synchronized LinkedList<Double> getUploadSpeeds() { return new LinkedList<>(uploadSpeeds); }
    public synchronized LinkedList<Double> getCpuUsages() { return new LinkedList<>(cpuUsages); }
    public synchronized LinkedList<Double> getMemoryUsages() { return new LinkedList<>(memoryUsages); }
    
    public synchronized boolean isEmpty() {
        return downloadSpeeds.isEmpty();
    }
}
