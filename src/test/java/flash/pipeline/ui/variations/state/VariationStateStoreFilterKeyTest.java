package flash.pipeline.ui.variations.state;

import flash.pipeline.ui.variations.CropSpec;
import flash.pipeline.ui.variations.FilterParameterId;
import flash.pipeline.ui.variations.ParameterKey;
import flash.pipeline.ui.variations.ParameterSweep;
import flash.pipeline.ui.variations.ParameterValueList;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class VariationStateStoreFilterKeyTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void filterParameterKeyRoundTripsThroughStateStore() throws Exception {
        File bin = temp.newFolder(".bin");
        VariationStateStore store = new VariationStateStore(bin.toPath());
        FilterParameterId sigma =
                new FilterParameterId(0, 0, 0, "Gaussian Blur", "sigma");
        Map<ParameterKey, ParameterValueList> values =
                new LinkedHashMap<ParameterKey, ParameterValueList>();
        values.put(sigma, ParameterValueList.ofDoubles(1.0d, 2.0d));
        ParameterSweep sweep = new ParameterSweep(ParameterSweep.Method.FILTER,
                values, CropSpec.full(), "DAPI", "image-a", "filter:macrohash");
        VariationState state = new VariationState(sweep,
                Collections.<VariationState.CompletedCell>emptyList(),
                "2026-05-14T09:00:00Z",
                "2026-05-14T09:00:01Z");

        store.save(state);
        Optional<VariationState> loaded = store.load();

        assertTrue(loaded.isPresent());
        ParameterKey loadedKey = loaded.get().sweep().valueLists().keySet().iterator().next();
        assertTrue(loadedKey instanceof FilterParameterId);
        assertEquals(sigma.stableKey(), loadedKey.stableKey());
        assertEquals(ParameterSweep.Method.FILTER, loaded.get().method());
        assertEquals("filter:macrohash", loaded.get().sweep().cacheNamespace());
        assertEquals(sweep.valueLists().get(sigma),
                loaded.get().sweep().valueLists().get(loadedKey));
    }
}
