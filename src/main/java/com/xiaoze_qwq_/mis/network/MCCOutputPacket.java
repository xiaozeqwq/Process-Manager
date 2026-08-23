package com.xiaoze_qwq_.mis.network;

import com.xiaoze_qwq_.mis.MCCInServer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record MCCOutputPacket(String instanceId, String rawANSI) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<MCCOutputPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(MCCInServer.MOD_ID, "mcc_output"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MCCOutputPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, MCCOutputPacket::instanceId,
                    ByteBufCodecs.STRING_UTF8, MCCOutputPacket::rawANSI,
                    MCCOutputPacket::new
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}