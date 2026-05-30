package flash.pipeline.runrecord;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RunDiffTest {

    @Test
    public void reportsAddedRemovedAndChangedParameterKeys() {
        RunRecord parent = record(map(
                "removed", "old",
                "changed", "before",
                "same", "stable"));
        RunRecord child = record(map(
                "changed", "after",
                "same", "stable",
                "added", "new"));

        List<RunDiff.DiffEntry> diff = RunDiff.diff(parent, child);

        assertEquals(3, diff.size());
        assertKind(diff, "removed", RunDiff.DiffKind.REMOVED);
        assertKind(diff, "changed", RunDiff.DiffKind.CHANGED);
        assertKind(diff, "added", RunDiff.DiffKind.ADDED);
    }

    @Test
    public void comparesNumbersByNumericValueNotStringRepresentation() {
        RunRecord parent = record(map(
                "threshold", Integer.valueOf(1),
                "nested", map("value", Double.valueOf(2.0)),
                "list", Arrays.asList(Integer.valueOf(3))));
        RunRecord child = record(map(
                "threshold", Double.valueOf(1.0),
                "nested", map("value", Integer.valueOf(2)),
                "list", Arrays.asList(Double.valueOf(3.0))));

        assertTrue(RunDiff.diff(parent, child).isEmpty());
    }

    private static void assertKind(List<RunDiff.DiffEntry> entries,
                                   String path,
                                   RunDiff.DiffKind kind) {
        for (RunDiff.DiffEntry entry : entries) {
            if (path.equals(entry.path())) {
                assertEquals(kind, entry.kind());
                return;
            }
        }
        org.junit.Assert.fail("Missing diff entry for " + path);
    }

    private static RunRecord record(Map<String, Object> parameters) {
        RunRecord record = new RunRecord();
        record.parameters = parameters;
        return record;
    }

    private static Map<String, Object> map(Object... entries) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < entries.length; i += 2) {
            out.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return out;
    }
}
