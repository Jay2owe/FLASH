package flash.pipeline.image;

import ij.ImagePlus;
import ij.ImageStack;
import ij.plugin.ContrastEnhancer;
import ij.plugin.filter.BackgroundSubtracter;
import ij.plugin.filter.GaussianBlur;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class FilterExecutorIntegrityTest {
    @Test
    public void unsharpMatchesImageJFormulaIncludingNegativeFloatValues() {
        FloatProcessor source = new FloatProcessor(9, 9);
        source.setf(4, 4, 100f);
        FloatProcessor blurred = (FloatProcessor) source.duplicate();
        new GaussianBlur().blurGaussian(blurred, 2, 2, 0.01);
        ImagePlus actual = new ImagePlus("unsharp", source.duplicate());
        assertTrue(FilterExecutor.runThreadSafe(actual,
                "run(\"Unsharp Mask...\", \"radius=2 mask=0.60 stack\");"));
        for (int i = 0; i < 81; i++) {
            float expected = (source.getf(i) - 0.6f * blurred.getf(i)) / (1f - 0.6f);
            assertEquals(expected, actual.getProcessor().getf(i), 0.0001);
        }
        assertTrue(actual.getProcessor().getf(3, 4) < 0);
    }

    @Test
    public void subtractBackgroundHonorsRecordedFlags() {
        String[] flags = {"light", "create", "sliding", "disable", "light create sliding disable"};
        for (String flag : flags) {
            FloatProcessor source = signal();
            ImageProcessor expected = source.duplicate();
            new BackgroundSubtracter().rollingBallBackground(expected, 3,
                    flag.contains("create"), flag.contains("light"),
                    flag.contains("sliding"), !flag.contains("disable"), true);
            ImagePlus actual = new ImagePlus("background", source.duplicate());
            assertTrue(FilterExecutor.runThreadSafe(actual,
                    "run(\"Subtract Background...\", \"rolling=3 " + flag + " stack\");"));
            assertPixelsEqual(expected, actual.getProcessor());
        }
    }

    @Test
    public void enhanceContrastActuallyEqualizesPixels() {
        ByteProcessor source = new ByteProcessor(6, 1, new byte[]{0, 1, 1, 1, 4, 8}, null);
        ImageProcessor expected = source.duplicate();
        new ContrastEnhancer().equalize(expected);
        ImagePlus actual = new ImagePlus("equalize", source.duplicate());
        assertTrue(FilterExecutor.runThreadSafe(actual,
                "run(\"Enhance Contrast...\", \"saturated=0 equalize\");"));
        assertPixelsEqual(expected, actual.getProcessor());
        assertNotEquals(source.get(1), actual.getProcessor().get(1));
    }

    @Test
    public void enhanceContrastUsesSharedStackHistogramWhenRecorded() {
        ImageStack source = new ImageStack(3, 1);
        source.addSlice(new ByteProcessor(3, 1, new byte[]{0, 10, 20}, null));
        source.addSlice(new ByteProcessor(3, 1, new byte[]{80, 90, 100}, null));
        ImagePlus expected = new ImagePlus("expected", source.duplicate());
        ContrastEnhancer enhancer = new ContrastEnhancer();
        enhancer.setNormalize(true);
        ij.process.ImageStatistics shared = new ij.process.StackStatistics(expected);
        for (int i = 1; i <= 2; i++) {
            enhancer.stretchHistogram(expected.getStack().getProcessor(i), 0, shared);
        }
        ImagePlus actual = new ImagePlus("actual", source.duplicate());
        assertTrue(FilterExecutor.runThreadSafe(actual,
                "run(\"Enhance Contrast...\", \"saturated=0 normalize process_all use\");"));
        for (int i = 1; i <= 2; i++) {
            assertPixelsEqual(expected.getStack().getProcessor(i), actual.getStack().getProcessor(i));
        }
        // Shared normalization expands 0..100 together; per-slice expands 0..20.
        assertEquals(51, actual.getStack().getProcessor(1).get(2));
        ImagePlus perSlice = new ImagePlus("per slice", source.duplicate());
        ContrastEnhancer individual = new ContrastEnhancer();
        individual.setNormalize(true);
        for (int i = 1; i <= 2; i++) {
            individual.stretchHistogram(perSlice.getStack().getProcessor(i), 0);
        }
        assertEquals(255, perSlice.getStack().getProcessor(1).get(2));
    }

    @Test public void sharedStackEqualizationProcessesEveryPlane() {
        ImageStack source = new ImageStack(3, 1);
        source.addSlice(new ByteProcessor(3, 1, new byte[]{0, 10, 20}, null));
        source.addSlice(new ByteProcessor(3, 1, new byte[]{80, 90, 100}, null));
        ImagePlus actual = new ImagePlus("equalize stack", source.duplicate());
        assertTrue(FilterExecutor.runThreadSafe(actual,
                "run(\"Enhance Contrast...\", \"equalize process_all use\");"));
        // Six singleton bins: weighted total=1+2*5=11. ImageJ's endpoint
        // convention gives cumulative weights 4,6,8,10 for 20,80,90,100.
        assertEquals(Math.round(255.0 * 4 / 11), actual.getStack().getProcessor(1).get(2));
        assertEquals(Math.round(255.0 * 6 / 11), actual.getStack().getProcessor(2).get(0));
        assertEquals(Math.round(255.0 * 8 / 11), actual.getStack().getProcessor(2).get(1));
        assertEquals(Math.round(255.0 * 10 / 11), actual.getStack().getProcessor(2).get(2));
    }

    @Test
    public void fileExecutionDoesNotReplayEarlierOperationsAfterFailure() throws Exception {
        File file = File.createTempFile("flash-filter-failure-", ".ijm");
        try {
            Files.write(file.toPath(), ("run(\"Add...\", \"value=3 stack\");\n"
                    + "run(\"Unsharp Mask...\", \"radius=1 mask=2 stack\");")
                    .getBytes(StandardCharsets.UTF_8));
            ImagePlus image = new ImagePlus("failed", new FloatProcessor(2, 2));
            try {
                FilterExecutor.runIjmFileThreadSafe(image, file);
                fail("Invalid operation must fail instead of replaying the macro");
            } catch (IllegalArgumentException expected) {
                assertEquals(3f, image.getProcessor().getf(0), 0f);
            }
        } finally {
            Files.deleteIfExists(file.toPath());
        }
    }

    @Test
    public void absentFilterFileCannotBeReportedAsSuccessfullyApplied() {
        File absent = new File(System.getProperty("java.io.tmpdir"),
                "flash-missing-" + System.nanoTime() + ".ijm");
        try {
            FilterExecutor.runIjmFileThreadSafe(new ImagePlus("input", signal()), absent);
            fail("Missing filter must fail");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("Cannot read filter macro"));
        }
    }

    @Test(timeout = 10000)
    public void parallelFailureWaitsUntilOtherWorkersStopMutating() {
        Assume.assumeTrue(Runtime.getRuntime().availableProcessors() >= 2);
        final CountDownLatch started = new CountDownLatch(1);
        final AtomicBoolean finished = new AtomicBoolean();
        FloatProcessor failing = new FloatProcessor(2, 2) {
            @Override public void add(double value) {
                try {
                    if (!started.await(3, TimeUnit.SECONDS)) throw new AssertionError("Worker never started");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new IllegalStateException("injected failure");
            }
        };
        FloatProcessor blocking = new FloatProcessor(2, 2) {
            @Override public void add(double value) {
                started.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException expected) {
                    // Simulate final pixel cleanup after cancellation.
                    setf(0, 123f);
                } finally {
                    finished.set(true);
                }
            }
        };
        final ImageProcessor[] processors = {failing, blocking, signal(), signal()};
        ImageStack stack = new ImageStack(2, 2) {
            @Override public ImageProcessor getProcessor(int index) { return processors[index - 1]; }
        };
        for (int i = 0; i < 4; i++) stack.addSlice(new FloatProcessor(2, 2));
        ImagePlus image = new ImagePlus("parallel failure", stack);
        try {
            FilterExecutor.runThreadSafe(image, "run(\"Add...\", \"value=1 stack\");");
            fail("Worker failure must be propagated");
        } catch (RuntimeException expected) {
            assertTrue("All active workers must finish before failure is returned", finished.get());
            assertEquals(123f, blocking.getf(0), 0f);
        }
    }

    private static FloatProcessor signal() {
        FloatProcessor result = new FloatProcessor(9, 9);
        for (int i = 0; i < 81; i++) result.setf(i, 10 + (i % 9) * 3 + (i / 9) * 2);
        result.setf(4, 4, 100f);
        return result;
    }

    private static void assertPixelsEqual(ImageProcessor expected, ImageProcessor actual) {
        assertEquals(expected.getPixelCount(), actual.getPixelCount());
        for (int i = 0; i < expected.getPixelCount(); i++) {
            assertEquals("pixel " + i, expected.getf(i), actual.getf(i), 0.0001);
        }
    }
}
