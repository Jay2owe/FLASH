package flash.pipeline.ui.variations;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import org.junit.Test;
import flash.pipeline.ui.config.StarDistParameterStage;
import static org.junit.Assert.*;

public class VariationSourceIdentityTest {
    @org.junit.Rule public org.junit.rules.TemporaryFolder temp =
            new org.junit.rules.TemporaryFolder();

    @Test public void replacingCustomModelUnderTheSameKeyChangesCacheIdentity() throws Exception {
        java.nio.file.Path root = temp.newFolder().toPath();
        java.nio.file.Path sourceFile = root.resolve("trained.zip");
        java.nio.file.Files.write(sourceFile, new byte[]{1, 2, 3});
        flash.pipeline.segmentation.catalog.ModelCatalog catalog =
                new flash.pipeline.segmentation.catalog.ModelCatalog(root);
        flash.pipeline.segmentation.catalog.ModelEntry entry =
                new flash.pipeline.segmentation.catalog.ModelEntry("custom", "Custom", "",
                        flash.pipeline.segmentation.catalog.ModelEntry.Engine.STARDIST,
                        flash.pipeline.segmentation.catalog.ModelEntry.Source.USER_IMPORTED,
                        null, null, null, null, null,
                        java.util.Collections.<String, Object>emptyMap(),
                        java.util.Collections.<String, Object>emptyMap(), false);
        catalog.add(entry, sourceFile);
        flash.pipeline.segmentation.catalog.ModelCatalogIO.writeProject(root, catalog);
        String before = customContext(root.toFile()).cacheNamespace();
        java.nio.file.Files.write(sourceFile, new byte[]{3, 2, 1});
        catalog.add(entry, sourceFile);
        String after = customContext(root.toFile()).cacheNamespace();
        assertNotEquals(before, after);
        assertTrue(before.contains(":sha256="));
    }

    private static VariationEngineContext customContext(java.io.File root) {
        ImagePlus source = new ImagePlus("pixels", new FloatProcessor(2, 2));
        StarDistParameterStage.Parameters parameters = new StarDistParameterStage.Parameters(
                0.5, 0.5, 1, 1, 1, 0, Double.POSITIVE_INFINITY, 0, 0, "custom");
        flash.pipeline.ui.config.ConfigQcContext config =
                flash.pipeline.ui.config.ConfigQcContext.fromImages(root, null, null,
                        java.util.Collections.singletonList(source),
                        java.util.Collections.singletonList("channel"), 0);
        return VariationEngineContext.forStarDist("channel", source, source, config,
                parameters, null);
    }
    @Test public void calibrationAndPlaneLayoutChangeSourceIdentity() {
        ImageStack stack = new ImageStack(2, 2);
        for (int i = 0; i < 4; i++) stack.addSlice(new FloatProcessor(2, 2));
        ImagePlus source = new ImagePlus("identical pixels", stack);
        String original = FilterVariationEngineContext.sourceImageHash(source);
        source.getCalibration().pixelWidth = 0.5;
        String calibrated = FilterVariationEngineContext.sourceImageHash(source);
        assertNotEquals(original, calibrated);
        source.setDimensions(2, 2, 1);
        assertNotEquals(calibrated, FilterVariationEngineContext.sourceImageHash(source));
    }

    @Test public void changingStarDistModelInvalidatesSavedSweepAndCacheKeys() {
        ImagePlus source = new ImagePlus("same pixels", new FloatProcessor(2, 2));
        ParameterSweep a = sweepForModel(source, "model-a");
        ParameterSweep b = sweepForModel(source, "model-b");
        assertNotEquals(a.cacheNamespace(), b.cacheNamespace());
        assertNotEquals(a.toCanonicalJson(), b.toCanonicalJson());
        assertNotEquals(VariationCache.keyFor(a, a.combos().get(0)),
                VariationCache.keyFor(b, b.combos().get(0)));
    }

    private static ParameterSweep sweepForModel(ImagePlus source, String model) {
        StarDistParameterStage.Parameters parameters = new StarDistParameterStage.Parameters(
                0.5, 0.5, 1, 1, 1, 0, Double.POSITIVE_INFINITY, 0, 0, model);
        VariationEngineContext context = VariationEngineContext.forStarDist(
                "channel", source, source, null, parameters, null);
        return new ParameterSweepEditor(context).currentSweep();
    }
}
