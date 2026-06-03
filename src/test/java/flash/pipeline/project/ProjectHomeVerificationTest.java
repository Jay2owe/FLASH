package flash.pipeline.project;

import flash.pipeline.FLASH_Pipeline;
import flash.pipeline.cli.CLIArgumentParser;
import flash.pipeline.cli.CLIConfig;
import flash.pipeline.io.ProjectStatusStore;
import flash.pipeline.recipes.PipelineRecipe;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ProjectHomeVerificationTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void saveReadRecipeRoundTripRestoresSameSelections() throws Exception {
        File project = temp.newFolder("recipe-round-trip");
        boolean[] selections = new boolean[FLASH_Pipeline.IDX_SPECTRAL_DECONTAMINATION + 1];
        selections[FLASH_Pipeline.IDX_CREATE_BIN] = true;
        selections[FLASH_Pipeline.IDX_SPATIAL] = true;
        selections[FLASH_Pipeline.IDX_INTENSITY] = true;
        selections[FLASH_Pipeline.IDX_EXCEL_EXPORT] = true;

        FLASH_Pipeline pipeline = new FLASH_Pipeline();
        Field directory = FLASH_Pipeline.class.getDeclaredField("directory");
        directory.setAccessible(true);
        directory.set(pipeline, project.getAbsolutePath());
        Method save = FLASH_Pipeline.class.getDeclaredMethod("saveProjectRecipe", boolean[].class);
        save.setAccessible(true);
        save.invoke(pipeline, new Object[]{selections});

        Map<String, Object> restoredJson = ProjectStatusStore.readLastRunRecipe(project.getAbsolutePath());
        assertNotNull(restoredJson);
        boolean[] restored = PipelineRecipe.fromJsonObject(restoredJson)
                .toSelections(selections.length);

        assertArrayEquals(selections, restored);
    }

    @Test
    public void cliDirOptionParsesHeadlessWithoutDialogDependency() throws Exception {
        File project = temp.newFolder("cli-project");

        CLIConfig cfg = CLIArgumentParser.parse("dir=[" + project.getAbsolutePath() + "] run_intensity");

        assertNotNull(cfg);
        assertTrue(cfg.isHeadless());
        assertEquals(project.getCanonicalPath(), cfg.getDirectory());
        assertTrue(cfg.getSelectedAnalyses()[FLASH_Pipeline.IDX_INTENSITY]);
    }
}
