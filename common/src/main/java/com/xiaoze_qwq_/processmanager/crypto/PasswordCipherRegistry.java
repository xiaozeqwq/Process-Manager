package com.xiaoze_qwq_.processmanager.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * 密码哈希算法注册表。内置 bcrypt 与 PBKDF2-SHA512 两个实现，
 * 并通过 Java SPI（META-INF/services）加载第三方 mod jar 提供的算法模块。
 *
 * <p>新哈希使用 {@link #getDefault()} 指向的算法（由配置 [hashing] algorithm 决定），
 * 校验时按存储格式自动识别算法，因此更换算法后旧哈希仍可校验。</p>
 */
public final class PasswordCipherRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger("PM-Crypto");
    private static final Map<String, PasswordCipher> CIPHERS = new LinkedHashMap<>();
    private static volatile String defaultId = "bcrypt";

    static {
        register(new BcryptPasswordCipher());
        register(new Pbkdf2PasswordCipher());
        loadExternal();
    }

    private PasswordCipherRegistry() {
    }

    /** 注册算法；id 冲突时保留先注册者并记录告警（内置算法优先）。 */
    public static synchronized void register(PasswordCipher cipher) {
        if (cipher == null || cipher.id() == null || cipher.id().isBlank()) return;
        String id = cipher.id().trim().toLowerCase(java.util.Locale.ROOT);
        if (CIPHERS.containsKey(id)) {
            LOGGER.warn("PM-Crypto duplicate cipher id '{}' ignored ({})", id, cipher.getClass().getName());
            return;
        }
        CIPHERS.put(id, cipher);
    }

    /** 扫描 SPI 提供者；单个模块初始化失败不影响其余模块。 */
    private static void loadExternal() {
        try {
            for (PasswordCipher cipher : ServiceLoader.load(PasswordCipher.class,
                    PasswordCipher.class.getClassLoader())) {
                try {
                    register(cipher);
                    LOGGER.info("PM-Crypto loaded external cipher '{}' from {}",
                            cipher.id(), cipher.getClass().getName());
                } catch (Throwable t) {
                    LOGGER.warn("PM-Crypto failed to register external cipher {}", cipher.getClass().getName(), t);
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("PM-Crypto failed to scan external cipher modules", t);
        }
    }

    /** 按 id 查找算法，未注册返回 null。 */
    public static PasswordCipher get(String id) {
        if (id == null) return null;
        return CIPHERS.get(id.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /** 已注册的全部算法 id（保持注册顺序）。 */
    public static List<String> ids() {
        return new ArrayList<>(CIPHERS.keySet());
    }

    /** 是否已注册某算法。 */
    public static boolean isRegistered(String id) {
        return get(id) != null;
    }

    /** 当前生成新哈希使用的默认算法。 */
    public static PasswordCipher getDefault() {
        PasswordCipher cipher = get(defaultId);
        return cipher != null ? cipher : get("bcrypt");
    }

    /** 当前默认算法 id。 */
    public static String getDefaultId() {
        PasswordCipher cipher = getDefault();
        return cipher != null ? cipher.id() : "bcrypt";
    }

    /** 切换默认算法（仅影响之后生成的新哈希）；返回是否切换成功。 */
    public static boolean setDefault(String id) {
        if (!isRegistered(id)) return false;
        defaultId = id.trim().toLowerCase(java.util.Locale.ROOT);
        return true;
    }

    /** 用默认算法生成哈希。 */
    public static String hash(String plain) {
        return getDefault().hash(plain);
    }

    /** 按存储格式自动识别算法并校验；无法识别的格式返回 false。 */
    public static boolean verify(String plain, String stored) {
        PasswordCipher cipher = detect(stored);
        return cipher != null && cipher.verify(plain, stored);
    }

    /**
     * 识别存储哈希所属算法：先按各算法当前格式精确匹配，
     * 再回退到历史遗留格式（兼容旧版本数据）。
     */
    public static PasswordCipher detect(String stored) {
        if (stored == null || stored.isEmpty()) return null;
        for (PasswordCipher cipher : CIPHERS.values()) {
            try {
                if (cipher.matches(stored)) return cipher;
            } catch (Throwable ignored) {
            }
        }
        for (PasswordCipher cipher : CIPHERS.values()) {
            try {
                if (cipher.matchesLegacy(stored)) return cipher;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 只读视图，供诊断命令展示。 */
    public static List<PasswordCipher> all() {
        return Collections.unmodifiableList(new ArrayList<>(CIPHERS.values()));
    }
}
