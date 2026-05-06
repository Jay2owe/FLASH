package flash.pipeline.recipes;

import flash.pipeline.FLASH_Pipeline;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PipelineRecipeTest {

    @Test
    public void fromSelections_roundTripsOrientationSetupKey() {
        boolean[] selections = new boolean[FLASH_Pipeline.IDX_ORIENTATION_SETUP + 1];
        selections[FLASH_Pipeline.IDX_ORIENTATION_SETUP] = true;

        PipelineRecipe recipe = PipelineRecipe.fromSelections(
                "Orientation setup", "Test recipe", selections);

        assertEquals("OrientationSetup", recipe.getAnalyses().get(0));
        assertEquals(Integer.valueOf(FLASH_Pipeline.IDX_ORIENTATION_SETUP),
                PipelineRecipe.KEY_TO_IDX.get("OrientationSetup"));
        assertEquals("OrientationSetup",
                PipelineRecipe.IDX_TO_KEY.get(Integer.valueOf(FLASH_Pipeline.IDX_ORIENTATION_SETUP)));
    }
}
