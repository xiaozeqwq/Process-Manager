package com.xiaoze_qwq_.mis.fabric;

import com.xiaoze_qwq_.mis.MCCInServer;
import net.fabricmc.api.ModInitializer;

public class MCCInServerFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        MCCInServer.init();
    }
}
