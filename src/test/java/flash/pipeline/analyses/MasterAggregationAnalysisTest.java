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
    public void hideImageWindowsFlagAloneDoesNotSuppressAggregationConfigDialog() {
        assertTrue(MasterAggregationAnalysis.canShowGuiDialog(false, false, false));
        assertFalse(MasterAggregationAnalysis.canShowGuiDialog(true, false, false));
        assertFalse(MasterAggregationAnalysis.canShowGuiDialog(false, true, false));
        assertFalse(MasterAggregationAnalysis.canShowGuiDialog(false, false, true));
    }

    @Test
    public void execute_countsSectionsFromScnBeforeRegionAndComputesPerMm3() throws Exception {
        File root = temp.newFolder("master-agg-sections");
        File attrs = roiTables(root);
        File objects = FlashProjectLayout.forDirectory(root.getAbsolutePath()).tablesObjectsWriteDir();
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
                aggregationFile(root, "3D Objects.csv").toPath(),
                StandardCharsets.UTF_8);
        assertEquals(2, lines.size());

        Map<String, String> row = csvRow(lines.get(0), lines.get(1));
        assertEquals("4", row.get("numSections"));
        assertEquals("2", row.get("CK1D_Count_permm3"));
    }

    @Test
    public void execute_ignoresZeroVolumeObjectRowsWhenCountingObjects() throws Exception {
        File root = temp.newFolder("master-agg-zero-volume-objects");
        File objects = FlashProjectLayout.forDirectory(root.getAbsolutePath()).tablesObjectsWriteDir();
        assertTrue(objects.mkdirs());

        writeCsv(new File(objects, "CK1D.csv"),
                "Region,Hemisphere,ROI,Animal Name,SCN,Label,Volume (micron^3),Surface (micron^2),IntDen,Mean,XM,YM,ZM",
                "SCN,LH,SCN1,Mouse1,1,1,0,0,0,0,0,0,0\n"
                        + "SCN,LH,SCN1,Mouse1,1,2,NaN,0,0,0,0,0,0\n"
                        + "SCN,LH,SCN1,Mouse1,1,3,10,5,100,10,1,1,1");

        MasterAggregationAnalysis analysis = new MasterAggregationAnalysis();
        analysis.setSuppressDialogs(true);
        analysis.execute(root.getAbsolutePath());

        List<String> lines = Files.readAllLines(
                aggregationFile(root, "3D Objects.csv").toPath(),
                StandardCharsets.UTF_8);
        Map<String, String> row = csvRow(lines.get(0), lines.get(1));

        assertEquals("1", row.get("CK1D_Count"));
        assertEquals("10", row.get("CK1D_VolumeTotal"));
        assertEquals("5", row.get("CK1D_SurfaceTotal"));
    }

    @Test
    public void execute_aggregatesAllNumericObjectAndSpatialMetrics() throws Exception {
        File root = temp.newFolder("master-agg-object-spatial-metrics");
        File objects = FlashProjectLayout.forDirectory(root.getAbsolutePath()).tablesObjectsWriteDir();
        assertTrue(objects.mkdirs());

        writeCsv(new File(objects, "A.csv"),
                "Region,Hemisphere,ROI,Animal Name,SCN,Label,Volume (micron^3),Surface (micron^2),"
                        + "IntDen,Mean,XM,YM,ZM,B-width,B-height,B-volume (voxels),Length,"
                        + "A_CPCColoc_B,A_CPCContains_B,A_CPCTargetsHit,A_CPCPattern,"
                        + "A_BBColoc_B,A_BBColoc30_B,A_BBCPCColoc_B,A_BBCPCContains_B,"
                        + "A_BBVolColoc_B,A_BBVolColocTotal_B,A_BBVolColoc30_B,A_VolContains30_B,"
                        + "Morph_Sphericity,Morph_NewComposite_A_B,Voronoi_TerritoryArea_um2,"
                        + "Cluster,MorphTexture_ClassLabel,CustomFutureScore",
                "SCN,LH,SCN1,Mouse1,1,1,10,5,100,10,1,1,3,2,4,8,100,"
                        + "1,2,1,B,55,1,0,1,25,40,0,2,0.5,9,100,2,1,7\n"
                        + "SCN,LH,SCN1,Mouse1,1,2,20,7,200,20,3,5,5,6,8,48,200,"
                        + "0,1,0,None,15,0,1,3,35,60,1,3,0.7,11,200,2,2,9");

        MasterAggregationAnalysis analysis = new MasterAggregationAnalysis();
        analysis.setSuppressDialogs(true);
        analysis.execute(root.getAbsolutePath());

        List<String> lines = Files.readAllLines(
                aggregationFile(root, "3D Objects.csv").toPath(),
                StandardCharsets.UTF_8);
        Map<String, String> row = csvRow(lines.get(0), lines.get(1));

        assertEquals("2", row.get("A_Count"));
        assertEquals(4.0, Double.parseDouble(row.get("A_ZMMean")), 0.0001);
        assertEquals(4.0, Double.parseDouble(row.get("A_B-widthMean")), 0.0001);
        assertEquals(28.0, Double.parseDouble(row.get("A_B-volume (voxels)Mean")), 0.0001);
        assertEquals(150.0, Double.parseDouble(row.get("A_LengthMean")), 0.0001);
        assertEquals("1", row.get("A_CPCColoc_BCount"));
        assertEquals(50.0, Double.parseDouble(row.get("A_CPCColoc_B%")), 0.0001);
        assertEquals("3", row.get("A_CPCContains_BTotal"));
        assertEquals(1.5, Double.parseDouble(row.get("A_CPCContains_BMean")), 0.0001);
        assertEquals("1", row.get("A_CPCTargetsHitTotal"));
        assertEquals("1", row.get("A_BBColoc30_BCount"));
        assertEquals(35.0, Double.parseDouble(row.get("A_BBColoc_BMean")), 0.0001);
        assertEquals("1", row.get("A_BBCPCColoc_BCount"));
        assertEquals("4", row.get("A_BBCPCContains_BTotal"));
        assertEquals(30.0, Double.parseDouble(row.get("A_BBVolColoc_BMean")), 0.0001);
        assertEquals(50.0, Double.parseDouble(row.get("A_BBVolColocTotal_BMean")), 0.0001);
        assertEquals("1", row.get("A_BBVolColoc30_BCount"));
        assertEquals("5", row.get("A_VolContains_BTotal"));
        assertEquals(2.5, Double.parseDouble(row.get("A_VolContains_BMean")), 0.0001);
        assertEquals(0.6, Double.parseDouble(row.get("A_Morph_SphericityMean")), 0.0001);
        assertEquals(10.0, Double.parseDouble(row.get("Morph_NewComposite_A_BMean")), 0.0001);
        assertFalse(row.containsKey("A_Morph_NewComposite_A_BMean"));
        assertEquals(150.0, Double.parseDouble(row.get("A_Voronoi_TerritoryArea_um2Mean")), 0.0001);
        assertEquals("2", row.get("A_ClusterMode"));
        assertEquals("1", row.get("A_MorphTexture_ClassLabelMode"));
        assertEquals(8.0, Double.parseDouble(row.get("A_CustomFutureScoreMean")), 0.0001);
        assertFalse(row.containsKey("A_CPCPatternMean"));
    }

    @Test
    public void chooseRoiPropertiesFile_prefersMatchingAnimalsWhenMultipleFilesExist() throws Exception {
        File root = temp.newFolder("master-agg-roi-choice");
        File attrs = roiTables(root);
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
        File attrs = roiTables(root);
        File objects = FlashProjectLayout.forDirectory(root.getAbsolutePath()).tablesObjectsWriteDir();
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
                aggregationFile(root, "3D Objects.csv").toPath(),
                StandardCharsets.UTF_8);
        Map<String, String> row = csvRow(lines.get(0), lines.get(1));

        assertEquals(4.0, Double.parseDouble(row.get("CK1D_Count_permm3")), 0.0);

        String details = new String(Files.readAllBytes(
                aggregationDetailsFile(root).toPath()),
                StandardCharsets.UTF_8);
        assertTrue(details.contains("persisted fallback stack depth"));
    }

    @Test
    public void execute_skipsFallbackWhenLegacyCalibrationHasNoStackDepth() throws Exception {
        File root = temp.newFolder("master-agg-legacy-cal");
        File attrs = roiTables(root);
        File objects = FlashProjectLayout.forDirectory(root.getAbsolutePath()).tablesObjectsWriteDir();
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
                aggregationFile(root, "3D Objects.csv").toPath(),
                StandardCharsets.UTF_8);

        assertFalse(lines.get(0).contains("CK1D_Count_permm3"));
        assertFalse(aggregationDetailsFile(root).exists());
    }

    @Test
    public void execute_convertsPixelAreaWhenPhysicalCalibrationIsAvailable() throws Exception {
        File root = temp.newFolder("master-agg-pixel-area");
        File attrs = roiTables(root);
        File objects = FlashProjectLayout.forDirectory(root.getAbsolutePath()).tablesObjectsWriteDir();
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
                aggregationFile(root, "3D Objects.csv").toPath(),
                StandardCharsets.UTF_8);
        Map<String, String> row = csvRow(lines.get(0), lines.get(1));

        assertEquals(1000000.0, Double.parseDouble(row.get("CK1D_Count_permm3")), 0.0);
    }

    @Test
    public void execute_skipsPixelAreaFallbackWithoutPhysicalCalibration() throws Exception {
        File root = temp.newFolder("master-agg-pixel-area-skip");
        File attrs = roiTables(root);
        File objects = FlashProjectLayout.forDirectory(root.getAbsolutePath()).tablesObjectsWriteDir();
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
                aggregationFile(root, "3D Objects.csv").toPath(),
                StandardCharsets.UTF_8);

        assertFalse(lines.get(0).contains("CK1D_Count_permm3"));
        assertFalse(aggregationDetailsFile(root).exists());
    }

    @Test
    public void execute_quotesAnimalNamesInMasterCsv() throws Exception {
        File root = temp.newFolder("master-agg-quoted-animal");
        File attrs = roiTables(root);
        File objects = FlashProjectLayout.forDirectory(root.getAbsolutePath()).tablesObjectsWriteDir();
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
                aggregationFile(root, "3D Objects.csv").toPath(),
                StandardCharsets.UTF_8);
        assertEquals(2, lines.size());

        String[] header = CsvTableIO.parseCsvLine(lines.get(0));
        String[] row = CsvTableIO.parseCsvLine(lines.get(1));
        assertEquals(header.length, row.length);
        assertEquals(animal, csvRow(lines.get(0), lines.get(1)).get("AnimalName"));
    }

    @Test
    public void execute_prefixesFormulaLikeAnimalNamesInMasterCsv() throws Exception {
        File root = temp.newFolder("master-agg-formula-animal");
        File attrs = roiTables(root);
        File objects = FlashProjectLayout.forDirectory(root.getAbsolutePath()).tablesObjectsWriteDir();
        assertTrue(attrs.mkdirs());
        assertTrue(objects.mkdirs());

        String animal = "=HYPERLINK(\"http://example.com\")";
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
                aggregationFile(root, "3D Objects.csv").toPath(),
                StandardCharsets.UTF_8);
        assertEquals("'" + animal, csvRow(lines.get(0), lines.get(1)).get("AnimalName"));
    }

    @Test
    public void execute_readsNewObjectIntensityAndLineDistanceOutputs() throws Exception {
        File root = temp.newFolder("master-agg-new-layout");
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(root.getAbsolutePath());
        File attrs = roiTables(root);
        File objects = layout.tablesObjectsWriteDir();
        File intensities = layout.tablesIntensityWriteDir();
        File lineDistances = layout.tablesLineDistanceWriteDir();
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

        MasterAggregationAnalysis analysis = new MasterAggregationAnalysis();
        analysis.setSuppressDialogs(true);
        analysis.execute(root.getAbsolutePath());

        List<String> objectLines = Files.readAllLines(
                aggregationFile(root, "3D Objects.csv").toPath(),
                StandardCharsets.UTF_8);
        String objectHeader = objectLines.get(0);
        String intensityHeader = Files.readAllLines(
                aggregationFile(root, "Image Intensities.csv").toPath(),
                StandardCharsets.UTF_8).get(0);

        assertTrue(objectHeader.contains("CK1D_Count"));
        assertTrue(objectHeader.contains("CK1D_DistTo_Line1Mean"));
        assertTrue(intensityHeader.contains("GFAP_ROI_IntDenMean"));
    }

    @Test
    public void execute_averagesRepeatedRoiCostesBySectionNotObjectCount() throws Exception {
        File root = temp.newFolder("master-agg-roi-costes-weighting");
        File objects = FlashProjectLayout.forDirectory(root.getAbsolutePath()).tablesObjectsWriteDir();
        assertTrue(objects.mkdirs());

        writeCsv(new File(objects, "A.csv"),
                "Region,Hemisphere,ROI,Animal Name,SCN,Volume (micron^3),Surface (micron^2),"
                        + "IntDen,Mean,XM,YM,ZM,A_ROICostesP_B,A_ObjPearson_B",
                "SCN,LH,SCN1,Mouse1,1,10,5,100,10,1,1,1,0.1,1\n"
                        + "SCN,LH,SCN1,Mouse1,1,10,5,100,10,1,1,1,0.1,2\n"
                        + "SCN,LH,SCN1,Mouse1,1,10,5,100,10,1,1,1,0.1,3\n"
                        + "SCN,LH,SCN2,Mouse1,2,10,5,100,10,1,1,1,0.9,9");

        MasterAggregationAnalysis analysis = new MasterAggregationAnalysis();
        analysis.setSuppressDialogs(true);
        analysis.execute(root.getAbsolutePath());

        List<String> lines = Files.readAllLines(
                aggregationFile(root, "3D Objects.csv").toPath(),
                StandardCharsets.UTF_8);
        assertEquals(2, lines.size());

        Map<String, String> row = csvRow(lines.get(0), lines.get(1));
        assertEquals(0.5, Double.parseDouble(row.get("A_ROICostesP_BMean")), 0.0001);
        assertEquals(3.75, Double.parseDouble(row.get("A_ObjPearson_BMean")), 0.0001);
    }

    @Test
    public void execute_readsLegacyRawIntDenAsIntDenUnfiltered() throws Exception {
        File root = temp.newFolder("master-agg-legacy-intensity");
        File intensities = FlashProjectLayout.forDirectory(root.getAbsolutePath()).tablesIntensityWriteDir();
        assertTrue(intensities.mkdirs());

        writeCsv(new File(intensities, "GFAP.csv"),
                "Animal Name,ROI,Region,SCN,Hemisphere,IntDen,%Area,RawIntDen",
                "Mouse1,SCN1,SCN,1,LH,10,3,100\n"
                        + "Mouse1,SCN2,SCN,2,LH,20,5,200");

        Map<String, String> row = aggregateIntensityRow(root);
        String header = Files.readAllLines(
                aggregationFile(root, "Image Intensities.csv").toPath(),
                StandardCharsets.UTF_8).get(0);

        assertEquals("15", row.get("GFAP_ROI_IntDenMean"));
        assertEquals("4", row.get("GFAP_ROI_%AreaMean"));
        assertEquals("150", row.get("GFAP_ROI_IntDen_UnfilteredMean"));
        assertFalse(header.contains("GFAP_ROI_RawIntDenMean"));
    }

    @Test
    public void execute_mixesLegacyAndNewIntensitySchemas() throws Exception {
        File root = temp.newFolder("master-agg-mixed-intensity");
        File intensities = FlashProjectLayout.forDirectory(root.getAbsolutePath()).tablesIntensityWriteDir();
        assertTrue(intensities.mkdirs());

        writeCsv(new File(intensities, "GFAP.csv"),
                "Animal Name,ROI,Region,SCN,Hemisphere,IntDen,%Area,RawIntDen",
                "Mouse1,SCN1,SCN,1,LH,40,7,80");
        writeCsv(new File(intensities, "DAPI.csv"),
                "Animal Name,ROI,Region,SCN,Hemisphere,IntDen,%Area,IntDen_Unfiltered",
                "Mouse1,SCN1,SCN,1,LH,25,2,30");

        Map<String, String> row = aggregateIntensityRow(root);
        String header = Files.readAllLines(
                aggregationFile(root, "Image Intensities.csv").toPath(),
                StandardCharsets.UTF_8).get(0);

        assertEquals("80", row.get("GFAP_ROI_IntDen_UnfilteredMean"));
        assertEquals("30", row.get("DAPI_ROI_IntDen_UnfilteredMean"));
        assertFalse(header.contains("ROI_RawIntDenMean"));
    }

    @Test
    public void execute_prefersIntDenUnfilteredWhenLegacyColumnAlsoExists() throws Exception {
        File root = temp.newFolder("master-agg-prefer-new-intensity");
        File intensities = FlashProjectLayout.forDirectory(root.getAbsolutePath()).tablesIntensityWriteDir();
        assertTrue(intensities.mkdirs());

        writeCsv(new File(intensities, "GFAP.csv"),
                "Animal Name,ROI,Region,SCN,Hemisphere,IntDen,%Area,IntDen_Unfiltered,RawIntDen",
                "Mouse1,SCN1,SCN,1,LH,40,7,123,999");

        Map<String, String> row = aggregateIntensityRow(root);

        assertEquals("123", row.get("GFAP_ROI_IntDen_UnfilteredMean"));
    }

    @Test
    public void execute_aggregatesNewBasicIntensitySchema() throws Exception {
        File root = temp.newFolder("master-agg-new-basic-intensity");
        File intensities = FlashProjectLayout.forDirectory(root.getAbsolutePath()).tablesIntensityWriteDir();
        assertTrue(intensities.mkdirs());

        writeCsv(new File(intensities, "Iba1.csv"),
                "Animal Name,ROI,Region,SCN,Hemisphere,IntDen,%Area,IntDen_Unfiltered",
                "Mouse1,SCN1,SCN,1,LH,12,20,100\n"
                        + "Mouse1,SCN2,SCN,2,LH,18,30,140");

        Map<String, String> row = aggregateIntensityRow(root);

        assertEquals("15", row.get("Iba1_ROI_IntDenMean"));
        assertEquals("25", row.get("Iba1_ROI_%AreaMean"));
        assertEquals("120", row.get("Iba1_ROI_IntDen_UnfilteredMean"));
        assertEquals("2", row.get("numZSlices"));
    }

    @Test
    public void execute_countsZRowsWithoutWritingZMean() throws Exception {
        File root = temp.newFolder("master-agg-z-count");
        File intensities = FlashProjectLayout.forDirectory(root.getAbsolutePath()).tablesIntensityWriteDir();
        assertTrue(intensities.mkdirs());

        writeCsv(new File(intensities, "Iba1.csv"),
                "Animal Name,ROI,Region,SCN,Hemisphere,z,IntDen,%Area,IntDen_Unfiltered",
                "Mouse1,SCN1,SCN,1,LH,5,12,20,100\n"
                        + "Mouse1,SCN1,SCN,1,LH,6,18,30,140\n"
                        + "Mouse1,SCN1,SCN,1,LH,7,30,40,180");

        Map<String, String> row = aggregateIntensityRow(root);
        String header = Files.readAllLines(
                aggregationFile(root, "Image Intensities.csv").toPath(),
                StandardCharsets.UTF_8).get(0);

        assertEquals("3", row.get("numZSlices"));
        assertEquals("20", row.get("Iba1_ROI_IntDenMean"));
        assertFalse(header.contains("zMean"));
    }

    @Test
    public void execute_aggregatesNewBinarizedIntensityColumns() throws Exception {
        File root = temp.newFolder("master-agg-binarized-intensity");
        File intensities = FlashProjectLayout.forDirectory(root.getAbsolutePath()).tablesIntensityWriteDir();
        assertTrue(intensities.mkdirs());

        writeCsv(new File(intensities, "GFAP.csv"),
                "Animal Name,ROI,Region,SCN,Hemisphere,IntDen,IntDen_binarized,%Area,%Area_binarized,IntDen_Unfiltered",
                "Mouse1,SCN1,SCN,1,LH,10,6,30,12,100\n"
                        + "Mouse1,SCN2,SCN,2,LH,20,8,50,14,200");

        Map<String, String> row = aggregateIntensityRow(root);

        assertEquals("15", row.get("GFAP_ROI_IntDenMean"));
        assertEquals("7", row.get("GFAP_ROI_IntDen_binarizedMean"));
        assertEquals("40", row.get("GFAP_ROI_%AreaMean"));
        assertEquals("13", row.get("GFAP_ROI_%Area_binarizedMean"));
        assertEquals("150", row.get("GFAP_ROI_IntDen_UnfilteredMean"));
    }

    @Test
    public void execute_aggregatesSpatialIntensityAndPairColumns() throws Exception {
        File root = temp.newFolder("master-agg-spatial-intensity");
        File intensities = FlashProjectLayout.forDirectory(root.getAbsolutePath()).tablesIntensityWriteDir();
        assertTrue(intensities.mkdirs());

        writeCsv(new File(intensities, "DAPI.csv"),
                "Animal Name,ROI,Region,SCN,Hemisphere,IntDen,%Area,IntDen_Unfiltered,"
                        + "Intensity_PatchinessCV50,Intensity_HotspotMoransI,"
                        + "DAPI_Pearson_mCherry,DAPI_MandersM1_mCherry_binarized",
                "Mouse1,SCN1,SCN,1,LH,10,1,100,0.25,0.5,0.25,0.5\n"
                        + "Mouse1,SCN2,SCN,2,LH,20,3,200,0.75,0.75,0.75,0.75");
        writeCsv(new File(intensities, "mCherry.csv"),
                "Animal Name,ROI,Region,SCN,Hemisphere,IntDen,%Area,IntDen_Unfiltered",
                "Mouse1,SCN1,SCN,1,LH,5,1,50");

        Map<String, String> row = aggregateIntensityRow(root);

        assertEquals("0.5", row.get("DAPI_ROI_Intensity_PatchinessCV50Mean"));
        assertEquals("0.625", row.get("DAPI_ROI_Intensity_HotspotMoransIMean"));
        assertEquals("0.5", row.get("DAPI_ROI_DAPI_Pearson_mCherryMean"));
        assertEquals("0.625", row.get("DAPI_ROI_DAPI_MandersM1_mCherry_binarizedMean"));
    }

    @Test
    public void execute_writesSeparateMipAnd3dIntensityMasters() throws Exception {
        File root = temp.newFolder("master-agg-intensity-modes");
        File intensities = FlashProjectLayout.forDirectory(root.getAbsolutePath()).tablesIntensityWriteDir();
        assertTrue(intensities.mkdirs());

        writeCsv(new File(intensities, "DAPI.csv"),
                "Animal Name,ROI,Region,SCN,Hemisphere,IntDen,%Area,IntDen_Unfiltered",
                "BaseMouse,SCN1,SCN,1,LH,10,2,100");
        writeCsv(new File(intensities, "mCherry.csv"),
                "Animal Name,ROI,Region,SCN,Hemisphere,IntDen,%Area,IntDen_Unfiltered",
                "BaseMouse,SCN1,SCN,1,LH,20,4,200");
        writeCsv(new File(intensities, "DAPI_MIP.csv"),
                "Animal Name,ROI,Region,SCN,Hemisphere,z,Intensity_HotspotMoransI,DAPI_Pearson_mCherry",
                "MipMouse,SCN1,SCN,1,LH,MIP,0.75,0.5");
        writeCsv(new File(intensities, "mCherry_MIP.csv"),
                "Animal Name,ROI,Region,SCN,Hemisphere,z,Intensity_HotspotMoransI",
                "MipMouse,SCN1,SCN,1,LH,MIP,0.25");
        writeCsv(new File(intensities, "DAPI_3D.csv"),
                "Animal Name,ROI,Region,SCN,Hemisphere,z,Intensity_Anisotropy3DCoherency,DAPI_Pearson3D_mCherry",
                "NativeMouse,SCN1,SCN,1,LH,3D,0.9,0.6");
        writeCsv(new File(intensities, "mCherry_3D.csv"),
                "Animal Name,ROI,Region,SCN,Hemisphere,z,Intensity_Anisotropy3DCoherency",
                "NativeMouse,SCN1,SCN,1,LH,3D,0.2");

        Map<String, String> baseRow = aggregateIntensityRow(root);
        Map<String, String> mipRow = aggregateIntensityRow(root, "Image Intensities_MIP.csv");
        Map<String, String> nativeRow = aggregateIntensityRow(root, "Image Intensities_3D.csv");

        assertEquals("BaseMouse", baseRow.get("AnimalName"));
        assertEquals("MipMouse", mipRow.get("AnimalName"));
        assertEquals("NativeMouse", nativeRow.get("AnimalName"));
        assertEquals("1", mipRow.get("numZSlices"));
        assertEquals("1", nativeRow.get("numZSlices"));
        assertEquals("0.75", mipRow.get("DAPI_ROI_Intensity_HotspotMoransIMean"));
        assertEquals("0.5", mipRow.get("DAPI_ROI_DAPI_Pearson_mCherryMean"));
        assertEquals("0.9", nativeRow.get("DAPI_ROI_Intensity_Anisotropy3DCoherencyMean"));
        assertEquals("0.6", nativeRow.get("DAPI_ROI_DAPI_Pearson3D_mCherryMean"));

        String mipHeader = Files.readAllLines(
                aggregationFile(root, "Image Intensities_MIP.csv").toPath(),
                StandardCharsets.UTF_8).get(0);
        String nativeHeader = Files.readAllLines(
                aggregationFile(root, "Image Intensities_3D.csv").toPath(),
                StandardCharsets.UTF_8).get(0);
        assertFalse(mipHeader.contains("DAPI_MIP_ROI"));
        assertFalse(nativeHeader.contains("DAPI_3D_ROI"));
    }

    @Test
    public void execute_preservesChannelRoiMaskBasicAggregation() throws Exception {
        File root = temp.newFolder("master-agg-channel-roi-mask-intensity");
        File intensities = FlashProjectLayout.forDirectory(root.getAbsolutePath()).tablesIntensityWriteDir();
        assertTrue(intensities.mkdirs());

        writeCsv(new File(intensities, "GFAP in DAPI ROI.csv"),
                "Animal Name,ROI,Region,SCN,Hemisphere,IntDen,IntDen_binarized,%Area,%Area_binarized,RawIntDen,"
                        + "Intensity_PatchinessCV50,GFAP_Pearson_DAPI",
                "Mouse1,SCN1,SCN,1,LH,30,12,9,4,88,0.5,0.7");

        Map<String, String> row = aggregateIntensityRow(root);

        assertEquals("30", row.get("GFAP in DAPI ROI_ROI_IntDenMean"));
        assertEquals("12", row.get("GFAP in DAPI ROI_ROI_IntDen_binarizedMean"));
        assertEquals("9", row.get("GFAP in DAPI ROI_ROI_%AreaMean"));
        assertEquals("4", row.get("GFAP in DAPI ROI_ROI_%Area_binarizedMean"));
        assertEquals("88", row.get("GFAP in DAPI ROI_ROI_IntDen_UnfilteredMean"));
        assertFalse(row.containsKey("GFAP in DAPI ROI_ROI_Intensity_PatchinessCV50Mean"));
        assertFalse(row.containsKey("GFAP in DAPI ROI_ROI_GFAP_Pearson_DAPIMean"));
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

    private Map<String, String> aggregateIntensityRow(File root) throws Exception {
        return aggregateIntensityRow(root, "Image Intensities.csv");
    }

    private Map<String, String> aggregateIntensityRow(File root, String fileName) throws Exception {
        MasterAggregationAnalysis analysis = new MasterAggregationAnalysis();
        analysis.setSuppressDialogs(true);
        analysis.execute(root.getAbsolutePath());

        List<String> lines = Files.readAllLines(
                aggregationFile(root, fileName).toPath(),
                StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        return csvRow(lines.get(0), lines.get(1));
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
        return FlashProjectLayout.forDirectory(root.getAbsolutePath()).projectSummaryWriteFile(fileName);
    }

    private File aggregationDetailsFile(File root) {
        return new File(FlashProjectLayout.forDirectory(root.getAbsolutePath()).analysisDetailsWriteDir(),
                "Aggregation_Analysis_Details.txt");
    }

    private File roiTables(File root) {
        return FlashProjectLayout.forDirectory(root.getAbsolutePath()).tablesRoiWriteDir();
    }
}
