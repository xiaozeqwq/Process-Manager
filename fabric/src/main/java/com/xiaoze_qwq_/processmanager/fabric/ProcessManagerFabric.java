package com.xiaoze_qwq_.processmanager.fabric;

import com.xiaoze_qwq_.processmanager.ProcessManager;
import net.fabricmc.api.ModInitializer;

public class ProcessManagerFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        ProcessManager.init();
    }
}
