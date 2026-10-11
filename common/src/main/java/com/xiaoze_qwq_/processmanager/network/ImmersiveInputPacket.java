package com.xiaoze_qwq_.processmanager.network;

import com.xiaoze_qwq_.processmanager.ProcessInstance;
import com.xiaoze_qwq_.processmanager.InstanceManager;
import com.xiaoze_qwq_.processmanager.SessionManager;
import dev.architectury.networking.NetworkManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import static com.xiaoze_qwq_.processmanager.ProcessManager.MOD_ID;

public record ImmersiveInputPacket(String instanceId, String command) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ImmersiveInputPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(MOD_ID, "immersive_input"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ImmersiveInputPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, ImmersiveInputPacket::instanceId,
                    ByteBufCodecs.STRING_UTF8, ImmersiveInputPacket::command,
                    ImmersiveInputPacket::new
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ImmersiveInputPacket msg, NetworkManager.PacketContext ctx) {
        ctx.queue(() -> {
            if (ctx.getPlayer() instanceof ServerPlayer player) {
                ProcessInstance inst = InstanceManager.getInstance().getInstance(msg.instanceId);
                if (inst != null && inst.isSubscribed(player) && inst.isImmersiveInputAllowed(player)) {
                    if (SessionManager.getInstance().isAuthorized(player)) {
                        inst.sendCommand(msg.command);
                    }
                }
            }
        });
    }
}
