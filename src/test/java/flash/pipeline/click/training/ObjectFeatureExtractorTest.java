package flash.pipeline.click.training;

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

    private static ImagePlus image(String title, ImageProcessor processor) {
        ImageStack stack = new ImageStack(processor.getWidth(), processor.getHeight());
        stack.addSlice(processor);
        ImagePlus image = new ImagePlus(title, stack);
        image.setDimensions(1, 1, 1);
        return image;
    }
}
