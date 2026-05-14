package flash.pipeline.ui.sandbox;

import flash.pipeline.image.FilterMacroParser.OpType;
import flash.pipeline.ui.sandbox.FilterAlternatives.Alternative;
import flash.pipeline.ui.sandbox.FilterAlternatives.SlotRole;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FilterAlternativesCatalogTest {

    @Test
    public void smoothingAlternativesUseNativeCatalogTypes() {
        assertEquals(SlotRole.SMOOTHING,
                FilterAlternatives.slotRoleFor(OpType.GAUSSIAN_BLUR));

        Set<OpType> types = types(FilterAlternatives.alternativesFor(
                SlotRole.SMOOTHING));

        assertTrue(types.contains(OpType.MEDIAN));
        assertTrue(types.contains(OpType.MEAN));
        assertTrue(types.contains(OpType.VARIANCE));
    }

    @Test
    public void morphologyAlternativesUseNativeCatalogTypes() {
        assertEquals(SlotRole.MORPHOLOGY,
                FilterAlternatives.slotRoleFor(OpType.CLOSE_));

        Set<OpType> types = types(FilterAlternatives.alternativesFor(
                SlotRole.MORPHOLOGY));

        assertTrue(types.contains(OpType.DILATE));
        assertTrue(types.contains(OpType.ERODE));
        assertTrue(types.contains(OpType.FILL_HOLES));
        assertTrue(types.contains(OpType.SKELETONIZE));
    }

    @Test
    public void nonNativeExamplesAreNotListed() {
        Set<String> labels = new HashSet<String>();
        for (SlotRole role : SlotRole.values()) {
            List<Alternative> alternatives = FilterAlternatives.alternativesFor(role);
            for (int i = 0; i < alternatives.size(); i++) {
                labels.add(alternatives.get(i).label());
            }
        }

        assertFalse(labels.contains("Top-hat"));
        assertFalse(labels.contains("Outline"));
        assertFalse(labels.contains("Auto Threshold"));
    }

    private static Set<OpType> types(List<Alternative> alternatives) {
        Set<OpType> out = new HashSet<OpType>();
        for (int i = 0; i < alternatives.size(); i++) {
            out.add(alternatives.get(i).type());
        }
        return out;
    }
}
