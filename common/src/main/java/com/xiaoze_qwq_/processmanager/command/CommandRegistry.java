package com.xiaoze_qwq_.processmanager.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.xiaoze_qwq_.processmanager.*;
import com.xiaoze_qwq_.processmanager.config.Config;
import com.xiaoze_qwq_.processmanager.network.CookieGrantPacket;
import com.xiaoze_qwq_.processmanager.network.OpenStatusScreenPacket;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class CommandRegistry {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var base = Commands.literal("pm")
                .requires(src -> src.hasPermission(0) && !isCommandBlock(src));

        base.then(Commands.literal("verify")
                .then(Commands.argument("password", StringArgumentType.word())
                        .executes(CommandRegistry::executeVerify)));

        base.then(Commands.literal("reload").executes(CommandRegistry::executeReload));

        base.then(Commands.literal("set")
                .then(Commands.literal("password")
                        .then(Commands.argument("password", StringArgumentType.greedyString())
                                .executes(CommandRegistry::executeSetPassword))
                        .then(Commands.literal("confirm")
                                .then(Commands.argument("password", StringArgumentType.greedyString())
                                        .executes(CommandRegistry::executeSetPasswordConfirm)))));

        base.then(Commands.literal("unset")
                .then(Commands.literal("password")
                        .executes(CommandRegistry::executeUnsetPassword)));

        base.then(Commands.literal("status").executes(CommandRegistry::executeStatusOverview));

        var authNode = Commands.literal("auth")
                .then(Commands.literal("list").executes(CommandRegistry::executeAuthList))
                .then(Commands.literal("grant")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .then(Commands.argument("duration", StringArgumentType.word())
                                        .executes(CommandRegistry::executeAuthGrant))))
                .then(Commands.literal("grantuuid")
                        .then(Commands.argument("uuid", StringArgumentType.word())
                                .then(Commands.argument("duration", StringArgumentType.word())
                                        .executes(CommandRegistry::executeAuthGrantUuid))))
                .then(Commands.literal("revoke")
                        .then(Commands.argument("target", StringArgumentType.word())
                                .executes(CommandRegistry::executeAuthRevoke)));
        base.then(authNode);

        var instanceNode = Commands.argument("instance", StringArgumentType.word())
                .suggests((ctx, builder) -> {
                    for (ProcessInstance inst : InstanceManager.getInstance().getAllInstances()) {
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
                                            Config.setInstanceField(id, "historyLimit", lines);
                                            ProcessInstance inst = InstanceManager.getInstance().getInstance(id);
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
                                            Config.setInstanceField(id, "exitCode", code);
                                            ctx.getSource().sendSuccess(() -> Component.literal("Exit code set to " + code + " (requires restart)"), false);
                                            return 1;
                                        })))
                        .then(Commands.literal("autorestart")
                                .then(Commands.literal("enable").executes(ctx -> {
                                    String id = getInstanceId(ctx);
                                    Config.setInstanceField(id, "autoRestart", true);
                                    ctx.getSource().sendSuccess(() -> Component.literal("Auto-restart enabled"), false);
                                    return 1;
                                }))
                                .then(Commands.literal("disable").executes(ctx -> {
                                    String id = getInstanceId(ctx);
                                    Config.setInstanceField(id, "autoRestart", false);
                                    ctx.getSource().sendSuccess(() -> Component.literal("Auto-restart disabled"), false);
                                    return 1;
                                })))
                        .then(Commands.literal("help").executes(ctx -> {
                            ctx.getSource().sendSuccess(() -> Component.translatable("pm.set.help.title"), false);
                            return 1;
                        })))
                .then(Commands.literal("ssh")
                        .executes(ctx -> executeSshStatus(ctx, getInstanceId(ctx)))
                        .then(Commands.literal("enable").executes(ctx -> executeSshEnable(ctx, getInstanceId(ctx))))
                        .then(Commands.literal("disable").executes(ctx -> executeSshDisable(ctx, getInstanceId(ctx))))
                        .then(Commands.literal("setuser")
                                .then(Commands.argument("user", StringArgumentType.word())
                                        .executes(ctx -> executeSshSetUser(ctx, getInstanceId(ctx)))))
                        .then(Commands.literal("setpassword")
                                .then(Commands.argument("password", StringArgumentType.greedyString())
                                        .executes(ctx -> executeSshSetPassword(ctx, getInstanceId(ctx))))));

        base.then(instanceNode);
        base.executes(CommandRegistry::executeHelp);
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
            ProcessInstance inst = InstanceManager.getInstance().getInstanceByName(input);
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
        return SessionManager.getInstance().isAuthorized(player);
    }

    private static int executeVerify(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var src = ctx.getSource();
        if (isConsole(src)) {
            src.sendSuccess(() -> Component.translatable("pm.verify.console_info"), false);
            return 1;
        }
        ServerPlayer player = src.getPlayer();
        if (player == null) return 0;
        UUID uuid = player.getUUID();
        if (!Config.hasPassword()) {
            src.sendFailure(Component.translatable("pm.error.no_password"));
            return 0;
        }
        long remaining = Config.getVerifyLockRemaining(uuid);
        if (remaining > 0) {
            src.sendFailure(Component.translatable("pm.error.verify_locked", remaining / 1000));
            return 0;
        }
        String pwd = StringArgumentType.getString(ctx, "password");
        Config.verifyPasswordAsync(pwd).thenAccept(ok -> {
            ProcessManager.executeOnServerThread(() -> {
                if (ok) {
                    Config.clearVerifyFailures(uuid);
                    SessionManager mgr = SessionManager.getInstance();
                    mgr.grant(player, SessionManager.defaultDurationMs());
                    // 撤销旧令牌（5 秒内仍可用旧令牌换新），并签发新令牌下发给客户端
                    mgr.revokeCookiesFor(uuid);
                    SessionManager.GrantedCookie granted = mgr.grantCookie(
                            uuid,
                            player.getGameProfile().getName(),
                            SessionManager.serverAddressOf(player.getServer()),
                            "",
                            SessionManager.defaultDurationMs());
                    ProcessManager.sendToPlayer(player, new CookieGrantPacket(granted.token(), granted.expiresAt()));
                    src.sendSuccess(() -> Component.translatable("pm.verify.success"), false);
                } else {
                    Config.recordVerifyFailure(uuid);
                    long lockRemaining = Config.getVerifyLockRemaining(uuid);
                    if (lockRemaining > 0) {
                        src.sendFailure(Component.translatable("pm.error.verify_locked", lockRemaining / 1000));
                    } else {
                        src.sendFailure(Component.translatable("pm.error.wrong_password"));
                    }
                }
            });
        });
        return 1;
    }

    private static int executeSetPassword(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        if (!isConsole(src)) {
            src.sendFailure(Component.translatable("pm.set.password.console_only"));
            return 0;
        }
        String pwd = StringArgumentType.getString(ctx, "password");
        if (pwd.isEmpty()) {
            src.sendFailure(Component.translatable("pm.set.password.empty"));
            return 0;
        }
        if (ProcessManager.isLogCommandsEnabled()) {
            src.sendFailure(Component.translatable("pm.set.password.log_commands_warning"));
            return 0;
        }
        Config.setPassword(pwd);
        src.sendSuccess(() -> Component.translatable("pm.set.password.success"), false);
        return 1;
    }

    private static int executeSetPasswordConfirm(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        if (!isConsole(src)) {
            src.sendFailure(Component.translatable("pm.set.password.console_only"));
            return 0;
        }
        String pwd = StringArgumentType.getString(ctx, "password");
        if (pwd.isEmpty()) {
            src.sendFailure(Component.translatable("pm.set.password.empty"));
            return 0;
        }
        if (ProcessManager.isLogCommandsEnabled()) {
            ProcessManager.LOGGER.warn("Password was set via /pm set password confirm while log-commands=true. "
                    + "The password will be written to the server log. Consider setting log-commands=false.");
        }
        Config.setPassword(pwd);
        src.sendSuccess(() -> Component.translatable("pm.set.password.success"), false);
        return 1;
    }

    private static int executeUnsetPassword(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        if (!isConsole(src)) {
            src.sendFailure(Component.translatable("pm.set.password.console_only"));
            return 0;
        }
        Config.clearPassword();
        src.sendSuccess(() -> Component.translatable("pm.unset.password.success"), false);
        return 1;
    }

    private static int executeStatusOverview(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        if (isConsole(src)) {
            StringBuilder sb = new StringBuilder("=== PM Status ===\n");
            sb.append("Password: ").append(Config.hasPassword() ? "SET" : "NOT SET").append("\n");
            sb.append("Verify: maxAttempts=").append(Config.getVerifyMaxAttempts())
                    .append(" lockout=").append(Config.getVerifyLockoutSeconds()).append("s\n");
            sb.append("Instances:\n");
            for (ProcessInstance inst : InstanceManager.getInstance().getAllInstances()) {
                sb.append("  [").append(inst.getId()).append("] ").append(inst.getConfig().name)
                        .append(" running=").append(inst.isRunning())
                        .append(" pid=").append(inst.getPid())
                        .append(" status=").append(inst.getStatusMonitor() != null ? inst.getStatusMonitor().getFormattedStatus() : "n/a")
                        .append("\n");
            }
            sb.append("Authorized players:\n");
            for (var cookie : SessionManager.getInstance().listCookies()) {
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
            boolean authorized = SessionManager.getInstance().isAuthorized(player);
            if (authorized) {
                var cookie = SessionManager.getInstance().getCookie(player.getUUID());
                long remaining = cookie != null ? Math.max(0, cookie.remainingMs()) / 1000 : 0;
                src.sendSuccess(() -> Component.translatable("pm.status.player_authorized", remaining), false);
            } else {
                src.sendFailure(Component.translatable("pm.status.player_unauthorized"));
            }
        }
        return 1;
    }

    private static int executeAuthList(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        if (!isConsole(src)) {
            src.sendFailure(Component.translatable("pm.auth.console_only"));
            return 0;
        }
        var cookies = SessionManager.getInstance().listCookies();
        if (cookies.isEmpty()) {
            src.sendSuccess(() -> Component.translatable("pm.auth.list.none"), false);
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
            src.sendFailure(Component.translatable("pm.auth.console_only"));
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "player");
        long durationMs = parseDuration(StringArgumentType.getString(ctx, "duration"));
        ServerPlayer player = src.getServer().getPlayerList().getPlayerByName(name);
        if (player == null) {
            src.sendFailure(Component.translatable("pm.auth.player_not_found", name));
            return 0;
        }
        SessionManager.getInstance().grant(player, durationMs);
        src.sendSuccess(() -> Component.translatable("pm.auth.grant.success", name, durationMs / 1000), false);
        return 1;
    }

    private static int executeAuthGrantUuid(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        if (!isConsole(src)) {
            src.sendFailure(Component.translatable("pm.auth.console_only"));
            return 0;
        }
        String uuidStr = StringArgumentType.getString(ctx, "uuid");
        long durationMs = parseDuration(StringArgumentType.getString(ctx, "duration"));
        UUID uuid;
        try {
            uuid = UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
            src.sendFailure(Component.translatable("pm.auth.invalid_uuid", uuidStr));
            return 0;
        }
        SessionManager.getInstance().grant(uuid, "", "", durationMs, "");
        src.sendSuccess(() -> Component.translatable("pm.auth.grantuuid.success", uuidStr, durationMs / 1000), false);
        return 1;
    }

    private static int executeAuthRevoke(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        if (!isConsole(src)) {
            src.sendFailure(Component.translatable("pm.auth.console_only"));
            return 0;
        }
        String target = StringArgumentType.getString(ctx, "target");
        UUID uuid;
        try {
            uuid = UUID.fromString(target);
        } catch (IllegalArgumentException e) {
            ServerPlayer player = src.getServer().getPlayerList().getPlayerByName(target);
            if (player == null) {
                src.sendFailure(Component.translatable("pm.auth.player_not_found", target));
                return 0;
            }
            uuid = player.getUUID();
        }
        final UUID finalUuid = uuid;
        if (!SessionManager.getInstance().hasCookie(finalUuid)) {
            src.sendSuccess(() -> Component.translatable("pm.auth.revoke.not_found"), false);
            return 1;
        }
        SessionManager.getInstance().revoke(finalUuid);
        src.sendSuccess(() -> Component.translatable("pm.auth.revoke.success", finalUuid), false);
        return 1;
    }

    private static long parseDuration(String input) {
        if (input == null || input.isEmpty()) return SessionManager.defaultDurationMs();
        String s = input.trim().toLowerCase(java.util.Locale.ROOT);
        try {
            if (s.endsWith("s")) return Long.parseLong(s.substring(0, s.length() - 1)) * 1000L;
            if (s.endsWith("m")) return Long.parseLong(s.substring(0, s.length() - 1)) * 60_000L;
            if (s.endsWith("h")) return Long.parseLong(s.substring(0, s.length() - 1)) * 3_600_000L;
            if (s.endsWith("d")) return Long.parseLong(s.substring(0, s.length() - 1)) * 86_400_000L;
            return Long.parseLong(s) * 1000L;
        } catch (NumberFormatException e) {
            return SessionManager.defaultDurationMs();
        }
    }

    private static int executeReload(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        if (!checkPermission(ctx.getSource(), "pm.reload")) return errorNoPermission(ctx);
        InstanceManager.getInstance().reloadInstances();
        ctx.getSource().sendSuccess(() -> Component.translatable("pm.reload.success"), false);
        return 1;
    }

    private static int executeInput(CommandContext<CommandSourceStack> ctx, String id) throws CommandSyntaxException {
        if (!checkPermission(ctx.getSource(), "pm.mode")) return errorNoPermission(ctx);
        ProcessInstance inst = resolveInstance(ctx, id);
        if (inst == null) return 0;
        if (!inst.isRunning()) {
            ctx.getSource().sendFailure(Component.translatable("pm.error.not_running"));
            return 0;
        }
        String cmd = StringArgumentType.getString(ctx, "command");
        inst.sendCommand(cmd);
        return 1;
    }

    private static int executeStart(CommandContext<CommandSourceStack> ctx, String id) throws CommandSyntaxException {
        if (!checkPermission(ctx.getSource(), "pm.start")) return errorNoPermission(ctx);
        ProcessInstance inst = resolveInstance(ctx, id);
        if (inst == null) return 0;
        if (inst.isRunning()) {
            ctx.getSource().sendFailure(Component.translatable("pm.error.already_running"));
            return 0;
        }
        if (!inst.canStartNewOperation()) {
            warnOtherOperation(ctx, inst);
            return 0;
        }
        CompletableFuture.runAsync(inst::start);
        ctx.getSource().sendSuccess(() -> Component.translatable("pm.start.success"), false);
        return 1;
    }

    private static int executeStop(CommandContext<CommandSourceStack> ctx, String id) throws CommandSyntaxException {
        if (!checkPermission(ctx.getSource(), "pm.stop")) return errorNoPermission(ctx);
        ProcessInstance inst = resolveInstance(ctx, id);
        if (inst == null) return 0;
        if (!inst.isRunning()) {
            ctx.getSource().sendFailure(Component.translatable("pm.error.not_running"));
            return 0;
        }
        if (!inst.canStartNewOperation()) {
            warnOtherOperation(ctx, inst);
            return 0;
        }
        CompletableFuture.runAsync(() -> inst.stop(true));
        ctx.getSource().sendSuccess(() -> Component.translatable("pm.stop.success"), false);
        return 1;
    }

    private static int executeRestart(CommandContext<CommandSourceStack> ctx, String id) throws CommandSyntaxException {
        if (!checkPermission(ctx.getSource(), "pm.restart")) return errorNoPermission(ctx);
        ProcessInstance inst = resolveInstance(ctx, id);
        if (inst == null) return 0;
        if (!inst.canStartNewOperation()) {
            warnOtherOperation(ctx, inst);
            return 0;
        }
        inst.restart();
        ctx.getSource().sendSuccess(() -> Component.translatable("pm.restart.success"), false);
        return 1;
    }

    private static int executeForceStop(CommandContext<CommandSourceStack> ctx, String id) throws CommandSyntaxException {
        if (!checkPermission(ctx.getSource(), "pm.forcestop")) return errorNoPermission(ctx);
        ProcessInstance inst = resolveInstance(ctx, id);
        if (inst == null) return 0;
        if (!inst.canStartNewOperation()) {
            warnOtherOperation(ctx, inst);
            return 0;
        }
        CompletableFuture.runAsync(() -> inst.forceStop());
        ctx.getSource().sendSuccess(() -> Component.translatable("pm.forcestop.success"), false);
        return 1;
    }

    private static int executeStatus(CommandContext<CommandSourceStack> ctx, String id) throws CommandSyntaxException {
        var src = ctx.getSource();
        var player = src.getPlayer();
        ProcessInstance inst = resolveInstance(ctx, id);
        if (inst == null) return 0;
        if (player == null) {
            String status = inst.getStatusMonitor() != null ? inst.getStatusMonitor().getFormattedStatus() : "Monitor not available.";
            src.sendSuccess(() -> Component.literal(status), false);
        } else {
            if (!checkPermission(src, "pm.status")) return errorNoPermission(ctx);
            ProcessManager.sendToPlayer(player, new OpenStatusScreenPacket(id));
        }
        return 1;
    }

    private static int executeImmersiveOn(CommandContext<CommandSourceStack> ctx, String id) throws CommandSyntaxException {
        var src = ctx.getSource();
        var player = src.getPlayer();
        ProcessInstance inst = resolveInstance(ctx, id);
        if (inst == null) return 0;
        if (!inst.isImmersiveAvailable()) {
            src.sendFailure(Component.translatable("pm.immersive.ssh_disabled"));
            return 0;
        }
        if (player == null) {
            if (inst.isImmersiveConsole()) {
                src.sendFailure(Component.translatable("pm.immersive.already_on"));
                return 0;
            }
            inst.setImmersiveConsole(true);
            src.sendSuccess(() -> Component.translatable("pm.immersive.on"), false);
        } else {
            if (!checkPermission(src, "pm.immersive.read")) return errorNoPermission(ctx);
            boolean canInput = checkPermission(src, "pm.immersive.input");
            inst.addImmersiveSubscriber(player, canInput);
            src.sendSuccess(() -> Component.translatable("pm.immersive.on_player"), false);
        }
        return 1;
    }

    private static int executeImmersiveOff(CommandContext<CommandSourceStack> ctx, String id) throws CommandSyntaxException {
        var src = ctx.getSource();
        var player = src.getPlayer();
        ProcessInstance inst = resolveInstance(ctx, id);
        if (inst == null) return 0;
        if (player == null) {
            if (!inst.isImmersiveConsole()) {
                src.sendFailure(Component.translatable("pm.immersive.already_off"));
                return 0;
            }
            inst.setImmersiveConsole(false);
            src.sendSuccess(() -> Component.translatable("pm.immersive.off"), false);
        } else {
            inst.removeImmersiveSubscriber(player);
        }
        return 1;
    }

    private static int executeInstanceHelp(CommandContext<CommandSourceStack> ctx, String id) {
        ctx.getSource().sendSuccess(() -> Component.literal("Commands for instance " + id + ": input/start/stop/restart/forcestop/status/immersive/ssh/set"), false);
        return 1;
    }

    private static int executeSshStatus(CommandContext<CommandSourceStack> ctx, String id) throws CommandSyntaxException {
        var src = ctx.getSource();
        if (!checkPermission(src, "pm.ssh")) return errorNoPermission(ctx);
        ProcessInstance inst = resolveInstance(ctx, id);
        if (inst == null) return 0;
        Config.InstanceConfig cfg = inst.getConfig();
        String user = (cfg.sshUser == null || cfg.sshUser.isEmpty()) ? "(unset)" : cfg.sshUser;
        boolean hasPassword = cfg.sshPasswordHash != null && !cfg.sshPasswordHash.isEmpty();
        src.sendSuccess(() -> Component.literal(
                "SSH for instance " + id + ": enabled=" + cfg.sshEnabled
                        + ", user=" + user + ", password=" + (hasPassword ? "set" : "unset")), false);
        return 1;
    }

    private static int executeSshEnable(CommandContext<CommandSourceStack> ctx, String id) throws CommandSyntaxException {
        var src = ctx.getSource();
        if (!checkPermission(src, "pm.ssh")) return errorNoPermission(ctx);
        ProcessInstance inst = resolveInstance(ctx, id);
        if (inst == null) return 0;
        Config.InstanceConfig cfg = inst.getConfig();
        if (cfg.sshUser == null || cfg.sshUser.isEmpty()) {
            src.sendFailure(Component.literal("Set an SSH username first: /pm " + id + " ssh setuser <user>"));
            return 0;
        }
        if (cfg.sshPasswordHash == null || cfg.sshPasswordHash.isEmpty()) {
            src.sendFailure(Component.literal("Set an SSH password first: /pm " + id + " ssh setpassword <password>"));
            return 0;
        }
        Config.setInstanceField(id, "sshEnabled", true);
        ProcessManager.ensureEmbeddedSsh();
        src.sendSuccess(() -> Component.literal("SSH enabled for instance " + id + " (user: " + cfg.sshUser + ")."), false);
        return 1;
    }

    private static int executeSshDisable(CommandContext<CommandSourceStack> ctx, String id) throws CommandSyntaxException {
        var src = ctx.getSource();
        if (!checkPermission(src, "pm.ssh")) return errorNoPermission(ctx);
        ProcessInstance inst = resolveInstance(ctx, id);
        if (inst == null) return 0;
        Config.setInstanceField(id, "sshEnabled", false);
        src.sendSuccess(() -> Component.literal("SSH disabled for instance " + id + "."), false);
        return 1;
    }

    private static int executeSshSetUser(CommandContext<CommandSourceStack> ctx, String id) throws CommandSyntaxException {
        var src = ctx.getSource();
        if (!checkPermission(src, "pm.ssh")) return errorNoPermission(ctx);
        ProcessInstance inst = resolveInstance(ctx, id);
        if (inst == null) return 0;
        String user = StringArgumentType.getString(ctx, "user");
        if (user.isEmpty()) {
            src.sendFailure(Component.literal("Username cannot be empty."));
            return 0;
        }
        if (Config.isSshUserTaken(user, id)) {
            src.sendFailure(Component.literal("SSH username '" + user + "' is already used by another instance."));
            return 0;
        }
        Config.setInstanceField(id, "sshUser", user);
        src.sendSuccess(() -> Component.literal("SSH username for instance " + id + " set to '" + user + "'."), false);
        return 1;
    }

    private static int executeSshSetPassword(CommandContext<CommandSourceStack> ctx, String id) throws CommandSyntaxException {
        var src = ctx.getSource();
        if (!checkPermission(src, "pm.ssh")) return errorNoPermission(ctx);
        if (!isConsole(src)) {
            src.sendFailure(Component.literal("For security, set the SSH password from the server console only."));
            return 0;
        }
        if (ProcessManager.isLogCommandsEnabled()) {
            src.sendFailure(Component.translatable("pm.set.password.log_commands_warning"));
            return 0;
        }
        ProcessInstance inst = resolveInstance(ctx, id);
        if (inst == null) return 0;
        String password = StringArgumentType.getString(ctx, "password");
        if (password.isEmpty()) {
            src.sendFailure(Component.literal("Password cannot be empty."));
            return 0;
        }
        if (!Config.setInstanceSshPassword(id, password)) {
            src.sendFailure(Component.literal("Failed to set SSH password."));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("SSH password for instance " + id + " has been set (bcrypt)."), false);
        return 1;
    }

    private static int executeHelp(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.translatable("pm.help.title"), false);
        return 1;
    }

    // 辅助方法：解析实例ID，如果不是数字则按名称查找，找不到发送错误
    private static ProcessInstance resolveInstance(CommandContext<CommandSourceStack> ctx, String id) {
        ProcessInstance inst;
        try {
            Integer.parseInt(id);
            inst = InstanceManager.getInstance().getInstance(id);
        } catch (NumberFormatException e) {
            inst = InstanceManager.getInstance().getInstanceByName(id);
        }
        if (inst == null) {
            ctx.getSource().sendFailure(Component.translatable("pm.error.instance_not_found", id));
        }
        return inst;
    }

    // 当有其他操作进行时，发送警告
    private static void warnOtherOperation(CommandContext<CommandSourceStack> ctx, ProcessInstance inst) {
        ctx.getSource().sendFailure(Component.translatable("pm.error.other_operation", inst.getId()));
    }

    private static int errorNoPermission(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendFailure(Component.translatable("pm.error.not_in_mode"));
        return 0;
    }
}