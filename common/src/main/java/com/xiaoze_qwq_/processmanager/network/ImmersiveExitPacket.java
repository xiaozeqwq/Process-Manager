package com.xiaoze_qwq_.processmanager.network;

import com.xiaoze_qwq_.processmanager.ProcessManager;
import com.xiaoze_qwq_.processmanager.ProcessInstance;
import com.xiaoze_qwq_.processmanager.InstanceManager;
import dev.architectury.networking.NetworkManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

public record ImmersiveExitPacket(String instanceId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ImmersiveExitPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ProcessManager.MOD_ID, "immersive_exit"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ImmersiveExitPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, ImmersiveExitPacket::instanceId,
                    ImmersiveExitPacket::new
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ImmersiveExitPacket msg, NetworkManager.PacketContext ctx) {
        ctx.queue(() -> {
            if (ctx.getPlayer() instanceof ServerPlayer player) {
                ProcessInstance inst = InstanceManager.getInstance().getInstance(msg.instanceId);
                if (inst != null && inst.isSubscribed(player)) {
                    inst.removeImmersiveSubscriber(player);
                }
            }
        });
    }
}
