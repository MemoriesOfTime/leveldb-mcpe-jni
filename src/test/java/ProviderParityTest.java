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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import net.daporkchop.ldbjni.DBProvider;
import net.daporkchop.ldbjni.LevelDB;
import net.daporkchop.ldbjni.direct.BufType;
import net.daporkchop.ldbjni.direct.DirectDB;
import net.daporkchop.ldbjni.direct.DirectReadOptions;
import net.daporkchop.ldbjni.direct.DirectWriteBatch;
import net.daporkchop.lib.common.misc.file.PFiles;
import org.iq80.leveldb.CompressionType;
import org.iq80.leveldb.DBIterator;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.Range;
import org.iq80.leveldb.ReadOptions;
import org.iq80.leveldb.Snapshot;
import org.iq80.leveldb.WriteOptions;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Map;

import static net.daporkchop.lib.common.util.PValidation.checkState;

public class ProviderParityTest {
    private static final File TEST_ROOT = new File("test_out/provider_parity");
    private static final boolean LITTLE_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;

    @BeforeClass
    public static void ensureNativeProviderAvailable() {
        if (!LevelDB.PROVIDER.isNative()) {
            throw new IllegalStateException("Not using native LevelDB!");
        }
    }

    @Before
    public void nukeTestDir() {
        if (PFiles.checkDirectoryExists(TEST_ROOT)) {
            PFiles.rmContentsParallel(TEST_ROOT);
        }
        checkState(TEST_ROOT.mkdirs() || TEST_ROOT.isDirectory(), TEST_ROOT);
    }

    @Test
    public void testJavaAndNativeDirectDBMethods() throws Exception {
        for (ProviderCase provider : this.providers()) {
            this.withDB(provider, "direct-methods", db -> {
                checkState(provider.provider.isNative() == provider.nativeProvider, provider.name);

                db.put(bytes(0), bytes(100));
                this.checkBytes(bytes(100), db.get(bytes(0)));
                this.checkBytes(bytes(100), db.get(bytes(0), new ReadOptions().fillCache(false).verifyChecksums(true)));

                this.put(db, this.heap(1), this.heap(101));
                this.put(db, this.heap(2), this.direct(102));
                this.put(db, this.direct(3), this.heap(103));
                this.put(db, this.direct(4), this.direct(104));
                this.put(db, this.composite(5), this.composite(105));
                for (int i = 1; i <= 5; i++) {
                    this.checkDBValue(db, i, i + 100);
                }

                for (BufType type : BufType.values()) {
                    ByteBuf key = this.heap(1);
                    ByteBuf value = db.get(key, new DirectReadOptions()
                            .alloc(PooledByteBufAllocator.DEFAULT)
                            .type(type)
                            .fillCache(false)
                            .verifyChecksums(true));
                    try {
                        this.checkBytes(bytes(101), value);
                    } finally {
                        value.release();
                        key.release();
                    }
                }

                this.checkGetInto(db, this.heap(2), 102);
                this.checkGetInto(db, this.direct(3), 103);
                this.checkGetInto(db, this.heap(4), 104, new DirectReadOptions()
                        .fillCache(false)
                        .verifyChecksums(true));
                this.checkMissingGetInto(db, this.heap(404), provider.name);

                ByteBuf zeroCopyKey = this.direct(4);
                ByteBuf zeroCopy = db.getZeroCopy(zeroCopyKey);
                try {
                    this.checkBytes(bytes(104), zeroCopy);
                } finally {
                    zeroCopy.release();
                    zeroCopyKey.release();
                }

                ByteBuf zeroCopyOptionsKey = this.heap(3);
                ByteBuf zeroCopyOptions = db.getZeroCopy(zeroCopyOptionsKey, new DirectReadOptions()
                        .alloc(PooledByteBufAllocator.DEFAULT)
                        .type(BufType.DEFAULT)
                        .fillCache(false)
                        .verifyChecksums(true));
                try {
                    this.checkBytes(bytes(103), zeroCopyOptions);
                } finally {
                    zeroCopyOptions.release();
                    zeroCopyOptionsKey.release();
                }

                db.put(bytes(6), new byte[0]);
                ByteBuf emptyZeroCopyKey = this.heap(6);
                ByteBuf emptyZeroCopy = db.getZeroCopy(emptyZeroCopyKey);
                try {
                    this.checkBytes(new byte[0], emptyZeroCopy);
                } finally {
                    emptyZeroCopy.release();
                    emptyZeroCopyKey.release();
                }

                db.delete(bytes(0));
                checkState(db.get(bytes(0)) == null, provider.name);

                this.delete(db, this.heap(1));
                this.delete(db, this.direct(2));
                checkState(db.get(bytes(1)) == null, provider.name);
                checkState(db.get(bytes(2)) == null, provider.name);

                db.put(bytes(20), bytes(120));
                try (Snapshot snapshot = db.delete(bytes(20), new WriteOptions().snapshot(true))) {
                    db.put(bytes(20), bytes(121));
                    checkState(db.get(bytes(20), new ReadOptions().snapshot(snapshot)) == null, provider.name);
                    this.checkBytes(bytes(121), db.get(bytes(20)));
                }

                db.put(bytes(21), bytes(121));
                try (Snapshot snapshot = this.deleteWithSnapshot(db, this.heap(21))) {
                    db.put(bytes(21), bytes(122));
                    checkState(db.get(bytes(21), new ReadOptions().snapshot(snapshot)) == null, provider.name);
                    this.checkBytes(bytes(122), db.get(bytes(21)));
                }

                try (Snapshot snapshot = this.putWithSnapshot(db, this.direct(22), this.direct(122))) {
                    db.put(bytes(22), bytes(123));
                    this.checkBytes(bytes(122), db.get(bytes(22), new ReadOptions().snapshot(snapshot)));
                    this.checkBytes(bytes(123), db.get(bytes(22)));
                }
            });
        }
    }

    @Test
    public void testJavaAndNativeWriteBatchMethods() throws Exception {
        for (ProviderCase provider : this.providers()) {
            this.withDB(provider, "write-batch", db -> {
                db.put(bytes(0), bytes(100));
                db.put(bytes(1), bytes(101));
                db.put(bytes(2), bytes(102));
                db.put(bytes(3), bytes(103));

                try (DirectWriteBatch batch = db.createWriteBatch()) {
                    checkState(batch.size() == 0, provider.name);
                    checkState(batch.getApproximateSize() == 0, provider.name);

                    batch.put(bytes(10), bytes(110));
                    this.batchPut(batch, this.heap(11), this.heap(111));
                    this.batchPut(batch, this.heap(12), this.direct(112));
                    this.batchPut(batch, this.direct(13), this.heap(113));
                    this.batchPut(batch, this.direct(14), this.direct(114));
                    this.batchPut(batch, this.composite(15), this.composite(115));
                    batch.delete(bytes(0));
                    this.batchDelete(batch, this.heap(1));
                    this.batchDelete(batch, this.direct(2));
                    this.batchDelete(batch, this.composite(3));

                    checkState(batch.size() == 10, provider.name);
                    checkState(batch.getApproximateSize() == 160, provider.name);
                    db.write(batch);
                }

                checkState(db.get(bytes(0)) == null, provider.name);
                checkState(db.get(bytes(1)) == null, provider.name);
                checkState(db.get(bytes(2)) == null, provider.name);
                checkState(db.get(bytes(3)) == null, provider.name);
                for (int i = 10; i <= 15; i++) {
                    this.checkDBValue(db, i, i + 100);
                }

                try (DirectWriteBatch batch = db.createWriteBatch()) {
                    batch.put(bytes(30), bytes(130));
                    batch.put(bytes(31), bytes(131));

                    try (Snapshot snapshot = db.write(batch, new WriteOptions().snapshot(true))) {
                        db.put(bytes(30), bytes(230));
                        db.delete(bytes(31));
                        this.checkBytes(bytes(130), db.get(bytes(30), new ReadOptions().snapshot(snapshot)));
                        this.checkBytes(bytes(131), db.get(bytes(31), new ReadOptions().snapshot(snapshot)));
                    }
                }
            });
        }
    }

    @Test
    public void testJavaAndNativeIteratorSnapshotAndMetadataMethods() throws Exception {
        for (ProviderCase provider : this.providers()) {
            this.withDB(provider, "iterator-snapshot-metadata", db -> {
                for (int i = 0; i < 16; i++) {
                    db.put(bytes(i), bytes(i + 1000));
                }

                try (DBIterator iterator = db.iterator()) {
                    checkState(iterator.hasNext(), provider.name);
                    this.checkEntry(iterator.peekNext(), 0, 1000);
                    this.checkEntry(iterator.next(), 0, 1000);
                    checkState(!iterator.hasPrev(), provider.name);

                    iterator.seek(bytes(8));
                    this.checkEntry(iterator.peekNext(), 8, 1008);
                    this.checkEntry(iterator.next(), 8, 1008);
                    this.checkEntry(iterator.peekPrev(), 7, 1007);
                    this.checkEntry(iterator.prev(), 7, 1007);

                    iterator.seekToFirst();
                    for (int i = 0; i < 16; i++) {
                        this.checkEntry(iterator.next(), i, i + 1000);
                    }
                    checkState(!iterator.hasNext(), provider.name);
                }

                try (Snapshot snapshot = db.getSnapshot()) {
                    db.put(bytes(0), bytes(2000));
                    db.delete(bytes(1));
                    db.put(bytes(16), bytes(1016));

                    try (DBIterator iterator = db.iterator(new ReadOptions().snapshot(snapshot))) {
                        iterator.seekToFirst();
                        this.checkEntry(iterator.next(), 0, 1000);
                        this.checkEntry(iterator.next(), 1, 1001);
                    }
                }

                try (DBIterator iterator = db.iterator()) {
                    iterator.seekToLast();
                    checkState(iterator.hasPrev(), provider.name);
                    this.checkEntry(iterator.peekPrev(), 16, 1016);
                    this.checkEntry(iterator.prev(), 16, 1016);
                    this.checkEntry(iterator.prev(), 15, 1015);

                    iterator.seek(bytes(8));
                    this.checkEntry(iterator.peekNext(), 8, 1008);
                    this.checkEntry(iterator.peekPrev(), 7, 1007);
                    this.checkEntry(iterator.prev(), 7, 1007);

                    iterator.seek(bytes(99));
                    checkState(!iterator.hasNext(), provider.name);
                    this.checkEntry(iterator.peekPrev(), 16, 1016);
                }

                db.compactRange(null, null);

                long[] sizes = db.getApproximateSizes(
                        new Range(bytes(0), bytes(8)),
                        new Range(bytes(8), bytes(32)));
                checkState(sizes.length == 2, provider.name);
                if (provider.nativeProvider) {
                    checkState(sizes[0] > 0L || sizes[1] > 0L, provider.name);
                }

                String stats = db.getProperty("leveldb.stats");
                checkState(stats != null && !stats.isEmpty(), provider.name);
                checkState(db.getProperty("leveldb.no-such-property") == null, provider.name);

                if (provider.nativeProvider) {
                    this.checkThrows(UnsupportedOperationException.class, db::suspendCompactions);
                    this.checkThrows(UnsupportedOperationException.class, db::resumeCompactions);
                } else {
                    db.suspendCompactions();
                    db.resumeCompactions();
                }
            });
        }
    }

    @Test
    public void testJavaAndNativeProviderDestroyAndRepairMethods() throws Exception {
        for (ProviderCase provider : this.providers()) {
            File destroyDir = new File(TEST_ROOT, provider.name + "-destroy");
            try (DirectDB db = provider.provider.open(destroyDir, this.options())) {
                db.put(bytes(0), bytes(100));
            }
            checkState(new File(destroyDir, "CURRENT").isFile(), provider.name);
            provider.provider.destroy(destroyDir, this.options());
            checkState(!new File(destroyDir, "CURRENT").exists(), provider.name);

            File repairDir = new File(TEST_ROOT, provider.name + "-repair");
            try (DirectDB db = provider.provider.open(repairDir, this.options())) {
                db.put(bytes(1), bytes(101));
            }

            if (provider.nativeProvider) {
                provider.provider.repair(repairDir, this.options());
                try (DirectDB db = provider.provider.open(repairDir, this.options())) {
                    this.checkBytes(bytes(101), db.get(bytes(1)));
                }
            } else {
                this.checkThrows(UnsupportedOperationException.class, () -> provider.provider.repair(repairDir, this.options()));
            }
        }
    }

    private ProviderCase[] providers() throws Exception {
        return new ProviderCase[] {
                new ProviderCase("native", LevelDB.PROVIDER, true),
                new ProviderCase("java", instantiateProvider("net.daporkchop.ldbjni.java.JavaDBProvider"), false)
        };
    }

    private static DBProvider instantiateProvider(@NonNull String className) throws Exception {
        Class<?> clazz = Class.forName(className, false, LevelDB.class.getClassLoader());
        Constructor<?> constructor = clazz.getDeclaredConstructor();
        constructor.setAccessible(true);
        return (DBProvider) constructor.newInstance();
    }

    private void withDB(@NonNull ProviderCase provider, @NonNull String name, @NonNull DBConsumer consumer) throws Exception {
        File path = new File(TEST_ROOT, provider.name + '-' + name);
        try (DirectDB db = provider.provider.open(path, this.options())) {
            consumer.accept(db);
        }
    }

    private Options options() {
        return new Options()
                .createIfMissing(true)
                .compressionType(CompressionType.NONE);
    }

    private void put(@NonNull DirectDB db, @NonNull ByteBuf key, @NonNull ByteBuf value) {
        try {
            db.put(key, value);
        } finally {
            value.release();
            key.release();
        }
    }

    private Snapshot putWithSnapshot(@NonNull DirectDB db, @NonNull ByteBuf key, @NonNull ByteBuf value) {
        try {
            return db.put(key, value, new WriteOptions().snapshot(true));
        } finally {
            value.release();
            key.release();
        }
    }

    private void delete(@NonNull DirectDB db, @NonNull ByteBuf key) {
        try {
            db.delete(key);
        } finally {
            key.release();
        }
    }

    private Snapshot deleteWithSnapshot(@NonNull DirectDB db, @NonNull ByteBuf key) {
        try {
            return db.delete(key, new WriteOptions().snapshot(true));
        } finally {
            key.release();
        }
    }

    private void batchPut(@NonNull DirectWriteBatch batch, @NonNull ByteBuf key, @NonNull ByteBuf value) {
        try {
            batch.put(key, value);
        } finally {
            value.release();
            key.release();
        }
    }

    private void batchDelete(@NonNull DirectWriteBatch batch, @NonNull ByteBuf key) {
        try {
            batch.delete(key);
        } finally {
            key.release();
        }
    }

    private void checkDBValue(@NonNull DirectDB db, int key, int value) {
        this.checkBytes(bytes(value), db.get(bytes(key)));

        ByteBuf keyBuf = this.direct(key);
        ByteBuf valueBuf = db.get(keyBuf);
        try {
            this.checkBytes(bytes(value), valueBuf);
        } finally {
            valueBuf.release();
            keyBuf.release();
        }
    }

    private void checkGetInto(@NonNull DirectDB db, @NonNull ByteBuf key, int value) {
        this.checkGetInto(db, key, value, null);
    }

    private void checkGetInto(@NonNull DirectDB db, @NonNull ByteBuf key, int value, ReadOptions options) {
        ByteBuf dst = Unpooled.buffer();
        try {
            checkState(options != null ? db.getInto(key, dst, options) : db.getInto(key, dst), key);
            this.checkBytes(bytes(value), dst);
        } finally {
            dst.release();
            key.release();
        }
    }

    private void checkMissingGetInto(@NonNull DirectDB db, @NonNull ByteBuf key, @NonNull String provider) {
        ByteBuf dst = Unpooled.buffer();
        try {
            checkState(!db.getInto(key, dst), provider);
            checkState(dst.readableBytes() == 0, dst);
        } finally {
            dst.release();
            key.release();
        }
    }

    private ByteBuf heap(int value) {
        return Unpooled.wrappedBuffer(bytes(value));
    }

    private ByteBuf direct(int value) {
        return Unpooled.directBuffer(4, 4).writeBytes(bytes(value));
    }

    private ByteBuf composite(int value) {
        byte[] bytes = bytes(value);
        return Unpooled.wrappedBuffer(
                Unpooled.wrappedBuffer(bytes, 0, 2),
                Unpooled.wrappedBuffer(bytes, 2, 2));
    }

    private static byte[] bytes(int value) {
        if (LITTLE_ENDIAN) {
            return new byte[] {
                    (byte) value,
                    (byte) (value >>> 8),
                    (byte) (value >>> 16),
                    (byte) (value >>> 24)
            };
        } else {
            return new byte[] {
                    (byte) (value >>> 24),
                    (byte) (value >>> 16),
                    (byte) (value >>> 8),
                    (byte) value
            };
        }
    }

    private void checkBytes(byte[] expected, ByteBuf actual) {
        checkState(actual != null, Arrays.toString(expected));
        checkState(actual.readableBytes() == expected.length, actual);
        for (int i = 0; i < expected.length; i++) {
            checkState(actual.getByte(actual.readerIndex() + i) == expected[i], i);
        }
    }

    private void checkBytes(byte[] expected, byte[] actual) {
        checkState(Arrays.equals(expected, actual), Arrays.toString(actual));
    }

    private void checkEntry(@NonNull Map.Entry<byte[], byte[]> entry, int key, int value) {
        this.checkBytes(bytes(key), entry.getKey());
        this.checkBytes(bytes(value), entry.getValue());
    }

    private void checkThrows(@NonNull Class<? extends Throwable> expected, @NonNull ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (Throwable t) {
            checkState(expected.isInstance(t), t);
            return;
        }
        throw new IllegalStateException("Expected exception: " + expected.getCanonicalName());
    }

    @RequiredArgsConstructor
    private static final class ProviderCase {
        @NonNull
        private final String name;
        @NonNull
        private final DBProvider provider;
        private final boolean nativeProvider;
    }

    @FunctionalInterface
    private interface DBConsumer {
        void accept(@NonNull DirectDB db) throws Exception;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
