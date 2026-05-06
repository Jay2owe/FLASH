package flash.pipeline.image;

import ij.ImagePlus;
import ij.ImageStack;
import ij.Prefs;
import ij.WindowManager;
import ij.macro.Interpreter;
import ij.process.ShortProcessor;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Stage 02 exit-gate verifier: every bundled preset under
 * {@code src/main/resources/named-filters/} must execute through
 * {@link FilterExecutor#runThreadSafe} without falling back to the
 * locked legacy macro path.
 *
 * <p>For <b>linear</b> presets (defaultFilter, intensityFilter, Microglia,
 * etc.) the test runs {@code runThreadSafe} end-to-end and asserts
 * {@code true} — proving the parser dispatch covered every {@code run(...)}
 * line natively.
 *
 * <p>For <b>compound DAG</b> presets (Puncta Resolve, Diffuse Object) we
 * verify their compound-handler {@code matches(...)} fingerprint hits.
 * Actually invoking the compound handlers in this headless harness drives
 * the IJ1 macro interpreter through {@code Auto_Local_Threshold} and
 * {@code Gaussian Blur 3D}, both of which can stall or deadlock without
 * a real Fiji environment — the production manual-test in stage 01 / 02
 * exit gate covers numerical parity. Here we only need to prove the
 * {@code runThreadSafe} dispatch will route through the compound handler
 * (returning {@code true}) rather than the locked legacy fallback.
 */
public class BundledFilterPresetIntegrationTest {

    /** All preset display names registered in {@link NamedFilterLoader}. */
    private static final List<String> ALL_PRESETS =
            Arrays.asList(NamedFilterLoader.FILTER_NAMES);

    /** Presets handled by Tier 1 native execution (no WindowManager). */
    private static final Set<String> LINEAR_PRESETS = new HashSet<String>(Arrays.asList(
            "Default",
            "Punctate Signal / High Background",
            "Ramified Cells (Microglia/Astrocytes)",
            "Clustered Small",
            "Clustered Large",
            "Overlapping Cellular Marker"
    ));

    /** Presets handled by a compound handler ({@code matches} probe). */
    private static final Set<String> COMPOUND_PRESETS = new HashSet<String>(Arrays.asList(
            "Puncta Resolve",
            "Diffuse Object"
    ));

    @Rule
    public Timeout globalTimeout = Timeout.seconds(60);

    private boolean priorBatchMode;
    private boolean priorBlackBg;

    @Before
    public void setUp() {
        priorBatchMode = Interpreter.batchMode;
        Interpreter.batchMode = true;
        priorBlackBg = Prefs.blackBackground;
        Prefs.blackBackground = true;
        clearAllWindows();
    }

    @After
    public void tearDown() {
        clearAllWindows();
        Interpreter.batchMode = priorBatchMode;
        Prefs.blackBackground = priorBlackBg;
    }

    @Test
    public void everyLinearPreset_runsNativelyViaRunThreadSafe() {
        for (String preset : LINEAR_PRESETS) {
            String macro = NamedFilterLoader.loadFilterContent(preset);
            assertNotNull("Preset macro must load: " + preset, macro);

            ImagePlus imp = makeStack16Bit("test-input-" + preset, 5);
            int sliceBefore = imp.getStackSize();

            boolean result;
            try {
                result = FilterExecutor.runThreadSafe(imp, macro);
            } catch (Exception e) {
                throw new AssertionError("Linear preset '" + preset
                        + "' threw during runThreadSafe: " + e, e);
            }

            assertTrue("Linear preset '" + preset + "' must execute through the"
                    + " native Tier 1 path (returned false → fell back to"
                    + " legacy macro). Macro:\n" + macro, result);
            assertEquals("Preset '" + preset + "' must preserve stack size",
                    sliceBefore, imp.getStackSize());
        }
    }

    @Test
    public void everyCompoundPreset_routesThroughItsCompoundHandler() {
        // The compound presets contain non-run() control flow (selectImage,
        // imageCalculator, rename, close) so they can never be parsed as a
        // pure linear chain. Stage 02 routes them through PunctaResolveFilter
        // / DiffuseObjectFilter so runThreadSafe still returns true. We probe
        // the matcher rather than invoking the macro — actual numerical
        // execution is covered by the manual exit-gate test in Fiji.
        String puncta = NamedFilterLoader.loadFilterContent("Puncta Resolve");
        assertNotNull(puncta);
        assertTrue("PunctaResolveFilter must claim the Puncta Resolve macro",
                PunctaResolveFilter.matches(puncta));

        String diffuse = NamedFilterLoader.loadFilterContent("Diffuse Object");
        assertNotNull(diffuse);
        assertTrue("DiffuseObjectFilter must claim the Diffuse Object macro",
                DiffuseObjectFilter.matches(diffuse));
    }

    @Test
    public void everyBundledPresetIsClassifiedExactlyOnce() {
        // No preset may appear in both the linear and compound buckets.
        Set<String> overlap = new HashSet<String>(LINEAR_PRESETS);
        overlap.retainAll(COMPOUND_PRESETS);
        assertTrue("Linear and compound buckets must be disjoint: " + overlap,
                overlap.isEmpty());

        // Every preset registered in NamedFilterLoader must fall in one bucket
        // — if a new preset is added without being classified, this test
        // catches the omission.
        Set<String> classified = new HashSet<String>(LINEAR_PRESETS);
        classified.addAll(COMPOUND_PRESETS);
        for (String preset : ALL_PRESETS) {
            assertTrue("Preset '" + preset + "' is not classified linear or"
                    + " compound — wire it up before relying on runThreadSafe.",
                    classified.contains(preset));
        }
    }

    @Test
    public void linearPresets_leaveNoStrayWindows() {
        for (String preset : LINEAR_PRESETS) {
            int[] beforeIds = WindowManager.getIDList();
            int beforeCount = beforeIds == null ? 0 : beforeIds.length;

            String macro = NamedFilterLoader.loadFilterContent(preset);
            ImagePlus imp = makeStack16Bit("strays-" + preset, 5);
            FilterExecutor.runThreadSafe(imp, macro);

            int[] afterIds = WindowManager.getIDList();
            int afterCount = afterIds == null ? 0 : afterIds.length;
            assertEquals("Preset '" + preset + "' leaked window(s) into WindowManager",
                    beforeCount, afterCount);
        }
    }

    private static ImagePlus makeStack16Bit(String title, int slices) {
        ImageStack stk = new ImageStack(32, 32);
        java.util.Random r = new java.util.Random(1234L);
        for (int s = 0; s < slices; s++) {
            ShortProcessor sp = new ShortProcessor(32, 32);
            short[] px = (short[]) sp.getPixels();
            for (int i = 0; i < px.length; i++) {
                int v = 800 + r.nextInt(400);
                if ((i % 32 < 8) && (i / 32 < 8)) v += 2000;
                px[i] = (short) v;
            }
            stk.addSlice(sp);
        }
        return new ImagePlus(title, stk);
    }

    private static void clearAllWindows() {
        int[] ids = WindowManager.getIDList();
        if (ids == null) return;
        for (int id : ids) {
            ImagePlus w = WindowManager.getImage(id);
            if (w != null) {
                w.changes = false;
                w.close();
            }
        }
    }
}
