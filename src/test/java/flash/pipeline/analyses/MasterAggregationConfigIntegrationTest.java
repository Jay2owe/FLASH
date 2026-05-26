package flash.pipeline.analyses;

import flash.pipeline.analyses.wizard.AggregationConfig;
import flash.pipeline.io.FlashProjectLayout;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MasterAggregationConfigIntegrationTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void composeGroupKey_animalGranularityIsPassThrough() {
        String[] row = {"Mouse1", "SCN", "LH", "ROI1", "5"};
        String key = MasterAggregationAnalysis.composeGroupKey(
                "Mouse1", AggregationConfig.Granularity.ANIMAL,
                row, Integer.valueOf(2), Integer.valueOf(1),
                Integer.valueOf(4), Integer.valueOf(3));
        assertEquals("Mouse1", key);
    }

    @Test
    public void composeGroupKey_hemisphereUsesHemisphereColumn() {
        String[] row = {"Mouse1", "SCN", "LH", "ROI1", "5"};
        String key = MasterAggregationAnalysis.composeGroupKey(
                "Mouse1", AggregationConfig.Granularity.HEMISPHERE,
                row, Integer.valueOf(2), Integer.valueOf(1),
                Integer.valueOf(4), Integer.valueOf(3));
        assertEquals("Mouse1-LH", key);
    }

    @Test
    public void composeGroupKey_hemisphereFallsBackToRoiName() {
        String[] row = {"Mouse1", "SCN", "", "LH_ROI1", "5"};
        String key = MasterAggregationAnalysis.composeGroupKey(
                "Mouse1", AggregationConfig.Granularity.HEMISPHERE,
                row, Integer.valueOf(2), Integer.valueOf(1),
                Integer.valueOf(4), Integer.valueOf(3));
        assertEquals("Mouse1-LH", key);
    }

    @Test
    public void composeGroupKey_regionUsesRegionColumn() {
        String[] row = {"Mouse1", "SCN", "LH", "ROI1", "5"};
        String key = MasterAggregationAnalysis.composeGroupKey(
                "Mouse1", AggregationConfig.Granularity.REGION,
                row, Integer.valueOf(2), Integer.valueOf(1),
                Integer.valueOf(4), Integer.valueOf(3));
        assertEquals("Mouse1-SCN", key);
    }

    @Test
    public void composeGroupKey_sectionUsesScnColumn() {
        String[] row = {"Mouse1", "SCN", "LH", "ROI1", "7"};
        String key = MasterAggregationAnalysis.composeGroupKey(
                "Mouse1", AggregationConfig.Granularity.SECTION,
                row, Integer.valueOf(2), Integer.valueOf(1),
                Integer.valueOf(4), Integer.valueOf(3));
        assertEquals("Mouse1-7", key);
    }

    @Test
    public void execute_perHemisphereGranularityEmitsOneRowPerHemisphere() throws Exception {
        File root = temp.newFolder("agg-hemisphere");
        File attrs = roiTables(root);
        File objects = FlashProjectLayout.forDirectory(root.getAbsolutePath()).tablesObjectsWriteDir();
        assertTrue(attrs.mkdirs());
        assertTrue(objects.mkdirs());

        writeCsv(new File(attrs, "SCN ROI Properties.csv"),
                "Animal Name,Region,ROI Set,SCN,Area (pixel),Area (um^2),Volume (micron^3),Volume (mm^3),Width,Height",
                "Mouse1,LHSCN,SCN,1,10,10,500000000,0.5,1,1\n"
                        + "Mouse1,RHSCN,SCN,1,10,10,500000000,0.5,1,1");

        writeCsv(new File(objects, "CK1D.csv"),
                "Region,Hemisphere,ROI,Animal Name,Volume (micron^3),Surface (micron^2),IntDen,Mean,XM,YM,ZM",
                "SCN,LH,SCN1,Mouse1,10,5,100,10,1,1,1\n"
                        + "SCN,LH,SCN1,Mouse1,10,5,100,10,1,1,1\n"
                        + "SCN,RH,SCN2,Mouse1,10,5,100,10,1,1,1");

        MasterAggregationAnalysis analysis = new MasterAggregationAnalysis();
        analysis.setSuppressDialogs(true);
        AggregationConfig config = new AggregationConfig();
        config.setGranularity(AggregationConfig.Granularity.HEMISPHERE);
        analysis.setAggregationConfig(config);
        analysis.execute(root.getAbsolutePath());

        List<String> lines = Files.readAllLines(
                aggregationFile(root, "3D Objects.csv").toPath(),
                StandardCharsets.UTF_8);
        // Header + two rows (Mouse1-LH, Mouse1-RH)
        assertEquals(3, lines.size());
        boolean hasLH = false, hasRH = false;
        for (int i = 1; i < lines.size(); i++) {
            String[] cols = lines.get(i).split(",");
            if (cols[0].equals("Mouse1-LH")) hasLH = true;
            if (cols[0].equals("Mouse1-RH")) hasRH = true;
        }
        assertTrue("Expected Mouse1-LH row", hasLH);
        assertTrue("Expected Mouse1-RH row", hasRH);
    }

    @Test
    public void execute_rawOnlyOutputModeDropsPerMm3Columns() throws Exception {
        File root = temp.newFolder("agg-raw-only");
        File attrs = roiTables(root);
        File objects = FlashProjectLayout.forDirectory(root.getAbsolutePath()).tablesObjectsWriteDir();
        assertTrue(attrs.mkdirs());
        assertTrue(objects.mkdirs());

        writeCsv(new File(attrs, "SCN ROI Properties.csv"),
                "Animal Name,Region,ROI Set,SCN,Area (pixel),Area (um^2),Volume (micron^3),Volume (mm^3),Width,Height",
                "Mouse1,LHSCN,SCN,1,10,10,500000000,0.5,1,1");

        writeCsv(new File(objects, "CK1D.csv"),
                "Region,Hemisphere,ROI,Animal Name,Volume (micron^3),Surface (micron^2),IntDen,Mean,XM,YM,ZM",
                "SCN,LH,SCN1,Mouse1,10,5,100,10,1,1,1");

        MasterAggregationAnalysis analysis = new MasterAggregationAnalysis();
        analysis.setSuppressDialogs(true);
        AggregationConfig config = new AggregationConfig();
        config.setOutputMode(AggregationConfig.OutputMode.RAW_ONLY);
        analysis.setAggregationConfig(config);
        analysis.execute(root.getAbsolutePath());

        List<String> lines = Files.readAllLines(
                aggregationFile(root, "3D Objects.csv").toPath(),
                StandardCharsets.UTF_8);
        String header = lines.get(0);
        assertFalse("RAW_ONLY output must not contain any _permm3 columns",
                header.contains("_permm3"));
        assertTrue("RAW_ONLY output should still contain raw summable columns",
                header.contains("CK1D_Count"));
    }

    @Test
    public void execute_permm3OnlyOutputModeDropsRawSummables() throws Exception {
        File root = temp.newFolder("agg-permm3-only");
        File attrs = roiTables(root);
        File objects = FlashProjectLayout.forDirectory(root.getAbsolutePath()).tablesObjectsWriteDir();
        assertTrue(attrs.mkdirs());
        assertTrue(objects.mkdirs());

        writeCsv(new File(attrs, "SCN ROI Properties.csv"),
                "Animal Name,Region,ROI Set,SCN,Area (pixel),Area (um^2),Volume (micron^3),Volume (mm^3),Width,Height",
                "Mouse1,LHSCN,SCN,1,10,10,500000000,0.5,1,1");

        writeCsv(new File(objects, "CK1D.csv"),
                "Region,Hemisphere,ROI,Animal Name,Volume (micron^3),Surface (micron^2),IntDen,Mean,XM,YM,ZM",
                "SCN,LH,SCN1,Mouse1,10,5,100,10,1,1,1");

        MasterAggregationAnalysis analysis = new MasterAggregationAnalysis();
        analysis.setSuppressDialogs(true);
        AggregationConfig config = new AggregationConfig();
        config.setOutputMode(AggregationConfig.OutputMode.PERMM3_ONLY);
        analysis.setAggregationConfig(config);
        analysis.execute(root.getAbsolutePath());

        List<String> lines = Files.readAllLines(
                aggregationFile(root, "3D Objects.csv").toPath(),
                StandardCharsets.UTF_8);
        String header = lines.get(0);
        assertTrue("PERMM3_ONLY must contain _permm3 columns", header.contains("_permm3"));
        // Raw summable column CK1D_Count should be excluded — only its _permm3 companion remains
        String[] cols = header.split(",");
        boolean hasRawCount = false, hasPerMm3Count = false;
        for (String c : cols) {
            if (c.equals("CK1D_Count")) hasRawCount = true;
            if (c.equals("CK1D_Count_permm3")) hasPerMm3Count = true;
        }
        assertFalse("PERMM3_ONLY should drop raw summables", hasRawCount);
        assertTrue("PERMM3_ONLY should keep _permm3 companions", hasPerMm3Count);
    }

    @Test
    public void parseHemisphereFromRoi_handlesTokenizedNames() {
        assertEquals("LH", MasterAggregationAnalysis.parseHemisphereFromRoi("LH_SCN_1"));
        assertEquals("RH", MasterAggregationAnalysis.parseHemisphereFromRoi("Mouse_RH_ROI2"));
        assertEquals("", MasterAggregationAnalysis.parseHemisphereFromRoi("UnknownShape"));
        assertEquals("", MasterAggregationAnalysis.parseHemisphereFromRoi(""));
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

    private static File aggregationFile(File root, String fileName) {
        return FlashProjectLayout.forDirectory(root.getAbsolutePath()).projectSummaryWriteFile(fileName);
    }

    private static File roiTables(File root) {
        return FlashProjectLayout.forDirectory(root.getAbsolutePath()).tablesRoiWriteDir();
    }
}
