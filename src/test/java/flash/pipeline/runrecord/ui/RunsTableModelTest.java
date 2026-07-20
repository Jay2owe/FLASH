package flash.pipeline.runrecord.ui;

import flash.pipeline.runrecord.RunSummary;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public class RunsTableModelTest {

    @Test
    public void exposesExpectedColumnsAndValues() {
        RunSummary summary = summary("RUN123456789", "PARENT654321",
                1_716_912_000_000L, 1_716_912_065_000L,
                "ok", "SpatialAnalysis", 3, "1.2.3");

        RunsTableModel model = new RunsTableModel(Arrays.asList(summary));

        assertEquals(8, model.getColumnCount());
        assertEquals("Date/time", model.getColumnName(RunsTableModel.COL_STARTED_AT));
        assertEquals(1, model.getRowCount());
        assertEquals("SpatialAnalysis", model.getValueAt(0, RunsTableModel.COL_ANALYSIS));
        assertEquals("ok", model.getValueAt(0, RunsTableModel.COL_STATUS));
        assertEquals("1m 05s", model.getValueAt(0, RunsTableModel.COL_DURATION));
        assertEquals(Integer.valueOf(3), model.getValueAt(0, RunsTableModel.COL_INPUTS));
        assertEquals("1.2.3", model.getValueAt(0, RunsTableModel.COL_FLASH_VERSION));
        assertEquals("PARENT", model.getValueAt(0, RunsTableModel.COL_PARENT_RUN_ID));
        assertEquals("RUN123", model.getValueAt(0, RunsTableModel.COL_RUN_ID));
    }

    @Test
    public void constructorSortsMostRecentFirst() {
        RunSummary older = summary("OLDER", "", 1_000L, 2_000L,
                "ok", "OlderAnalysis", 1, "1");
        RunSummary newer = summary("NEWER", "", 3_000L, 4_000L,
                "ok", "NewerAnalysis", 1, "1");

        RunsTableModel model = new RunsTableModel(Arrays.asList(older, newer));

        assertEquals("NEWER", model.getSummaryAt(0).runId);
        assertEquals("OLDER", model.getSummaryAt(1).runId);
    }

    private static RunSummary summary(String runId, String parentRunId,
                                      long startedAt, long finishedAt,
                                      String status, String analysis,
                                      int inputs, String version) {
        return new RunSummary(runId, parentRunId, startedAt, finishedAt, status,
                analysis, 0, analysis, version, "hash", "project", "output",
                inputs, new File(runId + ".jsonl"));
    }
}
