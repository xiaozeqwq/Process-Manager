package com.xiaoze_qwq_.processmanager.monitor;

import oshi.SystemInfo;

/**
 * 进程内共享的 OSHI {@link SystemInfo} 实例，避免每个实例重复初始化硬件/操作系统探测。
 */
final class SharedSystemInfo {
    private static volatile SystemInfo instance;

    private SharedSystemInfo() {
    }

    static SystemInfo get() {
        SystemInfo local = instance;
        if (local == null) {
            synchronized (SharedSystemInfo.class) {
                local = instance;
                if (local == null) {
                    local = new SystemInfo();
                    instance = local;
                }
            }
        }
        return local;
    }
}
