package flash.pipeline.analyses;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests for the whole-analysis skip helper in {@link ThreeDObjectAnalysis}.
 */
public class ThreeDObjectAnalysisSkipExistingTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void allOutputCsvsExist_trueWhenEveryChannelCsvExists() throws IOException {
        File outDir = temp.newFolder("Data Analysis");
        List<String> channels = Arrays.asList("DAPI", "NeuN", "cFos");

        for (String ch : channels) {
            assertTrue(ThreeDObjectAnalysis.objectOutputCsv(outDir, ch).createNewFile());
        }

        assertTrue(ThreeDObjectAnalysis.allOutputCsvsExist(outDir, channels));
    }

    @Test
    public void allOutputCsvsExist_falseWhenAnyChannelCsvIsMissing() throws IOException {
        File outDir = temp.newFolder("Data Analysis");
        List<String> channels = Arrays.asList("DAPI", "NeuN", "cFos");

        // Only create two of three
        assertTrue(ThreeDObjectAnalysis.objectOutputCsv(outDir, "DAPI").createNewFile());
        assertTrue(ThreeDObjectAnalysis.objectOutputCsv(outDir, "NeuN").createNewFile());

        assertFalse(ThreeDObjectAnalysis.allOutputCsvsExist(outDir, channels));
    }

    @Test
    public void objectOutputHelpersUseFlashLayoutWithLegacyReadFallbacks() throws IOException {
        File project = temp.newFolder("project");

        assertEquals(new File(project, "FLASH/Image Analysis/3D Objects/Objects"),
                ThreeDObjectAnalysis.objectCsvWriteDir(project.getAbsolutePath()));
        assertEquals(new File(project, "FLASH/Image Analysis/3D Objects/Image Outputs"),
                ThreeDObjectAnalysis.objectImageOutputWriteRoot(project.getAbsolutePath()));
        assertEquals(Arrays.asList(
                        new File(project, "FLASH/Image Analysis/3D Objects/Objects"),
                        new File(project, "FLASH/05 - 3D Object Analysis/Objects"),
                        new File(project, "Data Analysis/Objects")),
                ThreeDObjectAnalysis.objectCsvReadDirs(project.getAbsolutePath()));
        assertEquals(Arrays.asList(
                        new File(project, "FLASH/Image Analysis/3D Objects/Image Outputs"),
                        new File(project, "FLASH/05 - 3D Object Analysis/Image Outputs"),
                        new File(project, "Image Analysis")),
                ThreeDObjectAnalysis.objectImageOutputReadRoots(project.getAbsolutePath()));
    }

    @Test
    public void allOutputCsvsExist_falseWhenNoCsvsExist() {
        File outDir = temp.getRoot();
        List<String> channels = Arrays.asList("DAPI", "NeuN");

        assertFalse(ThreeDObjectAnalysis.allOutputCsvsExist(outDir, channels));
    }

    @Test
    public void allOutputCsvsExist_trueForEmptyChannelList() {
        // Edge case: no channels means vacuously true
        File outDir = temp.getRoot();

        assertTrue(ThreeDObjectAnalysis.allOutputCsvsExist(outDir,
                Collections.<String>emptyList()));
    }

    @Test
    public void allOutputCsvsExist_falseWhenOutDirDoesNotExist() {
        File outDir = new File(temp.getRoot(), "nonexistent");
        List<String> channels = Arrays.asList("DAPI");

        assertFalse(ThreeDObjectAnalysis.allOutputCsvsExist(outDir, channels));
    }
}
