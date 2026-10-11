package com.xiaoze_qwq_.processmanager.network;

import com.xiaoze_qwq_.processmanager.ProcessManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

/**
 * S2C：自动登录结果。success 为 false 时客户端应删除本地令牌；
 * 若 newCookie 非空，客户端应保存新令牌（设备绑定或旧 token 换新场景）。
 */
public record CookieAuthResultPacket(boolean success, String message, Optional<CookieGrantPacket> newCookie) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<CookieAuthResultPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ProcessManager.MOD_ID, "cookie_auth_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CookieAuthResultPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, CookieAuthResultPacket::success,
                    ByteBufCodecs.STRING_UTF8, CookieAuthResultPacket::message,
                    CookieGrantPacket.STREAM_CODEC.apply(ByteBufCodecs::optional), CookieAuthResultPacket::newCookie,
                    CookieAuthResultPacket::new
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
