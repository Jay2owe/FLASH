package flash.pipeline.ui.sandbox.variation;

import flash.pipeline.image.FilterMacroParser.OpType;
import flash.pipeline.image.dag.DagIR;
import flash.pipeline.image.dag.DagLine;
import flash.pipeline.image.dag.DagNode;
import flash.pipeline.image.variation.VariantPlan;
import flash.pipeline.image.variation.VariantResult;
import ij.ImagePlus;
import ij.process.ByteProcessor;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class VariationSessionLogTest {

    @Test
    public void recordsGenerateEliminatePromoteAndSavePresetEntries() {
        VariationSessionLog log = new VariationSessionLog();
        VariantPlan plan = plan("sigma=2");

        log.recordGenerate(Arrays.asList(
                new VariantResult(plan, new ImagePlus("ok", new ByteProcessor(4, 4)), null, 10),
                new VariantResult(plan, null, new RuntimeException("boom"), 5)));
        log.recordEliminate(plan, null);
        log.recordPromote(plan);
        log.recordSavePreset(plan, "Notebook Filter");

        assertEquals(4, log.entries().size());
        assertEquals("GENERATE", log.entries().get(0).action);
        assertEquals("2", log.entries().get(0).detail.get("total"));
        assertEquals("1", log.entries().get(0).detail.get("failed"));
        assertEquals("ELIMINATE", log.entries().get(1).action);
        assertEquals("PROMOTE", log.entries().get(2).action);
        assertEquals("SAVE_PRESET", log.entries().get(3).action);
        assertEquals("Notebook Filter", log.entries().get(3).detail.get("preset"));
        assertEquals("sigma=2", log.entries().get(3).detail.get("label"));
        assertEquals("sigma=2 stack", log.entries().get(3).detail.get("n1"));
    }

    @Test
    public void entriesAreImmutableSnapshotsAndClearable() {
        VariationSessionLog log = new VariationSessionLog();
        Map<String, String> detail = new LinkedHashMap<String, String>();
        detail.put("a", "b");

        log.record(new VariationSessionLog.LogEntry(null, "CUSTOM", "summary", detail));
        detail.put("a", "changed");

        assertEquals("b", log.entries().get(0).detail.get("a"));
        try {
            log.entries().add(log.entries().get(0));
        } catch (UnsupportedOperationException expected) {
            // expected
        }

        log.clear();
        assertTrue(log.entries().isEmpty());
    }

    private static VariantPlan plan(String label) {
        DagLine line = new DagLine("line_A",
                Collections.singletonList(
                        new DagNode("n1", OpType.GAUSSIAN_BLUR, "sigma=2 stack")));
        DagIR dag = new DagIR(1,
                Collections.singletonList(line),
                Collections.emptyList(),
                "line_A",
                "native");
        Map<String, String> delta = new LinkedHashMap<String, String>();
        delta.put("n1", "sigma=2 stack");
        return new VariantPlan(label, dag, delta);
    }
}
