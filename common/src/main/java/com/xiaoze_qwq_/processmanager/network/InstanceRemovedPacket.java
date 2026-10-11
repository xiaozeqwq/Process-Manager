package com.xiaoze_qwq_.processmanager.network;

import com.xiaoze_qwq_.processmanager.ProcessManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record InstanceRemovedPacket(String instanceId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<InstanceRemovedPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ProcessManager.MOD_ID, "instance_removed"));

    public static final StreamCodec<RegistryFriendlyByteBuf, InstanceRemovedPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, InstanceRemovedPacket::instanceId,
                    InstanceRemovedPacket::new
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
