package flash.pipeline.analyses;

import flash.pipeline.analyses.wizard.AggregationConfig;
import flash.pipeline.io.ConditionManifestIO;
import flash.pipeline.io.FlashProjectLayout;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Verifies aggregation applies the current {@code Conditions.csv} to master
 * tables post-hoc, including grouped rows and the condition snapshot.
 */
public class MasterAggregationConditionApplicationTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void appliesSavedConditionsToMasterObjectCsv() throws Exception {
        File root = temp.newFolder("agg-conditions");
        File objects = layout(root).tablesObjectsWriteDir();
        assertTrue(objects.mkdirs());
        writeCsv(new File(objects, "CK1D.csv"),
                "Region,Hemisphere,ROI,Animal Name,Volume (micron^3),Surface (micron^2),IntDen,Mean,XM,YM,ZM",
                "SCN,LH,SCN1,Mouse1,10,5,100,10,1,1,1\n"
                        + "SCN,LH,SCN1,Mouse2,10,5,100,10,1,1,1");

        LinkedHashMap<String, String> conditions = new LinkedHashMap<String, String>();
        conditions.put("Mouse1", "Control");
        conditions.put("Mouse2", "Treated");
        ConditionManifestIO.saveAssignments(root.getAbsolutePath(), conditions);

        runAggregation(root, AggregationConfig.Granularity.ANIMAL);

        List<String> lines = readMaster(root, "3D Objects.csv");
        assertTrue("header must include Condition", lines.get(0).contains("Condition"));
        assertEquals("Control", cell(lines, "Mouse1", "Condition"));
        assertEquals("Treated", cell(lines, "Mouse2", "Condition"));
    }

    @Test
    public void groupedRowsUseParentAnimalCondition() throws Exception {
        File root = temp.newFolder("agg-conditions-hemi");
        File objects = layout(root).tablesObjectsWriteDir();
        assertTrue(objects.mkdirs());
        writeCsv(new File(objects, "CK1D.csv"),
                "Region,Hemisphere,ROI,Animal Name,Volume (micron^3),Surface (micron^2),IntDen,Mean,XM,YM,ZM",
                "SCN,LH,SCN1,Mouse1,10,5,100,10,1,1,1\n"
                        + "SCN,RH,SCN2,Mouse1,10,5,100,10,1,1,1");

        LinkedHashMap<String, String> conditions = new LinkedHashMap<String, String>();
        conditions.put("Mouse1", "Control");
        ConditionManifestIO.saveAssignments(root.getAbsolutePath(), conditions);

        runAggregation(root, AggregationConfig.Granularity.HEMISPHERE);

        List<String> lines = readMaster(root, "3D Objects.csv");
        // Both per-hemisphere rows carry the parent animal's condition.
        assertEquals("Control", cell(lines, "Mouse1-LH", "Condition"));
        assertEquals("Control", cell(lines, "Mouse1-RH", "Condition"));
    }

    @Test
    public void writesConditionSnapshotWithParentAndCondition() throws Exception {
        File root = temp.newFolder("agg-conditions-snapshot");
        File objects = layout(root).tablesObjectsWriteDir();
        assertTrue(objects.mkdirs());
        writeCsv(new File(objects, "CK1D.csv"),
                "Region,Hemisphere,ROI,Animal Name,Volume (micron^3),Surface (micron^2),IntDen,Mean,XM,YM,ZM",
                "SCN,LH,SCN1,Mouse1,10,5,100,10,1,1,1\n"
                        + "SCN,LH,SCN1,Mouse2,10,5,100,10,1,1,1");

        LinkedHashMap<String, String> conditions = new LinkedHashMap<String, String>();
        conditions.put("Mouse1", "Control");
        conditions.put("Mouse2", "Treated");
        ConditionManifestIO.saveAssignments(root.getAbsolutePath(), conditions);

        runAggregation(root, AggregationConfig.Granularity.ANIMAL);

        File snapshot = new File(layout(root).analysisDetailsWriteDir(),
                AggregationConditionSupport.SNAPSHOT_FILENAME);
        assertTrue("snapshot should be written", snapshot.isFile());
        String text = new String(Files.readAllBytes(snapshot.toPath()), StandardCharsets.UTF_8);
        assertTrue(text.contains("RowName,ParentAnimal,Condition,run_id"));
        assertTrue(text.contains("Mouse1,Mouse1,Control"));
        assertTrue(text.contains("Mouse2,Mouse2,Treated"));
    }

    @Test
    public void fallsBackToAutoDetectedConditionWithoutManifest() throws Exception {
        File root = temp.newFolder("agg-conditions-auto");
        File objects = layout(root).tablesObjectsWriteDir();
        assertTrue(objects.mkdirs());
        writeCsv(new File(objects, "CK1D.csv"),
                "Region,Hemisphere,ROI,Animal Name,Volume (micron^3),Surface (micron^2),IntDen,Mean,XM,YM,ZM",
                "SCN,LH,SCN1,Control1,10,5,100,10,1,1,1");

        runAggregation(root, AggregationConfig.Granularity.ANIMAL);

        List<String> lines = readMaster(root, "3D Objects.csv");
        // No manifest: condition auto-detected from the animal name (Control1 -> Control).
        assertEquals("Control", cell(lines, "Control1", "Condition"));
    }

    private void runAggregation(File root, AggregationConfig.Granularity granularity) {
        MasterAggregationAnalysis analysis = new MasterAggregationAnalysis();
        analysis.setSuppressDialogs(true);
        AggregationConfig config = new AggregationConfig();
        config.setGranularity(granularity);
        analysis.setAggregationConfig(config);
        analysis.execute(root.getAbsolutePath());
    }

    private static FlashProjectLayout layout(File root) {
        return FlashProjectLayout.forDirectory(root.getAbsolutePath());
    }

    private static List<String> readMaster(File root, String fileName) throws Exception {
        File file = layout(root).projectSummaryWriteFile(fileName);
        return Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
    }

    /** Name-indexed cell lookup for the row whose AnimalName equals {@code animal}. */
    private static String cell(List<String> lines, String animal, String column) {
        String[] header = lines.get(0).split(",");
        int animalIdx = indexOf(header, "AnimalName");
        int colIdx = indexOf(header, column);
        for (int i = 1; i < lines.size(); i++) {
            String[] row = lines.get(i).split(",");
            if (animalIdx < row.length && row[animalIdx].equals(animal)) {
                return colIdx < row.length ? row[colIdx] : "";
            }
        }
        return null;
    }

    private static int indexOf(String[] header, String name) {
        for (int i = 0; i < header.length; i++) {
            if (header[i].equals(name)) return i;
        }
        return -1;
    }

    private static void writeCsv(File file, String header, String rows) throws Exception {
        PrintWriter pw = new PrintWriter(file);
        try {
            pw.println(header);
            pw.println(rows);
        } finally {
            pw.close();
        }
    }
}
