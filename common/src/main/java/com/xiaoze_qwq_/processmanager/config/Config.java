package com.xiaoze_qwq_.processmanager.config;

import at.favre.lib.crypto.bcrypt.BCrypt;
import com.moandjiezana.toml.Toml;
import com.moandjiezana.toml.TomlWriter;
import com.xiaoze_qwq_.processmanager.ProcessManager;
import dev.architectury.platform.Platform;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

public class Config {
    private static final Path CONFIG_PATH = Platform.getConfigFolder().resolve("pm_config.toml");
    private static final Path PASSWORD_FILE = Platform.getConfigFolder().resolve("pmpassword");
    private static final Path SECRET_KEY_FILE = Platform.getConfigFolder().resolve("pm_secret.key");
    private static final Path SSH_KEYS_DIR = Platform.getConfigFolder().resolve(".ssh");
    private static final Path SSH_DEFAULT_FILE = Platform.getConfigFolder().resolve("pm_ssh_default");
    private static volatile byte[] secretKey;

    private static final String PBKDF2_ALGO = "PBKDF2WithHmacSHA512";
    private static final int PBKDF2_ITERATIONS = 120_000;
    private static final int SALT_BYTES = 16;
    private static final SecureRandom RNG = new SecureRandom();

    private static final ExecutorService HASH_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "PM-Hash");
        t.setDaemon(true);
        return t;
    });

    private static InstanceConfig defaultConfig;
    private static Map<String, InstanceConfig> instances = new ConcurrentHashMap<>();
    private static volatile String sshDefaultInstance = null;

    private static volatile String passwordHash = "";

    private static int verifyMaxAttempts = 5;
    private static int verifyLockoutSeconds = 60;
    private static final Map<UUID, long[]> verifyFailures = new ConcurrentHashMap<>();

    private static final int BCRYPT_COST = 12;

    static {
        load();
    }

    // ==================== Cookie 令牌 HMAC 密钥 ====================
    public static byte[] getSecretKey() {
        byte[] key = secretKey;
        if (key != null && key.length > 0) return key;
        synchronized (Config.class) {
            key = secretKey;
            if (key != null && key.length > 0) return key;
            loadOrCreateSecretKey();
            return secretKey;
        }
    }

    private static void loadOrCreateSecretKey() {
        try {
            if (Files.exists(SECRET_KEY_FILE)) {
                String content = new String(Files.readAllBytes(SECRET_KEY_FILE), StandardCharsets.UTF_8).trim();
                byte[] decoded = Base64.getDecoder().decode(content);
                if (decoded.length > 0) {
                    secretKey = decoded;
                    return;
                }
            }
            byte[] key = new byte[64];
            RNG.nextBytes(key);
            String encoded = Base64.getEncoder().encodeToString(key);
            if (SECRET_KEY_FILE.getParent() != null) {
                Files.createDirectories(SECRET_KEY_FILE.getParent());
            }
            Files.write(SECRET_KEY_FILE, encoded.getBytes(StandardCharsets.UTF_8));
            setFilePermissions600(SECRET_KEY_FILE);
            secretKey = key;
            ProcessManager.LOGGER.info("Generated new PM secret key at {}", SECRET_KEY_FILE);
        } catch (Exception e) {
            ProcessManager.LOGGER.error("Failed to load or create PM secret key", e);
            secretKey = new byte[0];
        }
    }

    private static void setFilePermissions600(Path path) {
        try {
            Files.setPosixFilePermissions(path, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException e) {
            // 文件系统不支持 POSIX 权限时忽略
        }
    }

    // ==================== 密码管理（PBKDF2 + 随机盐） ====================
    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 512);
            SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF2_ALGO);
            return factory.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 not available", e);
        }
    }

    private static String hashPassword(String plain) {
        byte[] salt = new byte[SALT_BYTES];
        RNG.nextBytes(salt);
        byte[] hash = pbkdf2(plain.toCharArray(), salt, PBKDF2_ITERATIONS);
        return PBKDF2_ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt)
                + "$" + Base64.getEncoder().encodeToString(hash);
    }

    private static boolean verifyStoredPassword(String plain, String stored) {
        if (stored == null || stored.isEmpty()) return false;
        try {
            String[] parts = stored.split("\\$");
            if (parts.length != 3) return false;
            int iterations = Integer.parseInt(parts[0]);
            byte[] salt = Base64.getDecoder().decode(parts[1]);
            byte[] expected = Base64.getDecoder().decode(parts[2]);
            byte[] actual = pbkdf2(plain.toCharArray(), salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    private static void loadPasswordHash() {
        if (Files.exists(PASSWORD_FILE)) {
            try {
                passwordHash = new String(Files.readAllBytes(PASSWORD_FILE), StandardCharsets.UTF_8).trim();
            } catch (IOException e) {
                ProcessManager.LOGGER.error("Failed to read pmpassword, password will be empty", e);
                passwordHash = "";
            }
        } else {
            passwordHash = "";
        }
    }

    public static boolean hasPassword() {
        return passwordHash != null && !passwordHash.isEmpty();
    }

    public static boolean verifyPassword(String plain) {
        if (!hasPassword() || plain == null) return false;
        return verifyStoredPassword(plain, passwordHash);
    }

    public static CompletableFuture<Boolean> verifyPasswordAsync(String plain) {
        return CompletableFuture.supplyAsync(() -> verifyPassword(plain), HASH_EXECUTOR);
    }

    public static void setPassword(String pw) {
        if (pw == null) return;
        String stored = hashPassword(pw);
        try {
            Files.write(PASSWORD_FILE, stored.getBytes(StandardCharsets.UTF_8));
            passwordHash = stored;
            ProcessManager.LOGGER.info("Password stored as PBKDF2 hash in pmpassword.");
        } catch (IOException e) {
            ProcessManager.LOGGER.error("Failed to write password hash to pmpassword", e);
        }
    }

    public static void clearPassword() {
        try {
            Files.deleteIfExists(PASSWORD_FILE);
        } catch (IOException e) {
            ProcessManager.LOGGER.error("Failed to remove pmpassword", e);
        }
        passwordHash = "";
        verifyFailures.clear();
        ProcessManager.LOGGER.info("Password removed.");
    }

    // ==================== 验证失败限制 ====================
    public static int getVerifyMaxAttempts() { return verifyMaxAttempts; }
    public static int getVerifyLockoutSeconds() { return verifyLockoutSeconds; }

    public static long getVerifyLockRemaining(UUID uuid) {
        long[] rec = verifyFailures.get(uuid);
        if (rec == null) return 0;
        return Math.max(0, rec[1] - System.currentTimeMillis());
    }

    public static void recordVerifyFailure(UUID uuid) {
        long[] rec = verifyFailures.computeIfAbsent(uuid, k -> new long[2]);
        rec[0]++;
        if (rec[0] >= verifyMaxAttempts) {
            rec[1] = System.currentTimeMillis() + verifyLockoutSeconds * 1000L;
            rec[0] = 0;
        }
    }

    public static void clearVerifyFailures(UUID uuid) {
        verifyFailures.remove(uuid);
    }

    // ==================== 实例 SSH 配置（bcrypt / 公钥） ====================
    public static final String SSH_AUTH_PASSWORD = "password";
    public static final String SSH_AUTH_PUBLICKEY = "publickey";

    public static String bcryptHash(String plain) {
        return BCrypt.with(BCrypt.Version.VERSION_2B).hashToString(BCRYPT_COST, plain.toCharArray());
    }

    public static boolean bcryptVerify(String plain, String hash) {
        if (plain == null || hash == null || hash.isEmpty()) return false;
        try {
            return BCrypt.verifyer().verify(plain.toCharArray(), hash).verified;
        } catch (Exception e) {
            return false;
        }
    }

    public static String normalizeSshAuthMode(String mode) {
        return SSH_AUTH_PUBLICKEY.equalsIgnoreCase(mode == null ? "" : mode.trim())
                ? SSH_AUTH_PUBLICKEY : SSH_AUTH_PASSWORD;
    }

    /** 设置某实例的 SSH 密码（bcrypt 哈希后保存到配置）。 */
    public static boolean setInstanceSshPassword(String id, String plain) {
        InstanceConfig cfg = instances.get(id);
        if (cfg == null || plain == null || plain.isEmpty()) return false;
        if (plain.length() < 12 || plain.length() > 64 || plain.trim().isEmpty()) return false;
        cfg.sshPasswordHash = bcryptHash(plain);
        saveToml();
        return true;
    }

    /** 清除某实例的 SSH 密码。密码模式下同时停用 SSH。 */
    public static void clearInstanceSshPassword(String id) {
        InstanceConfig cfg = instances.get(id);
        if (cfg == null) return;
        cfg.sshPasswordHash = "";
        if (SSH_AUTH_PASSWORD.equals(normalizeSshAuthMode(cfg.sshAuthMode))) {
            cfg.sshEnabled = false;
        }
        saveToml();
    }

    /** 校验某实例的 SSH 密码。 */
    public static boolean verifyInstanceSshPassword(String id, String plain) {
        InstanceConfig cfg = instances.get(id);
        if (cfg == null) return false;
        return bcryptVerify(plain, cfg.sshPasswordHash);
    }

    /** 切换某实例的 SSH 认证方式：password 或 publickey。 */
    public static boolean setInstanceSshAuthMode(String id, String mode) {
        InstanceConfig cfg = instances.get(id);
        String normalized = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
        if (cfg == null || !isValidSshAuthMode(normalized)) return false;
        if (SSH_AUTH_PUBLICKEY.equals(normalized) && countSshAuthorizedKeys(id) == 0) return false;
        if (SSH_AUTH_PUBLICKEY.equals(normalized) && isSshDefaultInstance(id)) return false;
        if (SSH_AUTH_PASSWORD.equals(normalized)
                && (cfg.sshPasswordHash == null || cfg.sshPasswordHash.isEmpty())) return false;
        cfg.sshAuthMode = normalized;
        saveToml();
        return true;
    }

    private static boolean isValidSshAuthMode(String mode) {
        return SSH_AUTH_PASSWORD.equals(mode) || SSH_AUTH_PUBLICKEY.equals(mode);
    }

    /** 该实例是否已具备当前认证方式所需凭据。 */
    public static boolean isSshAuthReady(InstanceConfig cfg) {
        if (cfg == null) return false;
        if (SSH_AUTH_PUBLICKEY.equals(normalizeSshAuthMode(cfg.sshAuthMode))) {
            return countSshAuthorizedKeys(cfg.id) > 0;
        }
        return cfg.sshPasswordHash != null && !cfg.sshPasswordHash.isEmpty();
    }

    /**
     * 启用或停用某实例的 SSH。支持多个实例同时启用；启用前必须已配置对应认证凭据。
     */
    public static boolean setInstanceSshEnabled(String id, boolean enabled) {
        InstanceConfig cfg = instances.get(id);
        if (cfg == null) return false;
        if (enabled && !isSshAuthReady(cfg)) return false;
        cfg.sshEnabled = enabled;
        saveToml();
        saveSshDefault();
        return true;
    }

    /** 已启用 SSH 的实例列表。 */
    public static List<InstanceConfig> getSshEnabledInstances() {
        List<InstanceConfig> enabled = new ArrayList<>();
        for (InstanceConfig cfg : instances.values()) {
            if (cfg.sshEnabled) enabled.add(cfg);
        }
        return enabled;
    }

    /** 是否存在已开启 SSH 的实例。 */
    public static boolean hasEnabledSshInstance() {
        for (InstanceConfig cfg : instances.values()) {
            if (cfg.sshEnabled) return true;
        }
        return false;
    }

    /** 按 SSH 用户名解析实例：支持实例编号或实例名称（不区分大小写）。 */
    public static InstanceConfig findSshInstance(String username) {
        if (username == null) return null;
        String value = username.trim();
        if (value.isEmpty()) return null;
        if (value.matches("\\d+")) return instances.get(value);
        for (InstanceConfig cfg : instances.values()) {
            if (cfg.name != null && cfg.name.equalsIgnoreCase(value)) return cfg;
        }
        return null;
    }

    /** 某实例是否可作为默认实例：已启用 SSH、使用密码认证且已设置密码。 */
    private static boolean isSshDefaultEligible(InstanceConfig cfg) {
        return cfg != null
                && cfg.sshEnabled
                && SSH_AUTH_PASSWORD.equals(normalizeSshAuthMode(cfg.sshAuthMode))
                && cfg.sshPasswordHash != null
                && !cfg.sshPasswordHash.isEmpty();
    }

    /** 未匹配用户名时使用的默认实例；未配置或当前不合格时返回 null。 */
    public static String getSshDefaultInstance() {
        InstanceConfig cfg = sshDefaultInstance == null ? null : instances.get(sshDefaultInstance);
        return isSshDefaultEligible(cfg) ? cfg.id : null;
    }

    /** 配置中指定的默认实例编号，可能当前不合格或已停用。 */
    public static String getSshDefaultInstanceConfigured() {
        return sshDefaultInstance == null || sshDefaultInstance.isEmpty() ? null : sshDefaultInstance;
    }

    /** 该实例是否为配置指定的默认实例（即使当前停用或暂不合格）。 */
    public static boolean isSshDefaultInstance(String id) {
        return id != null && id.equals(sshDefaultInstance);
    }

    /** 清除默认实例：此后用户名无法匹配时直接拒绝，不再自动选择。 */
    public static void clearSshDefaultInstance() {
        sshDefaultInstance = "";
        saveToml();
        saveSshDefault();
    }

    public static boolean setSshDefaultInstance(String id) {
        InstanceConfig cfg = id == null ? null : instances.get(id);
        if (!isSshDefaultEligible(cfg)) return false;
        sshDefaultInstance = id;
        saveToml();
        saveSshDefault();
        return true;
    }

    /** 用户名匹配失败时回退到默认实例；公钥认证不使用此回退。 */
    public static InstanceConfig findSshInstanceWithFallback(String username) {
        InstanceConfig cfg = findSshInstance(username);
        if (cfg != null) return cfg;
        String defaultId = getSshDefaultInstance();
        return defaultId == null ? null : instances.get(defaultId);
    }

    private static void loadSshDefault() {
        Path path = SSH_DEFAULT_FILE;
        if (!Files.isRegularFile(path)) return;
        try {
            sshDefaultInstance = new String(Files.readAllBytes(path), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            ProcessManager.LOGGER.warn("Failed to read SSH default instance setting", e);
        }
    }

    private static void saveSshDefault() {
        try {
            if (SSH_DEFAULT_FILE.getParent() != null) {
                Files.createDirectories(SSH_DEFAULT_FILE.getParent());
            }
            String id = sshDefaultInstance;
            if (id == null) {
                return;
            }
            if (!id.isEmpty() && !instances.containsKey(id)) {
                ProcessManager.LOGGER.warn("SSH default instance {} no longer exists; clearing the setting.", id);
                sshDefaultInstance = "";
                id = "";
            }
            Files.write(SSH_DEFAULT_FILE, id.getBytes(StandardCharsets.UTF_8));
            setFilePermissions600(SSH_DEFAULT_FILE);
        } catch (IOException e) {
            ProcessManager.LOGGER.error("Failed to save SSH default instance setting", e);
        }
    }

    public static Path getSshKeysDirectory() {
        return SSH_KEYS_DIR;
    }

    public static Path getSshAuthorizedKeysPath(String id) {
        return SSH_KEYS_DIR.resolve(id + ".pub");
    }

    /** 读取某实例 authorized_keys 中的有效行（忽略空行与注释）。 */
    public static List<String> readSshAuthorizedKeys(String id) {
        Path path = getSshAuthorizedKeysPath(id);
        if (!Files.isRegularFile(path)) return new ArrayList<>();
        try {
            List<String> keys = new ArrayList<>();
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String trimmed = line == null ? "" : line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                keys.add(trimmed);
            }
            return keys;
        } catch (IOException e) {
            ProcessManager.LOGGER.error("Failed to read SSH authorized keys for instance {}", id, e);
            return new ArrayList<>();
        }
    }

    public static int countSshAuthorizedKeys(String id) {
        return readSshAuthorizedKeys(id).size();
    }

    /** 追加一条已规范化的公钥；重复公钥返回 false。 */
    public static boolean addSshAuthorizedKey(String id, String keyLine) {
        if (id == null || instances.get(id) == null || keyLine == null || keyLine.isBlank()) return false;
        String normalized = keyLine.trim();
        List<String> keys = readSshAuthorizedKeys(id);
        if (keys.contains(normalized)) return false;
        keys.add(normalized);
        return writeSshAuthorizedKeys(id, keys);
    }

    /** 按 1 起始的序号删除公钥。 */
    public static boolean removeSshAuthorizedKey(String id, int index) {
        if (id == null || instances.get(id) == null) return false;
        List<String> keys = readSshAuthorizedKeys(id);
        if (index < 1 || index > keys.size()) return false;
        keys.remove(index - 1);
        return writeSshAuthorizedKeys(id, keys);
    }

    private static boolean writeSshAuthorizedKeys(String id, List<String> keys) {
        try {
            Files.createDirectories(SSH_KEYS_DIR);
            setDirectoryPermissions700(SSH_KEYS_DIR);
            Path file = getSshAuthorizedKeysPath(id);
            StringBuilder content = new StringBuilder();
            for (String key : keys) {
                content.append(key).append('\n');
            }
            Files.write(file, content.toString().getBytes(StandardCharsets.UTF_8));
            setFilePermissions600(file);
            return true;
        } catch (IOException e) {
            ProcessManager.LOGGER.error("Failed to write SSH authorized keys for instance {}", id, e);
            return false;
        }
    }

    private static void setDirectoryPermissions700(Path path) {
        try {
            Files.setPosixFilePermissions(path, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException | IOException e) {
            // 文件系统不支持 POSIX 权限时忽略
        }
    }

    /** 加载配置时规范化认证方式，并停用缺少凭据的实例。 */
    private static void normalizeSshInstances() {
        for (InstanceConfig cfg : instances.values()) {
            cfg.sshAuthMode = normalizeSshAuthMode(cfg.sshAuthMode);
            if (cfg.sshEnabled && !isSshAuthReady(cfg)) {
                cfg.sshEnabled = false;
                ProcessManager.LOGGER.warn("Instance {} has SSH enabled without valid {} credentials; SSH disabled.",
                        cfg.id, cfg.sshAuthMode);
            }
        }
    }

    // ==================== 配置数据类 ====================
    public static class InstanceConfig {
        public String id;
        public String name = "";
        public boolean autoStart = false;
        public int startupDelaySeconds = 0;
        public String workingDirectory = "";
        public String command = "";
        public String stopCommand = "";
        public Map<String, String> environment = new HashMap<>();

        public boolean autoRestart = true;
        public int exitCode = 0;
        /** "exitCode": 按退出码判定正常退出；"gracefulStop": 发送停止命令后若干秒内退出即视为正常退出 */
        public String exitDetectionMode = "exitCode";
        public int gracefulStopTimeoutSeconds = 10;
        public int statusRefreshSeconds = 2;
        public boolean enableProcessMonitor = true;
        public boolean enableNetworkMonitor = true;
        public int historyLimit = 2000;
        public String logDirectory = "output";
        public String compressionFormat = "tar.gz";
        public int maxArchives = 10;
        public boolean saveLogTime = true;
        public String timeFormat = "HH:mm:ss";
        public boolean outputAlone = false;
        public boolean outputWithTime = true;
        public String outputTimeFormat = "HH:mm:ss";
        public int chartMaxPoints = 60;

        /** 是否允许该实例通过 SSH 接入其控制台（与 Minecraft 共用端口）。支持多实例同时启用。 */
        public boolean sshEnabled = false;
        /** SSH 认证方式："password" 或 "publickey"。 */
        public String sshAuthMode = SSH_AUTH_PASSWORD;
        /** SSH 密码的 bcrypt ($2b$) 哈希，加密保存，不存明文。 */
        public String sshPasswordHash = "";
    }

    // ==================== 加载 ====================
    public static void load() {
        loadSshDefault();
        if (!Files.exists(CONFIG_PATH)) {
            defaultConfig = new InstanceConfig();
            instances.clear();
            InstanceConfig defInst = new InstanceConfig();
            defInst.id = "1";
            defInst.name = "instance-1";
            instances.put("1", defInst);
            saveToml();
            loadPasswordHash();
            return;
        }

        try (Reader reader = new InputStreamReader(Files.newInputStream(CONFIG_PATH), StandardCharsets.UTF_8)) {
            Toml toml = new Toml().read(reader);

            Toml verifyTable = toml.getTable("verify");
            if (verifyTable != null) {
                Long maxAttempts = verifyTable.getLong("maxAttempts");
                if (maxAttempts != null && maxAttempts > 0) verifyMaxAttempts = maxAttempts.intValue();
                Long lockoutSeconds = verifyTable.getLong("lockoutSeconds");
                if (lockoutSeconds != null && lockoutSeconds > 0) verifyLockoutSeconds = lockoutSeconds.intValue();
            }

            defaultConfig = parseInstanceConfig(toml.getTable("default"), "default");

            instances.clear();
            List<Toml> instanceTables = toml.getTables("instances");
            if (instanceTables != null) {
                ensureDefaultConfig();
                for (Toml instToml : instanceTables) {
                    String id = instToml.getString("id", "");
                    if (id.isEmpty()) continue;
                    InstanceConfig cfg = parseInstanceConfig(instToml, id);
                    cfg.id = id;
                    mergeWithDefault(cfg);
                    instances.put(id, cfg);
                }
            }
            if (instances.isEmpty()) {
                InstanceConfig defInst = new InstanceConfig();
                defInst.id = "1";
                defInst.name = "instance-1";
                mergeWithDefault(defInst);
                instances.put("1", defInst);
            }

            // ==================== 实例校验 ====================
            Iterator<Map.Entry<String, InstanceConfig>> it = instances.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, InstanceConfig> entry = it.next();
                String id = entry.getKey();
                InstanceConfig cfg = entry.getValue();

                if (!id.matches("\\d+")) {
                    ProcessManager.LOGGER.error("Instance ID '{}' is not a pure number. Skipping instance.", id);
                    it.remove();
                    continue;
                }

                if (cfg.name != null && cfg.name.matches("\\d+")) {
                    ProcessManager.LOGGER.error("Instance '{}' has a numeric name '{}'. Please use a non-numeric name.", id, cfg.name);
                    it.remove();
                    continue;
                }

                String lowerName = (cfg.name != null ? cfg.name : "").toLowerCase(Locale.ROOT);
                for (Map.Entry<String, InstanceConfig> other : instances.entrySet()) {
                    if (other.getKey().equals(id)) continue;
                    String otherLower = (other.getValue().name != null ? other.getValue().name : "").toLowerCase(Locale.ROOT);
                    if (lowerName.equals(otherLower)) {
                        ProcessManager.LOGGER.error("Duplicate instance name '{}' (case-insensitive) between instances '{}' and '{}'. Skipping instance '{}'.",
                                cfg.name, id, other.getKey(), id);
                        it.remove();
                        break;
                    }
                }
            }

            normalizeSshInstances();
            saveToml();
            saveSshDefault();
            loadPasswordHash();
        } catch (Exception e) {
            ProcessManager.LOGGER.error("Failed to load pm_config.toml, using defaults", e);
            defaultConfig = new InstanceConfig();
            instances.clear();
            InstanceConfig defInst = new InstanceConfig();
            defInst.id = "1";
            defInst.name = "instance-1";
            instances.put("1", defInst);
        }
    }

    private static InstanceConfig parseInstanceConfig(Toml table, String fallbackId) {
        InstanceConfig cfg = new InstanceConfig();
        if (table == null) return cfg;
        cfg.id = table.getString("id", fallbackId);
        cfg.name = table.getString("name", "");
        cfg.autoStart = table.getBoolean("autoStart", false);
        cfg.startupDelaySeconds = table.getLong("startupDelaySeconds", 0L).intValue();
        cfg.workingDirectory = table.getString("workingDirectory", "");
        cfg.command = table.getString("command", "");
        cfg.stopCommand = table.getString("stopCommand", "");
        cfg.environment = parseEnvironment(table);
        cfg.autoRestart = table.getBoolean("autoRestart", true);
        cfg.exitCode = table.getLong("exitCode", 0L).intValue();
        cfg.exitDetectionMode = table.getString("exitDetectionMode", "exitCode");
        cfg.gracefulStopTimeoutSeconds = table.getLong("gracefulStopTimeoutSeconds", 10L).intValue();
        cfg.statusRefreshSeconds = table.getLong("statusRefreshSeconds", 2L).intValue();
        cfg.enableProcessMonitor = table.getBoolean("enableProcessMonitor", true);
        cfg.enableNetworkMonitor = table.getBoolean("enableNetworkMonitor", true);
        cfg.historyLimit = table.getLong("historyLimit", 2000L).intValue();
        cfg.logDirectory = table.getString("logDirectory", "");
        cfg.compressionFormat = table.getString("compressionFormat", "tar.gz");
        cfg.maxArchives = table.getLong("maxArchives", 10L).intValue();
        cfg.saveLogTime = table.getBoolean("saveLogTime", true);
        cfg.timeFormat = table.getString("timeFormat", "HH:mm:ss");
        cfg.outputAlone = table.getBoolean("outputAlone", false);
        cfg.outputWithTime = table.getBoolean("outputWithTime", true);
        cfg.outputTimeFormat = table.getString("outputTimeFormat", "HH:mm:ss");
        cfg.chartMaxPoints = table.getLong("chartMaxPoints", 60L).intValue();
        if (cfg.chartMaxPoints < 10) cfg.chartMaxPoints = 60;
        cfg.sshEnabled = table.getBoolean("sshEnabled", false);
        String authMode = table.getString("sshAuthMode", SSH_AUTH_PASSWORD);
        if (authMode == null || authMode.isBlank()) {
            cfg.sshAuthMode = SSH_AUTH_PASSWORD;
        } else if (!isValidSshAuthMode(authMode.trim().toLowerCase(Locale.ROOT))) {
            ProcessManager.LOGGER.warn("Instance '{}' has invalid sshAuthMode '{}'; using password.", fallbackId, authMode);
            cfg.sshAuthMode = SSH_AUTH_PASSWORD;
        } else {
            cfg.sshAuthMode = authMode.trim().toLowerCase(Locale.ROOT);
        }
        String sshHashValue = table.getString("sshPasswordHash", "");
        cfg.sshPasswordHash = sshHashValue == null ? "" : sshHashValue;
        if (table.getString("sshUser") != null) {
            ProcessManager.LOGGER.warn("Instance '{}' contains deprecated field 'sshUser' which is ignored.", fallbackId);
        }
        if (table.getString("executablePath") != null || table.toMap().containsKey("arguments")) {
            ProcessManager.LOGGER.warn("Instance '{}' contains deprecated fields 'executablePath'/'arguments' which are ignored. Use 'command' instead.", fallbackId);
        }
        if (table.getString("compressionStrategy") != null) {
            ProcessManager.LOGGER.warn("Instance '{}' contains deprecated field 'compressionStrategy' which is ignored.", fallbackId);
        }
        if (table.getLong("scheduledCompressionMinutes") != null) {
            ProcessManager.LOGGER.warn("Instance '{}' contains deprecated field 'scheduledCompressionMinutes' which is ignored.", fallbackId);
        }
        return cfg;
    }

    /** environment 直接走 table.toMap()，由 Toml 库反转义 */
    private static Map<String, String> parseEnvironment(Toml table) {
        Object envObj = table.toMap().get("environment");
        if (envObj instanceof Map<?, ?>) {
            Map<String, String> map = new HashMap<>();
            ((Map<?, ?>) envObj).forEach((k, v) -> map.put(String.valueOf(k), String.valueOf(v)));
            return map;
        }
        return new HashMap<>();
    }

    private static void ensureDefaultConfig() {
        if (defaultConfig == null) {
            defaultConfig = new InstanceConfig();
        }
    }

    private static void mergeWithDefault(InstanceConfig cfg) {
        ensureDefaultConfig();
        InstanceConfig defaults = defaultConfig;
        if (defaults == null) return;
        if (cfg.name == null || cfg.name.isEmpty()) cfg.name = defaults.name.isEmpty() ? cfg.id : defaults.name;
        if (cfg.command == null || cfg.command.isEmpty()) cfg.command = defaults.command;
        if (cfg.stopCommand == null) cfg.stopCommand = defaults.stopCommand;
        if (cfg.exitDetectionMode == null || cfg.exitDetectionMode.isEmpty()) cfg.exitDetectionMode = defaults.exitDetectionMode;
        if (cfg.gracefulStopTimeoutSeconds <= 0) cfg.gracefulStopTimeoutSeconds = defaults.gracefulStopTimeoutSeconds;
        if (cfg.environment.isEmpty() && !defaults.environment.isEmpty()) cfg.environment = defaults.environment;
        if (cfg.workingDirectory.isEmpty()) cfg.workingDirectory = defaults.workingDirectory;
        if (cfg.logDirectory.isEmpty()) cfg.logDirectory = defaults.logDirectory.isEmpty() ? "output" : defaults.logDirectory;
        if (cfg.statusRefreshSeconds <= 0) cfg.statusRefreshSeconds = defaults.statusRefreshSeconds;
        if (cfg.historyLimit <= 0) cfg.historyLimit = defaults.historyLimit;
        if (cfg.exitCode < 0) cfg.exitCode = defaults.exitCode;
        if (cfg.maxArchives <= 0) cfg.maxArchives = defaults.maxArchives;
        if (cfg.sshPasswordHash == null) cfg.sshPasswordHash = "";
        cfg.sshAuthMode = normalizeSshAuthMode(cfg.sshAuthMode);
    }

    // ==================== 保存（全部交给 TomlWriter） ====================
    public static void save() {
        saveToml();
        saveSshDefault();
    }

    private static void saveToml() {
        try {
            Map<String, Object> root = new LinkedHashMap<>();

            Map<String, Object> verify = new LinkedHashMap<>();
            verify.put("maxAttempts", (long) verifyMaxAttempts);
            verify.put("lockoutSeconds", (long) verifyLockoutSeconds);
            root.put("verify", verify);

            root.put("default", instanceToMap(defaultConfig, true));

            List<Map<String, Object>> instanceList = new ArrayList<>();
            for (InstanceConfig cfg : instances.values()) {
                instanceList.add(instanceToMap(cfg, false));
            }
            root.put("instances", instanceList);

            TomlWriter writer = new TomlWriter();
            String body = writer.write(root);

            String content = "# PM Configuration (TOML)\n\n" + body;
            if (CONFIG_PATH.getParent() != null) {
                Files.createDirectories(CONFIG_PATH.getParent());
            }
            Files.write(CONFIG_PATH, content.getBytes(StandardCharsets.UTF_8));
            setFilePermissions600(CONFIG_PATH);
        } catch (IOException e) {
            ProcessManager.LOGGER.error("Failed to save pm_config.toml", e);
        }
    }

    private static Map<String, Object> instanceToMap(InstanceConfig cfg, boolean skipId) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (!skipId) m.put("id", cfg.id);
        m.put("name", cfg.name);
        m.put("autoStart", cfg.autoStart);
        m.put("startupDelaySeconds", (long) cfg.startupDelaySeconds);
        m.put("workingDirectory", cfg.workingDirectory);
        m.put("command", cfg.command);
        m.put("stopCommand", cfg.stopCommand);
        m.put("environment", new LinkedHashMap<>(cfg.environment));
        m.put("autoRestart", cfg.autoRestart);
        m.put("exitCode", (long) cfg.exitCode);
        m.put("exitDetectionMode", cfg.exitDetectionMode);
        m.put("gracefulStopTimeoutSeconds", (long) cfg.gracefulStopTimeoutSeconds);
        m.put("statusRefreshSeconds", (long) cfg.statusRefreshSeconds);
        m.put("enableProcessMonitor", cfg.enableProcessMonitor);
        m.put("enableNetworkMonitor", cfg.enableNetworkMonitor);
        m.put("historyLimit", (long) cfg.historyLimit);
        m.put("logDirectory", cfg.logDirectory);
        m.put("compressionFormat", cfg.compressionFormat);
        m.put("maxArchives", (long) cfg.maxArchives);
        m.put("saveLogTime", cfg.saveLogTime);
        m.put("timeFormat", cfg.timeFormat);
        m.put("outputAlone", cfg.outputAlone);
        m.put("outputWithTime", cfg.outputWithTime);
        m.put("outputTimeFormat", cfg.outputTimeFormat);
        m.put("chartMaxPoints", (long) cfg.chartMaxPoints);
        m.put("sshEnabled", cfg.sshEnabled);
        m.put("sshAuthMode", normalizeSshAuthMode(cfg.sshAuthMode));
        m.put("sshPasswordHash", cfg.sshPasswordHash == null ? "" : cfg.sshPasswordHash);
        return m;
    }

    // ==================== 公共访问器 ====================
    public static Collection<InstanceConfig> getAllInstances() { return instances.values(); }
    public static InstanceConfig getInstanceConfig(String id) { return instances.get(id); }
    public static String[] getInstanceIds() { return instances.keySet().toArray(new String[0]); }
    public static boolean instanceExists(String id) { return instances.containsKey(id); }

    public static void setInstanceField(String id, String field, Object value) {
        InstanceConfig cfg = instances.get(id);
        if (cfg == null) return;
        switch (field) {
            case "autoRestart": cfg.autoRestart = (boolean) value; break;
            case "exitCode": cfg.exitCode = (int) value; break;
            case "statusRefreshSeconds": cfg.statusRefreshSeconds = (int) value; break;
            case "enableProcessMonitor": cfg.enableProcessMonitor = (boolean) value; break;
            case "enableNetworkMonitor": cfg.enableNetworkMonitor = (boolean) value; break;
            case "historyLimit": cfg.historyLimit = (int) value; break;
            case "logDirectory": cfg.logDirectory = (String) value; break;
            case "saveLogTime": cfg.saveLogTime = (boolean) value; break;
            case "timeFormat": cfg.timeFormat = (String) value; break;
            case "command": cfg.command = (String) value; break;
            case "stopCommand": cfg.stopCommand = (String) value; break;
            case "exitDetectionMode": cfg.exitDetectionMode = (String) value; break;
            case "gracefulStopTimeoutSeconds": cfg.gracefulStopTimeoutSeconds = (int) value; break;
            case "outputAlone": cfg.outputAlone = (boolean) value; break;
            case "outputWithTime": cfg.outputWithTime = (boolean) value; break;
            case "outputTimeFormat": cfg.outputTimeFormat = (String) value; break;
            case "sshPasswordHash": cfg.sshPasswordHash = value == null ? "" : (String) value; break;
        }
        saveToml();
    }
}
