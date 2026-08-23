package com.xiaoze_qwq_.mis;

import com.xiaoze_qwq_.mis.config.MISConfig;

import java.util.*;
import java.util.concurrent.*;

public class MCCManager {
    private static final MCCManager INSTANCE = new MCCManager();
    private final Map<String, MCCInstance> instances = new ConcurrentHashMap<>();

    private MCCManager() {}

    public static MCCManager getInstance() { return INSTANCE; }

    // ==================== 实例管理 ====================
    public void loadInstances() {
        MISConfig.getAllInstances().forEach(cfg -> {
            MCCInstance instance = new MCCInstance(cfg.id, cfg);
            instances.put(cfg.id, instance);
            if (cfg.autoStart) {
                long delay = cfg.startupDelaySeconds;
                if (delay > 0) {
                    instance.getScheduler().schedule(instance::start, delay, TimeUnit.SECONDS);
                } else {
                    instance.start();
                }
            }
        });
    }

    public void reloadInstances() {
        Map<String, MCCInstance> oldInstances = new HashMap<>(instances);
        MISConfig.load();
        Collection<MISConfig.InstanceConfig> newConfigs = MISConfig.getAllInstances();
        for (MISConfig.InstanceConfig cfg : newConfigs) {
            MCCInstance existing = oldInstances.get(cfg.id);
            if (existing != null) {
                existing.updateConfig(cfg);
                oldInstances.remove(cfg.id);
            } else {
                MCCInstance newInstance = new MCCInstance(cfg.id, cfg);
                instances.put(cfg.id, newInstance);
                if (cfg.autoStart) {
                    long delay = cfg.startupDelaySeconds;
                    if (delay > 0) {
                        newInstance.getScheduler().schedule(newInstance::start, delay, TimeUnit.SECONDS);
                    } else {
                        newInstance.start();
                    }
                }
            }
        }
        for (MCCInstance removed : oldInstances.values()) {
            instances.remove(removed.getId());
            removed.stop(true);
        }
    }

    public void shutdownAll() {
        instances.values().forEach(inst -> inst.stop(true));
        instances.clear();
    }

    public MCCInstance getInstance(String id) {
        return instances.get(id);
    }

    public Collection<MCCInstance> getAllInstances() {
        return instances.values();
    }

    public boolean hasInstance(String id) {
        return instances.containsKey(id);
    }

    /**
     * 通过名称（不区分大小写）查找实例
     */
    public MCCInstance getInstanceByName(String name) {
        String lowerName = name.toLowerCase(Locale.ROOT);
        for (MCCInstance inst : instances.values()) {
            if (inst.getConfig().name.toLowerCase(Locale.ROOT).equals(lowerName)) {
                return inst;
            }
        }
        return null;
    }

    // ==================== 统一玩家管理 ====================
    public void removeDisconnectedPlayer(UUID uuid) {
        instances.values().forEach(inst -> inst.removeDisconnectedPlayer(uuid));
    }

    public void start() {
        loadInstances();
    }

    public void stop() {
        shutdownAll();
    }
}