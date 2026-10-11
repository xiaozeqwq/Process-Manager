package com.xiaoze_qwq_.processmanager;

import com.moandjiezana.toml.Toml;
import com.xiaoze_qwq_.processmanager.config.Config;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import dev.architectury.platform.Platform;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class SessionManager {
    private static final SessionManager INSTANCE = new SessionManager();
    private static final Path AUTH_FILE = Platform.getConfigFolder().resolve("pm_auth.toml");
    private static final long DEFAULT_AUTH_DURATION_MS = 24L * 60L * 60L * 1000L;

    /** 已撤销 token 允许旧 token 换新的宽限期（毫秒） */
    private static final long REVOKED_TOKEN_GRACE_MS = 5000L;

    private final Map<UUID, AuthCookie> cookies = new ConcurrentHashMap<>();

    // ==================== Cookie 令牌（HMAC-SHA256） ====================
    /** token(hex) -> SessionCookie，token = HMAC(serialize(cookie)) */
    private final Map<String, SessionCookie> cookieMap = new ConcurrentHashMap<>();
    /** 最近撤销的 token -> 撤销时间与原始 cookie，用于 5 秒内旧 token 换取新 token */
    private final Map<String, RevokedToken> recentlyRevokedTokens = new ConcurrentHashMap<>();
    private final byte[] secret;
    private final ScheduledExecutorService revocationCleaner;

    private SessionManager() {
        this.secret = Config.getSecretKey();
        this.revocationCleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PM-TokenCleaner");
            t.setDaemon(true);
            return t;
        });
        this.revocationCleaner.scheduleWithFixedDelay(this::purgeRevokedTokens, 5, 5, TimeUnit.SECONDS);
    }

    public static SessionManager getInstance() {
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
                    AuthCookie cookie = new AuthCookie(
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
            ProcessManager.LOGGER.error("Failed to load pm_auth.toml", e);
        }
    }

    private synchronized void save() {
        try (Writer writer = new OutputStreamWriter(Files.newOutputStream(AUTH_FILE), StandardCharsets.UTF_8)) {
            writer.write("# PM Authentication Cookies\n\n");
            for (AuthCookie c : cookies.values()) {
                writer.write("[[auth]]\n");
                writer.write("uuid = \"" + c.uuid() + "\"\n");
                writer.write("username = \"" + escape(c.username()) + "\"\n");
                writer.write("ip = \"" + escape(c.ip()) + "\"\n");
                writer.write("grantedAt = " + c.grantedAt() + "\n");
                writer.write("expiresAt = " + c.expiresAt() + "\n");
                writer.write("uid = \"" + escape(c.uid()) + "\"\n\n");
            }
        } catch (IOException e) {
            ProcessManager.LOGGER.error("Failed to save pm_auth.toml", e);
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
        cookies.put(uuid, new AuthCookie(uuid, username, ip, now, now + durationMs, uid));
        save();
    }

    public void revoke(UUID uuid) {
        cookies.remove(uuid);
        save();
    }

    public boolean isAuthorized(ServerPlayer player) {
        AuthCookie c = cookies.get(player.getUUID());
        if (c == null) return false;
        return c.expiresAt() > System.currentTimeMillis();
    }

    public boolean hasCookie(UUID uuid) {
        return cookies.containsKey(uuid);
    }

    public AuthCookie getCookie(UUID uuid) {
        return cookies.get(uuid);
    }

    public Collection<AuthCookie> listCookies() {
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

    // ==================== Cookie 令牌（HMAC-SHA256） ====================

    /**
     * 服务端自身地址标识，用于签发与校验 Cookie 时保持一致的 serverAddress。
     */
    public static String serverAddressOf(MinecraftServer server) {
        if (server == null) return "";
        String ip = server.getLocalIp();
        int port = server.getPort();
        if (ip == null || ip.isEmpty()) ip = "localhost";
        if (port <= 0) port = 25565;
        return ip + ":" + port;
    }

    /**
     * 签发一个新的 Cookie 令牌。
     *
     * @param deviceId 设备标识；verify 阶段服务端尚不知道客户端设备 ID 时传空字符串（表示未绑定）
     * @return 令牌及其过期时间
     */
    public GrantedCookie grantCookie(UUID uuid, String username, String serverAddress, String deviceId, long durationMs) {
        byte[] nonceBytes = new byte[16];
        new SecureRandom().nextBytes(nonceBytes);
        String nonce = Base64.getEncoder().encodeToString(nonceBytes);
        long now = System.currentTimeMillis();
        SessionCookie cookie = new SessionCookie(uuid, username, serverAddress, now, now + durationMs, nonce, deviceId);
        String token = computeToken(cookie);
        cookieMap.put(token, cookie);
        return new GrantedCookie(token, cookie.expiresAt());
    }

    /**
     * 校验 Cookie 令牌。校验通过返回 CookieValidation（cookie 非 null）；
     * 校验失败返回 success=false 的 CookieValidation。
     * 若令牌为最近 5 秒内被撤销的旧令牌，会重新签发新令牌并通过 CookieValidation.newToken 返回。
     */
    public CookieValidation validateCookie(String token, String clientDeviceId, String expectedServerAddress) {
        if (token == null || token.isEmpty()) return CookieValidation.failure();
        long now = System.currentTimeMillis();
        SessionCookie cookie = cookieMap.get(token);
        if (cookie != null) {
            // 过期检查
            if (cookie.expiresAt() <= now) {
                cookieMap.remove(token);
                recentlyRevokedTokens.remove(token);
                return CookieValidation.failure();
            }
            // 设备绑定检查
            if (cookie.deviceId() == null || cookie.deviceId().isEmpty()) {
                // 未绑定设备（verify 刚签发）：用本次客户端 deviceId 完成绑定并重新签发
                if (clientDeviceId == null || clientDeviceId.isEmpty()) return CookieValidation.failure();
                cookieMap.remove(token);
                return reissue(cookie, clientDeviceId);
            }
            if (!cookie.deviceId().equals(clientDeviceId)) return CookieValidation.failure();
            // 服务器地址检查（防止跨服）
            if (expectedServerAddress != null && !expectedServerAddress.isEmpty()
                    && !expectedServerAddress.equals(cookie.serverAddress())) {
                return CookieValidation.failure();
            }
            // 旧 token 重试（最近被撤销）
            RevokedToken revoked = recentlyRevokedTokens.get(token);
            if (revoked != null) {
                if (now - revoked.revokedAt() <= REVOKED_TOKEN_GRACE_MS) {
                    cookieMap.remove(token);
                    recentlyRevokedTokens.remove(token);
                    return reissue(cookie, clientDeviceId);
                } else {
                    cookieMap.remove(token);
                    recentlyRevokedTokens.remove(token);
                    return CookieValidation.failure();
                }
            }
            // 首次验证通过
            return new CookieValidation(cookie, null, 0);
        }
        // 令牌不在 cookieMap：检查是否为最近撤销的旧令牌（5 秒内允许换新）
        RevokedToken revoked = recentlyRevokedTokens.get(token);
        if (revoked != null && revoked.cookie() != null) {
            SessionCookie old = revoked.cookie();
            if (now - revoked.revokedAt() <= REVOKED_TOKEN_GRACE_MS) {
                if (clientDeviceId == null || clientDeviceId.isEmpty()) return CookieValidation.failure();
                // 已绑定设备的旧令牌必须匹配设备；未绑定的旧令牌允许在此完成绑定
                if (!old.deviceId().isEmpty() && !clientDeviceId.equals(old.deviceId())) {
                    return CookieValidation.failure();
                }
                recentlyRevokedTokens.remove(token);
                return reissue(old, clientDeviceId);
            } else {
                recentlyRevokedTokens.remove(token);
                return CookieValidation.failure();
            }
        }
        return CookieValidation.failure();
    }

    private CookieValidation reissue(SessionCookie old, String deviceId) {
        long duration = Math.max(1000, old.expiresAt() - old.issuedAt());
        GrantedCookie granted = grantCookie(old.uuid(), old.username(), old.serverAddress(), deviceId, duration);
        return new CookieValidation(old, granted.token(), granted.expiresAt());
    }

    /**
     * 撤销指定令牌。撤销后 5 秒内，该旧令牌仍可用于换取新令牌（平滑过渡）。
     */
    public void revokeCookie(String token) {
        SessionCookie cookie = cookieMap.remove(token);
        if (cookie == null) return;
        recentlyRevokedTokens.put(token, new RevokedToken(System.currentTimeMillis(), cookie));
    }

    /**
     * 撤销某玩家名下的所有 Cookie 令牌（例如重新验证后使旧令牌失效）。
     */
    public void revokeCookiesFor(UUID uuid) {
        for (Map.Entry<String, SessionCookie> e : cookieMap.entrySet()) {
            if (e.getValue().uuid().equals(uuid)) {
                revokeCookie(e.getKey());
            }
        }
    }

    private void purgeRevokedTokens() {
        long now = System.currentTimeMillis();
        recentlyRevokedTokens.entrySet().removeIf(e -> now - e.getValue().revokedAt() > REVOKED_TOKEN_GRACE_MS);
    }

    private String computeToken(SessionCookie cookie) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] hash = mac.doFinal(cookie.serialize().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 not available", e);
        }
    }

    public record AuthCookie(UUID uuid, String username, String ip, long grantedAt, long expiresAt, String uid) {
        public long remainingMs() {
            return expiresAt - System.currentTimeMillis();
        }
    }

    /** 客户端 Cookie 数据，token 为其 serialize() 结果的 HMAC-SHA256 hex 值 */
    public record SessionCookie(UUID uuid, String username, String serverAddress,
                            long issuedAt, long expiresAt, String nonce, String deviceId) {
        public static final int VERSION = 1;

        public String serialize() {
            return VERSION + "|" + uuid + "|" + username + "|" + serverAddress + "|"
                    + issuedAt + "|" + expiresAt + "|" + nonce + "|" + deviceId;
        }
    }

    /** 最近撤销的令牌条目 */
    public record RevokedToken(long revokedAt, SessionCookie cookie) {
    }

    /** Cookie 校验结果：success() 为 true 表示验证通过；needsNewToken() 表示需向客户端下发新令牌 */
    public record CookieValidation(SessionCookie cookie, String newToken, long newExpiresAt) {
        public static CookieValidation failure() {
            return new CookieValidation(null, null, 0);
        }

        public boolean success() {
            return cookie != null;
        }

        public boolean needsNewToken() {
            return newToken != null && !newToken.isEmpty();
        }
    }

    /** 已签发的 Cookie 令牌 */
    public record GrantedCookie(String token, long expiresAt) {
    }
}
