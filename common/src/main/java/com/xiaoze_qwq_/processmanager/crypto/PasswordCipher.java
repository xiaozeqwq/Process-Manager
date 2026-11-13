package com.xiaoze_qwq_.processmanager.crypto;

/**
 * 密码哈希算法模块接口。所有 PM 密码（主密码、实例 SSH 密码、菜单密码）
 * 的哈希与校验都通过该接口完成，实现类可通过 Java SPI（META-INF/services）
 * 由独立 mod jar 提供，开发规范见仓库 skills/crypto-module/SKILL.md。
 *
 * <p>实现要求：</p>
 * <ul>
 *   <li>仅依赖 JDK，禁止引用 Minecraft、加载器或主模组内部类；</li>
 *   <li>输出必须携带唯一的算法前缀，便于 {@link PasswordCipherRegistry#detect(String)} 识别；</li>
 *   <li>verify 必须使用常量时间比较，禁止记录明文密码；</li>
 *   <li>实现必须线程安全（verify 可能被多个会话并发调用）。</li>
 * </ul>
 */
public interface PasswordCipher {
    /** 算法唯一标识（同时作为推荐输出前缀），如 "bcrypt"、"pbkdf2-sha512"。 */
    String id();

    /** 对明文密码生成自识别格式的哈希字符串。 */
    String hash(String plain);

    /**
     * 校验明文是否与存储哈希匹配。stored 可能为本算法任意历史格式的输出，
     * 非本算法格式时应返回 false（交由注册表继续探测）。
     */
    boolean verify(String plain, String stored);

    /** stored 是否为本算法当前输出格式（用于自动识别，须唯一无歧义）。 */
    boolean matches(String stored);

    /**
     * stored 是否为本算法的历史遗留格式。仅在所有算法的 {@link #matches}
     * 都未命中时才作为回退探测，用于兼容旧版本数据。
     */
    default boolean matchesLegacy(String stored) {
        return false;
    }
}
