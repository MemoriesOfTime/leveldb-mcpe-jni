/*
 * Adapted from The MIT License (MIT)
 *
 * Copyright (c) 2020-2020 DaPorkchop_
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy,
 * modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software
 * is furnished to do so, subject to the following conditions:
 *
 * Any persons and/or organizations using this software must include the above copyright notice and this permission notice,
 * provide sufficient credit to the original authors of the project (IE: DaPorkchop_), as well as provide a link to the original project.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS
 * BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 */

package net.daporkchop.ldbjni.natives;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import net.daporkchop.ldbjni.direct.DirectWriteBatch;

import java.io.IOException;
import java.lang.ref.Cleaner;
import java.util.concurrent.atomic.AtomicLong;

import static net.daporkchop.lib.common.util.PValidation.*;

/**
 * @author DaPorkchop_
 */
@RequiredArgsConstructor
final class NativeWriteBatch implements DirectWriteBatch {
    private static final int HEADER_SIZE = 12;

    final AtomicLong ptr;

    private final NativeDB db;
    private final Cleaner.Cleanable cleanable;

    private int approximateSize;
    private int size;

    public NativeWriteBatch(long ptr, @NonNull NativeDB db) {
        this.ptr = new AtomicLong(ptr);
        this.db = db;
        this.cleanable = NativeDB.CLEANER.register(this, new Releaser(this.ptr, this.db));
    }

    @Override
    public synchronized int getApproximateSize() {
        this.ptr();
        return this.approximateSize;
    }

    @Override
    public synchronized int size() {
        this.ptr();
        return this.size;
    }

    private long ptr() {
        long ptr = this.ptr.get();
        checkState(ptr != 0L, "NativeWriteBatch has already been closed!");
        return ptr;
    }

    private void countPut(int keyLength, int valueLength) {
        this.size++;
        this.approximateSize += HEADER_SIZE + keyLength + valueLength;
    }

    private void countDelete(int keyLength) {
        this.size++;
        this.approximateSize += 6 + keyLength;
    }

    @Override
    public synchronized DirectWriteBatch put(@NonNull byte[] key, @NonNull byte[] value) {
        this.put0HH(this.ptr(), key, 0, key.length, value, 0, value.length);
        this.countPut(key.length, value.length);
        return this;
    }

    @Override
    public synchronized DirectWriteBatch put(@NonNull ByteBuf key, @NonNull ByteBuf value) {
        int keyLength = key.readableBytes();
        int valueLength = value.readableBytes();
        if (key.hasArray()) {
            if (value.hasArray()) {
                this.put0HH(
                        this.ptr(),
                        key.array(), key.arrayOffset() + key.readerIndex(), keyLength,
                        value.array(), value.arrayOffset() + value.readerIndex(), valueLength);
                this.countPut(keyLength, valueLength);
                return this;
            } else if (value.hasMemoryAddress()) {
                this.put0HD(
                        this.ptr(),
                        key.array(), key.arrayOffset() + key.readerIndex(), keyLength,
                        value.memoryAddress() + value.readerIndex(), valueLength);
                this.countPut(keyLength, valueLength);
                return this;
            }
        } else if (key.hasMemoryAddress())    {
            if (value.hasArray()) {
                this.put0DH(
                        this.ptr(),
                        key.memoryAddress() + key.readerIndex(), keyLength,
                        value.array(), value.arrayOffset() + value.readerIndex(), valueLength);
                this.countPut(keyLength, valueLength);
                return this;
            } else if (value.hasMemoryAddress()) {
                this.put0DD(
                        this.ptr(),
                        key.memoryAddress() + key.readerIndex(), keyLength,
                        value.memoryAddress() + value.readerIndex(), valueLength);
                this.countPut(keyLength, valueLength);
                return this;
            }
        }
        if (!key.hasArray() && !key.hasMemoryAddress()) {
            ByteBuf keyCopy = ByteBufAllocator.DEFAULT.buffer(keyLength, keyLength);
            try {
                checkState(keyCopy.hasArray() || keyCopy.hasMemoryAddress(), keyCopy);
                key.getBytes(key.readerIndex(), keyCopy);
                this.put(keyCopy, value);
            } finally {
                keyCopy.release();
            }
        } else if (!value.hasArray() && !value.hasMemoryAddress()) {
            ByteBuf valueCopy = ByteBufAllocator.DEFAULT.buffer(valueLength, valueLength);
            try {
                checkState(valueCopy.hasArray() || valueCopy.hasMemoryAddress(), valueCopy);
                value.getBytes(value.readerIndex(), valueCopy);
                this.put(key, valueCopy);
            } finally {
                valueCopy.release();
            }
        } else {
            throw new IllegalArgumentException(key + " " + value);
        }
        return this;
    }

    private native void put0HH(long ptr, byte[] key, int keyOff, int keyLen, byte[] val, int valOff, int valLen);

    private native void put0HD(long ptr, byte[] key, int keyOff, int keyLen, long valAddr, int valLen);

    private native void put0DH(long ptr, long keyAddr, int keyLen, byte[] val, int valOff, int valLen);

    private native void put0DD(long ptr, long keyAddr, int keyLen, long valAddr, int valLen);

    @Override
    public synchronized DirectWriteBatch delete(@NonNull byte[] key) {
        this.delete0H(this.ptr(), key, 0, key.length);
        this.countDelete(key.length);
        return this;
    }

    @Override
    public synchronized DirectWriteBatch delete(@NonNull ByteBuf key) {
        int keyLength = key.readableBytes();
        if (key.hasArray()) {
            this.delete0H(
                    this.ptr(),
                    key.array(), key.arrayOffset() + key.readerIndex(), keyLength);
            this.countDelete(keyLength);
        } else if (key.hasMemoryAddress()) {
            this.delete0D(
                    this.ptr(),
                    key.memoryAddress() + key.readerIndex(), keyLength);
            this.countDelete(keyLength);
        } else {
            ByteBuf keyCopy = ByteBufAllocator.DEFAULT.buffer(keyLength, keyLength);
            try {
                checkState(keyCopy.hasArray() || keyCopy.hasMemoryAddress(), keyCopy);
                key.getBytes(key.readerIndex(), keyCopy);
                this.delete(keyCopy);
            } finally {
                keyCopy.release();
            }
        }
        return this;
    }

    private native void delete0H(long ptr, byte[] key, int keyOff, int keyLen);

    private native void delete0D(long ptr, long keyAddr, int keyLen);

    @Override
    public synchronized void close() throws IOException {
        this.cleanable.clean();
    }

    @RequiredArgsConstructor
    private static final class Releaser implements Runnable {
        @NonNull
        private final AtomicLong     ptr;
        @NonNull
        private final NativeDB db;

        @Override
        public void run() {
            this.db.releaseWriteBatch0(this.ptr.getAndSet(0L));
        }
    }
}
