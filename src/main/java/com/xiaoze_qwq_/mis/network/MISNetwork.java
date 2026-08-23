package com.xiaoze_qwq_.mis.network;

import com.xiaoze_qwq_.mis.MCCInServer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public class MISNetwork {
    private static final String PROTOCOL_VERSION = "1";

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(MCCInServer.MOD_ID)
                .versioned(PROTOCOL_VERSION)
                .optional();

        // C2S 包：服务端和客户端都会注册，但不会加载客户端类
        registrar.playToServer(
                StatusRequestPacket.TYPE,
                StatusRequestPacket.STREAM_CODEC,
                StatusRequestPacket::handle
        );
        registrar.playToServer(
                ImmersiveInputPacket.TYPE,
                ImmersiveInputPacket.STREAM_CODEC,
                ImmersiveInputPacket::handle
        );
        registrar.playToServer(
                ImmersiveExitPacket.TYPE,
                ImmersiveExitPacket.STREAM_CODEC,
                ImmersiveExitPacket::handle
        );

        // S2C 包：仅客户端环境注册，避免服务端加载 Screen/Minecraft 等客户端类
        if (FMLEnvironment.dist == Dist.CLIENT) {
            registerClientPackets(registrar);
        }
    }

    private static void registerClientPackets(PayloadRegistrar registrar) {
        registrar.playToClient(
                MCCOutputPacket.TYPE,
                MCCOutputPacket.STREAM_CODEC,
                ClientPacketHandlers::handleMCCOutput
        );
        registrar.playToClient(
                ImmersiveStatusPacket.TYPE,
                ImmersiveStatusPacket.STREAM_CODEC,
                ClientPacketHandlers::handleImmersiveStatus
        );
        registrar.playToClient(
                OpenStatusScreenPacket.TYPE,
                OpenStatusScreenPacket.STREAM_CODEC,
                ClientPacketHandlers::handleOpenStatusScreen
        );
        registrar.playToClient(
                StatusDataPacket.TYPE,
                StatusDataPacket.STREAM_CODEC,
                ClientPacketHandlers::handleStatusData
        );
        registrar.playToClient(
                InstanceRemovedPacket.TYPE,
                InstanceRemovedPacket.STREAM_CODEC,
                InstanceRemovedPacket::handleClient
        );
    }

    // 保留兼容，实际已无调用
    public static void commonSetup(RegisterPayloadHandlersEvent event) {
    }
}