package flash.pipeline.ui.config;

import flash.pipeline.segmentation.catalog.ModelEntry;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MissingModelControllerTest {

    @Test
    public void missingModelBlocksPreviewAndOffersSameEngineReplacements() {
        MissingModelController controller = new MissingModelController(
                ModelEntry.Engine.STARDIST,
                Arrays.asList(entry("stardist_a", ModelEntry.Engine.STARDIST),
                        entry("cellpose_a", ModelEntry.Engine.CELLPOSE)),
                "missing");

        MissingModelController.State state = controller.state();

        assertTrue(state.missing);
        assertTrue(state.previewBlocked);
        assertEquals("Cannot run segmentation: model missing.", state.statusMessage);
        assertEquals(1, state.replacementChoices.size());
        assertEquals("stardist_a", state.replacementChoices.get(0).modelKey);
    }

    @Test
    public void pickReplacementClearsMissingState() {
        MissingModelController controller = new MissingModelController(
                ModelEntry.Engine.CELLPOSE,
                Arrays.asList(entry("cellpose_cyto3", ModelEntry.Engine.CELLPOSE)),
                "missing");

        MissingModelController.State state = controller.pickReplacement("cellpose_cyto3");

        assertFalse(state.missing);
        assertFalse(state.previewBlocked);
    }

    @Test
    public void headlessMissingModelReturnsNonZeroValidation() {
        MissingModelController controller = new MissingModelController(
                ModelEntry.Engine.CELLPOSE,
                Arrays.asList(entry("cellpose_cyto3", ModelEntry.Engine.CELLPOSE)),
                "missing_model");

        MissingModelController.ValidationResult result = controller.validateHeadless();

        assertFalse(result.ok);
        assertEquals(1, result.exitCode);
        assertTrue(result.message.contains("missing_model"));
    }

    private static ModelEntry entry(String key, ModelEntry.Engine engine) {
        return new ModelEntry(key, key, "",
                engine, ModelEntry.Source.STOCK_BUILTIN,
                null, null, key, null, null, null, null, false);
    }
}
