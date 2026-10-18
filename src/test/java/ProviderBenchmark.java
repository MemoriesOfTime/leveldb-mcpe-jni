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
import io.netty.buffer.Unpooled;
import lombok.NonNull;
import net.daporkchop.ldbjni.DBProvider;
import net.daporkchop.ldbjni.LevelDB;
import net.daporkchop.ldbjni.direct.DirectDB;
import net.daporkchop.ldbjni.direct.DirectWriteBatch;
import net.daporkchop.lib.common.misc.file.PFiles;
import org.iq80.leveldb.CompressionType;
import org.iq80.leveldb.DBIterator;
import org.iq80.leveldb.Options;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Constructor;
import java.util.Map;
import java.util.Random;

/**
 * Compares the throughput of the native (JNI) provider against the pure-Java provider.
 * <p>
 * All timings are printed to stdout rather than asserted on; the test only fails if a provider is missing or returns
 * incorrect data.  Tunables (all optional system properties): {@code benchmark.warmup}, {@code benchmark.runs},
 * {@code benchmark.count}, {@code benchmark.smallValue}, {@code benchmark.largeCount}, {@code benchmark.largeValue}.
 *
 * @author DaPorkchop_
 */
public class ProviderBenchmark {
    private static final File TEST_ROOT = new File("test_out/provider_benchmark");

    private static final int WARMUP = Integer.getInteger("benchmark.warmup", 2);
    private static final int RUNS = Integer.getInteger("benchmark.runs", 3);
    private static final int COUNT = Integer.getInteger("benchmark.count", 100_000);
    private static final int SMALL_VALUE = Integer.getInteger("benchmark.smallValue", 128);
    private static final int LARGE_COUNT = Integer.getInteger("benchmark.largeCount", 20_000);
    private static final int LARGE_VALUE = Integer.getInteger("benchmark.largeValue", 4096);
    private static final int BATCH_SIZE = 1000;

    /** 9-byte keys in the style of MCPE chunk keys: 4-byte little-endian index + 5-byte suffix. */
    private static final int KEY_LENGTH = 9;

    @BeforeClass
    public static void ensureNativeProviderAvailable() {
        if (!LevelDB.PROVIDER.isNative()) {
            throw new IllegalStateException("Not using native LevelDB!");
        }
    }

    @Test
    public void testBenchmark() throws Exception {
        ProviderCase[] providers = this.providers();
        System.out.printf("benchmark: warmup=%d runs=%d java=%s native=%s%n",
                WARMUP, RUNS, providers[0].provider.getClass().getSimpleName(), providers[1].provider.getClass().getSimpleName());

        if (PFiles.checkDirectoryExists(TEST_ROOT)) {
            PFiles.rmContentsParallel(TEST_ROOT);
        }
        //noinspection ResultOfMethodCallIgnored
        TEST_ROOT.mkdirs();

        this.runScenario(providers, "small values", COUNT, SMALL_VALUE, WARMUP, RUNS);
        this.runScenario(providers, "large values", LARGE_COUNT, LARGE_VALUE, 1, 2);
    }

    private void runScenario(@NonNull ProviderCase[] providers, @NonNull String title, int count, int valueSize, int warmup, int runs) throws Exception {
        System.out.printf("%n=== %s (N=%d, key=%dB, value=%dB, compression=none) ===%n", title, count, KEY_LENGTH, valueSize);

        byte[] value = new byte[valueSize];
        new Random(valueSize).nextBytes(value);
        int[] order = shuffledOrder(count);

        this.writeSequential(providers, count, value, warmup, runs);
        this.writeRandom(providers, count, order, value, warmup, runs);
        this.writeBatched(providers, count, value, warmup, runs);
        this.readRandom(providers, count, order, warmup, runs, false);
        this.readRandom(providers, count, order, warmup, runs, true);
        this.iterateAll(providers, count, value, warmup, runs);
    }

    private void writeSequential(@NonNull ProviderCase[] providers, int count, @NonNull byte[] value, int warmup, int runs) throws Exception {
        this.phase(providers, "put sequential", count, value.length, warmup, runs, true, (db, runBase, runCount) -> {
            byte[] key = new byte[KEY_LENGTH];
            for (int i = 0; i < runCount; i++) {
                setKey(key, runBase + i);
                db.put(key, value);
            }
        });
    }

    private void writeRandom(@NonNull ProviderCase[] providers, int count, @NonNull int[] order, @NonNull byte[] value, int warmup, int runs) throws Exception {
        this.phase(providers, "put random", count, value.length, warmup, runs, true, (db, runBase, runCount) -> {
            byte[] key = new byte[KEY_LENGTH];
            for (int i = 0; i < runCount; i++) {
                setKey(key, runBase + order[i % order.length]);
                db.put(key, value);
            }
        });
    }

    private void writeBatched(@NonNull ProviderCase[] providers, int count, @NonNull byte[] value, int warmup, int runs) throws Exception {
        this.phase(providers, "put batched(x" + BATCH_SIZE + ")", count, value.length, warmup, runs, true, (db, runBase, runCount) -> {
            byte[] key = new byte[KEY_LENGTH];
            int nextKey = runBase;
            int remaining = runCount;
            while (remaining > 0) {
                int batchCount = Math.min(BATCH_SIZE, remaining);
                try (DirectWriteBatch batch = db.createWriteBatch()) {
                    for (int i = 0; i < batchCount; i++) {
                        setKey(key, nextKey++);
                        batch.put(key, value);
                    }
                    db.write(batch);
                }
                remaining -= batchCount;
            }
        });
    }

    private void readRandom(@NonNull ProviderCase[] providers, int count, @NonNull int[] order, int warmup, int runs, boolean zeroCopy) throws Exception {
        String name = zeroCopy ? "get random zero-copy" : "get random";
        this.phase(providers, name, count, 1, warmup, runs, false, (db, ignored, runCount) -> {
            long checksum = 0L;
            if (zeroCopy) {
                ByteBuf key = Unpooled.directBuffer(KEY_LENGTH, KEY_LENGTH);
                key.writeZero(KEY_LENGTH); // zero-fill suffix and set writerIndex so the full key is readable
                try {
                    for (int i = 0; i < runCount; i++) {
                        key.setIntLE(0, order[i % order.length]);
                        ByteBuf result = db.getZeroCopy(key);
                        try {
                            checksum += result.readableBytes() + result.getByte(result.readerIndex());
                        } finally {
                            result.release();
                        }
                    }
                } finally {
                    key.release();
                }
            } else {
                byte[] key = new byte[KEY_LENGTH];
                for (int i = 0; i < runCount; i++) {
                    setKey(key, order[i % order.length]);
                    byte[] result = db.get(key);
                    checksum += result.length + result[0];
                }
            }
            if (checksum == Long.MIN_VALUE) { // never true; keeps the reads from being eliminated
                throw new IllegalStateException(String.valueOf(checksum));
            }
        });
    }

    private void iterateAll(@NonNull ProviderCase[] providers, int count, @NonNull byte[] value, int warmup, int runs) throws Exception {
        this.phase(providers, "iterate full scan", count, value.length, warmup, runs, false, (db, ignored, runCount) -> {
            try (DBIterator iterator = db.iterator()) {
                iterator.seekToFirst();
                long checksum = 0L;
                while (iterator.hasNext()) {
                    Map.Entry<byte[], byte[]> entry = iterator.next();
                    checksum += entry.getKey().length + entry.getValue().length;
                }
                if (checksum != (long) runCount * (KEY_LENGTH + value.length)) {
                    throw new IllegalStateException("expected " + runCount + " entries, checksum=" + checksum);
                }
            }
        });
    }

    /**
     * Runs a single phase for both providers on independent databases, alternating which provider executes first
     * between runs so systematic effects (thermal throttling, GC, background compaction) hit both equally, and prints
     * the averaged results.
     *
     * @param writes whether the phase writes (each run uses a fresh, disjoint key range) or reads (the database is
     *               populated once, untimed, before measuring)
     */
    private void phase(@NonNull ProviderCase[] providers, @NonNull String name, int count, int valueSize, int warmup, int runs,
                       boolean writes, @NonNull PhaseOperation operation) throws Exception {
        String[] dirs = new String[providers.length];
        double[] totals = new double[providers.length];
        DirectDB[] dbs = new DirectDB[providers.length];
        try {
            for (int i = 0; i < providers.length; i++) {
                dirs[i] = providers[i].name + '-' + name;
                dbs[i] = providers[i].provider.open(new File(TEST_ROOT, dirs[i]), this.options());
                if (!writes) { // populate the database up-front so read runs always hit present keys
                    byte[] key = new byte[KEY_LENGTH];
                    byte[] chunk = new byte[Math.min(count, 8192)];
                    new Random(42L).nextBytes(chunk);
                    int span = Math.max(1, chunk.length - valueSize % chunk.length);
                    for (int j = 0; j < count; j++) {
                        setKey(key, j);
                        byte[] entryValue = new byte[valueSize];
                        System.arraycopy(chunk, (j * 31) % span, entryValue, 0, valueSize);
                        entryValue[valueSize - 1] = (byte) j;
                        dbs[i].put(key, entryValue);
                    }
                }
            }

            int totalRuns = (warmup + runs) * providers.length;
            for (int run = 0; run < totalRuns; run++) {
                boolean measured = run >= warmup * providers.length;
                int group = run / providers.length; // warmup groups first, then measured groups
                int indexInGroup = run % providers.length;
                if ((group & 1) != 0) { // alternate provider order between groups
                    indexInGroup = providers.length - 1 - indexInGroup;
                }
                DirectDB db = dbs[indexInGroup];
                int runBase = writes ? run * count : 0; // disjoint key range per run
                long start = System.nanoTime();
                operation.run(db, runBase, count);
                long elapsed = System.nanoTime() - start;
                if (measured) {
                    double seconds = elapsed / 1_000_000_000.0;
                    double opsPerSecond = count / seconds;
                    double megaBytesPerSecond = opsPerSecond * valueSize / (1024.0 * 1024.0);
                    System.out.printf("  %-24s %-6s %10.0f ops/s %8.1f MB/s (%.3fs)%n",
                            name, providers[indexInGroup].name, opsPerSecond, megaBytesPerSecond, seconds);
                    totals[indexInGroup] += opsPerSecond;
                }
            }
        } finally {
            for (DirectDB db : dbs) {
                if (db != null) {
                    db.close();
                }
            }
        }

        double[] averages = new double[providers.length];
        for (int i = 0; i < providers.length; i++) {
            averages[i] = totals[i] / runs;
        }
        System.out.printf("  %-24s native/java ratio: %.2fx (java %.0f, native %.0f ops/s avg)%n",
                name, averages[1] / averages[0], averages[0], averages[1]);
        for (int i = 0; i < providers.length; i++) {
            PFiles.rmContentsParallel(new File(TEST_ROOT, dirs[i]));
        }
    }

    private ProviderCase[] providers() throws Exception {
        return new ProviderCase[] {
                new ProviderCase("java", instantiateProvider("net.daporkchop.ldbjni.java.JavaDBProvider"), false),
                new ProviderCase("native", LevelDB.PROVIDER, true)
        };
    }

    private static DBProvider instantiateProvider(@NonNull String className) throws Exception {
        Class<?> clazz = Class.forName(className, false, LevelDB.class.getClassLoader());
        Constructor<?> constructor = clazz.getDeclaredConstructor();
        constructor.setAccessible(true);
        return (DBProvider) constructor.newInstance();
    }

    private Options options() {
        return new Options()
                .createIfMissing(true)
                .compressionType(CompressionType.NONE);
    }

    private static int[] shuffledOrder(int count) {
        int[] order = new int[count];
        for (int i = 0; i < count; i++) {
            order[i] = i;
        }
        Random random = new Random(1234L);
        for (int i = count - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int t = order[i];
            order[i] = order[j];
            order[j] = t;
        }
        return order;
    }

    private static void setKey(@NonNull byte[] key, int index) {
        key[0] = (byte) index;
        key[1] = (byte) (index >>> 8);
        key[2] = (byte) (index >>> 16);
        key[3] = (byte) (index >>> 24);
        // bytes 4..8 remain zero
    }

    @FunctionalInterface
    private interface PhaseOperation {
        void run(@NonNull DirectDB db, int runBase, int runCount) throws Exception;
    }

    private static final class ProviderCase {
        final String name;
        final DBProvider provider;

        ProviderCase(@NonNull String name, @NonNull DBProvider provider, boolean nativeProvider) {
            if (provider.isNative() != nativeProvider) {
                throw new IllegalStateException(name);
            }
            this.name = name;
            this.provider = provider;
        }
    }
}
