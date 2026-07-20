package flash.pipeline.recipes;

import flash.pipeline.audit.ReplayCommandFormatter;
import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.BinField;
import flash.pipeline.cellpose.Cellpose3DRunner;
import flash.pipeline.cli.CLIArgumentParser;
import flash.pipeline.cli.CLIConfig;
import flash.pipeline.segmentation.SegmentationMethod;
import flash.pipeline.segmentation.SegmentationTokenParser;
import flash.pipeline.segmentation.catalog.ModelCatalog;
import flash.pipeline.segmentation.catalog.ModelCatalogIO;
import flash.pipeline.segmentation.catalog.ModelEntry;
import flash.pipeline.stardist.StarDist3DRunner;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class RecipeReplayCustomModelTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void recipeReplayResolvesAndUsesCustomStarDistModel() throws Exception {
        Path root = temp.newFolder("stardist-replay").toPath();
        Path source = temp.newFile("projectModel_microglia_v3.zip").toPath();
        Files.write(source, "stardist-model".getBytes(StandardCharsets.UTF_8));
        ModelCatalog catalog = ModelCatalogIO.read(root);
        ModelEntry saved = catalog.add(userStarDist("projectModel_microglia_v3"), source);
        ModelCatalogIO.writeProject(root, catalog);
        String token = "stardist:0.5:0.3:model=projectModel_microglia_v3";

        String replayedToken = replayedSegmentationToken(root, recipeWithSegmentationToken(token));
        List<RecipeReplayModelResolver.ResolvedModelUse> resolved =
                RecipeReplayModelResolver.resolve(root, Collections.singletonList(replayedToken));
        SegmentationMethod replayedMethod = SegmentationTokenParser.parse(replayedToken);
        File runtimeFile = StarDist3DRunner.resolveStarDistModelFile(
                replayedMethod, root.toFile(), null, "IBA1");

        Path expected = catalog.resolve(saved).toAbsolutePath().normalize();
        assertEquals(1, resolved.size());
        assertEquals(RecipeReplayModelResolver.Engine.STARDIST, resolved.get(0).engine);
        assertEquals(saved.modelKey, resolved.get(0).modelKey);
        assertEquals(expected.toString(), resolved.get(0).runtimeArgument);
        assertEquals(expected.toFile().getAbsolutePath(), runtimeFile.getAbsolutePath());
    }

    @Test
    public void recipeReplayResolvesAndUsesCustomCellposeModel() throws Exception {
        Path root = temp.newFolder("cellpose-replay").toPath();
        Path source = temp.newFile("projectModel_microglia_cp_v3").toPath();
        Files.write(source, "cellpose-model".getBytes(StandardCharsets.UTF_8));
        ModelCatalog catalog = ModelCatalogIO.read(root);
        ModelEntry saved = catalog.add(userCellpose("projectModel_microglia_cp_v3"), source);
        ModelCatalogIO.writeProject(root, catalog);
        String token = "cellpose:30.0:0.4:0.0:model=projectModel_microglia_cp_v3";

        String replayedToken = replayedSegmentationToken(root, recipeWithSegmentationToken(token));
        List<RecipeReplayModelResolver.ResolvedModelUse> resolved =
                RecipeReplayModelResolver.resolve(root, Collections.singletonList(replayedToken));
        String runtimeArgument = Cellpose3DRunner.resolvePretrainedModelArgument(
                saved.modelKey, ModelCatalogIO.read(root));

        Path expected = catalog.resolve(saved).toAbsolutePath().normalize();
        assertEquals(1, resolved.size());
        assertEquals(RecipeReplayModelResolver.Engine.CELLPOSE, resolved.get(0).engine);
        assertEquals(saved.modelKey, resolved.get(0).modelKey);
        assertEquals(expected.toString(), resolved.get(0).runtimeArgument);
        assertEquals(expected.toString(), runtimeArgument);
    }

    @Test
    public void recipeReplayWithMissingModelKeyBlocksInsteadOfFallingBack() throws Exception {
        Path root = temp.newFolder("missing-replay").toPath();
        String token = "stardist:0.5:0.3:model=missing_model";

        String replayedToken = replayedSegmentationToken(root, recipeWithSegmentationToken(token));
        try {
            RecipeReplayModelResolver.resolve(root, Collections.singletonList(replayedToken));
            fail("Expected missing replayed model key to block replay.");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("missing_model"));
            assertTrue(expected.getMessage().contains("not found in catalog"));
            assertTrue(expected.getMessage().contains("select a different model"));
        }
    }

    private static PipelineRecipe recipeWithSegmentationToken(String token) {
        return new PipelineRecipe(
                "Custom model replay",
                "Recipe-like replay fixture with a captured segmentation token.",
                PipelineRecipe.CURRENT_FLASH_VERSION,
                Collections.singletonList("ThreeDObject"),
                Collections.singletonMap("segmentation_methods", token));
    }

    private static String replayedSegmentationToken(Path root, PipelineRecipe recipe) {
        BinConfig cfg = new BinConfig();
        cfg.segmentationMethods.add(recipe.getModulePresets().get("segmentation_methods"));
        String command = ReplayCommandFormatter.format(
                root.toAbsolutePath().normalize().toString().replace('\\', '/'), 4, cfg);
        CLIConfig parsed = CLIArgumentParser.parse(extractOptions(command));
        String value = parsed.getBinFieldValue(BinField.SEGMENTATION_METHODS);
        assertEquals(recipe.getModulePresets().get("segmentation_methods"), value);
        assertTrue(parsed.getSelectedAnalyses()[4]);
        return value;
    }

    private static String extractOptions(String command) {
        int start = command.indexOf("\", \"");
        assertTrue("Expected IJ.run command with options string", start >= 0);
        start += 4;
        int end = command.lastIndexOf("\");");
        assertTrue("Expected IJ.run command terminator", end > start);
        return command.substring(start, end);
    }

    private static ModelEntry userStarDist(String key) {
        return new ModelEntry(key, "User StarDist", null,
                ModelEntry.Engine.STARDIST, ModelEntry.Source.USER_IMPORTED,
                null, null, null, null, null,
                defaults(), null, false);
    }

    private static ModelEntry userCellpose(String key) {
        return new ModelEntry(key, "User Cellpose", null,
                ModelEntry.Engine.CELLPOSE, ModelEntry.Source.USER_IMPORTED,
                null, null, null, null, null,
                new LinkedHashMap<String, Object>(), null, false);
    }

    private static Map<String, Object> defaults() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("probThresh", Double.valueOf(0.5));
        out.put("nmsThresh", Double.valueOf(0.3));
        return out;
    }
}
