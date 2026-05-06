package flash.pipeline.deconv;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RefractiveIndexEstimatorTest {

    @Test
    public void immersionLookupIsCaseInsensitiveAndFallsBackForUnknownValues() {
        assertEquals(1.515, RefractiveIndexEstimator.immersionRI("Oil"), 0.0);
        assertEquals(1.47, RefractiveIndexEstimator.immersionRI("GLYCERIN"), 0.0);
        assertEquals(1.0, RefractiveIndexEstimator.immersionRI("mystery"), 0.0);
        assertEquals(1.0, RefractiveIndexEstimator.immersionRI(null), 0.0);
    }

    @Test
    public void mountingMediumLookupRecognizesCommonHints() {
        assertEquals(1.45, RefractiveIndexEstimator.mountingMediumRI("Vectashield"), 0.0);
        assertEquals(1.47, RefractiveIndexEstimator.mountingMediumRI("ProLong Diamond"), 0.0);
        assertEquals(1.52, RefractiveIndexEstimator.mountingMediumRI("cfm3"), 0.0);
        assertEquals(1.47, RefractiveIndexEstimator.mountingMediumRI("glycerol"), 0.0);
        assertEquals(1.33, RefractiveIndexEstimator.mountingMediumRI("PBS"), 0.0);
        assertEquals(1.46, RefractiveIndexEstimator.mountingMediumRI("CLARITY"), 0.0);
        assertTrue(Double.isNaN(RefractiveIndexEstimator.mountingMediumRI("unknown")));
    }

    @Test
    public void inferSampleRiPrefersMountingWhenAvailable() {
        assertEquals(1.45, RefractiveIndexEstimator.inferSampleRI("oil", "Vectashield"), 0.0);
        assertEquals(1.515, RefractiveIndexEstimator.inferSampleRI("oil", null), 0.0);
        assertEquals(1.0, RefractiveIndexEstimator.inferSampleRI(null, null), 0.0);
    }
}
