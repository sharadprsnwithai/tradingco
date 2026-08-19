package com.tradingbot.marketdata;

import com.tradingbot.model.Candle;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Fixed-capacity circular ring buffer for storing historical candles with zero memory growth.
 * Optimized for bounded heap environments (1 GB RAM VPS) with O(1) appends and lock-striped concurrency.
 */
public class CircularCandleBuffer {

    private final int capacity;
    private final Candle[] buffer;
    private int head = 0;
    private int size = 0;
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    /**
     * Constructs a circular candle buffer with the specified capacity.
     *
     * @param capacity the maximum number of candles the buffer can hold; must be positive
     * @throws IllegalArgumentException if capacity is not positive
     */
    public CircularCandleBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Buffer capacity must be positive, got: " + capacity);
        }
        this.capacity = capacity;
        this.buffer = new Candle[capacity];
    }

    /**
     * Constructs a circular candle buffer with the default capacity of 300 candles.
     */
    public CircularCandleBuffer() {
        this(300); // Default to 300 candles (e.g. 1 full trading day of 1m bars)
    }

    /**
     * Appends a new closed candle. Overwrites the oldest candle when capacity is reached.
     */
    public void add(Candle candle) {
        if (candle == null) return;
        rwLock.writeLock().lock();
        try {
            buffer[head] = candle;
            head = (head + 1) % capacity;
            if (size < capacity) {
                size++;
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Updates the most recent candle in-place (e.g., live intra-candle tick updates).
     */
    public void updateLast(Candle candle) {
        if (candle == null) return;
        rwLock.writeLock().lock();
        try {
            if (size == 0) {
                add(candle);
                return;
            }
            int lastIndex = (head - 1 + capacity) % capacity;
            buffer[lastIndex] = candle;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Retrieves the most recent candle.
     */
    public Optional<Candle> getLast() {
        rwLock.readLock().lock();
        try {
            if (size == 0) return Optional.empty();
            int lastIndex = (head - 1 + capacity) % capacity;
            return Optional.ofNullable(buffer[lastIndex]);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Returns all candles in chronological order (oldest to newest).
     */
    public List<Candle> getCandles() {
        rwLock.readLock().lock();
        try {
            if (size == 0) return Collections.emptyList();
            List<Candle> list = new ArrayList<>(size);
            int start = (size < capacity) ? 0 : head;
            for (int i = 0; i < size; i++) {
                int index = (start + i) % capacity;
                if (buffer[index] != null) {
                    list.add(buffer[index]);
                }
            }
            return list;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Returns the last N candles in chronological order.
     */
    public List<Candle> getLast(int count) {
        rwLock.readLock().lock();
        try {
            if (size == 0 || count <= 0) return Collections.emptyList();
            int actualCount = Math.min(count, size);
            List<Candle> list = new ArrayList<>(actualCount);
            int startOffset = size - actualCount;
            int start = ((size < capacity ? 0 : head) + startOffset) % capacity;
            for (int i = 0; i < actualCount; i++) {
                int index = (start + i) % capacity;
                if (buffer[index] != null) {
                    list.add(buffer[index]);
                }
            }
            return list;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Returns primitive array of close prices for low-allocation indicator calculations.
     */
    public double[] getClosePrices() {
        rwLock.readLock().lock();
        try {
            double[] arr = new double[size];
            int start = (size < capacity) ? 0 : head;
            for (int i = 0; i < size; i++) {
                int index = (start + i) % capacity;
                arr[i] = buffer[index] != null && buffer[index].close() != null ? buffer[index].close().doubleValue() : 0.0;
            }
            return arr;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Returns the number of candles currently stored in the buffer.
     *
     * @return the current count of candles (0 to capacity)
     */
    public int size() {
        rwLock.readLock().lock();
        try {
            return size;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Returns the maximum number of candles this buffer can hold.
     *
     * @return the buffer capacity
     */
    public int capacity() {
        return capacity;
    }

    /**
     * Returns whether the buffer contains no candles.
     *
     * @return {@code true} if the buffer is empty, {@code false} otherwise
     */
    public boolean isEmpty() {
        return size() == 0;
    }

    /**
     * Removes all candles from the buffer and resets the head and size to zero.
     */
    public void clear() {
        rwLock.writeLock().lock();
        try {
            for (int i = 0; i < capacity; i++) {
                buffer[i] = null;
            }
            head = 0;
            size = 0;
        } finally {
            rwLock.writeLock().unlock();
        }
    }
}
