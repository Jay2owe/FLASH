package flash.pipeline.ui.variations.strategy;

import flash.pipeline.ui.config.CellposeParameterStage;
import flash.pipeline.ui.config.ConfigQcContext;
import flash.pipeline.ui.variations.CropSpec;
import flash.pipeline.ui.variations.ParameterCombo;
import flash.pipeline.ui.variations.ParameterId;
import flash.pipeline.ui.variations.ParameterSweep;
import flash.pipeline.ui.variations.ParameterValueList;
import flash.pipeline.ui.variations.VariationResult;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import ij.process.ShortProcessor;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class CellposeOneShotTest {

    @Test
    public void dispatchRunsTwoCellsThroughPreviewAdapter() {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        CellposeOneShot strategy = new CellposeOneShot(sourceImage(),
                CropSpec.full(),
                null,
                adapter,
                baseParameters(),
                null);
        List<VariationResult> results = new ArrayList<VariationResult>();

        strategy.dispatch(sweep(), results::add, () -> false);

        assertEquals(2, results.size());
        assertEquals(2, adapter.parameters.size());
        assertEquals(20.0d, adapter.parameters.get(0).diameter, 0.001d);
        assertEquals(30.0d, adapter.parameters.get(1).diameter, 0.001d);
        assertEquals(2, results.get(0).nObjects());
        assertEquals(2, results.get(1).nObjects());
        assertFalse(results.get(0).hasError());
        assertFalse(results.get(1).hasError());
    }

    private static ParameterSweep sweep() {
        Map<ParameterId, ParameterValueList> values =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.DIAMETER, ParameterValueList.ofDoubles(20.0d, 30.0d));
        values.put(ParameterId.FLOW_THRESHOLD, ParameterValueList.ofDoubles(0.4d));
        values.put(ParameterId.CELLPROB_THRESHOLD, ParameterValueList.ofDoubles(0.0d));
        return new ParameterSweep(ParameterSweep.Method.CELLPOSE,
                values,
                CropSpec.full(),
                "DAPI",
                "synthetic");
    }

    private static CellposeParameterStage.Parameters baseParameters() {
        return new CellposeParameterStage.Parameters(
                "cyto3",
                -1,
                30.0d,
                0.4d,
                0.0d,
                false);
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
        return new ImagePlus("labels", processor);
    }

    private static final class RecordingPreviewAdapter
            implements CellposeParameterStage.PreviewAdapter {
        final List<CellposeParameterStage.Parameters> parameters =
                new ArrayList<CellposeParameterStage.Parameters>();

        @Override public ImagePlus createRawSource(ConfigQcContext context) {
            return null;
        }

        @Override public ImagePlus createFilteredSource(ConfigQcContext context) {
            return null;
        }

        @Override public ImagePlus createFilteredCompanionSource(ConfigQcContext context,
                                                                 int channelIndex) {
            return null;
        }

        @Override public ImagePlus runPreview(ImagePlus filteredSource,
                                              ImagePlus filteredCompanionSource,
                                              CellposeParameterStage.Parameters parameters) {
            this.parameters.add(parameters);
            return labelImage();
        }

        @Override public int countLabels(ImagePlus labelImage) {
            return labelImage == null ? 0
                    : (int) labelImage.getProcessor().getStats().max;
        }

        @Override public void close(ImagePlus image) {
            if (image != null) {
                image.flush();
            }
        }
    }
}
