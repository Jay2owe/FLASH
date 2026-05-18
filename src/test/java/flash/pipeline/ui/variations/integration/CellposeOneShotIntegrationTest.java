package flash.pipeline.ui.variations.integration;

import flash.pipeline.cellpose.CellposeRuntime;
import flash.pipeline.ui.variations.CropSpec;
import flash.pipeline.ui.variations.VariationResult;
import flash.pipeline.ui.variations.strategy.CellposeOneShot;

import ij.ImagePlus;

import org.junit.Assume;
import org.junit.Ignore;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Manual Cellpose integration test.
 * <p>
 * Remove {@link Ignore} and run after configuring Cellpose:
 * {@code .\mvnw.cmd '-Denforcer.skip=true' '-Dtest=flash.pipeline.ui.variations.integration.CellposeOneShotIntegrationTest' test}
 */
public class CellposeOneShotIntegrationTest {

    @Ignore("TODO(cellpose-fixture): manual Cellpose integration test; re-enable when Cellpose runtime fixtures exist in CI.")
    @Test
    public void threeCellSweepUsesOneShotFallbackWhenCellposeIsAvailable() throws Exception {
        CellposeRuntime.Status status = CellposeRuntime.probeConfigured();
        Assume.assumeTrue(status.message + "\n" + status.details, status.ready);

        ImagePlus source = VariationIntegrationTestSupport.loadSyntheticBlobStack();
        CellposeOneShot strategy = new CellposeOneShot(source,
                CropSpec.full(),
                null,
                new VariationIntegrationTestSupport.RealCellposePreviewAdapter(),
                VariationIntegrationTestSupport.cellposeBaseParameters(),
                null);
        List<VariationResult> results = new ArrayList<VariationResult>();

        strategy.dispatch(VariationIntegrationTestSupport.cellposeThreeCellSweep(),
                results::add,
                () -> false);

        assertEquals(3, results.size());
        for (int i = 0; i < results.size(); i++) {
            VariationResult result = results.get(i);
            assertFalse(result.hasError());
            assertNotNull(result.label());
            assertTrue("Expected Cellpose objects in cell " + i,
                    result.nObjects() > 0);
        }
    }
}
