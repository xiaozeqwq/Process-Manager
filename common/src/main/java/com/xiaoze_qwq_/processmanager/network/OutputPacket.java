package com.xiaoze_qwq_.processmanager.network;

import com.xiaoze_qwq_.processmanager.ProcessManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record OutputPacket(String instanceId, String rawANSI) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<OutputPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ProcessManager.MOD_ID, "output"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OutputPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, OutputPacket::instanceId,
                    ByteBufCodecs.STRING_UTF8, OutputPacket::rawANSI,
                    OutputPacket::new
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}