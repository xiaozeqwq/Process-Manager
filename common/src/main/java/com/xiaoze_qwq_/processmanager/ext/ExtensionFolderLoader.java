package com.xiaoze_qwq_.processmanager.ext;

import com.xiaoze_qwq_.processmanager.crypto.PasswordCipher;
import com.xiaoze_qwq_.processmanager.crypto.PasswordCipherRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.TreeMap;

/**
 * 扫描服务端根目录下的扩展文件夹，按 jar 独立类加载器加载算法扩展：
 * <ul>
 *   <li>{@code ciphers/} 文件夹：加载 {@link PasswordCipher} SPI 实现，注册到 {@link PasswordCipherRegistry}</li>
 *   <li>{@code ssh-ext/} 文件夹：加载 {@link SshTransportCustomizer} SPI 实现</li>
 * </ul>
 * 两个文件夹均位于服务端根目录（即配置目录的父目录）。文件夹不存在时静默跳过；
 * 单个 jar 加载失败只记录警告，不影响其他 jar 或服务器启动。
 */
public final class ExtensionFolderLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger("PM-Ext");

    private static final Path SERVER_ROOT = Paths.get(System.getProperty("user.dir"));
    /** 密码哈希算法扩展文件夹（服务器根目录/cihpers/）。 */
    public static final Path CIPHERS_FOLDER = SERVER_ROOT.resolve("ciphers");
    /** SSH 传输算法扩展文件夹（服务器根目录/ssh-ext/）。 */
    public static final Path SSH_EXT_FOLDER = SERVER_ROOT.resolve("ssh-ext");

    private static final String PASSWORD_CIPHER_SPI =
            "META-INF/services/com.xiaoze_qwq_.processmanager.crypto.PasswordCipher";
    private static final String SSH_TRANSPORT_CUSTOMIZER_SPI =
            "META-INF/services/com.xiaoze_qwq_.processmanager.ssh.SshTransportCustomizer";

    /** 各文件夹下已加载的扩展记录，按文件名字典序。 */
    private static final TreeMap<String, LoadedExtension> LOADED = new TreeMap<>();
    /** ssh-ext 自定义器实例，按 id 保留（重复 id 首个优先）。 */
    private static final java.util.Map<String, SshTransportCustomizer> CUSTOMIZERS = new java.util.LinkedHashMap<>();

    private ExtensionFolderLoader() {
    }

    /** 加载两个扩展文件夹中的所有扩展 jar。可安全多次调用（第二次调用会覆盖记录）。 */
    public static synchronized void loadAll() {
        LOADED.clear();
        CUSTOMIZERS.clear();
        loadFolder(CIPHERS_FOLDER, PASSWORD_CIPHER_SPI, "ciphers");
        loadFolder(SSH_EXT_FOLDER, SSH_TRANSPORT_CUSTOMIZER_SPI, "ssh-ext");
        logSummary();
    }

    /** 当前所有已加载扩展的记录（按 jar 名字典序）。 */
    public static List<LoadedExtension> getLoadedExtensions() {
        return new ArrayList<>(LOADED.values());
    }

    /** ssh-ext 文件夹中已注册的传输自定义器（jar 名字典序）。 */
    public static List<SshTransportCustomizer> getTransportCustomizers() {
        return new ArrayList<>(CUSTOMIZERS.values());
    }

    /** 按 jar 名字典序合并所有自定义器贡献的算法列表：首个非空值获胜，后续值被忽略。
     *  返回空列表表示回退至 sshd 内置默认。 */
    public static List<String> mergeAlgorithmLists(List<SshTransportCustomizer> customizers,
                                                  java.util.function.Function<SshTransportCustomizer, List<String>> extractor) {
        List<String> merged = new ArrayList<>();
        for (SshTransportCustomizer c : customizers) {
            List<String> list = extractor.apply(c);
            if (list != null && !list.isEmpty()) {
                merged.addAll(list);
                LOGGER.debug("PM-Ext customizer '{}' contributed algorithms: {}", c.id(), list);
            }
        }
        return merged;
    }

    private static void loadFolder(Path folder, String spiResourceName, String folderName) {
        if (!Files.isDirectory(folder)) {
            LOGGER.debug("Extension folder {} does not exist; skip scanning", folder);
            return;
        }
        List<Path> jars = new ArrayList<>();
        try (var stream = Files.list(folder)) {
            stream.filter(p -> p.toString().endsWith(".jar"))
                  .filter(p -> Files.isRegularFile(p))
                  .forEach(jars::add);
        } catch (IOException e) {
            LOGGER.warn("Failed to list extension folder {}: {}", folder, e.getMessage());
            return;
        }
        jars.sort((p1, p2) -> p1.getFileName().compareTo(p2.getFileName()));

        for (Path jar : jars) {
            loadJar(jar, spiResourceName, folderName);
        }
    }

    private static void loadJar(Path jar, String spiResourceName, String folderName) {
        String jarName = jar.getFileName().toString();
        LOGGER.debug("Loading extension jar {} from folder {}", jarName, folderName);
        List<String> registeredIds = new ArrayList<>();
        int loadedCount = 0;
        try (var loader = new URLClassLoader(new URL[]{jar.toUri().toURL()},
                ExtensionFolderLoader.class.getClassLoader())) {
            Class<?> spiType = spiClass(spiResourceName);
            ServiceLoader<?> spi = ServiceLoader.load(spiType, loader);
            for (Object impl : spi) {
                try {
                    if (impl instanceof PasswordCipher cipher) {
                        String id = cipher.id();
                        PasswordCipherRegistry.register(cipher);
                        registeredIds.add(id);
                        loadedCount++;
                        LOGGER.info("PM-Ext loaded cipher '{}' from {}", id, jarName);
                    } else if (impl instanceof SshTransportCustomizer customizer) {
                        String id = customizer.id();
                        if (!CUSTOMIZERS.containsKey(id)) {
                            CUSTOMIZERS.put(id, customizer);
                            loadedCount++;
                            LOGGER.info("PM-Ext loaded transport customizer '{}' from {}", id, jarName);
                        } else {
                            LOGGER.warn("PM-Ext duplicate transport customizer id '{}' from {} ignored", id, jarName);
                        }
                    } else {
                        LOGGER.warn("PM-Ext implementation in {} is neither PasswordCipher nor SshTransportCustomizer", jarName);
                    }
                } catch (Throwable t) {
                    LOGGER.warn("PM-Ext failed to register implementation from {}", jarName, t);
                    LOADED.put(jarName, new LoadedExtension(jarName, folderName, ExtensionStatus.FAILED,
                            t.getMessage(), List.of(), null));
                    return;
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("PM-Ext failed to load jar {}", jarName, t);
            LOADED.put(jarName, new LoadedExtension(jarName, folderName, ExtensionStatus.FAILED,
                    t.getMessage(), List.of(), null));
            return;
        }
        String transportId = loadedCount > 0 && "ssh-ext".equals(folderName) ? registeredIds.get(0) : null;
        if (loadedCount == 0) {
            LOADED.put(jarName, new LoadedExtension(jarName, folderName, ExtensionStatus.SKIPPED,
                    "no SPI implementation found for " + spiResourceName,
                    registeredIds, null));
        } else {
            LOADED.put(jarName, new LoadedExtension(jarName, folderName, ExtensionStatus.SUCCESS,
                    null, registeredIds, transportId));
        }
    }

    /** 测试专用：清空加载记录与自定义器缓存。 */
    static void resetForTest() {
        LOADED.clear();
        CUSTOMIZERS.clear();
    }

    private static void logSummary() {
        LOGGER.info("PM-Ext extension scan complete: {} jars processed",
                LOADED.size());
        for (LoadedExtension ext : LOADED.values()) {
            LOGGER.info("PM-Ext {} {} (folder={}): {}",
                    ext.status(), ext.jarName(), ext.folder(),
                    ext.error() == null ? "loaded ids=" + ext.cipherIds() + " transport=" + ext.transportId()
                            : "error=" + ext.error());
        }
    }
}