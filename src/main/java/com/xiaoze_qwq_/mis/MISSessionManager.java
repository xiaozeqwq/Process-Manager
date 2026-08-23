package com.xiaoze_qwq_.mis;

import com.moandjiezana.toml.Toml;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MISSessionManager {
    private static final MISSessionManager INSTANCE = new MISSessionManager();
    private static final Path AUTH_FILE = FMLPaths.CONFIGDIR.get().resolve("mis_auth.toml");
    private static final long DEFAULT_AUTH_DURATION_MS = 24L * 60L * 60L * 1000L;

    private final Map<UUID, MISAuthCookie> cookies = new ConcurrentHashMap<>();

    private MISSessionManager() {}

    public static MISSessionManager getInstance() {
        return INSTANCE;
    }

    public void load() {
        if (!Files.exists(AUTH_FILE)) return;
        try (Reader reader = new InputStreamReader(Files.newInputStream(AUTH_FILE), StandardCharsets.UTF_8)) {
            Toml toml = new Toml().read(reader);
            List<Toml> auths = toml.getTables("auth");
            if (auths == null) return;
            for (Toml t : auths) {
                String uuidStr = t.getString("uuid");
                if (uuidStr == null) continue;
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    MISAuthCookie cookie = new MISAuthCookie(
                            uuid,
                            t.getString("username", ""),
                            t.getString("ip", ""),
                            t.getLong("grantedAt", 0L),
                            t.getLong("expiresAt", 0L),
                            t.getString("uid", ""));
                    cookies.put(uuid, cookie);
                } catch (IllegalArgumentException ignored) {
                }
            }
        } catch (IOException e) {
            MCCInServer.LOGGER.error("Failed to load mis_auth.toml", e);
        }
    }

    private synchronized void save() {
        try (Writer writer = new OutputStreamWriter(Files.newOutputStream(AUTH_FILE), StandardCharsets.UTF_8)) {
            writer.write("# MIS Authentication Cookies\n\n");
            for (MISAuthCookie c : cookies.values()) {
                writer.write("[[auth]]\n");
                writer.write("uuid = \"" + c.uuid() + "\"\n");
                writer.write("username = \"" + escape(c.username()) + "\"\n");
                writer.write("ip = \"" + escape(c.ip()) + "\"\n");
                writer.write("grantedAt = " + c.grantedAt() + "\n");
                writer.write("expiresAt = " + c.expiresAt() + "\n");
                writer.write("uid = \"" + escape(c.uid()) + "\"\n\n");
            }
        } catch (IOException e) {
            MCCInServer.LOGGER.error("Failed to save mis_auth.toml", e);
        }
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ==================== 授权管理 ====================

    public void grant(ServerPlayer player, long durationMs) {
        grant(player.getUUID(), player.getGameProfile().getName(), ipOf(player), durationMs, "");
    }

    public void grant(UUID uuid, String username, String ip, long durationMs, String uid) {
        long now = System.currentTimeMillis();
        cookies.put(uuid, new MISAuthCookie(uuid, username, ip, now, now + durationMs, uid));
        save();
    }

    public void revoke(UUID uuid) {
        cookies.remove(uuid);
        save();
    }

    public boolean isInMISMode(ServerPlayer player) {
        MISAuthCookie c = cookies.get(player.getUUID());
        if (c == null) return false;
        return c.expiresAt() > System.currentTimeMillis();
    }

    public boolean hasCookie(UUID uuid) {
        return cookies.containsKey(uuid);
    }

    public MISAuthCookie getCookie(UUID uuid) {
        return cookies.get(uuid);
    }

    public Collection<MISAuthCookie> listCookies() {
        return new ArrayList<>(cookies.values());
    }

    public void purgeExpired() {
        long now = System.currentTimeMillis();
        boolean changed = cookies.values().removeIf(c -> c.expiresAt() <= now);
        if (changed) save();
    }

    private static String ipOf(ServerPlayer player) {
        try {
            if (player.connection != null && player.connection.getRemoteAddress() != null) {
                return player.connection.getRemoteAddress().toString();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    public static long defaultDurationMs() {
        return DEFAULT_AUTH_DURATION_MS;
    }

    public record MISAuthCookie(UUID uuid, String username, String ip, long grantedAt, long expiresAt, String uid) {
        public long remainingMs() {
            return expiresAt - System.currentTimeMillis();
        }
    }
}
