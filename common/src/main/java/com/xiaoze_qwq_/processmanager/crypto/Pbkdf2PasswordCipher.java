package com.xiaoze_qwq_.processmanager.crypto;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 内置 PBKDF2-HMAC-SHA512 算法模块。
 * 新格式：pbkdf2-sha512$&lt;iterations&gt;$&lt;saltB64&gt;$&lt;hashB64&gt;；
 * 兼容校验旧版无前缀格式 &lt;iterations&gt;$&lt;saltB64&gt;$&lt;hashB64&gt;（历史 pmpassword）。
 */
public final class Pbkdf2PasswordCipher implements PasswordCipher {
    public static final String ID = "pbkdf2-sha512";
    public static final String PREFIX = ID + "$";
    private static final int ITERATIONS = 120_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 512;
    private static final SecureRandom RNG = new SecureRandom();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String hash(String plain) {
        byte[] salt = new byte[SALT_BYTES];
        RNG.nextBytes(salt);
        byte[] hash = pbkdf2(plain.toCharArray(), salt, ITERATIONS);
        return PREFIX + ITERATIONS
                + "$" + Base64.getEncoder().encodeToString(salt)
                + "$" + Base64.getEncoder().encodeToString(hash);
    }

    @Override
    public boolean verify(String plain, String stored) {
        if (plain == null || stored == null || stored.isEmpty()) return false;
        String body = stored.startsWith(PREFIX) ? stored.substring(PREFIX.length()) : stored;
        String[] parts = body.split("\\$");
        if (parts.length != 3) return false;
        try {
            int iterations = Integer.parseInt(parts[0]);
            if (iterations <= 0) return false;
            byte[] salt = Base64.getDecoder().decode(parts[1]);
            byte[] expected = Base64.getDecoder().decode(parts[2]);
            byte[] actual = pbkdf2(plain.toCharArray(), salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean matches(String stored) {
        return stored != null && stored.startsWith(PREFIX) && stored.indexOf('$', PREFIX.length()) > 0;
    }

    /** 旧版 Config.hashPassword 输出的无前缀格式：iterations$salt$hash。 */
    @Override
    public boolean matchesLegacy(String stored) {
        if (stored == null || stored.isEmpty() || stored.startsWith("$")) return false;
        String[] parts = stored.split("\\$");
        if (parts.length != 3) return false;
        try {
            return Integer.parseInt(parts[0]) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512");
            return factory.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 not available", e);
        }
    }
}
