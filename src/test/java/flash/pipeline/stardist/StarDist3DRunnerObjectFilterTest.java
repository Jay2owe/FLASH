package flash.pipeline.stardist;

import fiji.plugin.trackmate.Settings;
import fiji.plugin.trackmate.detection.DetectorKeys;
import fiji.plugin.trackmate.stardist.StarDistCustomDetectorFactory;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.process.ShortProcessor;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class StarDist3DRunnerObjectFilterTest {

    @Test
    public void applyObjectFiltersRemovesLabelsByStarDistObjectMetrics() {
        ImagePlus labels = labelImage(new int[] {1, 2, 2, 2});
        ResultsTable stats = objectStats();

        int removed = StarDist3DRunner.applyObjectFilters(labels, stats,
                5, 10, 0.5, 50);

        assertEquals(2, removed);
        assertEquals(0, labels.getProcessor().get(0, 0));
        assertEquals(0, labels.getProcessor().get(1, 0));
        assertEquals(0, StarDist3DRunner.countLabels(labels));
    }

    @Test
    public void countLabelsCountsDistinctPositiveLabelsRatherThanMaximumLabelValue() {
        ImagePlus labels = labelImage(new int[] {1, 7, 7, 0});

        assertEquals(2, StarDist3DRunner.countLabels(labels));
    }

    @Test
    public void countLabelsIgnoresNonFiniteFloatLabels() {
        ij.process.FloatProcessor processor = new ij.process.FloatProcessor(3, 1);
        processor.setf(0, 0, 1.0f);
        processor.setf(1, 0, Float.NaN);
        processor.setf(2, 0, Float.POSITIVE_INFINITY);
        ImageStack stack = new ImageStack(3, 1);
        stack.addSlice(processor);
        ImagePlus labels = new ImagePlus("float-labels", stack);

        assertEquals(1, StarDist3DRunner.countLabels(labels));
    }

    @Test
    public void duplicateInputForTrackMateReturnsDetachedTitledCopy() {
        ImagePlus input = labelImage(new int[] {1, 2, 3, 4});
        Calibration calibration = new Calibration();
        calibration.pixelWidth = 0.5;
        calibration.pixelHeight = 0.75;
        input.setCalibration(calibration);

        ImagePlus copy = StarDist3DRunner.duplicateInputForTrackMate(input);

        assertEquals("StarDist_input", copy.getTitle());
        assertEquals(0.5, copy.getCalibration().pixelWidth, 0.0);
        copy.getProcessor().set(0, 0, 99);
        assertEquals(1, input.getProcessor().get(0, 0));
    }

    @Test
    public void configureDetectorSettingsPassesThresholdsToTrackMateStarDist() throws Exception {
        Settings settings = new Settings(labelImage(new int[] {1, 2, 3, 4}));

        StarDist3DRunner.configureStarDistDetector(settings, 0.73, 0.21);

        assertTrue(settings.detectorFactory instanceof StarDistCustomDetectorFactory);
        assertEquals(Integer.valueOf(1),
                settings.detectorSettings.get(DetectorKeys.KEY_TARGET_CHANNEL));
        assertEquals(0.73,
                ((Double) settings.detectorSettings.get(
                        StarDistCustomDetectorFactory.KEY_SCORE_THRESHOLD)).doubleValue(),
                0.0);
        assertEquals(0.21,
                ((Double) settings.detectorSettings.get(
                        StarDistCustomDetectorFactory.KEY_OVERLAP_THRESHOLD)).doubleValue(),
                0.0);
        assertTrue(new File((String) settings.detectorSettings.get(
                StarDistCustomDetectorFactory.KEY_MODEL_FILEPATH)).isFile());
    }

    private static ResultsTable objectStats() {
        ResultsTable stats = new ResultsTable();
        stats.incrementCounter();
        stats.setValue("Label", 0, 1);
        stats.setValue(StarDist3DRunner.STATS_AREA_MEAN, 0, 4);
        stats.setValue(StarDist3DRunner.STATS_QUALITY_MEAN, 0, 0.2);
        stats.setValue(StarDist3DRunner.STATS_INTENSITY_MEAN, 0, 10);
        stats.incrementCounter();
        stats.setValue("Label", 1, 2);
        stats.setValue(StarDist3DRunner.STATS_AREA_MEAN, 1, 20);
        stats.setValue(StarDist3DRunner.STATS_QUALITY_MEAN, 1, 0.9);
        stats.setValue(StarDist3DRunner.STATS_INTENSITY_MEAN, 1, 100);
        return stats;
    }

    private static ImagePlus labelImage(int[] pixels) {
        ShortProcessor processor = new ShortProcessor(pixels.length, 1);
        for (int x = 0; x < pixels.length; x++) {
            processor.set(x, 0, pixels[x]);
        }
        ImageStack stack = new ImageStack(pixels.length, 1);
        stack.addSlice(processor);
        return new ImagePlus("labels", stack);
    }
}
