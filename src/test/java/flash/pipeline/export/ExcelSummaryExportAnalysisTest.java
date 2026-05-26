package flash.pipeline.export;

import flash.pipeline.io.ConditionManifestIO;
import flash.pipeline.io.CsvSupport;
import flash.pipeline.io.FlashProjectLayout;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ExcelSummaryExportAnalysisTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void execute_readsQuotedStatisticsCsvIntoWorkbook() throws Exception {
        File dir = temp.newFolder("excel-summary");
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(dir.getAbsolutePath());
        File aggregationDir = layout.aggregationWriteDir();
        File statisticsDir = layout.statisticsWriteDir();
        assertTrue(aggregationDir.isDirectory() || aggregationDir.mkdirs());
        assertTrue(statisticsDir.isDirectory() || statisticsDir.mkdirs());

        writeCsv(new File(aggregationDir, FlashProjectLayout.MASTER_OBJECTS_FILENAME),
                Arrays.asList("AnimalName", "GFAP_Count"),
                Arrays.asList(
                        Arrays.asList("Mouse1", "1.0"),
                        Arrays.asList("Mouse2", "2.0")));

        LinkedHashMap<String, String> conditions = new LinkedHashMap<String, String>();
        conditions.put("Mouse1", "CondA");
        conditions.put("Mouse2", "CondB");
        ConditionManifestIO.saveAssignments(dir.getAbsolutePath(), conditions);

        writeCsv(new File(statisticsDir, FlashProjectLayout.STATISTICS_FILENAME),
                Arrays.asList("Metric", "Test", "Statistic", "p-value", "Significant", "NormalityResult",
                        "Group1", "Group2", "PairwiseTest", "PairwiseStatistic",
                        "PairwisePValue", "CorrectedPValue", "Significance", "Notes"),
                Arrays.asList(
                        Arrays.asList(
                                "Metric, \"Quoted\"",
                                "Welch t-test",
                                "1.500000",
                                "0.012345",
                                "*",
                                "normal",
                                "CondA",
                                "CondB",
                                "none",
                                "",
                                "",
                                "",
                                "*",
                                "Line1\nLine2")));

        ExcelSummaryExportAnalysis analysis = new ExcelSummaryExportAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.execute(dir.getAbsolutePath());

        File workbookFile = layout.excelWriteFile(FlashProjectLayout.SUMMARY_WORKBOOK_FILENAME);
        assertTrue(workbookFile.isFile());

        FileInputStream fis = new FileInputStream(workbookFile);
        try {
            Workbook workbook = new XSSFWorkbook(fis);
            try {
                org.apache.poi.ss.usermodel.Sheet sheet = workbook.getSheet("Statistics");
                assertNotNull(sheet);
                assertEquals("Metric, \"Quoted\"", sheet.getRow(1).getCell(0).getStringCellValue());
                assertEquals(0.012345, sheet.getRow(1).getCell(3).getNumericCellValue(), 0.0);
                assertEquals("Line1\nLine2", sheet.getRow(1).getCell(13).getStringCellValue());
            } finally {
                workbook.close();
            }
        } finally {
            fis.close();
        }
    }

    @Test
    public void execute_readsLegacyAnalysisDetailsIntoNewWorkbook() throws Exception {
        File dir = temp.newFolder("excel-summary-legacy-details");
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(dir.getAbsolutePath());
        File aggregationDir = layout.aggregationWriteDir();
        assertTrue(aggregationDir.isDirectory() || aggregationDir.mkdirs());

        writeCsv(new File(aggregationDir, FlashProjectLayout.MASTER_OBJECTS_FILENAME),
                Arrays.asList("AnimalName", "GFAP_Count"),
                Arrays.asList(
                        Arrays.asList("Mouse1", "1.0"),
                        Arrays.asList("Mouse2", "2.0")));

        LinkedHashMap<String, String> conditions = new LinkedHashMap<String, String>();
        conditions.put("Mouse1", "CondA");
        conditions.put("Mouse2", "CondB");
        ConditionManifestIO.saveAssignments(dir.getAbsolutePath(), conditions);

        File detailsDir = layout.objectAnalysisDetailsWriteDir();
        assertTrue(detailsDir.mkdirs());
        Files.write(new File(detailsDir, "GFAP.txt").toPath(), Arrays.asList(
                "<Filter Macro>legacy filter macro</Filter Macro>",
                "<Analysis Macro>legacy analysis macro</Analysis Macro>"));

        ExcelSummaryExportAnalysis analysis = new ExcelSummaryExportAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.execute(dir.getAbsolutePath());

        File workbookFile = layout.excelWriteFile(FlashProjectLayout.SUMMARY_WORKBOOK_FILENAME);
        assertTrue(workbookFile.isFile());

        FileInputStream fis = new FileInputStream(workbookFile);
        try {
            Workbook workbook = new XSSFWorkbook(fis);
            try {
                org.apache.poi.ss.usermodel.Sheet sheet = workbook.getSheet("Data Summary");
                assertNotNull(sheet);
                assertEquals("GFAP", sheet.getRow(2).getCell(0).getStringCellValue());
                assertEquals("Object", sheet.getRow(2).getCell(1).getStringCellValue());
                assertEquals("legacy filter macro", sheet.getRow(2).getCell(3).getStringCellValue());
                assertEquals("legacy analysis macro", sheet.getRow(2).getCell(4).getStringCellValue());
            } finally {
                workbook.close();
            }
        } finally {
            fis.close();
        }
    }

    @Test
    public void excelSafeText_escapesFormulaLeadingText() {
        assertEquals("'=cmd", ExcelSummaryExportAnalysis.excelSafeText("=cmd"));
        assertEquals("'+cmd", ExcelSummaryExportAnalysis.excelSafeText("+cmd"));
        assertEquals("'-cmd", ExcelSummaryExportAnalysis.excelSafeText("-cmd"));
        assertEquals("'@cmd", ExcelSummaryExportAnalysis.excelSafeText("@cmd"));
        assertEquals("safe", ExcelSummaryExportAnalysis.excelSafeText("safe"));
    }

    private static void writeCsv(File file, java.util.List<String> header,
                                 java.util.List<java.util.List<String>> rows) throws Exception {
        PrintWriter pw = CsvSupport.newWriter(file);
        try {
            pw.println(CsvSupport.joinRow(header));
            for (java.util.List<String> row : rows) {
                pw.println(CsvSupport.joinRow(row));
            }
        } finally {
            pw.close();
        }
    }
}
