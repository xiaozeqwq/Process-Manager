package com.xiaoze_qwq_.mis.config;

import com.moandjiezana.toml.Toml;
import com.xiaoze_qwq_.mis.MCCInServer;
import net.neoforged.fml.loading.FMLPaths;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

public class MISConfig {
    private static final Path CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve("mis_config.toml");
    private static final Path PASSWORD_FILE = FMLPaths.CONFIGDIR.get().resolve("mispassword");

    private static final String PBKDF2_ALGO = "PBKDF2WithHmacSHA512";
    private static final int PBKDF2_ITERATIONS = 120_000;
    private static final int SALT_BYTES = 16;
    private static final SecureRandom RNG = new SecureRandom();

    private static final ExecutorService HASH_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MIS-Hash");
        t.setDaemon(true);
        return t;
    });

    private static InstanceConfig defaultConfig;
    private static Map<String, InstanceConfig> instances = new ConcurrentHashMap<>();

    private static volatile String passwordHash = "";

    private static int verifyMaxAttempts = 5;
    private static int verifyLockoutSeconds = 60;
    private static final Map<UUID, long[]> verifyFailures = new ConcurrentHashMap<>();

    static {
        load();
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
                MCCInServer.LOGGER.error("Failed to read mispassword, password will be empty", e);
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
            MCCInServer.LOGGER.info("Password stored as PBKDF2 hash in mispassword.");
        } catch (IOException e) {
            MCCInServer.LOGGER.error("Failed to write password hash to mispassword", e);
        }
    }

    public static void clearPassword() {
        try {
            Files.deleteIfExists(PASSWORD_FILE);
        } catch (IOException e) {
            MCCInServer.LOGGER.error("Failed to remove mispassword", e);
        }
        passwordHash = "";
        verifyFailures.clear();
        MCCInServer.LOGGER.info("Password removed.");
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

    // ==================== 配置数据类 ====================
    public static class InstanceConfig {
        public String id;
        public String name = "";
        public boolean autoStart = false;
        public int startupDelaySeconds = 0;
        public String workingDirectory = "";
        public String executablePath = "MCC/MinecraftClient";
        public List<String> arguments = new ArrayList<>();
        public Map<String, String> environment = new HashMap<>();

        public boolean autoRestart = true;
        public int exitCode = 0;
        public int statusRefreshSeconds = 2;
        public boolean enableProcessMonitor = true;
        public boolean enableNetworkMonitor = true;
        public int historyLimit = 2000;
        public String logDirectory = "MCC-Output";
        public String compressionFormat = "tar.gz";
        public String compressionStrategy = "OLD";
        public int scheduledCompressionMinutes = 60;
        public int maxArchives = 10;
        public boolean saveLogTime = true;
        public String timeFormat = "HH:mm:ss";
        public boolean outputAlone = false;
        public boolean outputWithTime = true;
        public String outputTimeFormat = "HH:mm:ss";

        public void mergeMissing(InstanceConfig defaults) {
            if (name.isEmpty() && defaults != null) name = defaults.name;
            if (executablePath == null || executablePath.isEmpty()) executablePath = defaults.executablePath;
            if (arguments.isEmpty()) arguments = defaults.arguments;
            if (environment.isEmpty()) environment = defaults.environment;
            if (workingDirectory.isEmpty()) workingDirectory = defaults.workingDirectory;
            if (logDirectory.isEmpty()) logDirectory = defaults.logDirectory;
            if (statusRefreshSeconds <= 0) statusRefreshSeconds = defaults.statusRefreshSeconds;
            if (historyLimit <= 0) historyLimit = defaults.historyLimit;
            if (exitCode < 0) exitCode = defaults.exitCode;
            if (maxArchives <= 0) maxArchives = defaults.maxArchives;
        }
    }

    // ==================== 加载与保存 ====================
    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            defaultConfig = new InstanceConfig();
            instances.clear();
            InstanceConfig defInst = new InstanceConfig();
            defInst.id = "1";
            defInst.name = "MCC-1";
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
                defInst.name = "MCC-1";
                mergeWithDefault(defInst);
                instances.put("1", defInst);
            }

            // ==================== 实例校验 ====================
            Iterator<Map.Entry<String, InstanceConfig>> it = instances.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, InstanceConfig> entry = it.next();
                String id = entry.getKey();
                InstanceConfig cfg = entry.getValue();

                // ID 必须为纯数字
                if (!id.matches("\\d+")) {
                    MCCInServer.LOGGER.error("Instance ID '{}' is not a pure number. Skipping instance.", id);
                    it.remove();
                    continue;
                }

                // 名称不能是纯数字
                if (cfg.name != null && cfg.name.matches("\\d+")) {
                    MCCInServer.LOGGER.error("Instance '{}' has a numeric name '{}'. Please use a non-numeric name.", id, cfg.name);
                    it.remove();
                    continue;
                }

                // 检查名称是否重复（不区分大小写）
                String lowerName = (cfg.name != null ? cfg.name : "").toLowerCase(Locale.ROOT);
                for (Map.Entry<String, InstanceConfig> other : instances.entrySet()) {
                    if (other.getKey().equals(id)) continue;
                    String otherLower = (other.getValue().name != null ? other.getValue().name : "").toLowerCase(Locale.ROOT);
                    if (lowerName.equals(otherLower)) {
                        MCCInServer.LOGGER.error("Duplicate instance name '{}' (case-insensitive) between instances '{}' and '{}'. Skipping instance '{}'.",
                                cfg.name, id, other.getKey(), id);
                        it.remove();
                        break;
                    }
                }
            }

            saveToml();
            loadPasswordHash();
        } catch (Exception e) {
            MCCInServer.LOGGER.error("Failed to load mis_config.toml, using defaults", e);
            defaultConfig = new InstanceConfig();
            instances.clear();
            InstanceConfig defInst = new InstanceConfig();
            defInst.id = "1";
            defInst.name = "MCC-1";
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
        cfg.executablePath = table.getString("executablePath", "MCC/MinecraftClient");
        cfg.arguments = table.getList("arguments", new ArrayList<>());
        cfg.environment = table.getTable("environment") != null ? toStringMap(table.getTable("environment")) : new HashMap<>();
        cfg.autoRestart = table.getBoolean("autoRestart", true);
        cfg.exitCode = table.getLong("exitCode", 0L).intValue();
        cfg.statusRefreshSeconds = table.getLong("statusRefreshSeconds", 2L).intValue();
        cfg.enableProcessMonitor = table.getBoolean("enableProcessMonitor", true);
        cfg.enableNetworkMonitor = table.getBoolean("enableNetworkMonitor", true);
        cfg.historyLimit = table.getLong("historyLimit", 2000L).intValue();
        cfg.logDirectory = table.getString("logDirectory", "");
        cfg.compressionFormat = table.getString("compressionFormat", "tar.gz");
        cfg.compressionStrategy = table.getString("compressionStrategy", "OLD");
        cfg.scheduledCompressionMinutes = table.getLong("scheduledCompressionMinutes", 60L).intValue();
        cfg.maxArchives = table.getLong("maxArchives", 10L).intValue();
        cfg.saveLogTime = table.getBoolean("saveLogTime", true);
        cfg.timeFormat = table.getString("timeFormat", "HH:mm:ss");
        cfg.outputAlone = table.getBoolean("outputAlone", false);
        cfg.outputWithTime = table.getBoolean("outputWithTime", true);
        cfg.outputTimeFormat = table.getString("outputTimeFormat", "HH:mm:ss");
        return cfg;
    }

    private static void mergeWithDefault(InstanceConfig cfg) {
        if (cfg.name == null || cfg.name.isEmpty()) cfg.name = defaultConfig.name.isEmpty() ? cfg.id : defaultConfig.name;
        if (cfg.executablePath == null || cfg.executablePath.isEmpty()) cfg.executablePath = defaultConfig.executablePath;
        if (cfg.arguments.isEmpty() && !defaultConfig.arguments.isEmpty()) cfg.arguments = defaultConfig.arguments;
        if (cfg.environment.isEmpty() && !defaultConfig.environment.isEmpty()) cfg.environment = defaultConfig.environment;
        if (cfg.workingDirectory.isEmpty()) cfg.workingDirectory = defaultConfig.workingDirectory;
        if (cfg.logDirectory.isEmpty()) cfg.logDirectory = defaultConfig.logDirectory.isEmpty() ? "MCC-Output" : defaultConfig.logDirectory;
        if (cfg.statusRefreshSeconds <= 0) cfg.statusRefreshSeconds = defaultConfig.statusRefreshSeconds;
        if (cfg.historyLimit <= 0) cfg.historyLimit = defaultConfig.historyLimit;
        if (cfg.exitCode < 0) cfg.exitCode = defaultConfig.exitCode;
        if (cfg.maxArchives <= 0) cfg.maxArchives = defaultConfig.maxArchives;
    }

    private static Map<String, String> toStringMap(Toml table) {
        Map<String, String> map = new HashMap<>();
        table.toMap().forEach((k, v) -> map.put(k, v.toString()));
        return map;
    }

    private static void saveToml() {
        try (Writer writer = new OutputStreamWriter(Files.newOutputStream(CONFIG_PATH), StandardCharsets.UTF_8)) {
            writer.write("# MIS Configuration (TOML)\n\n");
            writer.write("[verify]\n");
            writer.write("maxAttempts = " + verifyMaxAttempts + "\n");
            writer.write("lockoutSeconds = " + verifyLockoutSeconds + "\n\n");
            writer.write("[default]\n");
            writeInstanceConfig(writer, defaultConfig, true);
            writer.write("\n");
            for (InstanceConfig cfg : instances.values()) {
                writer.write("[[instances]]\n");
                writer.write("id = \"" + cfg.id + "\"\n");
                writeInstanceConfig(writer, cfg, false);
                writer.write("\n");
            }
        } catch (IOException e) {
            MCCInServer.LOGGER.error("Failed to save mis_config.toml", e);
        }
    }

    private static void writeInstanceConfig(Writer writer, InstanceConfig cfg, boolean skipId) throws IOException {
        writer.write("name = \"" + cfg.name + "\"\n");
        writer.write("autoStart = " + cfg.autoStart + "\n");
        writer.write("startupDelaySeconds = " + cfg.startupDelaySeconds + "\n");
        writer.write("workingDirectory = \"" + cfg.workingDirectory + "\"\n");
        writer.write("executablePath = \"" + cfg.executablePath + "\"\n");
        writer.write("arguments = " + cfg.arguments.toString() + "\n");
        writer.write("environment = " + cfg.environment.toString() + "\n");
        writer.write("autoRestart = " + cfg.autoRestart + "\n");
        writer.write("exitCode = " + cfg.exitCode + "\n");
        writer.write("statusRefreshSeconds = " + cfg.statusRefreshSeconds + "\n");
        writer.write("enableProcessMonitor = " + cfg.enableProcessMonitor + "\n");
        writer.write("enableNetworkMonitor = " + cfg.enableNetworkMonitor + "\n");
        writer.write("historyLimit = " + cfg.historyLimit + "\n");
        writer.write("logDirectory = \"" + cfg.logDirectory + "\"\n");
        writer.write("compressionFormat = \"" + cfg.compressionFormat + "\"\n");
        writer.write("compressionStrategy = \"" + cfg.compressionStrategy + "\"\n");
        writer.write("scheduledCompressionMinutes = " + cfg.scheduledCompressionMinutes + "\n");
        writer.write("maxArchives = " + cfg.maxArchives + "\n");
        writer.write("saveLogTime = " + cfg.saveLogTime + "\n");
        writer.write("timeFormat = \"" + cfg.timeFormat + "\"\n");
        writer.write("outputAlone = " + cfg.outputAlone + "\n");
        writer.write("outputWithTime = " + cfg.outputWithTime + "\n");
        writer.write("outputTimeFormat = \"" + cfg.outputTimeFormat + "\"\n");
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
            case "executablePath": cfg.executablePath = (String) value; break;
            case "outputAlone": cfg.outputAlone = (boolean) value; break;
            case "outputWithTime": cfg.outputWithTime = (boolean) value; break;
            case "outputTimeFormat": cfg.outputTimeFormat = (String) value; break;
        }
        saveToml();
    }
}