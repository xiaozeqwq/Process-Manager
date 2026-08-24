package com.xiaoze_qwq_.mis.network;

import com.xiaoze_qwq_.mis.MCCInServer;
import com.xiaoze_qwq_.mis.client.gui.MCCImmersiveScreen;
import com.xiaoze_qwq_.mis.client.gui.StatusScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record InstanceRemovedPacket(String instanceId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<InstanceRemovedPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(MCCInServer.MOD_ID, "instance_removed"));

    public static final StreamCodec<RegistryFriendlyByteBuf, InstanceRemovedPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, InstanceRemovedPacket::instanceId,
                    InstanceRemovedPacket::new
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleClient(InstanceRemovedPacket msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Minecraft.getInstance().execute(() -> {
                MCCImmersiveScreen.clearInstanceLines(msg.instanceId);
                if (Minecraft.getInstance().screen instanceof StatusScreen screen && screen.getInstanceId().equals(msg.instanceId)) {
                    screen.onClose();
                }
            });
        });
    }
}