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
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.buffer.UnpooledUnsafeDirectByteBuf;
import io.netty.util.internal.PlatformDependent;
import lombok.NonNull;
import net.daporkchop.ldbjni.direct.BufType;
import net.daporkchop.ldbjni.direct.DirectDB;
import net.daporkchop.ldbjni.direct.DirectReadOptions;
import net.daporkchop.ldbjni.direct.DirectWriteBatch;
import net.daporkchop.lib.common.system.PlatformInfo;
import net.daporkchop.lib.unsafe.PCleaner;
import net.daporkchop.lib.unsafe.PUnsafe;
import org.iq80.leveldb.DBException;
import org.iq80.leveldb.DBIterator;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.Range;
import org.iq80.leveldb.ReadOptions;
import org.iq80.leveldb.Snapshot;
import org.iq80.leveldb.WriteBatch;
import org.iq80.leveldb.WriteOptions;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static net.daporkchop.lib.common.util.PValidation.*;

/**
 * @author DaPorkchop_
 */
final class NativeDB implements DirectDB {
    private static final ReadOptions DEFAULT_READ_OPTIONS = new ReadOptions();
    private static final WriteOptions DEFAULT_WRITE_OPTIONS = new WriteOptions();

    private static final long CLEANER_OFFSET = PUnsafe.pork_getOffset(ByteBuffer.allocateDirect(0).getClass(), "cleaner");

    static {
        init();
    }

    private static native void init();

    private static native long openDb(String name,
                                      boolean create_if_missing, boolean error_if_exists, boolean paranoid_checks, int write_buffer_size,
                                      int max_open_files, int block_size, int block_restart_interval, int max_file_size, int compression, long cacheSize);

    private static native long createDecompressAllocator();

    private static native void closeDb(long db, long dca);

    private static native void deleteString(long string);

    private long db;
    private long dca;
    private final PCleaner cleaner;
    private final Set<NativeResource> resources = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private final Lock readLock;
    private final Lock writeLock;

    public NativeDB(@NonNull File path, @NonNull Options options) {
        if (options.comparator() != null) {
            throw new UnsupportedOperationException("comparator");
        } else if (options.compressionType() == null) {
            throw new NullPointerException("compressionType");
        }

        this.db = openDb(
                path.getAbsoluteFile().getAbsolutePath(),
                options.createIfMissing(),
                options.errorIfExists(),
                options.paranoidChecks(),
                options.writeBufferSize(),
                options.maxOpenFiles(),
                options.blockSize(),
                options.blockRestartInterval(),
                -1, //not present in Options...
                options.compressionType().persistentId(),
                options.cacheSize());
        this.dca = createDecompressAllocator();

        this.cleaner = PCleaner.cleaner(this, new Releaser(this.db, this.dca));

        ReadWriteLock lock = new ReentrantReadWriteLock();
        this.readLock = lock.readLock();
        this.writeLock = lock.writeLock();
    }

    @Override
    public byte[] get(@NonNull byte[] key) throws DBException {
        return this.get(key, DEFAULT_READ_OPTIONS);
    }

    @Override
    public byte[] get(@NonNull byte[] key, @NonNull ReadOptions options) throws DBException {
        this.readLock.lock();
        try {
            this.assertOpen();
            return this.get0(key, options.verifyChecksums(), options.fillCache(), snapshotPtr(options));
        } finally {
            this.readLock.unlock();
        }
    }

    private native byte[] get0(byte[] key, boolean verifyChecksums, boolean fillCache, long snapshot);

    @Override
    public void put(@NonNull byte[] key, @NonNull byte[] value) throws DBException {
        this.put(key, value, DEFAULT_WRITE_OPTIONS);
    }

    @Override
    public Snapshot put(@NonNull byte[] key, @NonNull byte[] value, @NonNull WriteOptions options) throws DBException {
        Lock lock = options.snapshot() ? this.writeLock : this.readLock;
        lock.lock();
        try {
            this.assertOpen();
            this.put0HH(key, 0, key.length, value, 0, value.length, options.sync());
            return options.snapshot() ? this.snapshot0() : null;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void delete(@NonNull byte[] key) throws DBException {
        this.delete(key, DEFAULT_WRITE_OPTIONS);
    }

    @Override
    public Snapshot delete(@NonNull byte[] key, @NonNull WriteOptions options) throws DBException {
        Lock lock = options.snapshot() ? this.writeLock : this.readLock;
        lock.lock();
        try {
            this.assertOpen();
            this.delete0H(key, 0, key.length, options.sync());
            return options.snapshot() ? this.snapshot0() : null;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public DirectWriteBatch createWriteBatch() {
        return new NativeWriteBatch(this.createWriteBatch0(), this);
    }

    private native long createWriteBatch0();

    native void releaseWriteBatch0(long ptr);

    @Override
    public void write(@NonNull WriteBatch writeBatch) throws DBException {
        this.write(writeBatch, DEFAULT_WRITE_OPTIONS);
    }

    @Override
    public Snapshot write(@NonNull WriteBatch writeBatch, @NonNull WriteOptions options) throws DBException {
        if (!(writeBatch instanceof NativeWriteBatch)) {
            throw new IllegalArgumentException(writeBatch.getClass().getCanonicalName());
        }

        Lock lock = options.snapshot() ? this.writeLock : this.readLock;
        lock.lock();
        try {
            this.assertOpen();
            synchronized (writeBatch) {
                this.writeBatch0(((NativeWriteBatch) writeBatch).ptr.get(), options.sync());
            }
            return options.snapshot() ? this.snapshot0() : null;
        } finally {
            lock.unlock();
        }
    }

    private native void writeBatch0(long writeBatch, boolean sync);

    @Override
    public DBIterator iterator() {
        return this.iterator(DEFAULT_READ_OPTIONS);
    }

    @Override
    public DBIterator iterator(@NonNull ReadOptions options) {
        this.readLock.lock();
        try {
            this.assertOpen();
            return new NativeIterator(this, this.iterator0(options.verifyChecksums(), options.fillCache(), snapshotPtr(options)));
        } finally {
            this.readLock.unlock();
        }
    }

    private native long iterator0(boolean verifyChecksums, boolean fillCache, long snapshot);

    private static native void releaseIterator0(long iterator);

    private static native void iteratorSeekToFirst0(long iterator);

    private static native void iteratorSeekToLast0(long iterator);

    private static native void iteratorSeek0(long iterator, byte[] target);

    private static native boolean iteratorValid0(long iterator);

    private static native void iteratorNext0(long iterator);

    private static native void iteratorPrev0(long iterator);

    private static native byte[] iteratorKey0(long iterator);

    private static native byte[] iteratorValue0(long iterator);

    private static native void checkIteratorStatus0(long iterator);

    @Override
    public Snapshot getSnapshot() {
        this.readLock.lock();
        try {
            this.assertOpen();
            return this.snapshot0();
        } finally {
            this.readLock.unlock();
        }
    }

    private NativeSnapshot snapshot0() {
        return new NativeSnapshot(this.getSnapshot0(), this);
    }

    private native long getSnapshot0();

    private native void releaseSnapshot0(long snapshot);

    @Override
    public long[] getApproximateSizes(@NonNull Range... ranges) {
        this.readLock.lock();
        try {
            this.assertOpen();

            long[] sizes = new long[ranges.length];
            for (int i = 0; i < ranges.length; i++) {
                Range range = ranges[i];
                if (range == null) {
                    throw new NullPointerException("ranges[" + i + "]");
                } else if (range.start() == null) {
                    throw new NullPointerException("ranges[" + i + "].start");
                } else if (range.limit() == null) {
                    throw new NullPointerException("ranges[" + i + "].limit");
                }

                sizes[i] = this.getApproximateSize0(range.start(), range.limit());
            }
            return sizes;
        } finally {
            this.readLock.unlock();
        }
    }

    private native long getApproximateSize0(byte[] start, byte[] limit);

    @Override
    public String getProperty(@NonNull String name) {
        this.readLock.lock();
        try {
            this.assertOpen();
            return this.getProperty0(name);
        } finally {
            this.readLock.unlock();
        }
    }

    private native String getProperty0(String name);

    @Override
    public void suspendCompactions() throws InterruptedException {
        throw new UnsupportedOperationException("suspendCompactions");
    }

    @Override
    public void resumeCompactions() {
        throw new UnsupportedOperationException("resumeCompactions");
    }

    @Override
    public void compactRange(byte[] start, byte[] limit) throws DBException {
        this.readLock.lock();
        try {
            this.assertOpen();
            this.compactRange0(start, limit);
        } finally {
            this.readLock.unlock();
        }
    }

    private native void compactRange0(byte[] start, byte[] limit);

    @Override
    public void close() throws IOException {
        this.writeLock.lock();
        try {
            if (this.db != 0L) {
                this.closeResources();
                this.cleaner.clean();
                this.db = this.dca = 0L;
            }
        } finally {
            this.writeLock.unlock();
        }
    }

    private void registerResource(@NonNull NativeResource resource) {
        this.resources.add(resource);
    }

    private void unregisterResource(@NonNull NativeResource resource) {
        this.resources.remove(resource);
    }

    private void closeResources() {
        NativeResource[] resources;
        synchronized (this.resources) {
            resources = this.resources.toArray(new NativeResource[0]);
            this.resources.clear();
        }
        for (NativeResource resource : resources) {
            resource.closeFromDB();
        }
    }

    private static long snapshotPtr(@NonNull ReadOptions options) {
        Snapshot snapshot = options.snapshot();
        if (snapshot == null) {
            return 0L;
        } else if (!(snapshot instanceof NativeSnapshot)) {
            throw new IllegalArgumentException(snapshot.getClass().getCanonicalName());
        }
        return ((NativeSnapshot) snapshot).ptr();
    }

    private void releaseIterator(@NonNull AtomicLong iterator) {
        this.readLock.lock();
        try {
            if (this.db != 0L) {
                this.releaseIteratorFromDB(iterator);
            }
        } finally {
            this.readLock.unlock();
        }
    }

    private void releaseIteratorFromDB(@NonNull AtomicLong iterator) {
        long ptr = iterator.getAndSet(0L);
        if (ptr != 0L) {
            releaseIterator0(ptr);
        }
    }

    private void releaseSnapshot(@NonNull AtomicLong snapshot) {
        this.readLock.lock();
        try {
            if (this.db != 0L) {
                this.releaseSnapshotFromDB(snapshot);
            }
        } finally {
            this.readLock.unlock();
        }
    }

    private void releaseSnapshotFromDB(@NonNull AtomicLong snapshot) {
        long ptr = snapshot.getAndSet(0L);
        if (ptr != 0L) {
            this.releaseSnapshot0(ptr);
        }
    }

    //
    // direct ByteBuf methods
    //

    private ByteBufAllocator selectAlloc(@NonNull ReadOptions options) {
        ByteBufAllocator alloc = options instanceof DirectReadOptions ? ((DirectReadOptions) options).alloc() : null;
        return alloc == null ? ByteBufAllocator.DEFAULT : alloc;
    }

    private BufType selectType(@NonNull ReadOptions options) {
        BufType type = options instanceof DirectReadOptions ? ((DirectReadOptions) options).type() : null;
        return type == null ? BufType.DEFAULT : type;
    }

    @Override
    public ByteBuf get(@NonNull ByteBuf key) throws DBException {
        return this.get(key, DEFAULT_READ_OPTIONS);
    }

    @Override
    public ByteBuf get(@NonNull ByteBuf key, @NonNull ReadOptions options) throws DBException {
        this.readLock.lock();
        try {
            this.assertOpen();
            long snapshot = snapshotPtr(options);
            if (key.hasArray()) {
                return this.get0H(
                        key.array(), key.arrayOffset() + key.readerIndex(), key.readableBytes(),
                        options.verifyChecksums(), options.fillCache(), snapshot, this.selectAlloc(options), this.selectType(options));
            } else if (key.hasMemoryAddress()) {
                return this.get0D(
                        key.memoryAddress() + key.readerIndex(), key.readableBytes(),
                        options.verifyChecksums(), options.fillCache(), snapshot, this.selectAlloc(options), this.selectType(options));
            } else {
                ByteBuf keyCopy = this.selectAlloc(options).ioBuffer(key.readableBytes(), key.readableBytes());
                try {
                    checkState(keyCopy.hasArray() || keyCopy.hasMemoryAddress(), keyCopy);
                    key.getBytes(key.readerIndex(), keyCopy);
                    return this.get(keyCopy, options);
                } finally {
                    keyCopy.release();
                }
            }
        } finally {
            this.readLock.unlock();
        }
    }

    private native ByteBuf get0H(byte[] key, int keyOff, int keyLen, boolean verifyChecksums, boolean fillCache, long snapshot, ByteBufAllocator alloc, BufType type);

    private native ByteBuf get0D(long keyAddr, int keyLen, boolean verifyChecksums, boolean fillCache, long snapshot, ByteBufAllocator alloc, BufType type);

    private ByteBuf get0_final(byte[] value, @NonNull ByteBufAllocator alloc, @NonNull BufType type) {
        return type.allocate(alloc, value.length).writeBytes(value);
    }

    @Override
    public boolean getInto(@NonNull ByteBuf key, @NonNull ByteBuf dst) throws DBException {
        return this.getInto(key, dst, DEFAULT_READ_OPTIONS);
    }

    @Override
    public boolean getInto(@NonNull ByteBuf key, @NonNull ByteBuf dst, @NonNull ReadOptions options) throws DBException {
        this.readLock.lock();
        try {
            this.assertOpen();
            long snapshot = snapshotPtr(options);
            if (key.hasArray()) {
                return this.getInto0H(
                        key.array(), key.arrayOffset() + key.readerIndex(), key.readableBytes(),
                        options.verifyChecksums(), options.fillCache(), snapshot, dst);
            } else if (key.hasMemoryAddress()) {
                return this.getInto0D(
                        key.memoryAddress() + key.readerIndex(), key.readableBytes(),
                        options.verifyChecksums(), options.fillCache(), snapshot, dst);
            } else {
                ByteBuf keyCopy = this.selectAlloc(options).ioBuffer(key.readableBytes(), key.readableBytes());
                try {
                    checkState(keyCopy.hasArray() || keyCopy.hasMemoryAddress(), keyCopy);
                    key.getBytes(key.readerIndex(), keyCopy);
                    return this.getInto(keyCopy, dst, options);
                } finally {
                    keyCopy.release();
                }
            }
        } finally {
            this.readLock.unlock();
        }
    }

    private native boolean getInto0H(byte[] key, int keyOff, int keyLen, boolean verifyChecksums, boolean fillCache, long snapshot, ByteBuf dst);

    private native boolean getInto0D(long keyAddr, int keyLen, boolean verifyChecksums, boolean fillCache, long snapshot, ByteBuf dst);

    private void getInto0_final(byte[] value, @NonNull ByteBuf dst) {
        dst.writeBytes(value);
    }

    @Override
    public ByteBuf getZeroCopy(@NonNull ByteBuf key) throws DBException {
        return this.getZeroCopy(key, DEFAULT_READ_OPTIONS);
    }

    @Override
    public ByteBuf getZeroCopy(@NonNull ByteBuf key, @NonNull ReadOptions options) throws DBException {
        //jdk9+限制了反射，暂时调用get方法
        if (PlatformInfo.JAVA_VERSION > 8) {
            return get(key, options);
        }

        this.readLock.lock();
        try {
            this.assertOpen();
            long snapshot = snapshotPtr(options);
            if (key.hasArray()) {
                return this.getZeroCopy0H(
                        key.array(), key.arrayOffset() + key.readerIndex(), key.readableBytes(),
                        options.verifyChecksums(), options.fillCache(), snapshot);
            } else if (key.hasMemoryAddress()) {
                return this.getZeroCopy0D(
                        key.memoryAddress() + key.readerIndex(), key.readableBytes(),
                        options.verifyChecksums(), options.fillCache(), snapshot);
            } else {
                ByteBuf keyCopy = this.selectAlloc(options).ioBuffer(key.readableBytes(), key.readableBytes());
                try {
                    checkState(keyCopy.hasArray() || keyCopy.hasMemoryAddress(), keyCopy);
                    key.getBytes(key.readerIndex(), keyCopy);
                    return this.getZeroCopy(keyCopy, options);
                } finally {
                    keyCopy.release();
                }
            }
        } finally {
            this.readLock.unlock();
        }
    }

    private native ByteBuf getZeroCopy0H(byte[] key, int keyOff, int keyLen, boolean verifyChecksums, boolean fillCache, long snapshot);

    private native ByteBuf getZeroCopy0D(long keyAddr, int keyLen, boolean verifyChecksums, boolean fillCache, long snapshot);

    private ByteBuf getZeroCopy0_final(long valueAddr, int valueLen, long strAddr) {
        return new StdStringByteBuf(valueAddr, valueLen, strAddr);
    }

    @Override
    public void put(@NonNull ByteBuf key, @NonNull ByteBuf value) throws DBException {
        this.put(key, value, DEFAULT_WRITE_OPTIONS);
    }

    @Override
    public Snapshot put(@NonNull ByteBuf key, @NonNull ByteBuf value, @NonNull WriteOptions options) throws DBException {
        Lock lock = options.snapshot() ? this.writeLock : this.readLock;
        lock.lock();
        try {
            this.assertOpen();
            if (key.hasArray()) {
                if (value.hasArray()) {
                    this.put0HH(
                            key.array(), key.arrayOffset() + key.readerIndex(), key.readableBytes(),
                            value.array(), value.arrayOffset() + value.readerIndex(), value.readableBytes(),
                            options.sync());
                    return options.snapshot() ? this.snapshot0() : null;
                } else if (value.hasMemoryAddress()) {
                    this.put0HD(
                            key.array(), key.arrayOffset() + key.readerIndex(), key.readableBytes(),
                            value.memoryAddress() + value.readerIndex(), value.readableBytes(),
                            options.sync());
                    return options.snapshot() ? this.snapshot0() : null;
                }
            } else if (key.hasMemoryAddress())    {
                if (value.hasArray()) {
                    this.put0DH(
                            key.memoryAddress() + key.readerIndex(), key.readableBytes(),
                            value.array(), value.arrayOffset() + value.readerIndex(), value.readableBytes(),
                            options.sync());
                    return options.snapshot() ? this.snapshot0() : null;
                } else if (value.hasMemoryAddress()) {
                    this.put0DD(
                            key.memoryAddress() + key.readerIndex(), key.readableBytes(),
                            value.memoryAddress() + value.readerIndex(), value.readableBytes(),
                            options.sync());
                    return options.snapshot() ? this.snapshot0() : null;
                }
            }
            if (!key.hasArray() && !key.hasMemoryAddress()) {
                ByteBuf keyCopy = ByteBufAllocator.DEFAULT.buffer(key.readableBytes(), key.readableBytes());
                try {
                    checkState(keyCopy.hasArray() || keyCopy.hasMemoryAddress(), keyCopy);
                    key.getBytes(key.readerIndex(), keyCopy);
                    return this.put(keyCopy, value, options);
                } finally {
                    keyCopy.release();
                }
            } else if (!value.hasArray() && !value.hasMemoryAddress()) {
                ByteBuf valueCopy = ByteBufAllocator.DEFAULT.buffer(value.readableBytes(), value.readableBytes());
                try {
                    checkState(valueCopy.hasArray() || valueCopy.hasMemoryAddress(), valueCopy);
                    value.getBytes(value.readerIndex(), valueCopy);
                    return this.put(key, valueCopy, options);
                } finally {
                    valueCopy.release();
                }
            } else {
                throw new IllegalArgumentException(key + " " + value);
            }
        } finally {
            lock.unlock();
        }
    }

    private native void put0HH(byte[] key, int keyOff, int keyLen, byte[] val, int valOff, int valLen, boolean sync);

    private native void put0HD(byte[] key, int keyOff, int keyLen, long valAddr, int valLen, boolean sync);

    private native void put0DH(long keyAddr, int keyLen, byte[] val, int valOff, int valLen, boolean sync);

    private native void put0DD(long keyAddr, int keyLen, long valAddr, int valLen, boolean sync);

    @Override
    public void delete(@NonNull ByteBuf key) throws DBException {
        this.delete(key, DEFAULT_WRITE_OPTIONS);
    }

    @Override
    public Snapshot delete(@NonNull ByteBuf key, @NonNull WriteOptions options) throws DBException {
        Lock lock = options.snapshot() ? this.writeLock : this.readLock;
        lock.lock();
        try {
            this.assertOpen();
            if (key.hasArray()) {
                this.delete0H(
                        key.array(), key.arrayOffset() + key.readerIndex(), key.readableBytes(),
                        options.sync());
            } else if (key.hasMemoryAddress()) {
                this.delete0D(
                        key.memoryAddress() + key.readerIndex(), key.readableBytes(),
                        options.sync());
            } else {
                ByteBuf keyCopy = ByteBufAllocator.DEFAULT.buffer(key.readableBytes(), key.readableBytes());
                try {
                    checkState(keyCopy.hasArray() || keyCopy.hasMemoryAddress(), keyCopy);
                    key.getBytes(key.readerIndex(), keyCopy);
                    return this.delete(keyCopy, options);
                } finally {
                    keyCopy.release();
                }
            }
            return options.snapshot() ? this.snapshot0() : null;
        } finally {
            lock.unlock();
        }
    }

    private native void delete0H(byte[] key, int keyOff, int keyLen, boolean sync);

    private native void delete0D(long keyAddr, int keyLen, boolean sync);

    private void assertOpen() {
        if (this.db == 0L) {
            throw new IllegalStateException("NativeDB already closed!");
        }
    }

    private static final class Releaser implements Runnable {
        private final long db;
        private final long dca;

        private Releaser(long db, long dca) {
            this.db = db;
            this.dca = dca;
        }

        @Override
        public void run() {
            closeDb(this.db, this.dca);
        }
    }

    private interface NativeResource {
        void closeFromDB();
    }

    private static final class NativeIterator implements DBIterator, NativeResource {
        @NonNull
        private final NativeDB db;
        @NonNull
        private final AtomicLong ptr;
        @NonNull
        private final PCleaner cleaner;
        private Direction direction = Direction.FORWARD;
        private Position position = Position.START;
        private Map.Entry<byte[], byte[]> entry;

        public NativeIterator(@NonNull NativeDB db, long ptr) {
            this.db = db;
            this.ptr = new AtomicLong(ptr);
            this.cleaner = PCleaner.cleaner(this, new IteratorReleaser(this.ptr, this.db));
            this.db.registerResource(this);
        }

        @Override
        public void seek(@NonNull byte[] key) {
            this.db.readLock.lock();
            try {
                this.db.assertOpen();
                long ptr = this.ptr();
                iteratorSeek0(ptr, key);
                this.direction = Direction.FORWARD;
                this.position = iteratorValid0(ptr) ? Position.FORWARD : Position.END;
                this.entry = this.position.isValid() ? entry(ptr) : null;
            } finally {
                this.db.readLock.unlock();
            }
        }

        @Override
        public void seekToFirst() {
            this.db.readLock.lock();
            try {
                this.db.assertOpen();
                long ptr = this.ptr();
                iteratorSeekToFirst0(ptr);
                this.direction = Direction.FORWARD;
                this.position = iteratorValid0(ptr) ? Position.FORWARD : Position.END;
                this.entry = this.position.isValid() ? entry(ptr) : null;
            } finally {
                this.db.readLock.unlock();
            }
        }

        @Override
        public Map.Entry<byte[], byte[]> peekNext() {
            if (!this.hasNext()) {
                throw new NoSuchElementException();
            }
            return this.entry;
        }

        @Override
        public boolean hasPrev() {
            this.db.readLock.lock();
            try {
                this.db.assertOpen();
                long ptr = this.ptr();
                if (this.direction != Direction.REVERSE) {
                    this.entry = null;
                }
                if (this.entry == null) {
                    this.entry = this.prevEntry(ptr);
                }
                this.direction = Direction.REVERSE;
                return this.entry != null;
            } finally {
                this.db.readLock.unlock();
            }
        }

        @Override
        public Map.Entry<byte[], byte[]> prev() {
            if (!this.hasPrev()) {
                throw new NoSuchElementException();
            }
            Map.Entry<byte[], byte[]> entry = this.entry;
            this.entry = null;
            return entry;
        }

        @Override
        public Map.Entry<byte[], byte[]> peekPrev() {
            if (!this.hasPrev()) {
                throw new NoSuchElementException();
            }
            return this.entry;
        }

        @Override
        public void seekToLast() {
            this.db.readLock.lock();
            try {
                this.db.assertOpen();
                long ptr = this.ptr();
                iteratorSeekToLast0(ptr);
                this.direction = Direction.REVERSE;
                this.position = iteratorValid0(ptr) ? Position.REVERSE : Position.START;
                this.entry = this.position.isValid() ? entry(ptr) : null;
            } finally {
                this.db.readLock.unlock();
            }
        }

        @Override
        public boolean hasNext() {
            this.db.readLock.lock();
            try {
                this.db.assertOpen();
                long ptr = this.ptr();
                if (this.direction != Direction.FORWARD) {
                    this.entry = null;
                }
                if (this.entry == null) {
                    this.entry = this.nextEntry(ptr);
                }
                this.direction = Direction.FORWARD;
                return this.entry != null;
            } finally {
                this.db.readLock.unlock();
            }
        }

        @Override
        public Map.Entry<byte[], byte[]> next() {
            if (!this.hasNext()) {
                throw new NoSuchElementException();
            }
            Map.Entry<byte[], byte[]> entry = this.entry;
            this.entry = null;
            return entry;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
            this.db.readLock.lock();
            try {
                this.cleaner.clean();
            } finally {
                this.db.readLock.unlock();
            }
            this.db.unregisterResource(this);
        }

        @Override
        public void closeFromDB() {
            this.db.releaseIteratorFromDB(this.ptr);
        }

        private long ptr() {
            long ptr = this.ptr.get();
            if (ptr == 0L) {
                throw new IllegalStateException("NativeIterator already closed!");
            }
            return ptr;
        }

        private Map.Entry<byte[], byte[]> nextEntry(long ptr) {
            switch (this.position) {
            case START:
                iteratorSeekToFirst0(ptr);
                break;
            case END:
                checkIteratorStatus0(ptr);
                return null;
            default:
                if (!iteratorValid0(ptr)) {
                    checkIteratorStatus0(ptr);
                    this.position = Position.END;
                    return null;
                }
                iteratorNext0(ptr);
            }
            this.position = iteratorValid0(ptr) ? Position.FORWARD : Position.END;
            return this.position.isValid() ? entry(ptr) : null;
        }

        private Map.Entry<byte[], byte[]> prevEntry(long ptr) {
            switch (this.position) {
            case START:
                checkIteratorStatus0(ptr);
                return null;
            case END:
                iteratorSeekToLast0(ptr);
                break;
            default:
                if (!iteratorValid0(ptr)) {
                    checkIteratorStatus0(ptr);
                    this.position = Position.START;
                    return null;
                }
                iteratorPrev0(ptr);
            }
            this.position = iteratorValid0(ptr) ? Position.REVERSE : Position.START;
            return this.position.isValid() ? entry(ptr) : null;
        }

        private static Map.Entry<byte[], byte[]> entry(long ptr) {
            return new AbstractMap.SimpleImmutableEntry<>(iteratorKey0(ptr), iteratorValue0(ptr));
        }

        private enum Direction {
            FORWARD,
            REVERSE
        }

        private enum Position {
            START(false),
            END(false),
            FORWARD(true),
            REVERSE(true);

            private final boolean valid;

            Position(boolean valid) {
                this.valid = valid;
            }

            private boolean isValid() {
                return this.valid;
            }
        }
    }

    private static final class IteratorReleaser implements Runnable {
        @NonNull
        private final AtomicLong ptr;
        @NonNull
        private final NativeDB db;

        private IteratorReleaser(@NonNull AtomicLong ptr, @NonNull NativeDB db) {
            this.ptr = ptr;
            this.db = db;
        }

        @Override
        public void run() {
            this.db.releaseIterator(this.ptr);
        }
    }

    private static final class NativeSnapshot implements Snapshot, NativeResource {
        @NonNull
        private final NativeDB db;
        @NonNull
        private final AtomicLong ptr;
        @NonNull
        private final PCleaner cleaner;

        public NativeSnapshot(long ptr, @NonNull NativeDB db) {
            this.db = db;
            this.ptr = new AtomicLong(ptr);
            this.cleaner = PCleaner.cleaner(this, new SnapshotReleaser(this.ptr, this.db));
            this.db.registerResource(this);
        }

        @Override
        public void close() {
            this.db.readLock.lock();
            try {
                this.cleaner.clean();
            } finally {
                this.db.readLock.unlock();
            }
            this.db.unregisterResource(this);
        }

        @Override
        public void closeFromDB() {
            this.db.releaseSnapshotFromDB(this.ptr);
        }

        private long ptr() {
            long ptr = this.ptr.get();
            if (ptr == 0L) {
                throw new IllegalStateException("NativeSnapshot already closed!");
            }
            return ptr;
        }
    }

    private static final class SnapshotReleaser implements Runnable {
        @NonNull
        private final AtomicLong ptr;
        @NonNull
        private final NativeDB db;

        private SnapshotReleaser(@NonNull AtomicLong ptr, @NonNull NativeDB db) {
            this.ptr = ptr;
            this.db = db;
        }

        @Override
        public void run() {
            this.db.releaseSnapshot(this.ptr);
        }
    }

    private static final class StdStringByteBuf extends UnpooledUnsafeDirectByteBuf {
        protected final long strAddr;

        public StdStringByteBuf(long valueAddr, int valueLen, long strAddr) {
            super(UnpooledByteBufAllocator.DEFAULT, PlatformDependent.directBuffer(valueAddr, valueLen), valueLen);

            this.strAddr = strAddr;
        }

        @Override
        protected void freeDirect(ByteBuffer buffer) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ByteBuf capacity(int newCapacity) {
            throw new UnsupportedOperationException();
        }

        @Override
        protected void deallocate() {
            deleteString(this.strAddr);
        }
    }
}
