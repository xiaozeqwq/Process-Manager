package com.xiaoze_qwq_.processmanager.crypto;

import at.favre.lib.crypto.bcrypt.BCrypt;

/**
 * 内置 bcrypt 算法模块，cost 12，输出 favre bcrypt 原生格式（$2b$ 前缀，
 * 天然自识别）。兼容校验 $2a$ / $2b$ / $2y$ 前缀的历史哈希。
 */
public final class BcryptPasswordCipher implements PasswordCipher {
    public static final String ID = "bcrypt";
    private static final int COST = 12;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String hash(String plain) {
        return BCrypt.with(BCrypt.Version.VERSION_2B).hashToString(COST, plain.toCharArray());
    }

    @Override
    public boolean verify(String plain, String stored) {
        if (plain == null || stored == null || stored.isEmpty()) return false;
        try {
            return BCrypt.verifyer().verify(plain.toCharArray(), stored).verified;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean matches(String stored) {
        if (stored == null || stored.length() < 4) return false;
        return stored.startsWith("$2a$") || stored.startsWith("$2b$")
                || stored.startsWith("$2y$") || stored.startsWith("$2c$");
    }
}
