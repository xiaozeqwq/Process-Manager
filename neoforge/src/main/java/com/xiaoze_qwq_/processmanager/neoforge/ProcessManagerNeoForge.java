package com.xiaoze_qwq_.processmanager.neoforge;

import com.xiaoze_qwq_.processmanager.ProcessManager;
import net.neoforged.fml.common.Mod;

@Mod(ProcessManager.MOD_ID)
public class ProcessManagerNeoForge {
    public ProcessManagerNeoForge() {
        ProcessManager.init();
    }
}
