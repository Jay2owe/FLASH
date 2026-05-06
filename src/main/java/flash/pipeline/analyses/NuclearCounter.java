package flash.pipeline.analyses;

import flash.pipeline.cli.CLIConfig;
import flash.pipeline.deconv.DeconvolvedInputResolver;
import flash.pipeline.image.FilterExecutor;
import flash.pipeline.intelligence.JunkFileFilter;
import flash.pipeline.runtime.DependencyId;
import flash.pipeline.runtime.FeatureDependencyGate;
import flash.pipeline.ui.PipelineDialog;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.io.Opener;
import ij.plugin.ChannelSplitter;
import ij.plugin.ZProjector;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Migration of nuclearCounter() (partial but functional).
 *
 * Opens all .tif/.tiff in the directory, uses channel 1 as nuclear marker,
 * performs max projection + filtering, then runs the "Nucleus Counter" command.
 */
public class NuclearCounter implements Analysis {

    private boolean headless = false;
    private boolean suppressDialogs = false;
    private int parallelThreads = 1;
    private boolean useDeconvolvedInput = true;
    private CLIConfig cliConfig = null;

    @Override
    public void setHeadless(boolean headless) {
        this.headless = headless;
    }

    @Override
    public void setSuppressDialogs(boolean suppress) {
        this.suppressDialogs = suppress;
    }

    @Override
    public void setParallelThreads(int threads) {
        this.parallelThreads = Math.max(1, threads);
    }

    @Override
    public void setCliConfig(CLIConfig config) {
        this.cliConfig = config;
        if (config != null) {
            this.useDeconvolvedInput = config.isNuclearCounterUseDeconv();
        }
    }

    @Override
    public void execute(String directory) {
        if (!FeatureDependencyGate.gate(DependencyId.NUCLEUS_COUNTER, "Nucleus Counter")) {
            return;
        }

        List<File> images = listImageFiles(new File(directory));
        if (images.isEmpty()) {
            IJ.log("Nuclear Counter: No .tif/.tiff images found in: " + directory);
            if (!headless && !suppressDialogs) {
                IJ.showMessage("Nuclear Counter", "No .tif/.tiff images found in:\n" + directory);
            }
            return;
        }

        if (!headless && !suppressDialogs) {
            PipelineDialog dialog = new PipelineDialog("Nuclear Counter", PipelineDialog.Phase.ANALYSE);
            dialog.addHeader("Input");
            dialog.addToggle("Use deconvolved stacks if available", useDeconvolvedInput);
            if (!dialog.showDialog()) {
                return;
            }
            useDeconvolvedInput = dialog.getNextBoolean();
        }

        IJ.log("==========================================================");
        IJ.log("NUCLEAR COUNTER");
        IJ.log("==========================================================");
        IJ.log("Directory: " + directory);
        IJ.log("Images found: " + images.size());
        if (parallelThreads > 1) {
            IJ.log("Parallel mode: " + parallelThreads + " threads");
        }

        long startTime = System.currentTimeMillis();

        if (parallelThreads > 1) {
            processImagesParallel(images, startTime);
        } else {
            processImagesSequential(images, startTime);
        }

        long totalTime = System.currentTimeMillis() - startTime;
        IJ.log("__________________________________________________________");
        IJ.log("Nuclear Counter complete. Total time: " + formatDuration(totalTime));
        if (!suppressDialogs) IJ.showMessage("Nuclear Counter", "Finished.");
    }

    // -- Sequential processing (original path) --

    private void processImagesSequential(List<File> images, long startTime) {
        for (int i = 0; i < images.size(); i++) {
            File f = images.get(i);
            IJ.log("__________________________________________________________");
            IJ.log("Image " + (i + 1) + "/" + images.size() + ": " + f.getName());

            if (i > 0) {
                long elapsed = System.currentTimeMillis() - startTime;
                long avgPerImage = elapsed / i;
                long remainingMs = avgPerImage * (images.size() - i);
                IJ.log("  Estimated time to completion: " + formatDuration(remainingMs));
            }

            File inputFile = DeconvolvedInputResolver.resolveInput(f, useDeconvolvedInput);
            ImagePlus imp = new Opener().openImage(inputFile.getAbsolutePath());
            if (imp == null) {
                IJ.log("  Could not open: " + inputFile.getAbsolutePath());
                continue;
            }
            imp.show();

            try {
                runOnFirstChannel(imp);
            } finally {
                closeAllNoPrompt();
            }
        }
    }

    // -- Parallel processing --

    private void processImagesParallel(final List<File> images, final long startTime) {
        final int total = images.size();
        final AtomicInteger completed = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(parallelThreads);
        List<Future<?>> futures = new ArrayList<Future<?>>();

        for (int i = 0; i < total; i++) {
            final int idx = i;
            final File f = images.get(i);

            futures.add(pool.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        IJ.log("[" + (idx + 1) + "/" + total + "] Processing: " + f.getName());

                        File inputFile = DeconvolvedInputResolver.resolveInput(f, useDeconvolvedInput);
                        ImagePlus imp = new Opener().openImage(inputFile.getAbsolutePath());
                        if (imp == null) {
                            IJ.log("[" + (idx + 1) + "/" + total + "] Could not open: " + inputFile.getAbsolutePath());
                            return;
                        }

                        try {
                            runOnFirstChannelThreadSafe(imp);
                        } finally {
                            imp.changes = false;
                            imp.close();
                        }

                        int done = completed.incrementAndGet();
                        long elapsed = System.currentTimeMillis() - startTime;
                        long avgPerImage = elapsed / done;
                        long remainingMs = avgPerImage * (total - done);
                        IJ.log("[" + (idx + 1) + "/" + total + "] Complete (" + done + "/" + total
                                + " done, ~" + formatDuration(remainingMs) + " remaining)");
                    } catch (Exception e) {
                        IJ.log("[" + (idx + 1) + "/" + total + "] ERROR: " + e.getMessage());
                    }
                }
            }));
        }

        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception e) {
                IJ.log("Parallel processing error: " + e.getMessage());
            }
        }
        pool.shutdown();
    }

    /**
     * Original single-image processing path (uses IJ.run for Nucleus Counter which
     * requires the GUI). Used for sequential mode.
     */
    private void runOnFirstChannel(ImagePlus imp) {
        ImagePlus[] channels = ChannelSplitter.split(imp);
        if (channels == null || channels.length == 0) {
            IJ.error("No channels found in image: " + imp.getTitle());
            return;
        }

        ImagePlus c1 = channels[0];
        c1.setTitle("C1-" + imp.getTitle());
        c1.show();

        // Direct API Z-projection (no WindowManager dependency)
        ZProjector zp = new ZProjector(c1);
        zp.setMethod(ZProjector.MAX_METHOD);
        zp.doProjection();
        ImagePlus max = zp.getProjection();
        if (max == null) {
            IJ.error("Z Project failed for: " + c1.getTitle());
            return;
        }
        max.setTitle("MAX_" + c1.getTitle());
        max.show();

        // Thread-safe filtering via FilterExecutor
        String filterMacro = "run(\"Gaussian Blur...\", \"sigma=2\");\n"
                + "run(\"Subtract Background...\", \"rolling=20\");\n"
                + "run(\"Median...\", \"radius=2\");";
        FilterExecutor.runThreadSafe(max, filterMacro);
        IJ.log("  Filter applied (Gaussian sigma=2, SubtractBG rolling=20, Median r=2)");

        // Nucleus Counter plugin requires GUI interaction
        IJ.run(max, "Nucleus Counter",
                "smallest=50 largest=5000 threshold=Current smooth=None subtract watershed show");
    }

    /**
     * Thread-safe processing path for parallel mode. Performs Z-projection and
     * filtering using direct API calls. Note: Nucleus Counter still requires
     * IJ.run as it is an external plugin with no direct API.
     */
    private void runOnFirstChannelThreadSafe(ImagePlus imp) {
        ImagePlus[] channels = ChannelSplitter.split(imp);
        if (channels == null || channels.length == 0) {
            IJ.log("  No channels found in image: " + imp.getTitle());
            return;
        }

        ImagePlus c1 = channels[0];
        c1.setTitle("C1-" + imp.getTitle());

        // Direct API Z-projection
        ZProjector zp = new ZProjector(c1);
        zp.setMethod(ZProjector.MAX_METHOD);
        zp.doProjection();
        ImagePlus max = zp.getProjection();
        if (max == null) {
            IJ.log("  Z Project failed for: " + c1.getTitle());
            return;
        }
        max.setTitle("MAX_" + c1.getTitle());

        // Thread-safe filtering
        String filterMacro = "run(\"Gaussian Blur...\", \"sigma=2\");\n"
                + "run(\"Subtract Background...\", \"rolling=20\");\n"
                + "run(\"Median...\", \"radius=2\");";
        FilterExecutor.runThreadSafe(max, filterMacro);
        IJ.log("  Filter applied (Gaussian sigma=2, SubtractBG rolling=20, Median r=2)");

        // Nucleus Counter — must use IJ.run (external plugin, no direct API)
        // Synchronized to avoid concurrent GUI access from the plugin
        synchronized (NuclearCounter.class) {
            max.show();
            IJ.run(max, "Nucleus Counter",
                    "smallest=50 largest=5000 threshold=Current smooth=None subtract watershed show");
            max.changes = false;
            max.close();
        }

        // Clean up channel images
        c1.changes = false;
        c1.close();
    }

    private List<File> listImageFiles(File dir) {
        List<File> out = new ArrayList<File>();
        if (dir == null || !dir.isDirectory()) return out;

        File[] files = JunkFileFilter.listCleanFiles(dir);

        for (File f : files) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (n.endsWith(".tif") || n.endsWith(".tiff")) out.add(f);
        }
        return out;
    }

    private void closeAllNoPrompt() {
        int[] ids = WindowManager.getIDList();
        if (ids != null) {
            for (int id : ids) {
                ImagePlus imp = WindowManager.getImage(id);
                if (imp != null) imp.changes = false;
            }
        }
        IJ.run("Close All");
    }

    private static String formatDuration(long ms) {
        long seconds = ms / 1000;
        if (seconds < 60) return seconds + " Seconds";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + " Minutes";
        long hours = minutes / 60;
        return hours + " Hours";
    }
}
