package flash.pipeline.image;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.Prefs;
import ij.measure.ResultsTable;
import ij.plugin.ContrastEnhancer;
import ij.plugin.GaussianBlur3D;
import ij.plugin.filter.BackgroundSubtracter;
import ij.plugin.filter.GaussianBlur;
import ij.plugin.filter.RankFilters;
import ij.plugin.frame.RoiManager;
import ij.process.BinaryProcessor;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageConverter;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;
import ij.process.ShortProcessor;

import flash.pipeline.image.ParallelContext;
import flash.pipeline.image.dag.Combiner;
import flash.pipeline.image.dag.CombinerOp;
import flash.pipeline.image.dag.DagIR;
import flash.pipeline.image.dag.DagLine;
import flash.pipeline.image.dag.DagNode;
import flash.pipeline.image.dag.DagRejectedException;
import flash.pipeline.image.dag.DagToIjmEmitter;
import flash.pipeline.image.dag.IjmToDagLoader;

import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Executes ImageJ filter macros on an ImagePlus.
 *
 * Provides both legacy macro-based execution (single-threaded, requires active window)
 * and thread-safe native Java execution using ImageJ's processor-level APIs.
 */
public final class FilterExecutor {

    /** Minimum slice count before parallel execution kicks in. */
    private static final int SLICE_PARALLEL_THRESHOLD = 4;

    private FilterExecutor() {}

    // ── Legacy macro-based execution (NOT thread-safe) ──

    public static void runIjmFile(final ImagePlus imp, final File ijmFile) {
        if (imp == null || ijmFile == null || !ijmFile.exists()) return;
        try {
            String content = new String(java.nio.file.Files.readAllBytes(ijmFile.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            Boolean dagResult = runEmbeddedDagIfPresent(imp, content);
            if (dagResult != null) return;
        } catch (Exception e) {
            IJ.log("WARNING: could not inspect IJM file for embedded Sandbox DAG: " + e.getMessage());
        }
        runLegacyMacroSandboxed(imp, new Runnable() {
            @Override
            public void run() {
                IJ.runMacroFile(ijmFile.getAbsolutePath());
            }
        });
    }

    public static void runMacroString(final ImagePlus imp, final String macroContent) {
        if (imp == null || macroContent == null || macroContent.isEmpty()) return;
        final String safeMacro = normalizeDuplicateCommand(macroContent);
        Boolean dagResult = runEmbeddedDagIfPresent(imp, safeMacro);
        if (dagResult != null) return;
        runLegacyMacroSandboxed(imp, new Runnable() {
            @Override
            public void run() {
                IJ.runMacro(safeMacro);
            }
        });
    }

    /**
     * Regex for a bare {@code run("Duplicate", ...)} / {@code run("Duplicate")}
     * call — i.e. the standard duplicator command spelled WITHOUT its trailing
     * {@code ...}. Matches the command token only; the args (if any) are left
     * untouched.
     */
    private static final java.util.regex.Pattern BARE_DUPLICATE = java.util.regex.Pattern.compile(
            "(run\\s*\\(\\s*\")Duplicate(\"\\s*[,)])");

    /**
     * REGRESSION GUARD (docs/filter-branch-robustness_COMPLETED): a FLASH-generated filter
     * macro must never run {@code run("Duplicate", ...)} without the ellipsis —
     * ImageJ then dispatches it to the Image5D plugin's {@code Duplicate}
     * command, which throws "Image is not an Image5D" on a normal stack. Upstream
     * fixes (verbatim command names in the DAG round-trip, node-identity arg
     * binding) should prevent this ever reaching here; this is a last-resort
     * backstop scoped to the internal filter execution path. It deliberately
     * targets only the exact bare {@code "Duplicate"} token, so a macro that
     * already uses {@code "Duplicate..."} is left unchanged.
     */
    static String normalizeDuplicateCommand(String macroContent) {
        if (macroContent == null || macroContent.indexOf("Duplicate") < 0) {
            return macroContent;
        }
        return BARE_DUPLICATE.matcher(macroContent).replaceAll("$1Duplicate...$2");
    }

    /**
     * Runs an IJ1 macro fragment under a safety sandbox.
     * <p>
     * Snapshots {@link ij.WindowManager} state before the macro runs and, after
     * it completes (or throws), force-closes any image windows the macro left
     * behind that are not the kept input image, then resets the global
     * {@link ResultsTable} and {@link RoiManager}.
     * <p>
     * This protects batch runs from custom macros that forget {@code close();}
     * or leak ROIs/results across iterations. The kept input image (if non-null)
     * is excluded from the sweep — adoption of a renamed result, if any, runs
     * before the sweep so adopted images do not appear stray.
     * <p>
     * Caller is responsible for any required {@link WindowManagerLock} acquisition.
     */
    private static void runLegacyMacroSandboxed(ImagePlus imp, Runnable macroCall) {
        runLegacyMacroSandboxed(imp, macroCall, null);
    }

    /**
     * Runs an IJ1 macro fragment under a safety sandbox, optionally preserving
     * known secondary output images.
     *
     * @param imp input image made current for the macro, or {@code null}
     * @param macroCall macro invocation to run
     * @param keepTitles optional set of image titles to spare from the
     *                   {@link #closeStrayImages} sweep — used when a macro
     *                   intentionally produces a secondary output (e.g. a
     *                   mask channel) that the caller wants to adopt.
     */
    private static void runLegacyMacroSandboxed(ImagePlus imp, Runnable macroCall, Set<String> keepTitles) {
        String originalTitle = imp == null ? null : imp.getTitle();
        int originalId = imp == null ? 0 : imp.getID();
        Set<Integer> beforeIds = snapshotWindowIds();
        boolean attachedTemp = false;
        try {
            if (imp != null) {
                if (GraphicsEnvironment.isHeadless()) {
                    // Headless: there is no ImageWindow framework. Register
                    // imp as the macro interpreter's current image without
                    // opening a window — avoids the imp.show() side effects
                    // that otherwise log warnings or fail under --headless.
                    ij.WindowManager.setTempCurrentImage(imp);
                    attachedTemp = true;
                } else {
                    imp.show();
                    imp.setActivated();
                }
            }
            macroCall.run();
            if (imp != null) adoptResultIfOriginalClosed(imp, originalTitle, originalId);
        } finally {
            if (attachedTemp) {
                ij.WindowManager.setTempCurrentImage(null);
            }
            closeStrayImages(beforeIds, imp, keepTitles);
            resetResultsTable();
            resetRoiManager();
        }
    }

    private static Set<Integer> snapshotWindowIds() {
        int[] ids = ij.WindowManager.getIDList();
        Set<Integer> set = new HashSet<Integer>();
        if (ids != null) {
            for (int id : ids) set.add(id);
        }
        return set;
    }

    private static void closeStrayImages(Set<Integer> beforeIds, ImagePlus keep) {
        closeStrayImages(beforeIds, keep, null);
    }

    private static void closeStrayImages(Set<Integer> beforeIds, ImagePlus keep, Set<String> keepTitles) {
        int[] after = ij.WindowManager.getIDList();
        if (after == null) return;
        for (int id : after) {
            if (beforeIds.contains(id)) continue;
            ImagePlus rogue = ij.WindowManager.getImage(id);
            if (rogue == null || rogue == keep) continue;
            if (keepTitles != null && keepTitles.contains(rogue.getTitle())) continue;
            IJ.log("WARNING: custom macro leaked image '" + rogue.getTitle()
                    + "', auto-closing.");
            rogue.changes = false;
            rogue.close();
        }
    }

    private static void resetResultsTable() {
        ResultsTable rt = ResultsTable.getResultsTable();
        if (rt != null && rt.getCounter() > 0) {
            rt.reset();
        }
    }

    private static void resetRoiManager() {
        RoiManager rm = RoiManager.getInstance();
        if (rm != null) {
            rm.reset();
        }
    }

    /**
     * After running a macro, the original image may have been closed if the macro
     * created new images via Duplicate/imageCalculator and called close(original).
     * In that case, find the result image and adopt its data back into {@code imp}
     * so the caller's reference remains valid.
     *
     * Uses an ID snapshot to detect "modified in place": the prior
     * {@code imp.getWindow() != null} check produced false positives in
     * headless mode (no window ever exists) and false negatives when a
     * macro detached the image from its window without closing the data.
     *
     * Looks for a result image by the original title first (macros often rename
     * the result back to the original name), then falls back to the current
     * active image.
     */
    private static void adoptResultIfOriginalClosed(ImagePlus imp, String originalTitle, int originalId) {
        // Try to find the result by original title (macros may rename result to match)
        ImagePlus result = findImageByTitleExcluding(originalTitle, imp);
        ImagePlus afterById = ij.WindowManager.getImage(originalId);
        if (afterById == imp && result == null) {
            // Batch-mode close() can leave the original ID mapped to imp.
            // Still adopt a distinct active result so close-and-replace macros
            // work when they do not rename the output to the original title.
            ImagePlus current = ij.WindowManager.getCurrentImage();
            if (current != null && current != imp && current.getID() != originalId) {
                result = current;
            } else {
                return;
            }
        }
        if (result == null) {
            result = ij.WindowManager.getImage(originalTitle);
            if (result == imp) {
                result = null;
            }
        }
        if (result == null) {
            // Fall back to the current active image, but do not let the
            // headless temp-current input mask a replacement result.
            ImagePlus current = ij.WindowManager.getCurrentImage();
            if (current != imp) {
                result = current;
            }
        }
        if (result == null || result == imp || result.getStackSize() == 0) return;

        imp.setStack(result.getStack());
        imp.setDimensions(result.getNChannels(), result.getNSlices(), result.getNFrames());
        if (result.getCalibration() != null) {
            imp.setCalibration(result.getCalibration());
        }
        result.changes = false;
        result.close();
    }

    private static ImagePlus findImageByTitleExcluding(String title, ImagePlus excluded) {
        if (title == null) {
            return null;
        }
        int[] ids = ij.WindowManager.getIDList();
        if (ids == null) {
            return null;
        }
        for (int i = 0; i < ids.length; i++) {
            ImagePlus candidate = ij.WindowManager.getImage(ids[i]);
            if (candidate != null
                    && candidate != excluded
                    && title.equals(candidate.getTitle())) {
                return candidate;
            }
        }
        return null;
    }

    // ── Thread-safe native execution ──

    /**
     * Executes a filter macro string using direct Java API calls (no macro interpreter).
     * Thread-safe: operates only on the given ImagePlus's processors.
     *
     * If any operation in the macro is unrecognized, falls back to synchronized
     * macro execution for the entire macro.
     *
     * @return true if executed natively (or via a known compound handler), false
     *         if it fell back to the locked legacy macro path
     */
    public static boolean runThreadSafe(ImagePlus imp, String macroContent) {
        if (imp == null || macroContent == null || macroContent.isEmpty()) return true;

        Boolean dagResult = runEmbeddedDagIfPresent(imp, macroContent);
        if (dagResult != null) return dagResult.booleanValue();

        // Known compound filters — run under window lock
        if (PunctaResolveFilter.matches(macroContent)) {
            WindowManagerLock.LOCK.lock();
            try {
                PunctaResolveFilter.apply(imp, macroContent);
            } finally {
                WindowManagerLock.LOCK.unlock();
            }
            return true;
        }
        if (DiffuseObjectFilter.matches(macroContent)) {
            WindowManagerLock.LOCK.lock();
            try {
                DiffuseObjectFilter.apply(imp, macroContent);
            } finally {
                WindowManagerLock.LOCK.unlock();
            }
            return true;
        }

        List<FilterMacroParser.Op> ops = FilterMacroParser.parseString(macroContent);

        // Check if all ops can be executed natively
        for (FilterMacroParser.Op op : ops) {
            if (op.type == FilterMacroParser.OpType.UNKNOWN) {
                // Fall back to locked macro execution
                WindowManagerLock.LOCK.lock();
                try {
                    runMacroString(imp, macroContent);
                } finally {
                    // Close window to prevent stale entries in WindowManager
                    // that would collide with subsequent macro executions.
                    closeWindowSafely(imp);
                    WindowManagerLock.LOCK.unlock();
                }
                return false;
            }
        }

        // Execute all ops natively
        for (FilterMacroParser.Op op : ops) {
            executeOpOnStack(imp, op);
        }
        return true;
    }

    private static Boolean runEmbeddedDagIfPresent(ImagePlus imp, String macroContent) {
        DagIR dag = IjmToDagLoader.loadEmbeddedDag(macroContent);
        if (dag == null) return null;
        try {
            ImagePlus result;
            if ("legacy".equals(dag.executionTier)) {
                ImagePlus source = cloneStackPerSlice(imp, "dag_source");
                WindowManagerLock.LOCK.lock();
                try {
                    result = runLegacyDagSandboxed(source, dag);
                } finally {
                    WindowManagerLock.LOCK.unlock();
                }
            } else {
                result = runDagThreadSafe(imp, dag);
            }
            applyResultInPlace(imp, result);
            return Boolean.valueOf(!"legacy".equals(dag.executionTier));
        } catch (DagRejectedException e) {
            IJ.log("WARNING: embedded Sandbox DAG failed, falling back to macro execution: " + e.getMessage());
            return null;
        }
    }

    private static void applyResultInPlace(ImagePlus target, ImagePlus result) {
        if (target == null || result == null || result.getStackSize() == 0) return;
        target.setStack(result.getStack());
        target.setDimensions(result.getNChannels(), result.getNSlices(), result.getNFrames());
        if (result.getCalibration() != null) {
            target.setCalibration(result.getCalibration().copy());
        }
        target.setDisplayRange(result.getDisplayRangeMin(), result.getDisplayRangeMax());
        target.updateAndDraw();
    }

    /**
     * Executes a filter .ijm file using direct Java API calls.
     * Thread-safe version of runIjmFile.
     */
    public static boolean runIjmFileThreadSafe(ImagePlus imp, File ijmFile) {
        if (imp == null || ijmFile == null || !ijmFile.exists()) return true;
        try {
            // Read file content to check for compound filter patterns
            String content = new String(java.nio.file.Files.readAllBytes(ijmFile.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (PunctaResolveFilter.matches(content)) {
                WindowManagerLock.LOCK.lock();
                try {
                    PunctaResolveFilter.apply(imp, content);
                } finally {
                    WindowManagerLock.LOCK.unlock();
                }
                return true;
            }
            if (DiffuseObjectFilter.matches(content)) {
                WindowManagerLock.LOCK.lock();
                try {
                    DiffuseObjectFilter.apply(imp, content);
                } finally {
                    WindowManagerLock.LOCK.unlock();
                }
                return true;
            }

            List<FilterMacroParser.Op> ops = FilterMacroParser.parse(ijmFile);
            for (FilterMacroParser.Op op : ops) {
                if (op.type == FilterMacroParser.OpType.UNKNOWN) {
                    WindowManagerLock.LOCK.lock();
                    try {
                        runIjmFile(imp, ijmFile);
                    } finally {
                        closeWindowSafely(imp);
                        WindowManagerLock.LOCK.unlock();
                    }
                    return false;
                }
            }
            for (FilterMacroParser.Op op : ops) {
                executeOpOnStack(imp, op);
            }
            return true;
        } catch (Exception e) {
            WindowManagerLock.LOCK.lock();
            try {
                runIjmFile(imp, ijmFile);
            } finally {
                closeWindowSafely(imp);
                WindowManagerLock.LOCK.unlock();
            }
            return false;
        }
    }

    /**
     * Executes a Sandbox DAG fully natively, without showing images or touching
     * WindowManager. All line branches start from fresh per-slice crops of the
     * source image; combiner nodes always write a new 32-bit float stack.
     */
    public static ImagePlus runDagThreadSafe(ImagePlus source, DagIR dag)
            throws DagRejectedException {
        validateDagCanRunNative(source, dag);
        List<Combiner> orderedCombiners = orderCombinersTopologically(dag);

        Map<String, ImagePlus> bus = new HashMap<String, ImagePlus>();
        Map<String, Integer> remainingUses = countRemainingUses(dag);

        for (DagLine line : dag.lines) {
            ImagePlus work = cloneStackPerSlice(source, line.id);
            for (DagNode node : line.ops) {
                if (node.disabled) continue;
                executeOpOnStack(work, new FilterMacroParser.Op(node.type, node.args));
            }
            bus.put(line.id, work);
            releaseIfUnused(bus, remainingUses, line.id);
        }

        for (Combiner combiner : orderedCombiners) {
            List<ImagePlus> inputs = resolveInputs(bus, combiner);
            ImagePlus combined = combineNative(combiner, inputs);
            bus.put(combiner.id, combined);

            for (String inputId : combiner.inputs) {
                decrementUse(remainingUses, inputId);
                releaseIfUnused(bus, remainingUses, inputId);
            }
            releaseIfUnused(bus, remainingUses, combiner.id);
        }

        ImagePlus result = bus.remove(dag.output);
        if (result == null) throw new DagRejectedException("DAG output was not produced: " + dag.output);
        flushAll(bus);
        return result;
    }

    /**
     * Executes a legacy Sandbox DAG through the IJ1 macro interpreter under the
     * same WindowManager cleanup used for imported custom macros.
     */
    public static ImagePlus runLegacyDagSandboxed(final ImagePlus source, final DagIR dag)
            throws DagRejectedException {
        if (source == null) throw new DagRejectedException("Source image is required");
        if (dag == null) throw new DagRejectedException("DAG is required");
        if (!"legacy".equals(dag.executionTier)) {
            throw new DagRejectedException("DAG is not marked for legacy execution");
        }
        final ImagePlus[] result = new ImagePlus[1];
        WindowManagerLock.LOCK.lock();
        try {
            runLegacyMacroSandboxed(source, new Runnable() {
                @Override public void run() {
                    IJ.runMacro(DagToIjmEmitter.emit(dag));
                    ImagePlus output = ij.WindowManager.getImage(DagToIjmEmitter.macroTitleForId(dag.output));
                    if (output == null) output = ij.WindowManager.getCurrentImage();
                    if (output != null) {
                        result[0] = cloneStackPerSlice(output, "legacy_preview");
                    }
                }
            });
        } finally {
            closeWindowSafely(source);
            WindowManagerLock.LOCK.unlock();
        }
        if (result[0] == null) {
            throw new DagRejectedException("Legacy DAG did not produce an output image: " + dag.output);
        }
        return result[0];
    }

    private static void validateDagCanRunNative(ImagePlus source, DagIR dag)
            throws DagRejectedException {
        if (source == null) throw new DagRejectedException("Source image is required");
        if (dag == null) throw new DagRejectedException("DAG is required");
        if ("legacy".equals(dag.executionTier)) {
            throw new DagRejectedException("Legacy DAGs must run through the legacy macro sandbox");
        }
        if (!"native".equals(dag.executionTier)) {
            throw new DagRejectedException("Unsupported DAG execution tier: " + dag.executionTier);
        }
        Set<String> ids = new HashSet<String>();
        for (DagLine line : dag.lines) {
            if (line == null) throw new DagRejectedException("DAG line is required");
            if (line.id.length() == 0) throw new DagRejectedException("DAG line id is required");
            if (!ids.add(line.id)) throw new DagRejectedException("Duplicate DAG id: " + line.id);
            for (DagNode node : line.ops) {
                if (node == null) throw new DagRejectedException("DAG node is required");
                if (node.type == FilterMacroParser.OpType.UNKNOWN) {
                    throw new DagRejectedException("Unknown DAG op rejected: " + node.id);
                }
            }
        }
        for (Combiner combiner : dag.combiners) {
            if (combiner == null) throw new DagRejectedException("DAG combiner is required");
            if (combiner.id.length() == 0) throw new DagRejectedException("Combiner id is required");
            if (!ids.add(combiner.id)) throw new DagRejectedException("Duplicate DAG id: " + combiner.id);
            if (combiner.inputs.size() < 2) {
                throw new DagRejectedException("Combiner requires at least two inputs: " + combiner.id);
            }
        }
        orderCombinersTopologically(dag);
        if (dag.output == null || dag.output.length() == 0) {
            throw new DagRejectedException("DAG output is required");
        }
        if (!ids.contains(dag.output)) {
            throw new DagRejectedException("Unknown DAG output: " + dag.output);
        }
    }

    private static List<Combiner> orderCombinersTopologically(DagIR dag)
            throws DagRejectedException {
        Set<String> produced = new HashSet<String>();
        Set<String> allIds = new HashSet<String>();
        for (DagLine line : dag.lines) {
            if (line == null || line.id == null || line.id.length() == 0) continue;
            produced.add(line.id);
            allIds.add(line.id);
        }
        List<Combiner> pending = new ArrayList<Combiner>(dag.combiners);
        for (Combiner combiner : pending) {
            if (combiner != null && combiner.id != null && combiner.id.length() > 0) {
                allIds.add(combiner.id);
            }
        }

        List<Combiner> ordered = new ArrayList<Combiner>();
        while (!pending.isEmpty()) {
            boolean progressed = false;
            for (int i = 0; i < pending.size(); i++) {
                Combiner combiner = pending.get(i);
                if (combiner == null) {
                    throw new DagRejectedException("DAG combiner is required");
                }
                if (!allInputsProduced(combiner, produced)) continue;
                ordered.add(combiner);
                produced.add(combiner.id);
                pending.remove(i);
                i--;
                progressed = true;
            }
            if (!progressed) {
                throw new DagRejectedException("DAG combiner cycle or unresolved input: "
                        + describeUnresolvedCombiners(pending, allIds));
            }
        }
        return ordered;
    }

    private static boolean allInputsProduced(Combiner combiner, Set<String> produced) {
        for (String input : combiner.inputs) {
            if (!produced.contains(input)) return false;
        }
        return true;
    }

    private static String describeUnresolvedCombiners(List<Combiner> pending, Set<String> allIds) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pending.size(); i++) {
            if (i > 0) sb.append(", ");
            Combiner combiner = pending.get(i);
            sb.append(combiner == null ? "<null>" : combiner.id).append(" waiting for ");
            if (combiner == null) {
                sb.append("<null>");
                continue;
            }
            boolean first = true;
            for (String input : combiner.inputs) {
                if (allIds.contains(input)) continue;
                if (!first) sb.append("/");
                sb.append(input);
                first = false;
            }
            if (first) sb.append("upstream combiner");
        }
        return sb.toString();
    }

    private static Map<String, Integer> countRemainingUses(DagIR dag) {
        Map<String, Integer> uses = new HashMap<String, Integer>();
        if (dag.output != null && dag.output.length() > 0) incrementUse(uses, dag.output);
        for (Combiner combiner : dag.combiners) {
            for (String input : combiner.inputs) {
                incrementUse(uses, input);
            }
        }
        return uses;
    }

    private static void incrementUse(Map<String, Integer> uses, String id) {
        Integer count = uses.get(id);
        uses.put(id, count == null ? 1 : count + 1);
    }

    private static void decrementUse(Map<String, Integer> uses, String id) {
        Integer count = uses.get(id);
        if (count == null || count.intValue() <= 0) return;
        uses.put(id, count - 1);
    }

    private static void releaseIfUnused(Map<String, ImagePlus> bus,
                                        Map<String, Integer> remainingUses,
                                        String id) {
        Integer count = remainingUses.get(id);
        if (count != null && count.intValue() > 0) return;
        ImagePlus imp = bus.remove(id);
        if (imp != null) imp.flush();
    }

    private static void flushAll(Map<String, ImagePlus> bus) {
        for (ImagePlus imp : bus.values()) {
            if (imp != null) imp.flush();
        }
        bus.clear();
    }

    private static List<ImagePlus> resolveInputs(Map<String, ImagePlus> bus, Combiner combiner)
            throws DagRejectedException {
        if (combiner.inputs.size() < 2) {
            throw new DagRejectedException("Combiner requires at least two inputs: " + combiner.id);
        }
        List<ImagePlus> inputs = new ArrayList<ImagePlus>();
        for (String id : combiner.inputs) {
            ImagePlus imp = bus.get(id);
            if (imp == null) throw new DagRejectedException("Missing combiner input: " + id);
            inputs.add(imp);
        }
        return inputs;
    }

    private static ImagePlus cloneStackPerSlice(ImagePlus source, String label) {
        ImageStack src = source.getStack();
        int width = source.getWidth();
        int height = source.getHeight();
        ImageStack copy = new ImageStack(width, height);
        for (int s = 1; s <= src.getSize(); s++) {
            ImageProcessor ip = src.getProcessor(s);
            Rectangle oldRoi = ip.getRoi();
            ip.setRoi(0, 0, width, height);
            ImageProcessor cropped = ip.crop();
            if (oldRoi != null) {
                ip.setRoi(oldRoi);
            } else {
                ip.resetRoi();
            }
            copy.addSlice(src.getSliceLabel(s), cropped);
        }
        ImagePlus out = new ImagePlus(source.getTitle() + "-" + label, copy);
        if (source.getCalibration() != null) out.setCalibration(source.getCalibration().copy());
        int c = source.getNChannels();
        int z = source.getNSlices();
        int t = source.getNFrames();
        if (c * z * t == copy.getSize()) out.setDimensions(c, z, t);
        return out;
    }

    private static ImagePlus combineNative(Combiner combiner, List<ImagePlus> inputs)
            throws DagRejectedException {
        ImagePlus first = inputs.get(0);
        int width = first.getWidth();
        int height = first.getHeight();
        int slices = first.getStackSize();
        for (ImagePlus input : inputs) {
            if (input.getWidth() != width || input.getHeight() != height
                    || input.getStackSize() != slices) {
                throw new DagRejectedException("Combiner input dimensions differ: " + combiner.id);
            }
        }

        ImageStack out = new ImageStack(width, height);
        for (int s = 1; s <= slices; s++) {
            FloatProcessor fp = new FloatProcessor(width, height);
            int n = width * height;
            for (int i = 0; i < n; i++) {
                fp.setf(i, combinePixel(combiner.op, inputs, s, i));
            }
            out.addSlice(first.getStack().getSliceLabel(s), fp);
        }
        ImagePlus result = new ImagePlus(combiner.id, out);
        if (first.getCalibration() != null) result.setCalibration(first.getCalibration().copy());
        int c = first.getNChannels();
        int z = first.getNSlices();
        int t = first.getNFrames();
        if (c * z * t == out.getSize()) result.setDimensions(c, z, t);
        return result;
    }

    private static float combinePixel(CombinerOp op, List<ImagePlus> inputs, int slice, int index)
            throws DagRejectedException {
        float value = inputs.get(0).getStack().getProcessor(slice).getf(index);
        switch (op) {
            case AND:
            case MIN:
                for (int i = 1; i < inputs.size(); i++) {
                    value = Math.min(value, inputs.get(i).getStack().getProcessor(slice).getf(index));
                }
                return value;
            case OR:
            case MAX:
                for (int i = 1; i < inputs.size(); i++) {
                    value = Math.max(value, inputs.get(i).getStack().getProcessor(slice).getf(index));
                }
                return value;
            case ADD:
                for (int i = 1; i < inputs.size(); i++) {
                    value += inputs.get(i).getStack().getProcessor(slice).getf(index);
                }
                return value;
            case SUBTRACT:
                for (int i = 1; i < inputs.size(); i++) {
                    value -= inputs.get(i).getStack().getProcessor(slice).getf(index);
                }
                return value;
            case DIFFERENCE:
                for (int i = 1; i < inputs.size(); i++) {
                    value = Math.abs(value - inputs.get(i).getStack().getProcessor(slice).getf(index));
                }
                return value;
            case AVG:
                for (int i = 1; i < inputs.size(); i++) {
                    value += inputs.get(i).getStack().getProcessor(slice).getf(index);
                }
                return value / inputs.size();
            default:
                throw new DagRejectedException("Unsupported combiner op: " + op);
        }
    }

    /**
     * Whole-stack ops are dispatched at the {@link ImagePlus} level — they can
     * touch slice neighbours (3D filters) or change bit depth (CONVERT_*) and
     * therefore cannot be run per-slice in parallel.
     */
    private static boolean isWholeStackOp(FilterMacroParser.OpType t) {
        switch (t) {
            case GAUSSIAN_BLUR_3D:
            case MEDIAN_3D:
            case MINIMUM_3D:
            case CONVERT_8BIT:
            case CONVERT_16BIT:
            case CONVERT_32BIT:
                return true;
            default:
                return false;
        }
    }

    /**
     * Applies a single parsed operation to all slices in the stack.
     * Whole-stack ops run once per imp; everything else parallelises across slices.
     * Thread-safe: uses only ImageProcessor-level APIs with per-call filter instances.
     */
    private static void executeOpOnStack(ImagePlus imp, FilterMacroParser.Op op) {
        if (isWholeStackOp(op.type)) {
            executeWholeStackOp(imp, op);
            return;
        }

        ImageStack stack = imp.getStack();
        int nSlices = stack.getSize();

        if (nSlices < SLICE_PARALLEL_THRESHOLD || ParallelContext.isNested()) {
            for (int s = 1; s <= nSlices; s++) {
                executeOpOnSlice(stack.getProcessor(s), op);
            }
            return;
        }

        int nThreads = Math.min(nSlices, Runtime.getRuntime().availableProcessors());
        ExecutorService slicePool = Executors.newFixedThreadPool(nThreads);
        List<Future<?>> futures = new ArrayList<Future<?>>();
        // Inherit the parent's ParallelContext so any nested FilterExecutor
        // calls on the slice threads serialise rather than spawning more pools.
        // We're at the top of the parallel chain here, so enter the flag
        // unconditionally — child code that recurses will see isNested() == true.
        for (int s = 1; s <= nSlices; s++) {
            final ImageProcessor ip = stack.getProcessor(s);
            futures.add(slicePool.submit(new Runnable() {
                @Override
                public void run() {
                    ParallelContext.enterParallel();
                    try {
                        executeOpOnSlice(ip, op);
                    } finally {
                        ParallelContext.exitParallel();
                    }
                }
            }));
        }
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                Throwable cause = e.getCause() == null ? e : e.getCause();
                throw new RuntimeException("Filter operation " + op.type
                        + " failed while processing image '" + imp.getTitle()
                        + "' across " + nSlices + " slice(s)", cause);
            }
        }
        slicePool.shutdown();
    }

    /**
     * Applies a single parsed operation to one slice's ImageProcessor.
     * Thread-safe: creates new filter instances per call (no shared mutable state).
     */
    private static void executeOpOnSlice(ImageProcessor ip, FilterMacroParser.Op op) {
        switch (op.type) {
            case GAUSSIAN_BLUR: {
                double sigma = op.getParam("sigma");
                if (Double.isNaN(sigma)) sigma = 2.0;
                new GaussianBlur().blurGaussian(ip, sigma);
                break;
            }
            case SUBTRACT_BACKGROUND: {
                double rolling = op.getParam("rolling");
                if (Double.isNaN(rolling)) rolling = 20.0;
                // createBackground=false, lightBackground=false, useParaboloid=false,
                // doPresmooth=true, correctCorners=true
                new BackgroundSubtracter().rollingBallBackground(
                        ip, rolling, false, false, false, true, true);
                break;
            }
            case MEDIAN: {
                double radius = op.getParam("radius");
                if (Double.isNaN(radius)) radius = 2.0;
                new RankFilters().rank(ip, radius, RankFilters.MEDIAN);
                break;
            }
            case MEAN: {
                double radius = op.getParam("radius");
                if (Double.isNaN(radius)) radius = 2.0;
                new RankFilters().rank(ip, radius, RankFilters.MEAN);
                break;
            }
            case MINIMUM: {
                double radius = op.getParam("radius");
                if (Double.isNaN(radius)) radius = 2.0;
                new RankFilters().rank(ip, radius, RankFilters.MIN);
                break;
            }
            case MAXIMUM: {
                double radius = op.getParam("radius");
                if (Double.isNaN(radius)) radius = 2.0;
                new RankFilters().rank(ip, radius, RankFilters.MAX);
                break;
            }
            case VARIANCE: {
                double radius = op.getParam("radius");
                if (Double.isNaN(radius)) radius = 2.0;
                new RankFilters().rank(ip, radius, RankFilters.VARIANCE);
                break;
            }
            case UNSHARP_MASK: {
                double radius = op.getParam("radius");
                if (Double.isNaN(radius)) radius = 10.0;
                double weight = op.getParam("mask");
                if (Double.isNaN(weight)) weight = 0.60;
                ImageProcessor blurred = ip.duplicate();
                new GaussianBlur().blurGaussian(blurred, radius);
                float[] origPixels = ipToFloatArray(ip);
                float[] blurredPixels = ipToFloatArray(blurred);
                int size = origPixels.length;
                for (int i = 0; i < size; i++) {
                    float v = origPixels[i] + (float)(weight * (origPixels[i] - blurredPixels[i]));
                    origPixels[i] = Math.max(0, v);
                }
                setFromFloatArray(ip, origPixels);
                break;
            }
            case DILATE: {
                // IJ macro `run("Dilate")` calls Binary plugin with count=1
                // (default iterations) and background = 255 - foreground,
                // where foreground depends on Prefs.blackBackground. The bare
                // ImageProcessor.dilate() shortcut uses count=8 — a far weaker
                // dilation — so we mirror the macro path explicitly via
                // ByteProcessor's two-arg dilate.
                if (ip instanceof ByteProcessor) {
                    int bg = Prefs.blackBackground ? 0 : 255;
                    ((ByteProcessor) ip).dilate(1, bg);
                } else {
                    ip.dilate();
                }
                break;
            }
            case ERODE: {
                if (ip instanceof ByteProcessor) {
                    int bg = Prefs.blackBackground ? 0 : 255;
                    ((ByteProcessor) ip).erode(1, bg);
                } else {
                    ip.erode();
                }
                break;
            }
            case OPEN: {
                // Process > Binary > Open = erode then dilate
                if (ip instanceof ByteProcessor) {
                    int bg = Prefs.blackBackground ? 0 : 255;
                    ((ByteProcessor) ip).erode(1, bg);
                    ((ByteProcessor) ip).dilate(1, bg);
                } else {
                    ip.erode();
                    ip.dilate();
                }
                break;
            }
            case CLOSE_: {
                // Process > Binary > Close- = dilate then erode
                if (ip instanceof ByteProcessor) {
                    int bg = Prefs.blackBackground ? 0 : 255;
                    ((ByteProcessor) ip).dilate(1, bg);
                    ((ByteProcessor) ip).erode(1, bg);
                } else {
                    ip.dilate();
                    ip.erode();
                }
                break;
            }
            case FILL_HOLES: {
                fillHoles(ip);
                break;
            }
            case SKELETONIZE: {
                if (ip instanceof ByteProcessor) {
                    int fg = Prefs.blackBackground ? 255 : 0;
                    new BinaryProcessor((ByteProcessor) ip).skeletonize(fg);
                }
                break;
            }
            case INVERT: {
                ip.invert();
                break;
            }
            case ADD: {
                double v = op.getParam("value");
                if (!Double.isNaN(v)) ip.add(v);
                break;
            }
            case SUBTRACT: {
                double v = op.getParam("value");
                if (!Double.isNaN(v)) ip.subtract(v);
                break;
            }
            case MULTIPLY: {
                double v = op.getParam("value");
                if (!Double.isNaN(v)) ip.multiply(v);
                break;
            }
            case DIVIDE: {
                double v = op.getParam("value");
                if (!Double.isNaN(v) && v != 0.0) {
                    // ip.multiply(1/v) drops 32-bit precision in some IJ versions —
                    // operate per-pixel through the ip's getf/setf for parity.
                    int n = ip.getWidth() * ip.getHeight();
                    for (int i = 0; i < n; i++) {
                        ip.setf(i, (float)(ip.getf(i) / v));
                    }
                }
                break;
            }
            case AUTO_LOCAL_THRESHOLD: {
                applyAutoLocalThreshold(ip, op);
                break;
            }
            case ENHANCE_CONTRAST: {
                applyEnhanceContrast(ip, op);
                break;
            }
            default:
                // Whole-stack ops should not reach this method; UNKNOWN never reaches
                // here either (caller falls back). Be defensive: silently skip.
                break;
        }
    }

    /**
     * Whole-stack ops (3D filters, bit-depth conversion) operate on the entire
     * {@link ImagePlus} in one call. They are not parallelised across slices —
     * the underlying IJ APIs handle the stack coherently.
     */
    private static void executeWholeStackOp(ImagePlus imp, FilterMacroParser.Op op) {
        switch (op.type) {
            case GAUSSIAN_BLUR_3D: {
                double sx = op.getParam("x");
                double sy = op.getParam("y");
                double sz = op.getParam("z");
                if (Double.isNaN(sx)) sx = 2.0;
                if (Double.isNaN(sy)) sy = sx;
                if (Double.isNaN(sz)) sz = 1.0;
                GaussianBlur3D.blur(imp, sx, sy, sz);
                break;
            }
            case MEDIAN_3D: {
                applyFilters3D(imp, op, ij.plugin.Filters3D.MEDIAN);
                break;
            }
            case MINIMUM_3D: {
                applyFilters3D(imp, op, ij.plugin.Filters3D.MIN);
                break;
            }
            case CONVERT_8BIT: {
                if (imp.getBitDepth() != 8) {
                    new ImageConverter(imp).convertToGray8();
                }
                break;
            }
            case CONVERT_16BIT: {
                if (imp.getBitDepth() != 16) {
                    new ImageConverter(imp).convertToGray16();
                }
                break;
            }
            case CONVERT_32BIT: {
                if (imp.getBitDepth() != 32) {
                    new ImageConverter(imp).convertToGray32();
                }
                break;
            }
            default:
                break;
        }
    }

    private static void applyFilters3D(ImagePlus imp, FilterMacroParser.Op op, int filter) {
        double dx = op.getParam("x");
        double dy = op.getParam("y");
        double dz = op.getParam("z");
        if (Double.isNaN(dx)) dx = 2.0;
        if (Double.isNaN(dy)) dy = dx;
        if (Double.isNaN(dz)) dz = 1.0;
        ImageStack out = ij.plugin.Filters3D.filter(
                imp.getStack(), filter, (float) dx, (float) dy, (float) dz);
        if (out != null) imp.setStack(out);
    }

    /**
     * Auto Local Threshold (Fiji plugin). Uses reflection so the project compiles
     * even when {@code fiji.threshold.Auto_Local_Threshold} is not on the build
     * classpath; at Fiji runtime it is always present.
     */
    private static void applyAutoLocalThreshold(ImageProcessor ip, FilterMacroParser.Op op) {
        if (!(ip instanceof ByteProcessor)) {
            // Auto Local Threshold runs on 8-bit images. Skip silently for other
            // bit depths — the caller is expected to convert first (the bundled
            // Puncta Resolve macro does run("8-bit") immediately before).
            return;
        }
        String method = op.getStringParam("method");
        if (method == null) method = "Bernsen";
        double radius = op.getParam("radius");
        if (Double.isNaN(radius)) radius = 15.0;
        double p1 = op.getParam("parameter_1");
        if (Double.isNaN(p1)) p1 = 0.0;
        double p2 = op.getParam("parameter_2");
        if (Double.isNaN(p2)) p2 = 0.0;
        boolean white = op.hasFlag("white");

        try {
            Class<?> cls = Class.forName("fiji.threshold.Auto_Local_Threshold");
            Object inst = cls.getDeclaredConstructor().newInstance();
            // Public signature in fiji.threshold.Auto_Local_Threshold:
            //   Object[] exec(ImagePlus imp, String method, int radius,
            //                 double par1, double par2, boolean doIwhite)
            ImagePlus tmp = new ImagePlus("alt-tmp", ip);
            cls.getMethod("exec",
                    ImagePlus.class, String.class, int.class,
                    double.class, double.class, boolean.class)
                    .invoke(inst, tmp, method, (int) radius, p1, p2, white);
        } catch (ClassNotFoundException notFound) {
            // Plugin not available — leave the slice unchanged. This only happens
            // outside Fiji (unit tests without the auto-local-threshold jar).
        } catch (Exception e) {
            throw new RuntimeException("Auto Local Threshold native execution failed", e);
        }
    }

    /**
     * Histogram normalisation matching {@code run("Enhance Contrast...", "saturated=N normalize ...")}.
     * Uses {@link ContrastEnhancer#stretchHistogram(ImageProcessor, double)} with
     * the {@code normalize} field set when present in the args.
     */
    private static void applyEnhanceContrast(ImageProcessor ip, FilterMacroParser.Op op) {
        if (ip instanceof ColorProcessor) return;
        double saturated = op.getParam("saturated");
        if (Double.isNaN(saturated)) saturated = 0.35;
        boolean normalize = op.hasFlag("normalize");
        boolean equalize = op.hasFlag("equalize");

        ContrastEnhancer ce = new ContrastEnhancer();
        // ContrastEnhancer fields differ in visibility across IJ versions —
        // reflectively assign the ones we recognise so we do not depend on
        // a particular accessor surface.
        setFieldIfExists(ce, "normalize", normalize);
        setFieldIfExists(ce, "equalize",  equalize);
        setFieldIfExists(ce, "useStackHistogram", false);
        setFieldIfExists(ce, "processStack", false);
        ce.stretchHistogram(ip, saturated);
    }

    private static void setFieldIfExists(Object target, String name, boolean value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.setBoolean(target, value);
        } catch (NoSuchFieldException ignored) {
            // Field not present in this IJ version — caller's default applies.
        } catch (Exception e) {
            // Reflection failure is non-fatal; native fallback uses ImageJ defaults.
        }
    }

    /**
     * Process > Binary > Fill Holes. Runs on 8-bit only; respects
     * {@link Prefs#blackBackground} for foreground/background colour.
     */
    private static void fillHoles(ImageProcessor ip) {
        if (!(ip instanceof ByteProcessor)) return;
        int width = ip.getWidth();
        int height = ip.getHeight();
        byte[] pixels = (byte[]) ip.getPixels();
        int n = width * height;

        int foreground = Prefs.blackBackground ? 255 : 0;
        int background = 255 - foreground;

        boolean[] reached = new boolean[n];
        Deque<Integer> queue = new ArrayDeque<Integer>();

        for (int x = 0; x < width; x++) {
            int top = x;
            int bot = (height - 1) * width + x;
            if ((pixels[top] & 0xff) == background && !reached[top]) {
                reached[top] = true; queue.add(top);
            }
            if ((pixels[bot] & 0xff) == background && !reached[bot]) {
                reached[bot] = true; queue.add(bot);
            }
        }
        for (int y = 0; y < height; y++) {
            int left = y * width;
            int right = y * width + width - 1;
            if ((pixels[left] & 0xff) == background && !reached[left]) {
                reached[left] = true; queue.add(left);
            }
            if ((pixels[right] & 0xff) == background && !reached[right]) {
                reached[right] = true; queue.add(right);
            }
        }

        while (!queue.isEmpty()) {
            int idx = queue.poll();
            int y = idx / width;
            int x = idx % width;
            if (x > 0)            tryEnqueue(pixels, reached, queue, idx - 1, background);
            if (x < width - 1)    tryEnqueue(pixels, reached, queue, idx + 1, background);
            if (y > 0)            tryEnqueue(pixels, reached, queue, idx - width, background);
            if (y < height - 1)   tryEnqueue(pixels, reached, queue, idx + width, background);
        }

        for (int i = 0; i < n; i++) {
            if (!reached[i] && (pixels[i] & 0xff) == background) {
                pixels[i] = (byte) foreground;
            }
        }
    }

    private static void tryEnqueue(byte[] pixels, boolean[] reached,
                                   Deque<Integer> queue, int idx, int background) {
        if (reached[idx]) return;
        if ((pixels[idx] & 0xff) != background) return;
        reached[idx] = true;
        queue.add(idx);
    }

    private static float[] ipToFloatArray(ImageProcessor ip) {
        int w = ip.getWidth();
        int h = ip.getHeight();
        float[] result = new float[w * h];
        for (int i = 0; i < result.length; i++) {
            result[i] = ip.getf(i);
        }
        return result;
    }

    private static void setFromFloatArray(ImageProcessor ip, float[] values) {
        for (int i = 0; i < values.length; i++) {
            ip.setf(i, values[i]);
        }
    }

    /**
     * Closes the image window safely, removing it from WindowManager.
     * Pixel data is preserved — ImagePlus.close() does not call flush().
     * Safe to call when the window is null (no-op).
     */
    private static void closeWindowSafely(ImagePlus imp) {
        if (imp != null && imp.getWindow() != null) {
            imp.changes = false;
            imp.hide();
        }
    }
}
