package flash.pipeline.decontamination;

import flash.pipeline.io.CsvTableIO;
import flash.pipeline.results.RunIdCsv;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class SpectralRunIdCsvTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void spectralSummaryAndObjectScoreWritersAppendRunIdLast() throws Exception {
        File root = temp.newFolder("spectral-run-id");
        List<Map<String, String>> summaryRows = new ArrayList<Map<String, String>>();
        Map<String, String> summary = new LinkedHashMap<String, String>();
        summary.put("SeriesIndex", "0");
        summary.put("SeriesName", "Mouse1");
        summary.put("RunAction", "processed");
        summaryRows.add(summary);

        SpectralOutputWriter.writePerImageSummary(root.getAbsolutePath(), summaryRows, "R-SPEC");

        Map<String, String> summaryRow = firstRow(SpectralOutputWriter.perImageSummaryFile(root.getAbsolutePath()));
        assertEquals("R-SPEC", summaryRow.get(RunIdCsv.RUN_ID_COLUMN));

        List<Map<String, String>> objectRows = new ArrayList<Map<String, String>>();
        Map<String, String> object = new LinkedHashMap<String, String>();
        object.put("SeriesIndex", "0");
        object.put("SeriesName", "Mouse1");
        object.put("ObjectID", "7");
        objectRows.add(object);

        ObjectScoreWriter.writePerObjectScores(root.getAbsolutePath(), objectRows, "R-OBJ");

        Map<String, String> objectRow = firstRow(ObjectScoreWriter.perObjectScoresFile(root.getAbsolutePath()));
        assertEquals("R-OBJ", objectRow.get(RunIdCsv.RUN_ID_COLUMN));
    }

    private static Map<String, String> firstRow(File csv) throws Exception {
        List<String> lines = Files.readAllLines(csv.toPath(), StandardCharsets.UTF_8);
        String[] headers = CsvTableIO.parseCsvLine(lines.get(0));
        String[] values = CsvTableIO.parseCsvLine(lines.get(1));
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (int i = 0; i < headers.length; i++) {
            out.put(headers[i], i < values.length ? values[i] : "");
        }
        assertEquals(RunIdCsv.RUN_ID_COLUMN, headers[headers.length - 1]);
        return out;
    }

    @Test
    public void rewritingReusedRowsKeepsOriginalProducerAcrossMultipleRuns() throws Exception {
        File root = temp.newFolder("spectral-reuse-run-id");
        Map<String, String> producer = new LinkedHashMap<String, String>();
        producer.put("SeriesIndex", "0");
        producer.put("Metric", "weight_channel_2");
        producer.put("ObjectID", "7");
        producer.put(RunIdCsv.RUN_ID_COLUMN, "R-PRODUCER");
        Map<String, String> copied = SpectralOutputWriter.copySummaryRow(
                producer, "skipped_existing", "");
        assertEquals("R-PRODUCER", copied.get(RunIdCsv.SOURCE_RUN_ID_COLUMN));
        List<Map<String, String>> rows = new ArrayList<Map<String, String>>();
        rows.add(copied);
        SpectralOutputWriter.writePerImageSummary(root.getAbsolutePath(), rows, "R-REUSE-1");
        Map<String, String> second = firstRow(SpectralOutputWriter.perImageSummaryFile(root.getAbsolutePath()));
        assertEquals("R-REUSE-1", second.get(RunIdCsv.RUN_ID_COLUMN));
        assertEquals("R-PRODUCER", second.get(RunIdCsv.SOURCE_RUN_ID_COLUMN));
        rows.clear();
        rows.add(SpectralOutputWriter.copySummaryRow(second, "skipped_existing", ""));
        SpectralOutputWriter.writePerImageSummary(root.getAbsolutePath(), rows, "R-REUSE-2");
        assertEquals("R-PRODUCER", firstRow(SpectralOutputWriter.perImageSummaryFile(
                root.getAbsolutePath())).get(RunIdCsv.SOURCE_RUN_ID_COLUMN));

        rows.clear();
        rows.add(producer);
        SpectralOutputWriter.writeCorrectionCoefficients(root.getAbsolutePath(),
                SpectralOutputWriter.copyCoefficientRows(rows, "skipped_existing"), "R-REUSE-2");
        assertEquals("R-PRODUCER", firstRow(SpectralOutputWriter.correctionCoefficientsFile(
                root.getAbsolutePath())).get(RunIdCsv.SOURCE_RUN_ID_COLUMN));
        ObjectScoreWriter.writePerObjectScores(root.getAbsolutePath(),
                ObjectScoreWriter.copyObjectRows(rows, "skipped_existing"), "R-REUSE-2");
        assertEquals("R-PRODUCER", firstRow(ObjectScoreWriter.perObjectScoresFile(
                root.getAbsolutePath())).get(RunIdCsv.SOURCE_RUN_ID_COLUMN));
        org.junit.Assert.assertFalse(producer.containsKey(RunIdCsv.SOURCE_RUN_ID_COLUMN));
    }
}
