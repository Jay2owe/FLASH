package flash.pipeline.ui.sandbox;

import flash.pipeline.image.dag.DagIR;
import flash.pipeline.image.dag.DagLine;
import flash.pipeline.image.dag.DagNode;
import flash.pipeline.image.dag.IjmToDagLoader;
import flash.pipeline.image.FilterMacroParser.OpType;
import flash.pipeline.image.dag.Combiner;
import flash.pipeline.image.variation.VariantAxis;
import flash.pipeline.image.variation.VariantPlan;
import flash.pipeline.image.variation.VariantSampler;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Headless tests for {@link FilterBuilderPanel}'s public API. The panel must
 * construct, snapshot, and load presets without showing a Window — exercising
 * any code path that pops a JOptionPane is out of scope here.
 */
public class FilterBuilderPanelTest {

    private static final String SEED_MACRO =
            "run(\"Gaussian Blur...\", \"sigma=2\");\n";
    private static final String OTHER_MACRO =
            "run(\"Gaussian Blur...\", \"sigma=4\");\n";

    @Test
    public void constructionWithSeedExposesNonNullDag() {
        DagIR seed = IjmToDagLoader.load(SEED_MACRO);
        FilterBuilderPanel panel = new FilterBuilderPanel(seed, null, noopRunner(), null);

        assertNotNull("currentDag must not be null after construction", panel.currentDag());
        assertNotNull("currentIjm must not be null after construction", panel.currentIjm());
        assertFalse("freshly constructed panel must not be dirty", panel.isDirty());
    }

    @Test
    public void markSavedKeepsPanelClean() {
        DagIR seed = IjmToDagLoader.load(SEED_MACRO);
        FilterBuilderPanel panel = new FilterBuilderPanel(seed, null, noopRunner(), null);

        panel.markSaved();
        assertFalse("markSaved must not mark a clean panel dirty", panel.isDirty());
    }

    @Test
    public void loadPresetResetsDirtyBaselineAndFiresChangeListener() {
        DagIR seed = IjmToDagLoader.load(SEED_MACRO);
        FilterBuilderPanel panel = new FilterBuilderPanel(seed, null, noopRunner(), null);

        AtomicInteger fired = new AtomicInteger(0);
        panel.addChangeListener(new Runnable() {
            @Override public void run() { fired.incrementAndGet(); }
        });

        DagIR replacement = IjmToDagLoader.load(OTHER_MACRO);
        panel.loadPreset(replacement, "Other");

        assertFalse("loadPreset must reset dirty baseline to the new chain", panel.isDirty());
        assertTrue("loadPreset must fire registered change listeners", fired.get() >= 1);
        assertNotNull(panel.currentDag());
    }

    @Test
    public void replaceCurrentDagPreservesSavedBaselineAndMarksDirty() {
        DagIR seed = IjmToDagLoader.load(SEED_MACRO);
        FilterBuilderPanel panel = new FilterBuilderPanel(seed, null, noopRunner(), null);
        AtomicInteger fired = new AtomicInteger(0);
        panel.addChangeListener(new Runnable() {
            @Override public void run() { fired.incrementAndGet(); }
        });

        panel.replaceCurrentDag(IjmToDagLoader.load(OTHER_MACRO), "Applied variation");

        assertTrue("promotion must leave the builder dirty until Save is clicked",
                panel.isDirty());
        assertTrue("promoted DAG must become the current emitted filter",
                panel.currentIjm().contains("sigma=4"));
        assertTrue("promotion must notify hosts so Save/Cancel state can update",
                fired.get() >= 1);
    }

    @Test
    public void generatedVariationPromotionRoundTripsAndPreservesDisabledNodes() {
        DagIR baseline = dagWithDisabledLegacyAndUnknown("sigma=2 stack");
        VariantAxis axis = new VariantAxis("active", VariantAxis.Kind.PARAM_SWEEP,
                Collections.singletonList(new VariantAxis.AlternativeValue(
                        "sigma=6", null, "sigma=6 stack")));
        VariantPlan promoted = VariantSampler.ofat(baseline,
                Collections.singletonList(axis), 2).get(1);
        FilterBuilderPanel panel = new FilterBuilderPanel(baseline, null, noopRunner(), null);

        panel.replaceCurrentDag(promoted.dag, "Applied variation: sigma=6");

        String ijm = panel.currentIjm();
        assertTrue("promoted IJM must contain the generated parameter value",
                ijm.contains("sigma=6"));
        assertFalse("disabled legacy command must not execute from the IJM body",
                ijm.contains("run(\"Legacy Disabled\""));
        assertTrue("embedded DAG JSON must keep disabled nodes",
                ijm.contains("\"disabled\":true"));
        DagIR reloaded = IjmToDagLoader.load(ijm);
        assertEquals("promoted DAG must survive IJM emission and reload",
                panel.currentDag(), reloaded);
        assertEquals("native", reloaded.executionTier);
        assertTrue(findNode(reloaded, "disabledLegacy").disabled);
        assertTrue(findNode(reloaded, "disabledUnknown").disabled);
    }

    @Test
    public void constructorChangeListenerFiresOnLoadPreset() {
        DagIR seed = IjmToDagLoader.load(SEED_MACRO);
        AtomicInteger fired = new AtomicInteger(0);
        FilterBuilderPanel panel = new FilterBuilderPanel(seed, null, noopRunner(), new Runnable() {
            @Override public void run() { fired.incrementAndGet(); }
        });

        DagIR replacement = IjmToDagLoader.load(OTHER_MACRO);
        panel.loadPreset(replacement, "Other");

        assertTrue("onModelChanged callback must fire on loadPreset", fired.get() >= 1);
    }

    @Test
    public void multiplePanelsShareStaticCatalogStateSafely() {
        DagIR seed = IjmToDagLoader.load(SEED_MACRO);
        FilterBuilderPanel first = new FilterBuilderPanel(seed, null, noopRunner(), null);
        FilterBuilderPanel second = new FilterBuilderPanel(seed, null, noopRunner(), null);

        // FilterCatalog has a static tier-two cache; constructing two panels must
        // not throw and both panels must independently report a non-null DAG.
        assertNotNull(first.currentDag());
        assertNotNull(second.currentDag());
    }

    // ── Stage 04 structural-mutation API ────────────────────────────────

    @Test
    public void appendNode_emitsExpectedIjm() {
        DagIR seed = IjmToDagLoader.load(SEED_MACRO);
        FilterBuilderPanel panel = new FilterBuilderPanel(seed, null, noopRunner(), null);
        FilterCatalog catalog = new FilterCatalog();
        FilterCatalog.Entry medianEntry = catalog.findEntryByLabel("Median");
        assertNotNull("Median entry must be present in the catalog", medianEntry);

        int nodeCountBefore = panel.nodeSummaries().size();
        panel.appendNode(medianEntry);
        List<FilterBuilderPanel.NodeSummary> after = panel.nodeSummaries();
        assertEquals(nodeCountBefore + 1, after.size());
        String ijm = panel.currentIjm();
        assertTrue("Emitted IJM must contain the appended Median run() line",
                ijm.contains("run(\"Median..."));
        assertTrue("Original Gaussian Blur step must remain in emitted IJM",
                ijm.contains("run(\"Gaussian Blur..."));
        assertTrue("Median must be emitted after Gaussian Blur (appendNode == end)",
                ijm.indexOf("run(\"Gaussian Blur") < ijm.indexOf("run(\"Median"));
    }

    @Test
    public void appendNodeWithArgs_emitsLegacyIjm() {
        DagIR seed = IjmToDagLoader.load(SEED_MACRO);
        FilterBuilderPanel panel = new FilterBuilderPanel(seed, null, noopRunner(), null);
        FilterCatalog.Entry pluginEntry = FilterCatalog.Entry.legacy(
                "Fiji commands", "Plugin Filter", "Fiji commands > Plugin Filter");

        panel.appendNode(pluginEntry, "radius=5 stack");

        String ijm = panel.currentIjm();
        assertTrue("Legacy append must switch the emitted DAG tier",
                ijm.contains("executionTier=legacy"));
        assertTrue("Legacy append must emit the captured options string",
                ijm.contains("run(\"Plugin Filter\", \"radius=5 stack\");"));
    }

    @Test
    public void setNodeDisabled_omitsFromIjmButPreservesInDagJson() {
        DagIR seed = IjmToDagLoader.load(SEED_MACRO);
        FilterBuilderPanel panel = new FilterBuilderPanel(seed, null, noopRunner(), null);

        List<FilterBuilderPanel.NodeSummary> summaries = panel.nodeSummaries();
        assertFalse(summaries.isEmpty());
        String firstId = summaries.get(0).id;

        panel.setNodeDisabled(firstId, true);
        String ijm = panel.currentIjm();

        assertFalse("Disabled node's run() must be absent from the IJM body",
                ijm.contains("run(\"Gaussian Blur..."));
        assertTrue("Embedded DAG JSON header must mark the node disabled",
                ijm.contains("\"disabled\":true"));

        DagIR reloaded = IjmToDagLoader.load(ijm);
        assertNotNull(reloaded);
        assertFalse(reloaded.lines.isEmpty());
        assertFalse(reloaded.lines.get(0).ops.isEmpty());
        assertTrue("Round-trip through embedded JSON must restore disabled=true",
                reloaded.lines.get(0).ops.get(0).disabled);
    }

    @Test
    public void disabledLegacyNodeDoesNotForceSandboxLegacyState() {
        DagNode legacy = new DagNode("legacy", OpType.UNKNOWN, "raw",
                "Plugin Filter", "Plugins > Filter");
        legacy.disabled = true;
        DagIR seed = new DagIR(
                1,
                Collections.singletonList(new DagLine("A", Collections.singletonList(legacy))),
                Collections.<Combiner>emptyList(),
                "A",
                "native");

        SandboxModel model = SandboxModel.fromDag(seed);

        assertEquals("native", seed.executionTier);
        assertEquals("native", model.toDag().executionTier);
        assertFalse("disabled legacy node must not show the legacy banner",
                model.hasLegacyNode());
    }

    @Test
    public void partialDagPreservesDisabledStateAndLegacyMetadata() {
        DagNode legacy = new DagNode("legacy", OpType.UNKNOWN, "raw",
                "Plugin Filter", "Plugins > Filter");
        legacy.disabled = true;
        DagIR seed = new DagIR(
                1,
                Collections.singletonList(new DagLine("A", Collections.singletonList(legacy))),
                Collections.<Combiner>emptyList(),
                "A",
                "native");
        SandboxModel model = SandboxModel.fromDag(seed);
        model.selected = model.lines.get(0).nodes.get(0);

        DagIR partial = model.toPartialDag();
        DagNode partialNode = partial.lines.get(0).ops.get(0);

        assertEquals("native", partial.executionTier);
        assertEquals("legacy", partialNode.id);
        assertEquals(OpType.UNKNOWN, partialNode.type);
        assertEquals("raw", partialNode.args);
        assertEquals("Plugin Filter", partialNode.commandName);
        assertEquals("Plugins > Filter", partialNode.menuPath);
        assertTrue(partialNode.disabled);
    }

    @Test
    public void reorder_changesEmittedOrder() {
        String macro = "run(\"Gaussian Blur...\", \"sigma=2 stack\");\n"
                + "run(\"Median...\", \"radius=2 stack\");\n";
        DagIR seed = IjmToDagLoader.load(macro);
        FilterBuilderPanel panel = new FilterBuilderPanel(seed, null, noopRunner(), null);

        String before = panel.currentIjm();
        assertTrue("Pre-reorder, Gaussian Blur must come before Median",
                before.indexOf("run(\"Gaussian Blur") < before.indexOf("run(\"Median"));

        panel.reorder(0, 1);
        String after = panel.currentIjm();
        assertTrue("Post-reorder, Median must come before Gaussian Blur",
                after.indexOf("run(\"Median") < after.indexOf("run(\"Gaussian Blur"));
    }

    @Test
    public void seedMacroProducesCompatibleSandboxResult() {
        // Smoke: a known-good seed macro round-trips through the panel and yields
        // a non-null DAG snapshot — stand-in for the production caller's
        // SandboxDialog.show(...) success path that returns Result.dag != null.
        DagIR seed = IjmToDagLoader.load(SEED_MACRO);
        assertNotNull(seed);
        FilterBuilderPanel panel = new FilterBuilderPanel(seed, null, noopRunner(), null);
        DagIR snapshot = panel.currentDag();
        assertNotNull("currentDag must produce a non-null snapshot", snapshot);
        assertNotNull("repeated currentDag must continue to produce a snapshot",
                panel.currentDag());
    }

    private static FilterBuilderPanel.PreviewRunner noopRunner() {
        return new FilterBuilderPanel.PreviewRunner() {
            @Override public ImagePlus createSource() {
                ImageStack stack = new ImageStack(2, 2);
                stack.addSlice(new ByteProcessor(2, 2));
                return new ImagePlus("source", stack);
            }
            @Override public ImagePlus showPreview(ImagePlus result, ImagePlus existingPreview) {
                return result;
            }
            @Override public void close(ImagePlus imp) {
                // no-op
            }
        };
    }

    private static DagIR dagWithDisabledLegacyAndUnknown(String activeArgs) {
        DagNode active = new DagNode("active", OpType.GAUSSIAN_BLUR, activeArgs);
        DagNode disabledLegacy = new DagNode("disabledLegacy", OpType.UNKNOWN,
                "radius=99 stack", "Legacy Disabled", "Plugins > Legacy Disabled");
        disabledLegacy.disabled = true;
        DagNode disabledUnknown = new DagNode("disabledUnknown", OpType.UNKNOWN,
                "selectWindow(\"legacy\");");
        disabledUnknown.disabled = true;
        return new DagIR(1,
                Collections.singletonList(new DagLine("line_A",
                        Arrays.asList(active, disabledLegacy, disabledUnknown))),
                Collections.<Combiner>emptyList(),
                "line_A",
                "native");
    }

    private static DagNode findNode(DagIR dag, String id) {
        for (DagLine line : dag.lines) {
            for (DagNode node : line.ops) {
                if (id.equals(node.id)) return node;
            }
        }
        throw new AssertionError("Missing node: " + id);
    }
}
