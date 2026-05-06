package flash.pipeline.analyses;

import flash.pipeline.io.CalibrationIO;
import flash.pipeline.io.CsvSupport;
import flash.pipeline.io.CsvTableIO;
import flash.pipeline.io.FlashProjectLayout;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class MasterAggregationAnalysisTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void execute_countsSectionsFromScnBeforeRegionAndComputesPerMm3() throws Exception {
        File root = temp.newFolder("master-agg-sections");
        File attrs = new File(root, "Data Analysis/Attributes");
        File objects = new File(root, "Data Analysis/Objects");
        assertTrue(attrs.mkdirs());
        assertTrue(objects.mkdirs());

        writeCsv(new File(attrs, "SCN ROI Properties.csv"),
                "Animal Name,Region,ROI Set,SCN,Area (pixel),Area (um^2),Volume (micron^3),Volume (mm^3),Width,Height",
                "Mouse1,LHSCN,SCN,1,10,10,500000000,0.5,1,1\n"
                        + "Mouse1,LHSCN,SCN,2,10,10,500000000,0.5,1,1\n"
                        + "Mouse1,LHSCN,SCN,3,10,10,500000000,0.5,1,1\n"
                        + "Mouse1,LHSCN,SCN,4,10,10,500000000,0.5,1,1");

        writeCsv(new File(objects, "CK1D.csv"),
                "Region,Hemisphere,ROI,Animal Name,Volume (micron^3),Surface (micron^2),IntDen,Mean,XM,YM,ZM",
                "SCN,LH,SCN1,Mouse1,10,5,100,10,1,1,1\n"
                        + "SCN,LH,SCN2,Mouse1,10,5,100,10,1,1,1\n"
                        + "SCN,LH,SCN3,Mouse1,10,5,100,10,1,1,1\n"
                        + "SCN,LH,SCN4,Mouse1,10,5,100,10,1,1,1");

        MasterAggregationAnalysis analysis = new MasterAggregationAnalysis();
        analysis.setSuppressDialogs(true);
        analysis.execute(root.getAbsolutePath());

        List<String> lines = Files.readAllLines(
                aggregationFile(root, "Project_Master_Objects.csv").toPath(),
                StandardCharsets.UTF_8);
        assertEquals(2, lines.size());

        Map<String, String> row = csvRow(lines.get(0), lines.get(1));
        assertEquals("4", row.get("numSections"));
        assertEquals("2", row.get("CK1D_Count_permm3"));
    }

    @Test
    public void chooseRoiPropertiesFile_prefersMatchingAnimalsWhenMultipleFilesExist() throws Exception {
        File root = temp.newFolder("master-agg-roi-choice");
        File attrs = new File(root, "Data Analysis/Attributes");
        assertTrue(attrs.mkdirs());

        writeCsv(new File(attrs, "AAA ROI Properties.csv"),
                "Animal Name,Region,ROI Set,SCN,Area (pixel),Area (um^2),Volume (micron^3),Volume (mm^3),Width,Height",
                "OtherMouse,LHSCN,SCN,1,10,10,250000000,0.25,1,1");
        writeCsv(new File(attrs, "SCN ROI Properties.csv"),
                "Animal Name,Region,ROI Set,SCN,Area (pixel),Area (um^2),Volume (micron^3),Volume (mm^3),Width,Height",
                "Mouse1,LHSCN,SCN,1,10,10,250000000,0.25,1,1");

        MasterAggregationAnalysis analysis = new MasterAggregationAnalysis();
        File chosen = analysis.chooseRoiPropertiesFile(
                root.getAbsolutePath(),
                Collections.singleton("Mouse1"));

        assertNotNull(chosen);
        assertEquals("SCN ROI Properties.csv", chosen.getName());
    }

    @Test
    public void execute_usesPersistedFullStackDepthWhenVolumeColumnMissing() throws Exception {
        File root = temp.newFolder("master-agg-stack-depth");
        File attrs = new File(root, "Data Analysis/Attributes");
        File objects = new File(root, "Data Analysis/Objects");
        assertTrue(attrs.mkdirs());
        assertTrue(objects.mkdirs());

        CalibrationIO.write(objects, 1.0, 1.0, 2.0, 10.0, "um");

        writeCsv(new File(attrs, "SCN ROI Properties.csv"),
                "Animal Name,Region,ROI Set,SCN,Area (pixel),Area (um^2),Width,Height",
                "Mouse1,LHSCN,SCN,1,100000000,100000000,1,1");

        writeCsv(new File(objects, "CK1D.csv"),
                "Region,Hemisphere,ROI,Animal Name,Volume (micron^3),Surface (micron^2),IntDen,Mean,XM,YM,ZM",
                objectRows("Mouse1", 4));

        MasterAggregationAnalysis analysis = new MasterAggregationAnalysis();
        analysis.setSuppressDialogs(true);
        analysis.execute(root.getAbsolutePath());

        List<String> lines = Files.readAllLines(
                aggregationFile(root, "Project_Master_Objects.csv").toPath(),
                StandardCharsets.UTF_8);
        Map<String, String> row = csvRow(lines.get(0), lines.get(1));

        assertEquals(4.0, Double.parseDouble(row.get("CK1D_Count_permm3")), 0.0);

        String details = new String(Files.readAllBytes(
                aggregationFile(root, "Aggregation_Analysis_Details.txt").toPath()),
                StandardCharsets.UTF_8);
        assertTrue(details.contains("persisted fallback stack depth"));
    }

    @Test
    public void execute_skipsFallbackWhenLegacyCalibrationHasNoStackDepth() throws Exception {
        File root = temp.newFolder("master-agg-legacy-cal");
        File attrs = new File(root, "Data Analysis/Attributes");
        File objects = new File(root, "Data Analysis/Objects");
        assertTrue(attrs.mkdirs());
        assertTrue(objects.mkdirs());

        CalibrationIO.write(objects, 1.0, 1.0, 2.0, "um");

        writeCsv(new File(attrs, "SCN ROI Properties.csv"),
                "Animal Name,Region,ROI Set,SCN,Area (pixel),Area (um^2),Width,Height",
                "Mouse1,LHSCN,SCN,1,100000000,100000000,1,1");

        writeCsv(new File(objects, "CK1D.csv"),
                "Region,Hemisphere,ROI,Animal Name,Volume (micron^3),Surface (micron^2),IntDen,Mean,XM,YM,ZM",
                objectRows("Mouse1", 4));

        MasterAggregationAnalysis analysis = new MasterAggregationAnalysis();
        analysis.setSuppressDialogs(true);
        analysis.execute(root.getAbsolutePath());

        List<String> lines = Files.readAllLines(
                aggregationFile(root, "Project_Master_Objects.csv").toPath(),
                StandardCharsets.UTF_8);

        assertFalse(lines.get(0).contains("CK1D_Count_permm3"));
        assertFalse(aggregationFile(root, "Aggregation_Analysis_Details.txt").exists());
    }

    @Test
    public void execute_convertsPixelAreaWhenPhysicalCalibrationIsAvailable() throws Exception {
        File root = temp.newFolder("master-agg-pixel-area");
        File attrs = new File(root, "Data Analysis/Attributes");
        File objects = new File(root, "Data Analysis/Objects");
        assertTrue(attrs.mkdirs());
        assertTrue(objects.mkdirs());

        CalibrationIO.write(objects, 2.0, 3.0, 2.0, 10.0, "um");

        writeCsv(new File(attrs, "SCN ROI Properties.csv"),
                "Animal Name,Region,ROI Set,SCN,Area (pixel),Width,Height",
                "Mouse1,LHSCN,SCN,1,50,1,1");

        writeCsv(new File(objects, "CK1D.csv"),
                "Region,Hemisphere,ROI,Animal Name,Volume (micron^3),Surface (micron^2),IntDen,Mean,XM,YM,ZM",
                objectRows("Mouse1", 3));

        MasterAggregationAnalysis analysis = new MasterAggregationAnalysis();
        analysis.setSuppressDialogs(true);
        analysis.execute(root.getAbsolutePath());

        List<String> lines = Files.readAllLines(
                aggregationFile(root, "Project_Master_Objects.csv").toPath(),
                StandardCharsets.UTF_8);
        Map<String, String> row = csvRow(lines.get(0), lines.get(1));

        assertEquals(1000000.0, Double.parseDouble(row.get("CK1D_Count_permm3")), 0.0);
    }

    @Test
    public void execute_skipsPixelAreaFallbackWithoutPhysicalCalibration() throws Exception {
        File root = temp.newFolder("master-agg-pixel-area-skip");
        File attrs = new File(root, "Data Analysis/Attributes");
        File objects = new File(root, "Data Analysis/Objects");
        assertTrue(attrs.mkdirs());
        assertTrue(objects.mkdirs());

        writeCsv(new File(attrs, "SCN ROI Properties.csv"),
                "Animal Name,Region,ROI Set,SCN,Area (pixel),Width,Height",
                "Mouse1,LHSCN,SCN,1,50,1,1");

        writeCsv(new File(objects, "CK1D.csv"),
                "Region,Hemisphere,ROI,Animal Name,Volume (micron^3),Surface (micron^2),IntDen,Mean,XM,YM,ZM",
                objectRows("Mouse1", 3));

        MasterAggregationAnalysis analysis = new MasterAggregationAnalysis();
        analysis.setSuppressDialogs(true);
        analysis.execute(root.getAbsolutePath());

        List<String> lines = Files.readAllLines(
                aggregationFile(root, "Project_Master_Objects.csv").toPath(),
                StandardCharsets.UTF_8);

        assertFalse(lines.get(0).contains("CK1D_Count_permm3"));
        assertFalse(aggregationFile(root, "Aggregation_Analysis_Details.txt").exists());
    }

    @Test
    public void execute_quotesAnimalNamesInMasterCsv() throws Exception {
        File root = temp.newFolder("master-agg-quoted-animal");
        File attrs = new File(root, "Data Analysis/Attributes");
        File objects = new File(root, "Data Analysis/Objects");
        assertTrue(attrs.mkdirs());
        assertTrue(objects.mkdirs());

        String animal = "Mouse, \"Alpha\"";
        writeCsvRows(new File(attrs, "SCN ROI Properties.csv"),
                Arrays.asList("Animal Name", "Region", "ROI Set", "SCN", "Area (pixel)", "Area (um^2)",
                        "Volume (micron^3)", "Volume (mm^3)", "Width", "Height"),
                Arrays.asList(
                        Arrays.asList(animal, "LHSCN", "SCN", "1", "10", "10", "500000000", "0.5", "1", "1")));
        writeCsvRows(new File(objects, "CK1D.csv"),
                Arrays.asList("Region", "Hemisphere", "ROI", "Animal Name", "Volume (micron^3)",
                        "Surface (micron^2)", "IntDen", "Mean", "XM", "YM", "ZM"),
                Arrays.asList(
                        Arrays.asList("SCN", "LH", "SCN1", animal, "10", "5", "100", "10", "1", "1", "1")));

        MasterAggregationAnalysis analysis = new MasterAggregationAnalysis();
        analysis.setSuppressDialogs(true);
        analysis.execute(root.getAbsolutePath());

        List<String> lines = Files.readAllLines(
                aggregationFile(root, "Project_Master_Objects.csv").toPath(),
                StandardCharsets.UTF_8);
        assertEquals(2, lines.size());

        String[] header = CsvTableIO.parseCsvLine(lines.get(0));
        String[] row = CsvTableIO.parseCsvLine(lines.get(1));
        assertEquals(header.length, row.length);
        assertEquals(animal, csvRow(lines.get(0), lines.get(1)).get("AnimalName"));
    }

    @Test
    public void execute_readsNewObjectIntensityAndLineDistanceOutputsBeforeLegacy() throws Exception {
        File root = temp.newFolder("master-agg-new-layout");
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(root.getAbsolutePath());
        File attrs = new File(root, "FLASH/01 - Regions of Interest/Attributes");
        File objects = layout.objectDataWriteDir();
        File intensities = layout.intensityDataWriteDir();
        File lineDistances = layout.lineDistanceWriteDir();
        assertTrue(attrs.mkdirs());
        assertTrue(objects.mkdirs());
        assertTrue(intensities.mkdirs());
        assertTrue(lineDistances.mkdirs());

        writeCsv(new File(attrs, "SCN ROI Properties.csv"),
                "Animal Name,Region,ROI Set,SCN,Area (pixel),Area (um^2),Volume (micron^3),Volume (mm^3),Width,Height",
                "Mouse1,LHSCN,SCN,1,10,10,500000000,0.5,1,1");

        writeCsv(new File(objects, "CK1D.csv"),
                "Region,Hemisphere,ROI,Animal Name,Volume (micron^3),Surface (micron^2),IntDen,Mean,XM,YM,ZM",
                "SCN,LH,SCN1,Mouse1,10,5,100,10,1,1,1");
        writeCsv(new File(lineDistances, "CK1D.csv"),
                "Region,Hemisphere,ROI,Animal Name,Volume (micron^3),Surface (micron^2),IntDen,Mean,XM,YM,ZM,CK1D_DistTo_Line1",
                "SCN,LH,SCN1,Mouse1,10,5,100,10,1,1,1,25");
        writeCsv(new File(intensities, "GFAP.csv"),
                "Animal Name,ROI,Region,SCN,Hemisphere,IntDen,%Area,RawIntDen",
                "Mouse1,SCN1,SCN,1,LH,40,7,80");

        File legacyObjects = new File(root, "Data Analysis/Objects");
        assertTrue(legacyObjects.mkdirs());
        writeCsv(new File(legacyObjects, "CK1D.csv"),
                "Region,Hemisphere,ROI,Animal Name,Volume (micron^3),Surface (micron^2),IntDen,Mean,XM,YM,ZM",
                "SCN,LH,SCN1,LegacyDuplicate,10,5,100,10,1,1,1");
        writeCsv(new File(legacyObjects, "LegacyOnly.csv"),
                "Region,Hemisphere,ROI,Animal Name,Volume (micron^3),Surface (micron^2),IntDen,Mean,XM,YM,ZM",
                "SCN,LH,SCN1,LegacyMouse,10,5,100,10,1,1,1");

        MasterAggregationAnalysis analysis = new MasterAggregationAnalysis();
        analysis.setSuppressDialogs(true);
        analysis.execute(root.getAbsolutePath());

        List<String> objectLines = Files.readAllLines(
                aggregationFile(root, "Project_Master_Objects.csv").toPath(),
                StandardCharsets.UTF_8);
        String objectHeader = objectLines.get(0);
        String intensityHeader = Files.readAllLines(
                aggregationFile(root, "Project_Master_Intensities.csv").toPath(),
                StandardCharsets.UTF_8).get(0);

        assertTrue(objectHeader.contains("CK1D_Count"));
        assertTrue(objectHeader.contains("CK1D_DistTo_Line1Mean"));
        assertTrue(objectHeader.contains("LegacyOnly_Count"));
        assertTrue(intensityHeader.contains("GFAP_ROI_IntDenMean"));
        assertFalse(objectLines.toString().contains("LegacyDuplicate"));
        assertFalse(new File(root, "ImageJ Exports/Project_Master_Objects.csv").exists());
    }

    private void writeCsv(File file, String header, String rows) throws Exception {
        PrintWriter pw = new PrintWriter(file, "UTF-8");
        try {
            pw.println(header);
            pw.print(rows);
            if (!rows.endsWith("\n")) {
                pw.println();
            }
        } finally {
            pw.close();
        }
    }

    private String objectRows(String animal, int rows) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= rows; i++) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("SCN,LH,SCN").append(i).append(',')
                    .append(animal)
                    .append(",10,5,100,10,1,1,1");
        }
        return sb.toString();
    }

    private void writeCsvRows(File file, List<String> header, List<List<String>> rows) throws Exception {
        PrintWriter pw = CsvSupport.newWriter(file);
        try {
            pw.println(CsvSupport.joinRow(header));
            for (List<String> row : rows) {
                pw.println(CsvSupport.joinRow(new ArrayList<String>(row)));
            }
        } finally {
            pw.close();
        }
    }

    private Map<String, String> csvRow(String header, String row) {
        String[] headers = CsvTableIO.parseCsvLine(header);
        String[] values = CsvTableIO.parseCsvLine(row);
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (int i = 0; i < headers.length; i++) {
            out.put(headers[i], i < values.length ? values[i] : "");
        }
        return out;
    }

    private File aggregationFile(File root, String fileName) {
        return new File(FlashProjectLayout.forDirectory(root.getAbsolutePath()).aggregationWriteDir(), fileName);
    }
}
