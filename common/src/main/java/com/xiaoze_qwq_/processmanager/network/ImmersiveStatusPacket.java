package com.xiaoze_qwq_.processmanager.network;

import com.xiaoze_qwq_.processmanager.ProcessManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ImmersiveStatusPacket(String instanceId, boolean enabled, boolean canInput) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ImmersiveStatusPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ProcessManager.MOD_ID, "immersive_status"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ImmersiveStatusPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, ImmersiveStatusPacket::instanceId,
                    ByteBufCodecs.BOOL, ImmersiveStatusPacket::enabled,
                    ByteBufCodecs.BOOL, ImmersiveStatusPacket::canInput,
                    ImmersiveStatusPacket::new
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}