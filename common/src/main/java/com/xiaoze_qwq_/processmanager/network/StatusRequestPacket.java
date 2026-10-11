package com.xiaoze_qwq_.processmanager.network;

import com.xiaoze_qwq_.processmanager.ProcessManager;
import com.xiaoze_qwq_.processmanager.InstanceManager;
import com.xiaoze_qwq_.processmanager.SessionManager;
import dev.architectury.networking.NetworkManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

public record StatusRequestPacket(String instanceId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<StatusRequestPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ProcessManager.MOD_ID, "status_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StatusRequestPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, StatusRequestPacket::instanceId,
                    StatusRequestPacket::new
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(StatusRequestPacket msg, NetworkManager.PacketContext ctx) {
        ctx.queue(() -> {
            if (ctx.getPlayer() instanceof ServerPlayer serverPlayer) {
                if (!SessionManager.getInstance().isAuthorized(serverPlayer)) {
                    return;
                }
                var inst = InstanceManager.getInstance().getInstance(msg.instanceId);
                if (inst != null) {
                    inst.addStatusSubscriber(serverPlayer);
                }
            }
        });
    }
}
