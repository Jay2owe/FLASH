package flash.pipeline.decontamination;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ShortProcessor;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ObjectDecontaminationScorerTest {

    @Test
    public void scoresNonContiguousLabelsAndRemovesRejectedObjectsWithoutRelabelling() {
        ImagePlus labels = labelImage(3, 2, new int[]{
                1, 1, 0,
                7, 7, 0
        });
        ImagePlus source = multiChannelImage(3, 2,
                new int[]{
                        100, 120, 0,
                        40, 45, 0
                },
                new int[]{
                        5, 5, 0,
                        200, 220, 0
                },
                new int[]{
                        2, 3, 0,
                        20, 25, 0
                });
        SpectralDecontaminationConfig config = baseConfig();

        ObjectDecontaminationScorer.ScoreResult result =
                ObjectDecontaminationScorer.score(
                        labels,
                        source,
                        config,
                        null,
                        new ObjectDecontaminationScorer.Settings());

        List<ObjectDecontaminationScorer.ObjectScore> scores = result.getScores();
        assertEquals(2, scores.size());
        assertEquals(1, scores.get(0).getObjectId());
        assertEquals(7, scores.get(1).getObjectId());
        assertTrue(scores.get(0).isKeepObject());
        assertFalse(scores.get(1).isKeepObject());
        assertTrue(scores.get(1).getRejectReason().contains("low_target_to_contaminant_ratio"));
        assertEquals(110.0, scores.get(0).getTargetMean(), 0.0001);
        assertEquals(120.0, scores.get(0).getTargetP99(), 0.0001);
        assertEquals(42.5 / 210.0, scores.get(1).getTargetToMaxContaminantRatio(), 0.0001);

        assertArrayEquals(new int[]{
                1, 1, 0,
                0, 0, 0
        }, labelPixels(result.getCleanedObjectMap()));
    }

    @Test
    public void includesCorrectedTargetScoreWhenAvailable() {
        ImagePlus labels = labelImage(2, 1, new int[]{1, 1});
        ImagePlus source = multiChannelImage(2, 1,
                new int[]{100, 120},
                new int[]{5, 5});
        ImagePlus corrected = singleChannelImage(2, 1, new int[]{80, 100});
        SpectralDecontaminationConfig config = new SpectralDecontaminationConfig();
        config.setTargetChannelIndex(0);
        config.setBleedThroughChannelIndexes(Arrays.asList(Integer.valueOf(1)));

        ObjectDecontaminationScorer.ScoreResult result =
                ObjectDecontaminationScorer.score(
                        labels,
                        source,
                        config,
                        corrected,
                        new ObjectDecontaminationScorer.Settings());

        ObjectDecontaminationScorer.ObjectScore score = result.getScores().get(0);
        assertEquals(90.0, score.getCorrectedTargetMean(), 0.0001);
        assertEquals(100.0, score.getCorrectedTargetP99(), 0.0001);
        assertEquals(90.0 / 110.0, score.getCorrectedTargetRetentionFraction(), 0.0001);
    }

    @Test
    public void cleanedObjectMapCopyPreservesStackMetadataWithoutMutatingLabels() {
        ImagePlus labels = labelImage(2, 1, new int[]{1, 2}, new int[]{3, 4});
        ImagePlus source = multiChannelImage(2, 1, 2,
                new int[][]{
                        {100, 10},
                        {100, 100}
                },
                new int[][]{
                        {1, 200},
                        {1, 1}
                });
        Calibration calibration = new Calibration();
        calibration.pixelWidth = 0.5;
        labels.setCalibration(calibration);
        SpectralDecontaminationConfig config = new SpectralDecontaminationConfig();
        config.setTargetChannelIndex(0);
        config.setBleedThroughChannelIndexes(Arrays.asList(Integer.valueOf(1)));

        ImagePlus cleaned = ObjectDecontaminationScorer.score(
                        labels,
                        source,
                        config,
                        null,
                        new ObjectDecontaminationScorer.Settings())
                .getCleanedObjectMap();

        assertEquals(2, cleaned.getNSlices());
        assertEquals(0.5, cleaned.getCalibration().pixelWidth, 0.0);
        assertArrayEquals(new int[]{1, 2}, labelPixels(labels, 1));
        assertArrayEquals(new int[]{1, 0}, labelPixels(cleaned, 1));
        assertArrayEquals(new int[]{3, 4}, labelPixels(cleaned, 2));
    }

    private static SpectralDecontaminationConfig baseConfig() {
        SpectralDecontaminationConfig config = new SpectralDecontaminationConfig();
        config.setTargetChannelIndex(0);
        config.setBleedThroughChannelIndexes(Arrays.asList(Integer.valueOf(1)));
        config.setAutofluorescenceChannelIndexes(Arrays.asList(Integer.valueOf(2)));
        return config;
    }

    private static ImagePlus multiChannelImage(int width, int height, int[]... channels) {
        ImageStack stack = new ImageStack(width, height);
        for (int[] channel : channels) {
            stack.addSlice(new ShortProcessor(width, height, toShorts(channel), null));
        }
        ImagePlus image = new ImagePlus("source", stack);
        image.setDimensions(channels.length, 1, 1);
        return image;
    }

    private static ImagePlus multiChannelImage(int width, int height, int slices, int[][]... channelPlanes) {
        ImageStack stack = new ImageStack(width, height);
        for (int slice = 0; slice < slices; slice++) {
            for (int channel = 0; channel < channelPlanes.length; channel++) {
                stack.addSlice(new ShortProcessor(width, height,
                        toShorts(channelPlanes[channel][slice]), null));
            }
        }
        ImagePlus image = new ImagePlus("source", stack);
        image.setDimensions(channelPlanes.length, slices, 1);
        return image;
    }

    private static ImagePlus singleChannelImage(int width, int height, int[] pixels) {
        ImageStack stack = new ImageStack(width, height);
        stack.addSlice(new ShortProcessor(width, height, toShorts(pixels), null));
        ImagePlus image = new ImagePlus("corrected", stack);
        image.setDimensions(1, 1, 1);
        return image;
    }

    private static ImagePlus labelImage(int width, int height, int[] pixels) {
        return labelImage(width, height, new int[][]{pixels});
    }

    private static ImagePlus labelImage(int width, int height, int[]... planes) {
        ImageStack stack = new ImageStack(width, height);
        for (int[] pixels : planes) {
            stack.addSlice(new ShortProcessor(width, height, toShorts(pixels), null));
        }
        ImagePlus image = new ImagePlus("labels", stack);
        image.setDimensions(1, planes.length, 1);
        return image;
    }

    private static short[] toShorts(int[] values) {
        short[] out = new short[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (short) values[i];
        }
        return out;
    }

    private static int[] labelPixels(ImagePlus image) {
        return labelPixels(image, 1);
    }

    private static int[] labelPixels(ImagePlus image, int slice) {
        int[] pixels = new int[image.getWidth() * image.getHeight()];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = image.getStack().getProcessor(slice).get(i);
        }
        return pixels;
    }
}
