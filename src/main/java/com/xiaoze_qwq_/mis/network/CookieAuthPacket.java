package com.xiaoze_qwq_.mis.network;

import com.xiaoze_qwq_.mis.MCCInServer;
import com.xiaoze_qwq_.mis.MISSessionManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Optional;

/**
 * C2S：客户端自动登录请求，携带本地存储的令牌与设备标识。
 */
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

    public static void handle(CookieAuthPacket msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) {
                MISSessionManager mgr = MISSessionManager.getInstance();
                String serverAddress = MISSessionManager.serverAddressOf(player.getServer());
                MISSessionManager.CookieValidation validation = mgr.validateCookie(msg.token(), msg.deviceId(), serverAddress);
                if (validation.success()) {
                    // 自动登录成功：授予玩家 MIS 模式授权（同时刷新持久化授权）
                    mgr.grant(player, MISSessionManager.defaultDurationMs());
                    CookieGrantPacket newCookie = validation.needsNewToken()
                            ? new CookieGrantPacket(validation.newToken(), validation.newExpiresAt())
                            : null;
                    MCCInServer.sendToPlayer(player, new CookieAuthResultPacket(true, "auto-login success", Optional.ofNullable(newCookie)));
                } else {
                    MCCInServer.sendToPlayer(player, new CookieAuthResultPacket(false, "auto-login failed", null));
                }
            }
        });
    }
}
