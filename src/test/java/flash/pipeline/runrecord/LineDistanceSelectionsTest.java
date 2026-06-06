package flash.pipeline.runrecord;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Round-trip contract for "Load settings from previous run" on Line Distance.
 * The capture (LineDistanceAnalysis.recordLineDistanceRunParameters) writes the
 * same keys the parser (LoadedRunParameters.lineDistanceSelections) reads back.
 */
public class LineDistanceSelectionsTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void listRoundTripAppliesAllKnownKeys() {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("line_sets", Arrays.asList("LH ventricle", "cortex"));
        params.put("draw_new", Boolean.TRUE);
        params.put("landmark", "Ventricle");
        params.put("custom_name", "myline");
        params.put("draw_on_subset", Boolean.TRUE);

        LoadedRunParameters.ValueLoad<LoadedRunParameters.LineDistanceSelections> load =
                LoadedRunParameters.lineDistanceSelections(params);
        LoadedRunParameters.LineDistanceSelections sel = load.value;

        assertEquals(Arrays.asList("LH ventricle", "cortex"), sel.selectedSets);
        assertTrue(sel.drawNew);
        assertEquals("Ventricle", sel.landmark);
        assertEquals("myline", sel.customName);
        assertTrue(sel.drawOnSubset);
        assertTrue(load.result.getAppliedKeys().containsAll(Arrays.asList(
                "line_sets", "draw_new", "landmark", "custom_name", "draw_on_subset")));
        assertTrue(load.result.getIgnoredKeys().isEmpty());
    }

    @Test
    public void lineSetsAcceptsCommaSeparatedStringFromCommand() {
        // LineDistanceAnalysisCommand records line_sets as a CSV string.
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("line_sets", "LH ventricle, cortex ,  ");

        LoadedRunParameters.LineDistanceSelections sel =
                LoadedRunParameters.lineDistanceSelections(params).value;

        assertEquals(Arrays.asList("LH ventricle", "cortex"), sel.selectedSets);
    }

    @Test
    public void booleansAcceptStringForm() {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("draw_new", "true");
        params.put("draw_on_subset", "false");

        LoadedRunParameters.LineDistanceSelections sel =
                LoadedRunParameters.lineDistanceSelections(params).value;

        assertTrue(sel.drawNew);
        assertFalse(sel.drawOnSubset);
    }

    @Test
    public void missingKeysDefaultSafelyAndUnknownKeysAreIgnored() {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("unrelated", "x");

        LoadedRunParameters.ValueLoad<LoadedRunParameters.LineDistanceSelections> load =
                LoadedRunParameters.lineDistanceSelections(params);
        LoadedRunParameters.LineDistanceSelections sel = load.value;

        assertTrue(sel.selectedSets.isEmpty());
        assertFalse(sel.drawNew);
        assertFalse(sel.drawOnSubset);
        assertNull(sel.landmark);
        assertNull(sel.customName);
        assertTrue(load.result.getIgnoredKeys().contains("unrelated"));
    }

    @Test
    public void nullParametersAreSafe() {
        LoadedRunParameters.LineDistanceSelections sel =
                LoadedRunParameters.lineDistanceSelections(null).value;
        assertTrue(sel.selectedSets.isEmpty());
        assertFalse(sel.drawNew);
    }

    @Test
    public void recordWriteThenReadRoundTripsThroughTheRunRecord() throws Exception {
        // Mirror what LineDistanceAnalysis.recordLineDistanceRunParameters writes.
        File projectRoot = temp.newFolder("project");
        AnalysisRunContext context = AnalysisRunContext.open("LineDistanceAnalysis", 6,
                "Line Distance Analysis", projectRoot.getAbsolutePath(), null,
                new LinkedHashMap<String, Object>(), "");
        Map<String, Object> captured = new LinkedHashMap<String, Object>();
        captured.put("line_sets", Arrays.asList("set A", "set B"));
        captured.put("draw_new", Boolean.FALSE);
        captured.put("landmark", "Custom");
        captured.put("custom_name", "Ventricle");
        captured.put("draw_on_subset", Boolean.TRUE);
        context.recordParameters(captured);
        context.close();

        RunRecord record = RunRecordIO.readLatest(context.recordFile());
        LoadedRunParameters.LineDistanceSelections sel =
                LoadedRunParameters.lineDistanceSelections(record.parameters).value;

        assertEquals(Arrays.asList("set A", "set B"), sel.selectedSets);
        assertFalse(sel.drawNew);
        assertEquals("Custom", sel.landmark);
        assertEquals("Ventricle", sel.customName);
        assertTrue(sel.drawOnSubset);
    }

    @Test
    public void selectedSetsAreUnmodifiable() {
        LoadedRunParameters.LineDistanceSelections sel =
                new LoadedRunParameters.LineDistanceSelections(
                        Arrays.asList("a"), false, null, null, false);
        try {
            sel.selectedSets.add("b");
            org.junit.Assert.fail("selectedSets should be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
        assertEquals(Collections.singletonList("a"), sel.selectedSets);
    }
}
