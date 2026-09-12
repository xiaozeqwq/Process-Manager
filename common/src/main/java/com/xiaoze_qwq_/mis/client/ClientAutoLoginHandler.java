package com.xiaoze_qwq_.mis.client;

import com.xiaoze_qwq_.mis.network.CookieAuthPacket;
import dev.architectury.event.events.client.ClientPlayerEvent;
import dev.architectury.event.events.client.ClientTickEvent;
import dev.architectury.networking.NetworkManager;
import net.minecraft.client.Minecraft;

import java.util.UUID;

public class ClientAutoLoginHandler {
    private static UUID currentSessionId;
    private static boolean autoLoginSent;

    public static void register() {
        ClientPlayerEvent.CLIENT_PLAYER_JOIN.register(player -> {
            currentSessionId = UUID.randomUUID();
            autoLoginSent = false;
        });
        ClientPlayerEvent.CLIENT_PLAYER_RESPAWN.register((oldPlayer, newPlayer) -> {
            currentSessionId = UUID.randomUUID();
            autoLoginSent = false;
        });
        ClientPlayerEvent.CLIENT_PLAYER_QUIT.register(player -> {
            currentSessionId = null;
            autoLoginSent = false;
        });
        ClientTickEvent.CLIENT_PRE.register(instance -> onClientTick());
    }

    private static void onClientTick() {
        if (currentSessionId == null || autoLoginSent) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return;
        autoLoginSent = true;
        String serverAddress = ClientCookieStorage.currentServerAddress();
        String token = ClientCookieStorage.loadCookie(serverAddress);
        if (token == null) return;
        String deviceId = ClientCookieStorage.getOrCreateDeviceId();
        NetworkManager.sendToServer(new CookieAuthPacket(token, deviceId));
    }
}
