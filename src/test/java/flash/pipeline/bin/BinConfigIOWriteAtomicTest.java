package flash.pipeline.bin;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

/**
 * Integration guard that {@code writeAtomic} produces the target file and leaves
 * no leftover temp. The Windows + cloud-sync (Dropbox/OneDrive) in-place-rewrite
 * fallback that this routes through is unit-tested in
 * {@code flash.pipeline.io.IoUtilsTest#commitReplacingSmallFile_*}.
 */
public class BinConfigIOWriteAtomicTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void happyPathWritesContentAndRemovesTemp() throws Exception {
        Path target = new File(temp.getRoot(), "channel_config.json").toPath();

        BinConfigIO.writeAtomic(target, Arrays.asList("line1", "line2"));

        assertEquals("line1\nline2\n", read(target));
        assertNoLeftoverTemp(target);
    }

    @Test
    public void overwritesExistingTarget() throws Exception {
        Path target = new File(temp.getRoot(), "channel_config.json").toPath();
        Files.write(target, "stale-and-longer-content".getBytes(StandardCharsets.UTF_8));

        BinConfigIO.writeAtomic(target, Arrays.asList("fresh"));

        assertEquals("fresh\n", read(target));
        assertNoLeftoverTemp(target);
    }

    @Test
    public void concurrentWritesUseIndependentTempFilesAndLeaveDecodableJson() throws Exception {
        final Path target = new File(temp.getRoot(), ChannelConfigIO.FILE_NAME).toPath();
        final CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Void> first = executor.submit(writeConfigTask(start, target, "DAPI", "Blue"));
            Future<Void> second = executor.submit(writeConfigTask(start, target, "IBA1", "Green"));
            start.countDown();
            first.get();
            second.get();

            ChannelConfig decoded = ChannelConfigCodec.decode(read(target));
            assertEquals(1, decoded.channels.size());
            assertNoLeftoverTemp(target);
        } finally {
            executor.shutdownNow();
        }
    }

    private static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static Callable<Void> writeConfigTask(final CountDownLatch start,
                                                  final Path target,
                                                  final String name,
                                                  final String color) {
        return new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                start.await();
                BinConfigIO.writeAtomic(target,
                        Arrays.asList(ChannelConfigCodec.encode(
                                ChannelConfigIORoundTripTest.committedConfig(name, color))));
                return null;
            }
        };
    }

    private static void assertNoLeftoverTemp(Path target) throws IOException {
        Path oldFixedTemp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        assertFalse("leftover old fixed .tmp should be cleaned up: " + oldFixedTemp,
                Files.exists(oldFixedTemp));

        Path dir = target.getParent() == null ? new File(".").toPath() : target.getParent();
        String glob = "." + target.getFileName().toString() + ".*.tmp";
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, glob)) {
            for (Path leftover : stream) {
                fail("leftover unique temp should be cleaned up: " + leftover);
            }
        }
    }
}
