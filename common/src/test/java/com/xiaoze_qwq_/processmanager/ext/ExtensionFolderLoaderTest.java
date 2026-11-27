package com.xiaoze_qwq_.processmanager.ext;

import com.xiaoze_qwq_.processmanager.ssh.HostKeySpec;
import com.xiaoze_qwq_.processmanager.ssh.SshTransportCustomizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ExtensionFolderLoaderTest {

    static final class Customizer implements SshTransportCustomizer {
        final String id;
        final List<String> kex;

        Customizer(String id, String... kex) {
            this.id = id;
            this.kex = List.of(kex);
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public List<String> kexAlgorithms() {
            return kex;
        }
    }

    @Test
    void mergeAlgorithmListsFirstWinsByInputOrder() {
        List<SshTransportCustomizer> customizers = List.of(
                new Customizer("b-cipher", "curve25519-sha256"),
                new Customizer("a-cipher", "sntrup761x25519-sha512", "mlkem768x25519-sha256"),
                new Customizer("c-cipher", "diffie-hellman-group14-sha256")
        );
        // 合并遵循传入顺序（jar 名字典序）：先 b-cipher 的 curve25519-sha256，再 a-cipher 的两项，最后 c-cipher
        List<String> merged = ExtensionFolderLoader.mergeAlgorithmLists(customizers, SshTransportCustomizer::kexAlgorithms);
        assertEquals(List.of("curve25519-sha256", "sntrup761x25519-sha512", "mlkem768x25519-sha256", "diffie-hellman-group14-sha256"), merged);
    }

    @Test
    void mergeAlgorithmListsEmptyFallsBack() {
        assertTrue(ExtensionFolderLoader.mergeAlgorithmLists(List.of(), SshTransportCustomizer::kexAlgorithms).isEmpty());
        List<SshTransportCustomizer> emptyCustomizers = List.of(new Customizer("empty"));
        assertTrue(ExtensionFolderLoader.mergeAlgorithmLists(emptyCustomizers, SshTransportCustomizer::kexAlgorithms).isEmpty());
    }

    @Test
    void missingFolderScansNothing(@TempDir Path temp) throws Exception {
        Path missing = temp.resolve("nope");
        assertFalse(Files.exists(missing));
        ExtensionFolderLoader.resetForTest();
        ExtensionFolderLoader.loadFolder(missing,
                "META-INF/services/com.xiaoze_qwq_.processmanager.crypto.PasswordCipher", "ciphers");
        List<LoadedExtension> loaded = ExtensionFolderLoader.getLoadedExtensions();
        assertTrue(loaded.isEmpty(), "missing folder should not produce any loaded extension");
    }

    @Test
    void jarWithMissingSpiClassRecordsFailed(@TempDir Path temp) throws Exception {
        // 构造最小 jar：SPI 声明指向不存在的类
        ExtensionFolderLoader.resetForTest();
        Path jar = temp.resolve("bad.jar");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jar))) {
            String entry = "META-INF/services/com.xiaoze_qwq_.processmanager.ssh.SshTransportCustomizer";
            zos.putNextEntry(new ZipEntry(entry));
            zos.write("com.example.NoSuchCustomizer".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        ExtensionFolderLoader.loadJar(jar,
                "META-INF/services/com.xiaoze_qwq_.processmanager.ssh.SshTransportCustomizer", "ssh-ext");
        List<LoadedExtension> loaded = ExtensionFolderLoader.getLoadedExtensions();
        assertEquals(1, loaded.size());
        LoadedExtension ext = loaded.get(0);
        assertEquals("bad.jar", ext.jarName());
        assertEquals("ssh-ext", ext.folder());
        assertEquals(ExtensionStatus.FAILED, ext.status());
        assertNotNull(ext.error());
    }

    @Test
    void emptyJarRecordsSkipped(@TempDir Path temp) throws Exception {
        ExtensionFolderLoader.resetForTest();
        Path jar = temp.resolve("empty.jar");
        Files.createFile(jar);
        ExtensionFolderLoader.loadJar(jar,
                "META-INF/services/com.xiaoze_qwq_.processmanager.crypto.PasswordCipher", "ciphers");
        List<LoadedExtension> loaded = ExtensionFolderLoader.getLoadedExtensions();
        assertEquals(1, loaded.size());
        LoadedExtension ext = loaded.get(0);
        assertEquals("empty.jar", ext.jarName());
        assertEquals("ciphers", ext.folder());
        assertEquals(ExtensionStatus.SKIPPED, ext.status());
    }
}
