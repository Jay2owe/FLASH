package flash.pipeline.recipes;

import flash.pipeline.FLASH_Pipeline;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PipelineRecipeTest {

    @Test
    public void fromSelections_doesNotEmitHiddenOrientationSetupKey() {
        boolean[] selections = new boolean[FLASH_Pipeline.IDX_ORIENTATION_SETUP + 1];
        selections[FLASH_Pipeline.IDX_ORIENTATION_SETUP] = true;

        PipelineRecipe recipe = PipelineRecipe.fromSelections(
                "Orientation setup", "Test recipe", selections);

        assertTrue(recipe.getAnalyses().isEmpty());
        assertFalse(PipelineRecipe.IDX_TO_KEY.containsKey(
                Integer.valueOf(FLASH_Pipeline.IDX_ORIENTATION_SETUP)));
    }

    @Test
    public void legacyOrientationSetupRecipeKeyAliasesToDrawRois() {
        PipelineRecipe recipe = new PipelineRecipe(
                "Legacy orientation", "Old saved recipe", PipelineRecipe.CURRENT_FLASH_VERSION,
                Collections.singletonList("OrientationSetup"), Collections.<String, String>emptyMap());

        assertTrue(recipe.unknownAnalysisKeys().isEmpty());
        assertEquals(Integer.valueOf(FLASH_Pipeline.IDX_DRAW_ROIS),
                PipelineRecipe.KEY_TO_IDX.get("OrientationSetup"));
    }
}
