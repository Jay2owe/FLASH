package flash.pipeline.image;

import ij.CompositeImage;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Overlay;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.LUT;
import ij.process.ShortProcessor;
import org.junit.Test;

import java.awt.Color;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ImageOpsTest {

    @Test
    public void duplicateThreadSafeIgnoresExistingProcessorRoi() {
        ByteProcessor bp = new ByteProcessor(4, 4);
        bp.set(0, 0, 11);
        bp.set(3, 3, 99);
        ProcessorBackedImageStack stack = new ProcessorBackedImageStack(4, 4);
        stack.addSourceSlice("source", bp);
        ImagePlus source = new ImagePlus("source", stack);
        source.setRoi(1, 1, 2, 2);
        source.getImageStack().setRoi(new Rectangle(1, 1, 2, 2));
        bp.setRoi(1, 1, 2, 2);

        ImagePlus duplicate = ImageOps.duplicateThreadSafe(source);

        assertEquals(4, duplicate.getWidth());
        assertEquals(4, duplicate.getHeight());
        assertEquals(11, duplicate.getProcessor().get(0, 0));
        assertEquals(99, duplicate.getProcessor().get(3, 3));
        assertEquals("source ROI must remain unchanged", 2,
                source.getProcessor().getRoi().width);
        duplicate.flush();
        source.flush();
    }

    @Test
    public void concurrentCopiesPreserveFullPixelsRoisAndImageMetadata()
            throws Exception {
        final CompositeImage source = metadataRichSource();
        Rectangle expectedNonFullRoi = new Rectangle(2, 1, 3, 2);
        assertEquals("concurrency fixture must carry a non-full ImagePlus ROI",
                expectedNonFullRoi, source.getRoi().getBounds());
        assertEquals("concurrency fixture must carry a non-full stack ROI",
                expectedNonFullRoi, source.getImageStack().getRoi());
        for (int index = 1; index <= source.getStackSize(); index++) {
            assertEquals("concurrency fixture processor ROI " + index,
                    expectedNonFullRoi,
                    source.getImageStack().getProcessor(index).getRoi());
        }
        final SourceSnapshot before = SourceSnapshot.capture(source);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        final CountDownLatch start = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<Future<Void>>();

        try {
            for (int worker = 0; worker < 2; worker++) {
                futures.add(executor.submit(new Callable<Void>() {
                    @Override
                    public Void call() throws Exception {
                        assertTrue("copy workers did not start together",
                                start.await(5L, TimeUnit.SECONDS));
                        for (int iteration = 0; iteration < 75; iteration++) {
                            ImagePlus duplicate = ImageOps.duplicateThreadSafe(source);
                            try {
                                assertDuplicateMatches(source, before, duplicate);
                            } finally {
                                duplicate.flush();
                            }
                        }
                        return null;
                    }
                }));
            }
            start.countDown();
            for (Future<Void> future : futures) {
                future.get(20L, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
            assertTrue("copy executor did not terminate",
                    executor.awaitTermination(5L, TimeUnit.SECONDS));
        }

        before.assertSourceUnchanged(source);
        assertEquals("source image must remain open", 8, source.getStackSize());
        source.flush();
    }

    @Test
    public void privateProcessorCopyPreservesSupportedPixelTypes() {
        ImageProcessor[] processors = new ImageProcessor[] {
                new ByteProcessor(3, 2),
                new ShortProcessor(3, 2),
                new FloatProcessor(3, 2),
                new ColorProcessor(3, 2)
        };
        int[] expectedDepths = new int[] {8, 16, 32, 24};

        for (int type = 0; type < processors.length; type++) {
            ImageProcessor sourceProcessor = processors[type];
            for (int i = 0; i < sourceProcessor.getPixelCount(); i++) {
                int value = type == 3
                        ? (0xff000000 | ((i * 31) << 16) | ((i * 17) << 8) | i)
                        : i * 101 + type;
                sourceProcessor.set(i, value);
            }
            ProcessorBackedImageStack stack = new ProcessorBackedImageStack(3, 2);
            stack.addSourceSlice("type-" + expectedDepths[type], sourceProcessor);
            ImagePlus source = new ImagePlus("type-" + expectedDepths[type], stack);
            source.setRoi(1, 0, 1, 2);
            source.getImageStack().setRoi(new Rectangle(1, 0, 1, 2));
            sourceProcessor.setRoi(1, 0, 1, 2);
            ImageProcessor actualSource = source.getImageStack().getProcessor(1);
            Rectangle sourceRoi = new Rectangle(actualSource.getRoi());
            int[] sourcePixels = pixelsOf(actualSource);

            ImagePlus duplicate = ImageOps.duplicateThreadSafe(source);
            try {
                assertEquals(expectedDepths[type], duplicate.getBitDepth());
                assertEquals(sourcePixels.length, duplicate.getProcessor().getPixelCount());
                for (int i = 0; i < sourcePixels.length; i++) {
                    assertEquals("pixel " + i + " at bit depth " + expectedDepths[type],
                            sourcePixels[i], duplicate.getProcessor().get(i));
                }
                assertEquals(sourceRoi, actualSource.getRoi());
                assertNotSame(actualSource, duplicate.getProcessor());
            } finally {
                duplicate.flush();
                source.flush();
            }
        }
    }

    @Test
    public void subrangeCopyKeepsTheSelectedCompositeChannelMetadata() {
        CompositeImage source = metadataRichSource();
        SourceSnapshot before = SourceSnapshot.capture(source);

        ImagePlus duplicate = ImageOps.duplicateThreadSafe(
                source, 2, 2, 1, 2, 1, 2);
        try {
            assertFalse("one-channel subset should not retain a composite wrapper",
                    duplicate instanceof CompositeImage);
            assertEquals(1, duplicate.getNChannels());
            assertEquals(2, duplicate.getNSlices());
            assertEquals(2, duplicate.getNFrames());
            assertEquals(4, duplicate.getStackSize());
            assertEquals(22.0d, duplicate.getDisplayRangeMin(), 0.0d);
            assertEquals(2345.0d, duplicate.getDisplayRangeMax(), 0.0d);
            assertEquals(Color.GREEN.getRGB(),
                    duplicate.getProcessor().getColorModel().getRGB(255));

            int outputIndex = 1;
            for (int t = 1; t <= 2; t++) {
                for (int z = 1; z <= 2; z++) {
                    int sourceIndex = source.getStackIndex(2, z, t);
                    assertEquals(source.getStack().getSliceLabel(sourceIndex),
                            duplicate.getStack().getSliceLabel(outputIndex));
                    assertEquals(source.getStack().getProcessor(sourceIndex).get(0),
                            duplicate.getStack().getProcessor(outputIndex).get(0));
                    outputIndex++;
                }
            }
            assertTrue("channel-1 overlay must not leak into channel-2 subset",
                    duplicate.getOverlay() == null || duplicate.getOverlay().size() == 0);
        } finally {
            duplicate.flush();
        }
        before.assertSourceUnchanged(source);
        source.flush();
    }

    @Test
    public void cropFailureClosesPartialCopyExactlyOnceAndNeverTouchesSource() {
        ByteProcessor first = new ByteProcessor(4, 3);
        first.set(0, 0, 17);
        first.set(3, 2, 91);
        first.setRoi(1, 1, 2, 1);
        FailingCropSourceProcessor second = new FailingCropSourceProcessor(
                4, 3, new IllegalStateException("injected private crop failure"));
        second.set(0, 0, 23);
        second.set(3, 2, 77);
        second.setRoi(0, 1, 3, 2);

        ProcessorBackedImageStack stack = new ProcessorBackedImageStack(4, 3);
        stack.addSourceSlice("first", first);
        stack.addSourceSlice("failing", second);
        TrackingImagePlus source = new TrackingImagePlus("source", stack);
        source.setDimensions(1, 2, 1);
        first.setRoi(1, 1, 2, 1);
        second.setRoi(0, 1, 3, 2);
        final SourceSnapshot before = SourceSnapshot.capture(source);
        final AtomicReference<TrackingImagePlus> partial =
                new AtomicReference<TrackingImagePlus>();

        try {
            ImageOps.duplicateThreadSafe(source, 1, 1, 1, 2, 1, 1,
                    new ImageOps.OutputImageFactory() {
                        @Override
                        public ImagePlus create(String title, ImageStack partialStack) {
                            TrackingImagePlus created =
                                    new TrackingImagePlus(title, partialStack);
                            partial.set(created);
                            return created;
                        }
                    });
            fail("injected crop failure should escape");
        } catch (IllegalStateException expected) {
            assertEquals("injected private crop failure", expected.getMessage());
        }

        assertNotNull("one-plane partial output was constructed", partial.get());
        assertEquals("partial output close count", 1, partial.get().closeCount.get());
        assertEquals("partial output flush count", 1, partial.get().flushCount.get());
        assertEquals("caller-owned source close count", 0, source.closeCount.get());
        assertEquals("caller-owned source flush count", 0, source.flushCount.get());
        before.assertSourceUnchanged(source);
        assertEquals(2, source.getStackSize());
        source.flush();
    }

    @Test
    public void sameFailureFromCropCloseAndFlushNeverMasksPrimary() {
        final IllegalStateException sharedFailure =
                new IllegalStateException("same injected failure");
        ByteProcessor first = new ByteProcessor(3, 2);
        first.setRoi(1, 0, 1, 2);
        FailingCropSourceProcessor second = new FailingCropSourceProcessor(
                3, 2, sharedFailure);
        second.setRoi(0, 0, 2, 1);
        ProcessorBackedImageStack stack = new ProcessorBackedImageStack(3, 2);
        stack.addSourceSlice("first", first);
        stack.addSourceSlice("failing", second);
        final ImagePlus source = new ImagePlus("source", stack);
        source.setDimensions(1, 2, 1);
        first.setRoi(1, 0, 1, 2);
        second.setRoi(0, 0, 2, 1);
        final AtomicReference<TrackingImagePlus> partial =
                new AtomicReference<TrackingImagePlus>();

        RuntimeException observed = null;
        try {
            ImageOps.duplicateThreadSafe(source, 1, 1, 1, 2, 1, 1,
                    new ImageOps.OutputImageFactory() {
                        @Override
                        public ImagePlus create(String title, ImageStack partialStack) {
                            TrackingImagePlus created = new TrackingImagePlus(
                                    title, partialStack, sharedFailure, sharedFailure);
                            partial.set(created);
                            return created;
                        }
                    });
            fail("shared crop/cleanup failure should escape");
        } catch (RuntimeException failure) {
            observed = failure;
        }

        assertSame("cleanup must not replace the crop failure", sharedFailure, observed);
        assertEquals(0, observed.getSuppressed().length);
        assertNotNull(partial.get());
        assertEquals(1, partial.get().closeCount.get());
        assertEquals(1, partial.get().flushCount.get());
        assertEquals(new Rectangle(1, 0, 1, 2), first.getRoi());
        assertEquals(new Rectangle(0, 0, 2, 1), second.getRoi());
        assertEquals(2, source.getStackSize());
        source.flush();
    }

    @Test
    public void invalidRangeFailsBeforeOutputAllocation() {
        final AtomicInteger factoryCalls = new AtomicInteger();
        ImagePlus source = new ImagePlus("source", new ByteProcessor(2, 2));
        try {
            ImageOps.duplicateThreadSafe(source, 1, 2, 1, 1, 1, 1,
                    new ImageOps.OutputImageFactory() {
                        @Override
                        public ImagePlus create(String title, ImageStack stack) {
                            factoryCalls.incrementAndGet();
                            return new ImagePlus(title, stack);
                        }
                    });
            fail("invalid channel range should fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("channel range"));
        }
        assertEquals(0, factoryCalls.get());
        assertEquals(1, source.getStackSize());
        source.flush();
    }

    private static CompositeImage metadataRichSource() {
        int width = 7;
        int height = 5;
        int channels = 2;
        int slices = 2;
        int frames = 2;
        ProcessorBackedImageStack stack = new ProcessorBackedImageStack(width, height);
        for (int index = 1; index <= channels * slices * frames; index++) {
            ShortProcessor processor = new ShortProcessor(width, height);
            for (int pixel = 0; pixel < processor.getPixelCount(); pixel++) {
                processor.set(pixel, index * 1000 + pixel);
            }
            processor.setRoi(index % 3, index % 2, 3, 2);
            stack.addSourceSlice("plane-" + index, processor);
        }

        ImagePlus base = new ImagePlus("metadata-source", stack);
        base.setDimensions(channels, slices, frames);
        base.setOpenAsHyperStack(true);
        CompositeImage source = new CompositeImage(base, CompositeImage.COMPOSITE);
        source.setDimensions(channels, slices, frames);
        source.setOpenAsHyperStack(true);
        source.setPositionWithoutUpdate(2, 2, 2);

        Calibration calibration = new Calibration();
        calibration.pixelWidth = 0.41d;
        calibration.pixelHeight = 0.52d;
        calibration.pixelDepth = 1.73d;
        calibration.frameInterval = 2.25d;
        calibration.setUnit("micron");
        source.setCalibration(calibration);
        source.setProperty("Info", "instrument metadata");
        source.setProperty("flash-test-property", "preserved value");

        LUT red = LUT.createLutFromColor(Color.RED);
        red.min = 11.0d;
        red.max = 1234.0d;
        LUT green = LUT.createLutFromColor(Color.GREEN);
        green.min = 22.0d;
        green.max = 2345.0d;
        source.setLuts(new LUT[] {red, green});

        Roi annotation = new Roi(1, 1, 2, 2);
        annotation.setName("annotation");
        annotation.setPosition(1, 2, 1);
        source.setOverlay(new Overlay(annotation));
        source.setHideOverlay(true);
        source.setRoi(2, 1, 3, 2);
        source.getImageStack().setRoi(new Rectangle(2, 1, 3, 2));
        for (int index = 1; index <= source.getStackSize(); index++) {
            source.getImageStack().getProcessor(index).setRoi(2, 1, 3, 2);
        }
        return source;
    }

    private static void assertDuplicateMatches(
            CompositeImage source, SourceSnapshot expected, ImagePlus duplicate) {
        assertNotNull(duplicate);
        assertNotSame(source, duplicate);
        assertTrue("composite metadata", duplicate instanceof CompositeImage);
        assertEquals(expected.width, duplicate.getWidth());
        assertEquals(expected.height, duplicate.getHeight());
        assertEquals(expected.pixels.length, duplicate.getStackSize());
        assertEquals(expected.channels, duplicate.getNChannels());
        assertEquals(expected.slices, duplicate.getNSlices());
        assertEquals(expected.frames, duplicate.getNFrames());
        assertTrue(duplicate.getOpenAsHyperStack());
        assertEquals(source.getC(), duplicate.getC());
        assertEquals(source.getZ(), duplicate.getZ());
        assertEquals(source.getT(), duplicate.getT());
        assertEquals("instrument metadata", duplicate.getProperty("Info"));
        assertEquals("preserved value", duplicate.getProperty("flash-test-property"));

        assertNotSame(source.getCalibration(), duplicate.getCalibration());
        assertEquals(source.getCalibration().pixelWidth,
                duplicate.getCalibration().pixelWidth, 0.0d);
        assertEquals(source.getCalibration().pixelHeight,
                duplicate.getCalibration().pixelHeight, 0.0d);
        assertEquals(source.getCalibration().pixelDepth,
                duplicate.getCalibration().pixelDepth, 0.0d);
        assertEquals(source.getCalibration().frameInterval,
                duplicate.getCalibration().frameInterval, 0.0d);
        assertEquals(source.getCalibration().getUnit(),
                duplicate.getCalibration().getUnit());

        LUT[] sourceLuts = source.getLuts();
        LUT[] duplicateLuts = duplicate.getLuts();
        assertEquals(sourceLuts.length, duplicateLuts.length);
        for (int channel = 0; channel < sourceLuts.length; channel++) {
            assertNotSame(sourceLuts[channel], duplicateLuts[channel]);
            assertEquals(sourceLuts[channel].min, duplicateLuts[channel].min, 0.0d);
            assertEquals(sourceLuts[channel].max, duplicateLuts[channel].max, 0.0d);
            assertEquals(sourceLuts[channel].getRGB(255),
                    duplicateLuts[channel].getRGB(255));
        }

        assertNotNull(duplicate.getOverlay());
        assertNotSame(source.getOverlay(), duplicate.getOverlay());
        assertEquals(1, duplicate.getOverlay().size());
        assertEquals("annotation", duplicate.getOverlay().get(0).getName());
        assertTrue(duplicate.getHideOverlay());

        for (int index = 1; index <= expected.pixels.length; index++) {
            assertEquals(expected.labels[index - 1],
                    duplicate.getStack().getSliceLabel(index));
            ImageProcessor actual = duplicate.getStack().getProcessor(index);
            assertNotSame(source.getStack().getProcessor(index), actual);
            assertEquals(new Rectangle(0, 0, expected.width, expected.height),
                    actual.getRoi());
            for (int pixel = 0; pixel < expected.pixels[index - 1].length; pixel++) {
                assertEquals("plane " + index + " pixel " + pixel,
                        expected.pixels[index - 1][pixel], actual.get(pixel));
            }
        }
    }

    private static int[] pixelsOf(ImageProcessor processor) {
        int[] pixels = new int[processor.getPixelCount()];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = processor.get(i);
        }
        return pixels;
    }

    private static final class SourceSnapshot {
        private final int width;
        private final int height;
        private final int channels;
        private final int slices;
        private final int frames;
        private final int[][] pixels;
        private final Rectangle[] rois;
        private final String[] labels;
        private final Calibration calibration;
        private final int c;
        private final int z;
        private final int t;

        private SourceSnapshot(ImagePlus source) {
            width = source.getWidth();
            height = source.getHeight();
            channels = source.getNChannels();
            slices = source.getNSlices();
            frames = source.getNFrames();
            pixels = new int[source.getStackSize()][];
            rois = new Rectangle[source.getStackSize()];
            labels = new String[source.getStackSize()];
            for (int index = 1; index <= source.getStackSize(); index++) {
                ImageProcessor processor = source.getStack().getProcessor(index);
                pixels[index - 1] = pixelsOf(processor);
                rois[index - 1] = new Rectangle(processor.getRoi());
                labels[index - 1] = source.getStack().getSliceLabel(index);
            }
            calibration = source.getCalibration().copy();
            c = source.getC();
            z = source.getZ();
            t = source.getT();
        }

        static SourceSnapshot capture(ImagePlus source) {
            return new SourceSnapshot(source);
        }

        void assertSourceUnchanged(ImagePlus source) {
            assertEquals(width, source.getWidth());
            assertEquals(height, source.getHeight());
            assertEquals(channels, source.getNChannels());
            assertEquals(slices, source.getNSlices());
            assertEquals(frames, source.getNFrames());
            assertEquals(c, source.getC());
            assertEquals(z, source.getZ());
            assertEquals(t, source.getT());
            assertEquals(calibration.pixelWidth,
                    source.getCalibration().pixelWidth, 0.0d);
            assertEquals(calibration.pixelHeight,
                    source.getCalibration().pixelHeight, 0.0d);
            assertEquals(calibration.pixelDepth,
                    source.getCalibration().pixelDepth, 0.0d);
            for (int index = 1; index <= pixels.length; index++) {
                ImageProcessor processor = source.getStack().getProcessor(index);
                assertEquals(rois[index - 1], processor.getRoi());
                assertEquals(labels[index - 1], source.getStack().getSliceLabel(index));
                assertEquals(pixels[index - 1].length, processor.getPixelCount());
                for (int pixel = 0; pixel < pixels[index - 1].length; pixel++) {
                    assertEquals("source plane " + index + " pixel " + pixel,
                            pixels[index - 1][pixel], processor.get(pixel));
                }
            }
        }
    }

    private static final class FailingCropSourceProcessor extends ByteProcessor {
        private final RuntimeException failure;

        FailingCropSourceProcessor(int width, int height, RuntimeException failure) {
            super(width, height);
            this.failure = failure;
        }

        @Override
        public ImageProcessor duplicate() {
            final ByteProcessor realPrivateCopy = (ByteProcessor) super.duplicate();
            return new ByteProcessor(getWidth(), getHeight(),
                    (byte[]) realPrivateCopy.getPixels(), realPrivateCopy.getColorModel()) {
                @Override
                public ImageProcessor crop() {
                    throw failure;
                }
            };
        }
    }

    /** ImageStack normally rehydrates processors from pixels and loses subclasses. */
    private static final class ProcessorBackedImageStack extends ImageStack {
        private final List<ImageProcessor> sourceProcessors =
                new ArrayList<ImageProcessor>();

        ProcessorBackedImageStack(int width, int height) {
            super(width, height);
        }

        void addSourceSlice(String label, ImageProcessor processor) {
            super.addSlice(label, processor);
            sourceProcessors.add(processor);
        }

        @Override
        public ImageProcessor getProcessor(int index) {
            return sourceProcessors.get(index - 1);
        }
    }

    private static final class TrackingImagePlus extends ImagePlus {
        private final AtomicInteger closeCount = new AtomicInteger();
        private final AtomicInteger flushCount = new AtomicInteger();
        private final RuntimeException closeFailure;
        private final RuntimeException flushFailure;

        TrackingImagePlus(String title, ImageStack stack) {
            this(title, stack, null, null);
        }

        TrackingImagePlus(String title, ImageStack stack,
                          RuntimeException closeFailure,
                          RuntimeException flushFailure) {
            super(title, stack);
            this.closeFailure = closeFailure;
            this.flushFailure = flushFailure;
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
            super.close();
            if (closeFailure != null) throw closeFailure;
        }

        @Override
        public void flush() {
            flushCount.incrementAndGet();
            super.flush();
            if (flushFailure != null) throw flushFailure;
        }
    }
}
