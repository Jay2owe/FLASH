package flash.pipeline.ui.variations;

import org.junit.Test;

import static org.junit.Assert.assertNotEquals;

public class DownstreamVerdictCacheKeyTest {

    @Test
    public void downstreamMethodTokenSeparatesOtherwiseIdenticalKeys() {
        ParameterCombo combo = ParameterCombo.builder()
                .put(ParameterId.THRESHOLD, Integer.valueOf(128))
                .put(ParameterId.MIN_SIZE, Integer.valueOf(10))
                .put(ParameterId.MAX_SIZE, Integer.valueOf(100))
                .build();

        String starDist = VariationCache.downstreamCacheNamespace(
                "filter-result-key",
                "stardist:0.5:0.4",
                "StarDistPerCell",
                CropSpec.full(),
                combo);
        String cellpose = VariationCache.downstreamCacheNamespace(
                "filter-result-key",
                "cellpose:30:cyto3:0.4:0.0:gpu=false",
                "CellposeOneShot",
                CropSpec.full(),
                combo);

        assertNotEquals(starDist, cellpose);
    }
}
