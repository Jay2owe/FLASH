package flash.pipeline.ui.sandbox.variation;

import flash.pipeline.image.FilterMacroParser.OpType;
import flash.pipeline.image.dag.DagIR;
import flash.pipeline.image.dag.DagLine;
import flash.pipeline.image.dag.DagNode;
import flash.pipeline.image.dag.DagToIjmEmitter;
import flash.pipeline.image.variation.VariantPlan;
import ij.ImagePlus;
import ij.process.ByteProcessor;
import org.junit.Test;

import javax.swing.JButton;
import java.awt.Component;
import java.awt.Container;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class IjmClipboardExporterTest {

    @Test
    public void buildTextContainsOneLabelledHeaderPerVisibleVariant() {
        VariantPlan first = plan("sigma=2", "sigma=2 stack");
        VariantPlan second = plan("sigma=4", "sigma=4 stack");

        String text = IjmClipboardExporter.buildText(Arrays.asList(first, second));

        assertEquals(2, count(text, "// VARIANT:"));
        assertTrue(text.contains("// VARIANT: sigma=2"));
        assertTrue(text.contains("// VARIANT: sigma=4"));
        assertTrue(text.contains(DagToIjmEmitter.emit(first.dag)));
        assertTrue(text.contains(DagToIjmEmitter.emit(second.dag)));
    }

    @Test
    public void buildTextSkipsNullPlansAndNullDags() {
        VariantPlan plan = plan("sigma=2", "sigma=2 stack");

        String text = IjmClipboardExporter.buildText(Arrays.asList(null, plan));

        assertEquals(1, count(text, "// VARIANT:"));
        assertTrue(text.contains("run(\"Gaussian Blur...\", \"sigma=2 stack\");"));
    }

    @Test
    public void buildTextFromTilesUsesVariantPlansOnly() {
        VariantPlan plan = plan("sigma=2", "sigma=2 stack");
        TilePanel raw = new TilePanel(new ImagePlus("raw", new ByteProcessor(4, 4)),
                "RAW",
                true,
                null);
        TilePanel variant = new TilePanel(new ImagePlus("variant", new ByteProcessor(4, 4)),
                "sigma=2",
                false,
                plan);

        String text = IjmClipboardExporter.buildTextFromTiles(Arrays.asList(raw, variant));

        assertEquals(1, count(text, "// VARIANT:"));
        assertTrue(text.contains("// VARIANT: sigma=2"));
    }

    @Test
    public void tileSaveActionCanDelegateThroughInjectedPresetWriter() {
        final VariantPlan plan = plan("sigma=2", "sigma=2 stack");
        final AtomicReference<String> presetName = new AtomicReference<String>();
        final AtomicReference<VariantPlan> receivedPlan = new AtomicReference<VariantPlan>();
        final AtomicReference<String> emittedIjm = new AtomicReference<String>();
        final VariationPresetWriter writer = new VariationPresetWriter() {
            @Override public void savePreset(String name, VariantPlan variantPlan) {
                presetName.set(name);
                receivedPlan.set(variantPlan);
                emittedIjm.set(DagToIjmEmitter.emit(variantPlan.dag));
            }
        };
        TilePanel tile = new TilePanel(new ImagePlus("variant", new ByteProcessor(4, 4)),
                "sigma=2",
                false,
                plan);
        tile.setActions(new TileActionListener() {
            @Override public void onPromote(VariantPlan plan) {}

            @Override public void onSavePreset(VariantPlan selectedPlan) {
                try {
                    writer.savePreset("Notebook Filter", selectedPlan);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }
        }, null);

        JButton save = findButton(tile, "Save");
        assertNotNull(save);
        save.doClick();

        assertEquals("Notebook Filter", presetName.get());
        assertSame(plan, receivedPlan.get());
        assertTrue(emittedIjm.get().contains("source_id = getImageID();"));
        assertTrue(emittedIjm.get().contains("run(\"Gaussian Blur...\", \"sigma=2 stack\");"));
    }

    private static VariantPlan plan(String label, String args) {
        DagLine line = new DagLine("line_A",
                Collections.singletonList(new DagNode("n1", OpType.GAUSSIAN_BLUR, args)));
        DagIR dag = new DagIR(1,
                Collections.singletonList(line),
                Collections.emptyList(),
                "line_A",
                "native");
        return new VariantPlan(label, dag, Collections.singletonMap("n1", args));
    }

    private static int count(String text, String needle) {
        int count = 0;
        int offset = 0;
        while (true) {
            int idx = text.indexOf(needle, offset);
            if (idx < 0) return count;
            count++;
            offset = idx + needle.length();
        }
    }

    private static JButton findButton(Container container, String text) {
        for (Component child : container.getComponents()) {
            if (child instanceof JButton && text.equals(((JButton) child).getText())) {
                return (JButton) child;
            }
            if (child instanceof Container) {
                JButton nested = findButton((Container) child, text);
                if (nested != null) return nested;
            }
        }
        return null;
    }
}
