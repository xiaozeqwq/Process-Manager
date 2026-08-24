package com.xiaoze_qwq_.mis.client.gui;

import com.xiaoze_qwq_.mis.network.ImmersiveExitPacket;
import com.xiaoze_qwq_.mis.network.ImmersiveInputPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MCCImmersiveScreen extends Screen {
    private static final Map<String, List<Component>> instanceLines = new ConcurrentHashMap<>();
    private static final int MAX_LINES = 1000;

    private String currentInstanceId;
    private boolean canInput;
    private EditBox inputField;
    private int scrollOffset = 0;
    private int textAreaTop, textAreaBottom;
    private int lineHeight;
    private boolean isDraggingScrollbar = false;
    private boolean initialized = false;

    private List<String> availableInstances = new ArrayList<>();

    public MCCImmersiveScreen(String instanceId, boolean canInput) {
        super(Component.translatable("mis.immersive.window_title"));
        this.currentInstanceId = instanceId;
        this.canInput = canInput;
        instanceLines.putIfAbsent(instanceId, Collections.synchronizedList(new ArrayList<>()));
        instanceLines.keySet().forEach(id -> { if (!id.equals(instanceId)) availableInstances.add(id); });
    }

    public static void addLine(String instanceId, Component line) {
        List<Component> lines = instanceLines.computeIfAbsent(instanceId, k -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (lines) {
            lines.add(line);
            while (lines.size() > MAX_LINES) lines.remove(0);
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof MCCImmersiveScreen screen && screen.currentInstanceId.equals(instanceId)) {
            screen.updateScrollIfAtBottom();
        }
    }

    public static void clearInstanceLines(String instanceId) {
        instanceLines.remove(instanceId);
    }

    public String getCurrentInstanceId() { return currentInstanceId; }

    public void switchToInstance(String instanceId, boolean canInput) {
        if (!this.currentInstanceId.equals(instanceId)) {
            this.currentInstanceId = instanceId;
            this.canInput = canInput;
            this.scrollOffset = 0;
            instanceLines.putIfAbsent(instanceId, Collections.synchronizedList(new ArrayList<>()));
            if (inputField != null) {
                inputField.setEditable(canInput);
                if (canInput) inputField.setFocused(true);
            }
            availableInstances.clear();
            instanceLines.keySet().forEach(id -> { if (!id.equals(instanceId)) availableInstances.add(id); });
        } else {
            this.canInput = canInput;
            if (inputField != null) {
                inputField.setEditable(canInput);
            }
        }
    }

    @Override
    protected void init() {
        lineHeight = this.font.lineHeight + 2;
        int bw = 100, bh = 20;
        this.addRenderableWidget(Button.builder(Component.translatable("mis.immersive.close_button"), b -> onClose())
                .bounds(this.width - bw - 10, this.height - bh - 10, bw, bh).build());

        if (canInput) {
            int inputHeight = 20;
            int inputY = this.height - inputHeight - 10;
            inputField = new EditBox(this.font, 10, inputY, this.width - 20, inputHeight, Component.literal(""));
            inputField.setMaxLength(256);
            inputField.setEditable(true);
            inputField.setFocused(true);
            this.addRenderableWidget(inputField);
            this.setInitialFocus(inputField);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER && canInput && inputField != null && inputField.isFocused()) {
            String command = inputField.getValue();
            if (!command.isEmpty()) {
                PacketDistributor.sendToServer(new ImmersiveInputPacket(currentInstanceId, command));
                inputField.setValue("");
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(gui, mouseX, mouseY, partialTick);
        super.render(gui, mouseX, mouseY, partialTick);

        textAreaTop = 10;
        int inputAreaHeight = canInput ? 30 : 10;
        textAreaBottom = this.height - 40 - inputAreaHeight;
        lineHeight = this.font.lineHeight + 2;
        initialized = true;
        int leftMargin = 10, rightMargin = this.width - 10;

        gui.fill(leftMargin - 2, textAreaTop - 2, rightMargin + 2, textAreaTop - 1, 0xFFAAAAAA);
        gui.fill(leftMargin - 2, textAreaBottom + 1, rightMargin + 2, textAreaBottom + 2, 0xFFAAAAAA);
        gui.fill(leftMargin - 2, textAreaTop - 2, leftMargin - 1, textAreaBottom + 2, 0xFFAAAAAA);
        gui.fill(rightMargin + 1, textAreaTop - 2, rightMargin + 2, textAreaBottom + 2, 0xFFAAAAAA);

        List<Component> lines = instanceLines.getOrDefault(currentInstanceId, Collections.emptyList());
        int visibleLines = (textAreaBottom - textAreaTop) / lineHeight;
        int totalLines;
        int maxScroll;
        int start, end;
        List<Component> toDraw;
        synchronized (lines) {
            totalLines = lines.size();
            maxScroll = Math.max(0, totalLines - visibleLines);
            scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
            start = Math.max(0, totalLines - visibleLines - scrollOffset);
            end = Math.min(totalLines, start + visibleLines + 1);
            toDraw = new ArrayList<>(lines.subList(start, end));
        }

        int y = textAreaTop;
        for (Component c : toDraw) {
            gui.drawString(this.font, c, leftMargin, y, 0xFFFFFF, false);
            y += lineHeight;
        }

        if (totalLines > visibleLines) {
            int barWidth = 8;
            int barX = this.width - barWidth - 4;
            int trackHeight = textAreaBottom - textAreaTop;
            int barHeight = Math.max(20, (int)((float)visibleLines / totalLines * trackHeight));
            int barPos = (int)((float)scrollOffset / maxScroll * (trackHeight - barHeight));
            barPos = Math.max(0, Math.min(barPos, trackHeight - barHeight));
            gui.fill(barX, textAreaTop, barX + barWidth, textAreaBottom, 0x44000000);
            gui.fill(barX, textAreaTop + barPos, barX + barWidth, textAreaTop + barPos + barHeight, 0xFFAAAAAA);
        }

        if (canInput) {
            gui.drawString(this.font, "> ", 10, textAreaBottom + 5, 0xAAAAAA, false);
        }
    }

    private void updateScrollIfAtBottom() {
        if (!initialized) return;
        if (isAtBottom()) scrollToBottom();
    }

    private boolean isAtBottom() {
        if (!initialized) return true;
        List<Component> lines = instanceLines.getOrDefault(currentInstanceId, Collections.emptyList());
        int visible = (textAreaBottom - textAreaTop) / lineHeight;
        if (visible <= 0) return true;
        int max = Math.max(0, lines.size() - visible);
        return scrollOffset >= max;
    }

    private void scrollToBottom() {
        if (!initialized) return;
        List<Component> lines = instanceLines.getOrDefault(currentInstanceId, Collections.emptyList());
        int visible = (textAreaBottom - textAreaTop) / lineHeight;
        scrollOffset = Math.max(0, lines.size() - visible);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && isOverScrollbar(mouseX, mouseY)) {
            isDraggingScrollbar = true;
            updateScrollFromMouseY(mouseY);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) isDraggingScrollbar = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (isDraggingScrollbar) {
            updateScrollFromMouseY(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    private boolean isOverScrollbar(double mouseX, double mouseY) {
        if (!initialized || textAreaBottom <= textAreaTop) return false;
        List<Component> lines = instanceLines.getOrDefault(currentInstanceId, Collections.emptyList());
        int visible = (textAreaBottom - textAreaTop) / lineHeight;
        if (visible <= 0 || lines.size() <= visible) return false;
        int barWidth = 8;
        int barX = this.width - barWidth - 4;
        return mouseX >= barX && mouseX <= barX + barWidth && mouseY >= textAreaTop && mouseY <= textAreaBottom;
    }

    private void updateScrollFromMouseY(double mouseY) {
        if (!initialized || textAreaBottom <= textAreaTop) return;
        List<Component> lines = instanceLines.getOrDefault(currentInstanceId, Collections.emptyList());
        int visible = (textAreaBottom - textAreaTop) / lineHeight;
        int totalLines = lines.size();
        if (visible <= 0 || totalLines <= visible) return;
        int maxScroll = Math.max(0, totalLines - visible);
        int trackHeight = textAreaBottom - textAreaTop;
        int barHeight = Math.max(20, (int)((float)visible / totalLines * trackHeight));
        double usable = trackHeight - barHeight;
        if (usable <= 0) return;
        double percent = Math.max(0, Math.min(1, (mouseY - textAreaTop) / usable));
        scrollOffset = (int)(percent * maxScroll);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
    }

    @Override
    public void onClose() {
        if (Minecraft.getInstance().getConnection() != null) {
            PacketDistributor.sendToServer(new ImmersiveExitPacket(currentInstanceId));
        }
        super.onClose();
    }
}