package flash.pipeline.stardist;

import flash.pipeline.click.ClickStore;
import flash.pipeline.click.suggest.StarDistFilterSuggester;
import flash.pipeline.click.suggest.SuggestionContext;
import flash.pipeline.click.training.ObjectFeatureExtractor;
import flash.pipeline.objects.ObjectsCounter3DWrapper;
import fiji.plugin.trackmate.Settings;
import fiji.plugin.trackmate.detection.DetectorKeys;
import fiji.plugin.trackmate.stardist.StarDistCustomDetectorFactory;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.process.FloatProcessor;
import ij.process.ShortProcessor;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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
        assertEquals(0, stats.size());
    }

    @Test
    public void applyObjectFiltersKeepsLabelsExactlyAtBounds() {
        ImagePlus labels = labelImage(new int[] {1, 2, 2});
        ResultsTable stats = new ResultsTable();
        stats.incrementCounter();
        stats.setValue("Label", 0, 1);
        stats.setValue(StarDist3DRunner.STATS_AREA_MEAN, 0, 5);
        stats.setValue(StarDist3DRunner.STATS_QUALITY_MEAN, 0, 0.5);
        stats.setValue(StarDist3DRunner.STATS_INTENSITY_MEAN, 0, 50);
        stats.incrementCounter();
        stats.setValue("Label", 1, 2);
        stats.setValue(StarDist3DRunner.STATS_AREA_MEAN, 1, 10);
        stats.setValue(StarDist3DRunner.STATS_QUALITY_MEAN, 1, 0.5);
        stats.setValue(StarDist3DRunner.STATS_INTENSITY_MEAN, 1, 50);

        int removed = StarDist3DRunner.applyObjectFilters(labels, stats,
                5, 10, 0.5, 50);

        assertEquals(0, removed);
        assertEquals(2, StarDist3DRunner.countLabels(labels));
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
    public void filteringWideLabelRemovesItsExactStatisticsRowAndPreservesOrderAndMetrics() {
        ImagePlus labels = wideLabels(false);
        ResultsTable stats = wideStats();
        labels.setProperty(StarDist3DRunner.OBJECT_STATS_PROPERTY, stats);

        int removed = StarDist3DRunner.applyObjectFilters(
                labels, stats, 2.0d, Double.POSITIVE_INFINITY, 0.0d, 0.0d);

        assertEquals(1, removed);
        assertEquals(0, (int) labels.getProcessor().getf(1, 0));
        assertEquals(3, stats.size());
        assertEquals(65_535, (int) stats.getValue("Label", 0));
        assertEquals(70_000, (int) stats.getValue("Label", 1));
        assertEquals(80_000, (int) stats.getValue("Label", 2));
        assertEquals(0.2d,
                stats.getValue(StarDist3DRunner.STATS_QUALITY_MEAN, 1), 0.0d);
        StarDist3DRunner.validatePixelTableJoin(labels, stats);
    }

    @Test
    public void synchronizedFilteredWideOutputFeedsSuggestionsAndFeaturesWithoutStaleRows() {
        Assume.assumeTrue(ObjectsCounter3DWrapper.isMcib3dAvailable());
        ImagePlus labels = wideLabels(true);
        ResultsTable stats = wideStats();
        labels.setProperty(StarDist3DRunner.OBJECT_STATS_PROPERTY, stats);
        StarDist3DRunner.applyObjectFilters(
                labels, stats, 2.0d, Double.POSITIVE_INFINITY, 0.0d, 0.0d);

        List<ObjectFeatureExtractor.FeatureRow> features = new ObjectFeatureExtractor()
                .extractFromLabelImage(labels, wideRaw(), null, null);
        assertEquals(3, features.size());
        assertEquals(65_535, features.get(0).label);
        assertEquals(70_000, features.get(1).label);
        assertEquals(80_000, features.get(2).label);
        assertEquals(0.2d, features.get(1).value(ObjectFeatureExtractor.FEATURE_QUALITY),
                0.0d);

        StarDistFilterSuggester.StarDistSuggestion suggestion =
                new StarDistFilterSuggester().suggest(new SuggestionContext(
                        null, labels, null,
                        Arrays.asList(negative(65_535, 0), negative(70_000, 6),
                                negative(80_000, 9)),
                        Collections.<ClickStore.Click>emptyList(),
                        Collections.<String, Double>emptyMap()));
        assertTrue(suggestion.hasSuggestion());
        assertEquals(3, suggestion.badRemoved);
        assertEquals(0, suggestion.collateralRemoved);
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

    private static ResultsTable wideStats() {
        int[] labels = new int[] {65_535, 65_536, 70_000, 80_000};
        double[] areas = new double[] {3.0d, 1.0d, 3.0d, 3.0d};
        double[] qualities = new double[] {0.1d, 0.9d, 0.2d, 0.3d};
        ResultsTable stats = new ResultsTable();
        for (int i = 0; i < labels.length; i++) {
            stats.incrementCounter();
            stats.setValue("Label", i, labels[i]);
            stats.setValue(StarDist3DRunner.STATS_AREA_MEAN, i, areas[i]);
            stats.setValue(StarDist3DRunner.STATS_QUALITY_MEAN, i, qualities[i]);
            stats.setValue(StarDist3DRunner.STATS_INTENSITY_MEAN, i, 10.0d + i);
        }
        return stats;
    }

    private static ImagePlus wideLabels(boolean repeated) {
        int[] labels = new int[] {65_535, 65_536, 70_000, 80_000};
        int pixelsPerLabel = repeated ? 3 : 1;
        FloatProcessor processor = new FloatProcessor(labels.length * pixelsPerLabel, 1);
        for (int i = 0; i < labels.length; i++) {
            for (int pixel = 0; pixel < pixelsPerLabel; pixel++) {
                processor.setf(i * pixelsPerLabel + pixel, 0, labels[i]);
            }
        }
        ImageStack stack = new ImageStack(processor.getWidth(), 1);
        stack.addSlice(processor);
        ImagePlus image = new ImagePlus("wide-labels", stack);
        image.setDimensions(1, 1, 1);
        return image;
    }

    private static ImagePlus wideRaw() {
        FloatProcessor processor = new FloatProcessor(12, 1);
        for (int pixel = 0; pixel < processor.getPixelCount(); pixel++) {
            processor.setf(pixel, 10.0f + pixel);
        }
        ImageStack stack = new ImageStack(12, 1);
        stack.addSlice(processor);
        ImagePlus image = new ImagePlus("wide-raw", stack);
        image.setDimensions(1, 1, 1);
        return image;
    }

    private static ClickStore.Click negative(int label, int x) {
        return new ClickStore.Click("wide", 1, label, 1, x, 0.0d,
                ClickStore.Verdict.NEGATIVE, 1L);
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
