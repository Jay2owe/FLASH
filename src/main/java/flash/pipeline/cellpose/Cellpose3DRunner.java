package flash.pipeline.cellpose;

import flash.pipeline.image.GpuConcurrency;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.io.FileSaver;
import ij.process.ImageProcessor;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class Cellpose3DRunner {
    private static final String INPUT_STACK_BASENAME = "cellpose_input";
    private static final String MASK_SUFFIX = "_cp_masks.tif";
    private static final long CELLPOSE_TIMEOUT_SECONDS = 1800L;

    private Cellpose3DRunner() {}

    public static ImagePlus run(ImagePlus input,
                                String model,
                                double diameter,
                                double flowThreshold,
                                double cellprobThreshold,
                                boolean useGpu,
                                String channelName) {
        return run(input, null, model, diameter, flowThreshold, cellprobThreshold, useGpu, channelName);
    }

    public static ImagePlus run(ImagePlus input,
                                ImagePlus companionInput,
                                String model,
                                double diameter,
                                double flowThreshold,
                                double cellprobThreshold,
                                boolean useGpu,
                                String channelName) {
        if (input == null) {
            IJ.log("WARNING: Cellpose input image is null.");
            return null;
        }

        CellposeRuntime.Status runtime = CellposeRuntime.probeConfigured();
        if (!runtime.ready) {
            IJ.log("WARNING: " + runtime.message);
            if (!runtime.details.isEmpty()) IJ.log(runtime.details);
            return null;
        }

        Path tempDir = null;
        ImagePlus runtimeInput = null;
        try {
            int stackSize = input.getStackSize();
            if (stackSize <= 0) {
                IJ.log("WARNING: Cellpose input image has 0 slices.");
                return null;
            }

            runtimeInput = prepareRuntimeInput(input, companionInput, channelName);
            tempDir = Files.createTempDirectory("ihf-cellpose-");
            Path inputStackPath = writeInputStack(runtimeInput, tempDir);

            // GPU permit gate: shared with StarDist so two GPU inferences cannot overlap
            // on a single card by default. Stack/mask I/O stays outside — only the
            // Python subprocess consumes GPU memory.
            GpuConcurrency.gpuSemaphore().acquireUninterruptibly();
            try {
                runCellposeCommand(runtime.pythonPath, inputStackPath, tempDir, model, runtimeInput,
                        runtimeInput != null && runtimeInput.getNChannels() > 1,
                        diameter, flowThreshold, cellprobThreshold, useGpu, channelName);
            } finally {
                GpuConcurrency.gpuSemaphore().release();
            }

            return readMaskImage(expectedMaskPath(tempDir), input, channelName);
        } catch (Exception e) {
            IJ.log("WARNING: Cellpose failed: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            IJ.log(sw.toString());
            return null;
        } finally {
            if (runtimeInput != null && runtimeInput != input) {
                runtimeInput.changes = false;
                runtimeInput.close();
                runtimeInput.flush();
            }
            if (tempDir != null) {
                deleteRecursively(tempDir);
            }
        }
    }

    /**
     * Legacy overload retained for call-site compatibility after removing the
     * TrackMate linking bridge. The extra arguments are ignored.
     */
    public static ImagePlus run(ImagePlus input,
                                String model,
                                double diameter,
                                double flowThreshold,
                                double cellprobThreshold,
                                boolean useGpu,
                                String channelName,
                                double linkingMaxDistance,
                                double gapClosingMaxDistance,
                                int maxFrameGap) {
        return run(input, null, model, diameter, flowThreshold, cellprobThreshold, useGpu, channelName);
    }

    public static int countLabels(ImagePlus labelImage) {
        if (labelImage == null || labelImage.getStack() == null) return 0;
        double maxVal = 0;
        int nSlices = labelImage.getStackSize();
        for (int s = 1; s <= nSlices; s++) {
            ImageProcessor ip = labelImage.getStack().getProcessor(s);
            double sliceMax = ip.getStats().max;
            if (Double.isFinite(sliceMax) && sliceMax > maxVal) {
                maxVal = sliceMax;
            }
        }
        return maxVal > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) maxVal;
    }

    public static ImagePlus prepareRuntimeInput(ImagePlus primaryInput, ImagePlus companionInput, String channelName) {
        if (primaryInput == null || companionInput == null) return primaryInput;

        String chTag = (channelName != null && !channelName.isEmpty()) ? " [" + channelName + "]" : "";
        if (!sameSpatialShape(primaryInput, companionInput)) {
            IJ.log("WARNING: Cellpose" + chTag
                    + " companion channel dimensions do not match the primary channel. Falling back to single-channel input.");
            return primaryInput;
        }

        ImageStack primaryStack = primaryInput.getStack();
        ImageStack companionStack = companionInput.getStack();
        ImageStack mergedStack = new ImageStack(primaryInput.getWidth(), primaryInput.getHeight());
        for (int s = 1; s <= primaryInput.getStackSize(); s++) {
            mergedStack.addSlice(primaryStack.getProcessor(s).duplicate());
            mergedStack.addSlice(companionStack.getProcessor(s).duplicate());
        }

        ImagePlus merged = new ImagePlus(primaryInput.getTitle(), mergedStack);
        merged.setDimensions(2, Math.max(1, primaryInput.getStackSize()), 1);
        merged.setOpenAsHyperStack(true);
        if (primaryInput.getCalibration() != null) {
            merged.setCalibration(primaryInput.getCalibration().copy());
        }
        return merged;
    }

    private static boolean sameSpatialShape(ImagePlus first, ImagePlus second) {
        if (first == null || second == null) return false;
        return first.getWidth() == second.getWidth()
                && first.getHeight() == second.getHeight()
                && first.getStackSize() == second.getStackSize();
    }

    public static Path writeInputStack(ImagePlus input, Path tempDir) throws Exception {
        Path inputStackPath = tempDir.resolve(INPUT_STACK_BASENAME + ".tif");
        FileSaver saver = new FileSaver(input);
        boolean saved = input.getStackSize() > 1
                ? saver.saveAsTiffStack(inputStackPath.toString())
                : saver.saveAsTiff(inputStackPath.toString());
        if (!saved) {
            throw new IllegalStateException("Could not save temporary Cellpose input stack: " + inputStackPath);
        }
        return inputStackPath;
    }

    static List<String> buildCellposeCommand(String pythonPath,
                                             Path inputStackPath,
                                             Path outputDir,
                                             String model,
                                             ImagePlus input,
                                             double diameter,
                                             double flowThreshold,
                                             double cellprobThreshold,
                                             boolean useGpu) {
        return buildCellposeCommand(
                pythonPath, inputStackPath, outputDir, model, input,
                false, diameter, flowThreshold, cellprobThreshold, useGpu);
    }

    private static void runCellposeCommand(String pythonPath,
                                           Path inputStackPath,
                                           Path outputDir,
                                           String model,
                                           ImagePlus input,
                                           boolean hasSecondChannel,
                                           double diameter,
                                           double flowThreshold,
                                           double cellprobThreshold,
                                           boolean useGpu,
                                           String channelName) throws Exception {
        List<String> command = buildCellposeCommand(
                pythonPath, inputStackPath, outputDir, model, input,
                hasSecondChannel, diameter, flowThreshold, cellprobThreshold, useGpu);

        String chTag = (channelName != null && !channelName.isEmpty()) ? " [" + channelName + "]" : "";
        IJ.log("    Cellpose" + chTag + " command: " + String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        // Cap the BLAS / OpenMP thread pools inside the Cellpose Python
        // subprocess so two concurrent DL inferences (StarDist + Cellpose,
        // or two Cellpose channels) do not oversubscribe the host CPU. All
        // four variables are set because the binding one depends on which
        // BLAS backend PyTorch pulled in; the non-binding ones are ignored.
        // See docs/GPU_CONCURRENCY/PLAN_2_DL_THREAD_CAPPING.md.
        int threadsPerInference = GpuConcurrency.threadsPerInference();
        String tStr = Integer.toString(threadsPerInference);
        java.util.Map<String, String> env = pb.environment();
        env.put("OMP_NUM_THREADS", tStr);
        env.put("MKL_NUM_THREADS", tStr);
        env.put("OPENBLAS_NUM_THREADS", tStr);
        env.put("NUMEXPR_NUM_THREADS", tStr);
        IJ.log("    Cellpose" + chTag + " thread cap: " + tStr
                + " threads (OMP/MKL/OPENBLAS/NUMEXPR)");

        Process process = pb.start();
        final StringBuilder output = new StringBuilder();
        Thread outputThread = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(
                            process.getInputStream(), StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (output) {
                            output.append(line).append('\n');
                        }
                        IJ.log("    Cellpose" + chTag + " > " + line);
                    }
                } catch (Exception ignored) {
                }
            }
        }, "flash-cellpose-output");
        outputThread.setDaemon(true);
        outputThread.start();
        try {
            if (!process.waitFor(CELLPOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(2L, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
                throw new IllegalStateException("Cellpose timed out after "
                        + CELLPOSE_TIMEOUT_SECONDS + " seconds.\n" + outputText(output));
            }
        } finally {
            process.getInputStream().close();
            process.getErrorStream().close();
            process.getOutputStream().close();
            try {
                outputThread.join(2000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        int exit = process.exitValue();
        if (exit != 0) {
            throw new IllegalStateException("Cellpose exited with code " + exit + ".\n" + outputText(output));
        }
    }

    private static String outputText(StringBuilder output) {
        synchronized (output) {
            return output.toString().trim();
        }
    }

    static List<String> buildCellposeCommand(String pythonPath,
                                             Path inputStackPath,
                                             Path outputDir,
                                             String model,
                                             ImagePlus input,
                                             boolean hasSecondChannel,
                                             double diameter,
                                             double flowThreshold,
                                             double cellprobThreshold,
                                             boolean useGpu) {
        List<String> command = new ArrayList<String>();
        command.add(CellposeRuntime.normalizeExecutablePath(pythonPath));
        command.add("-m");
        command.add("cellpose");
        command.add("--image_path");
        command.add(inputStackPath.toString());
        command.add("--savedir");
        command.add(outputDir.toString());
        command.add("--pretrained_model");
        command.add(CellposeModel.fromToken(model).token());
        if (hasSecondChannel) {
            command.add("--chan");
            command.add("1");
            command.add("--chan2");
            command.add("2");
            command.add("--channel_axis");
            command.add(input != null && input.getNSlices() > 1 ? "1" : "0");
        } else {
            command.add("--chan");
            command.add("0");
        }
        command.add("--diameter");
        command.add(formatDiameterPixels(input, diameter));
        command.add("--flow_threshold");
        command.add(String.valueOf(flowThreshold));
        command.add("--cellprob_threshold");
        command.add(String.valueOf(cellprobThreshold));
        if (useGpu) {
            command.add("--use_gpu");
        }

        if (input != null && input.getNSlices() > 1) {
            command.add("--do_3D");
            command.add("--z_axis");
            command.add("0");

            Double anisotropy = computeAnisotropy(input);
            if (anisotropy != null) {
                command.add("--anisotropy");
                command.add(String.valueOf(anisotropy.doubleValue()));
            }
        }

        command.add("--save_tif");
        command.add("--no_npy");
        command.add("--verbose");
        return command;
    }

    static ImagePlus readMaskImage(Path maskPath, ImagePlus input, String channelName) {
        try {
            if (maskPath == null || !Files.isRegularFile(maskPath)) {
                String chTag = (channelName != null && !channelName.isEmpty()) ? " [" + channelName + "]" : "";
                IJ.log("WARNING: Cellpose" + chTag + " produced no mask file at " + maskPath);
                return null;
            }

            ImagePlus labelImage = IJ.openImage(maskPath.toString());
            if (labelImage == null) {
                throw new IllegalStateException("Could not open Cellpose mask: " + maskPath);
            }

            if (labelImage.getBitDepth() != 16) {
                ImageStack oldStack = labelImage.getStack();
                ImageStack newStack = new ImageStack(oldStack.getWidth(), oldStack.getHeight());
                for (int s = 1; s <= oldStack.getSize(); s++) {
                    ImageProcessor ip = oldStack.getProcessor(s);
                    newStack.addSlice(ip.convertToShort(false));
                }
                labelImage.setStack(newStack);
            }

            labelImage.setTitle("Label Image");
            labelImage.setDimensions(1, Math.max(1, labelImage.getStackSize()), 1);
            if (input != null && input.getCalibration() != null) {
                labelImage.setCalibration(input.getCalibration().copy());
            }

            if (input != null && labelImage.getStackSize() != input.getStackSize()) {
                String chTag = (channelName != null && !channelName.isEmpty()) ? " [" + channelName + "]" : "";
                IJ.log("WARNING: Cellpose" + chTag + " output slice count " + labelImage.getStackSize()
                        + " does not match input slice count " + input.getStackSize() + ".");
            }

            int nObjects = countLabels(labelImage);
            String chTag = (channelName != null && !channelName.isEmpty()) ? " [" + channelName + "]" : "";
            IJ.log("    Cellpose" + chTag + ": " + nObjects + " objects detected"
                    + " [native " + (input != null && input.getNSlices() > 1 ? "3D" : "2D") + "]");
            return labelImage;
        } catch (Exception e) {
            IJ.log("WARNING: Failed reading Cellpose mask image: " + e.getMessage());
            return null;
        }
    }

    static Path expectedMaskPath(Path outputDir) {
        return outputDir.resolve(INPUT_STACK_BASENAME + MASK_SUFFIX);
    }

    static Double computeAnisotropy(ImagePlus input) {
        if (input == null || input.getCalibration() == null) return null;
        if (!input.getCalibration().scaled()) return null;

        double pixelWidth = input.getCalibration().pixelWidth;
        double pixelHeight = input.getCalibration().pixelHeight;
        double pixelDepth = input.getCalibration().pixelDepth;
        if (pixelWidth <= 0 || pixelHeight <= 0 || pixelDepth <= 0
                || Double.isNaN(pixelWidth) || Double.isNaN(pixelHeight) || Double.isNaN(pixelDepth)
                || Double.isInfinite(pixelWidth) || Double.isInfinite(pixelHeight) || Double.isInfinite(pixelDepth)) {
            return null;
        }

        double xy = (pixelWidth + pixelHeight) / 2.0;
        if (xy <= 0) return null;
        return Double.valueOf(pixelDepth / xy);
    }

    static String formatDiameterPixels(ImagePlus input, double diameterInUnits) {
        if (diameterInUnits <= 0 || !Double.isFinite(diameterInUnits)) return "0";
        double pixelWidth = input == null || input.getCalibration() == null
                ? 1.0 : input.getCalibration().pixelWidth;
        if (pixelWidth <= 0 || Double.isNaN(pixelWidth) || Double.isInfinite(pixelWidth)) {
            pixelWidth = 1.0;
        }
        double diameterPixels = diameterInUnits / pixelWidth;
        return String.valueOf(diameterPixels);
    }

    private static void deleteRecursively(Path root) {
        if (root == null) return;
        try {
            Files.walk(root)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {}
                    });
        } catch (Exception ignored) {}
    }
}
