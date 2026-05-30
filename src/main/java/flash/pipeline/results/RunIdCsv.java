package flash.pipeline.results;

import flash.pipeline.runrecord.AnalysisRunContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Small helper for keeping run-id CSV headers and rows in lockstep.
 */
public final class RunIdCsv {

    public static final String RUN_ID_COLUMN = "run_id";
    public static final String SOURCE_RUN_ID_COLUMN = "source_run_id";

    private RunIdCsv() {
    }

    public static String runId(AnalysisRunContext context) {
        return context == null ? "" : clean(context.runId());
    }

    public static List<String> appendRunId(List<String> values, String runId) {
        return append(values, clean(runId));
    }

    public static List<String> appendRunIdHeader(List<String> header) {
        return append(withoutColumn(header, RUN_ID_COLUMN), RUN_ID_COLUMN);
    }

    public static List<String> appendRunIdRow(List<String> row, String runId) {
        return append(row, clean(runId));
    }

    public static List<String> appendSourceAndRunIdHeader(List<String> header) {
        List<String> values = withoutColumn(withoutColumn(header, SOURCE_RUN_ID_COLUMN), RUN_ID_COLUMN);
        values.add(SOURCE_RUN_ID_COLUMN);
        values.add(RUN_ID_COLUMN);
        return values;
    }

    public static List<String> appendSourceAndRunIdRow(List<String> row,
                                                       String sourceRunId,
                                                       String runId) {
        List<String> values = copy(row);
        values.add(clean(sourceRunId));
        values.add(clean(runId));
        return values;
    }

    public static List<String> withoutRunIdColumns(List<String> columns) {
        return withoutColumn(withoutColumn(columns, SOURCE_RUN_ID_COLUMN), RUN_ID_COLUMN);
    }

    public static List<String> withoutRunId(List<String> columns) {
        return withoutColumn(columns, RUN_ID_COLUMN);
    }

    private static List<String> append(List<String> values, String value) {
        List<String> out = copy(values);
        out.add(value);
        return out;
    }

    private static List<String> withoutColumn(List<String> values, String column) {
        List<String> out = new ArrayList<String>();
        if (values == null) {
            return out;
        }
        for (String value : values) {
            if (column.equals(value == null ? "" : value.trim())) {
                continue;
            }
            out.add(value);
        }
        return out;
    }

    private static List<String> copy(List<String> values) {
        List<String> out = new ArrayList<String>();
        if (values != null) {
            out.addAll(values);
        }
        return out;
    }

    private static String clean(String value) {
        return value == null ? "" : value;
    }
}
