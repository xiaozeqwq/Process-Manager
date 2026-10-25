package com.xiaoze_qwq_.processmanager;

import com.xiaoze_qwq_.processmanager.client.ClientAutoLoginHandler;
import com.xiaoze_qwq_.processmanager.command.CommandRegistry;
import com.xiaoze_qwq_.processmanager.config.Config;
import com.xiaoze_qwq_.processmanager.network.NetworkRegistry;
import com.xiaoze_qwq_.processmanager.ssh.ListenerHook;
import com.xiaoze_qwq_.processmanager.ssh.SshServerHost;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.networking.NetworkManager;
import dev.architectury.utils.Env;
import dev.architectury.utils.EnvExecutor;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerConnectionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ProcessManager {
    public static final String MOD_ID = "processmanager";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static volatile MinecraftServer serverInstance;
    private static ScheduledExecutorService sessionPurger;

    public static void init() {
        NetworkRegistry.register();
        CommandRegistrationEvent.EVENT.register((dispatcher, registry, selection) -> CommandRegistry.register(dispatcher));
        LifecycleEvent.SERVER_STARTED.register(ProcessManager::onServerStarted);
        LifecycleEvent.SERVER_STOPPING.register(ProcessManager::onServerStopping);
        PlayerEvent.PLAYER_JOIN.register(player -> {
            if (SessionManager.getInstance().isAuthorized(player)) {
                LOGGER.info("Player {} reconnected with a valid PM auth cookie.", player.getGameProfile().getName());
            }
        });
        PlayerEvent.PLAYER_QUIT.register(player -> InstanceManager.getInstance().removeDisconnectedPlayer(player.getUUID()));
        EnvExecutor.runInEnv(Env.CLIENT, () -> () -> ClientAutoLoginHandler.register());
    }

    private static void onServerStarted(MinecraftServer server) {
        serverInstance = server;
        LOGGER.info("Process Manager started. LuckPerms not used, password-only mode.");
        SessionManager.getInstance().load();
        SessionManager.getInstance().purgeExpired();
        if (!Config.hasPassword()) {
            LOGGER.warn("PM password is not set. Use /pm set password <password> from the server console to enable verification.");
        } else {
            LOGGER.info("PM password is set. Players can verify with /pm verify <password>.");
        }
        if (isLogCommandsEnabled()) {
            LOGGER.warn("server.properties log-commands is enabled. The /pm verify password will be written to the server log. Consider setting log-commands=false to avoid leaking passwords.");
        }
        sessionPurger = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PM-SessionPurger");
            t.setDaemon(true);
            return t;
        });
        sessionPurger.scheduleAtFixedRate(SessionManager.getInstance()::purgeExpired, 10, 10, TimeUnit.MINUTES);
        InstanceManager.getInstance().start();
        startEmbeddedSsh(server);
    }

    private static void startEmbeddedSsh(MinecraftServer server) {
        if (!Config.hasConfiguredSshInstance()) {
            LOGGER.info("No instance has SSH enabled; embedded SSH server not started.");
            return;
        }
        try {
            ServerConnectionListener listener = server.getConnection();
            if (listener == null) {
                LOGGER.warn("ServerConnectionListener unavailable; embedded SSH server not started.");
                return;
            }
            SshServerHost.start();
            int localPort = SshServerHost.getPort();
            if (localPort <= 0) {
                LOGGER.warn("Embedded SSH server failed to bind; not installed.");
                return;
            }
            int installed = ListenerHook.install(listener, localPort);
            if (installed == 0) {
                SshServerHost.stop();
                LOGGER.warn("No Minecraft listener channels found; embedded SSH server not installed.");
                return;
            }
            LOGGER.info("Embedded SSH server multiplexed over the Minecraft port. Log in with any username and the instance SSH password.");
        } catch (Throwable t) {
            LOGGER.error("Failed to start embedded SSH server", t);
            try {
                if (server.getConnection() != null) {
                    ListenerHook.uninstall(server.getConnection());
                }
            } catch (Throwable cleanup) {
                LOGGER.warn("Failed to uninstall SSH listener after startup failure", cleanup);
            }
            try {
                SshServerHost.stop();
            } catch (Throwable cleanup) {
                LOGGER.warn("Failed to clean up embedded SSH server after startup failure", cleanup);
            }
        }
    }

    private static void stopEmbeddedSsh() {
        MinecraftServer server = serverInstance;
        if (server != null && server.getConnection() != null) {
            ListenerHook.uninstall(server.getConnection());
        }
        SshServerHost.stop();
    }

    /** 在实例 SSH 配置发生变化后调用：先彻底停掉旧 sshd 与旧会话，再按新配置决定是否启动。 */
    public static boolean applySshConfigurationChange() {
        MinecraftServer server = serverInstance;
        if (server == null) return false;
        stopEmbeddedSsh();
        if (!Config.hasConfiguredSshInstance()) return true;
        ensureEmbeddedSsh();
        return SshServerHost.getPort() > 0;
    }

    public static void disconnectSshSessions() {
        SshServerHost.disconnectAllSessions();
    }

    /** 在实例 SSH 配置发生变化后调用：若已有实例启用 SSH 而服务端尚未启动，则懒启动。 */
    public static void ensureEmbeddedSsh() {
        if (SshServerHost.getPort() > 0) return;
        MinecraftServer server = serverInstance;
        if (server == null) return;
        startEmbeddedSsh(server);
    }

    private static void onServerStopping(MinecraftServer server) {
        stopEmbeddedSsh();
        if (sessionPurger != null) {
            sessionPurger.shutdownNow();
            sessionPurger = null;
        }
        InstanceManager.getInstance().stop();
    }

    public static boolean isLogCommandsEnabled() {
        try {
            Path props = Paths.get("server.properties");
            if (Files.exists(props)) {
                for (String line : Files.readAllLines(props, StandardCharsets.UTF_8)) {
                    if (line.startsWith("log-commands=")) {
                        return line.substring("log-commands=".length()).trim().equalsIgnoreCase("true");
                    }
                }
            }
        } catch (IOException e) {
            // ignore
        }
        return false;
    }

    public static MinecraftServer getServer() {
        return serverInstance;
    }

    public static void executeOnServerThread(Runnable action) {
        MinecraftServer server = serverInstance;
        if (server == null) return;
        if (server.isSameThread()) {
            runSafely(action);
        } else {
            server.execute(() -> runSafely(action));
        }
    }

    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        if (player == null || player.connection == null) return;
        executeOnServerThread(() -> NetworkManager.sendToPlayer(player, payload));
    }

    public static void sendToAllPlayers(CustomPacketPayload payload) {
        MinecraftServer server = serverInstance;
        if (server == null) return;
        executeOnServerThread(() -> NetworkManager.sendToPlayers(server.getPlayerList().getPlayers(), payload));
    }

    private static void runSafely(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            LOGGER.warn("Error executing server-thread network task", e);
        }
    }
}
