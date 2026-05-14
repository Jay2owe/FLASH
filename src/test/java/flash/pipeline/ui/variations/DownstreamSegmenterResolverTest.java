package flash.pipeline.ui.variations;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.image.FilterMacroEditorModel;
import flash.pipeline.objects.ObjectsCounter3DWrapper;
import flash.pipeline.ui.config.CellposeParameterStage;
import flash.pipeline.ui.config.ClassicalSegmentationStage;
import flash.pipeline.ui.config.ConfigQcContext;
import flash.pipeline.ui.config.FilterParameterStage;
import flash.pipeline.ui.config.StarDistParameterStage;

import ij.ImagePlus;
import ij.process.ByteProcessor;

import org.junit.Test;

import java.io.File;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DownstreamSegmenterResolverTest {

    @Test
    public void resolvesClassicalFromBinConfigChannelSettings() {
        BinConfig bin = binConfig("classical", "128", "7-99");
        DownstreamSegmenter.Resolution resolution = DownstreamSegmenter.resolve(
                context(bin, new ClassicalAdapter(), null, null));

        assertTrue(resolution.isAvailable());
        assertEquals(ParameterSweep.Method.CLASSICAL,
                resolution.segmenter().method());
        ParameterCombo combo = resolution.segmenter().comboForTest(image());
        assertEquals(Integer.valueOf(128), combo.get(ParameterId.THRESHOLD));
        assertEquals(Integer.valueOf(7), combo.get(ParameterId.MIN_SIZE));
        assertEquals(Integer.valueOf(99), combo.get(ParameterId.MAX_SIZE));
    }

    @Test
    public void resolvesStarDistWithParsedMethodToken() {
        DownstreamSegmenter.Resolution resolution = DownstreamSegmenter.resolve(
                context(binConfig("stardist:0.5:0.4", "default", "100-Infinity"),
                        null, new StarDistAdapter(), null));

        assertTrue(resolution.isAvailable());
        assertEquals(ParameterSweep.Method.STARDIST,
                resolution.segmenter().method());
        assertEquals(0.5d,
                resolution.segmenter().starDistParametersForTest()
                        .probabilityThreshold,
                0.0001d);
        assertEquals(0.4d,
                resolution.segmenter().starDistParametersForTest().nmsThreshold,
                0.0001d);
    }

    @Test
    public void resolvesCellposeWithParsedMethodToken() {
        DownstreamSegmenter.Resolution resolution = DownstreamSegmenter.resolve(
                context(binConfig("cellpose:30:cyto3:0.4:0.0:gpu=false",
                                "default", "100-Infinity"),
                        null, null, new CellposeAdapter()));

        assertTrue(resolution.isAvailable());
        assertEquals(ParameterSweep.Method.CELLPOSE,
                resolution.segmenter().method());
        assertEquals(30.0d,
                resolution.segmenter().cellposeParametersForTest().diameter,
                0.0001d);
        assertEquals("cyto3",
                resolution.segmenter().cellposeParametersForTest().modelToken);
        assertFalse(resolution.segmenter().cellposeParametersForTest().useGpu);
    }

    @Test
    public void nonBinConfigReportsUnavailable() {
        DownstreamSegmenter.Resolution resolution = DownstreamSegmenter.resolve(
                context(new Object(), new ClassicalAdapter(), null, null));

        assertFalse(resolution.isAvailable());
        assertTrue(resolution.unavailableReason().contains("BinConfig"));
    }

    private static FilterVariationEngineContext context(
            Object config,
            ClassicalSegmentationStage.PreviewAdapter classical,
            StarDistParameterStage.PreviewAdapter starDist,
            CellposeParameterStage.PreviewAdapter cellpose) {
        ImagePlus image = image();
        ConfigQcContext qc = ConfigQcContext.fromImages(new File("."),
                new File("target/downstream-segmenter-resolver-test-bin"),
                config,
                Collections.singletonList(image),
                Collections.singletonList("DAPI"),
                0);
        return new FilterVariationEngineContext(
                FilterMacroEditorModel.parse(
                        "run(\"Gaussian Blur...\", \"sigma=1 stack\");"),
                image,
                CropSpec.full(),
                "DAPI",
                qc,
                new FilterAdapter(),
                classical,
                starDist,
                cellpose);
    }

    private static BinConfig binConfig(String method,
                                       String threshold,
                                       String size) {
        BinConfig bin = new BinConfig();
        bin.channelNames.add("DAPI");
        bin.channelThresholds.add(threshold);
        bin.channelSizes.add(size);
        bin.segmentationMethods.add(method);
        return bin;
    }

    private static ImagePlus image() {
        return new ImagePlus("source", new ByteProcessor(4, 4));
    }

    private static final class FilterAdapter
            implements FilterParameterStage.PreviewAdapter {
        @Override public ImagePlus createSource(ConfigQcContext context) {
            return image();
        }

        @Override public ImagePlus createFilteredPreview(ImagePlus source,
                                                         String macroContent) {
            return source;
        }

        @Override public void close(ImagePlus image) {
        }
    }

    private static final class ClassicalAdapter
            implements ClassicalSegmentationStage.PreviewAdapter {
        @Override public ImagePlus createRawSource(ConfigQcContext context) {
            return null;
        }

        @Override public ImagePlus createFilteredSource(ConfigQcContext context) {
            return null;
        }

        @Override public ObjectsCounter3DWrapper.Result runPreview(
                ImagePlus filteredSource,
                int threshold,
                int minSize,
                int maxSize) {
            return null;
        }

        @Override public int countObjects(ObjectsCounter3DWrapper.Result result) {
            return 0;
        }

        @Override public void close(ImagePlus image) {
        }
    }

    private static final class StarDistAdapter
            implements StarDistParameterStage.PreviewAdapter {
        @Override public ImagePlus createRawSource(ConfigQcContext context) {
            return null;
        }

        @Override public ImagePlus createFilteredSource(ConfigQcContext context) {
            return null;
        }

        @Override public ImagePlus runPreview(ImagePlus filteredSource,
                                              StarDistParameterStage.Parameters parameters) {
            return null;
        }

        @Override public int countLabels(ImagePlus labelImage) {
            return 0;
        }

        @Override public void close(ImagePlus image) {
        }
    }

    private static final class CellposeAdapter
            implements CellposeParameterStage.PreviewAdapter {
        @Override public ImagePlus createRawSource(ConfigQcContext context) {
            return null;
        }

        @Override public ImagePlus createFilteredSource(ConfigQcContext context) {
            return null;
        }

        @Override public ImagePlus createFilteredCompanionSource(
                ConfigQcContext context,
                int channelIndex) {
            return null;
        }

        @Override public ImagePlus runPreview(ImagePlus filteredSource,
                                              ImagePlus filteredCompanionSource,
                                              CellposeParameterStage.Parameters parameters) {
            return null;
        }

        @Override public int countLabels(ImagePlus labelImage) {
            return 0;
        }

        @Override public void close(ImagePlus image) {
        }
    }
}
