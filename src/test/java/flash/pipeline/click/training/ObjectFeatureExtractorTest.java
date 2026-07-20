package flash.pipeline.click.training;

import flash.pipeline.click.ClickStore;
import flash.pipeline.click.suggest.StarDistFilterSuggester;
import flash.pipeline.click.suggest.SuggestionContext;
import flash.pipeline.objects.ObjectsCounter3DWrapper;
import flash.pipeline.stardist.StarDist3DRunner;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.ResultsTable;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class ObjectFeatureExtractorTest {

    @Before
    public void requireMcib3d() {
        Assume.assumeTrue(ObjectsCounter3DWrapper.isMcib3dAvailable());
    }

    @Test
    public void universalFeatureSetProducesSameColumnCountAndOrder() {
        ObjectFeatureExtractor extractor = new ObjectFeatureExtractor();

        List<ObjectFeatureExtractor.FeatureRow> first =
                extractor.extractFromLabelImage(labels(), raw(), null, null);
        List<ObjectFeatureExtractor.FeatureRow> second =
                extractor.extractFromLabelImage(labels(), raw(), null, null);

        assertEquals(11, extractor.universalFeatureNames().length);
        assertEquals(2, first.size());
        assertArrayEquals(first.get(0).featureNames, second.get(0).featureNames);
        assertEquals(first.get(0).featureNames.length, first.get(0).features.length);
    }

    @Test
    public void starDistQualityColumnPopulatedWhenStatsPresent() {
        ObjectFeatureExtractor extractor = new ObjectFeatureExtractor();
        ImagePlus labels = labels();
        ResultsTable stats = new ResultsTable();
        stats.incrementCounter();
        stats.setValue("Label", 0, 1);
        stats.setValue(StarDist3DRunner.STATS_QUALITY_MEAN, 0, 0.91);
        stats.incrementCounter();
        stats.setValue("Label", 1, 2);
        stats.setValue(StarDist3DRunner.STATS_QUALITY_MEAN, 1, Double.NaN);
        labels.setProperty(StarDist3DRunner.OBJECT_STATS_PROPERTY, stats);

        ObjectFeatureExtractor.FeatureRow row =
                rowByLabel(extractor.extractFromLabelImage(labels, raw(), null, null), 1);

        assertEquals(0.91, row.value(ObjectFeatureExtractor.FEATURE_QUALITY), 1.0e-9);
    }

    @Test
    public void starDistQualityColumnIsNaNWhenStatsMissing() {
        ObjectFeatureExtractor.FeatureRow row =
                rowByLabel(new ObjectFeatureExtractor().extractFromLabelImage(labels(), raw(), null, null), 1);

        assertTrue(Double.isNaN(row.value(ObjectFeatureExtractor.FEATURE_QUALITY)));
    }

    @Test
    public void cellposeMeanCellprobPopulatedWhenAuxImageProvided() {
        ObjectFeatureExtractor.FeatureRow row =
                rowByLabel(new ObjectFeatureExtractor().extractFromLabelImage(labels(), raw(), cellprob(), null), 2);

        assertEquals(0.75, row.value(ObjectFeatureExtractor.FEATURE_MEAN_CELLPROB), 1.0e-9);
        assertEquals(0.204124145, row.value(ObjectFeatureExtractor.FEATURE_STD_CELLPROB), 1.0e-6);
    }

    @Test
    public void cellposeMeanCellprobIsNaNWhenAuxImageMissing() {
        ObjectFeatureExtractor.FeatureRow row =
                rowByLabel(new ObjectFeatureExtractor().extractFromLabelImage(labels(), raw(), null, null), 2);

        assertTrue(Double.isNaN(row.value(ObjectFeatureExtractor.FEATURE_MEAN_CELLPROB)));
    }

    @Test
    public void emptyLabelsOfInterestReturnsEmptyList() {
        List<ObjectFeatureExtractor.FeatureRow> rows = new ObjectFeatureExtractor()
                .extractFromLabelImage(labels(), raw(), null, Collections.<Integer>emptySet());

        assertTrue(rows.isEmpty());
    }

    @Test
    public void wideCanonicalLabelsJoinPixelsStatisticsSuggestionsAndFeaturesOneToOne() {
        final int[] oracleLabels = new int[] {65_535, 65_536, 70_000};
        ImagePlus labels = wideLabels(oracleLabels);
        ResultsTable stats = new ResultsTable();
        for (int i = 0; i < oracleLabels.length; i++) {
            stats.incrementCounter();
            stats.setValue("Label", i, oracleLabels[i]);
            stats.setValue(StarDist3DRunner.STATS_QUALITY_MEAN, i, 0.1d * (i + 1));
            stats.setValue(StarDist3DRunner.STATS_AREA_MEAN, i, 3.0d);
            stats.setValue(StarDist3DRunner.STATS_INTENSITY_MEAN, i, 12.0d + 10.0d * i);
        }
        labels.setProperty(StarDist3DRunner.OBJECT_STATS_PROPERTY, stats);

        List<ObjectFeatureExtractor.FeatureRow> rows = new ObjectFeatureExtractor()
                .extractFromLabelImage(labels, wideRaw(), null, null);

        assertEquals(3, rows.size());
        for (int i = 0; i < oracleLabels.length; i++) {
            ObjectFeatureExtractor.FeatureRow row = rowByLabel(rows, oracleLabels[i]);
            assertEquals(12.0d + 10.0d * i,
                    row.value(ObjectFeatureExtractor.FEATURE_MEAN_INTENSITY), 0.0d);
            assertEquals(0.1d * (i + 1),
                    row.value(ObjectFeatureExtractor.FEATURE_QUALITY), 1.0e-12d);
        }

        List<ClickStore.Click> negatives = Arrays.asList(
                negative(oracleLabels[0], 0),
                negative(oracleLabels[1], 3),
                negative(oracleLabels[2], 6));
        StarDistFilterSuggester.StarDistSuggestion suggestion =
                new StarDistFilterSuggester().suggest(new SuggestionContext(
                        null, labels, null, negatives,
                        Collections.<ClickStore.Click>emptyList(),
                        Collections.<String, Double>emptyMap()));
        assertNotNull(suggestion.minQuality);
        assertEquals(3, suggestion.badRemoved);
        assertEquals(0, suggestion.collateralRemoved);
    }

    @Test
    public void substitutedWideStatisticsLabelIsRejectedInsteadOfSilentlyMissing() {
        ImagePlus labels = wideLabels(new int[] {65_535, 65_536, 70_000});
        ResultsTable stats = new ResultsTable();
        int[] substituted = new int[] {65_535, 65_536, 4_464};
        for (int i = 0; i < substituted.length; i++) {
            stats.incrementCounter();
            stats.setValue("Label", i, substituted[i]);
            stats.setValue(StarDist3DRunner.STATS_QUALITY_MEAN, i, 0.1d * (i + 1));
            stats.setValue(StarDist3DRunner.STATS_AREA_MEAN, i, 3.0d);
            stats.setValue(StarDist3DRunner.STATS_INTENSITY_MEAN, i, 10.0d);
        }
        labels.setProperty(StarDist3DRunner.OBJECT_STATS_PROPERTY, stats);

        try {
            new ObjectFeatureExtractor().extractFromLabelImage(
                    labels, wideRaw(), null, null);
            fail("Expected feature pixel/statistics label mismatch.");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("LABEL_IDENTITY_UNSUPPORTED"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("70000"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("4464"));
        }

        try {
            new StarDistFilterSuggester().suggest(new SuggestionContext(
                    null, labels, null,
                    Arrays.asList(negative(65_535, 0), negative(65_536, 3),
                            negative(70_000, 6)),
                    Collections.<ClickStore.Click>emptyList(),
                    Collections.<String, Double>emptyMap()));
            fail("Expected pixel/statistics label mismatch.");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("LABEL_IDENTITY_UNSUPPORTED"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("70000"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("4464"));
        }
    }

    @Test
    public void attachedEmptyStatisticsTableCannotSilentlyDetachNonemptyPixels() {
        ImagePlus labels = wideLabels(new int[] {65_535, 65_536, 70_000});
        labels.setProperty(StarDist3DRunner.OBJECT_STATS_PROPERTY, new ResultsTable());

        try {
            new ObjectFeatureExtractor().extractFromLabelImage(
                    labels, wideRaw(), null, null);
            fail("Expected empty feature statistics-table mismatch.");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("LABEL_IDENTITY_UNSUPPORTED"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("70000"));
        }

        try {
            new StarDistFilterSuggester().suggest(new SuggestionContext(
                    null, labels, null,
                    Arrays.asList(negative(65_535, 0), negative(65_536, 3),
                            negative(70_000, 6)),
                    Collections.<ClickStore.Click>emptyList(),
                    Collections.<String, Double>emptyMap()));
            fail("Expected empty suggestion statistics-table mismatch.");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("LABEL_IDENTITY_UNSUPPORTED"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("70000"));
        }
    }

    private static ObjectFeatureExtractor.FeatureRow rowByLabel(List<ObjectFeatureExtractor.FeatureRow> rows,
                                                                int label) {
        for (ObjectFeatureExtractor.FeatureRow row : rows) {
            if (row.label == label) return row;
        }
        throw new AssertionError("Missing row for label " + label);
    }

    private static ImagePlus labels() {
        ByteProcessor bp = new ByteProcessor(4, 3);
        bp.set(0, 0, 1);
        bp.set(1, 0, 1);
        bp.set(0, 1, 1);
        bp.set(3, 1, 2);
        bp.set(2, 2, 2);
        bp.set(3, 2, 2);
        return image("labels", bp);
    }

    private static ImagePlus raw() {
        FloatProcessor fp = new FloatProcessor(4, 3);
        for (int i = 0; i < fp.getPixelCount(); i++) {
            fp.setf(i, 10 + i);
        }
        return image("raw", fp);
    }

    private static ImagePlus cellprob() {
        FloatProcessor fp = new FloatProcessor(4, 3);
        fp.setf(3, 1, 0.5f);
        fp.setf(2, 2, 0.75f);
        fp.setf(3, 2, 1.0f);
        return image("cellprob", fp);
    }

    private static ImagePlus wideLabels(int[] labels) {
        FloatProcessor processor = new FloatProcessor(labels.length * 3, 1);
        for (int i = 0; i < labels.length; i++) {
            for (int pixel = 0; pixel < 3; pixel++) {
                processor.setf(i * 3 + pixel, labels[i]);
            }
        }
        return image("wide-labels", processor);
    }

    private static ImagePlus wideRaw() {
        FloatProcessor processor = new FloatProcessor(9, 1);
        for (int group = 0; group < 3; group++) {
            processor.setf(group * 3, 10.0f + 10.0f * group);
            processor.setf(group * 3 + 1, 12.0f + 10.0f * group);
            processor.setf(group * 3 + 2, 14.0f + 10.0f * group);
        }
        return image("wide-raw", processor);
    }

    private static ClickStore.Click negative(int label, int x) {
        return new ClickStore.Click("wide", 1, label, 1, x, 0.0,
                ClickStore.Verdict.NEGATIVE, 1L);
    }

    private static ImagePlus image(String title, ImageProcessor processor) {
        ImageStack stack = new ImageStack(processor.getWidth(), processor.getHeight());
        stack.addSlice(processor);
        ImagePlus image = new ImagePlus(title, stack);
        image.setDimensions(1, 1, 1);
        return image;
    }
}
