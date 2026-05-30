package flash.pipeline.runrecord.ui;

import flash.pipeline.runrecord.RunSummary;
import org.junit.Test;

import javax.swing.table.TableRowSorter;
import java.io.File;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class RunSummaryFilteringTest {

    private static final long NOW = 2_000_000_000_000L;

    @Test
    public void filtersByAnalysis() {
        List<RunSummary> runs = Arrays.asList(
                summary("A1", "SpatialAnalysis", "ok", NOW - 1_000L),
                summary("A2", "ThreeDObjectAnalysis", "ok", NOW - 1_000L),
                summary("A3", "SpatialAnalysis", "warn", NOW - 1_000L));

        assertEquals(2, visibleCount(runs, new RunsTableModel.FilterCriteria(
                "", "SpatialAnalysis", RunsTableModel.ALL_VALUE,
                RunsTableModel.DateRange.ALL, NOW)));
    }

    @Test
    public void filtersByStatus() {
        List<RunSummary> runs = Arrays.asList(
                summary("A1", "SpatialAnalysis", "ok", NOW - 1_000L),
                summary("A2", "SpatialAnalysis", "failed", NOW - 1_000L),
                summary("A3", "SpatialAnalysis", "warn", NOW - 1_000L));

        assertEquals(1, visibleCount(runs, new RunsTableModel.FilterCriteria(
                "", RunsTableModel.ALL_VALUE, "failed",
                RunsTableModel.DateRange.ALL, NOW)));
    }

    @Test
    public void filtersByDateRange() {
        List<RunSummary> runs = Arrays.asList(
                summary("RECENT", "SpatialAnalysis", "ok", NOW - 60_000L),
                summary("WEEK", "SpatialAnalysis", "ok", NOW - 3L * 24L * 60L * 60L * 1000L),
                summary("OLD", "SpatialAnalysis", "ok", NOW - 10L * 24L * 60L * 60L * 1000L));

        assertEquals(1, visibleCount(runs, new RunsTableModel.FilterCriteria(
                "", RunsTableModel.ALL_VALUE, RunsTableModel.ALL_VALUE,
                RunsTableModel.DateRange.LAST_24_HOURS, NOW)));
        assertEquals(2, visibleCount(runs, new RunsTableModel.FilterCriteria(
                "", RunsTableModel.ALL_VALUE, RunsTableModel.ALL_VALUE,
                RunsTableModel.DateRange.LAST_7_DAYS, NOW)));
    }

    @Test
    public void filtersCanBeCombined() {
        List<RunSummary> runs = Arrays.asList(
                summary("ALPHA-ONE", "SpatialAnalysis", "warn", NOW - 60_000L),
                summary("ALPHA-TWO", "SpatialAnalysis", "ok", NOW - 60_000L),
                summary("BETA-ONE", "ThreeDObjectAnalysis", "warn", NOW - 60_000L),
                summary("ALPHA-OLD", "SpatialAnalysis", "warn", NOW - 10L * 24L * 60L * 60L * 1000L));

        assertEquals(1, visibleCount(runs, new RunsTableModel.FilterCriteria(
                "alpha", "SpatialAnalysis", "warn",
                RunsTableModel.DateRange.LAST_7_DAYS, NOW)));
    }

    private static int visibleCount(List<RunSummary> runs, RunsTableModel.FilterCriteria criteria) {
        RunsTableModel model = new RunsTableModel(runs);
        TableRowSorter<RunsTableModel> sorter = new TableRowSorter<RunsTableModel>(model);
        sorter.setRowFilter(RunsTableModel.createRowFilter(criteria));
        return sorter.getViewRowCount();
    }

    private static RunSummary summary(String runId, String analysis, String status, long startedAt) {
        return new RunSummary(runId, "", startedAt, startedAt + 1_000L, status,
                analysis, 0, analysis, "1", "hash", "project", "output",
                1, new File(runId + ".jsonl"));
    }
}
