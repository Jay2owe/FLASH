package flash.pipeline.io;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ProjectStatusStoreTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void writesSectionsIntoSingleStatusJsonAndPreservesExistingSections() throws Exception {
        File project = temp.newFolder("project-status");

        ProjectStatusStore.setMarker(project, "noInputFolderPrompt", true);

        Map<String, Object> analysis = new LinkedHashMap<String, Object>();
        analysis.put("binHash", "abc123");
        analysis.put("ranAt", "2026-06-02T12:00:00Z");
        analysis.put("imageCount", Integer.valueOf(4));
        ProjectStatusStore.writeAnalysisStatus(project, "createBin", analysis);

        ProjectStatusStore.writeCliStatus(project, false,
                Collections.singletonList("3D Object Analysis"), "failed");

        File status = ProjectStatusStore.statusFile(project);
        assertTrue(status.isFile());
        assertEquals(new File(project, "FLASH/.settings/status.json").getAbsolutePath(),
                status.getAbsolutePath());
        assertFalse(new File(project, "FLASH/Status").exists());

        assertTrue(ProjectStatusStore.hasMarker(project, "noInputFolderPrompt"));
        assertEquals("abc123",
                ProjectStatusStore.readAnalysisStatus(project, "createBin").get("binHash"));

        @SuppressWarnings("unchecked")
        Map<String, Object> cliStatus = (Map<String, Object>)
                ProjectStatusStore.load(project).get(ProjectStatusStore.SECTION_CLI_STATUS);
        assertEquals(Boolean.FALSE, cliStatus.get("ok"));
        assertEquals("failed", cliStatus.get("reason"));
        assertEquals(Collections.singletonList("3D Object Analysis"), cliStatus.get("failed"));
    }

    @Test
    public void readLastRunRecipeReturnsStoredRecipeObject() throws Exception {
        File project = temp.newFolder("project-status-recipe");
        Map<String, Object> recipe = new LinkedHashMap<String, Object>();
        recipe.put("name", "last-run");
        recipe.put("analyses", Arrays.asList("CreateBin", "Intensity"));

        ProjectStatusStore.writeLastRunRecipe(project.getAbsolutePath(), recipe);

        Map<String, Object> restored = ProjectStatusStore.readLastRunRecipe(project.getAbsolutePath());
        assertEquals("last-run", restored.get("name"));
        assertEquals(Arrays.asList("CreateBin", "Intensity"), restored.get("analyses"));
    }

    @Test
    public void readLastRunRecipeSoftFailsToNullForMissingOrInvalidStatus() throws Exception {
        File missing = temp.newFolder("project-status-missing-recipe");
        assertNull(ProjectStatusStore.readLastRunRecipe(missing.getAbsolutePath()));

        File invalid = temp.newFolder("project-status-invalid-recipe");
        File status = ProjectStatusStore.statusFile(invalid);
        Files.createDirectories(status.getParentFile().toPath());
        Files.write(status.toPath(), "{not json".getBytes(StandardCharsets.UTF_8));

        assertNull(ProjectStatusStore.readLastRunRecipe(invalid.getAbsolutePath()));
    }
}
