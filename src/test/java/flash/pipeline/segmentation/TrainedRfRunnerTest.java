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
        final ImagePlus baseLabels = fiveLabelImage();
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
    }

    @Test
    public void nestedTrainedRfBaseLogsWarningAndReturnsBaseUnchanged() {
        final ImagePlus baseLabels = fiveLabelImage();
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

        assertSamePixels(baseLabels, result);
        assertFalse(warnings.isEmpty());
        assertTrue(warnings.get(0).contains("cannot use another Trained RF"));
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
}
