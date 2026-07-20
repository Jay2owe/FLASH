package flash.pipeline.ui.variations.integration;

import flash.pipeline.ui.variations.CropSpec;
import flash.pipeline.ui.variations.VariationResult;
import flash.pipeline.ui.variations.strategy.ClassicalSweep;

import ij.ImagePlus;

import org.junit.Ignore;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Manual integration test.
 * <p>
 * Remove {@link Ignore} and run:
 * {@code .\mvnw.cmd '-Denforcer.skip=true' '-Dtest=flash.pipeline.ui.variations.integration.ClassicalSweepIntegrationTest' test}
 */
public class ClassicalSweepIntegrationTest {

    @Ignore("TODO(integration-fixture): manual integration test; re-enable when the class Javadoc command is available in CI fixtures.")
    @Test
    public void nineCellRealImageSweepCompletesUnderFiveSeconds() throws Exception {
        ImagePlus source = VariationIntegrationTestSupport.loadSyntheticBlobStack();
        ClassicalSweep strategy = new ClassicalSweep(source,
                CropSpec.full(),
                null,
                new VariationIntegrationTestSupport.ThresholdingPreviewAdapter(),
                2);
        List<VariationResult> results =
                Collections.synchronizedList(new ArrayList<VariationResult>());

        long started = System.currentTimeMillis();
        strategy.dispatch(VariationIntegrationTestSupport.classicalNineCellSweep(),
                results::add,
                () -> false);
        long elapsedMs = System.currentTimeMillis() - started;

        assertEquals(9, results.size());
        for (int i = 0; i < results.size(); i++) {
            VariationResult result = results.get(i);
            assertFalse(result.hasError());
            assertNotNull(result.label());
            assertTrue("Expected objects in cell " + i, result.nObjects() > 0);
        }
        assertTrue("9-cell classical sweep took " + elapsedMs + " ms",
                elapsedMs < 5000L);
    }
}
