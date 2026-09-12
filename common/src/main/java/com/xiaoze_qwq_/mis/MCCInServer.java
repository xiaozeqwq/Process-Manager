package com.xiaoze_qwq_.mis;

import com.xiaoze_qwq_.mis.client.ClientAutoLoginHandler;
import com.xiaoze_qwq_.mis.command.MISCommand;
import com.xiaoze_qwq_.mis.config.MISConfig;
import com.xiaoze_qwq_.mis.network.MISNetwork;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.networking.NetworkManager;
import dev.architectury.utils.Env;
import dev.architectury.utils.EnvExecutor;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
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

public class MCCInServer {
    public static final String MOD_ID = "mccinserver";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static volatile MinecraftServer serverInstance;
    private static ScheduledExecutorService sessionPurger;

    public static void init() {
        MISNetwork.register();
        CommandRegistrationEvent.EVENT.register((dispatcher, registry, selection) -> MISCommand.register(dispatcher));
        LifecycleEvent.SERVER_STARTED.register(MCCInServer::onServerStarted);
        LifecycleEvent.SERVER_STOPPING.register(MCCInServer::onServerStopping);
        PlayerEvent.PLAYER_JOIN.register(player -> {
            if (MISSessionManager.getInstance().isInMISMode(player)) {
                LOGGER.info("Player {} reconnected with a valid MIS auth cookie.", player.getGameProfile().getName());
            }
        });
        PlayerEvent.PLAYER_QUIT.register(player -> MCCManager.getInstance().removeDisconnectedPlayer(player.getUUID()));
        EnvExecutor.runInEnv(Env.CLIENT, () -> () -> ClientAutoLoginHandler.register());
    }

    private static void onServerStarted(MinecraftServer server) {
        serverInstance = server;
        LOGGER.info("MCC In Server started. LuckPerms not used, password-only mode.");
        MISSessionManager.getInstance().load();
        MISSessionManager.getInstance().purgeExpired();
        if (!MISConfig.hasPassword()) {
            LOGGER.warn("MIS password is not set. Use /mis set password <password> from the server console to enable verification.");
        } else {
            LOGGER.info("MIS password is set. Players can verify with /mis verify <password>.");
        }
        if (isLogCommandsEnabled()) {
            LOGGER.warn("server.properties log-commands is enabled. The /mis verify password will be written to the server log. Consider setting log-commands=false to avoid leaking passwords.");
        }
        sessionPurger = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MIS-SessionPurger");
            t.setDaemon(true);
            return t;
        });
        sessionPurger.scheduleAtFixedRate(MISSessionManager.getInstance()::purgeExpired, 10, 10, TimeUnit.MINUTES);
        MCCManager.getInstance().start();
    }

    private static void onServerStopping(MinecraftServer server) {
        if (sessionPurger != null) {
            sessionPurger.shutdownNow();
            sessionPurger = null;
        }
        MCCManager.getInstance().stop();
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
