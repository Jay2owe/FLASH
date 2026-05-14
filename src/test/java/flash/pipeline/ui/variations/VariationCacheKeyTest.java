package flash.pipeline.ui.variations;

import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class VariationCacheKeyTest {

    @Test
    public void filterCacheNamespaceSeparatesOtherwiseIdenticalSweeps() {
        FilterParameterId sigma =
                new FilterParameterId(0, 0, 0, "Gaussian Blur", "sigma");
        Map<ParameterKey, ParameterValueList> values =
                new LinkedHashMap<ParameterKey, ParameterValueList>();
        values.put(sigma, ParameterValueList.ofDoubles(1.0d));
        ParameterCombo combo = new ParameterCombo(
                Collections.singletonMap(sigma, Double.valueOf(1.0d)));
        ParameterSweep first = new ParameterSweep(ParameterSweep.Method.FILTER,
                values, CropSpec.full(), "DAPI", "hash", "filter:first");
        ParameterSweep second = new ParameterSweep(ParameterSweep.Method.FILTER,
                values, CropSpec.full(), "DAPI", "hash", "filter:second");

        assertNotEquals(VariationCache.keyFor(first, combo),
                VariationCache.keyFor(second, combo));
    }

    @Test
    public void emptyNamespaceKeepsSegmentationKeyStable() {
        Map<ParameterId, ParameterValueList> values =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.THRESHOLD, ParameterValueList.ofInts(128));
        ParameterCombo combo = ParameterCombo.builder()
                .put(ParameterId.THRESHOLD, Integer.valueOf(128))
                .build();
        ParameterSweep oldConstructor = new ParameterSweep(ParameterSweep.Method.CLASSICAL,
                values, CropSpec.full(), "DAPI", "hash");
        ParameterSweep explicitEmptyNamespace = new ParameterSweep(
                ParameterSweep.Method.CLASSICAL, values, CropSpec.full(), "DAPI",
                "hash", "");

        assertEquals(VariationCache.keyFor(oldConstructor, combo),
                VariationCache.keyFor(explicitEmptyNamespace, combo));
    }

    @Test
    public void filterAndSegmentationKeysDoNotCollide() {
        Map<ParameterId, ParameterValueList> segmentationValues =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        segmentationValues.put(ParameterId.THRESHOLD, ParameterValueList.ofInts(128));
        ParameterCombo segmentationCombo = ParameterCombo.builder()
                .put(ParameterId.THRESHOLD, Integer.valueOf(128))
                .build();
        ParameterSweep segmentation = new ParameterSweep(ParameterSweep.Method.CLASSICAL,
                segmentationValues, CropSpec.full(), "DAPI", "hash");

        FilterParameterId threshold =
                new FilterParameterId(0, 0, 0, "Threshold", "threshold");
        Map<ParameterKey, ParameterValueList> filterValues =
                new LinkedHashMap<ParameterKey, ParameterValueList>();
        filterValues.put(threshold, ParameterValueList.ofInts(128));
        ParameterCombo filterCombo = new ParameterCombo(
                Collections.singletonMap(threshold, Integer.valueOf(128)));
        ParameterSweep filter = new ParameterSweep(ParameterSweep.Method.FILTER,
                filterValues, CropSpec.full(), "DAPI", "hash", "filter:abc");

        assertNotEquals(VariationCache.keyFor(segmentation, segmentationCombo),
                VariationCache.keyFor(filter, filterCombo));
    }
}
