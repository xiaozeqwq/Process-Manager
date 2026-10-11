package com.xiaoze_qwq_.processmanager;

import com.xiaoze_qwq_.processmanager.config.Config;

import java.util.*;
import java.util.concurrent.*;

public class InstanceManager {
    private static final InstanceManager INSTANCE = new InstanceManager();
    private final Map<String, ProcessInstance> instances = new ConcurrentHashMap<>();

    private InstanceManager() {}

    public static InstanceManager getInstance() { return INSTANCE; }

    // ==================== 实例管理 ====================
    public void loadInstances() {
        Config.getAllInstances().forEach(cfg -> {
            ProcessInstance instance = new ProcessInstance(cfg.id, cfg);
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
        Map<String, ProcessInstance> oldInstances = new HashMap<>(instances);
        Config.load();
        Collection<Config.InstanceConfig> newConfigs = Config.getAllInstances();
        for (Config.InstanceConfig cfg : newConfigs) {
            ProcessInstance existing = oldInstances.get(cfg.id);
            if (existing != null) {
                existing.updateConfig(cfg);
                oldInstances.remove(cfg.id);
            } else {
                ProcessInstance newInstance = new ProcessInstance(cfg.id, cfg);
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
        for (ProcessInstance removed : oldInstances.values()) {
            instances.remove(removed.getId());
            removed.stop(true);
        }
    }

    public void shutdownAll() {
        instances.values().forEach(inst -> inst.stop(true));
        instances.clear();
    }

    public ProcessInstance getInstance(String id) {
        return instances.get(id);
    }

    public Collection<ProcessInstance> getAllInstances() {
        return instances.values();
    }

    public boolean hasInstance(String id) {
        return instances.containsKey(id);
    }

    /**
     * 通过名称（不区分大小写）查找实例
     */
    public ProcessInstance getInstanceByName(String name) {
        String lowerName = name.toLowerCase(Locale.ROOT);
        for (ProcessInstance inst : instances.values()) {
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