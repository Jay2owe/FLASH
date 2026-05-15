package flash.pipeline.ui.config;

import flash.pipeline.segmentation.catalog.ModelEntry;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ModelDropdownPolishControllerTest {

    @Test
    public void selectingModelCreatesPendingDefaultsWithoutOverwritingPreviousValues() {
        Map<String, Double> current = new LinkedHashMap<String, Double>();
        current.put("probThresh", Double.valueOf(0.5));
        current.put("nmsThresh", Double.valueOf(0.4));

        ModelDropdownPolishController.DefaultsState state =
                new ModelDropdownPolishController().select(entry(), current);

        assertTrue(state.pending);
        assertEquals(0.5, state.previousValues.get("probThresh").doubleValue(), 0.001);
        assertEquals(0.61, state.suggestedValues.get("probThresh").doubleValue(), 0.001);
    }

    @Test
    public void applyReturnsDefaultsAndRevertReturnsPreviousValues() {
        Map<String, Double> current = new LinkedHashMap<String, Double>();
        current.put("probThresh", Double.valueOf(0.5));
        current.put("nmsThresh", Double.valueOf(0.4));

        ModelDropdownPolishController.DefaultsState state =
                new ModelDropdownPolishController().select(entry(), current);

        assertEquals(0.61, state.applyValues().get("probThresh").doubleValue(), 0.001);
        assertEquals(0.5, state.revertValues().get("probThresh").doubleValue(), 0.001);
    }

    @Test
    public void noDefaultsCreatesNoPendingState() {
        ModelEntry entry = new ModelEntry("empty", "Empty", "",
                ModelEntry.Engine.STARDIST, ModelEntry.Source.USER_IMPORTED,
                "files/empty/model.zip", null, null, null, null,
                null, null, false);

        ModelDropdownPolishController.DefaultsState state =
                new ModelDropdownPolishController().select(entry,
                        new LinkedHashMap<String, Double>());

        assertFalse(state.pending);
    }

    private static ModelEntry entry() {
        Map<String, Object> defaults = new LinkedHashMap<String, Object>();
        defaults.put("probThresh", Double.valueOf(0.61));
        defaults.put("nmsThresh", Double.valueOf(0.22));
        return new ModelEntry("user", "User", "",
                ModelEntry.Engine.STARDIST, ModelEntry.Source.USER_IMPORTED,
                "files/user/model.zip", null, null, null, null,
                defaults, null, false);
    }
}
