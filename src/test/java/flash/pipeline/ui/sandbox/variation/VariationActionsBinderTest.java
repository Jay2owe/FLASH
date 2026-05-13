package flash.pipeline.ui.sandbox.variation;

import flash.pipeline.image.FilterMacroParser.OpType;
import flash.pipeline.image.dag.Combiner;
import flash.pipeline.image.dag.DagIR;
import flash.pipeline.image.dag.DagLine;
import flash.pipeline.image.dag.DagNode;
import flash.pipeline.image.variation.VariantPlan;
import org.junit.Test;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class VariationActionsBinderTest {

    @Test
    public void promoteCallsTargetAndRecordsLog() {
        final AtomicReference<DagIR> promoted = new AtomicReference<DagIR>();
        final AtomicReference<String> status = new AtomicReference<String>();
        VariationSessionLog log = new VariationSessionLog();
        VariationActionsBinder binder = new VariationActionsBinder(
                new VariationActionsBinder.Target() {
                    @Override public void promote(DagIR dag, String label) {
                        promoted.set(dag);
                    }
                },
                null,
                log,
                null,
                "source",
                new VariationActionsBinder.StatusSink() {
                    @Override public void setStatus(String text) {
                        status.set(text);
                    }
                });
        VariantPlan plan = plan("sigma=4");

        binder.onPromote(plan);

        assertSame(plan.dag, promoted.get());
        assertEquals(1, log.entries().size());
        assertEquals("PROMOTE", log.entries().get(0).action);
        assertTrue(status.get().contains("sigma=4"));
    }

    @Test
    public void savePresetUsesInjectedWriterAndSanitizedNameOnly() throws Exception {
        final AtomicReference<String> savedName = new AtomicReference<String>();
        final AtomicReference<VariantPlan> savedPlan = new AtomicReference<VariantPlan>();
        VariationSessionLog log = new VariationSessionLog();
        VariationActionsBinder binder = new VariationActionsBinder(
                new VariationActionsBinder.Target() {
                    @Override public void promote(DagIR dag, String label) {
                    }
                },
                null,
                log,
                new VariationPresetWriter() {
                    @Override public void savePreset(String presetName, VariantPlan plan) {
                        savedName.set(presetName);
                        savedPlan.set(plan);
                    }
                },
                "source",
                null);
        VariantPlan plan = plan("sigma=4");

        String safeName = binder.savePreset(plan, " IBA1 cleanup/filter ");

        assertEquals("IBA1_cleanup_filter", safeName);
        assertEquals("IBA1_cleanup_filter", savedName.get());
        assertSame(plan, savedPlan.get());
        assertEquals(1, log.entries().size());
        assertEquals("SAVE_PRESET", log.entries().get(0).action);
    }

    private static VariantPlan plan(String label) {
        return new VariantPlan(label, dag("sigma=4 stack"),
                Collections.singletonMap("n1", "sigma=4 stack"));
    }

    private static DagIR dag(String args) {
        DagNode node = new DagNode("n1", OpType.GAUSSIAN_BLUR, args);
        return new DagIR(1,
                Collections.singletonList(new DagLine("line_A",
                        Collections.singletonList(node))),
                Collections.<Combiner>emptyList(),
                "line_A",
                "native");
    }
}
