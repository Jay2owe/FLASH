package flash.pipeline.ui.variations.strategy;

import flash.pipeline.stardist.StarDist3DRunner;
import flash.pipeline.ui.config.ConfigQcContext;
import flash.pipeline.ui.config.StarDistParameterStage;
import flash.pipeline.ui.variations.CropSpec;
import flash.pipeline.ui.variations.ParameterId;
import flash.pipeline.ui.variations.ParameterSweep;
import flash.pipeline.ui.variations.ParameterValueList;
import flash.pipeline.ui.variations.VariationResult;

import ij.ImagePlus;
import ij.measure.ResultsTable;
import ij.process.ByteProcessor;
import ij.process.ShortProcessor;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class StarDistPerCellTest {

    @Test
    public void dispatchRunsFourCellsSequentiallyAndAppliesPostFiltersAfterPreview()
            throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        StarDistPerCell strategy = new StarDistPerCell(sourceImage(),
                CropSpec.full(),
                null,
                adapter,
                baseParameters());
        List<VariationResult> results = new ArrayList<VariationResult>();

        strategy.dispatch(sweepWithLinkingAndAreaFilter(),
                results::add,
                () -> false);

        assertEquals(4, results.size());
        assertEquals(4, adapter.parameters.size());
        assertEquals(2, countResultsForAreaMin(results, 0.0d, 2));
        assertEquals(2, countResultsForAreaMin(results, 10.0d, 1));

        boolean sawLowLinking = false;
        boolean sawHighLinking = false;
        for (int i = 0; i < adapter.parameters.size(); i++) {
            StarDistParameterStage.Parameters p = adapter.parameters.get(i);
            if (p.linkingMaxDistance == 3.0d) {
                sawLowLinking = true;
            }
            if (p.linkingMaxDistance == 7.0d) {
                sawHighLinking = true;
            }
            assertEquals(0.0d, p.areaMin, 0.001d);
            assertTrue(Double.isInfinite(p.areaMax));
            assertEquals(0.0d, p.qualityMin, 0.001d);
            assertEquals(0.0d, p.intensityMin, 0.001d);
        }
        assertTrue(sawLowLinking);
        assertTrue(sawHighLinking);
        for (int i = 0; i < results.size(); i++) {
            assertNotNull(results.get(i).label());
            assertNotNull(results.get(i).stats());
        }
    }

    private static int countResultsForAreaMin(List<VariationResult> results,
                                              double areaMin,
                                              int expectedObjects) {
        int matches = 0;
        for (int i = 0; i < results.size(); i++) {
            VariationResult result = results.get(i);
            Object value = result.combo().get(ParameterId.AREA_MIN);
            if (value instanceof Number
                    && ((Number) value).doubleValue() == areaMin
                    && result.nObjects() == expectedObjects) {
                matches++;
            }
        }
        return matches;
    }

    private static ParameterSweep sweepWithLinkingAndAreaFilter() {
        Map<ParameterId, ParameterValueList> values =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.PROB_THRESH, ParameterValueList.ofDoubles(0.5d));
        values.put(ParameterId.NMS_THRESH, ParameterValueList.ofDoubles(0.4d));
        values.put(ParameterId.LINKING_MAX, ParameterValueList.ofDoubles(3.0d, 7.0d));
        values.put(ParameterId.AREA_MIN, ParameterValueList.ofDoubles(0.0d, 10.0d));
        return new ParameterSweep(ParameterSweep.Method.STARDIST,
                values,
                CropSpec.full(),
                "DAPI",
                "synthetic");
    }

    private static StarDistParameterStage.Parameters baseParameters() {
        return new StarDistParameterStage.Parameters(
                0.5d,
                0.4d,
                5.0d,
                5.0d,
                1,
                0,
                Double.POSITIVE_INFINITY,
                0,
                0);
    }

    private static ImagePlus sourceImage() {
        return new ImagePlus("source", new ByteProcessor(4, 1));
    }

    private static ImagePlus labelImage() {
        ShortProcessor processor = new ShortProcessor(4, 1);
        processor.set(0, 0, 1);
        processor.set(1, 0, 1);
        processor.set(2, 0, 2);
        processor.set(3, 0, 2);
        ImagePlus labels = new ImagePlus("labels", processor);
        labels.setProperty(StarDist3DRunner.OBJECT_STATS_PROPERTY, objectStats());
        return labels;
    }

    private static ResultsTable objectStats() {
        ResultsTable stats = new ResultsTable();
        stats.incrementCounter();
        stats.setValue("Label", 0, 1);
        stats.setValue(StarDist3DRunner.STATS_AREA_MEAN, 0, 4.0d);
        stats.incrementCounter();
        stats.setValue("Label", 1, 2);
        stats.setValue(StarDist3DRunner.STATS_AREA_MEAN, 1, 20.0d);
        return stats;
    }

    private static final class RecordingPreviewAdapter
            implements StarDistParameterStage.PreviewAdapter {
        final List<StarDistParameterStage.Parameters> parameters =
                new ArrayList<StarDistParameterStage.Parameters>();

        @Override public ImagePlus createRawSource(ConfigQcContext context) {
            return null;
        }

        @Override public ImagePlus createFilteredSource(ConfigQcContext context) {
            return null;
        }

        @Override public ImagePlus runPreview(ImagePlus filteredSource,
                                              StarDistParameterStage.Parameters parameters) {
            this.parameters.add(parameters);
            return labelImage();
        }

        @Override public int countLabels(ImagePlus labelImage) {
            return labelImage == null ? 0 : (int) labelImage.getProcessor().getStats().max;
        }

        @Override public void close(ImagePlus image) {
            if (image != null) {
                image.flush();
            }
        }
    }
}
