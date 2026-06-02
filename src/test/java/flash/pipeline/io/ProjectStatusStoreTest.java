package flash.pipeline.io;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
}
