package flash.pipeline.analyses;

import flash.pipeline.io.CsvTableIO;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.project.ProjectFile;
import flash.pipeline.runrecord.AnalysisRunContext;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MasterAggregationRunIdPropagationTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void objectAggregationPreservesSourceRunIdAndWritesAggregatorRunId() throws Exception {
        File root = temp.newFolder("master-run-id");
        File objects = FlashProjectLayout.forDirectory(root.getAbsolutePath()).tablesObjectsWriteDir();
        assertTrue(objects.mkdirs());
        writeCsv(new File(objects, "DAPI.csv"),
                "Region,Hemisphere,ROI,Animal Name,Volume (micron^3),Surface (micron^2),IntDen,Mean,XM,YM,ZM,run_id",
                "SCN,LH,SCN1,Mouse1,10,5,100,10,1,1,1,R1");

        MasterAggregationAnalysis analysis = new MasterAggregationAnalysis();
        analysis.setSuppressDialogs(true);
        AnalysisRunContext context = AnalysisRunContext.open("MasterAggregationAnalysis", 8,
                "Master Data Aggregation", root.getAbsolutePath(), new ProjectFile(),
                new LinkedHashMap<String, Object>(), null);
        try {
            analysis.setRunRecordContext(context);
            analysis.execute(root.getAbsolutePath());

            Map<String, String> row = csvRow(
                    FlashProjectLayout.forDirectory(root.getAbsolutePath())
                            .projectSummaryWriteFile(FlashProjectLayout.MASTER_OBJECTS_FILENAME));
            assertEquals("R1", row.get("source_run_id"));
            assertEquals(context.runId(), row.get("run_id"));
        } finally {
            context.close();
        }
    }

    @Test
    public void objectAggregationToleratesSourceCsvWithoutRunId() throws Exception {
        File root = temp.newFolder("master-run-id-missing-source");
        File objects = FlashProjectLayout.forDirectory(root.getAbsolutePath()).tablesObjectsWriteDir();
        assertTrue(objects.mkdirs());
        writeCsv(new File(objects, "DAPI.csv"),
                "Region,Hemisphere,ROI,Animal Name,Volume (micron^3),Surface (micron^2),IntDen,Mean,XM,YM,ZM",
                "SCN,LH,SCN1,Mouse1,10,5,100,10,1,1,1");

        MasterAggregationAnalysis analysis = new MasterAggregationAnalysis();
        analysis.setSuppressDialogs(true);
        AnalysisRunContext context = AnalysisRunContext.open("MasterAggregationAnalysis", 8,
                "Master Data Aggregation", root.getAbsolutePath(), new ProjectFile(),
                new LinkedHashMap<String, Object>(), null);
        try {
            analysis.setRunRecordContext(context);
            analysis.execute(root.getAbsolutePath());

            Map<String, String> row = csvRow(
                    FlashProjectLayout.forDirectory(root.getAbsolutePath())
                            .projectSummaryWriteFile(FlashProjectLayout.MASTER_OBJECTS_FILENAME));
            assertEquals("", row.get("source_run_id"));
            assertEquals(context.runId(), row.get("run_id"));
        } finally {
            context.close();
        }
    }

    private static void writeCsv(File file, String header, String row) throws Exception {
        PrintWriter pw = new PrintWriter(file, "UTF-8");
        try {
            pw.println(header);
            pw.println(row);
        } finally {
            pw.close();
        }
    }

    private static Map<String, String> csvRow(File file) throws Exception {
        java.util.List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        String[] headers = CsvTableIO.parseCsvLine(lines.get(0));
        String[] values = CsvTableIO.parseCsvLine(lines.get(1));
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (int i = 0; i < headers.length; i++) {
            out.put(headers[i], i < values.length ? values[i] : "");
        }
        return out;
    }
}
