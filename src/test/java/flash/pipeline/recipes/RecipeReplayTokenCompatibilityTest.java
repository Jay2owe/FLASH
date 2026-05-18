package flash.pipeline.recipes;

import flash.pipeline.FLASH_Pipeline;
import flash.pipeline.segmentation.SegmentationTokenParser;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class RecipeReplayTokenCompatibilityTest {

    @Test
    public void legacyRecipeJsonLoadsWithCurrentAnalysisKeyParser() throws Exception {
        String legacyJson = "{"
                + "\"name\":\"Pre-training recipe\","
                + "\"description\":\"Saved before custom model support\","
                + "\"flashVersion\":\"0.x\","
                + "\"analyses\":[\"OrientationSetup\",\"ThreeDObject\",\"Intensity\"],"
                + "\"modulePresets\":{\"ThreeDObject\":\"count_coloc_standard\"}"
                + "}";

        PipelineRecipe recipe = PipelineRecipe.fromJson(legacyJson);

        assertFalse(recipe.unknownAnalysisKeys().contains("OrientationSetup"));
        assertEquals(Integer.valueOf(FLASH_Pipeline.IDX_DRAW_ROIS),
                PipelineRecipe.KEY_TO_IDX.get("OrientationSetup"));
        assertEquals("count_coloc_standard", recipe.getModulePresets().get("ThreeDObject"));
    }

    @Test
    public void newSegmentationTokensHaveDeterministicCanonicalOrdering() {
        assertStable("enhanced_classical:thresh=120:minSize=200:maxSize=10000:"
                + "morph=sphericity%3E%3D0.6%2Celongation%3C%3D2.0");
        assertStable("stardist:0.5:0.3:linking=5.0:gapClosing=5.0:"
                + "area=20-2000:quality=0.2:intensity=50:model=user_microglia_iba1_v3");
        assertStable("cellpose:30.0:0.4:0.0:gpu=true:chan2=0:model=user_iba1_v3");
        assertStable("trained_rf:projectModel_microglia_v1:base=classical");
    }

    @Test
    public void recipeJsonSerializationIsStableAcrossRuns() throws Exception {
        PipelineRecipe recipe = new PipelineRecipe(
                "Token compatibility",
                "Confirms recipe JSON ordering remains deterministic.",
                PipelineRecipe.CURRENT_FLASH_VERSION,
                Collections.singletonList("ThreeDObject"),
                Collections.singletonMap("ThreeDObject", "count_coloc_standard"));

        String firstJson = recipe.toJson();
        PipelineRecipe loaded = PipelineRecipe.fromJson(firstJson);

        assertEquals(recipe.getName(), loaded.getName());
        assertEquals(recipe.getDescription(), loaded.getDescription());
        assertEquals(recipe.getAnalyses(), loaded.getAnalyses());
        assertEquals(recipe.getModulePresets(), loaded.getModulePresets());
        assertEquals(firstJson, loaded.toJson());
    }

    private static void assertStable(String token) {
        String first = SegmentationTokenParser.format(SegmentationTokenParser.parse(token));
        String second = SegmentationTokenParser.format(SegmentationTokenParser.parse(first));
        assertEquals(first, second);
    }
}
