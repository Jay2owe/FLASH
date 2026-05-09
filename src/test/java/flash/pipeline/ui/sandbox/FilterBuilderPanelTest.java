package flash.pipeline.ui.sandbox;

import flash.pipeline.image.dag.DagIR;
import flash.pipeline.image.dag.IjmToDagLoader;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

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
}
