package com.xiaoze_qwq_.processmanager.ssh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MenuKeyDecoderTest {

    private static MenuKeyDecoder.Key feedAll(MenuKeyDecoder decoder, int... bytes) {
        MenuKeyDecoder.Key last = null;
        for (int b : bytes) {
            MenuKeyDecoder.Key key = decoder.feed((byte) b);
            if (key != null) last = key;
        }
        MenuKeyDecoder.Key pending = decoder.flushPending();
        return pending != null ? pending : last;
    }

    @Test
    void arrowKeysViaCsi() {
        MenuKeyDecoder d = new MenuKeyDecoder();
        assertEquals(MenuKeyDecoder.Key.UP, feedAll(d, 0x1b, '[', 'A'));
        assertEquals(MenuKeyDecoder.Key.DOWN, feedAll(d, 0x1b, '[', 'B'));
        assertEquals(MenuKeyDecoder.Key.RIGHT, feedAll(d, 0x1b, '[', 'C'));
        assertEquals(MenuKeyDecoder.Key.LEFT, feedAll(d, 0x1b, '[', 'D'));
    }

    @Test
    void arrowKeysViaSs3() {
        MenuKeyDecoder d = new MenuKeyDecoder();
        assertEquals(MenuKeyDecoder.Key.UP, feedAll(d, 0x1b, 'O', 'A'));
        assertEquals(MenuKeyDecoder.Key.DOWN, feedAll(d, 0x1b, 'O', 'B'));
    }

    @Test
    void csiWithParametersIsIgnored() {
        MenuKeyDecoder d = new MenuKeyDecoder();
        assertEquals(MenuKeyDecoder.Key.IGNORED, feedAll(d, 0x1b, '[', '2', '0', '~'));
    }

    @Test
    void loneEscFlushesAtBlockEnd() {
        MenuKeyDecoder d = new MenuKeyDecoder();
        assertNull(d.feed((byte) 0x1b));
        assertEquals(MenuKeyDecoder.Key.ESC, d.flushPending());
    }

    @Test
    void enterVariants() {
        MenuKeyDecoder d = new MenuKeyDecoder();
        assertEquals(MenuKeyDecoder.Key.ENTER, feedAll(d, 0x0d));
        assertEquals(MenuKeyDecoder.Key.ENTER, feedAll(d, 0x0a));
    }

    @Test
    void backspaceVariants() {
        MenuKeyDecoder d = new MenuKeyDecoder();
        assertEquals(MenuKeyDecoder.Key.BACKSPACE, feedAll(d, 0x08));
        assertEquals(MenuKeyDecoder.Key.BACKSPACE, feedAll(d, 0x7f));
    }

    @Test
    void controlKeys() {
        MenuKeyDecoder d = new MenuKeyDecoder();
        assertEquals(MenuKeyDecoder.Key.CTRL_A, feedAll(d, 0x01));
        assertEquals(MenuKeyDecoder.Key.CTRL_C, feedAll(d, 0x03));
    }

    @Test
    void printableCharacters() {
        MenuKeyDecoder d = new MenuKeyDecoder();
        assertEquals(MenuKeyDecoder.Key.CHAR, feedAll(d, 'a'));
        assertEquals("a", d.getChar());
        assertEquals(MenuKeyDecoder.Key.CHAR, feedAll(d, 'Z'));
        assertEquals("Z", d.getChar());
    }

    @Test
    void utf8TwoByteCharacter() {
        MenuKeyDecoder d = new MenuKeyDecoder();
        // "中" = E4 B8 AD
        assertNull(d.feed((byte) 0xe4));
        assertNull(d.feed((byte) 0xb8));
        MenuKeyDecoder.Key key = d.feed((byte) 0xad);
        assertEquals(MenuKeyDecoder.Key.CHAR, key);
        assertEquals("中", d.getChar());
    }

    @Test
    void utf8FourByteEmoji() {
        MenuKeyDecoder d = new MenuKeyDecoder();
        // U+1F600 = F0 9F 98 80
        assertNull(d.feed((byte) 0xf0));
        assertNull(d.feed((byte) 0x9f));
        assertNull(d.feed((byte) 0x98));
        assertEquals(MenuKeyDecoder.Key.CHAR, d.feed((byte) 0x80));
        assertEquals("\uD83D\uDE00", d.getChar());
    }

    @Test
    void invalidUtf8SequenceRecovers() {
        MenuKeyDecoder d = new MenuKeyDecoder();
        assertNull(d.feed((byte) 0xe4));
        // 非法续字节后应恢复普通解析，当前字节按普通字符处理
        MenuKeyDecoder.Key key = d.feed((byte) 'x');
        assertEquals(MenuKeyDecoder.Key.CHAR, key);
        assertEquals("x", d.getChar());
    }

    @Test
    void otherControlBytesIgnored() {
        MenuKeyDecoder d = new MenuKeyDecoder();
        assertEquals(MenuKeyDecoder.Key.IGNORED, feedAll(d, 0x00));
        assertEquals(MenuKeyDecoder.Key.IGNORED, feedAll(d, 0x1a));
    }
}
