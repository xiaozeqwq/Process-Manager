package com.xiaoze_qwq_.mis.network;

import com.xiaoze_qwq_.mis.client.ClientCookieStorage;
import com.xiaoze_qwq_.mis.client.gui.MCCImmersiveScreen;
import com.xiaoze_qwq_.mis.client.gui.StatusScreen;
import com.xiaoze_qwq_.mis.client.util.ANSITextProcessorClient;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
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

    public static void handleCookieGrant(CookieGrantPacket msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Minecraft.getInstance().execute(() -> {
                ClientCookieStorage.saveCookie(ClientCookieStorage.currentServerAddress(), msg.token(), msg.expiresAt());
            });
        });
    }

    public static void handleCookieAuthResult(CookieAuthResultPacket msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Minecraft.getInstance().execute(() -> {
                String serverAddress = ClientCookieStorage.currentServerAddress();
                if (msg.success()) {
                    msg.newCookie().ifPresent(nc -> ClientCookieStorage.saveCookie(serverAddress, nc.token(), nc.expiresAt()));
                } else {
                    ClientCookieStorage.deleteCookie(serverAddress);
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.player != null) {
                        mc.player.sendSystemMessage(Component.literal("[MIS] Auto-login failed: " + msg.message()));
                    }
                }
            });
        });
    }
}