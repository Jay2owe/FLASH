package flash.pipeline.recipes;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PipelineRecipeTest {

    @Test
    public void fromSelections_ignoresOutOfRangeSelections() {
        boolean[] selections = new boolean[32];
        selections[31] = true;

        PipelineRecipe recipe = PipelineRecipe.fromSelections(
                "Out of range", "Test recipe", selections);

        assertTrue(recipe.getAnalyses().isEmpty());
    }

    @Test
    public void unknownRecipeKeysRemainUnknown() {
        PipelineRecipe recipe = new PipelineRecipe(
                "Unknown", "Test recipe", PipelineRecipe.CURRENT_FLASH_VERSION,
                Collections.singletonList("UnknownAnalysis"), Collections.<String, String>emptyMap());

        assertEquals(Collections.singletonList("UnknownAnalysis"), recipe.unknownAnalysisKeys());
    }
}
