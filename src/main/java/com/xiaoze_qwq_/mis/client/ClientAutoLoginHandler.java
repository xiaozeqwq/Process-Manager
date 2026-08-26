package com.xiaoze_qwq_.mis.client;

import com.xiaoze_qwq_.mis.network.CookieAuthPacket;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;

/**
 * 客户端自动登录：
 * - 连接建立（LoggingIn / Clone）时生成会话 ID，断开（LoggingOut）时清空
 * - 进入世界后每个会话仅发送一次 CookieAuthPacket（携带令牌与设备标识）
 */
public class ClientAutoLoginHandler {
    /** 当前连接会话 ID，用于防止同一连接重复发送自动登录包 */
    private static UUID currentSessionId;
    /** 当前会话是否已发送过自动登录包 */
    private static boolean autoLoginSent;

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        currentSessionId = UUID.randomUUID();
        autoLoginSent = false;
    }

    @SubscribeEvent
    public static void onClone(ClientPlayerNetworkEvent.Clone event) {
        currentSessionId = UUID.randomUUID();
        autoLoginSent = false;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        currentSessionId = null;
        autoLoginSent = false;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Pre event) {
        if (currentSessionId == null || autoLoginSent) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return;
        autoLoginSent = true;
        String serverAddress = ClientCookieStorage.currentServerAddress();
        String token = ClientCookieStorage.loadCookie(serverAddress);
        if (token == null) return;
        String deviceId = ClientCookieStorage.getOrCreateDeviceId();
        PacketDistributor.sendToServer(new CookieAuthPacket(token, deviceId));
    }
}
