package com.xiaoze_qwq_.mis.network;

import com.xiaoze_qwq_.mis.MCCInstance;
import com.xiaoze_qwq_.mis.MCCManager;
import com.xiaoze_qwq_.mis.MISSessionManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import static com.xiaoze_qwq_.mis.MCCInServer.MOD_ID;

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

    public static void handle(ImmersiveInputPacket msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) {
                MCCInstance inst = MCCManager.getInstance().getInstance(msg.instanceId);
                if (inst != null && inst.isSubscribed(player) && inst.isImmersiveInputAllowed(player)) {
                    // 仅使用密码模式检查输入权限
                    if (MISSessionManager.getInstance().isInMISMode(player)) {
                        inst.sendCommand(msg.command);
                    }
                }
            }
        });
    }
}