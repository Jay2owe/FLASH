package flash.pipeline.runrecord.ui;

import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.runrecord.RunRecordIO;
import flash.pipeline.runrecord.RunSummary;

import javax.swing.RowFilter;
import javax.swing.table.AbstractTableModel;
import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Header-only table model for run records. Full {@code RunRecord} objects are
 * loaded by {@link RunDetailPanel} only when a row is selected.
 */
public final class RunsTableModel extends AbstractTableModel {

    public static final int COL_STARTED_AT = 0;
    public static final int COL_ANALYSIS = 1;
    public static final int COL_STATUS = 2;
    public static final int COL_DURATION = 3;
    public static final int COL_INPUTS = 4;
    public static final int COL_FLASH_VERSION = 5;
    public static final int COL_PARENT_RUN_ID = 6;
    public static final int COL_RUN_ID = 7;

    public static final String ALL_VALUE = "All";

    private static final String[] COLUMNS = {
            "Date/time",
            "Analysis",
            "Status",
            "Duration",
            "Inputs",
            "FLASH ver",
            "Parent",
            "Run ID"
    };

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final List<RunSummary> rows = new ArrayList<RunSummary>();

    public RunsTableModel(List<RunSummary> summaries) {
        setRuns(summaries);
    }

    public RunsTableModel(FlashProjectLayout layout) {
        this(layout == null ? Collections.<RunSummary>emptyList()
                : RunRecordIO.readIndex(layout.runJsonlWriteDir()));
    }

    public void setRuns(List<RunSummary> summaries) {
        rows.clear();
        if (summaries != null) {
            for (RunSummary summary : summaries) {
                if (summary != null) {
                    rows.add(summary);
                }
            }
        }
        Collections.sort(rows, new Comparator<RunSummary>() {
            @Override
            public int compare(RunSummary a, RunSummary b) {
                return Long.compare(b.startedAtMillis, a.startedAtMillis);
            }
        });
        fireTableDataChanged();
    }

    public int getColumnCount() {
        return COLUMNS.length;
    }

    public int getRowCount() {
        return rows.size();
    }

    @Override
    public String getColumnName(int column) {
        return COLUMNS[column];
    }

    @Override
    public Class<?> getColumnClass(int columnIndex) {
        if (columnIndex == COL_INPUTS) {
            return Integer.class;
        }
        return String.class;
    }

    public Object getValueAt(int rowIndex, int columnIndex) {
        RunSummary summary = getSummaryAt(rowIndex);
        switch (columnIndex) {
            case COL_STARTED_AT:
                return formatDateTime(summary.startedAtMillis);
            case COL_ANALYSIS:
                return displayAnalysis(summary);
            case COL_STATUS:
                return safe(summary.status);
            case COL_DURATION:
                return formatDuration(summary.durationMillis());
            case COL_INPUTS:
                return Integer.valueOf(summary.inputCount);
            case COL_FLASH_VERSION:
                return safe(summary.flashVersion);
            case COL_PARENT_RUN_ID:
                return shortId(summary.parentRunId);
            case COL_RUN_ID:
                return shortId(summary.runId);
            default:
                return "";
        }
    }

    public RunSummary getSummaryAt(int modelRow) {
        return rows.get(modelRow);
    }

    public List<RunSummary> getRows() {
        return Collections.unmodifiableList(rows);
    }

    public List<String> analysisValues() {
        Set<String> values = new LinkedHashSet<String>();
        values.add(ALL_VALUE);
        for (RunSummary summary : rows) {
            String analysis = displayAnalysis(summary);
            if (!analysis.isEmpty()) {
                values.add(analysis);
            }
        }
        return new ArrayList<String>(values);
    }

    public List<String> statusValues() {
        Set<String> values = new LinkedHashSet<String>();
        values.add(ALL_VALUE);
        values.add("ok");
        values.add("warn");
        values.add("failed");
        for (RunSummary summary : rows) {
            String status = safe(summary.status).trim().toLowerCase(Locale.ROOT);
            if (!status.isEmpty()) {
                values.add(status);
            }
        }
        return new ArrayList<String>(values);
    }

    public static RowFilter<RunsTableModel, Integer> createRowFilter(FilterCriteria criteria) {
        final FilterCriteria filter = criteria == null ? FilterCriteria.all() : criteria;
        return new RowFilter<RunsTableModel, Integer>() {
            @Override
            public boolean include(Entry<? extends RunsTableModel, ? extends Integer> entry) {
                Integer row = entry.getIdentifier();
                if (row == null) {
                    return false;
                }
                RunSummary summary = entry.getModel().getSummaryAt(row.intValue());
                return matchesText(summary, filter.text)
                        && matchesExact(displayAnalysis(summary), filter.analysis)
                        && matchesExact(safe(summary.status), filter.status)
                        && matchesDateRange(summary, filter);
            }
        };
    }

    private static boolean matchesText(RunSummary summary, String text) {
        String needle = normalise(text);
        if (needle.isEmpty()) {
            return true;
        }
        StringBuilder haystack = new StringBuilder();
        append(haystack, formatDateTime(summary.startedAtMillis));
        append(haystack, displayAnalysis(summary));
        append(haystack, summary.analysisLabel);
        append(haystack, summary.status);
        append(haystack, formatDuration(summary.durationMillis()));
        append(haystack, summary.flashVersion);
        append(haystack, summary.parentRunId);
        append(haystack, summary.runId);
        append(haystack, summary.projectRoot);
        append(haystack, summary.outputRoot);
        File recordFile = summary.recordFile;
        append(haystack, recordFile == null ? "" : recordFile.getName());
        return haystack.toString().toLowerCase(Locale.ROOT).contains(needle);
    }

    private static boolean matchesExact(String value, String selected) {
        String choice = safe(selected).trim();
        if (choice.isEmpty() || ALL_VALUE.equalsIgnoreCase(choice)) {
            return true;
        }
        return safe(value).trim().equalsIgnoreCase(choice);
    }

    private static boolean matchesDateRange(RunSummary summary, FilterCriteria filter) {
        if (filter.dateRange == null || filter.dateRange == DateRange.ALL) {
            return true;
        }
        if (summary.startedAtMillis <= 0L) {
            return false;
        }
        long cutoff = filter.nowMillis - filter.dateRange.millis;
        return summary.startedAtMillis >= cutoff && summary.startedAtMillis <= filter.nowMillis;
    }

    private static void append(StringBuilder out, String value) {
        if (value != null && !value.isEmpty()) {
            out.append(' ').append(value);
        }
    }

    private static String normalise(String value) {
        return safe(value).trim().toLowerCase(Locale.ROOT);
    }

    public static String displayAnalysis(RunSummary summary) {
        if (summary == null) {
            return "";
        }
        String analysis = safe(summary.analysis).trim();
        if (!analysis.isEmpty()) {
            return analysis;
        }
        return safe(summary.analysisLabel).trim();
    }

    public static String formatDateTime(long millis) {
        if (millis <= 0L) {
            return "-";
        }
        return DATE_TIME.format(Instant.ofEpochMilli(millis));
    }

    public static String formatDuration(long millis) {
        if (millis < 0L) {
            return "-";
        }
        if (millis < 1000L) {
            return "<1s";
        }
        long seconds = TimeUnit.MILLISECONDS.toSeconds(millis);
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long secs = seconds % 60L;
        if (hours > 0L) {
            return String.format(Locale.ROOT, "%dh %02dm %02ds", hours, minutes, secs);
        }
        if (minutes > 0L) {
            return String.format(Locale.ROOT, "%dm %02ds", minutes, secs);
        }
        return secs + "s";
    }

    public static String shortId(String id) {
        String safe = safe(id).trim();
        if (safe.length() <= 6) {
            return safe;
        }
        return safe.substring(0, 6);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public enum DateRange {
        LAST_24_HOURS("Last 24h", TimeUnit.HOURS.toMillis(24L)),
        LAST_7_DAYS("Last 7 days", TimeUnit.DAYS.toMillis(7L)),
        ALL("All dates", 0L);

        private final String label;
        private final long millis;

        DateRange(String label, long millis) {
            this.label = label;
            this.millis = millis;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public static final class FilterCriteria {
        public final String text;
        public final String analysis;
        public final String status;
        public final DateRange dateRange;
        public final long nowMillis;

        public FilterCriteria(String text, String analysis, String status,
                              DateRange dateRange, long nowMillis) {
            this.text = text == null ? "" : text;
            this.analysis = analysis == null ? ALL_VALUE : analysis;
            this.status = status == null ? ALL_VALUE : status;
            this.dateRange = dateRange == null ? DateRange.ALL : dateRange;
            this.nowMillis = nowMillis <= 0L ? System.currentTimeMillis() : nowMillis;
        }

        public static FilterCriteria all() {
            return new FilterCriteria("", ALL_VALUE, ALL_VALUE, DateRange.ALL, System.currentTimeMillis());
        }
    }
}
