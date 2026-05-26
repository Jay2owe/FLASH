package flash.pipeline.analyses;

import flash.pipeline.io.CsvTableIO;
import ij.measure.ResultsTable;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
    public void objectOutputHelpersUseResultsLayout() throws IOException {
        File project = temp.newFolder("project");

        assertEquals(new File(project, "FLASH/Results/Tables/Objects"),
                ThreeDObjectAnalysis.objectCsvWriteDir(project.getAbsolutePath()));
        assertEquals(new File(project, "FLASH/Results/Analysis Images/Objects"),
                ThreeDObjectAnalysis.objectImageOutputsRoot(project.getAbsolutePath()));
        assertEquals(Collections.singletonList(
                        new File(project, "FLASH/Results/Tables/Objects")),
                ThreeDObjectAnalysis.objectCsvReadDirs(project.getAbsolutePath()));
    }

    @Test
    public void existingObjectOutputCsvsReadsFromTablesObjects() throws IOException {
        File project = temp.newFolder("results-project");
        File tablesObjects = ThreeDObjectAnalysis.objectCsvWriteDir(project.getAbsolutePath());
        assertTrue(tablesObjects.mkdirs());
        File dapi = ThreeDObjectAnalysis.objectOutputCsv(tablesObjects, "DAPI");
        assertTrue(dapi.createNewFile());

        List<File> existing = ThreeDObjectAnalysis.existingObjectOutputCsvs(
                project.getAbsolutePath(), Arrays.asList("DAPI", "NeuN"));

        assertEquals(1, existing.size());
        assertEquals(dapi, existing.get(0));
        assertTrue(ThreeDObjectAnalysis.existingObjectOutputCsv(
                project.getAbsolutePath(), "DAPI").isFile());
        assertFalse(ThreeDObjectAnalysis.allObjectOutputCsvsExist(
                project.getAbsolutePath(), Arrays.asList("DAPI", "NeuN")));
    }

    @Test
    public void writeObjectResultsCsvExtendsExistingRowsWhenRequested() throws Exception {
        File project = temp.newFolder("extend-project");
        File outDir = ThreeDObjectAnalysis.objectCsvWriteDir(project.getAbsolutePath());
        assertTrue(outDir.mkdirs());
        File out = ThreeDObjectAnalysis.objectOutputCsv(outDir, "DAPI");
        Files.write(out.toPath(),
                ("Region,SCN,ManualNote\n"
                        + "SCN,1,keep\n").getBytes(StandardCharsets.UTF_8));

        ResultsTable table = new ResultsTable();
        table.incrementCounter();
        table.setValue("Region", 0, "CA1");
        table.setValue("SCN", 0, 2);
        table.setValue("Volume", 0, 12.5);

        ThreeDObjectAnalysis.writeObjectResultsCsv(project.getAbsolutePath(), out, "DAPI",
                table, Arrays.asList("Region", "SCN", "Volume"), true);

        CsvTableIO.ChannelData loaded = CsvTableIO.loadChannelCsv(out, "DAPI");
        assertNotNull(loaded);
        assertEquals(Arrays.asList("Region", "SCN", "ManualNote", "Volume"), loaded.header);
        assertEquals(2, loaded.rows.size());
        assertEquals("keep", loaded.get(0, "ManualNote"));
        assertEquals("CA1", loaded.get(1, "Region"));
        assertEquals("", loaded.get(1, "ManualNote"));
        assertEquals(12.5, Double.parseDouble(loaded.get(1, "Volume")), 0.0001);
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
