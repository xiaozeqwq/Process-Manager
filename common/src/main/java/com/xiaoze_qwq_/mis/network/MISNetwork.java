package com.xiaoze_qwq_.mis.network;

import dev.architectury.networking.NetworkManager;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import dev.architectury.utils.EnvExecutor;

public class MISNetwork {
    public static void register() {
        NetworkManager.registerReceiver(
                NetworkManager.c2s(),
                StatusRequestPacket.TYPE,
                StatusRequestPacket.STREAM_CODEC,
                StatusRequestPacket::handle
        );
        NetworkManager.registerReceiver(
                NetworkManager.c2s(),
                ImmersiveInputPacket.TYPE,
                ImmersiveInputPacket.STREAM_CODEC,
                ImmersiveInputPacket::handle
        );
        NetworkManager.registerReceiver(
                NetworkManager.c2s(),
                ImmersiveExitPacket.TYPE,
                ImmersiveExitPacket.STREAM_CODEC,
                ImmersiveExitPacket::handle
        );
        NetworkManager.registerReceiver(
                NetworkManager.c2s(),
                CookieAuthPacket.TYPE,
                CookieAuthPacket.STREAM_CODEC,
                CookieAuthPacket::handle
        );

        if (Platform.getEnvironment() == Env.CLIENT) {
            EnvExecutor.runInEnv(Env.CLIENT, () -> () -> registerClientPackets());
        } else {
            registerS2CPayloadTypes();
        }
    }

    private static void registerS2CPayloadTypes() {
        NetworkManager.registerS2CPayloadType(MCCOutputPacket.TYPE, MCCOutputPacket.STREAM_CODEC);
        NetworkManager.registerS2CPayloadType(ImmersiveStatusPacket.TYPE, ImmersiveStatusPacket.STREAM_CODEC);
        NetworkManager.registerS2CPayloadType(OpenStatusScreenPacket.TYPE, OpenStatusScreenPacket.STREAM_CODEC);
        NetworkManager.registerS2CPayloadType(StatusDataPacket.TYPE, StatusDataPacket.STREAM_CODEC);
        NetworkManager.registerS2CPayloadType(InstanceRemovedPacket.TYPE, InstanceRemovedPacket.STREAM_CODEC);
        NetworkManager.registerS2CPayloadType(CookieGrantPacket.TYPE, CookieGrantPacket.STREAM_CODEC);
        NetworkManager.registerS2CPayloadType(CookieAuthResultPacket.TYPE, CookieAuthResultPacket.STREAM_CODEC);
    }

    private static void registerClientPackets() {
        NetworkManager.registerReceiver(
                NetworkManager.s2c(),
                MCCOutputPacket.TYPE,
                MCCOutputPacket.STREAM_CODEC,
                ClientPacketHandlers::handleMCCOutput
        );
        NetworkManager.registerReceiver(
                NetworkManager.s2c(),
                ImmersiveStatusPacket.TYPE,
                ImmersiveStatusPacket.STREAM_CODEC,
                ClientPacketHandlers::handleImmersiveStatus
        );
        NetworkManager.registerReceiver(
                NetworkManager.s2c(),
                OpenStatusScreenPacket.TYPE,
                OpenStatusScreenPacket.STREAM_CODEC,
                ClientPacketHandlers::handleOpenStatusScreen
        );
        NetworkManager.registerReceiver(
                NetworkManager.s2c(),
                StatusDataPacket.TYPE,
                StatusDataPacket.STREAM_CODEC,
                ClientPacketHandlers::handleStatusData
        );
        NetworkManager.registerReceiver(
                NetworkManager.s2c(),
                InstanceRemovedPacket.TYPE,
                InstanceRemovedPacket.STREAM_CODEC,
                ClientPacketHandlers::handleInstanceRemoved
        );
        NetworkManager.registerReceiver(
                NetworkManager.s2c(),
                CookieGrantPacket.TYPE,
                CookieGrantPacket.STREAM_CODEC,
                ClientPacketHandlers::handleCookieGrant
        );
        NetworkManager.registerReceiver(
                NetworkManager.s2c(),
                CookieAuthResultPacket.TYPE,
                CookieAuthResultPacket.STREAM_CODEC,
                ClientPacketHandlers::handleCookieAuthResult
        );
    }
}
