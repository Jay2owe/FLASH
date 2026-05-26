package flash.pipeline.integration;

import flash.pipeline.analyses.MasterAggregationAnalysis;
import flash.pipeline.analyses.StatisticalAnalysis;
import flash.pipeline.export.ExcelSummaryExportAnalysis;
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

import static org.junit.Assert.assertTrue;

public class ResultsLayoutEndToEndTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void aggregationStatisticsAndExcelProduceCleanResultsTree() throws Exception {
        File project = temp.newFolder("project");
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(project.getAbsolutePath());
        File objectDir = layout.tablesObjectsWriteDir();
        assertTrue(objectDir.mkdirs());

        writeObjectTable(new File(objectDir, "DAPI.csv"));

        LinkedHashMap<String, String> conditions = new LinkedHashMap<String, String>();
        conditions.put("Syn1", "Syn");
        conditions.put("Syn2", "Syn");
        conditions.put("Syn3", "Syn");
        conditions.put("hAPP1", "hAPP");
        conditions.put("hAPP2", "hAPP");
        conditions.put("hAPP3", "hAPP");
        ConditionManifestIO.saveAssignments(project.getAbsolutePath(), conditions);

        MasterAggregationAnalysis aggregation = new MasterAggregationAnalysis();
        aggregation.setSuppressDialogs(true);
        aggregation.execute(project.getAbsolutePath());

        StatisticalAnalysis statistics = new StatisticalAnalysis();
        statistics.setHeadless(true);
        statistics.setSuppressDialogs(true);
        statistics.execute(project.getAbsolutePath());

        ExcelSummaryExportAnalysis excel = new ExcelSummaryExportAnalysis();
        excel.setHeadless(true);
        excel.setSuppressDialogs(true);
        excel.execute(project.getAbsolutePath());

        assertTrue(layout.resultsRoot().isDirectory());
        assertTrue(layout.projectSummaryWriteFile(FlashProjectLayout.MASTER_OBJECTS_FILENAME).isFile());
        assertTrue(layout.projectSummaryWriteFile(FlashProjectLayout.STATISTICS_FILENAME).isFile());
        assertTrue(layout.projectSummaryWriteFile(FlashProjectLayout.CONDITIONS_FILENAME).isFile());
        assertTrue(layout.summaryWorkbookWriteFile().isFile());
        assertTrue(layout.startHereWriteFile().isFile());

        String startHere = new String(Files.readAllBytes(layout.startHereWriteFile().toPath()),
                StandardCharsets.UTF_8);
        assertTrue(startHere.contains("Summary.xlsx"));
        assertTrue(startHere.contains("Tables"));
        assertTrue(startHere.contains("Run Records"));
    }

    private static void writeObjectTable(File file) throws Exception {
        PrintWriter pw = new PrintWriter(file);
        try {
            pw.println("Region,Hemisphere,ROI,Animal Name,Volume (micron^3),Surface (micron^2),IntDen,Mean,XM,YM,ZM");
            writeObjectRow(pw, "Syn1", "10");
            writeObjectRow(pw, "Syn2", "12");
            writeObjectRow(pw, "Syn3", "11");
            writeObjectRow(pw, "hAPP1", "30");
            writeObjectRow(pw, "hAPP2", "32");
            writeObjectRow(pw, "hAPP3", "31");
        } finally {
            pw.close();
        }
    }

    private static void writeObjectRow(PrintWriter pw, String animal, String mean) {
        pw.println("SCN,LH,SCN1," + animal + ",10,5,100," + mean + ",1,1,1");
    }
}
