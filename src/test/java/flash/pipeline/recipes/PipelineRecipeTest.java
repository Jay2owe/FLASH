package flash.pipeline.recipes;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertFalse;
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

    @Test
    public void toSelectionsMapsKnownRecipeKeysBackToAnalysisIndexes() {
        java.util.List<String> analyses = new java.util.ArrayList<String>();
        analyses.add("CreateBin");
        analyses.add("Intensity");
        analyses.add("RepresentativeFigure");
        analyses.add("UnknownAnalysis");
        PipelineRecipe recipe = new PipelineRecipe(
                "Restore", "Test recipe", PipelineRecipe.CURRENT_FLASH_VERSION,
                analyses, Collections.<String, String>emptyMap());

        boolean[] selections = recipe.toSelections(
                flash.pipeline.FLASH_Pipeline.IDX_REPRESENTATIVE_FIGURE + 1);

        assertTrue(selections[flash.pipeline.FLASH_Pipeline.IDX_CREATE_BIN]);
        assertTrue(selections[flash.pipeline.FLASH_Pipeline.IDX_INTENSITY]);
        assertTrue(selections[flash.pipeline.FLASH_Pipeline.IDX_REPRESENTATIVE_FIGURE]);
        assertFalse(selections[flash.pipeline.FLASH_Pipeline.IDX_DRAW_ROIS]);
    }

    @Test
    public void toSelectionsIgnoresKeysOutsideRequestedLength() {
        PipelineRecipe recipe = new PipelineRecipe(
                "Short", "Test recipe", PipelineRecipe.CURRENT_FLASH_VERSION,
                Collections.singletonList("Excel"), Collections.<String, String>emptyMap());

        boolean[] selections = recipe.toSelections(2);

        assertEquals(2, selections.length);
        assertFalse(selections[0]);
        assertFalse(selections[1]);
    }
}
