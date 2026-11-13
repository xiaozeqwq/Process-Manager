package com.xiaoze_qwq_.processmanager.ssh;

import java.nio.charset.StandardCharsets;

/**
 * SSH TUI 菜单按键解码器：把原始输入字节流解码为按键事件。
 * 支持方向键（CSI ESC [ A 与 SS3 ESC O A）、回车、退格、Ctrl-A 前缀键、
 * Ctrl-C 与 UTF-8 多字节字符。按块读取后逐字节 feed，块尾调用
 * {@link #flushPending()} 把单独按下的 ESC 冲刷为事件。
 */
public final class MenuKeyDecoder {
    public enum Key { UP, DOWN, LEFT, RIGHT, ENTER, ESC, BACKSPACE, CTRL_A, CTRL_C, CHAR, IGNORED }

    private enum State { IDLE, ESC, CSI, SS3, UTF8 }

    private State state = State.IDLE;
    private final byte[] utf8 = new byte[4];
    private int utf8Len = 0;
    private int utf8Need = 0;
    private String lastChar = "";

    /** 喂入多个字节（块）；返回最后一个非 null 事件，若无事件返回 null。 */
    public static Key feedAll(MenuKeyDecoder decoder, byte... bytes) {
        Key last = null;
        for (byte b : bytes) {
            Key key = decoder.feed(b);
            if (key != null) last = key;
        }
        Key pending = decoder.flushPending();
        return pending != null ? pending : last;
    }

    /** 喂入一个字节；返回 null 表示序列未完成或无事件。 */
    public Key feed(byte b) {
        switch (state) {
            case ESC: return feedAfterEsc(b);
            case CSI: return feedCsi(b);
            case SS3: return feedSs3(b);
            case UTF8: return feedUtf8(b);
            default: return feedIdle(b);
        }
    }

    /** 块尾冲刷：单独按下的 ESC 作为事件返回，残缺的转义序列与 UTF-8 片段直接丢弃。 */
    public Key flushPending() {
        State was = state;
        state = State.IDLE;
        utf8Len = 0;
        utf8Need = 0;
        return was == State.ESC ? Key.ESC : null;
    }

    /** 丢弃全部中间状态。 */
    public void reset() {
        flushPending();
        lastChar = "";
    }

    /** 最近一次 {@link Key#CHAR} 事件对应的文本（UTF-8 解码后，可能含代理对）。 */
    public String getChar() {
        return lastChar;
    }

    private Key feedIdle(byte b) {
        int v = b & 0xff;
        switch (v) {
            case 0x1b:
                state = State.ESC;
                return null;
            case 0x0d:
            case 0x0a:
                return Key.ENTER;
            case 0x08:
            case 0x7f:
                return Key.BACKSPACE;
            case 0x01:
                return Key.CTRL_A;
            case 0x03:
                return Key.CTRL_C;
            default:
                break;
        }
        if (v < 0x20) return Key.IGNORED;
        return feedChar(b, v);
    }

    private Key feedChar(byte b, int v) {
        if (v < 0x80) {
            lastChar = String.valueOf((char) v);
            return Key.CHAR;
        }
        int need;
        if (v >= 0xc0 && v <= 0xdf) need = 1;
        else if (v >= 0xe0 && v <= 0xef) need = 2;
        else if (v >= 0xf0 && v <= 0xf7) need = 3;
        else return Key.IGNORED;
        utf8[0] = b;
        utf8Len = 1;
        utf8Need = need;
        state = State.UTF8;
        return null;
    }

    private Key feedUtf8(byte b) {
        if ((b & 0xc0) != 0x80) {
            utf8Len = 0;
            utf8Need = 0;
            state = State.IDLE;
            return feedIdle(b);
        }
        utf8[utf8Len++] = b;
        if (utf8Len <= utf8Need) return null;
        lastChar = new String(utf8, 0, utf8Len, StandardCharsets.UTF_8);
        utf8Len = 0;
        utf8Need = 0;
        state = State.IDLE;
        return Key.CHAR;
    }

    private Key feedAfterEsc(byte b) {
        int v = b & 0xff;
        if (v == '[') {
            state = State.CSI;
            return null;
        }
        if (v == 'O') {
            state = State.SS3;
            return null;
        }
        state = v == 0x1b ? State.ESC : State.IDLE;
        return Key.ESC;
    }

    private Key feedCsi(byte b) {
        int v = b & 0xff;
        if (v >= 'A' && v <= 'D') {
            state = State.IDLE;
            switch (v) {
                case 'A': return Key.UP;
                case 'B': return Key.DOWN;
                case 'C': return Key.RIGHT;
                default: return Key.LEFT;
            }
        }
        if (v >= 0x40 && v <= 0x7e) {
            state = State.IDLE;
            return Key.IGNORED;
        }
        return null;
    }

    private Key feedSs3(byte b) {
        int v = b & 0xff;
        state = State.IDLE;
        if (v >= 'A' && v <= 'D') {
            switch (v) {
                case 'A': return Key.UP;
                case 'B': return Key.DOWN;
                case 'C': return Key.RIGHT;
                default: return Key.LEFT;
            }
        }
        return Key.IGNORED;
    }
}
