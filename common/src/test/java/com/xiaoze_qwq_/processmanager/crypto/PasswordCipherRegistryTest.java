package com.xiaoze_qwq_.processmanager.crypto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordCipherRegistryTest {

    @Test
    void bcryptRoundTrip() {
        PasswordCipher bcrypt = PasswordCipherRegistry.get("bcrypt");
        assertNotNull(bcrypt);
        String hash = bcrypt.hash("secret-password-123");
        assertTrue(bcrypt.verify("secret-password-123", hash));
        assertFalse(bcrypt.verify("wrong", hash));
        assertTrue(hash.startsWith("$2b$"), "bcrypt hash should carry its native prefix");
    }

    @Test
    void bcryptSaltsAreRandom() {
        PasswordCipher bcrypt = PasswordCipherRegistry.get("bcrypt");
        assertNotEquals(bcrypt.hash("same"), bcrypt.hash("same"));
    }

    @Test
    void pbkdf2RoundTrip() {
        PasswordCipher pbkdf2 = PasswordCipherRegistry.get("pbkdf2-sha512");
        assertNotNull(pbkdf2);
        String hash = pbkdf2.hash("another-secret-456");
        assertTrue(hash.startsWith("pbkdf2-sha512$"), "new format must be self-identifying");
        assertTrue(pbkdf2.verify("another-secret-456", hash));
        assertFalse(pbkdf2.verify("wrong", hash));
    }

    @Test
    void pbkdf2LegacyFormatStillVerifies() {
        PasswordCipher pbkdf2 = PasswordCipherRegistry.get("pbkdf2-sha512");
        String modern = pbkdf2.hash("legacy-check");
        String legacy = modern.substring("pbkdf2-sha512$".length());
        assertTrue(pbkdf2.matchesLegacy(legacy), "legacy format should be detected as legacy");
        assertFalse(pbkdf2.matches(legacy), "legacy format must not match the modern format");
        assertTrue(pbkdf2.verify("legacy-check", legacy));
        assertFalse(pbkdf2.verify("wrong", legacy));
    }

    @Test
    void registryDetectsAlgorithmsByFormat() {
        String bcryptHash = PasswordCipherRegistry.get("bcrypt").hash("x");
        assertEquals("bcrypt", PasswordCipherRegistry.detect(bcryptHash).id());

        String pbkdf2Hash = PasswordCipherRegistry.get("pbkdf2-sha512").hash("x");
        assertEquals("pbkdf2-sha512", PasswordCipherRegistry.detect(pbkdf2Hash).id());

        String legacy = pbkdf2Hash.substring("pbkdf2-sha512$".length());
        assertEquals("pbkdf2-sha512", PasswordCipherRegistry.detect(legacy).id());

        assertNull(PasswordCipherRegistry.detect("garbage"));
        assertNull(PasswordCipherRegistry.detect(""));
        assertNull(PasswordCipherRegistry.detect(null));
    }

    @Test
    void registryVerifyAutoDetects() {
        String hash = PasswordCipherRegistry.get("bcrypt").hash("auto-detect");
        assertTrue(PasswordCipherRegistry.verify("auto-detect", hash));
        assertFalse(PasswordCipherRegistry.verify("wrong", hash));
        assertFalse(PasswordCipherRegistry.verify("x", "unknown-format"));
    }

    @Test
    void defaultCipherSwitchesAndHashes() {
        String original = PasswordCipherRegistry.getDefaultId();
        try {
            assertTrue(PasswordCipherRegistry.setDefault("pbkdf2-sha512"));
            assertEquals("pbkdf2-sha512", PasswordCipherRegistry.getDefaultId());
            String hash = PasswordCipherRegistry.hash("default-alg");
            assertTrue(hash.startsWith("pbkdf2-sha512$"));

            assertFalse(PasswordCipherRegistry.setDefault("does-not-exist"));
            assertEquals("pbkdf2-sha512", PasswordCipherRegistry.getDefaultId());
        } finally {
            assertTrue(PasswordCipherRegistry.setDefault(original));
        }
    }

    @Test
    void builtinsAreRegistered() {
        assertTrue(PasswordCipherRegistry.isRegistered("bcrypt"));
        assertTrue(PasswordCipherRegistry.isRegistered("pbkdf2-sha512"));
        assertFalse(PasswordCipherRegistry.isRegistered("argon2id"));
        assertTrue(PasswordCipherRegistry.ids().contains("bcrypt"));
    }

    @Test
    void malformedStoredValuesAreRejected() {
        PasswordCipher pbkdf2 = PasswordCipherRegistry.get("pbkdf2-sha512");
        assertFalse(pbkdf2.verify("x", "pbkdf2-sha512$"));
        assertFalse(pbkdf2.verify("x", "pbkdf2-sha512$abc"));
        assertFalse(pbkdf2.verify("x", "pbkdf2-sha512$0$abc$abc"));
        assertFalse(pbkdf2.verify("x", "pbkdf2-sha512$bad$abc$abc"));
        PasswordCipher bcrypt = PasswordCipherRegistry.get("bcrypt");
        assertFalse(bcrypt.verify("x", ""));
        assertFalse(bcrypt.verify("x", null));
        assertFalse(pbkdf2.verify(null, "pbkdf2-sha512$1$aa$bb"));
    }
}
