package com.xiaoze_qwq_.processmanager.network;

import com.xiaoze_qwq_.processmanager.client.ClientCookieStorage;
import com.xiaoze_qwq_.processmanager.client.gui.ImmersiveConsoleScreen;
import com.xiaoze_qwq_.processmanager.client.gui.StatusScreen;
import com.xiaoze_qwq_.processmanager.client.util.ANSITextProcessorClient;
import dev.architectury.networking.NetworkManager;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class ClientPacketHandlers {

    public static void handleOutput(OutputPacket msg, NetworkManager.PacketContext ctx) {
        ctx.queue(() -> Minecraft.getInstance().execute(() ->
                ImmersiveConsoleScreen.addLine(msg.instanceId(), ANSITextProcessorClient.toTextComponent(msg.rawANSI()))
        ));
    }

    public static void handleImmersiveStatus(ImmersiveStatusPacket msg, NetworkManager.PacketContext ctx) {
        ctx.queue(() -> {
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                if (msg.enabled()) {
                    if (mc.screen instanceof ImmersiveConsoleScreen screen) {
                        screen.switchToInstance(msg.instanceId(), msg.canInput());
                    } else {
                        mc.setScreen(new ImmersiveConsoleScreen(msg.instanceId(), msg.canInput()));
                    }
                } else {
                    if (mc.screen instanceof ImmersiveConsoleScreen screen && screen.getCurrentInstanceId().equals(msg.instanceId())) {
                        mc.screen.onClose();
                    }
                }
            });
        });
    }

    public static void handleOpenStatusScreen(OpenStatusScreenPacket msg, NetworkManager.PacketContext ctx) {
        ctx.queue(() -> Minecraft.getInstance().execute(() ->
                Minecraft.getInstance().setScreen(new StatusScreen(msg.instanceId()))
        ));
    }

    public static void handleStatusData(StatusDataPacket msg, NetworkManager.PacketContext ctx) {
        ctx.queue(() -> Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().screen instanceof StatusScreen screen && screen.getInstanceId().equals(msg.instanceId())) {
                screen.updateStatus(msg);
            }
        }));
    }

    public static void handleInstanceRemoved(InstanceRemovedPacket msg, NetworkManager.PacketContext ctx) {
        ctx.queue(() -> Minecraft.getInstance().execute(() -> {
            ImmersiveConsoleScreen.clearInstanceLines(msg.instanceId());
            if (Minecraft.getInstance().screen instanceof StatusScreen screen && screen.getInstanceId().equals(msg.instanceId())) {
                screen.onClose();
            }
        }));
    }

    public static void handleCookieGrant(CookieGrantPacket msg, NetworkManager.PacketContext ctx) {
        ctx.queue(() -> Minecraft.getInstance().execute(() ->
                ClientCookieStorage.saveCookie(ClientCookieStorage.currentServerAddress(), msg.token(), msg.expiresAt())
        ));
    }

    public static void handleCookieAuthResult(CookieAuthResultPacket msg, NetworkManager.PacketContext ctx) {
        ctx.queue(() -> Minecraft.getInstance().execute(() -> {
            String serverAddress = ClientCookieStorage.currentServerAddress();
            if (msg.success()) {
                msg.newCookie().ifPresent(nc -> ClientCookieStorage.saveCookie(serverAddress, nc.token(), nc.expiresAt()));
            } else {
                ClientCookieStorage.deleteCookie(serverAddress);
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) {
                    mc.player.sendSystemMessage(Component.literal("[PM] Auto-login failed: " + msg.message()));
                }
            }
        }));
    }
}
