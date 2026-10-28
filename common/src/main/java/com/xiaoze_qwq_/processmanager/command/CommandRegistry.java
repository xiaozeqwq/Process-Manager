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
import com.xiaoze_qwq_.processmanager.ssh.SshServerHost;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.apache.sshd.common.config.keys.AuthorizedKeyEntry;
import org.apache.sshd.common.config.keys.KeyUtils;

import java.security.PublicKey;
import java.util.List;
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
                        .then(Commands.literal("setpassword")
                                .then(Commands.argument("password", StringArgumentType.greedyString())
                                        .executes(ctx -> executeSshSetPassword(ctx, getInstanceId(ctx)))))
                        .then(Commands.literal("auth")
                                .then(Commands.literal("password").executes(ctx -> executeSshSetAuthMode(
                                        ctx, getInstanceId(ctx), Config.SSH_AUTH_PASSWORD)))
                                .then(Commands.literal("publickey").executes(ctx -> executeSshSetAuthMode(
                                        ctx, getInstanceId(ctx), Config.SSH_AUTH_PUBLICKEY))))
                        .then(Commands.literal("keys").executes(ctx -> executeSshListKeys(ctx, getInstanceId(ctx))))
                        .then(Commands.literal("addkey")
                                .then(Commands.argument("key", StringArgumentType.greedyString())
                                        .executes(ctx -> executeSshAddKey(ctx, getInstanceId(ctx)))))
                        .then(Commands.literal("delkey")
                                .then(Commands.argument("index", IntegerArgumentType.integer(1))
                                        .executes(ctx -> executeSshDeleteKey(ctx, getInstanceId(ctx)))))
                        .then(Commands.literal("default")
                                .executes(ctx -> executeSshShowDefault(ctx, getInstanceId(ctx)))
                                .then(Commands.literal("set")
                                        .executes(ctx -> executeSshSetDefault(ctx, getInstanceId(ctx))))
                                .then(Commands.literal("clear")
                                        .executes(ctx -> executeSshClearDefault(ctx)))));

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
        String mode = Config.normalizeSshAuthMode(cfg.sshAuthMode);
        boolean hasPassword = cfg.sshPasswordHash != null && !cfg.sshPasswordHash.isEmpty();
        int keyCount = Config.countSshAuthorizedKeys(id);
        boolean ready = Config.isSshAuthReady(cfg);
        boolean sshdRunning = SshServerHost.getPort() > 0;
        src.sendSuccess(() -> Component.literal(
                "SSH for instance " + id + ": enabled=" + cfg.sshEnabled
                        + ", auth=" + mode
                        + ", credentials=" + (ready ? "ready" : "missing")
                        + ", password=" + (hasPassword ? "set" : "unset")
                        + ", keys=" + keyCount
                        + ", server=" + (sshdRunning ? "running" : "stopped")
                        + ", sessions=" + SshServerHost.getActiveSessionCount(id)), false);
        String name = cfg.name == null || cfg.name.isBlank() ? id : cfg.name;
        src.sendSuccess(() -> Component.literal(
                "Connect with: ssh -p <mc-port> " + id + "@<host>  (instance name: " + name + ")"), false);
        if (Config.SSH_AUTH_PUBLICKEY.equals(mode)) {
            src.sendSuccess(() -> Component.literal(
                    "Public keys file: " + Config.getSshAuthorizedKeysPath(id)), false);
        }
        String defaultId = Config.getSshDefaultInstance();
        if (id.equals(defaultId)) {
            src.sendSuccess(() -> Component.literal(
                    "This is the SSH default instance: unmatched usernames will require its password."), false);
        } else if (defaultId != null) {
            src.sendSuccess(() -> Component.literal(
                    "Unmatched SSH usernames fall back to instance " + defaultId
                            + " and require that instance's password."), false);
        } else {
            src.sendSuccess(() -> Component.literal(
                    "No SSH default instance is set: unmatched usernames are rejected."), false);
        }
        return 1;
    }

    private static int executeSshShowDefault(CommandContext<CommandSourceStack> ctx, String id)
            throws CommandSyntaxException {
        var src = ctx.getSource();
        if (!checkPermission(src, "pm.ssh")) return errorNoPermission(ctx);
        String activeId = Config.getSshDefaultInstance();
        String configuredId = Config.getSshDefaultInstanceConfigured();
        String shown = activeId != null ? activeId : configuredId;
        Config.InstanceConfig cfg = shown == null ? null : Config.getInstanceConfig(shown);
        String name = cfg == null || cfg.name == null || cfg.name.isBlank() ? "" : " (" + cfg.name + ")";
        String suffix = activeId != null || shown == null ? ""
                : " [inactive: enable it with password auth to use the fallback]";
        src.sendSuccess(() -> Component.literal(
                "SSH default instance: " + (shown == null ? "(none)" : shown + name + suffix)), false);
        return 1;
    }

    private static int executeSshSetDefault(CommandContext<CommandSourceStack> ctx, String id)
            throws CommandSyntaxException {
        var src = ctx.getSource();
        if (!checkPermission(src, "pm.ssh")) return errorNoPermission(ctx);
        if (!isConsole(src)) {
            src.sendFailure(Component.literal("For security, change the SSH default instance from the server console only."));
            return 0;
        }
        ProcessInstance inst = resolveInstance(ctx, id);
        if (inst == null) return 0;
        Config.InstanceConfig cfg = inst.getConfig();
        if (!cfg.sshEnabled) {
            src.sendFailure(Component.literal("Enable SSH for instance " + id + " before making it the default."));
            return 0;
        }
        if (!Config.SSH_AUTH_PASSWORD.equals(Config.normalizeSshAuthMode(cfg.sshAuthMode))) {
            src.sendFailure(Component.literal(
                    "The default instance must use password authentication so unmatched usernames can be rejected or accepted safely."));
            return 0;
        }
        if (cfg.sshPasswordHash == null || cfg.sshPasswordHash.isEmpty()) {
            src.sendFailure(Component.literal("Set an SSH password for instance " + id + " first."));
            return 0;
        }
        if (!Config.setSshDefaultInstance(id)) {
            src.sendFailure(Component.literal("Failed to set SSH default instance."));
            return 0;
        }
        ProcessManager.applySshConfigurationChange();
        src.sendSuccess(() -> Component.literal(
                "SSH default instance set to " + id + ". Unmatched usernames will require this instance's password."), false);
        return 1;
    }

    private static int executeSshClearDefault(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var src = ctx.getSource();
        if (!checkPermission(src, "pm.ssh")) return errorNoPermission(ctx);
        if (!isConsole(src)) {
            src.sendFailure(Component.literal("For security, change the SSH default instance from the server console only."));
            return 0;
        }
        Config.clearSshDefaultInstance();
        ProcessManager.applySshConfigurationChange();
        src.sendSuccess(() -> Component.literal(
                "SSH default instance cleared. Unmatched usernames are now rejected."), false);
        return 1;
    }

    private static int executeSshEnable(CommandContext<CommandSourceStack> ctx, String id) throws CommandSyntaxException {
        var src = ctx.getSource();
        if (!checkPermission(src, "pm.ssh")) return errorNoPermission(ctx);
        ProcessInstance inst = resolveInstance(ctx, id);
        if (inst == null) return 0;
        Config.InstanceConfig cfg = inst.getConfig();
        if (!Config.isSshAuthReady(cfg)) {
            if (Config.SSH_AUTH_PUBLICKEY.equals(Config.normalizeSshAuthMode(cfg.sshAuthMode))) {
                src.sendFailure(Component.literal("Add a public key first: /pm " + id + " ssh addkey <public-key>"));
            } else {
                src.sendFailure(Component.literal("Set an SSH password first: /pm " + id + " ssh setpassword <password>"));
            }
            return 0;
        }
        if (inst.isRunning() || !inst.canStartNewOperation()) {
            src.sendFailure(Component.literal("Stop the instance before enabling SSH."));
            return 0;
        }
        if (!Config.setInstanceSshEnabled(id, true)) {
            src.sendFailure(Component.literal("Failed to enable SSH for instance " + id + "."));
            return 0;
        }
        ProcessManager.applySshConfigurationChange();
        if (SshServerHost.getPort() <= 0) {
            Config.setInstanceSshEnabled(id, false);
            ProcessManager.applySshConfigurationChange();
            src.sendFailure(Component.literal("SSH server failed to start. Check the server log."));
            return 0;
        }
        String name = cfg.name == null || cfg.name.isBlank() ? null : cfg.name;
        String hint = name == null || name.equals(id)
                ? id + "@<host>"
                : id + "@<host> or " + name + "@<host>";
        src.sendSuccess(() -> Component.literal(
                "SSH enabled for instance " + id + ". Connect with: ssh -p <mc-port> " + hint), false);
        return 1;
    }

    private static int executeSshDisable(CommandContext<CommandSourceStack> ctx, String id) throws CommandSyntaxException {
        var src = ctx.getSource();
        if (!checkPermission(src, "pm.ssh")) return errorNoPermission(ctx);
        ProcessInstance inst = resolveInstance(ctx, id);
        if (inst == null) return 0;
        if (inst.isRunning() || !inst.canStartNewOperation()) {
            src.sendFailure(Component.literal("Stop the instance before disabling SSH."));
            return 0;
        }
        boolean wasEnabled = inst.getConfig().sshEnabled;
        Config.setInstanceSshEnabled(id, false);
        if (wasEnabled) {
            ProcessManager.applySshConfigurationChange();
        }
        src.sendSuccess(() -> Component.literal("SSH disabled for instance " + id + ". Its SSH sessions have been closed."), false);
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
        if (password.length() < 12 || password.length() > 64 || password.trim().isEmpty()) {
            src.sendFailure(Component.literal("SSH password must be 12-64 characters and cannot be blank."));
            return 0;
        }
        if (!Config.setInstanceSshPassword(id, password)) {
            src.sendFailure(Component.literal("Failed to set SSH password."));
            return 0;
        }
        Config.InstanceConfig cfg = Config.getInstanceConfig(id);
        if (cfg != null && cfg.sshEnabled
                && Config.SSH_AUTH_PASSWORD.equals(Config.normalizeSshAuthMode(cfg.sshAuthMode))) {
            ProcessManager.disconnectSshSessions(id);
        }
        src.sendSuccess(() -> Component.literal("SSH password for instance " + id + " has been set (bcrypt)."), false);
        return 1;
    }

    private static int executeSshSetAuthMode(CommandContext<CommandSourceStack> ctx, String id, String mode)
            throws CommandSyntaxException {
        var src = ctx.getSource();
        if (!checkPermission(src, "pm.ssh")) return errorNoPermission(ctx);
        if (!isConsole(src)) {
            src.sendFailure(Component.literal("For security, change the SSH auth mode from the server console only."));
            return 0;
        }
        ProcessInstance inst = resolveInstance(ctx, id);
        if (inst == null) return 0;
        if (inst.isRunning() || !inst.canStartNewOperation()) {
            src.sendFailure(Component.literal("Stop the instance before changing the SSH auth mode."));
            return 0;
        }
        Config.InstanceConfig cfg = inst.getConfig();
        if (Config.SSH_AUTH_PUBLICKEY.equals(mode) && Config.isSshDefaultInstance(id)) {
            src.sendFailure(Component.literal(
                    "This instance is the SSH default and must keep password authentication. Set another default first."));
            return 0;
        }
        if (Config.SSH_AUTH_PUBLICKEY.equals(mode) && Config.countSshAuthorizedKeys(id) == 0) {
            src.sendFailure(Component.literal("Add a public key first: /pm " + id + " ssh addkey <public-key>"));
            return 0;
        }
        if (Config.SSH_AUTH_PASSWORD.equals(mode)
                && (cfg.sshPasswordHash == null || cfg.sshPasswordHash.isEmpty())) {
            src.sendFailure(Component.literal("Set an SSH password first: /pm " + id + " ssh setpassword <password>"));
            return 0;
        }
        if (!Config.setInstanceSshAuthMode(id, mode)) {
            src.sendFailure(Component.literal("Failed to change SSH auth mode for instance " + id + "."));
            return 0;
        }
        Config.InstanceConfig updated = Config.getInstanceConfig(id);
        if (updated != null && updated.sshEnabled) {
            ProcessManager.applySshConfigurationChange();
            ProcessManager.disconnectSshSessions(id);
        }
        src.sendSuccess(() -> Component.literal(
                "SSH auth mode for instance " + id + " set to " + mode + ". Existing sessions were closed."), false);
        return 1;
    }

    private static int executeSshListKeys(CommandContext<CommandSourceStack> ctx, String id) throws CommandSyntaxException {
        var src = ctx.getSource();
        if (!checkPermission(src, "pm.ssh")) return errorNoPermission(ctx);
        ProcessInstance inst = resolveInstance(ctx, id);
        if (inst == null) return 0;
        List<String> keys = Config.readSshAuthorizedKeys(id);
        if (keys.isEmpty()) {
            src.sendSuccess(() -> Component.literal(
                    "No SSH public keys configured for instance " + id + ". Add one with: /pm " + id + " ssh addkey <public-key>"), false);
            return 1;
        }
        for (int i = 0; i < keys.size(); i++) {
            int index = i + 1;
            String line = keys.get(i);
            String summary;
            try {
                AuthorizedKeyEntry entry = AuthorizedKeyEntry.parseAuthorizedKeyEntry(line);
                PublicKey key = entry == null ? null : entry.resolvePublicKey(null, null, null);
                String comment = entry == null || entry.getComment() == null || entry.getComment().isBlank()
                        ? "" : " " + entry.getComment();
                summary = entry == null || key == null
                        ? "(unparsable key)"
                        : entry.getKeyType() + " " + KeyUtils.getFingerPrint(key) + comment;
            } catch (Exception e) {
                summary = "(unparsable key)";
            }
            String rendered = summary;
            src.sendSuccess(() -> Component.literal(index + ": " + rendered), false);
        }
        return 1;
    }

    private static int executeSshAddKey(CommandContext<CommandSourceStack> ctx, String id) throws CommandSyntaxException {
        var src = ctx.getSource();
        if (!checkPermission(src, "pm.ssh")) return errorNoPermission(ctx);
        if (!isConsole(src)) {
            src.sendFailure(Component.literal("For security, add SSH public keys from the server console only."));
            return 0;
        }
        ProcessInstance inst = resolveInstance(ctx, id);
        if (inst == null) return 0;
        String raw = StringArgumentType.getString(ctx, "key");
        AuthorizedKeyEntry entry;
        PublicKey key;
        try {
            entry = AuthorizedKeyEntry.parseAuthorizedKeyEntry(raw);
            if (entry == null) throw new IllegalArgumentException("empty key");
            key = entry.resolvePublicKey(null, null, null);
        } catch (Exception e) {
            src.sendFailure(Component.literal("Invalid SSH public key. Expected format: ssh-ed25519 AAAA... comment"));
            return 0;
        }
        if (key == null) {
            src.sendFailure(Component.literal("Invalid SSH public key. Expected format: ssh-ed25519 AAAA... comment"));
            return 0;
        }
        if (!Config.addSshAuthorizedKey(id, entry.toString())) {
            src.sendFailure(Component.literal("This public key is already configured or could not be saved."));
            return 0;
        }
        String fingerprint = KeyUtils.getFingerPrint(key);
        String keyType = entry.getKeyType();
        int total = Config.countSshAuthorizedKeys(id);
        src.sendSuccess(() -> Component.literal(
                "Added " + keyType + " key " + fingerprint + " to instance " + id + ". Total keys: " + total), false);
        return 1;
    }

    private static int executeSshDeleteKey(CommandContext<CommandSourceStack> ctx, String id) throws CommandSyntaxException {
        var src = ctx.getSource();
        if (!checkPermission(src, "pm.ssh")) return errorNoPermission(ctx);
        if (!isConsole(src)) {
            src.sendFailure(Component.literal("For security, remove SSH public keys from the server console only."));
            return 0;
        }
        ProcessInstance inst = resolveInstance(ctx, id);
        if (inst == null) return 0;
        int index = IntegerArgumentType.getInteger(ctx, "index");
        if (!Config.removeSshAuthorizedKey(id, index)) {
            src.sendFailure(Component.literal("No SSH public key at index " + index + "."));
            return 0;
        }
        Config.InstanceConfig cfg = Config.getInstanceConfig(id);
        boolean noKeysLeft = Config.countSshAuthorizedKeys(id) == 0;
        if (noKeysLeft && cfg != null && cfg.sshEnabled
                && Config.SSH_AUTH_PUBLICKEY.equals(Config.normalizeSshAuthMode(cfg.sshAuthMode))) {
            if (!inst.isRunning() && inst.canStartNewOperation()) {
                Config.setInstanceSshEnabled(id, false);
                ProcessManager.applySshConfigurationChange();
                src.sendSuccess(() -> Component.literal(
                        "Removed key " + index + " from instance " + id
                                + ". No keys remain, so SSH has been disabled."), false);
                return 1;
            }
            src.sendSuccess(() -> Component.literal(
                    "Removed key " + index + " from instance " + id
                            + ". No keys remain, so new logins are rejected until you add a key."), false);
            return 1;
        }
        src.sendSuccess(() -> Component.literal("Removed SSH public key " + index + " from instance " + id + "."), false);
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