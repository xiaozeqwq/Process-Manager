package com.xiaoze_qwq_.processmanager.ssh;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SshBridgeRegistry {
    private static final Map<Integer, String> IP_BY_LOCAL_PORT = new ConcurrentHashMap<>();

    private SshBridgeRegistry() {
    }

    public static void register(int localPort, String ip) {
        if (localPort <= 0) return;
        IP_BY_LOCAL_PORT.put(localPort, ip == null || ip.isEmpty() ? "unknown" : ip);
    }

    public static void unregister(int localPort) {
        if (localPort <= 0) return;
        IP_BY_LOCAL_PORT.remove(localPort);
    }

    public static String lookup(int localPort) {
        return localPort <= 0 ? null : IP_BY_LOCAL_PORT.get(localPort);
    }
}
