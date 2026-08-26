package com.xiaoze_qwq_.mis.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Properties;
import java.util.UUID;

/**
 * 客户端 Cookie 本地存储：
 * - 按服务器地址区分保存令牌（mis_cookie.dat，Properties 格式，等价于文档要求的 JSON 结构）
 * - 全局设备标识（mis_device.id）
 */
public class ClientCookieStorage {

    private static Path cookieFile() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("mis_cookie.dat");
    }

    private static Path deviceFile() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("mis_device.id");
    }

    /** 当前连接服务器的地址标识（用于本地令牌隔离，避免跨服误用） */
    public static String currentServerAddress() {
        Minecraft mc = Minecraft.getInstance();
        ServerData serverData = mc.getCurrentServer();
        if (serverData != null && serverData.ip != null && !serverData.ip.isEmpty()) {
            return serverData.ip;
        }
        return "local";
    }

    /** 读取或创建全局设备标识（UUID），文件权限设置为 600 */
    public static String getOrCreateDeviceId() {
        Path file = deviceFile();
        try {
            if (Files.exists(file)) {
                String id = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
                if (!id.isEmpty()) return id;
            }
            String id = UUID.randomUUID().toString();
            Files.write(file, id.getBytes(StandardCharsets.UTF_8));
            setRestrictivePermissions(file);
            return id;
        } catch (IOException e) {
            return UUID.randomUUID().toString();
        }
    }

    /** 更新对应服务器的令牌 */
    public static void saveCookie(String serverAddress, String token, long expiresAt) {
        Properties props = readAll();
        props.setProperty(serverAddress + ".token", token);
        props.setProperty(serverAddress + ".expiresAt", String.valueOf(expiresAt));
        props.setProperty(serverAddress + ".deviceId", getOrCreateDeviceId());
        writeAll(props);
    }

    /** 返回对应服务器的令牌；本地已过期时返回 null */
    public static String loadCookie(String serverAddress) {
        Properties props = readAll();
        String token = props.getProperty(serverAddress + ".token");
        if (token == null || token.isEmpty()) return null;
        String expiresAt = props.getProperty(serverAddress + ".expiresAt");
        if (expiresAt != null) {
            try {
                if (System.currentTimeMillis() >= Long.parseLong(expiresAt)) return null;
            } catch (NumberFormatException ignored) {
            }
        }
        return token;
    }

    /** 移除对应服务器的条目 */
    public static void deleteCookie(String serverAddress) {
        Properties props = readAll();
        if (props.remove(serverAddress + ".token") == null
                && props.remove(serverAddress + ".expiresAt") == null
                && props.remove(serverAddress + ".deviceId") == null) {
            return;
        }
        props.remove(serverAddress + ".expiresAt");
        props.remove(serverAddress + ".deviceId");
        writeAll(props);
    }

    private static Properties readAll() {
        Path file = cookieFile();
        Properties props = new Properties();
        if (!Files.exists(file)) return props;
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException ignored) {
        }
        return props;
    }

    private static void writeAll(Properties props) {
        try (OutputStream out = Files.newOutputStream(cookieFile())) {
            props.store(out, "MIS auto-login cookies");
        } catch (IOException ignored) {
        }
    }

    private static void setRestrictivePermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException e) {
            // 文件系统不支持 POSIX 权限时忽略
        }
    }
}
