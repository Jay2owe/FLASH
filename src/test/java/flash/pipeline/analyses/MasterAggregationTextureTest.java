package flash.pipeline.analyses;

import flash.pipeline.export.ExcelNameMap;
import flash.pipeline.io.CsvTableIO;
import flash.pipeline.io.FlashProjectLayout;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MasterAggregationTextureTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void execute_aggregatesMorphTextureMeansAndClassLabelMode() throws Exception {
        File root = temp.newFolder("master-agg-texture");
        File objects = FlashProjectLayout.forDirectory(root.getAbsolutePath()).objectDataWriteDir();
        assertTrue(objects.mkdirs());

        writeCsv(new File(objects, "Iba1.csv"),
                "Animal Name,MorphTexture_GLCMContrast,MorphTexture_FractalDim,"
                        + "MorphTexture_ClassLabel,MorphTexture_F1,"
                        + "MorphTexture_Class3DLabel,MorphTexture_F3D1",
                "Mouse1,1,1.1,1,10,3,100\n"
                        + "Mouse1,2,1.2,2,20,2,200\n"
                        + "Mouse1,3,1.3,2,30,3,300\n"
                        + "Mouse1,4,1.4,3,40,2,400\n"
                        + "Mouse1,5,1.5,3,50,4,500");

        MasterAggregationAnalysis analysis = new MasterAggregationAnalysis();
        analysis.setSuppressDialogs(true);
        analysis.execute(root.getAbsolutePath());

        List<String> lines = Files.readAllLines(
                aggregationFile(root, "3D Objects.csv").toPath(),
                StandardCharsets.UTF_8);
        assertEquals(2, lines.size());

        String header = lines.get(0);
        Map<String, String> row = csvRow(header, lines.get(1));

        assertTrue(header.contains("Iba1_MorphTexture_GLCMContrastMean"));
        assertTrue(header.contains("Iba1_MorphTexture_FractalDimMean"));
        assertTrue(header.contains("Iba1_MorphTexture_ClassLabelMode"));
        assertTrue(header.contains("Iba1_MorphTexture_F1Mean"));
        assertTrue(header.contains("Iba1_MorphTexture_Class3DLabelMode"));
        assertTrue(header.contains("Iba1_MorphTexture_F3D1Mean"));
        assertFalse(header.contains("Iba1_MorphTexture_ClassLabelMean"));
        assertFalse(header.contains("Iba1_MorphTexture_Class3DLabelMean"));

        assertEquals(3.0, Double.parseDouble(row.get("Iba1_MorphTexture_GLCMContrastMean")), 0.0);
        assertEquals(1.3, Double.parseDouble(row.get("Iba1_MorphTexture_FractalDimMean")), 1e-12);
        assertEquals(2.0, Double.parseDouble(row.get("Iba1_MorphTexture_ClassLabelMode")), 0.0);
        assertEquals(30.0, Double.parseDouble(row.get("Iba1_MorphTexture_F1Mean")), 0.0);
        assertEquals(2.0, Double.parseDouble(row.get("Iba1_MorphTexture_Class3DLabelMode")), 0.0);
        assertEquals(300.0, Double.parseDouble(row.get("Iba1_MorphTexture_F3D1Mean")), 0.0);
    }

    @Test
    public void execute_aggregatesSyntheticSpatialAnalysisTextureOutput() throws Exception {
        File root = temp.newFolder("spatial-texture-to-master-aggregation");
        runSpatialTextureFixture(root);

        MasterAggregationAnalysis analysis = new MasterAggregationAnalysis();
        analysis.setSuppressDialogs(true);
        analysis.execute(root.getAbsolutePath());

        List<String> lines = Files.readAllLines(
                aggregationFile(root, "3D Objects.csv").toPath(),
                StandardCharsets.UTF_8);
        assertEquals(2, lines.size());

        Map<String, String> row = csvRow(lines.get(0), lines.get(1));
        assertFinite(row, "A_MorphTexture_GLCMContrastMean");
        assertFinite(row, "A_MorphTexture_FractalDimMean");
        assertFinite(row, "A_MorphTexture_ClassLabelMode");
    }

    @Test
    public void needsChannelPrefix_prefixesMorphTextureColumns() {
        assertTrue(MasterAggregationAnalysis.needsChannelPrefix("MorphTexture_GLCMContrast"));
        assertTrue(MasterAggregationAnalysis.needsChannelPrefix("MorphTexture_ClassLabel"));
        assertTrue(MasterAggregationAnalysis.needsChannelPrefix("MorphTexture_Class3DLabel"));
    }

    @Test
    public void excelNameMap_mapsUserFacingMorphTextureAggregatesOnly() {
        String[] mapped = {
                "Iba1_MorphTexture_GLCMContrastMean",
                "Iba1_MorphTexture_GLCMASMMean",
                "Iba1_MorphTexture_GLCMCorrelationMean",
                "Iba1_MorphTexture_GLCMEntropyMean",
                "Iba1_MorphTexture_GLCMHomogeneityMean",
                "Iba1_MorphTexture_FractalDimMean",
                "Iba1_MorphTexture_FractalDim_R2Mean",
                "Iba1_MorphTexture_LacunarityMeanMean",
                "Iba1_MorphTexture_LacunaritySpreadMean",
                "Iba1_MorphTexture_ClassLabelMode",
                "Iba1_MorphTexture_ClassDistanceMean",
                "Iba1_MorphTexture_GLCM3DContrastMean",
                "Iba1_MorphTexture_GLCM3DASMMean",
                "Iba1_MorphTexture_GLCM3DCorrelationMean",
                "Iba1_MorphTexture_GLCM3DEntropyMean",
                "Iba1_MorphTexture_GLCM3DHomogeneityMean",
                "Iba1_MorphTexture_Class3DLabelMode",
                "Iba1_MorphTexture_Class3DDistanceMean"
        };
        for (String col : mapped) {
            String[] nameDesc = ExcelNameMap.convert(col);
            assertNotNull("expected Excel mapping for " + col, nameDesc);
            assertNotNull(nameDesc[0]);
            assertNotNull(nameDesc[1]);
        }

        assertNull(ExcelNameMap.convert("Iba1_MorphTexture_F1Mean"));
        assertNull(ExcelNameMap.convert("Iba1_MorphTexture_F8Mean"));
        assertNull(ExcelNameMap.convert("Iba1_MorphTexture_F3D1Mean"));
        assertNull(ExcelNameMap.convert("Iba1_MorphTexture_F3D8Mean"));
    }

    private void runSpatialTextureFixture(File root) throws Exception {
        Method createFixture = SpatialAnalysisObjectTextureTest.class
                .getDeclaredMethod("createFixture", File.class);
        createFixture.setAccessible(true);
        createFixture.invoke(null, root);

        Method runTexture = SpatialAnalysisObjectTextureTest.class
                .getDeclaredMethod("runTexture", File.class);
        runTexture.setAccessible(true);
        runTexture.invoke(null, root);
    }

    private void assertFinite(Map<String, String> row, String column) {
        assertTrue("missing " + column, row.containsKey(column));
        double value = Double.parseDouble(row.get(column));
        assertTrue(column + " should be finite", !Double.isNaN(value) && !Double.isInfinite(value));
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
