package com.xiaoze_qwq_.processmanager.ssh;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SSH 接入防护：新连接令牌桶、失败锁定、会话并发限制与 bcrypt 并发限制。
 * 所有限制都以来源 IP 为维度，且不依赖系统用户。
 */
public final class SshGuard {
    private static final int MAX_NEW_PER_MINUTE = 6;
    private static final double BURST = 3.0;
    private static final int MAX_SESSIONS_PER_IP = 2;
    private static final int MAX_SESSIONS_GLOBAL = 32;
    private static final int MAX_BRIDGES_PER_IP = 4;
    private static final int MAX_BRIDGES_GLOBAL = 64;

    private static final int MAX_AUTH_FAILURES = 5;
    private static final long LOCK_MILLIS = 30L * 60L * 1000L;
    private static final int MAX_BCRYPT = 4;
    private static final long BCRYPT_WAIT_SECONDS = 3L;
    private static final long ENTRY_IDLE_MILLIS = 60L * 60L * 1000L;

    private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static final Semaphore BCRYPT = new Semaphore(MAX_BCRYPT);
    private static final AtomicInteger GLOBAL_SESSIONS = new AtomicInteger();
    private static final AtomicInteger GLOBAL_BRIDGES = new AtomicInteger();

    private static final ScheduledExecutorService CLEANER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "PM-SSH-Guard");
        t.setDaemon(true);
        return t;
    });

    static {
        CLEANER.scheduleWithFixedDelay(SshGuard::cleanup, 5, 5, TimeUnit.MINUTES);
    }

    private SshGuard() {
    }

    public static boolean isLocked(String ip) {
        Entry entry = entry(ip);
        synchronized (entry) {
            long now = System.currentTimeMillis();
            entry.lastSeen = now;
            if (entry.lockedUntil == 0L) return false;
            if (entry.lockedUntil > now) return true;
            entry.lockedUntil = 0L;
            entry.failures = 0;
            return false;
        }
    }

    public static boolean allowNewConnection(String ip) {
        Entry entry = entry(ip);
        synchronized (entry) {
            long now = System.currentTimeMillis();
            entry.lastSeen = now;
            long elapsed = now - entry.lastRefill;
            entry.tokens = Math.min(BURST, entry.tokens + (elapsed * MAX_NEW_PER_MINUTE) / 60_000.0);
            entry.lastRefill = now;
            if (entry.tokens < 1.0) return false;
            entry.tokens -= 1.0;
            return true;
        }
    }

    public static void recordFailure(String ip) {
        Entry entry = entry(ip);
        synchronized (entry) {
            long now = System.currentTimeMillis();
            entry.lastSeen = now;
            if (entry.lockedUntil != 0L && entry.lockedUntil <= now) {
                entry.lockedUntil = 0L;
                entry.failures = 0;
            }
            entry.failures++;
            if (entry.failures >= MAX_AUTH_FAILURES) {
                entry.lockedUntil = now + LOCK_MILLIS;
            }
        }
    }

    public static void recordSuccess(String ip) {
        Entry entry = entry(ip);
        synchronized (entry) {
            entry.lastSeen = System.currentTimeMillis();
            entry.failures = 0;
            entry.lockedUntil = 0L;
        }
    }

    public static boolean acquireBcrypt() {
        try {
            return BCRYPT.tryAcquire(BCRYPT_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public static void releaseBcrypt() {
        BCRYPT.release();
    }

    public static boolean tryAcquireSession(String ip) {
        Entry entry = entry(ip);
        synchronized (entry) {
            long now = System.currentTimeMillis();
            entry.lastSeen = now;
            if (entry.lockedUntil > now) return false;
            if (entry.sessions >= MAX_SESSIONS_PER_IP) return false;
            while (true) {
                int current = GLOBAL_SESSIONS.get();
                if (current >= MAX_SESSIONS_GLOBAL) return false;
                if (GLOBAL_SESSIONS.compareAndSet(current, current + 1)) break;
            }
            entry.sessions++;
            return true;
        }
    }

    public static boolean tryAcquireBridge(String ip) {
        Entry entry = entry(ip);
        synchronized (entry) {
            long now = System.currentTimeMillis();
            entry.lastSeen = now;
            if (entry.bridges >= MAX_BRIDGES_PER_IP) return false;
            while (true) {
                int current = GLOBAL_BRIDGES.get();
                if (current >= MAX_BRIDGES_GLOBAL) return false;
                if (GLOBAL_BRIDGES.compareAndSet(current, current + 1)) break;
            }
            entry.bridges++;
            return true;
        }
    }

    public static void releaseBridge(String ip) {
        Entry entry = entry(ip);
        boolean released;
        synchronized (entry) {
            entry.lastSeen = System.currentTimeMillis();
            released = entry.bridges > 0;
            if (released) entry.bridges--;
        }
        if (released) {
            GLOBAL_BRIDGES.updateAndGet(value -> value > 0 ? value - 1 : 0);
        }
    }

    public static void releaseSession(String ip) {
        Entry entry = entry(ip);
        boolean released;
        synchronized (entry) {
            entry.lastSeen = System.currentTimeMillis();
            released = entry.sessions > 0;
            if (released) entry.sessions--;
        }
        if (released) {
            GLOBAL_SESSIONS.updateAndGet(value -> value > 0 ? value - 1 : 0);
        }
    }

    private static Entry entry(String ip) {
        String key = ip == null || ip.isEmpty() ? "unknown" : ip;
        return ENTRIES.computeIfAbsent(key, ignored -> new Entry());
    }

    private static void cleanup() {
        long now = System.currentTimeMillis();
        ENTRIES.entrySet().removeIf(entry -> {
            Entry value = entry.getValue();
            synchronized (value) {
                return value.sessions == 0
                        && value.bridges == 0
                        && value.lockedUntil <= now
                        && now - value.lastSeen > ENTRY_IDLE_MILLIS;
            }
        });
    }

    private static final class Entry {
        double tokens = BURST;
        long lastRefill = System.currentTimeMillis();
        long lastSeen = System.currentTimeMillis();
        long lockedUntil;
        int failures;
        int sessions;
        int bridges;
    }
}
