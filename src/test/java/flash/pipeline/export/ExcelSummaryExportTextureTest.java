package flash.pipeline.export;

import flash.pipeline.io.CsvSupport;
import flash.pipeline.io.FlashProjectLayout;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ExcelSummaryExportTextureTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void execute_omitsTextureFeatureVectorsByDefault() throws Exception {
        File dir = prepareFixture("excel-texture-default");

        ExcelSummaryExportAnalysis analysis = new ExcelSummaryExportAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.execute(dir.getAbsolutePath());

        Workbook workbook = openWorkbook(dir);
        try {
            assertNull(workbook.getSheet("MorphTexture_F1"));
            assertNull(workbook.getSheet("MorphTexture_F8"));
            assertNull(workbook.getSheet("MorphTexture_F3D1"));
            assertNull(workbook.getSheet("MorphTexture_F3D8"));

            Sheet summary = workbook.getSheet("Data Summary");
            assertNotNull(summary);
            String text = sheetText(summary);
            assertTrue(text.contains("GFAP Count"));
            assertTrue(!text.contains("MorphTexture_F1"));
            assertTrue(!text.contains("MorphTexture_F3D1"));
        } finally {
            workbook.close();
        }
    }

    @Test
    public void execute_includesTextureFeatureVectorsWhenPresetFlagIsOn() throws Exception {
        File dir = prepareFixture("excel-texture-enabled");

        ExcelSummaryExportAnalysis analysis = new ExcelSummaryExportAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.setPreset(ExcelExportPreset.exploratoryDefault()
                .withField("texture_features", "true"));
        analysis.execute(dir.getAbsolutePath());

        Workbook workbook = openWorkbook(dir);
        try {
            assertNotNull(workbook.getSheet("MorphTexture_F1"));
            assertNotNull(workbook.getSheet("MorphTexture_F8"));
            assertNotNull(workbook.getSheet("MorphTexture_F3D1"));
            assertNotNull(workbook.getSheet("MorphTexture_F3D8"));

            Sheet summary = workbook.getSheet("Data Summary");
            assertNotNull(summary);
            String text = sheetText(summary);
            assertTrue(text.contains("MorphTexture_F1"));
            assertTrue(text.contains("MorphTexture_F8"));
            assertTrue(text.contains("MorphTexture_F3D1"));
            assertTrue(text.contains("MorphTexture_F3D8"));
        } finally {
            workbook.close();
        }
    }

    private File prepareFixture(String folderName) throws Exception {
        File dir = temp.newFolder(folderName);
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(dir.getAbsolutePath());
        File aggregationDir = layout.aggregationWriteDir();
        assertTrue(aggregationDir.isDirectory() || aggregationDir.mkdirs());

        List<String> header = new ArrayList<String>();
        header.add("AnimalName");
        header.add("GFAP_Count");
        for (int i = 1; i <= 8; i++) {
            header.add("Iba1_MorphTexture_F" + i + "Mean");
        }
        for (int i = 1; i <= 8; i++) {
            header.add("Iba1_MorphTexture_F3D" + i + "Mean");
        }

        List<String> mouse1 = new ArrayList<String>();
        mouse1.add("Mouse1");
        mouse1.add("1.0");
        for (int i = 1; i <= 16; i++) {
            mouse1.add(Integer.toString(i));
        }

        List<String> mouse2 = new ArrayList<String>();
        mouse2.add("Mouse2");
        mouse2.add("2.0");
        for (int i = 1; i <= 16; i++) {
            mouse2.add(Integer.toString(i * 2));
        }

        writeCsv(new File(aggregationDir, FlashProjectLayout.MASTER_OBJECTS_FILENAME),
                header, Arrays.asList(mouse1, mouse2));
        return dir;
    }

    private Workbook openWorkbook(File dir) throws Exception {
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(dir.getAbsolutePath());
        File workbookFile = layout.excelWriteFile(FlashProjectLayout.SUMMARY_WORKBOOK_FILENAME);
        assertTrue(workbookFile.isFile());
        FileInputStream fis = new FileInputStream(workbookFile);
        try {
            return new XSSFWorkbook(fis);
        } finally {
            fis.close();
        }
    }

    private static String sheetText(Sheet sheet) {
        StringBuilder out = new StringBuilder();
        for (Row row : sheet) {
            for (Cell cell : row) {
                out.append(cell.toString()).append('\n');
            }
        }
        return out.toString();
    }

    private static void writeCsv(File file, List<String> header,
                                 List<List<String>> rows) throws Exception {
        PrintWriter pw = CsvSupport.newWriter(file);
        try {
            pw.println(CsvSupport.joinRow(header));
            for (List<String> row : rows) {
                pw.println(CsvSupport.joinRow(row));
            }
        } finally {
            pw.close();
        }
    }
}
