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

/**
 * Headless Excel export must use the current Conditions.csv deterministically
 * and must not treat the master Condition column as a numeric metric.
 */
public class ExcelSummaryExportConditionReviewTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void headlessExportGroupsBySavedConditionsAndIgnoresConditionMetric() throws Exception {
        File dir = temp.newFolder("excel-condition-review");
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(dir.getAbsolutePath());
        File summaryDir = layout.tablesProjectSummaryWriteDir();
        assertTrue(summaryDir.isDirectory() || summaryDir.mkdirs());

        // Master CSV now carries a Condition column (Stage 03 output shape).
        writeCsv(new File(summaryDir, FlashProjectLayout.MASTER_OBJECTS_FILENAME),
                Arrays.asList("AnimalName", "Condition", "GFAP_Count"),
                Arrays.asList(
                        Arrays.asList("Mouse1", "StaleLabel", "1.0"),
                        Arrays.asList("Mouse2", "StaleLabel", "2.0")));

        LinkedHashMap<String, String> conditions = new LinkedHashMap<String, String>();
        conditions.put("Mouse1", "CondA");
        conditions.put("Mouse2", "CondB");
        ConditionManifestIO.saveAssignments(dir.getAbsolutePath(), conditions);

        ExcelSummaryExportAnalysis analysis = new ExcelSummaryExportAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.execute(dir.getAbsolutePath());

        File workbookFile = layout.summaryWorkbookWriteFile();
        assertTrue(workbookFile.isFile());

        FileInputStream fis = new FileInputStream(workbookFile);
        try {
            Workbook workbook = new XSSFWorkbook(fis);
            try {
                // Condition must not be exported as a per-metric sheet.
                assertNull("Condition must not be a metric sheet", workbook.getSheet("Condition"));

                Sheet conditions_ = workbook.getSheet("Experimental Conditions");
                assertNotNull(conditions_);
                // Uses the saved manifest (CondA/CondB), not the stale master labels.
                assertEquals("CondA", conditions_.getRow(1).getCell(0).getStringCellValue());
                assertTrue(conditions_.getRow(1).getCell(1).getStringCellValue().contains("Mouse1"));
                assertEquals("CondB", conditions_.getRow(2).getCell(0).getStringCellValue());
                assertTrue(conditions_.getRow(2).getCell(1).getStringCellValue().contains("Mouse2"));
            } finally {
                workbook.close();
            }
        } finally {
            fis.close();
        }
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
