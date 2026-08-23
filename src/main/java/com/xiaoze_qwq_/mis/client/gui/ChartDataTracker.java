package com.xiaoze_qwq_.mis.client.gui;

import java.util.LinkedList;

/**
 * 图表数据追踪器，用于存储历史数据点（最多 60 个点）
 */
public class ChartDataTracker {
    private static final int MAX_POINTS = 60;
    
    private final LinkedList<Double> downloadSpeeds = new LinkedList<>();
    private final LinkedList<Double> uploadSpeeds = new LinkedList<>();
    private final LinkedList<Double> cpuUsages = new LinkedList<>();
    private final LinkedList<Double> memoryUsages = new LinkedList<>();

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
        if (queue.size() > MAX_POINTS) {
            queue.removeFirst();
        }
    }

    public synchronized LinkedList<Double> getDownloadSpeeds() { return new LinkedList<>(downloadSpeeds); }
    public synchronized LinkedList<Double> getUploadSpeeds() { return new LinkedList<>(uploadSpeeds); }
    public synchronized LinkedList<Double> getCpuUsages() { return new LinkedList<>(cpuUsages); }
    public synchronized LinkedList<Double> getMemoryUsages() { return new LinkedList<>(memoryUsages); }
    
    public synchronized boolean isEmpty() {
        return downloadSpeeds.isEmpty();
    }
}