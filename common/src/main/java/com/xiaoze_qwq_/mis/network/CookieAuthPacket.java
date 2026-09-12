package com.xiaoze_qwq_.mis.network;

import com.xiaoze_qwq_.mis.MCCInServer;
import com.xiaoze_qwq_.mis.MISSessionManager;
import dev.architectury.networking.NetworkManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

public record CookieAuthPacket(String token, String deviceId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<CookieAuthPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(MCCInServer.MOD_ID, "cookie_auth"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CookieAuthPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, CookieAuthPacket::token,
                    ByteBufCodecs.STRING_UTF8, CookieAuthPacket::deviceId,
                    CookieAuthPacket::new
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(CookieAuthPacket msg, NetworkManager.PacketContext ctx) {
        ctx.queue(() -> {
            if (ctx.getPlayer() instanceof ServerPlayer player) {
                MISSessionManager mgr = MISSessionManager.getInstance();
                String serverAddress = MISSessionManager.serverAddressOf(player.getServer());
                MISSessionManager.CookieValidation validation = mgr.validateCookie(msg.token(), msg.deviceId(), serverAddress);
                if (validation.success()) {
                    mgr.grant(player, MISSessionManager.defaultDurationMs());
                    CookieGrantPacket newCookie = validation.needsNewToken()
                            ? new CookieGrantPacket(validation.newToken(), validation.newExpiresAt())
                            : null;
                    MCCInServer.sendToPlayer(player, new CookieAuthResultPacket(true, "auto-login success", Optional.ofNullable(newCookie)));
                } else {
                    MCCInServer.sendToPlayer(player, new CookieAuthResultPacket(false, "auto-login failed", Optional.empty()));
                }
            }
        });
    }
}
