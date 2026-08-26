package com.xiaoze_qwq_.mis.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.xiaoze_qwq_.mis.*;
import com.xiaoze_qwq_.mis.config.MISConfig;
import com.xiaoze_qwq_.mis.network.CookieGrantPacket;
import com.xiaoze_qwq_.mis.network.OpenStatusScreenPacket;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class MISCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var base = Commands.literal("mis")
                .requires(src -> src.hasPermission(0) && !isCommandBlock(src));

        base.then(Commands.literal("verify")
                .then(Commands.argument("password", StringArgumentType.word())
                        .executes(MISCommand::executeVerify)));

        base.then(Commands.literal("reload").executes(MISCommand::executeReload));

        base.then(Commands.literal("set")
                .then(Commands.literal("password")
                        .then(Commands.argument("password", StringArgumentType.greedyString())
                                .executes(MISCommand::executeSetPassword))
                        .then(Commands.literal("confirm")
                                .then(Commands.argument("password", StringArgumentType.greedyString())
                                        .executes(MISCommand::executeSetPasswordConfirm)))));

        base.then(Commands.literal("unset")
                .then(Commands.literal("password")
                        .executes(MISCommand::executeUnsetPassword)));

        base.then(Commands.literal("status").executes(MISCommand::executeStatusOverview));

        var authNode = Commands.literal("auth")
                .then(Commands.literal("list").executes(MISCommand::executeAuthList))
                .then(Commands.literal("grant")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .then(Commands.argument("duration", StringArgumentType.word())
                                        .executes(MISCommand::executeAuthGrant))))
                .then(Commands.literal("grantuuid")
                        .then(Commands.argument("uuid", StringArgumentType.word())
                                .then(Commands.argument("duration", StringArgumentType.word())
                                        .executes(MISCommand::executeAuthGrantUuid))))
                .then(Commands.literal("revoke")
                        .then(Commands.argument("target", StringArgumentType.word())
                                .executes(MISCommand::executeAuthRevoke)));
        base.then(authNode);

        var instanceNode = Commands.argument("instance", StringArgumentType.word())
                .suggests((ctx, builder) -> {
                    for (MCCInstance inst : MCCManager.getInstance().getAllInstances()) {
                        builder.suggest(inst.getId());
                        builder.suggest(inst.getConfig().name);
                    }
                    return builder.buildFuture();
                })
                .executes(ctx -> executeInstanceHelp(ctx, getInstanceId(ctx)))
                .then(Commands.literal("input")
                        .then(Commands.argument("command", StringArgumentType.greedyString())
                                .executes(ctx -> executeInput(ctx, getInstanceId(ctx)))))
                .then(Commands.literal("start").executes(ctx -> executeStart(ctx, getInstanceId(ctx))))
                .then(Commands.literal("stop").executes(ctx -> executeStop(ctx, getInstanceId(ctx))))
                .then(Commands.literal("restart").executes(ctx -> executeRestart(ctx, getInstanceId(ctx))))
                .then(Commands.literal("forcestop").executes(ctx -> executeForceStop(ctx, getInstanceId(ctx))))
                .then(Commands.literal("status").executes(ctx -> executeStatus(ctx, getInstanceId(ctx))))
                .then(Commands.literal("immersive")
                        .then(Commands.literal("on").executes(ctx -> executeImmersiveOn(ctx, getInstanceId(ctx))))
                        .then(Commands.literal("off").executes(ctx -> executeImmersiveOff(ctx, getInstanceId(ctx)))))
                .then(Commands.literal("set")
                        .then(Commands.literal("history")
                                .then(Commands.argument("lines", IntegerArgumentType.integer(1, 10000))
                                        .executes(ctx -> {
                                            int lines = IntegerArgumentType.getInteger(ctx, "lines");
                                            String id = getInstanceId(ctx);
                                            MISConfig.setInstanceField(id, "historyLimit", lines);
                                            MCCInstance inst = MCCManager.getInstance().getInstance(id);
                                            if (inst != null) {
                                                inst.getConfig().historyLimit = lines;
                                                inst.resizeBuffer(lines);
                                            }
                                            ctx.getSource().sendSuccess(() -> Component.literal("History limit set to " + lines), false);
                                            return 1;
                                        })))
                        .then(Commands.literal("exitcode")
                                .then(Commands.argument("code", IntegerArgumentType.integer(0, 255))
                                        .executes(ctx -> {
                                            int code = IntegerArgumentType.getInteger(ctx, "code");
                                            String id = getInstanceId(ctx);
                                            MISConfig.setInstanceField(id, "exitCode", code);
                                            ctx.getSource().sendSuccess(() -> Component.literal("Exit code set to " + code + " (requires restart)"), false);
                                            return 1;
                                        })))
                        .then(Commands.literal("autorestart")
                                .then(Commands.literal("enable").executes(ctx -> {
                                    String id = getInstanceId(ctx);
                                    MISConfig.setInstanceField(id, "autoRestart", true);
                                    ctx.getSource().sendSuccess(() -> Component.literal("Auto-restart enabled"), false);
                                    return 1;
                                }))
                                .then(Commands.literal("disable").executes(ctx -> {
                                    String id = getInstanceId(ctx);
                                    MISConfig.setInstanceField(id, "autoRestart", false);
                                    ctx.getSource().sendSuccess(() -> Component.literal("Auto-restart disabled"), false);
                                    return 1;
                                })))
                        .then(Commands.literal("help").executes(ctx -> {
                            ctx.getSource().sendSuccess(() -> Component.translatable("mis.set.help.title"), false);
                            return 1;
                        })));

        base.then(instanceNode);
        base.executes(MISCommand::executeHelp);
        dispatcher.register(base);
    }

    private static boolean isCommandBlock(CommandSourceStack src) {
        return src.source instanceof net.minecraft.world.level.BaseCommandBlock;
    }

    private static String getInstanceId(CommandContext<CommandSourceStack> ctx) {
        String input = StringArgumentType.getString(ctx, "instance");
        // 尝试解析为数字，如果不是则按名称查找
        try {
            Integer.parseInt(input);
            return input;
        } catch (NumberFormatException e) {
            MCCInstance inst = MCCManager.getInstance().getInstanceByName(input);
            if (inst != null) {
                return inst.getId();
            }
            return input; // 返回原始输入，后续找不到实例时会报错
        }
    }

    private static boolean isConsole(CommandSourceStack src) { return src.getEntity() == null; }

    private static boolean checkPermission(CommandSourceStack src, String perm) throws CommandSyntaxException {
        if (isConsole(src)) return true;
        ServerPlayer player = src.getPlayer();
        if (player == null) return false;
        return MISSessionManager.getInstance().isInMISMode(player);
    }

    private static int executeVerify(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var src = ctx.getSource();
        if (isConsole(src)) {
            src.sendSuccess(() -> Component.translatable("mis.verify.console_info"), false);
            return 1;
        }
        ServerPlayer player = src.getPlayer();
        if (player == null) return 0;
        UUID uuid = player.getUUID();
        if (!MISConfig.hasPassword()) {
            src.sendFailure(Component.translatable("mis.error.no_password"));
            return 0;
        }
        long remaining = MISConfig.getVerifyLockRemaining(uuid);
        if (remaining > 0) {
            src.sendFailure(Component.translatable("mis.error.verify_locked", remaining / 1000));
            return 0;
        }
        String pwd = StringArgumentType.getString(ctx, "password");
        MISConfig.verifyPasswordAsync(pwd).thenAccept(ok -> {
            MCCInServer.executeOnServerThread(() -> {
                if (ok) {
                    MISConfig.clearVerifyFailures(uuid);
                    MISSessionManager mgr = MISSessionManager.getInstance();
                    mgr.grant(player, MISSessionManager.defaultDurationMs());
                    // 撤销旧令牌（5 秒内仍可用旧令牌换新），并签发新令牌下发给客户端
                    mgr.revokeCookiesFor(uuid);
                    MISSessionManager.GrantedCookie granted = mgr.grantCookie(
                            uuid,
                            player.getGameProfile().getName(),
                            MISSessionManager.serverAddressOf(player.getServer()),
                            "",
                            MISSessionManager.defaultDurationMs());
                    MCCInServer.sendToPlayer(player, new CookieGrantPacket(granted.token(), granted.expiresAt()));
                    src.sendSuccess(() -> Component.translatable("mis.verify.success"), false);
                } else {
                    MISConfig.recordVerifyFailure(uuid);
                    long lockRemaining = MISConfig.getVerifyLockRemaining(uuid);
                    if (lockRemaining > 0) {
                        src.sendFailure(Component.translatable("mis.error.verify_locked", lockRemaining / 1000));
                    } else {
                        src.sendFailure(Component.translatable("mis.error.wrong_password"));
                    }
                }
            });
        });
        return 1;
    }

    private static int executeSetPassword(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        if (!isConsole(src)) {
            src.sendFailure(Component.translatable("mis.set.password.console_only"));
            return 0;
        }
        String pwd = StringArgumentType.getString(ctx, "password");
        if (pwd.isEmpty()) {
            src.sendFailure(Component.translatable("mis.set.password.empty"));
            return 0;
        }
        if (MCCInServer.isLogCommandsEnabled()) {
            src.sendFailure(Component.translatable("mis.set.password.log_commands_warning"));
            return 0;
        }
        MISConfig.setPassword(pwd);
        src.sendSuccess(() -> Component.translatable("mis.set.password.success"), false);
        return 1;
    }

    private static int executeSetPasswordConfirm(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        if (!isConsole(src)) {
            src.sendFailure(Component.translatable("mis.set.password.console_only"));
            return 0;
        }
        String pwd = StringArgumentType.getString(ctx, "password");
        if (pwd.isEmpty()) {
            src.sendFailure(Component.translatable("mis.set.password.empty"));
            return 0;
        }
        if (MCCInServer.isLogCommandsEnabled()) {
            MCCInServer.LOGGER.warn("Password was set via /mis set password confirm while log-commands=true. "
                    + "The password will be written to the server log. Consider setting log-commands=false.");
        }
        MISConfig.setPassword(pwd);
        src.sendSuccess(() -> Component.translatable("mis.set.password.success"), false);
        return 1;
    }

    private static int executeUnsetPassword(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        if (!isConsole(src)) {
            src.sendFailure(Component.translatable("mis.set.password.console_only"));
            return 0;
        }
        MISConfig.clearPassword();
        src.sendSuccess(() -> Component.translatable("mis.unset.password.success"), false);
        return 1;
    }

    private static int executeStatusOverview(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        if (isConsole(src)) {
            StringBuilder sb = new StringBuilder("=== MIS Status ===\n");
            sb.append("Password: ").append(MISConfig.hasPassword() ? "SET" : "NOT SET").append("\n");
            sb.append("Verify: maxAttempts=").append(MISConfig.getVerifyMaxAttempts())
                    .append(" lockout=").append(MISConfig.getVerifyLockoutSeconds()).append("s\n");
            sb.append("Instances:\n");
            for (MCCInstance inst : MCCManager.getInstance().getAllInstances()) {
                sb.append("  [").append(inst.getId()).append("] ").append(inst.getConfig().name)
                        .append(" running=").append(inst.isRunning())
                        .append(" pid=").append(inst.getPid())
                        .append(" status=").append(inst.getStatusMonitor() != null ? inst.getStatusMonitor().getFormattedStatus() : "n/a")
                        .append("\n");
            }
            sb.append("Authorized players:\n");
            for (var cookie : MISSessionManager.getInstance().listCookies()) {
                sb.append("  ").append(cookie.username()).append(" (").append(cookie.uuid())
                        .append(") expires=").append(cookie.expiresAt())
                        .append(" remaining=").append(Math.max(0, cookie.remainingMs()) / 1000).append("s")
                        .append(" ip=").append(cookie.ip())
                        .append("\n");
            }
            String text = sb.toString();
            for (String line : text.split("\n")) {
                String l = line;
                src.sendSuccess(() -> Component.literal(l), false);
            }
        } else {
            ServerPlayer player = src.getPlayer();
            if (player == null) return 0;
            boolean authorized = MISSessionManager.getInstance().isInMISMode(player);
            if (authorized) {
                var cookie = MISSessionManager.getInstance().getCookie(player.getUUID());
                long remaining = cookie != null ? Math.max(0, cookie.remainingMs()) / 1000 : 0;
                src.sendSuccess(() -> Component.translatable("mis.status.player_authorized", remaining), false);
            } else {
                src.sendFailure(Component.translatable("mis.status.player_unauthorized"));
            }
        }
        return 1;
    }

    private static int executeAuthList(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        if (!isConsole(src)) {
            src.sendFailure(Component.translatable("mis.auth.console_only"));
            return 0;
        }
        var cookies = MISSessionManager.getInstance().listCookies();
        if (cookies.isEmpty()) {
            src.sendSuccess(() -> Component.translatable("mis.auth.list.none"), false);
            return 1;
        }
        for (var cookie : cookies) {
            String line = "  " + cookie.username() + " (" + cookie.uuid() + ") expires="
                    + cookie.expiresAt() + " remaining=" + Math.max(0, cookie.remainingMs()) / 1000
                    + "s ip=" + cookie.ip();
            src.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    private static int executeAuthGrant(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        if (!isConsole(src)) {
            src.sendFailure(Component.translatable("mis.auth.console_only"));
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "player");
        long durationMs = parseDuration(StringArgumentType.getString(ctx, "duration"));
        ServerPlayer player = src.getServer().getPlayerList().getPlayerByName(name);
        if (player == null) {
            src.sendFailure(Component.translatable("mis.auth.player_not_found", name));
            return 0;
        }
        MISSessionManager.getInstance().grant(player, durationMs);
        src.sendSuccess(() -> Component.translatable("mis.auth.grant.success", name, durationMs / 1000), false);
        return 1;
    }

    private static int executeAuthGrantUuid(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        if (!isConsole(src)) {
            src.sendFailure(Component.translatable("mis.auth.console_only"));
            return 0;
        }
        String uuidStr = StringArgumentType.getString(ctx, "uuid");
        long durationMs = parseDuration(StringArgumentType.getString(ctx, "duration"));
        UUID uuid;
        try {
            uuid = UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
            src.sendFailure(Component.translatable("mis.auth.invalid_uuid", uuidStr));
            return 0;
        }
        MISSessionManager.getInstance().grant(uuid, "", "", durationMs, "");
        src.sendSuccess(() -> Component.translatable("mis.auth.grantuuid.success", uuidStr, durationMs / 1000), false);
        return 1;
    }

    private static int executeAuthRevoke(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        if (!isConsole(src)) {
            src.sendFailure(Component.translatable("mis.auth.console_only"));
            return 0;
        }
        String target = StringArgumentType.getString(ctx, "target");
        UUID uuid;
        try {
            uuid = UUID.fromString(target);
        } catch (IllegalArgumentException e) {
            ServerPlayer player = src.getServer().getPlayerList().getPlayerByName(target);
            if (player == null) {
                src.sendFailure(Component.translatable("mis.auth.player_not_found", target));
                return 0;
            }
            uuid = player.getUUID();
        }
        final UUID finalUuid = uuid;
        if (!MISSessionManager.getInstance().hasCookie(finalUuid)) {
            src.sendSuccess(() -> Component.translatable("mis.auth.revoke.not_found"), false);
            return 1;
        }
        MISSessionManager.getInstance().revoke(finalUuid);
        src.sendSuccess(() -> Component.translatable("mis.auth.revoke.success", finalUuid), false);
        return 1;
    }

    private static long parseDuration(String input) {
        if (input == null || input.isEmpty()) return MISSessionManager.defaultDurationMs();
        String s = input.trim().toLowerCase(java.util.Locale.ROOT);
        try {
            if (s.endsWith("s")) return Long.parseLong(s.substring(0, s.length() - 1)) * 1000L;
            if (s.endsWith("m")) return Long.parseLong(s.substring(0, s.length() - 1)) * 60_000L;
            if (s.endsWith("h")) return Long.parseLong(s.substring(0, s.length() - 1)) * 3_600_000L;
            if (s.endsWith("d")) return Long.parseLong(s.substring(0, s.length() - 1)) * 86_400_000L;
            return Long.parseLong(s) * 1000L;
        } catch (NumberFormatException e) {
            return MISSessionManager.defaultDurationMs();
        }
    }

    private static int executeReload(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        if (!checkPermission(ctx.getSource(), "mis.reload")) return errorNoPermission(ctx);
        MCCManager.getInstance().reloadInstances();
        ctx.getSource().sendSuccess(() -> Component.translatable("mis.reload.success"), false);
        return 1;
    }

    private static int executeInput(CommandContext<CommandSourceStack> ctx, String id) throws CommandSyntaxException {
        if (!checkPermission(ctx.getSource(), "mis.mismode")) return errorNoPermission(ctx);
        MCCInstance inst = resolveInstance(ctx, id);
        if (inst == null) return 0;
        if (!inst.isRunning()) {
            ctx.getSource().sendFailure(Component.translatable("mis.error.mcc_not_running"));
            return 0;
        }
        String cmd = StringArgumentType.getString(ctx, "command");
        inst.sendCommand(cmd);
        return 1;
    }

    private static int executeStart(CommandContext<CommandSourceStack> ctx, String id) throws CommandSyntaxException {
        if (!checkPermission(ctx.getSource(), "mis.start")) return errorNoPermission(ctx);
        MCCInstance inst = resolveInstance(ctx, id);
        if (inst == null) return 0;
        if (inst.isRunning()) {
            ctx.getSource().sendFailure(Component.translatable("mis.error.already_running"));
            return 0;
        }
        if (!inst.canStartNewOperation()) {
            warnOtherOperation(ctx, inst);
            return 0;
        }
        CompletableFuture.runAsync(inst::start);
        ctx.getSource().sendSuccess(() -> Component.translatable("mis.start.success"), false);
        return 1;
    }

    private static int executeStop(CommandContext<CommandSourceStack> ctx, String id) throws CommandSyntaxException {
        if (!checkPermission(ctx.getSource(), "mis.stop")) return errorNoPermission(ctx);
        MCCInstance inst = resolveInstance(ctx, id);
        if (inst == null) return 0;
        if (!inst.isRunning()) {
            ctx.getSource().sendFailure(Component.translatable("mis.error.not_running"));
            return 0;
        }
        if (!inst.canStartNewOperation()) {
            warnOtherOperation(ctx, inst);
            return 0;
        }
        CompletableFuture.runAsync(() -> inst.stop(true));
        ctx.getSource().sendSuccess(() -> Component.translatable("mis.stop.success"), false);
        return 1;
    }

    private static int executeRestart(CommandContext<CommandSourceStack> ctx, String id) throws CommandSyntaxException {
        if (!checkPermission(ctx.getSource(), "mis.restart")) return errorNoPermission(ctx);
        MCCInstance inst = resolveInstance(ctx, id);
        if (inst == null) return 0;
        if (!inst.canStartNewOperation()) {
            warnOtherOperation(ctx, inst);
            return 0;
        }
        inst.restart();
        ctx.getSource().sendSuccess(() -> Component.translatable("mis.restart.success"), false);
        return 1;
    }

    private static int executeForceStop(CommandContext<CommandSourceStack> ctx, String id) throws CommandSyntaxException {
        if (!checkPermission(ctx.getSource(), "mis.forcestop")) return errorNoPermission(ctx);
        MCCInstance inst = resolveInstance(ctx, id);
        if (inst == null) return 0;
        if (!inst.canStartNewOperation()) {
            warnOtherOperation(ctx, inst);
            return 0;
        }
        CompletableFuture.runAsync(() -> inst.forceStop());
        ctx.getSource().sendSuccess(() -> Component.translatable("mis.forcestop.success"), false);
        return 1;
    }

    private static int executeStatus(CommandContext<CommandSourceStack> ctx, String id) throws CommandSyntaxException {
        var src = ctx.getSource();
        var player = src.getPlayer();
        MCCInstance inst = resolveInstance(ctx, id);
        if (inst == null) return 0;
        if (player == null) {
            String status = inst.getStatusMonitor() != null ? inst.getStatusMonitor().getFormattedStatus() : "Monitor not available.";
            src.sendSuccess(() -> Component.literal(status), false);
        } else {
            if (!checkPermission(src, "mis.status")) return errorNoPermission(ctx);
            PacketDistributor.sendToPlayer(player, new OpenStatusScreenPacket(id));
        }
        return 1;
    }

    private static int executeImmersiveOn(CommandContext<CommandSourceStack> ctx, String id) throws CommandSyntaxException {
        var src = ctx.getSource();
        var player = src.getPlayer();
        MCCInstance inst = resolveInstance(ctx, id);
        if (inst == null) return 0;
        if (player == null) {
            if (inst.isImmersiveConsole()) {
                src.sendFailure(Component.translatable("mis.immersive.already_on"));
                return 0;
            }
            inst.setImmersiveConsole(true);
            src.sendSuccess(() -> Component.translatable("mis.immersive.on"), false);
        } else {
            if (!checkPermission(src, "mis.immersive.read")) return errorNoPermission(ctx);
            boolean canInput = checkPermission(src, "mis.immersive.input");
            inst.addImmersiveSubscriber(player, canInput);
            src.sendSuccess(() -> Component.translatable("mis.immersive.on_player"), false);
        }
        return 1;
    }

    private static int executeImmersiveOff(CommandContext<CommandSourceStack> ctx, String id) throws CommandSyntaxException {
        var src = ctx.getSource();
        var player = src.getPlayer();
        MCCInstance inst = resolveInstance(ctx, id);
        if (inst == null) return 0;
        if (player == null) {
            if (!inst.isImmersiveConsole()) {
                src.sendFailure(Component.translatable("mis.immersive.already_off"));
                return 0;
            }
            inst.setImmersiveConsole(false);
            src.sendSuccess(() -> Component.translatable("mis.immersive.off"), false);
        } else {
            inst.removeImmersiveSubscriber(player);
        }
        return 1;
    }

    private static int executeInstanceHelp(CommandContext<CommandSourceStack> ctx, String id) {
        ctx.getSource().sendSuccess(() -> Component.literal("Commands for instance " + id + ": input/start/stop/restart/forcestop/status/immersive/set"), false);
        return 1;
    }

    private static int executeHelp(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.translatable("mis.help.title"), false);
        return 1;
    }

    // 辅助方法：解析实例ID，如果不是数字则按名称查找，找不到发送错误
    private static MCCInstance resolveInstance(CommandContext<CommandSourceStack> ctx, String id) {
        MCCInstance inst;
        try {
            Integer.parseInt(id);
            inst = MCCManager.getInstance().getInstance(id);
        } catch (NumberFormatException e) {
            inst = MCCManager.getInstance().getInstanceByName(id);
        }
        if (inst == null) {
            ctx.getSource().sendFailure(Component.translatable("mis.error.instance_not_found", id));
        }
        return inst;
    }

    // 当有其他操作进行时，发送警告
    private static void warnOtherOperation(CommandContext<CommandSourceStack> ctx, MCCInstance inst) {
        ctx.getSource().sendFailure(Component.translatable("mis.error.other_operation", inst.getId()));
    }

    private static int errorNoPermission(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendFailure(Component.translatable("mis.error.not_in_mis_mode"));
        return 0;
    }
}