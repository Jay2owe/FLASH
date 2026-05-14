package flash.pipeline.ui.variations.strategy;

import flash.pipeline.objects.ObjectsCounter3DWrapper;
import flash.pipeline.ui.config.CellposeParameterStage;
import flash.pipeline.ui.config.ClassicalSegmentationStage;
import flash.pipeline.ui.config.ConfigQcContext;
import flash.pipeline.ui.config.StarDistParameterStage;
import flash.pipeline.ui.variations.CropSpec;
import flash.pipeline.ui.variations.ParameterCombo;
import flash.pipeline.ui.variations.ParameterId;
import flash.pipeline.ui.variations.ParameterSweep;
import flash.pipeline.ui.variations.ParameterValueList;
import flash.pipeline.ui.variations.VariationEngineContext;
import flash.pipeline.ui.variations.VariationStrategy;

import ij.ImagePlus;
import ij.process.ByteProcessor;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertTrue;

public class VariationStrategyChooserTest {

    @Test
    public void classicalMethodRoutesToClassicalSweep() {
        VariationStrategy strategy = VariationStrategyChooser.choose(
                classicalSweep(),
                classicalContext(),
                null);

        assertTrue(strategy instanceof ClassicalSweep);
    }

    @Test
    public void starDistProbAndNmsRoutesToFastPathOnlyWhenParityIsEnabled() {
        ParameterSweep sweep = starDistSweep(ParameterId.PROB_THRESH,
                ParameterId.NMS_THRESH);
        VariationEngineContext context = starDistContext();

        assertTrue(VariationStrategyChooser.choose(sweep, context, null)
                instanceof StarDistPerCell);
        assertTrue(VariationStrategyChooser.choose(sweep, context, null, Boolean.FALSE)
                instanceof StarDistPerCell);
        assertTrue(VariationStrategyChooser.choose(sweep, context, null, Boolean.TRUE)
                instanceof StarDistFastNms);
    }

    @Test
    public void starDistNonNmsParameterRoutesToPerCellFallback() {
        VariationStrategy strategy = VariationStrategyChooser.choose(
                starDistSweep(ParameterId.PROB_THRESH, ParameterId.LINKING_MAX),
                starDistContext(),
                null,
                Boolean.TRUE);

        assertTrue(strategy instanceof StarDistPerCell);
    }

    @Test
    public void cellposeWithoutModelSweepRoutesToPersistent() {
        VariationStrategy strategy = VariationStrategyChooser.choose(
                cellposeSweep(false), cellposeContext(), null);

        assertTrue(strategy instanceof CellposePersistent);
    }

    @Test
    public void cellposeModelSweepRoutesToOneShot() {
        VariationStrategy strategy = VariationStrategyChooser.choose(
                cellposeSweep(true), cellposeContext(), null);

        assertTrue(strategy instanceof CellposeOneShot);
    }

    private static VariationEngineContext classicalContext() {
        ImagePlus source = sourceImage();
        return VariationEngineContext.forClassical("DAPI",
                source,
                source,
                null,
                ParameterCombo.builder().put(ParameterId.THRESHOLD, Integer.valueOf(1)).build(),
                new ClassicalAdapter());
    }

    private static VariationEngineContext starDistContext() {
        ImagePlus source = sourceImage();
        return VariationEngineContext.forStarDist("DAPI",
                source,
                source,
                null,
                starDistParameters(),
                new StarDistAdapter());
    }

    private static VariationEngineContext cellposeContext() {
        ImagePlus source = sourceImage();
        return VariationEngineContext.forCellpose("DAPI",
                source,
                source,
                null,
                cellposeParameters(),
                new CellposeAdapter());
    }

    private static ParameterSweep classicalSweep() {
        Map<ParameterId, ParameterValueList> values =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.THRESHOLD, ParameterValueList.ofInts(1, 2));
        return new ParameterSweep(ParameterSweep.Method.CLASSICAL,
                values,
                CropSpec.full(),
                "DAPI",
                "hash");
    }

    private static ParameterSweep starDistSweep(ParameterId firstVaried,
                                                ParameterId secondVaried) {
        Map<ParameterId, ParameterValueList> values =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.PROB_THRESH,
                valuesFor(ParameterId.PROB_THRESH, firstVaried, secondVaried, 0.3d, 0.5d));
        values.put(ParameterId.NMS_THRESH,
                valuesFor(ParameterId.NMS_THRESH, firstVaried, secondVaried, 0.4d, 0.6d));
        if (firstVaried == ParameterId.LINKING_MAX
                || secondVaried == ParameterId.LINKING_MAX) {
            values.put(ParameterId.LINKING_MAX, ParameterValueList.ofDoubles(3.0d, 7.0d));
        }
        return new ParameterSweep(ParameterSweep.Method.STARDIST,
                values,
                CropSpec.full(),
                "DAPI",
                "hash");
    }

    private static ParameterSweep cellposeSweep(boolean sweepModel) {
        Map<ParameterId, ParameterValueList> values =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.DIAMETER, ParameterValueList.ofDoubles(20.0d, 30.0d));
        if (sweepModel) {
            values.put(ParameterId.MODEL, ParameterValueList.ofStrings("cyto3", "nuclei"));
        }
        return new ParameterSweep(ParameterSweep.Method.CELLPOSE,
                values,
                CropSpec.full(),
                "DAPI",
                "hash");
    }

    private static ParameterValueList valuesFor(ParameterId id,
                                                ParameterId first,
                                                ParameterId second,
                                                double low,
                                                double high) {
        if (id == first || id == second) {
            return ParameterValueList.ofDoubles(low, high);
        }
        return ParameterValueList.ofDoubles(low);
    }

    private static StarDistParameterStage.Parameters starDistParameters() {
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

    private static CellposeParameterStage.Parameters cellposeParameters() {
        return new CellposeParameterStage.Parameters(
                "cyto",
                -1,
                30.0d,
                0.4d,
                0.0d,
                false);
    }

    private static ImagePlus sourceImage() {
        return new ImagePlus("source", new ByteProcessor(1, 1));
    }

    private static final class ClassicalAdapter
            implements ClassicalSegmentationStage.PreviewAdapter {
        @Override public ImagePlus createRawSource(ConfigQcContext context) {
            return null;
        }

        @Override public ImagePlus createFilteredSource(ConfigQcContext context) {
            return null;
        }

        @Override public ObjectsCounter3DWrapper.Result runPreview(ImagePlus filteredSource,
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

        @Override public ImagePlus createFilteredCompanionSource(ConfigQcContext context,
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
