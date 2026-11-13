package com.xiaoze_qwq_.processmanager.ssh;

import com.pty4j.PtyProcess;
import com.xiaoze_qwq_.processmanager.InstanceManager;
import com.xiaoze_qwq_.processmanager.ProcessInstance;
import com.xiaoze_qwq_.processmanager.ProcessManager;
import com.xiaoze_qwq_.processmanager.config.Config;
import com.xiaoze_qwq_.processmanager.monitor.StatusMonitor;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.Signal;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.session.ServerSession;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * SSH 保留用户名 "menu" 登录后的 TUI 实例管理菜单（ANSI 全屏界面，非纯命令行）。
 * 提供实例列表、启停控制、状态总览、命令下发与控制台桥接；
 * 菜单内没有任何密码修改入口，密码只能由服务器控制台管理。
 * 控制台模式透传实例 PTY 输入输出，Ctrl-A d 返回菜单，实例退出自动回菜单。
 */
public class MenuTuiCommand implements Command {
    private static final byte[] EOF = new byte[0];
    private static final int QUEUE_CAPACITY = 256;
    private static final int MAX_INPUT_LENGTH = 256;

    private static final String CLEAR_SCREEN = "\u001b[H\u001b[2J";
    private static final String HIDE_CURSOR = "\u001b[?25l";
    private static final String SHOW_CURSOR = "\u001b[?25h";
    private static final String RESET = "\u001b[0m";
    private static final String REVERSE = "\u001b[7m";
    private static final String BOLD = "\u001b[1m";
    private static final String CYAN = "\u001b[36m";
    private static final String GREEN = "\u001b[32m";
    private static final String RED = "\u001b[31m";
    private static final String YELLOW = "\u001b[33m";
    private static final String GRAY = "\u001b[90m";

    /** 实例操作在独立线程执行，避免 stop 等阻塞调用冻结 TUI。 */
    private static final ExecutorService ACTIONS_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "PM-Menu-Action");
        t.setDaemon(true);
        return t;
    });

    private enum Screen { MAIN, ACTIONS, STATUS, INPUT, CONFIRM, CONSOLE }

    private enum PendingAction { STOP, RESTART, FORCE_STOP }

    private final Object stateLock = new Object();
    private final BlockingQueue<byte[]> outputQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final CountDownLatch writerDone = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private final AtomicBoolean exitReported = new AtomicBoolean(false);

    private InputStream in;
    private OutputStream out;
    private ExitCallback callback;
    private ServerSession session;

    private Thread inputThread;
    private Thread writerThread;
    private ScheduledExecutorService refreshTimer;
    private volatile boolean exitRequested;
    private volatile int exitCode = -1;

    private Screen screen = Screen.MAIN;
    private final List<ProcessInstance> listedInstances = new ArrayList<>();
    private int mainIndex;
    private int actionIndex;
    private String currentId;
    private PendingAction pendingAction;
    private final StringBuilder inputLine = new StringBuilder();
    private String message = "";
    private ProcessInstance.ConsoleOutputListener consoleListener;
    private boolean ctrlAPending;

    private int cols = 80;
    private int rows = 24;
    private String ip = "unknown";

    @Override
    public void setInputStream(InputStream in) {
        this.in = in;
    }

    @Override
    public void setOutputStream(OutputStream out) {
        this.out = out;
    }

    @Override
    public void setErrorStream(OutputStream err) {
    }

    @Override
    public void setExitCallback(ExitCallback callback) {
        this.callback = callback;
    }

    @Override
    public void start(ChannelSession channel, Environment env) {
        this.session = channel.getSession();
        this.ip = SshServerHost.peerIp(session);
        int[] size = readSize(env);
        cols = size[0];
        rows = size[1];
        env.addSignalListener((ch, signal) -> {
            if (signal == Signal.WINCH) {
                int[] s = readSize(env);
                synchronized (stateLock) {
                    cols = s[0];
                    rows = s[1];
                    if (screen == Screen.CONSOLE) {
                        ProcessInstance inst = current();
                        if (inst != null) inst.resizeConsole(s[0], s[1]);
                    } else {
                        renderLocked();
                    }
                }
            }
        }, Signal.WINCH);

        startWriter();
        refreshTimer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PM-Menu-Refresh");
            t.setDaemon(true);
            return t;
        });
        refreshTimer.scheduleAtFixedRate(() -> {
            synchronized (stateLock) {
                if (screen == Screen.MAIN || screen == Screen.STATUS) renderLocked();
            }
        }, 1, 1, TimeUnit.SECONDS);
        synchronized (stateLock) {
            enqueue(CLEAR_SCREEN.getBytes(StandardCharsets.UTF_8));
            enqueue(HIDE_CURSOR.getBytes(StandardCharsets.UTF_8));
            renderLocked();
        }
        inputThread = new Thread(this::readLoop, "PM-Menu-Input");
        inputThread.setDaemon(true);
        inputThread.start();
    }

    // ==================== 输入处理 ====================
    private void readLoop() {
        InputStream source = in;
        if (source == null) return;
        MenuKeyDecoder decoder = new MenuKeyDecoder();
        byte[] buf = new byte[1024];
        try {
            int n;
            while (!closed.get() && (n = source.read(buf)) != -1) {
                if (n <= 0) continue;
                boolean consoleMode;
                synchronized (stateLock) {
                    consoleMode = screen == Screen.CONSOLE;
                }
                if (consoleMode) {
                    handleConsoleBytes(buf, n);
                    continue;
                }
                for (int i = 0; i < n && !closed.get(); i++) {
                    MenuKeyDecoder.Key key = decoder.feed(buf[i]);
                    if (key != null && key != MenuKeyDecoder.Key.IGNORED) {
                        handleKey(key, decoder.getChar());
                    }
                }
                MenuKeyDecoder.Key pending = decoder.flushPending();
                if (pending != null && !closed.get()) {
                    handleKey(pending, "");
                }
            }
        } catch (IOException ignored) {
            // 客户端断开，由 destroy() 收尾
        }
    }

    private void handleKey(MenuKeyDecoder.Key key, String text) {
        synchronized (stateLock) {
            if (screen == Screen.CONSOLE) return;
            message = "";
            switch (screen) {
                case MAIN: handleMainKey(key, text); break;
                case ACTIONS: handleActionsKey(key, text); break;
                case STATUS: handleStatusKey(key, text); break;
                case INPUT: handleInputKey(key, text); break;
                case CONFIRM: handleConfirmKey(key); break;
                default: break;
            }
        }
    }

    private void handleMainKey(MenuKeyDecoder.Key key, String text) {
        switch (key) {
            case UP:
                if (mainIndex > 0) {
                    mainIndex--;
                    renderLocked();
                }
                return;
            case DOWN:
                if (mainIndex < Math.max(0, listedInstances.size() - 1)) {
                    mainIndex++;
                    renderLocked();
                }
                return;
            case ENTER:
                if (listedInstances.isEmpty()) {
                    message = "No instances configured.";
                    renderLocked();
                    return;
                }
                currentId = listedInstances.get(Math.min(mainIndex, listedInstances.size() - 1)).getId();
                screen = Screen.ACTIONS;
                actionIndex = 0;
                renderLocked();
                return;
            case ESC:
            case CTRL_C:
                finish(0, "Menu closed.");
                return;
            case CHAR:
                if ("r".equals(text) || "R".equals(text)) {
                    renderLocked();
                } else if ("q".equals(text) || "Q".equals(text)) {
                    finish(0, "Menu closed.");
                } else if ("j".equals(text)) {
                    handleMainKey(MenuKeyDecoder.Key.DOWN, "");
                } else if ("k".equals(text)) {
                    handleMainKey(MenuKeyDecoder.Key.UP, "");
                }
                return;
            default:
        }
    }

    private void handleActionsKey(MenuKeyDecoder.Key key, String text) {
        switch (key) {
            case UP:
                if (actionIndex > 0) {
                    actionIndex--;
                    renderLocked();
                }
                return;
            case DOWN:
                if (actionIndex < ACTION_COUNT - 1) {
                    actionIndex++;
                    renderLocked();
                }
                return;
            case ENTER:
                runAction(actionIndex);
                return;
            case ESC:
            case CTRL_C:
                screen = Screen.MAIN;
                renderLocked();
                return;
            case CHAR:
                if ("b".equals(text) || "B".equals(text)) {
                    screen = Screen.MAIN;
                    renderLocked();
                } else if ("j".equals(text)) {
                    handleActionsKey(MenuKeyDecoder.Key.DOWN, "");
                } else if ("k".equals(text)) {
                    handleActionsKey(MenuKeyDecoder.Key.UP, "");
                }
                return;
            default:
        }
    }

    private void handleStatusKey(MenuKeyDecoder.Key key, String text) {
        switch (key) {
            case ESC:
            case ENTER:
            case CTRL_C:
                screen = Screen.ACTIONS;
                renderLocked();
                return;
            case CHAR:
                if ("r".equals(text) || "R".equals(text)) {
                    renderLocked();
                }
                return;
            default:
        }
    }

    private void handleInputKey(MenuKeyDecoder.Key key, String text) {
        switch (key) {
            case CHAR:
                if (inputLine.length() + text.length() <= MAX_INPUT_LENGTH) {
                    inputLine.append(text);
                    renderLocked();
                }
                return;
            case BACKSPACE:
                deleteLastCodePoint();
                renderLocked();
                return;
            case ENTER:
                String command = inputLine.toString().trim();
                ProcessInstance inst = current();
                if (!command.isEmpty() && inst != null) {
                    boolean ok = inst.writeRawInput((command + "\r").getBytes(StandardCharsets.UTF_8));
                    message = ok ? "Command sent." : "Failed: instance is not running.";
                } else if (inst == null) {
                    message = "Instance removed.";
                }
                screen = Screen.ACTIONS;
                renderLocked();
                return;
            case ESC:
            case CTRL_C:
                screen = Screen.ACTIONS;
                renderLocked();
                return;
            default:
        }
    }

    private void handleConfirmKey(MenuKeyDecoder.Key key) {
        switch (key) {
            case ENTER:
                PendingAction action = pendingAction;
                ProcessInstance inst = current();
                if (inst != null && action != null) {
                    switch (action) {
                        case STOP:
                            submit("stop", inst, i -> i.stop(true));
                            message = "Stop requested.";
                            break;
                        case RESTART:
                            submit("restart", inst, i -> i.restart());
                            message = "Restart requested.";
                            break;
                        case FORCE_STOP:
                            submit("force_stop", inst, ProcessInstance::forceStop);
                            message = "Force stop requested.";
                            break;
                    }
                } else {
                    message = "Instance removed.";
                }
                screen = Screen.ACTIONS;
                renderLocked();
                return;
            case ESC:
            case CTRL_C:
                screen = Screen.ACTIONS;
                renderLocked();
                return;
            default:
        }
    }

    private void deleteLastCodePoint() {
        if (inputLine.length() == 0) return;
        int cp = inputLine.codePointBefore(inputLine.length());
        inputLine.delete(inputLine.length() - Character.charCount(cp), inputLine.length());
    }

    // ==================== 实例操作 ====================
    private static final int ACTION_COUNT = 8;

    private void runAction(int index) {
        ProcessInstance inst = current();
        if (inst == null) {
            message = "Instance removed.";
            screen = Screen.MAIN;
            renderLocked();
            return;
        }
        boolean running = inst.isRunning();
        switch (index) {
            case 0:
                if (running || !inst.canStartNewOperation()) {
                    message = running ? "Instance is already running." : "Another operation is in progress.";
                    renderLocked();
                } else {
                    submit("start", inst, ProcessInstance::start);
                    message = "Start requested.";
                    renderLocked();
                }
                return;
            case 1:
                if (!running) {
                    message = "Instance is not running.";
                    renderLocked();
                } else {
                    pendingAction = PendingAction.STOP;
                    screen = Screen.CONFIRM;
                    renderLocked();
                }
                return;
            case 2:
                if (!running) {
                    message = "Instance is not running.";
                    renderLocked();
                } else {
                    pendingAction = PendingAction.RESTART;
                    screen = Screen.CONFIRM;
                    renderLocked();
                }
                return;
            case 3:
                if (!running) {
                    message = "Instance is not running.";
                    renderLocked();
                } else {
                    pendingAction = PendingAction.FORCE_STOP;
                    screen = Screen.CONFIRM;
                    renderLocked();
                }
                return;
            case 4:
                screen = Screen.STATUS;
                renderLocked();
                return;
            case 5:
                if (!running) {
                    message = "Instance is not running.";
                    renderLocked();
                } else {
                    inputLine.setLength(0);
                    screen = Screen.INPUT;
                    renderLocked();
                }
                return;
            case 6:
                attachConsole();
                return;
            case 7:
                screen = Screen.MAIN;
                renderLocked();
                return;
            default:
        }
    }

    /** 菜单操作禁止修改任何密码：此处刻意没有任何 setpassword / clearpassword 入口。 */
    private void submit(String action, ProcessInstance inst, Consumer<ProcessInstance> op) {
        String id = inst.getId();
        SshAudit.menuAction(ip, id, action);
        ACTIONS_EXECUTOR.submit(() -> {
            ProcessInstance target = InstanceManager.getInstance().getInstance(id);
            if (target == null) return;
            try {
                op.accept(target);
            } catch (Throwable t) {
                ProcessManager.LOGGER.warn("[menu] action {} failed for instance {}", action, id, t);
            }
        });
    }

    // ==================== 控制台桥接 ====================
    private void attachConsole() {
        ProcessInstance inst = current();
        if (inst == null) {
            message = "Instance removed.";
            screen = Screen.MAIN;
            renderLocked();
            return;
        }
        Config.InstanceConfig cfg = inst.getConfig();
        if (!cfg.sshEnabled) {
            message = "Console unavailable: SSH is disabled for this instance.";
            renderLocked();
            return;
        }
        boolean pty = inst.getProcess() instanceof PtyProcess;
        if (!inst.isRunning() || !pty) {
            message = "Console unavailable: instance is not running.";
            renderLocked();
            return;
        }
        screen = Screen.CONSOLE;
        ctrlAPending = false;
        consoleListener = new ProcessInstance.ConsoleOutputListener() {
            @Override
            public void onConsoleOutput(byte[] data, int length) {
                if (destroyed()) return;
                byte[] copy = new byte[length];
                System.arraycopy(data, 0, copy, 0, length);
                enqueue(copy);
            }

            @Override
            public void onConsoleClosed(int exitCode) {
                handleConsoleClosed(exitCode);
            }
        };
        inst.addConsoleListener(consoleListener);
        SshAudit.menuAction(ip, inst.getId(), "console_attach");
        enqueue(CLEAR_SCREEN.getBytes(StandardCharsets.UTF_8));
        enqueue(("\r\n[attached to instance " + inst.getId() + " console — press Ctrl-A then d to return to the menu]\r\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    private void handleConsoleBytes(byte[] data, int length) {
        ProcessInstance inst = current();
        if (inst == null) {
            synchronized (stateLock) {
                detachLocked();
                message = "Instance removed.";
                screen = Screen.ACTIONS;
                renderLocked();
            }
            return;
        }
        if (!ctrlAPending && indexOf(data, length, (byte) 0x01) < 0) {
            byte[] copy = new byte[length];
            System.arraycopy(data, 0, copy, 0, length);
            inst.writeRawInput(copy);
            return;
        }
        for (int i = 0; i < length; i++) {
            byte b = data[i];
            if (ctrlAPending) {
                ctrlAPending = false;
                if (b == 'd' || b == 'D') {
                    detachFromConsole();
                    return;
                }
                inst.writeRawInput(new byte[]{0x01, b});
            } else if (b == 0x01) {
                ctrlAPending = true;
            } else {
                inst.writeRawInput(new byte[]{b});
            }
        }
    }

    private void detachFromConsole() {
        synchronized (stateLock) {
            if (screen != Screen.CONSOLE) return;
            detachLocked();
            SshAudit.menuAction(ip, currentId, "console_detach");
            message = "Detached from console.";
            renderLocked();
        }
    }

    private void handleConsoleClosed(int exitCode) {
        synchronized (stateLock) {
            if (screen != Screen.CONSOLE) return;
            detachLocked();
            SshAudit.menuAction(ip, currentId, "console_exit");
            message = "Instance exited (code " + exitCode + ").";
            renderLocked();
        }
    }

    private void detachLocked() {
        ProcessInstance inst = current();
        if (inst != null && consoleListener != null) {
            inst.removeConsoleListener(consoleListener);
        }
        consoleListener = null;
        ctrlAPending = false;
    }

    private static int indexOf(byte[] data, int length, byte target) {
        for (int i = 0; i < length; i++) {
            if (data[i] == target) return i;
        }
        return -1;
    }

    // ==================== 渲染 ====================
    private void renderLocked() {
        switch (screen) {
            case MAIN: enqueue(buildLines(mainLines()).getBytes(StandardCharsets.UTF_8)); break;
            case ACTIONS: enqueue(buildLines(actionLines()).getBytes(StandardCharsets.UTF_8)); break;
            case STATUS: enqueue(buildLines(statusLines()).getBytes(StandardCharsets.UTF_8)); break;
            case INPUT: enqueue(buildLines(inputLines()).getBytes(StandardCharsets.UTF_8)); break;
            case CONFIRM: enqueue(buildLines(confirmLines()).getBytes(StandardCharsets.UTF_8)); break;
            default: break;
        }
    }

    private String buildLines(List<String> lines) {
        StringBuilder b = new StringBuilder(CLEAR_SCREEN);
        for (String line : lines) {
            b.append(line).append("\r\n");
        }
        return b.toString();
    }

    private List<String> mainLines() {
        listedInstances.clear();
        listedInstances.addAll(InstanceManager.getInstance().getAllInstances());
        listedInstances.sort(Comparator.comparingInt(i -> {
            try {
                return Integer.parseInt(i.getId());
            } catch (NumberFormatException e) {
                return Integer.MAX_VALUE;
            }
        }));
        if (mainIndex >= listedInstances.size()) mainIndex = Math.max(0, listedInstances.size() - 1);
        if (mainIndex < 0) mainIndex = 0;

        List<String> lines = new ArrayList<>();
        lines.add(topBorder("Process Manager"));
        lines.add(blank());
        lines.add(row(" Instances: " + listedInstances.size() + "        [r] refresh   [q] quit", GRAY));
        lines.add(blank());
        int window = Math.max(1, rows - 9);
        int from = Math.max(0, Math.min(mainIndex - window / 2, listedInstances.size() - window));
        int to = Math.min(listedInstances.size(), from + window);
        if (listedInstances.isEmpty()) {
            lines.add(row("   (no instances configured)", GRAY));
        }
        for (int i = from; i < to; i++) {
            ProcessInstance inst = listedInstances.get(i);
            Config.InstanceConfig cfg = inst.getConfig();
            String name = cfg.name == null || cfg.name.isEmpty() ? inst.getId() : cfg.name;
            String state = stateText(inst);
            String detail = inst.isRunning() ? "pid " + inst.getPid() : "";
            String ssh = cfg.sshEnabled ? "ssh:on" : "ssh:off";
            String plain = String.format(" %s %-4s %-16s %-10s %-9s %s",
                    i == mainIndex ? ">" : " ", inst.getId(), name, state, detail, ssh);
            if (i == mainIndex) {
                lines.add(row(plain, REVERSE + BOLD));
            } else {
                lines.add(row(plain, stateColor(inst)));
            }
        }
        lines.add(blank());
        lines.add(bottomBorder());
        lines.add(footer(" Up/Down: select   Enter: open   r: refresh   q: quit"));
        return lines;
    }

    private List<String> actionLines() {
        ProcessInstance inst = current();
        List<String> lines = new ArrayList<>();
        String title = inst == null ? "instance removed" : "Instance " + inst.getId() + " - " + displayName(inst)
                + " - " + stateText(inst);
        lines.add(topBorder(title));
        lines.add(blank());
        if (inst == null) {
            lines.add(row("   (instance removed)", RED));
        } else {
            boolean running = inst.isRunning();
            boolean busy = !inst.canStartNewOperation();
            boolean consoleOk = inst.getConfig().sshEnabled && running
                    && inst.getProcess() instanceof PtyProcess;
            String[] labels = {
                    "Start" + (!running && !busy ? "" : running ? "   (already running)" : "   (busy)"),
                    "Stop (graceful)" + (running ? "" : "   (stopped)"),
                    "Restart" + (running ? "" : "   (stopped)"),
                    "Force stop" + (running ? "" : "   (stopped)"),
                    "Status",
                    "Send command" + (running ? "" : "   (stopped)"),
                    "Console (Ctrl-A d to return)"
                            + (consoleOk ? "" : inst.getConfig().sshEnabled ? "   (not running)" : "   (SSH disabled)"),
                    "Back"
            };
            boolean[] enabled = {
                    !running && !busy,
                    running,
                    running,
                    running,
                    true,
                    running,
                    consoleOk,
                    true
            };
            for (int i = 0; i < labels.length; i++) {
                String plain = " " + (i == actionIndex ? "> " : "  ") + labels[i];
                if (i == actionIndex) {
                    lines.add(row(plain, REVERSE + (enabled[i] ? BOLD : GRAY)));
                } else {
                    lines.add(row(plain, enabled[i] ? RESET : GRAY));
                }
            }
        }
        lines.add(blank());
        lines.add(bottomBorder());
        lines.add(footer(" Up/Down: select   Enter: run   Esc: back"));
        return lines;
    }

    private List<String> statusLines() {
        ProcessInstance inst = current();
        List<String> lines = new ArrayList<>();
        String title = inst == null ? "instance removed" : "Status - " + inst.getId() + " - " + displayName(inst);
        lines.add(topBorder(title));
        lines.add(blank());
        if (inst == null) {
            lines.add(row("   (instance removed)", RED));
        } else {
            StatusMonitor monitor = inst.getStatusMonitor();
            String running = inst.isRunning() ? "yes" : "no";
            String pid = inst.getPid() > 0 ? String.valueOf(inst.getPid()) : "-";
            String uptime = monitor != null && inst.isRunning() ? monitor.getUptimeFormatted() : "N/A";
            String cpu = monitor != null && inst.isRunning() ? String.format(Locale.ROOT, "%.1f%%", monitor.getCpuUsage()) : "N/A";
            String mem = monitor != null && inst.isRunning() ? monitor.getMemoryMB() + " MB" : "N/A";
            Config.InstanceConfig cfg = inst.getConfig();
            String ssh = cfg.sshEnabled ? "enabled (" + Config.normalizeSshAuthMode(cfg.sshAuthMode) + ")" : "disabled";
            lines.add(kvLine("Running", running + "   (pid " + pid + ")"));
            lines.add(kvLine("Operation", stateText(inst)));
            lines.add(kvLine("Uptime", uptime));
            lines.add(kvLine("CPU", cpu));
            lines.add(kvLine("Memory", mem));
            lines.add(kvLine("Auto-restart", cfg.autoRestart ? "on" : "off"));
            lines.add(kvLine("Exit detection", cfg.exitDetectionMode + " (" + cfg.exitCode + ")"));
            lines.add(kvLine("SSH", ssh));
        }
        lines.add(blank());
        lines.add(bottomBorder());
        lines.add(footer(" [Esc] back   [r] refresh now   (auto refresh 1s)"));
        return lines;
    }

    private List<String> inputLines() {
        ProcessInstance inst = current();
        List<String> lines = new ArrayList<>();
        String title = inst == null ? "send command" : "Send command - " + inst.getId() + " - " + displayName(inst);
        lines.add(topBorder(title));
        lines.add(blank());
        lines.add(row(" Command to send to the instance stdin:", RESET));
        lines.add(blank());
        lines.add(row(" > " + inputLine + "_", BOLD));
        lines.add(blank());
        lines.add(bottomBorder());
        lines.add(footer(" [Enter] send   [Esc] cancel"));
        return lines;
    }

    private List<String> confirmLines() {
        ProcessInstance inst = current();
        String verb = pendingAction == PendingAction.STOP ? "stop"
                : pendingAction == PendingAction.RESTART ? "restart" : "force stop";
        List<String> lines = new ArrayList<>();
        lines.add(topBorder("Confirm"));
        lines.add(blank());
        if (inst == null) {
            lines.add(row("   (instance removed)", RED));
        } else {
            lines.add(row(" Really " + verb + " instance " + inst.getId()
                    + " (" + displayName(inst) + ")?", YELLOW + BOLD));
        }
        lines.add(blank());
        lines.add(bottomBorder());
        lines.add(footer(" [Enter] confirm   [Esc] cancel"));
        return lines;
    }

    private String kvLine(String key, String value) {
        String plain = String.format(" %-16s %s", key + ":", value);
        return row(plain, RESET);
    }

    private String stateText(ProcessInstance inst) {
        if (inst.isRunning()) {
            ProcessInstance.OperationState op = inst.getOperationState();
            return op == ProcessInstance.OperationState.IDLE ? "RUNNING" : op.name();
        }
        return "STOPPED";
    }

    private String stateColor(ProcessInstance inst) {
        if (!inst.isRunning()) return RED;
        return inst.getOperationState() == ProcessInstance.OperationState.IDLE ? GREEN : YELLOW;
    }

    private String displayName(ProcessInstance inst) {
        Config.InstanceConfig cfg = inst.getConfig();
        return cfg.name == null || cfg.name.isEmpty() ? inst.getId() : cfg.name;
    }

    private String footer(String hint) {
        String text = message == null || message.isEmpty() ? hint : message;
        return row(text, message == null || message.isEmpty() ? GRAY : YELLOW + BOLD);
    }

    private String topBorder(String title) {
        int inner = innerWidth();
        String t = " " + title + " ";
        int rest = Math.max(0, inner - 2 - t.length());
        return CYAN + "+" + "--" + t + repeat('-', rest) + "+" + RESET;
    }

    private String bottomBorder() {
        return CYAN + "+" + repeat('-', innerWidth()) + "+" + RESET;
    }

    private String blank() {
        return row("", RESET);
    }

    private String row(String plain, String color) {
        int inner = innerWidth();
        String t = truncate(plain, inner);
        StringBuilder b = new StringBuilder();
        b.append(color).append('|').append(t);
        for (int i = t.length(); i < inner; i++) b.append(' ');
        b.append('|').append(RESET);
        return b.toString();
    }

    private int innerWidth() {
        return Math.max(20, cols - 4);
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) return s;
        StringBuilder b = new StringBuilder();
        s.codePoints().limit(max).forEach(b::appendCodePoint);
        return b.toString();
    }

    private static String repeat(char c, int count) {
        StringBuilder b = new StringBuilder(Math.max(0, count));
        for (int i = 0; i < count; i++) b.append(c);
        return b.toString();
    }

    // ==================== 输出与生命周期 ====================
    private void startWriter() {
        if (writerThread != null) return;
        writerThread = new Thread(this::writeLoop, "PM-Menu-Writer");
        writerThread.setDaemon(true);
        writerThread.start();
    }

    private void writeLoop() {
        OutputStream target = out;
        if (target == null) return;
        try {
            while (true) {
                byte[] data = outputQueue.take();
                if (data == EOF) break;
                target.write(data);
                target.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // 客户端断开，等待 destroy() 收尾
        } finally {
            writerDone.countDown();
            notifyExit();
        }
    }

    private boolean destroyed() {
        return closed.get() || finished.get();
    }

    private void enqueue(byte[] data) {
        if (outputQueue.offer(data)) return;
        outputQueue.poll();
        outputQueue.offer(data);
    }

    private void finish(int code, String goodbye) {
        if (!finished.compareAndSet(false, true)) return;
        closed.set(true);
        synchronized (stateLock) {
            detachLocked();
        }
        stopTimer();
        if (inputThread != null) inputThread.interrupt();
        exitCode = code;
        exitRequested = true;
        enqueue((SHOW_CURSOR + "\r\n" + goodbye + "\r\n").getBytes(StandardCharsets.UTF_8));
        enqueue(EOF);
        if (writerThread == null || writerDone.getCount() == 0L || !writerThread.isAlive()) {
            notifyExit();
        } else {
            startExitFallback();
        }
    }

    private void startExitFallback() {
        Thread fallback = new Thread(() -> {
            try {
                writerDone.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            notifyExit();
        }, "PM-Menu-Exit");
        fallback.setDaemon(true);
        fallback.start();
    }

    private void notifyExit() {
        if (!exitRequested) return;
        if (!exitReported.compareAndSet(false, true)) return;
        ExitCallback cb = callback;
        if (cb != null) {
            cb.onExit(exitCode);
        }
    }

    private void stopTimer() {
        ScheduledExecutorService timer = refreshTimer;
        refreshTimer = null;
        if (timer != null) timer.shutdownNow();
    }

    @Override
    public void destroy(ChannelSession channel) {
        closed.set(true);
        synchronized (stateLock) {
            detachLocked();
        }
        stopTimer();
        if (inputThread != null) inputThread.interrupt();
        if (finished.compareAndSet(false, true)) {
            enqueue(SHOW_CURSOR.getBytes(StandardCharsets.UTF_8));
            enqueue(EOF);
        }
    }

    private static int[] readSize(Environment env) {
        int c = 80;
        int r = 24;
        java.util.Map<String, String> e = env.getEnv();
        if (e != null) {
            c = parseInt(e.get(Environment.ENV_COLUMNS), c);
            r = parseInt(e.get(Environment.ENV_LINES), r);
        }
        return new int[]{c, r};
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isEmpty()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private ProcessInstance current() {
        return currentId == null ? null : InstanceManager.getInstance().getInstance(currentId);
    }
}
