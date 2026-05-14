package flash.pipeline.ui.variations.integration;

import flash.pipeline.stardist.StarDistDetector;
import flash.pipeline.stardist.StarDistVariationRunner;
import flash.pipeline.ui.config.ConfigQcContext;
import flash.pipeline.ui.config.StarDistParameterStage;
import flash.pipeline.ui.variations.ParameterSweep;
import flash.pipeline.ui.variations.VariationEngineContext;
import flash.pipeline.ui.variations.VariationResult;
import flash.pipeline.ui.variations.VariationStrategy;
import flash.pipeline.ui.variations.strategy.StarDistFastNms;
import flash.pipeline.ui.variations.strategy.VariationStrategyChooser;

import ij.ImagePlus;

import org.junit.Assume;
import org.junit.Ignore;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Manual GPU integration test for the disabled StarDist fast-NMS path.
 * <p>
 * Remove {@link Ignore} and run in a Fiji-compatible runtime:
 * {@code .\mvnw.cmd '-Denforcer.skip=true' '-Dtest=flash.pipeline.ui.variations.integration.StarDistFastNmsIntegrationTest' test}
 */
public class StarDistFastNmsIntegrationTest {

    @Ignore("Manual GPU integration test: run only after StarDist fast-NMS parity is enabled.")
    @Test
    public void fastNmsSweepUsesFastPathAndBeatsRepeatedInference() throws Exception {
        Assume.assumeTrue("StarDist fast-NMS parity is disabled.",
                StarDistVariationRunner.isFastNmsParityVerified());
        Assume.assumeTrue("StarDist runtime unavailable: "
                        + StarDistDetector.getAvailabilityMessage(),
                StarDistDetector.isAvailable());

        ImagePlus source = VariationIntegrationTestSupport.loadSyntheticBlobStack();
        ParameterSweep sweep =
                VariationIntegrationTestSupport.starDistFastNmsSixCellSweep();
        StarDistParameterStage.Parameters base =
                VariationIntegrationTestSupport.starDistBaseParameters();
        VariationIntegrationTestSupport.RealStarDistPreviewAdapter adapter =
                new VariationIntegrationTestSupport.RealStarDistPreviewAdapter();
        ConfigQcContext config = ConfigQcContext.fromImages(new File("."),
                new File("target/variation-stardist-integration-bin"),
                null,
                Collections.singletonList(source),
                Collections.singletonList("DAPI"),
                0);
        VariationEngineContext context = VariationEngineContext.forStarDist(
                "DAPI", source, source, config, base, adapter);

        VariationStrategy strategy = VariationStrategyChooser.choose(sweep,
                context,
                null);
        assertTrue(strategy instanceof StarDistFastNms);

        long singleStarted = System.currentTimeMillis();
        ImagePlus single = adapter.runPreview(source, base);
        long singleInferenceMs = Math.max(1L,
                System.currentTimeMillis() - singleStarted);
        assertNotNull(single);

        List<VariationResult> results = new ArrayList<VariationResult>();
        long sweepStarted = System.currentTimeMillis();
        strategy.dispatch(sweep, results::add, () -> false);
        long sweepMs = System.currentTimeMillis() - sweepStarted;

        assertEquals(6, results.size());
        for (int i = 0; i < results.size(); i++) {
            VariationResult result = results.get(i);
            assertFalse(result.hasError());
            assertNotNull(result.label());
        }
        assertTrue("Fast path took " + sweepMs + " ms; single inference took "
                        + singleInferenceMs + " ms",
                sweepMs < singleInferenceMs * 2L);
    }
}
