package flash.pipeline.decontamination;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ShortProcessor;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SpectralPreviewRendererTest {

    @Test
    public void rendersPreviewFromLoadedImageUsingCurrentPipeline() {
        BinConfigStub binConfig = new BinConfigStub("Target", "Bleed");
        SpectralDecontaminationConfig config = new SpectralDecontaminationConfig();
        config.setTargetChannelIndex(0);
        config.setBleedThroughChannelIndexes(Arrays.asList(Integer.valueOf(1)));

        CorrectionPipeline pipeline = new CorrectionPipeline();
        pipeline.setFeatureIds(Arrays.asList(
                "linear_unmixing",
                "threshold_corrected_target"));
        config.setCorrectionPipeline(pipeline);

        ImagePlus source = twoChannelImage(
                new int[]{0, 10, 20, 100},
                new int[]{0, 20, 40, 0});
        SpectralPreviewSelector.PreviewSelection selection =
                new SpectralPreviewSelector.PreviewSelection(
                        new SpectralPreviewSelector.PreviewCandidate(
                                0,
                                "Mouse1_Control",
                                "Mouse1",
                                "Control"),
                        new SpectralPreviewSelector.ImageScores(0.0, 0.0, 0.0, 0.0, -1),
                        "control",
                        "typical");

        SpectralPreviewRenderer.RenderedPreview preview =
                SpectralPreviewRenderer.renderLoadedImage(source, binConfig, config, selection);

        assertNotNull(preview);
        assertNotNull(preview.rawTarget);
        assertNotNull(preview.correctedTarget);
        assertNotNull(preview.finalOverlay);
        assertFalse(preview.correctedTarget.placeholder);
        assertFalse(preview.finalOverlay.placeholder);
        assertEquals(Long.valueOf(1L), preview.metrics.targetPositiveVoxels);
        assertEquals(0.0, preview.metrics.saturatedFraction, 0.0);
        assertEquals(1, preview.bleedThroughChannels.size());
        assertFalse(preview.metrics.coefficientLines.isEmpty());
    }

    @Test
    public void extractsObjectCountsAndSaturationForMetricsPanel() {
        BinConfigStub binConfig = new BinConfigStub("Target", "Bleed");
        ImagePlus source = twoChannelImage(
                new int[]{1, 2, 3, 4},
                new int[]{5, 6, 7, 8});
        Calibration calibration = new Calibration();
        calibration.pixelWidth = 2.0;
        calibration.pixelHeight = 2.0;
        calibration.pixelDepth = 1.0;
        calibration.setUnit("micron");
        source.setCalibration(calibration);

        List<CorrectionPipeline.FeatureSummary> summaries =
                new ArrayList<CorrectionPipeline.FeatureSummary>();
        summaries.add(new CorrectionPipeline.FeatureSummary("linear_unmixing", "Linear unmixing")
                .putDouble("weight_channel_2", 0.25));
        summaries.add(new CorrectionPipeline.FeatureSummary("size_filter", "Size filter")
                .putInt("kept_voxels", 12)
                .putInt("kept_components", 3)
                .putInt("removed_components", 1));

        SpectralPreviewRenderer.PreviewMetrics metrics = SpectralPreviewRenderer.PreviewMetrics.from(
                source,
                binConfig,
                summaries,
                new SpectralPreviewSelector.ImageScores(0.0, 0.0, 0.0, 0.125, -1));

        assertEquals(Long.valueOf(12L), metrics.targetPositiveVoxels);
        assertEquals(Integer.valueOf(3), metrics.objectsKept);
        assertEquals(Integer.valueOf(1), metrics.objectsRemoved);
        assertEquals(0.125, metrics.saturatedFraction, 0.0);
        assertTrue(metrics.targetPositiveLabel.contains("48"));
        assertTrue(metrics.coefficientLines.get(0).contains("0.25"));
    }

    private static ImagePlus twoChannelImage(int[] targetPixels, int[] bleedPixels) {
        ImageStack stack = new ImageStack(2, 2);
        stack.addSlice(new ShortProcessor(2, 2, toShorts(targetPixels), null));
        stack.addSlice(new ShortProcessor(2, 2, toShorts(bleedPixels), null));
        ImagePlus image = new ImagePlus("source", stack);
        image.setDimensions(2, 1, 1);
        image.setOpenAsHyperStack(true);
        return image;
    }

    private static short[] toShorts(int[] values) {
        short[] out = new short[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (short) values[i];
        }
        return out;
    }

    private static final class BinConfigStub extends flash.pipeline.bin.BinConfig {
        BinConfigStub(String... channelNames) {
            this.channelNames.addAll(Arrays.asList(channelNames));
        }
    }
}
