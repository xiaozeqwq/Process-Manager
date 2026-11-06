package com.xiaoze_qwq_.processmanager.util;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class RingBuffer<T> {
    private Object[] buffer;  // 去掉 final
    private volatile int writePos = 0;
    private volatile int size = 0;
    private int capacity;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public RingBuffer(int capacity) {
        this.capacity = Math.max(1, capacity);
        this.buffer = new Object[this.capacity];
    }

    public void add(T item) {
        lock.writeLock().lock();
        try {
            buffer[writePos] = item;
            writePos = (writePos + 1) % capacity;
            if (size < capacity) size++;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<T> getLast(int count) {
        lock.readLock().lock();
        try {
            if (count <= 0 || size == 0) return new ArrayList<>();
            int actual = Math.min(count, size);
            List<T> result = new ArrayList<>(actual);
            int start = (writePos - size + capacity) % capacity;
            for (int i = 0; i < size; i++) {
                int idx = (start + i) % capacity;
                if (i >= size - actual) {
                    @SuppressWarnings("unchecked")
                    T item = (T) buffer[idx];
                    result.add(item);
                }
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    public int size() {
        return size;
    }

    public int getCapacity() {
        return capacity;
    }

    public void resize(int newCapacity) {
        lock.writeLock().lock();
        try {
            if (newCapacity == capacity) return;
            if (newCapacity <= 0) {
                newCapacity = 1;
            }
            Object[] newBuffer = new Object[newCapacity];
            int oldSize = size;
            int start = (writePos - oldSize + capacity) % capacity;
            int keep = Math.min(oldSize, newCapacity);
            int skip = oldSize - keep;
            for (int i = 0; i < keep; i++) {
                newBuffer[i] = buffer[(start + skip + i) % capacity];
            }
            this.buffer = newBuffer;
            this.capacity = newCapacity;
            this.size = keep;
            this.writePos = this.size % capacity;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void clear() {
        lock.writeLock().lock();
        try {
            for (int i = 0; i < capacity; i++) buffer[i] = null;
            writePos = 0;
            size = 0;
        } finally {
            lock.writeLock().unlock();
        }
    }
}