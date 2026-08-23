package com.xiaoze_qwq_.mis.network;

import com.xiaoze_qwq_.mis.MCCInServer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record StatusDataPacket(String instanceId, boolean mccRunning, String mccUptime,
                               double cpuUsage, long memoryMB,
                               double downloadSpeedKBps, double uploadSpeedKBps,
                               long totalRxMB, long totalTxMB) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<StatusDataPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(MCCInServer.MOD_ID, "status_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StatusDataPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, packet) -> {
                        buf.writeUtf(packet.instanceId);
                        buf.writeBoolean(packet.mccRunning);
                        buf.writeUtf(packet.mccUptime);
                        buf.writeDouble(packet.cpuUsage);
                        buf.writeLong(packet.memoryMB);
                        buf.writeDouble(packet.downloadSpeedKBps);
                        buf.writeDouble(packet.uploadSpeedKBps);
                        buf.writeLong(packet.totalRxMB);
                        buf.writeLong(packet.totalTxMB);
                    },
                    buf -> new StatusDataPacket(
                            buf.readUtf(),
                            buf.readBoolean(),
                            buf.readUtf(),
                            buf.readDouble(),
                            buf.readLong(),
                            buf.readDouble(),
                            buf.readDouble(),
                            buf.readLong(),
                            buf.readLong()
                    )
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}