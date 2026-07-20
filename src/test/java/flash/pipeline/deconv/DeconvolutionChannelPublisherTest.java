package flash.pipeline.deconv;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DeconvolutionChannelPublisherTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void mirrorPublishFailureRestoresExactPriorTiffAndParamsManifest() throws Exception {
        File root = temp.newFolder("publisher-rollback");
        DeconvolutionIO.ArtifactIdentity identity = identity("project:Input/Test.LIF", "51");
        publish(root, identity, 0, "old-pixels", "OLD");

        File staged = staged(root, "new-pixels");
        try {
            DeconvolutionChannelPublisher.publishForTest(root, identity, 0, staged,
                    entry(identity, "NEW"), failAt(
                            DeconvolutionChannelPublisher.FaultPoint.AFTER_MIRROR_PUBLISH));
            fail("expected deterministic publication failure");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("deterministic"));
        }

        assertEquals("old-pixels", read(DeconvolutionIO.deconvFile(root, identity, 0)));
        assertEquals("OLD", DeconvManifest.load(
                DeconvolutionIO.manifestFile(root, identity)).channel(0).paramsHash);
    }

    @Test
    public void ambiguousManifestCommitIsValidatedThenRestoredAndStillPropagated() throws Exception {
        File root = temp.newFolder("publisher-ambiguous-manifest");
        DeconvolutionIO.ArtifactIdentity identity = identity("project:Input/Test.LIF", "62");
        publish(root, identity, 0, "old-pixels", "OLD");

        try {
            DeconvolutionChannelPublisher.publishForTest(root, identity, 0,
                    staged(root, "new-pixels"), entry(identity, "NEW"), failAt(
                            DeconvolutionChannelPublisher.FaultPoint.AFTER_MANIFEST_COMMIT_BEFORE_VALIDATION));
            fail("expected ambiguous commit failure to propagate");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("deterministic"));
        }

        assertEquals("old-pixels", read(DeconvolutionIO.deconvFile(root, identity, 0)));
        assertEquals("OLD", DeconvManifest.load(
                DeconvolutionIO.manifestFile(root, identity)).channel(0).paramsHash);
    }

    @Test
    public void concurrentChannelWritersRetainBothManifestUpdates() throws Exception {
        final File root = temp.newFolder("publisher-concurrent-channels");
        final DeconvolutionIO.ArtifactIdentity identity = identity("project:Input/Test.LIF", "73");
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread first = writer(root, identity, 0, "zero", "P0", start, failure);
        Thread second = writer(root, identity, 1, "one", "P1", start, failure);
        first.start();
        second.start();
        start.countDown();
        first.join(5000L);
        second.join(5000L);

        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
        assertEquals(null, failure.get());
        DeconvManifest manifest = DeconvManifest.load(DeconvolutionIO.manifestFile(root, identity));
        assertEquals("P0", manifest.channel(0).paramsHash);
        assertEquals("P1", manifest.channel(1).paramsHash);
    }

    @Test
    public void readerLockCoversValidationAndPixelReadWindow() throws Exception {
        final File root = temp.newFolder("publisher-reader-window");
        final DeconvolutionIO.ArtifactIdentity identity = identity("project:Input/Test.LIF", "84");
        publish(root, identity, 0, "old-pixels", "OLD");
        final CountDownLatch validated = new CountDownLatch(1);
        final CountDownLatch releaseReader = new CountDownLatch(1);
        final CountDownLatch writerFinished = new CountDownLatch(1);
        final AtomicReference<String> readerPixels = new AtomicReference<String>();
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                try (DeconvolutionFamilyLock.Handle ignored =
                             DeconvolutionIO.lockFamilyForAccess(root, identity)) {
                    assertEquals("OLD", DeconvManifest.load(
                            DeconvolutionIO.manifestFile(root, identity)).channel(0).paramsHash);
                    validated.countDown();
                    assertTrue(releaseReader.await(5, TimeUnit.SECONDS));
                    readerPixels.set(read(DeconvolutionIO.deconvFile(root, identity, 0)));
                } catch (Throwable t) {
                    failure.set(t);
                }
            }
        }, "deconv-reader-window");
        Thread writer = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    publish(root, identity, 0, "new-pixels", "NEW");
                    writerFinished.countDown();
                } catch (Throwable t) {
                    failure.set(t);
                }
            }
        }, "deconv-writer-window");
        reader.start();
        assertTrue(validated.await(5, TimeUnit.SECONDS));
        writer.start();
        assertFalse(writerFinished.await(250, TimeUnit.MILLISECONDS));
        releaseReader.countDown();
        reader.join(5000L);
        writer.join(5000L);

        assertEquals(null, failure.get());
        assertEquals("old-pixels", readerPixels.get());
        assertEquals("new-pixels", read(DeconvolutionIO.deconvFile(root, identity, 0)));
    }

    private static Thread writer(final File root,
                                 final DeconvolutionIO.ArtifactIdentity identity,
                                 final int channel,
                                 final String pixels,
                                 final String params,
                                 final CountDownLatch start,
                                 final AtomicReference<Throwable> failure) {
        return new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    publish(root, identity, channel, pixels, params);
                } catch (Throwable t) {
                    failure.set(t);
                }
            }
        });
    }

    private static void publish(File root, DeconvolutionIO.ArtifactIdentity identity,
                                int channel, String pixels, String params) throws Exception {
        DeconvolutionChannelPublisher.publish(root, identity, channel,
                staged(root, pixels), entry(identity, params));
    }

    private static DeconvolutionChannelPublisher.FaultInjector failAt(
            final DeconvolutionChannelPublisher.FaultPoint expected) {
        return new DeconvolutionChannelPublisher.FaultInjector() {
            @Override
            public void checkpoint(DeconvolutionChannelPublisher.FaultPoint point) throws IOException {
                if (point == expected) throw new IOException("deterministic " + point);
            }
        };
    }

    private static DeconvManifest.ChannelEntry entry(
            DeconvolutionIO.ArtifactIdentity identity, String params) {
        return new DeconvManifest.ChannelEntry(params, new LinkedHashMap<String, String>(),
                new DeconvManifest.SourceFingerprint(identity.sourceSize, 0L,
                        identity.verifiedSourceContentHash), "test", "1", 1);
    }

    private static File staged(File root, String pixels) throws Exception {
        File file = File.createTempFile("deconv-staged-", ".tif", root);
        Files.write(file.toPath(), pixels.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static DeconvolutionIO.ArtifactIdentity identity(String discriminator, String pair) {
        return new DeconvolutionIO.ArtifactIdentity(DeconvolutionIO.ArtifactIdentity.VERSION,
                100L, repeat(pair, 32), discriminator, 0, "Image");
    }

    private static String repeat(String value, int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }
}
