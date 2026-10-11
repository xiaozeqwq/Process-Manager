package com.xiaoze_qwq_.processmanager.network;

import com.xiaoze_qwq_.processmanager.ProcessManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record StatusDataPacket(String instanceId, boolean mccRunning, String uptime,
                               double cpuUsage, long memoryMB,
                               double downloadSpeedKBps, double uploadSpeedKBps,
                               long totalRxMB, long totalTxMB) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<StatusDataPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ProcessManager.MOD_ID, "status_data"));

    public record SubStats(long memoryMB, double downloadSpeedKBps, double uploadSpeedKBps,
                           long totalRxMB, long totalTxMB) {
    }

    private static final StreamCodec<RegistryFriendlyByteBuf, SubStats> SUB_STATS_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_LONG, SubStats::memoryMB,
                    ByteBufCodecs.DOUBLE, SubStats::downloadSpeedKBps,
                    ByteBufCodecs.DOUBLE, SubStats::uploadSpeedKBps,
                    ByteBufCodecs.VAR_LONG, SubStats::totalRxMB,
                    ByteBufCodecs.VAR_LONG, SubStats::totalTxMB,
                    SubStats::new
            );

    public static final StreamCodec<RegistryFriendlyByteBuf, StatusDataPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, StatusDataPacket::instanceId,
                    ByteBufCodecs.BOOL, StatusDataPacket::mccRunning,
                    ByteBufCodecs.STRING_UTF8, StatusDataPacket::uptime,
                    ByteBufCodecs.DOUBLE, StatusDataPacket::cpuUsage,
                    SUB_STATS_CODEC, StatusDataPacket::toSubStats,
                    StatusDataPacket::new
            );

    private SubStats toSubStats() {
        return new SubStats(memoryMB, downloadSpeedKBps, uploadSpeedKBps, totalRxMB, totalTxMB);
    }

    public StatusDataPacket(String instanceId, boolean mccRunning, String uptime, double cpuUsage, SubStats sub) {
        this(instanceId, mccRunning, uptime, cpuUsage, sub.memoryMB(),
                sub.downloadSpeedKBps(), sub.uploadSpeedKBps(), sub.totalRxMB(), sub.totalTxMB());
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
