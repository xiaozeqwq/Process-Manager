package com.xiaoze_qwq_.processmanager.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RingBufferTest {

    @Test
    void addKeepsOnlyLatestWhenFull() {
        RingBuffer<Integer> buffer = new RingBuffer<>(3);
        for (int i = 1; i <= 5; i++) buffer.add(i);
        assertEquals(List.of(3, 4, 5), buffer.getLast(3));
        assertEquals(3, buffer.size());
    }

    @Test
    void getLastReturnsTailInOrder() {
        RingBuffer<Integer> buffer = new RingBuffer<>(5);
        for (int i = 0; i < 4; i++) buffer.add(i);
        assertEquals(List.of(2, 3), buffer.getLast(2));
    }

    @Test
    void getLastHandlesZeroAndEmpty() {
        RingBuffer<Integer> buffer = new RingBuffer<>(2);
        assertTrue(buffer.getLast(10).isEmpty());
        buffer.add(1);
        assertTrue(buffer.getLast(0).isEmpty());
    }

    @Test
    void resizeShrinkKeepsNewestEntries() {
        RingBuffer<Integer> buffer = new RingBuffer<>(10);
        for (int i = 0; i < 10; i++) buffer.add(i);
        buffer.resize(3);
        assertEquals(3, buffer.size());
        assertEquals(List.of(7, 8, 9), buffer.getLast(3));
    }

    @Test
    void resizeGrowKeepsExistingEntries() {
        RingBuffer<Integer> buffer = new RingBuffer<>(2);
        buffer.add(1);
        buffer.add(2);
        buffer.add(3);
        buffer.resize(5);
        assertEquals(2, buffer.size());
        assertEquals(List.of(2, 3), buffer.getLast(5));
        buffer.add(4);
        assertEquals(List.of(2, 3, 4), buffer.getLast(5));
    }

    @Test
    void resizeWithSmallerCapacityThanSizeDoesNotThrow() {
        RingBuffer<Integer> buffer = new RingBuffer<>(6);
        for (int i = 0; i < 6; i++) buffer.add(i);
        buffer.resize(1);
        assertEquals(List.of(5), buffer.getLast(1));
    }

    @Test
    void resizeToZeroFallsBackToSingleSlot() {
        RingBuffer<Integer> buffer = new RingBuffer<>(4);
        buffer.add(1);
        buffer.resize(0);
        buffer.add(2);
        assertEquals(List.of(2), buffer.getLast(4));
    }

    @Test
    void clearRemovesAllEntries() {
        RingBuffer<Integer> buffer = new RingBuffer<>(4);
        buffer.add(1);
        buffer.add(2);
        buffer.clear();
        assertEquals(0, buffer.size());
        assertTrue(buffer.getLast(4).isEmpty());
    }
}
