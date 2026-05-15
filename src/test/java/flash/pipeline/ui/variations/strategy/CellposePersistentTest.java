package flash.pipeline.ui.variations.strategy;

import flash.pipeline.cellpose.CellposePersistentWorker;
import flash.pipeline.ui.config.CellposeParameterStage;
import flash.pipeline.ui.config.ConfigQcContext;
import flash.pipeline.ui.variations.CropSpec;
import flash.pipeline.ui.variations.ParameterId;
import flash.pipeline.ui.variations.ParameterSweep;
import flash.pipeline.ui.variations.ParameterValueList;
import flash.pipeline.ui.variations.VariationResult;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import ij.process.ShortProcessor;

import org.junit.Test;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class CellposePersistentTest {

    @Test
    public void dispatchFallsBackToOneShotWhenHelperCannotStart() throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        CellposePersistent strategy = new CellposePersistent(sourceImage(),
                CropSpec.full(),
                null,
                adapter,
                baseParameters(),
                null,
                "DAPI",
                new CellposePersistent.WorkerFactory() {
                    @Override public CellposePersistentWorker open(Path imagePath,
                                                                   Path outputDir,
                                                                   ImagePlus referenceInput,
                                                                   ImagePlus runtimeInput,
                                                                   String model,
                                                                   boolean useGpu,
                                                                   String channelName,
                                                                   File projectRoot) throws Exception {
                        throw new IllegalStateException("synthetic helper failure");
                    }
                });
        List<VariationResult> results = new ArrayList<VariationResult>();

        strategy.dispatch(sweep(), results::add, () -> false);

        assertEquals(2, results.size());
        assertEquals(2, adapter.previewRuns);
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
        int previewRuns;

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
            previewRuns++;
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
