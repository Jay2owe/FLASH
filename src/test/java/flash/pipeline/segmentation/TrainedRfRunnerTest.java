package flash.pipeline.segmentation;

import flash.pipeline.click.training.ObjectClassifierTrainer;
import flash.pipeline.click.training.ObjectFeatureExtractor;
import flash.pipeline.objects.ObjectsCounter3DWrapper;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class TrainedRfRunnerTest {
    private static final String[] NAMES = new String[] {"volume"};

    @Before
    public void requireMcib3d() {
        Assume.assumeTrue(ObjectsCounter3DWrapper.isMcib3dAvailable());
    }

    @Test
    public void runWithRfFiltersBaseOutput() {
        ObjectClassifierTrainer.TrainingResult trained =
                new ObjectClassifierTrainer().train(rows(30, true), rows(30, false), 23);
        final CloseTrackingImagePlus baseLabels = closeTrackedFiveLabelImage(false);
        ImagePlus raw = rawLike(baseLabels);

        TrainedRfParameters params = new TrainedRfParameters(
                "test_rf",
                null,
                null,
                trained.model,
                trained.featureNames,
                SegmentationMethod.classical("classical"),
                0.5,
                1,
                1,
                Integer.MAX_VALUE,
                raw,
                null,
                new TrainedRfRunner.BaseRunner() {
                    @Override
                    public ImagePlus run(ImagePlus channelImage, TrainedRfParameters params) {
                        return baseLabels;
                    }
                },
                null);

        ImagePlus filtered = new TrainedRfRunner().run(raw, params);

        assertEquals(new HashSet<Integer>(Arrays.asList(
                Integer.valueOf(1), Integer.valueOf(2), Integer.valueOf(3))),
                labelsIn(filtered));
        assertNotNull(filtered.getProperty(TrainedRfRunner.OBJECT_STATS_PROPERTY));
        assertNotSame(baseLabels, filtered);
        assertNotNull("returned output remains usable after owned base cleanup", filtered.getStack());
        assertEquals("owned base closes exactly once on success", 1, baseLabels.closeCalls);
    }

    @Test
    public void nestedTrainedRfBaseLogsWarningAndReturnsBaseUnchanged() {
        final CloseTrackingImagePlus baseLabels = closeTrackedFiveLabelImage(false);
        ImagePlus raw = rawLike(baseLabels);
        final List<String> warnings = new ArrayList<String>();
        TrainedRfParameters params = new TrainedRfParameters(
                "unused",
                null,
                null,
                null,
                null,
                SegmentationTokenParser.parse("trained_rf:inner_rf:base=classical"),
                0.5,
                1,
                1,
                Integer.MAX_VALUE,
                raw,
                null,
                new TrainedRfRunner.BaseRunner() {
                    @Override
                    public ImagePlus run(ImagePlus channelImage, TrainedRfParameters params) {
                        return baseLabels;
                    }
                },
                new TrainedRfRunner.WarningSink() {
                    @Override
                    public void warn(String message) {
                        warnings.add(message);
                    }
                });

        ImagePlus result = new TrainedRfRunner().run(raw, params);

        assertSame("unchanged base becomes the caller-owned output", baseLabels, result);
        assertEquals("returned output must not be closed", 0, baseLabels.closeCalls);
        assertSamePixels(baseLabels, result);
        assertFalse(warnings.isEmpty());
        assertTrue(warnings.get(0).contains("cannot use another Trained RF"));
    }

    @Test
    public void missingModelFailureClosesCallbackBaseExactlyOnce() {
        final CloseTrackingImagePlus baseLabels = closeTrackedFiveLabelImage(false);
        ImagePlus raw = rawLike(baseLabels);
        TrainedRfParameters params = parameters(
                null, null, raw, baseLabels);

        try {
            new TrainedRfRunner().run(raw, params);
            fail("Expected missing RF model failure.");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("No Smile RF model"));
        }

        assertEquals("owned callback base closes on model-load failure", 1, baseLabels.closeCalls);
    }

    @Test
    public void callbackBaseClosesWhenFeatureExtractionFails() {
        ObjectClassifierTrainer.TrainingResult trained =
                new ObjectClassifierTrainer().train(rows(30, true), rows(30, false), 29);
        final CloseTrackingImagePlus baseLabels = closeTrackedFiveLabelImage(false);
        ImagePlus raw = rawLike(baseLabels);
        ImagePlus wrongGeometry = new ImagePlus("wrong geometry", new FloatProcessor(1, 1));
        TrainedRfParameters params = parameters(
                trained.model, trained.featureNames, wrongGeometry, baseLabels);

        try {
            new TrainedRfRunner().run(raw, params);
            fail("Expected registered-geometry failure.");
        } catch (ObjectFeatureExtractor.GeometryMismatchException expected) {
            assertTrue(expected.getMessage().contains("registration mismatch"));
        }

        assertEquals("owned callback base closes on extraction failure", 1, baseLabels.closeCalls);
    }

    @Test
    public void cleanupFailureIsSuppressedWithoutMaskingPrimaryFailure() {
        final CloseTrackingImagePlus baseLabels = closeTrackedFiveLabelImage(true);
        ImagePlus raw = rawLike(baseLabels);
        TrainedRfParameters params = parameters(
                null, null, raw, baseLabels);

        try {
            new TrainedRfRunner().run(raw, params);
            fail("Expected missing RF model failure.");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("No Smile RF model"));
            assertEquals(1, expected.getSuppressed().length);
            assertTrue(expected.getSuppressed()[0].getMessage().contains("injected close failure"));
        }

        assertEquals("failing cleanup is still attempted once", 1, baseLabels.closeCalls);
    }

    @Test
    public void fatalCleanupFailurePromotesOverNonfatalPrimary() {
        final CloseTrackingImagePlus baseLabels = closeTrackedFatalFiveLabelImage();
        ImagePlus raw = rawLike(baseLabels);
        TrainedRfParameters params = parameters(
                null, null, raw, baseLabels);

        try {
            new TrainedRfRunner().run(raw, params);
            fail("Expected injected fatal cleanup failure.");
        } catch (OutOfMemoryError expected) {
            assertTrue(expected.getMessage().contains("injected fatal close failure"));
            assertEquals(1, expected.getSuppressed().length);
            assertTrue(expected.getSuppressed()[0] instanceof IllegalArgumentException);
            assertTrue(expected.getSuppressed()[0].getMessage().contains("No Smile RF model"));
        }

        assertEquals("fatal cleanup is attempted exactly once", 1, baseLabels.closeCalls);
    }

    private static TrainedRfParameters parameters(
            final smile.classification.RandomForest model,
            String[] featureNames,
            ImagePlus intensity,
            final ImagePlus baseLabels) {
        return new TrainedRfParameters(
                "ownership_test",
                null,
                null,
                model,
                featureNames,
                SegmentationMethod.classical("classical"),
                0.5,
                1,
                1,
                Integer.MAX_VALUE,
                intensity,
                null,
                new TrainedRfRunner.BaseRunner() {
                    @Override
                    public ImagePlus run(ImagePlus channelImage, TrainedRfParameters params) {
                        return baseLabels;
                    }
                },
                null);
    }

    private static List<ObjectFeatureExtractor.FeatureRow> rows(int count, boolean positive) {
        List<ObjectFeatureExtractor.FeatureRow> rows = new ArrayList<ObjectFeatureExtractor.FeatureRow>();
        for (int i = 0; i < count; i++) {
            double volume = positive ? 3 + (i % 5) : 1 + (i % 2);
            rows.add(new ObjectFeatureExtractor.FeatureRow(i + 1, new double[] {volume}, NAMES));
        }
        return rows;
    }

    private static ImagePlus fiveLabelImage() {
        int width = 20;
        ByteProcessor bp = new ByteProcessor(width, 1);
        int x = 0;
        for (int label = 1; label <= 5; label++) {
            for (int i = 0; i < label; i++) {
                bp.set(x++, 0, label);
            }
            x++;
        }
        ImageStack stack = new ImageStack(width, 1);
        stack.addSlice(bp);
        return new ImagePlus("base-labels", stack);
    }

    private static CloseTrackingImagePlus closeTrackedFiveLabelImage(boolean failOnClose) {
        ImagePlus labels = fiveLabelImage();
        return new CloseTrackingImagePlus(
                labels.getTitle(), labels.getStack(), failOnClose, false);
    }

    private static CloseTrackingImagePlus closeTrackedFatalFiveLabelImage() {
        ImagePlus labels = fiveLabelImage();
        return new CloseTrackingImagePlus(
                labels.getTitle(), labels.getStack(), false, true);
    }

    private static ImagePlus rawLike(ImagePlus labels) {
        FloatProcessor fp = new FloatProcessor(labels.getWidth(), labels.getHeight());
        for (int i = 0; i < fp.getPixelCount(); i++) {
            fp.setf(i, 100.0f);
        }
        ImageStack stack = new ImageStack(labels.getWidth(), labels.getHeight());
        stack.addSlice(fp);
        return new ImagePlus("raw", stack);
    }

    private static Set<Integer> labelsIn(ImagePlus image) {
        Set<Integer> out = new HashSet<Integer>();
        ImageProcessor ip = image.getProcessor();
        for (int i = 0; i < ip.getPixelCount(); i++) {
            int label = Math.round(ip.getf(i));
            if (label > 0) out.add(Integer.valueOf(label));
        }
        return out;
    }

    private static void assertSamePixels(ImagePlus expected, ImagePlus actual) {
        assertNotNull(expected);
        assertNotNull(actual);
        assertEquals(expected.getWidth(), actual.getWidth());
        assertEquals(expected.getHeight(), actual.getHeight());
        ImageProcessor e = expected.getProcessor();
        ImageProcessor a = actual.getProcessor();
        for (int i = 0; i < e.getPixelCount(); i++) {
            assertEquals(e.getf(i), a.getf(i), 0.0);
        }
    }

    private static final class CloseTrackingImagePlus extends ImagePlus {
        private final boolean failOnClose;
        private final boolean fatalOnClose;
        int closeCalls;

        CloseTrackingImagePlus(String title, ImageStack stack,
                               boolean failOnClose, boolean fatalOnClose) {
            super(title, stack);
            this.failOnClose = failOnClose;
            this.fatalOnClose = fatalOnClose;
        }

        @Override
        public void close() {
            closeCalls++;
            if (fatalOnClose) {
                throw new OutOfMemoryError("injected fatal close failure");
            }
            if (failOnClose) {
                throw new IllegalStateException("injected close failure");
            }
            super.close();
        }
    }
}
