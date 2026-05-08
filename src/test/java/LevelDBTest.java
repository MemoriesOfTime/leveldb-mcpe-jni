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
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import lombok.NonNull;
import net.daporkchop.ldbjni.LevelDB;
import net.daporkchop.ldbjni.direct.BufType;
import net.daporkchop.ldbjni.direct.DirectDB;
import net.daporkchop.ldbjni.direct.DirectReadOptions;
import net.daporkchop.lib.common.function.io.IOConsumer;
import net.daporkchop.lib.common.misc.Tuple;
import net.daporkchop.lib.common.misc.file.PFiles;
import net.daporkchop.lib.encoding.ToBytes;
import org.iq80.leveldb.CompressionType;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.DBIterator;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.Range;
import org.iq80.leveldb.ReadOptions;
import org.iq80.leveldb.Snapshot;
import org.iq80.leveldb.WriteBatch;
import org.iq80.leveldb.WriteOptions;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static net.daporkchop.lib.common.util.PValidation.*;

/**
 * @author DaPorkchop_
 */
public class LevelDBTest {
    public static final File TEST_ROOT = new File("test_out");

    @BeforeClass
    public static void ensureNative() {
        if (!LevelDB.PROVIDER.isNative()) {
            throw new IllegalStateException("Not using native LevelDB!");
        }
    }

    @Before
    public void nukeTestDir() {
        if (PFiles.checkDirectoryExists(TEST_ROOT)) {
            System.out.println("Nuking " + TEST_ROOT);
            PFiles.rmContentsParallel(TEST_ROOT);
        }
    }

    @Test
    public void testManyWrites() throws IOException {
        this.doTest(db -> {
            int cnt = 100000;
            int batchSize = 1000;
            IntStream.range(0, cnt / batchSize)
                    .forEach(i -> {
                        try (WriteBatch writeBatch = db.createWriteBatch()) {
                            for (int j = 0; j < batchSize; j++) {
                                byte[] arr = new byte[ThreadLocalRandom.current().nextInt(10, 100000)];
                                writeBatch.put(ToBytes.toBytes(i * batchSize + j), arr);
                            }

                            db.write(writeBatch);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                        System.out.println(i);
                    });
        }, CompressionType.NONE);
    }

    @Test
    public void testBufferTypes() throws IOException {
        this.doTest(db -> {
            ByteBuf direct = ByteBufAllocator.DEFAULT.directBuffer().writeInt(0);
            ByteBuf heap = ByteBufAllocator.DEFAULT.heapBuffer().writeInt(0);

            System.out.println("DD");
            ((DirectDB) db).put(direct, direct);

            System.out.println("DH");
            ((DirectDB) db).put(direct, heap);

            System.out.println("HD");
            ((DirectDB) db).put(heap, direct);

            System.out.println("HH");
            ((DirectDB) db).put(heap, heap);
        }, CompressionType.ZLIB_RAW);
    }

    @Test
    public void testManyReads() throws IOException {
        this.doTest(db -> {
            //write a single large entry
            db.put(ToBytes.toBytes(0), new byte[1 << 20]);

            //compact it
            db.compactRange(null, null);

            //get it a bunch of times to see if the byte[]s are actually being GC-d
            IntStream.range(0, 10000 * 2).parallel()
                    .peek(i -> {
                        if ((i & 511) == 0) {
                            System.gc();
                        }
                    })
                    .forEach(i -> db.get(ToBytes.toBytes(0)));
        }, CompressionType.SNAPPY);
    }

    @Test
    public void testDirectRead() throws IOException {
        this.doTest(db -> {
            //write a single large entry
            byte[] arr0 = new byte[1 << 20 >> 3];
            ThreadLocalRandom.current().nextBytes(arr0);
            byte[] arr1 = new byte[arr0.length];
            db.put(ToBytes.toBytes(0), arr0);
            db.put(ToBytes.toBytes(1), arr1);

            //compact it
            db.compactRange(null, null);

            { //sanity checks
                ByteBuf key1 = ByteBufAllocator.DEFAULT.ioBuffer();
                key1.writeBytes(ToBytes.toBytes(0));
                ByteBuf buf = ((DirectDB) db).get(key1);
                try {
                    System.out.println(buf);
                    this.checkIdentical(arr0, buf);
                } finally {
                    buf.release();
                }
                ByteBuf key2 = ByteBufAllocator.DEFAULT.ioBuffer();
                key2.writeBytes(ToBytes.toBytes(1));
                buf = ((DirectDB) db).get(key2);
                try {
                    System.out.println(buf);
                    this.checkIdentical(arr1, buf);
                } finally {
                    buf.release();
                }

                buf = ((DirectDB) db).get(Unpooled.directBuffer().writeBytes(ToBytes.toBytes(0)),
                        new DirectReadOptions().alloc(PooledByteBufAllocator.DEFAULT).type(BufType.HEAP));
                try {
                    System.out.println(buf);
                    this.checkIdentical(arr0, buf);
                } finally {
                    buf.release();
                }
                buf = ((DirectDB) db).get(Unpooled.directBuffer().writeBytes(ToBytes.toBytes(1)),
                        new DirectReadOptions().alloc(PooledByteBufAllocator.DEFAULT).type(BufType.HEAP));
                try {
                    System.out.println(buf);
                    this.checkIdentical(arr1, buf);
                } finally {
                    buf.release();
                }
            }

            { //get it a bunch of times to check for memory leaks
                ByteBuf key0 = Unpooled.directBuffer().writeBytes(ToBytes.toBytes(0));
                ByteBuf key1 = Unpooled.directBuffer().writeBytes(ToBytes.toBytes(1));
                System.out.println("get");
                IntStream.range(0, 10000).parallel()
                        .mapToObj(i -> new Tuple<>(((DirectDB) db).get(key0), ((DirectDB) db).get(key1)))
                        .peek(t -> this.checkIdentical(arr0, t.getA()))
                        .peek(t -> this.checkIdentical(arr1, t.getB()))
                        .forEach(t -> {
                            t.getA().release();
                            t.getB().release();
                        });

                System.out.println("getInto");
                ThreadLocal<Tuple<ByteBuf, ByteBuf>> tl = ThreadLocal.withInitial(() ->
                        new Tuple<>(Unpooled.directBuffer(arr0.length, arr0.length), Unpooled.directBuffer(arr1.length, arr1.length)));
                IntStream.range(0, 10000).parallel()
                        .mapToObj(i -> tl.get())
                        .peek(t -> ((DirectDB) db).getInto(key0, t.getA().clear()))
                        .peek(t -> ((DirectDB) db).getInto(key1, t.getB().clear()))
                        .peek(t -> this.checkIdentical(arr0, t.getA()))
                        .forEach(t -> this.checkIdentical(arr1, t.getB()));

                System.out.println("getZeroCopy");
                IntStream.range(0, 10000).parallel()
                        .mapToObj(i -> new Tuple<>(((DirectDB) db).getZeroCopy(key0), ((DirectDB) db).getZeroCopy(key1)))
                        .peek(t -> this.checkIdentical(arr0, t.getA()))
                        .peek(t -> this.checkIdentical(arr1, t.getB()))
                        .forEach(t -> {
                            t.getA().release();
                            t.getB().release();
                        });

                key0.release();
                key1.release();
            }
        }, CompressionType.NONE);
    }

    @Test
    public void testIteratorTraversal() throws IOException {
        this.doTest(db -> {
            for (int i = 0; i < 5; i++) {
                db.put(ToBytes.toBytes(i), ToBytes.toBytes(i * 100));
            }

            try (DBIterator iterator = db.iterator()) {
                checkState(iterator.hasNext());
                this.checkEntry(iterator.peekNext(), 0, 0);
                this.checkEntry(iterator.next(), 0, 0);
                this.checkEntry(iterator.next(), 1, 100);
                this.checkEntry(iterator.peekPrev(), 0, 0);
                this.checkEntry(iterator.prev(), 0, 0);

                iterator.seekToFirst();
                this.checkEntry(iterator.peekNext(), 0, 0);
                this.checkEntry(iterator.next(), 0, 0);
                this.checkEntry(iterator.next(), 1, 100);

                iterator.seek(ToBytes.toBytes(3));
                this.checkEntry(iterator.peekNext(), 3, 300);
                this.checkEntry(iterator.next(), 3, 300);
                this.checkEntry(iterator.next(), 4, 400);
                checkState(!iterator.hasNext());

                iterator.seek(ToBytes.toBytes(10));
                checkState(!iterator.hasNext());
            }
        }, CompressionType.NONE);
    }

    @Test
    public void testIteratorReverseTraversalAndInvalidStates() throws IOException {
        this.doTest(db -> {
            for (int i = 0; i < 5; i++) {
                db.put(ToBytes.toBytes(i), ToBytes.toBytes(i * 100));
            }

            try (DBIterator iterator = db.iterator()) {
                iterator.seekToLast();
                this.checkEntry(iterator.peekPrev(), 4, 400);
                this.checkEntry(iterator.prev(), 4, 400);
                this.checkEntry(iterator.prev(), 3, 300);

                iterator.seek(ToBytes.toBytes(2));
                this.checkEntry(iterator.peekNext(), 2, 200);
                this.checkEntry(iterator.peekPrev(), 1, 100);
                this.checkEntry(iterator.prev(), 1, 100);
                this.checkEntry(iterator.prev(), 0, 0);
                checkState(!iterator.hasPrev());

                iterator.seek(ToBytes.toBytes(10));
                checkState(!iterator.hasNext());
                this.checkEntry(iterator.peekPrev(), 4, 400);
                this.checkEntry(iterator.prev(), 4, 400);

                this.checkThrows(NoSuchElementException.class, iterator::next);
                this.checkThrows(NoSuchElementException.class, iterator::peekNext);
                this.checkThrows(UnsupportedOperationException.class, iterator::remove);
            }
        }, CompressionType.NONE);
    }

    @Test
    public void testIteratorSnapshotView() throws IOException {
        this.doTest(db -> {
            db.put(ToBytes.toBytes(0), ToBytes.toBytes(100));
            db.put(ToBytes.toBytes(1), ToBytes.toBytes(200));
            db.put(ToBytes.toBytes(2), ToBytes.toBytes(300));

            try (Snapshot snapshot = db.getSnapshot()) {
                db.put(ToBytes.toBytes(0), ToBytes.toBytes(101));
                db.delete(ToBytes.toBytes(1));
                db.put(ToBytes.toBytes(3), ToBytes.toBytes(400));

                try (DBIterator iterator = db.iterator(new ReadOptions().snapshot(snapshot))) {
                    iterator.seekToFirst();
                    this.checkEntry(iterator.next(), 0, 100);
                    this.checkEntry(iterator.next(), 1, 200);
                    this.checkEntry(iterator.next(), 2, 300);
                    checkState(!iterator.hasNext());
                }

                this.checkIdentical(ToBytes.toBytes(101), db.get(ToBytes.toBytes(0)));
                checkState(db.get(ToBytes.toBytes(1)) == null);
                this.checkIdentical(ToBytes.toBytes(400), db.get(ToBytes.toBytes(3)));
            }
        }, CompressionType.NONE);
    }

    @Test
    public void testSnapshots() throws IOException {
        this.doTest(db -> {
            byte[] key = ToBytes.toBytes(0);
            db.put(key, ToBytes.toBytes(100));

            try (Snapshot snapshot = db.getSnapshot()) {
                db.put(key, ToBytes.toBytes(200));
                this.checkIdentical(ToBytes.toBytes(100), db.get(key, new ReadOptions().snapshot(snapshot)));
                this.checkIdentical(ToBytes.toBytes(200), db.get(key));

                ByteBuf keyBuf = Unpooled.wrappedBuffer(key);
                ByteBuf valueBuf = ((DirectDB) db).get(keyBuf, new ReadOptions().snapshot(snapshot));
                try {
                    this.checkIdentical(ToBytes.toBytes(100), valueBuf);
                } finally {
                    valueBuf.release();
                }
            }

            byte[] writeKey = ToBytes.toBytes(1);
            try (Snapshot snapshot = db.put(writeKey, ToBytes.toBytes(300), new WriteOptions().snapshot(true))) {
                db.put(writeKey, ToBytes.toBytes(400));
                this.checkIdentical(ToBytes.toBytes(300), db.get(writeKey, new ReadOptions().snapshot(snapshot)));
                this.checkIdentical(ToBytes.toBytes(400), db.get(writeKey));
            }

            try (WriteBatch batch = db.createWriteBatch()) {
                batch.put(ToBytes.toBytes(2), ToBytes.toBytes(500));
                batch.put(ToBytes.toBytes(3), ToBytes.toBytes(600));

                try (Snapshot snapshot = db.write(batch, new WriteOptions().snapshot(true))) {
                    db.put(ToBytes.toBytes(2), ToBytes.toBytes(501));
                    db.delete(ToBytes.toBytes(3));
                    this.checkIdentical(ToBytes.toBytes(500), db.get(ToBytes.toBytes(2), new ReadOptions().snapshot(snapshot)));
                    this.checkIdentical(ToBytes.toBytes(600), db.get(ToBytes.toBytes(3), new ReadOptions().snapshot(snapshot)));
                }
            }
        }, CompressionType.NONE);
    }

    @Test
    public void testApproximateSizesAndProperties() throws IOException {
        this.doTest(db -> {
            for (int i = 0; i < 128; i++) {
                byte[] value = new byte[1024];
                ThreadLocalRandom.current().nextBytes(value);
                db.put(ToBytes.toBytes(i), value);
            }

            db.compactRange(null, null);

            long[] sizes = db.getApproximateSizes(
                    new Range(ToBytes.toBytes(0), ToBytes.toBytes(64)),
                    new Range(ToBytes.toBytes(64), ToBytes.toBytes(128)),
                    new Range(new byte[0], new byte[0]));
            checkState(sizes.length == 3, sizes.length);
            checkState(sizes[0] > 0L, sizes[0]);
            checkState(sizes[1] > 0L, sizes[1]);
            checkState(sizes[2] == 0L, sizes[2]);

            String stats = db.getProperty("leveldb.stats");
            checkState(stats != null && !stats.isEmpty());
            checkState(db.getProperty("leveldb.no-such-property") == null);
        }, CompressionType.NONE);
    }

    @Test
    public void testCloseReleasesOpenNativeResources() throws IOException {
        this.doTest(db -> {
            db.put(ToBytes.toBytes(0), ToBytes.toBytes(100));

            DBIterator iterator = db.iterator();
            iterator.seekToFirst();
            checkState(iterator.hasNext());

            Snapshot snapshot = db.getSnapshot();
            this.checkIdentical(ToBytes.toBytes(100), db.get(ToBytes.toBytes(0), new ReadOptions().snapshot(snapshot)));
        }, CompressionType.NONE);
    }

    @Test
    public void testClosedNativeIteratorAndSnapshotRejectUse() throws IOException {
        this.doTest(db -> {
            db.put(ToBytes.toBytes(0), ToBytes.toBytes(100));

            DBIterator iterator = db.iterator();
            iterator.close();
            this.checkThrows(IllegalStateException.class, iterator::hasNext);

            Snapshot snapshot = db.getSnapshot();
            snapshot.close();
            this.checkThrows(IllegalStateException.class, () -> db.get(ToBytes.toBytes(0), new ReadOptions().snapshot(snapshot)));
        }, CompressionType.NONE);
    }

    @Test
    public void testConcurrentDBCloseAndNativeResourceClose() throws Exception {
        for (int i = 0; i < 50; i++) {
            if (PFiles.checkDirectoryExists(TEST_ROOT)) {
                PFiles.rmContentsParallel(TEST_ROOT);
            }

            DB db = LevelDB.PROVIDER.open(TEST_ROOT, new Options().compressionType(CompressionType.NONE));
            db.put(ToBytes.toBytes(0), ToBytes.toBytes(100));

            DBIterator iterator = db.iterator();
            Snapshot snapshot = db.getSnapshot();
            AtomicReference<Throwable> closerFailure = new AtomicReference<>();
            Thread closer = new Thread(() -> {
                try {
                    iterator.close();
                    snapshot.close();
                } catch (Throwable t) {
                    closerFailure.set(t);
                }
            }, "native-resource-close-test");

            closer.start();
            db.close();
            closer.join();

            Throwable failure = closerFailure.get();
            if (failure != null) {
                throw new AssertionError(failure);
            }
        }
    }

    @Test
    public void testCompactionSuspensionUnsupported() throws IOException {
        this.doTest(db -> {
            this.checkThrows(UnsupportedOperationException.class, db::suspendCompactions);
            this.checkThrows(UnsupportedOperationException.class, db::resumeCompactions);
        }, CompressionType.NONE);
    }

    private void doTest(@NonNull IOConsumer<DB> code, @NonNull CompressionType compression) throws IOException {
        System.out.printf("Opening DB with compression: %s...\n", compression);
        try (DB db = LevelDB.PROVIDER.open(TEST_ROOT, new Options().compressionType(compression))) {
            System.out.println("Opened DB!");

            code.acceptThrowing(db);

            System.out.println("Closing DB...");
        }
        System.out.println("Closed DB!");
    }

    private void checkIdentical(@NonNull byte[] arr, @NonNull ByteBuf buf) {
        for (int i = 0; i < arr.length; i++) {
            checkState(arr[i] == buf.getByte(i), i);
        }
    }

    private void checkIdentical(@NonNull byte[] expected, byte[] actual) {
        checkState(Arrays.equals(expected, actual), Arrays.toString(actual));
    }

    private void checkEntry(@NonNull Map.Entry<byte[], byte[]> entry, int key, int value) {
        this.checkIdentical(ToBytes.toBytes(key), entry.getKey());
        this.checkIdentical(ToBytes.toBytes(value), entry.getValue());
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

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
