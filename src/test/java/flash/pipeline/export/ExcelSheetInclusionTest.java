package flash.pipeline.export;

import flash.pipeline.io.ConditionManifestIO;
import flash.pipeline.io.CsvSupport;
import flash.pipeline.io.FlashProjectLayout;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ExcelSheetInclusionTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void minimalPresetProducesOnlyPerMetricSheets() throws Exception {
        File dir = prepareFixture("minimal");

        ExcelSummaryExportAnalysis analysis = new ExcelSummaryExportAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.setPreset(loadStockPreset(dir, "Minimal (metric sheets only)"));
        analysis.execute(dir.getAbsolutePath());

        FlashProjectLayout layout = FlashProjectLayout.forDirectory(dir.getAbsolutePath());
        List<String> sheets = readSheetNames(layout.summaryWorkbookWriteFile());
        assertTrue("Expected at least one per-metric sheet. Got: " + sheets, sheets.size() >= 1);
        for (String name : sheets) {
            assertSheetIsNotMeta(name);
        }
    }

    @Test
    public void supervisorReviewPresetProducesOnlyConditionsAndStatistics() throws Exception {
        File dir = prepareFixture("supervisor");

        ExcelSummaryExportAnalysis analysis = new ExcelSummaryExportAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.setPreset(loadStockPreset(dir, "Supervisor review (conditions + statistics only)"));
        analysis.execute(dir.getAbsolutePath());

        FlashProjectLayout layout = FlashProjectLayout.forDirectory(dir.getAbsolutePath());
        File workbookFile = layout.summaryWorkbookWriteFile();
        assertTrue(workbookFile.isFile());

        FileInputStream fis = new FileInputStream(workbookFile);
        try {
            Workbook workbook = new XSSFWorkbook(fis);
            try {
                assertNotNull("Experimental Conditions sheet missing",
                        workbook.getSheet("Experimental Conditions"));
                assertNotNull("Statistics sheet missing", workbook.getSheet("Statistics"));
                assertNull("Data Summary sheet should be absent",
                        workbook.getSheet("Data Summary"));
                for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                    String name = workbook.getSheetName(i);
                    assertTrue("Unexpected per-metric sheet: " + name,
                            name.equals("Experimental Conditions")
                                    || name.equals("Statistics"));
                }
            } finally {
                workbook.close();
            }
        } finally {
            fis.close();
        }
    }

    private File prepareFixture(String folderName) throws Exception {
        File dir = temp.newFolder(folderName);
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(dir.getAbsolutePath());
        File aggregationDir = layout.tablesProjectSummaryWriteDir();
        File statisticsDir = layout.tablesProjectSummaryWriteDir();
        assertTrue(aggregationDir.isDirectory() || aggregationDir.mkdirs());
        assertTrue(statisticsDir.isDirectory() || statisticsDir.mkdirs());

        writeCsv(new File(aggregationDir, FlashProjectLayout.MASTER_OBJECTS_FILENAME),
                Arrays.asList("AnimalName", "GFAP_Count"),
                Arrays.asList(
                        Arrays.asList("Mouse1", "12.0"),
                        Arrays.asList("Mouse2", "18.0"),
                        Arrays.asList("Mouse3", "10.0"),
                        Arrays.asList("Mouse4", "16.0")));

        LinkedHashMap<String, String> conditions = new LinkedHashMap<String, String>();
        conditions.put("Mouse1", "WT");
        conditions.put("Mouse2", "WT");
        conditions.put("Mouse3", "KO");
        conditions.put("Mouse4", "KO");
        ConditionManifestIO.saveAssignments(dir.getAbsolutePath(), conditions);

        writeCsv(new File(statisticsDir, FlashProjectLayout.STATISTICS_FILENAME),
                Arrays.asList("Metric", "Test", "Statistic", "p-value", "Significant", "NormalityResult",
                        "Group1", "Group2", "PairwiseTest", "PairwiseStatistic",
                        "PairwisePValue", "CorrectedPValue", "Significance", "Notes"),
                Arrays.asList(
                        Arrays.asList("GFAP_Count", "Welch t-test", "1.500",
                                "0.0300", "*", "normal", "WT", "KO", "none",
                                "", "", "", "*", "")));
        return dir;
    }

    private ExcelExportPreset loadStockPreset(File dir, String name) throws Exception {
        ExcelExportPresetIO io = new ExcelExportPresetIO(dir);
        io.bootstrapStockPresets();
        return io.load(name);
    }

    private static List<String> readSheetNames(File workbookFile) throws Exception {
        assertTrue(workbookFile.isFile());
        java.util.List<String> names = new java.util.ArrayList<String>();
        FileInputStream fis = new FileInputStream(workbookFile);
        try {
            Workbook workbook = new XSSFWorkbook(fis);
            try {
                for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                    Sheet sheet = workbook.getSheetAt(i);
                    names.add(sheet.getSheetName());
                }
            } finally {
                workbook.close();
            }
        } finally {
            fis.close();
        }
        return names;
    }

    private static void assertSheetIsNotMeta(String name) {
        assertTrue("Unexpected meta sheet '" + name + "'",
                !name.equals("Experimental Conditions")
                        && !name.equals("Data Summary")
                        && !name.equals("Statistics")
                        && !name.equals("Methods Appendix"));
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
