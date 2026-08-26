package com.xiaoze_qwq_.mis;

import com.xiaoze_qwq_.mis.client.ClientAutoLoginHandler;
import com.xiaoze_qwq_.mis.command.MISCommand;
import com.xiaoze_qwq_.mis.config.MISConfig;
import com.xiaoze_qwq_.mis.network.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
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

@Mod(MCCInServer.MOD_ID)
public class MCCInServer {
    public static final String MOD_ID = "mccinserver";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static volatile MinecraftServer serverInstance;
    private ScheduledExecutorService sessionPurger;

    public MCCInServer(IEventBus modEventBus) {
        modEventBus.addListener(this::registerPayloads);
        modEventBus.addListener(MISNetwork::commonSetup);
        NeoForge.EVENT_BUS.register(this);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            NeoForge.EVENT_BUS.register(ClientAutoLoginHandler.class);
        }
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        MISNetwork.register(event);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        MISCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        serverInstance = event.getServer();
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

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (sessionPurger != null) {
            sessionPurger.shutdownNow();
            sessionPurger = null;
        }
        MCCManager.getInstance().stop();
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (MISSessionManager.getInstance().isInMISMode(player)) {
                LOGGER.info("Player {} reconnected with a valid MIS auth cookie.", player.getGameProfile().getName());
            }
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            MCCManager.getInstance().removeDisconnectedPlayer(player.getUUID());
        }
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

    /**
     * 在服务端主线程上执行任务。网络包必须从主线程发送，否则会与主线程并发
     * 写入 Connection 的共享发送缓冲，导致异常并使后台进程流程中断。
     */
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
        executeOnServerThread(() -> PacketDistributor.sendToPlayer(player, payload));
    }

    public static void sendToAllPlayers(CustomPacketPayload payload) {
        executeOnServerThread(() -> PacketDistributor.sendToAllPlayers(payload));
    }

    private static void runSafely(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            LOGGER.warn("Error executing server-thread network task", e);
        }
    }
}