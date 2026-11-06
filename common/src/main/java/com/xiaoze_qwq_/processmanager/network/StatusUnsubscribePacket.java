package com.xiaoze_qwq_.processmanager.network;

import com.xiaoze_qwq_.processmanager.ProcessManager;
import com.xiaoze_qwq_.processmanager.InstanceManager;
import com.xiaoze_qwq_.processmanager.ProcessInstance;
import dev.architectury.networking.NetworkManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/** C2S：客户端关闭状态界面时停止接收状态推送。 */
public record StatusUnsubscribePacket(String instanceId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<StatusUnsubscribePacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ProcessManager.MOD_ID, "status_unsubscribe"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StatusUnsubscribePacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, StatusUnsubscribePacket::instanceId,
                    StatusUnsubscribePacket::new
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(StatusUnsubscribePacket msg, NetworkManager.PacketContext ctx) {
        ctx.queue(() -> {
            if (ctx.getPlayer() instanceof ServerPlayer player) {
                ProcessInstance inst = InstanceManager.getInstance().getInstance(msg.instanceId);
                if (inst != null) {
                    inst.removeStatusSubscriber(player);
                }
            }
        });
    }}
