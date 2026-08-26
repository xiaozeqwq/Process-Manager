package com.xiaoze_qwq_.mis.client.gui;

import com.xiaoze_qwq_.mis.config.MISConfig;
import com.xiaoze_qwq_.mis.network.StatusDataPacket;
import com.xiaoze_qwq_.mis.network.StatusRequestPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.LinkedList;

public class StatusScreen extends Screen {
    private static final int REFRESH_SECONDS = 2;
    private static final int DEFAULT_CHART_MAX_POINTS = 60;
    private final String instanceId;
    private final ChartDataTracker dataTracker;
    private int lastChartMaxPoints = DEFAULT_CHART_MAX_POINTS;

    private String mccUptime = "N/A";
    private boolean mccRunning = false;
    private double currentCpu = 0;
    private long currentMemory = 0;
    private double currentDownload = 0;
    private double currentUpload = 0;
    private long totalRxMB = 0, totalTxMB = 0;

    private int scrollY = 0;
    private int totalContentHeight;
    private int visibleHeight;
    private static final int CHART_HEIGHT = 100;
    private static final int CHART_MARGIN = 20;
    private static final int CHART_WIDTH = 500;
    private int chartLeft, chartRight;
    private long lastRenderTime = 0, lastRequestTime = 0;

    public StatusScreen(String instanceId) {
        super(Component.translatable("mis.status.window_title"));
        this.instanceId = instanceId;
        this.lastChartMaxPoints = readChartMaxPoints(instanceId);
        this.dataTracker = new ChartDataTracker(lastChartMaxPoints);
    }

    /** 从实例配置读取图表点数上限，失败时回退到默认值 */
    private static int readChartMaxPoints(String instanceId) {
        try {
            MISConfig.InstanceConfig cfg = MISConfig.getInstanceConfig(instanceId);
            if (cfg != null && cfg.chartMaxPoints >= 10) {
                return cfg.chartMaxPoints;
            }
        } catch (Throwable ignored) {
        }
        return DEFAULT_CHART_MAX_POINTS;
    }

    public String getInstanceId() {
        return instanceId;
    }

    @Override
    protected void init() {
        this.addRenderableWidget(Button.builder(Component.translatable("mis.status.close_button"), b -> this.onClose())
                .bounds(this.width - 90, this.height - 25, 80, 20).build());
        requestStatus();
        chartLeft = (this.width - CHART_WIDTH) / 2;
        chartRight = chartLeft + CHART_WIDTH;
        int textHeight = 20 + 15 + 12 + 12 + 20;
        totalContentHeight = textHeight + 4 * (CHART_HEIGHT + CHART_MARGIN);
        visibleHeight = this.height - 25;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0) {
            int maxScroll = Math.max(0, totalContentHeight - visibleHeight);
            this.scrollY -= scrollY * 20;
            this.scrollY = Math.max(0, Math.min(this.scrollY, maxScroll));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void tick() {
        // 配置更新时调整图表容量（保留最新数据）
        int current = readChartMaxPoints(instanceId);
        if (current != lastChartMaxPoints) {
            lastChartMaxPoints = current;
            dataTracker.resize(current);
        }
        if (System.currentTimeMillis() - lastRequestTime >= REFRESH_SECONDS * 1000L)
            requestStatus();
    }

    private void requestStatus() {
        lastRequestTime = System.currentTimeMillis();
        if (Minecraft.getInstance().getConnection() != null)
            PacketDistributor.sendToServer(new StatusRequestPacket(instanceId));
    }

    public void updateStatus(StatusDataPacket data) {
        this.mccRunning = data.mccRunning();
        this.mccUptime = data.mccUptime();
        this.currentCpu = data.cpuUsage();
        this.currentMemory = data.memoryMB();
        this.currentDownload = data.downloadSpeedKBps();
        this.currentUpload = data.uploadSpeedKBps();
        this.totalRxMB = data.totalRxMB();
        this.totalTxMB = data.totalTxMB();
        if (mccRunning) dataTracker.addDataPoint(currentDownload, currentUpload, currentCpu, currentMemory);
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        long now = System.currentTimeMillis();
        if (now - lastRenderTime < 50 && lastRenderTime != 0) { /* skip excessive rendering */ }
        lastRenderTime = now;

        this.renderBackground(gui, mouseX, mouseY, partialTick);
        super.render(gui, mouseX, mouseY, partialTick);

        gui.enableScissor(0, 0, this.width, this.height - 25);
        int baseY = 20 - scrollY;

        int leftMargin = 20;
        gui.drawString(this.font, Component.literal("Instance: " + instanceId), leftMargin, baseY, 0xFFFFFF, false);
        baseY += 15;
        gui.drawString(this.font, Component.translatable("mis.status.uptime", mccUptime), leftMargin, baseY, 0xAAAAAA, false);
        baseY += 12;
        gui.drawString(this.font, Component.translatable("mis.status.total_traffic", totalRxMB, totalTxMB), leftMargin, baseY, 0xAAAAAA, false);
        baseY += 12;
        String line = String.format("%s: ↓%.1f KB/s ↑%.1f KB/s | %s: %.1f%% | %s: %.1f MB",
                Component.translatable("mis.status.network_speed").getString(),
                currentDownload, currentUpload,
                Component.translatable("mis.status.cpu").getString(), currentCpu,
                Component.translatable("mis.status.memory").getString(), (double) currentMemory);
        gui.drawString(this.font, line, leftMargin, baseY, 0xCCCCCC, false);

        int chartStartY = baseY + 20 + 15 + 12 + 12 + 20;
        drawChart(gui, chartStartY, dataTracker.getDownloadSpeeds(), "Download (KB/s)", 0xFF3399FF);
        drawChart(gui, chartStartY + CHART_HEIGHT + CHART_MARGIN, dataTracker.getUploadSpeeds(), "Upload (KB/s)", 0xFFFF3333);
        drawChart(gui, chartStartY + 2 * (CHART_HEIGHT + CHART_MARGIN), dataTracker.getCpuUsages(), "CPU (%)", 0xFF33FF33);
        drawChart(gui, chartStartY + 3 * (CHART_HEIGHT + CHART_MARGIN), dataTracker.getMemoryUsages(), "Memory (MB)", 0xFFFFAA33);

        gui.disableScissor();
        if (totalContentHeight > visibleHeight) {
            int barHeight = Math.max(30, visibleHeight * visibleHeight / totalContentHeight);
            int barY = (int) ((double) scrollY / (totalContentHeight - visibleHeight) * (visibleHeight - barHeight));
            gui.fill(this.width - 8, barY, this.width - 4, barY + barHeight, 0xFF888888);
        }
    }

    private void drawChart(GuiGraphics gui, int chartY, LinkedList<Double> data, String title, int color) {
        if (chartY + CHART_HEIGHT < 0 || chartY > this.height) return;
        int plotTop = chartY + 20, plotBottom = chartY + CHART_HEIGHT - 25;
        int plotLeft = chartLeft + 40, plotRight = chartRight - 20;
        double maxVal = calcMax(data);

        gui.fill(chartLeft, chartY, chartRight, chartY + 1, 0xFFAAAAAA);
        gui.fill(chartLeft, chartY + CHART_HEIGHT - 1, chartRight, chartY + CHART_HEIGHT, 0xFFAAAAAA);
        gui.fill(chartLeft, chartY, chartLeft + 1, chartY + CHART_HEIGHT, 0xFFAAAAAA);
        gui.fill(chartRight - 1, chartY, chartRight, chartY + CHART_HEIGHT, 0xFFAAAAAA);

        gui.fill(plotLeft, plotTop, plotRight, plotBottom, 0xFF2D2D2D);
        for (int i = 0; i <= 4; i++) {
            int y = plotTop + (plotBottom - plotTop) * i / 4;
            gui.fill(plotLeft, y, plotRight, y + 1, 0xFF555555);
        }
        gui.drawString(this.font, title, chartLeft + 5, chartY + 5, 0xFFFFFF, false);
        gui.drawString(this.font, String.format("%.0f", maxVal), plotLeft - 30, plotTop - 4, 0xAAAAAA, false);
        gui.drawString(this.font, "0", plotLeft - 20, plotBottom - 4, 0xAAAAAA, false);
        if (data.size() >= 2) {
            drawPolyline(gui, data, plotLeft, plotRight, plotTop, plotBottom, maxVal, color);
        } else {
            gui.drawString(this.font, Component.translatable("mis.status.chart_loading"), plotLeft + 10, plotTop + 40, 0xFF6666, false);
        }
    }

    private double calcMax(LinkedList<Double> data) {
        if (data.isEmpty()) return 100;
        double max = 0;
        for (double v : data) if (v > max) max = v;
        double ext = max * 1.2;
        int step = ext <= 200 ? 10 : 50;
        return Math.ceil(ext / step) * step;
    }

    private void drawPolyline(GuiGraphics gui, LinkedList<Double> data, int left, int right, int top, int bottom, double max, int color) {
        int n = data.size();
        int prevX = -1, prevY = -1;
        for (int i = 0; i < n; i++) {
            double val = data.get(i);
            int x = left + (right - left) * i / (n - 1);
            int y = bottom - (int) ((val / max) * (bottom - top));
            y = Math.max(top, Math.min(bottom, y));
            if (prevX != -1) drawLine(gui, prevX, prevY, x, y, color);
            gui.fill(x - 1, y - 1, x + 1, y + 1, color);
            prevX = x;
            prevY = y;
        }
    }

    private void drawLine(GuiGraphics gui, int x1, int y1, int x2, int y2, int color) {
        int dx = Math.abs(x2 - x1), dy = Math.abs(y2 - y1);
        int sx = x1 < x2 ? 1 : -1, sy = y1 < y2 ? 1 : -1;
        int err = dx - dy, x = x1, y = y1;
        while (true) {
            gui.fill(x, y, x + 1, y + 1, color);
            if (x == x2 && y == y2) break;
            int e2 = err * 2;
            if (e2 > -dy) { err -= dy; x += sx; }
            if (e2 < dx) { err += dx; y += sy; }
        }
    }
}