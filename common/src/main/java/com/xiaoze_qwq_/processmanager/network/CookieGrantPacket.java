package com.xiaoze_qwq_.processmanager.network;

import com.xiaoze_qwq_.processmanager.ProcessManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C：服务端向客户端下发新的 Cookie 令牌（验证成功或 verify 成功后）。
 */
public record CookieGrantPacket(String token, long expiresAt) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<CookieGrantPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ProcessManager.MOD_ID, "cookie_grant"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CookieGrantPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, CookieGrantPacket::token,
                    ByteBufCodecs.VAR_LONG, CookieGrantPacket::expiresAt,
                    CookieGrantPacket::new
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
