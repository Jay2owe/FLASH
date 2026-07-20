package flash.pipeline.cellpose;

import flash.pipeline.click.training.cellpose.CellposeLocalTrainingService;
import flash.pipeline.image.GpuConcurrency;
import flash.pipeline.segmentation.SegmentationRunFailureException;
import flash.pipeline.segmentation.catalog.ModelCatalog;
import flash.pipeline.segmentation.catalog.ModelCatalogIO;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.io.FileSaver;
import ij.measure.Calibration;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

public final class Cellpose3DRunner {
    public static final String CELLPROB_IMAGE_PROPERTY = "flash.cellpose.cellprobImage";
    private static final String INPUT_STACK_BASENAME = "cellpose_input";
    private static final String MASK_SUFFIX = "_cp_masks.tif";
    private static final long CELLPOSE_TIMEOUT_SECONDS = 1800L;
    private static final int MAX_BITSET_LABEL = 10_000_000;
    private static final int MAX_DENSE_CELLPROB_LABEL = 1_000_000;
    private static final AtomicLong PROCESS_RUN_SEQUENCE = new AtomicLong();
    /** Largest integer for which ImageJ's 32-bit float pixels preserve every ID exactly. */
    private static final int MAX_EXACT_FLOAT_LABEL = 16_777_216;

    /** Typed boundary failure for an unsupported or mismatched image geometry. */
    public static final class ImageGeometryException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        ImageGeometryException(String message) {
            super(message);
        }
    }

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
            String message = "Cellpose input image is null.";
            IJ.log("WARNING: " + message);
            IllegalArgumentException cause = new IllegalArgumentException(message);
            throw failure("Cellpose failed: " + message, cause);
        }
        try {
            requireCellposeSourceGeometry(input, "primary input");
            if (companionInput != null) {
                requireRegisteredPair(input, companionInput,
                        "primary input", "companion input", true);
            }
        } catch (ImageGeometryException e) {
            IJ.log("WARNING: Cellpose rejected input geometry: " + e.getMessage());
            throw failure("Cellpose failed: " + e.getMessage(), e);
        }

        CellposeRuntime.Status runtime = CellposeRuntime.probeConfigured();
        if (!runtime.ready) {
            IJ.log("WARNING: " + runtime.message);
            if (!runtime.details.isEmpty()) IJ.log(runtime.details);
            String message = runtime.message == null || runtime.message.trim().isEmpty()
                    ? "Cellpose runtime is not ready."
                    : runtime.message.trim();
            IllegalStateException cause = new IllegalStateException(message);
            throw failure("Cellpose failed: " + message, cause);
        }

        Path tempDir = null;
        ImagePlus runtimeInput = null;
        try {
            int stackSize = input.getStackSize();
            if (stackSize <= 0) {
                String message = "Cellpose input image has 0 slices.";
                IJ.log("WARNING: " + message);
                throw new IllegalStateException(message);
            }

            runtimeInput = prepareRuntimeInput(input, companionInput, channelName);
            tempDir = Files.createTempDirectory("ihf-cellpose-");
            Path inputStackPath = writeInputStack(runtimeInput, tempDir);

            // GPU permit gate: shared with StarDist so two GPU inferences cannot overlap
            // on a single card by default. Stack/mask I/O stays outside — only the
            // Python subprocess consumes GPU memory.
            if (dumpCellprob) {
                return runWithPersistentCellprobDump(inputStackPath, tempDir, input,
                        runtimeInput, model, diameter, flowThreshold, cellprobThreshold,
                        useGpu, channelName, projectRoot);
            }

            GpuConcurrency.gpuSemaphore().acquireUninterruptibly();
            try {
                runCellposeCommand(runtime.pythonPath, inputStackPath, tempDir, model, runtimeInput,
                        runtimeInput != null && runtimeInput.getNChannels() > 1,
                        diameter, flowThreshold, cellprobThreshold, useGpu, channelName,
                        projectRoot, false);
            } finally {
                GpuConcurrency.gpuSemaphore().release();
            }

            Path maskPath = expectedMaskPath(tempDir);
            ImagePlus labelImage = readMaskImage(maskPath, input, channelName);
            if (labelImage == null) {
                throw new IllegalStateException("Cellpose produced no readable mask image at " + maskPath);
            }
            return labelImage;
        } catch (Exception e) {
            IJ.log("WARNING: Cellpose failed for channel='" + channelName
                    + "', input='" + (input == null ? "<null>" : input.getTitle())
                    + "', model='" + model + "', diameter=" + diameter
                    + ", flowThreshold=" + flowThreshold
                    + ", cellprobThreshold=" + cellprobThreshold
                    + ", gpu=" + useGpu + ": " + exceptionSummary(e));
            throw failure("Cellpose failed: " + exceptionSummary(e), e);
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

    private static ImagePlus runWithPersistentCellprobDump(Path inputStackPath,
                                                          Path outputDir,
                                                          ImagePlus referenceInput,
                                                          ImagePlus runtimeInput,
                                                          String model,
                                                          double diameter,
                                                          double flowThreshold,
                                                          double cellprobThreshold,
                                                          boolean useGpu,
                                                          String channelName,
                                                          File projectRoot) throws Exception {
        CellposePersistentWorker worker = new CellposePersistentWorker(
                inputStackPath,
                outputDir,
                referenceInput,
                runtimeInput,
                model,
                useGpu,
                channelName,
                projectRoot);
        try {
            Future<CellposeWorkerResult> future = worker.submit(new CellposeWorkerRequest(
                    INPUT_STACK_BASENAME,
                    diameter,
                    flowThreshold,
                    cellprobThreshold,
                    true));
            CellposeWorkerResult result;
            try {
                result = future.get(CELLPOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new IllegalStateException("Cellpose timed out after "
                        + CELLPOSE_TIMEOUT_SECONDS + " seconds.", e);
            }
            if (result == null || result.hasError()) {
                throw new IllegalStateException(result == null
                        ? "Cellpose helper returned no result."
                        : result.errorText());
            }
            ImagePlus labelImage = result.labelImage();
            if (labelImage != null) {
                Path cellprobPath = result.cellprobPath().orElse(expectedCellprobPath(outputDir));
                try {
                    attachCellprobImage(labelImage, cellprobPath);
                } catch (RuntimeException e) {
                    closeImage(labelImage);
                    throw e;
                }
            }
            return labelImage;
        } finally {
            worker.close();
        }
    }

    static void attachCellprobImage(ImagePlus labelImage, Path cellprobPath) {
        if (labelImage == null) {
            return;
        }
        ImagePlus cellprobImage = readCellprobImage(cellprobPath);
        if (cellprobImage != null) {
            try {
                requireRegisteredPair(labelImage, cellprobImage,
                        "Cellpose label output", "Cellpose cell-probability output", false);
                if (labelImage.getCalibration() != null) {
                    cellprobImage.setCalibration(labelImage.getCalibration().copy());
                }
                labelImage.setProperty(CELLPROB_IMAGE_PROPERTY, cellprobImage);
            } catch (RuntimeException e) {
                closeImage(cellprobImage);
                throw e;
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
        BitSet labels = new BitSet();
        Set<Integer> highLabels = null;
        int nSlices = labelImage.getStackSize();
        for (int s = 1; s <= nSlices; s++) {
            ImageProcessor ip = labelImage.getStack().getProcessor(s);
            if (ip == null) continue;
            for (int i = 0; i < ip.getPixelCount(); i++) {
                int label = labelFromPixel(ip.getf(i));
                if (label <= 0) continue;
                if (label <= MAX_BITSET_LABEL) {
                    labels.set(label);
                } else {
                    if (highLabels == null) highLabels = new HashSet<Integer>();
                    highLabels.add(Integer.valueOf(label));
                }
            }
        }
        return labels.cardinality() + (highLabels == null ? 0 : highLabels.size());
    }

    public static ImagePlus prepareRuntimeInput(ImagePlus primaryInput, ImagePlus companionInput, String channelName) {
        if (primaryInput == null) return null;
        requireCellposeSourceGeometry(primaryInput, "primary input");
        if (companionInput == null) return primaryInput;
        requireRegisteredPair(primaryInput, companionInput,
                "primary input", "companion input", true);

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

        // Cap the BLAS / OpenMP thread pools inside the Cellpose Python
        // subprocess so two concurrent DL inferences (StarDist + Cellpose,
        // or two Cellpose channels) do not oversubscribe the host CPU. All
        // four variables are set because the binding one depends on which
        // BLAS backend PyTorch pulled in; the non-binding ones are ignored.
        int threadsPerInference = GpuConcurrency.threadsPerInference();
        String tStr = Integer.toString(threadsPerInference);
        java.util.Map<String, String> env = pb.environment();
        env.put("OMP_NUM_THREADS", tStr);
        env.put("MKL_NUM_THREADS", tStr);
        env.put("OPENBLAS_NUM_THREADS", tStr);
        env.put("NUMEXPR_NUM_THREADS", tStr);
        IJ.log("    Cellpose" + chTag + " thread cap: " + tStr
                + " threads (OMP/MKL/OPENBLAS/NUMEXPR)");

        Path logFile = diagnosticLogFile(projectRoot, channelName);
        runManagedProcess(pb, logFile, CELLPOSE_TIMEOUT_SECONDS,
                "    Cellpose" + chTag);
    }

    static CellposeLocalTrainingService.DiagnosticSnapshot runManagedCommand(
            List<String> command,
            Path workingDirectory,
            Path logFile,
            long timeoutSeconds,
            String logPrefix) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        return runManagedProcess(builder, logFile, timeoutSeconds, logPrefix);
    }

    private static CellposeLocalTrainingService.DiagnosticSnapshot runManagedProcess(
            ProcessBuilder builder,
            Path logFile,
            long timeoutSeconds,
            String logPrefix) throws Exception {
        String prefix = logPrefix == null ? "    Cellpose" : logPrefix;
        CellposeLocalTrainingService.DiagnosticSnapshot snapshot;
        try (CellposeLocalTrainingService.ProcessDiagnostics diagnostics =
                     new CellposeLocalTrainingService.ProcessDiagnostics(logFile)) {
            diagnostics.writeMetadata("Command: " + displayCommand(builder.command()));
            IJ.log(prefix + " diagnostics: " + diagnostics.logFile());
            Process process;
            try {
                process = builder.start();
            } catch (IOException e) {
                throw new IOException("Could not start Cellpose process. Log: "
                        + diagnostics.logFile(), e);
            }
            CellposeLocalTrainingService.ManagedProcessResult result;
            try {
                result = CellposeLocalTrainingService.runManagedProcess(process,
                        "Cellpose", timeoutSeconds, 0L,
                        diagnostics.stdout(new ThrottledImageJLogConsumer(prefix, "stdout")),
                        diagnostics.stderr(new ThrottledImageJLogConsumer(prefix, "stderr")));
            } catch (IOException e) {
                snapshot = diagnostics.snapshot();
                throw new IOException(processFailureMessage("Cellpose process failed",
                        diagnostics.logFile(), snapshot), e);
            } catch (InterruptedException e) {
                snapshot = diagnostics.snapshot();
                throw new InterruptedExceptionWithCause(processFailureMessage(
                        "Cellpose process interrupted", diagnostics.logFile(), snapshot), e);
            }
            snapshot = diagnostics.snapshot();
            if (result.exitCode != 0) {
                throw new IOException(processFailureMessage("Cellpose exited with code "
                        + result.exitCode, diagnostics.logFile(), snapshot));
            }
        }
        return snapshot;
    }

    private static String displayCommand(List<String> command) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < command.size(); i++) {
            if (i > 0) {
                out.append(' ');
            }
            String value = command.get(i) == null ? "" : command.get(i);
            if (value.indexOf(' ') >= 0 || value.indexOf('\t') >= 0
                    || value.indexOf('"') >= 0) {
                out.append('"').append(value.replace("\"", "\\\"")).append('"');
            } else {
                out.append(value);
            }
        }
        return out.toString();
    }

    private static Path diagnosticLogFile(File projectRoot, String channelName)
            throws IOException {
        Path root;
        if (projectRoot != null) {
            root = projectRoot.toPath().toAbsolutePath().normalize()
                    .resolve("logs").resolve("cellpose");
        } else {
            root = Paths.get(System.getProperty("java.io.tmpdir"),
                    "flash-cellpose-logs").toAbsolutePath().normalize();
        }
        Files.createDirectories(root);
        String channel = channelName == null ? "channel" : channelName.trim();
        channel = channel.replaceAll("[^A-Za-z0-9._-]+", "_");
        if (channel.isEmpty()) {
            channel = "channel";
        }
        long sequence = PROCESS_RUN_SEQUENCE.incrementAndGet();
        return root.resolve("cellpose-" + channel + '-'
                + System.currentTimeMillis() + '-' + sequence + ".log");
    }

    private static String processFailureMessage(String label,
                                                Path logFile,
                                                CellposeLocalTrainingService.DiagnosticSnapshot snapshot) {
        StringBuilder message = new StringBuilder(label).append(". Log: ")
                .append(logFile);
        appendDiagnosticTail(message, "stderr", snapshot == null
                ? java.util.Collections.<String>emptyList() : snapshot.stderrTail);
        appendDiagnosticTail(message, "stdout", snapshot == null
                ? java.util.Collections.<String>emptyList() : snapshot.stdoutTail);
        return message.toString();
    }

    private static void appendDiagnosticTail(StringBuilder message,
                                             String stream,
                                             List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        message.append(". Last ").append(stream).append(": ");
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                message.append(" | ");
            }
            message.append(lines.get(i));
        }
    }

    private static final class ThrottledImageJLogConsumer
            implements CellposeLocalTrainingService.LineConsumer {
        private static final long INITIAL_LINES = 16L;
        private static final long MAX_IMMEDIATE_DIAGNOSTICS = 64L;
        private static final long SAMPLE_INTERVAL = 1000L;

        private final String prefix;
        private final String stream;
        private long lines;
        private long immediateDiagnostics;

        ThrottledImageJLogConsumer(String prefix, String stream) {
            this.prefix = prefix;
            this.stream = stream;
        }

        @Override public void accept(String line) {
            lines++;
            String lower = line == null ? "" : line.toLowerCase(java.util.Locale.ROOT);
            boolean diagnostic = lower.contains("error") || lower.contains("warning")
                    || lower.contains("traceback") || lower.contains("exception");
            boolean immediate = diagnostic
                    && immediateDiagnostics < MAX_IMMEDIATE_DIAGNOSTICS;
            if (immediate) {
                immediateDiagnostics++;
            }
            if (lines <= INITIAL_LINES || immediate || lines % SAMPLE_INTERVAL == 0L) {
                IJ.log(prefix + " " + stream + " > " + (line == null ? "" : line));
            }
        }
    }

    private static final class InterruptedExceptionWithCause extends InterruptedException {
        InterruptedExceptionWithCause(String message, InterruptedException cause) {
            super(message);
            initCause(cause);
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
        command.add(formatDiameterPixels(input, requirePositiveDiameter(diameter)));
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
        ImagePlus labelImage = null;
        try {
            if (maskPath == null || !Files.isRegularFile(maskPath)) {
                String chTag = (channelName != null && !channelName.isEmpty()) ? " [" + channelName + "]" : "";
                IJ.log("WARNING: Cellpose" + chTag + " produced no mask file at " + maskPath);
                return null;
            }

            labelImage = IJ.openImage(maskPath.toString());
            if (labelImage == null) {
                throw new IllegalStateException("Could not open Cellpose mask: " + maskPath);
            }

            requireCellposeOutputGeometry(labelImage, "mask output");
            if (input != null) {
                requireCellposeSourceGeometry(input, "Cellpose input");
                requireMaskGeometry(input, labelImage);
            }
            prepareLabelImageForInstall(labelImage);
            requireCellposeOutputGeometry(labelImage, "mask output");

            labelImage.setTitle("Label Image");
            if (input != null && input.getCalibration() != null) {
                labelImage.setCalibration(input.getCalibration().copy());
            }

            int nObjects = countLabels(labelImage);
            String chTag = (channelName != null && !channelName.isEmpty()) ? " [" + channelName + "]" : "";
            IJ.log("    Cellpose" + chTag + ": " + nObjects + " objects detected"
                    + " [native " + (input != null && input.getNSlices() > 1 ? "3D" : "2D") + "]");
            return labelImage;
        } catch (ImageGeometryException e) {
            closeImage(labelImage);
            IJ.log("WARNING: Failed reading Cellpose mask image at " + maskPath
                    + " for channel='" + channelName + "': " + e.getMessage());
            throw e;
        } catch (Exception e) {
            closeImage(labelImage);
            IJ.log("WARNING: Failed reading Cellpose mask image at " + maskPath
                    + " for channel='" + channelName + "', input='"
                    + (input == null ? "<null>" : input.getTitle()) + "': "
                    + exceptionSummary(e));
            return null;
        }
    }

    /**
     * Validates Cellpose's label domain and narrows only values that are
     * exactly representable as unsigned-short pixels. Wide IDs remain 32-bit.
     */
    static void prepareLabelImageForInstall(ImagePlus labelImage) {
        Set<Integer> before = validatedPositiveLabels(labelImage);
        int maximum = 0;
        for (Integer label : before) {
            maximum = Math.max(maximum, label.intValue());
        }
        if (maximum <= 65_535 && labelImage.getBitDepth() != 16) {
            ImageStack oldStack = labelImage.getStack();
            ImageStack newStack = new ImageStack(oldStack.getWidth(), oldStack.getHeight());
            for (int s = 1; s <= oldStack.getSize(); s++) {
                ImageProcessor source = oldStack.getProcessor(s);
                ShortProcessor target = new ShortProcessor(source.getWidth(), source.getHeight());
                for (int i = 0; i < source.getPixelCount(); i++) {
                    target.set(i, checkedLabel(source.getf(i)));
                }
                newStack.addSlice(target);
            }
            labelImage.setStack(newStack);
        } else if (maximum > 65_535 && labelImage.getBitDepth() != 32) {
            throw new IllegalArgumentException(
                    "Cellpose label IDs above 65,535 require 32-bit ImageJ storage.");
        }
        Set<Integer> after = validatedPositiveLabels(labelImage);
        if (!before.equals(after)) {
            throw new IllegalStateException(
                    "Cellpose label storage conversion did not preserve the label identity set.");
        }
    }

    private static Set<Integer> validatedPositiveLabels(ImagePlus labelImage) {
        if (labelImage == null || labelImage.getStack() == null) {
            throw new IllegalArgumentException("Cellpose label image is missing.");
        }
        if (labelImage.getBitDepth() == 24) {
            throw new IllegalArgumentException("Cellpose label image cannot use RGB storage.");
        }
        Set<Integer> labels = new HashSet<Integer>();
        for (int s = 1; s <= labelImage.getStackSize(); s++) {
            ImageProcessor processor = labelImage.getStack().getProcessor(s);
            if (processor == null) {
                throw new IllegalArgumentException(
                        "Cellpose label image has a missing slice " + s + ".");
            }
            for (int i = 0; i < processor.getPixelCount(); i++) {
                int label = checkedLabel(processor.getf(i));
                if (label > 0) labels.add(Integer.valueOf(label));
            }
        }
        return labels;
    }

    private static int checkedLabel(float value) {
        if (!Float.isFinite(value) || value < 0.0f || value != Math.rint(value)
                || value > MAX_EXACT_FLOAT_LABEL) {
            throw new IllegalArgumentException(
                    "Cellpose labels must be finite, integral, non-negative, and no greater than "
                            + MAX_EXACT_FLOAT_LABEL + "; found " + value + ".");
        }
        return (int) value;
    }

    private static SegmentationRunFailureException failure(String message, Throwable cause) {
        return new SegmentationRunFailureException(message, cause);
    }

    private static String exceptionSummary(Throwable throwable) {
        if (throwable == null) {
            return "unknown error";
        }
        String message = throwable.getMessage();
        return throwable.getClass().getSimpleName()
                + (message == null || message.trim().isEmpty() ? "" : " - " + message.trim());
    }

    public static ImagePlus readCellprobImage(Path cellprobPath) {
        ImagePlus cellprobImage = null;
        try {
            if (cellprobPath == null || !Files.isRegularFile(cellprobPath)) {
                IJ.log("WARNING: Cellpose produced no cell probability file at " + cellprobPath);
                return null;
            }

            cellprobImage = IJ.openImage(cellprobPath.toString());
            if (cellprobImage == null) {
                throw new IllegalStateException("Could not open Cellpose cell probability image: "
                        + cellprobPath);
            }
            requireCellposeOutputGeometry(cellprobImage, "cell-probability output");

            if (cellprobImage.getBitDepth() != 32) {
                ImageStack oldStack = cellprobImage.getStack();
                ImageStack newStack = new ImageStack(oldStack.getWidth(), oldStack.getHeight());
                for (int s = 1; s <= oldStack.getSize(); s++) {
                    ImageProcessor ip = oldStack.getProcessor(s);
                    newStack.addSlice(ip.convertToFloatProcessor());
                }
                cellprobImage.setStack(newStack);
            }
            requireCellposeOutputGeometry(cellprobImage, "cell-probability output");

            cellprobImage.setTitle("Cellpose Cell Probability");
            return cellprobImage;
        } catch (ImageGeometryException e) {
            closeImage(cellprobImage);
            IJ.log("WARNING: Failed reading Cellpose cell probability image at "
                    + cellprobPath + ": " + e.getMessage());
            throw e;
        } catch (Exception e) {
            closeImage(cellprobImage);
            IJ.log("WARNING: Failed reading Cellpose cell probability image: " + e.getMessage());
            return null;
        }
    }

    /**
     * Compatibility adapter for callers that index means directly by label ID.
     * Dense allocation is capped so sparse wide IDs cannot exhaust the heap;
     * new code should use {@link #perObjectMeanCellprobByLabel(ImagePlus, ImagePlus)}.
     */
    @Deprecated
    public static double[] perObjectMeanCellprob(ImagePlus labelImage,
                                                 ImagePlus cellprobImage) {
        if (labelImage == null || cellprobImage == null
                || labelImage.getStack() == null || cellprobImage.getStack() == null) {
            return new double[0];
        }
        Map<Integer, Double> sparse =
                perObjectMeanCellprobByLabel(labelImage, cellprobImage);
        int maximum = 0;
        for (Integer label : sparse.keySet()) {
            if (label != null) maximum = Math.max(maximum, label.intValue());
        }
        if (maximum > MAX_DENSE_CELLPROB_LABEL) {
            throw new IllegalArgumentException("Dense Cellpose cell-probability means cannot "
                    + "represent sparse label " + maximum + " safely; use "
                    + "perObjectMeanCellprobByLabel instead.");
        }
        double[] dense = new double[maximum + 1];
        Arrays.fill(dense, Double.NaN);
        for (Map.Entry<Integer, Double> entry : sparse.entrySet()) {
            dense[entry.getKey().intValue()] = entry.getValue().doubleValue();
        }
        return dense;
    }

    public static Map<Integer, Double> perObjectMeanCellprobByLabel(
            ImagePlus labelImage,
            ImagePlus cellprobImage) {
        if (labelImage == null || cellprobImage == null
                || labelImage.getStack() == null || cellprobImage.getStack() == null) {
            return Collections.emptyMap();
        }
        requireRegisteredPair(labelImage, cellprobImage,
                "label image", "cell probability image", true);

        Map<Integer, CellprobAccumulator> accumulators =
                new LinkedHashMap<Integer, CellprobAccumulator>();
        for (int s = 1; s <= labelImage.getStackSize(); s++) {
            ImageProcessor labels = labelImage.getStack().getProcessor(s);
            ImageProcessor cellprob = cellprobImage.getStack().getProcessor(s);
            int width = labels.getWidth();
            int height = labels.getHeight();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int label = checkedLabel(labels.getf(x, y));
                    if (label <= 0) continue;
                    Integer key = Integer.valueOf(label);
                    CellprobAccumulator accumulator = accumulators.get(key);
                    if (accumulator == null) {
                        accumulator = new CellprobAccumulator();
                        accumulators.put(key, accumulator);
                    }
                    float cellprobValue = cellprob.getf(x, y);
                    if (!Float.isFinite(cellprobValue)) {
                        continue;
                    }
                    accumulator.sum += cellprobValue;
                    accumulator.count++;
                }
            }
        }

        Map<Integer, Double> means = new LinkedHashMap<Integer, Double>();
        for (Map.Entry<Integer, CellprobAccumulator> entry : accumulators.entrySet()) {
            CellprobAccumulator accumulator = entry.getValue();
            double mean = accumulator.count == 0L
                    ? Double.NaN
                    : accumulator.sum / (double) accumulator.count;
            means.put(entry.getKey(), Double.valueOf(mean));
        }
        return means;
    }

    private static final class CellprobAccumulator {
        double sum;
        long count;
    }

    static Path expectedMaskPath(Path outputDir) {
        return outputDir.resolve(INPUT_STACK_BASENAME + MASK_SUFFIX);
    }

    static Path expectedCellprobPath(Path outputDir) {
        return outputDir.resolve(INPUT_STACK_BASENAME + "_cellprob.tif");
    }

    private static void requireCellposeSourceGeometry(ImagePlus image, String role) {
        requireConsistentAxes(image, role);
        if (image.getNChannels() != 1 || image.getNFrames() != 1) {
            throw new ImageGeometryException("Unsupported Cellpose " + role + " geometry: "
                    + geometry(image) + ". Expected C=1 and T=1; time must not be flattened into Z.");
        }
    }

    private static void requireCellposeOutputGeometry(ImagePlus image, String role) {
        requireConsistentAxes(image, role);
        if (image.getNChannels() != 1 || image.getNFrames() != 1) {
            throw new ImageGeometryException("Unsupported Cellpose " + role + " geometry: "
                    + geometry(image) + ". Expected C=1 and T=1; time must not be flattened into Z.");
        }
    }

    private static void requireMaskGeometry(ImagePlus input, ImagePlus mask) {
        requireConsistentAxes(mask, "mask output");
        if (mask.getNChannels() != 1 || mask.getNFrames() != 1
                || input.getWidth() != mask.getWidth()
                || input.getHeight() != mask.getHeight()
                || input.getNSlices() != mask.getNSlices()) {
            throw new ImageGeometryException("Cellpose mask geometry does not match its input: input "
                    + geometry(input) + ", mask " + geometry(mask)
                    + ". Expected identical X/Y/Z with C=1 and T=1.");
        }
    }

    private static void requireRegisteredPair(ImagePlus reference,
                                              ImagePlus candidate,
                                              String referenceRole,
                                              String candidateRole,
                                              boolean requireCalibration) {
        requireConsistentAxes(reference, referenceRole);
        requireConsistentAxes(candidate, candidateRole);
        boolean sameAxes = reference.getWidth() == candidate.getWidth()
                && reference.getHeight() == candidate.getHeight()
                && reference.getNChannels() == candidate.getNChannels()
                && reference.getNSlices() == candidate.getNSlices()
                && reference.getNFrames() == candidate.getNFrames();
        boolean supported = reference.getNChannels() == 1
                && candidate.getNChannels() == 1
                && reference.getNFrames() == 1
                && candidate.getNFrames() == 1;
        if (!sameAxes || !supported
                || (requireCalibration && !sameSpatialCalibration(reference, candidate))) {
            throw new ImageGeometryException("Cellpose image registration mismatch: "
                    + referenceRole + " " + geometry(reference) + ", "
                    + candidateRole + " " + geometry(candidate)
                    + ". Expected identical X/Y/Z/C/T"
                    + (requireCalibration ? " and spatial calibration" : "") + ".");
        }
    }

    private static void requireConsistentAxes(ImagePlus image, String role) {
        if (image == null || image.getStack() == null || image.getStackSize() <= 0) {
            throw new ImageGeometryException("Cellpose " + role + " is missing or empty.");
        }
        long planes = (long) image.getNChannels() * (long) image.getNSlices()
                * (long) image.getNFrames();
        if (image.getWidth() <= 0 || image.getHeight() <= 0
                || image.getNChannels() <= 0 || image.getNSlices() <= 0
                || image.getNFrames() <= 0 || planes != image.getStackSize()) {
            throw new ImageGeometryException("Inconsistent Cellpose " + role + " axes: "
                    + geometry(image) + ".");
        }
    }

    private static boolean sameSpatialCalibration(ImagePlus first, ImagePlus second) {
        Calibration left = first == null ? null : first.getCalibration();
        Calibration right = second == null ? null : second.getCalibration();
        if (left == null || right == null) return false;
        return validSpatialCalibration(left) && validSpatialCalibration(right)
                && Double.compare(left.pixelWidth, right.pixelWidth) == 0
                && Double.compare(left.pixelHeight, right.pixelHeight) == 0
                && Double.compare(left.pixelDepth, right.pixelDepth) == 0
                && Double.compare(left.xOrigin, right.xOrigin) == 0
                && Double.compare(left.yOrigin, right.yOrigin) == 0
                && Double.compare(left.zOrigin, right.zOrigin) == 0
                && safeUnit(left).equals(safeUnit(right));
    }

    private static boolean validSpatialCalibration(Calibration calibration) {
        return calibration != null
                && Double.isFinite(calibration.pixelWidth) && calibration.pixelWidth > 0.0d
                && Double.isFinite(calibration.pixelHeight) && calibration.pixelHeight > 0.0d
                && Double.isFinite(calibration.pixelDepth) && calibration.pixelDepth > 0.0d
                && Double.isFinite(calibration.xOrigin)
                && Double.isFinite(calibration.yOrigin)
                && Double.isFinite(calibration.zOrigin);
    }

    private static String safeUnit(Calibration calibration) {
        String unit = calibration == null ? "" : calibration.getUnit();
        return unit == null ? "" : unit;
    }

    private static String geometry(ImagePlus image) {
        if (image == null) return "<null>";
        return "X=" + image.getWidth() + ",Y=" + image.getHeight()
                + ",Z=" + image.getNSlices() + ",C=" + image.getNChannels()
                + ",T=" + image.getNFrames() + ",stack=" + image.getStackSize();
    }

    private static void closeImage(ImagePlus image) {
        if (image == null) return;
        try {
            image.changes = false;
            image.close();
        } catch (RuntimeException ignored) {
            // Best-effort cleanup must not hide the geometry or backend failure.
        }
        try {
            image.flush();
        } catch (RuntimeException ignored) {
            // Best-effort cleanup must not hide the geometry or backend failure.
        }
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

    private static double requirePositiveDiameter(double diameter) {
        if (!Double.isFinite(diameter) || diameter <= 0.0d) {
            throw new IllegalArgumentException("Cellpose diameter must be finite and greater than 0.");
        }
        return diameter;
    }

    private static int labelFromPixel(float value) {
        if (!Float.isFinite(value) || value <= 0f) return 0;
        return value > Integer.MAX_VALUE ? 0 : Math.round(value);
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
