package flash.pipeline.cellpose;

import flash.pipeline.image.GpuConcurrency;
import flash.pipeline.segmentation.catalog.ModelCatalog;
import flash.pipeline.segmentation.catalog.ModelCatalogIO;
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
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public final class Cellpose3DRunner {
    public static final String CELLPROB_IMAGE_PROPERTY = "flash.cellpose.cellprobImage";
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
        return run(input, companionInput, model, diameter, flowThreshold, cellprobThreshold,
                useGpu, channelName, null);
    }

    public static ImagePlus run(ImagePlus input,
                                ImagePlus companionInput,
                                String model,
                                double diameter,
                                double flowThreshold,
                                double cellprobThreshold,
                                boolean useGpu,
                                String channelName,
                                File projectRoot) {
        return run(input, companionInput, model, diameter, flowThreshold,
                cellprobThreshold, useGpu, channelName, projectRoot, false);
    }

    public static ImagePlus run(ImagePlus input,
                                ImagePlus companionInput,
                                String model,
                                double diameter,
                                double flowThreshold,
                                double cellprobThreshold,
                                boolean useGpu,
                                String channelName,
                                File projectRoot,
                                boolean dumpCellprob) {
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
                        diameter, flowThreshold, cellprobThreshold, useGpu, channelName,
                        projectRoot, dumpCellprob);
            } finally {
                GpuConcurrency.gpuSemaphore().release();
            }

            ImagePlus labelImage = readMaskImage(expectedMaskPath(tempDir), input, channelName);
            if (labelImage != null && dumpCellprob) {
                ImagePlus cellprobImage = readCellprobImage(expectedCellprobPath(tempDir));
                if (cellprobImage != null) {
                    labelImage.setProperty(CELLPROB_IMAGE_PROPERTY, cellprobImage);
                }
            }
            return labelImage;
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

    static List<String> buildCellposeCommand(String pythonPath,
                                             Path inputStackPath,
                                             Path outputDir,
                                             String model,
                                             ModelCatalog catalog,
                                             ImagePlus input,
                                             double diameter,
                                             double flowThreshold,
                                             double cellprobThreshold,
                                             boolean useGpu) {
        return buildCellposeCommand(
                pythonPath, inputStackPath, outputDir, model, catalog, input,
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
                                           String channelName,
                                           File projectRoot,
                                           boolean dumpCellprob) throws Exception {
        List<String> command = buildCellposeCommand(
                pythonPath, inputStackPath, outputDir, model,
                readCatalog(projectRoot), input, hasSecondChannel, diameter,
                flowThreshold, cellprobThreshold, useGpu, dumpCellprob);

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
        return buildCellposeCommand(pythonPath, inputStackPath, outputDir, model,
                readCatalog(null), input, hasSecondChannel, diameter,
                flowThreshold, cellprobThreshold, useGpu, false);
    }

    static List<String> buildCellposeCommand(String pythonPath,
                                             Path inputStackPath,
                                             Path outputDir,
                                             String model,
                                             ModelCatalog catalog,
                                             ImagePlus input,
                                             boolean hasSecondChannel,
                                             double diameter,
                                             double flowThreshold,
                                             double cellprobThreshold,
                                             boolean useGpu) {
        return buildCellposeCommand(pythonPath, inputStackPath, outputDir, model,
                catalog, input, hasSecondChannel, diameter, flowThreshold,
                cellprobThreshold, useGpu, false);
    }

    static List<String> buildCellposeCommand(String pythonPath,
                                             Path inputStackPath,
                                             Path outputDir,
                                             String model,
                                             ModelCatalog catalog,
                                             ImagePlus input,
                                             boolean hasSecondChannel,
                                             double diameter,
                                             double flowThreshold,
                                             double cellprobThreshold,
                                             boolean useGpu,
                                             boolean dumpCellprob) {
        String pretrainedModelArgument = resolvePretrainedModelArgument(model, catalog);
        List<String> command = new ArrayList<String>();
        command.add(CellposeRuntime.normalizeExecutablePath(pythonPath));
        command.add("-m");
        command.add("cellpose");
        command.add("--image_path");
        command.add(inputStackPath.toString());
        command.add("--savedir");
        command.add(outputDir.toString());
        command.add("--pretrained_model");
        command.add(pretrainedModelArgument);
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
        if (dumpCellprob) {
            command.add("--save_flows");
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

    public static String resolvePretrainedModelArgument(String model, ModelCatalog catalog) {
        String modelKey = CellposeModelResolver.normalizeModelKey(model);
        Optional<CellposeModelResolver.Resolved> resolved =
                new CellposeModelResolver().resolve(modelKey, catalog);
        if (!resolved.isPresent()) {
            throw new IllegalArgumentException("Cellpose model '" + displayModelKey(modelKey, model)
                    + "' not found in catalog. Please import it via Manage Models or select a different model.");
        }
        CellposeModelResolver.Resolved value = resolved.get();
        if (value.built_in) {
            return value.pretrainedName;
        }
        if (value.absolutePath == null || !Files.isRegularFile(Paths.get(value.absolutePath))) {
            throw new IllegalStateException("Cellpose model file for '"
                    + displayModelKey(modelKey, model) + "' does not exist: "
                    + value.absolutePath
                    + ". Please import it via Manage Models or select a different model.");
        }
        return value.absolutePath;
    }

    private static String displayModelKey(String modelKey, String rawModel) {
        if (modelKey != null && !modelKey.trim().isEmpty()) return modelKey;
        return rawModel == null || rawModel.trim().isEmpty() ? "<missing>" : rawModel.trim();
    }

    private static ModelCatalog readCatalog(File projectRoot) {
        Path root = projectRoot == null
                ? Paths.get(System.getProperty("user.dir", "."))
                : projectRoot.toPath();
        return ModelCatalogIO.read(root.toAbsolutePath().normalize());
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

    public static ImagePlus readCellprobImage(Path cellprobPath) {
        try {
            if (cellprobPath == null || !Files.isRegularFile(cellprobPath)) {
                IJ.log("WARNING: Cellpose produced no cell probability file at " + cellprobPath);
                return null;
            }

            ImagePlus cellprobImage = IJ.openImage(cellprobPath.toString());
            if (cellprobImage == null) {
                throw new IllegalStateException("Could not open Cellpose cell probability image: "
                        + cellprobPath);
            }

            if (cellprobImage.getBitDepth() != 32) {
                ImageStack oldStack = cellprobImage.getStack();
                ImageStack newStack = new ImageStack(oldStack.getWidth(), oldStack.getHeight());
                for (int s = 1; s <= oldStack.getSize(); s++) {
                    ImageProcessor ip = oldStack.getProcessor(s);
                    newStack.addSlice(ip.convertToFloatProcessor());
                }
                cellprobImage.setStack(newStack);
            }

            cellprobImage.setTitle("Cellpose Cell Probability");
            cellprobImage.setDimensions(1, Math.max(1, cellprobImage.getStackSize()), 1);
            return cellprobImage;
        } catch (Exception e) {
            IJ.log("WARNING: Failed reading Cellpose cell probability image: " + e.getMessage());
            return null;
        }
    }

    public static double[] perObjectMeanCellprob(ImagePlus labelImage,
                                                 ImagePlus cellprobImage) {
        if (labelImage == null || cellprobImage == null
                || labelImage.getStack() == null || cellprobImage.getStack() == null) {
            return new double[0];
        }
        if (labelImage.getWidth() != cellprobImage.getWidth()
                || labelImage.getHeight() != cellprobImage.getHeight()
                || labelImage.getStackSize() != cellprobImage.getStackSize()) {
            throw new IllegalArgumentException("Label and cell probability images must have matching dimensions.");
        }

        int maxLabel = countLabels(labelImage);
        double[] sums = new double[maxLabel + 1];
        long[] counts = new long[maxLabel + 1];
        for (int s = 1; s <= labelImage.getStackSize(); s++) {
            ImageProcessor labels = labelImage.getStack().getProcessor(s);
            ImageProcessor cellprob = cellprobImage.getStack().getProcessor(s);
            int width = labels.getWidth();
            int height = labels.getHeight();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    float labelValue = labels.getf(x, y);
                    if (!Float.isFinite(labelValue)) {
                        continue;
                    }
                    int label = (int) labelValue;
                    if (label <= 0 || label > maxLabel) {
                        continue;
                    }
                    float cellprobValue = cellprob.getf(x, y);
                    if (!Float.isFinite(cellprobValue)) {
                        continue;
                    }
                    sums[label] += cellprobValue;
                    counts[label]++;
                }
            }
        }

        double[] means = new double[maxLabel + 1];
        means[0] = Double.NaN;
        for (int label = 1; label <= maxLabel; label++) {
            means[label] = counts[label] == 0L
                    ? Double.NaN
                    : sums[label] / (double) counts[label];
        }
        return means;
    }

    static Path expectedMaskPath(Path outputDir) {
        return outputDir.resolve(INPUT_STACK_BASENAME + MASK_SUFFIX);
    }

    static Path expectedCellprobPath(Path outputDir) {
        return outputDir.resolve(INPUT_STACK_BASENAME + "_cellprob.tif");
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
