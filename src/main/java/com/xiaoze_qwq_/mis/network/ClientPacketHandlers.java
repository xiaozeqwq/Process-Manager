package com.xiaoze_qwq_.mis.network;

import com.xiaoze_qwq_.mis.client.gui.MCCImmersiveScreen;
import com.xiaoze_qwq_.mis.client.gui.StatusScreen;
import com.xiaoze_qwq_.mis.client.util.ANSITextProcessorClient;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public class ClientPacketHandlers {

    public static void handleMCCOutput(MCCOutputPacket msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Minecraft.getInstance().execute(() -> {
                MCCImmersiveScreen.addLine(msg.instanceId(), ANSITextProcessorClient.toTextComponent(msg.rawANSI()));
            });
        });
    }

    public static void handleImmersiveStatus(ImmersiveStatusPacket msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                if (msg.enabled()) {
                    if (mc.screen instanceof MCCImmersiveScreen screen) {
                        screen.switchToInstance(msg.instanceId(), msg.canInput());
                    } else {
                        mc.setScreen(new MCCImmersiveScreen(msg.instanceId(), msg.canInput()));
                    }
                } else {
                    if (mc.screen instanceof MCCImmersiveScreen screen && screen.getCurrentInstanceId().equals(msg.instanceId())) {
                        mc.screen.onClose();
                    }
                }
            });
        });
    }

    public static void handleOpenStatusScreen(OpenStatusScreenPacket msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Minecraft.getInstance().execute(() -> {
                Minecraft.getInstance().setScreen(new StatusScreen(msg.instanceId()));
            });
        });
    }

    public static void handleStatusData(StatusDataPacket msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Minecraft.getInstance().execute(() -> {
                if (Minecraft.getInstance().screen instanceof StatusScreen screen && screen.getInstanceId().equals(msg.instanceId())) {
                    screen.updateStatus(msg);
                }
            });
        });
    }
}