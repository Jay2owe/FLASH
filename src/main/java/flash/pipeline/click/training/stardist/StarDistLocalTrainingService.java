package flash.pipeline.click.training.stardist;

import flash.pipeline.click.training.cellpose.CellposeLocalTrainingService;
import flash.pipeline.intelligence.MiniJson;
import flash.pipeline.segmentation.StarDistModelZipValidator;
import flash.pipeline.ui.wizard.JsonIO;
import ij.ImagePlus;
import ij.io.Opener;
import ij.process.ImageProcessor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hidden local StarDist 2D training runner for datasets exported by FLASH.
 */
public final class StarDistLocalTrainingService {
    public static final String LOCAL_ENABLED_PROPERTY =
            "flash.stardist.training.local.enabled";
    public static final String PYTHON_PROPERTY =
            "flash.stardist.training.python";
    public static final String CONDA_ENV_PROPERTY =
            "flash.stardist.training.conda.env";
    public static final String CONDA_EXECUTABLE_PROPERTY =
            "flash.stardist.training.conda.executable";
    public static final String EPOCHS_PROPERTY =
            "flash.stardist.training.epochs";
    public static final String BATCH_SIZE_PROPERTY =
            "flash.stardist.training.batchSize";
    public static final String STEPS_PER_EPOCH_PROPERTY =
            "flash.stardist.training.stepsPerEpoch";
    public static final String LEARNING_RATE_PROPERTY =
            "flash.stardist.training.learningRate";
    public static final String USE_GPU_PROPERTY =
            "flash.stardist.training.useGpu";
    public static final String VALIDATION_FRACTION_PROPERTY =
            "flash.stardist.training.validationFraction";
    public static final String SEED_PROPERTY =
            "flash.stardist.training.seed";
    public static final String TIMEOUT_SECONDS_PROPERTY =
            "flash.stardist.training.timeoutSeconds";
    public static final String STALL_TIMEOUT_SECONDS_PROPERTY =
            "flash.stardist.training.stallTimeoutSeconds";

    private static final String SCRIPT_FILENAME = "train_stardist_flash.py";
    private static final String COMMAND_FILENAME = "train_stardist_command.txt";
    private static final String LOG_FILENAME = "stardist_training.log";
    private static final String OUTPUT_ZIP_FILENAME = "TF_SavedModel.zip";
    private static final String MODELS_DIR = "stardist_model";
    static final String SPLIT_MANIFEST_FILENAME = "training_split.json";
    static final String REPRODUCIBILITY_FILENAME = "training_reproducibility.json";
    private static final int MAX_EXACT_FLOAT_LABEL = 16_777_216;
    private static final AtomicLong LOG_SEQUENCE = new AtomicLong();

    private final Config config;
    private final ProcessRunner runner;

    public StarDistLocalTrainingService() {
        this(Config.fromSystemProperties(), new DefaultProcessRunner());
    }

    public StarDistLocalTrainingService(Config config, ProcessRunner runner) {
        this.config = config == null ? Config.fromSystemProperties() : config;
        this.runner = runner == null ? new DefaultProcessRunner() : runner;
    }

    public boolean isEnabled() {
        return config.enabled;
    }

    public TrainingResult train(StarDistDatasetPackager.PackagingResult packageResult,
                                String modelName,
                                ProgressSink progress) throws IOException, InterruptedException {
        if (packageResult == null || packageResult.outputDir == null) {
            throw new IOException("StarDist training dataset is not available.");
        }
        return train(packageResult.outputDir, modelName, progress);
    }

    public TrainingResult train(Path datasetDir,
                                String modelName,
                                ProgressSink progress) throws IOException, InterruptedException {
        if (!config.enabled) {
            throw new IOException("Local StarDist training is disabled. Set -D"
                    + LOCAL_ENABLED_PROPERTY + "=true to enable the hidden backend.");
        }
        validateTrainingDataset(datasetDir);
        TrainingArtifacts artifacts = prepareTrainingArtifacts(datasetDir, modelName, config);
        final Path[] reportedZip = new Path[] {null};
        ProgressSink safeProgress = progress == null ? NO_PROGRESS : progress;
        safeProgress.update(0.0, "Starting local StarDist training...");

        try (CellposeLocalTrainingService.ProcessDiagnostics diagnostics =
                     new CellposeLocalTrainingService.ProcessDiagnostics(artifacts.logFile)) {
            diagnostics.writeMetadata("Command: " + displayCommand(artifacts.command));
            ProcessSpec spec = new ProcessSpec(artifacts.command, artifacts.datasetDir,
                    config.timeoutSeconds, config.stallTimeoutSeconds,
                    artifacts.environment);
            ProcessResult result;
            try {
                result = runner.run(spec,
                        new DiagnosticAdapter(diagnostics.stdout(null),
                                new LoggingLineConsumer(artifacts.datasetDir,
                                        reportedZip, safeProgress)),
                        new DiagnosticAdapter(diagnostics.stderr(null),
                                new LoggingLineConsumer(artifacts.datasetDir,
                                        reportedZip, safeProgress)));
            } catch (IOException e) {
                throw trainingIoFailure("Local StarDist training", e,
                        artifacts.logFile, diagnostics.snapshot());
            } catch (InterruptedException e) {
                InterruptedException interrupted = new InterruptedException(
                        trainingInterruptionMessage("Local StarDist training",
                                artifacts.logFile, diagnostics.snapshot()));
                interrupted.initCause(e);
                Thread.currentThread().interrupt();
                throw interrupted;
            }
            int exitCode = result == null ? -1 : result.exitCode;
            if (exitCode != 0) {
                throw new IOException(failureMessage("Local StarDist training",
                        exitCode, artifacts.logFile, diagnostics.snapshot()));
            }
        }

        Path zip = reportedZip[0] == null ? artifacts.outputZip : reportedZip[0];
        if (!Files.isRegularFile(zip)) {
            throw new IOException("Local StarDist training finished but no model zip was found: "
                    + zip + ". Log: " + artifacts.logFile);
        }
        validateStarDistZip(zip);
        safeProgress.update(1.0, "Local StarDist training complete.");
        return new TrainingResult(zip, artifacts.logFile, artifacts.scriptFile,
                artifacts.commandFile, 0);
    }

    private static void validateTrainingDataset(Path datasetDir) throws IOException {
        Path dir = datasetDir == null ? null : datasetDir.toAbsolutePath().normalize();
        if (dir == null || !Files.isDirectory(dir)) {
            throw new IOException("StarDist training dataset directory does not exist: " + dir);
        }
        Path rawDir = dir.resolve("raw");
        Path labelsDir = dir.resolve("labels");
        if (!Files.isDirectory(rawDir) || !Files.isDirectory(labelsDir)) {
            throw new IOException("StarDist dataset must contain raw/ and labels/ folders: " + dir);
        }
        Set<String> rawNames = tiffNames(rawDir);
        Set<String> labelNames = tiffNames(labelsDir);
        if (rawNames.isEmpty()) {
            throw new IOException("StarDist training dataset has no raw TIFFs: " + rawDir);
        }
        Set<String> missingLabels = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        missingLabels.addAll(rawNames);
        missingLabels.removeAll(labelNames);
        if (!missingLabels.isEmpty()) {
            throw new IOException("StarDist training dataset is missing label TIFFs in "
                    + labelsDir + ": " + missingLabels);
        }
        Set<String> extraLabels = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        extraLabels.addAll(labelNames);
        extraLabels.removeAll(rawNames);
        if (!extraLabels.isEmpty()) {
            throw new IOException("StarDist training dataset has label TIFFs without raw pairs in "
                    + labelsDir + ": " + extraLabels);
        }
    }

    private static Set<String> tiffNames(Path dir) throws IOException {
        Set<String> out = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        java.util.stream.Stream<Path> stream = Files.list(dir);
        try {
            java.util.Iterator<Path> iterator = stream.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                Path name = path.getFileName();
                String fileName = name == null ? "" : name.toString();
                String lower = fileName.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".tif") || lower.endsWith(".tiff")) {
                    out.add(fileName);
                }
            }
        } finally {
            stream.close();
        }
        return out;
    }

    private static void validatePackagedLabelIdentity(Path datasetDir) throws IOException {
        Path labelsDir = datasetDir.resolve("labels");
        Set<String> names = tiffNames(labelsDir);
        if (names.isEmpty()) {
            throw labelFailure("StarDist handoff contains no label TIFFs in '"
                    + labelsDir + "'.");
        }
        for (String name : names) {
            Path path = labelsDir.resolve(name);
            ImagePlus image = new Opener().openImage(path.toString());
            if (image == null || image.getStack() == null || image.getStackSize() <= 0) {
                closeImage(image);
                throw labelFailure("could not decode packaged StarDist label TIFF '" + path + "'.");
            }
            try {
                int bitDepth = image.getBitDepth();
                if (bitDepth != 8 && bitDepth != 16 && bitDepth != 32) {
                    throw labelFailure("packaged StarDist label TIFF '" + path
                            + "' has unsupported " + bitDepth + "-bit storage.");
                }
                for (int slice = 1; slice <= image.getStackSize(); slice++) {
                    ImageProcessor processor = image.getStack().getProcessor(slice);
                    for (int pixel = 0; pixel < processor.getPixelCount(); pixel++) {
                        float value = processor.getf(pixel);
                        if (value == 0.0f) continue;
                        if (!Float.isFinite(value) || value <= 0.0f
                                || value > MAX_EXACT_FLOAT_LABEL
                                || value != Math.rint(value)) {
                            throw labelFailure("packaged StarDist label TIFF '" + path
                                    + "' has non-canonical value " + value + " at slice "
                                    + slice + ", pixel " + pixel + ".");
                        }
                    }
                }
            } finally {
                closeImage(image);
            }
        }
    }

    private static IOException labelFailure(String detail) {
        return new IOException("LABEL_IDENTITY_UNSUPPORTED: " + detail
                + " No StarDist training scripts or manifests were written.");
    }

    private static void closeImage(ImagePlus image) {
        if (image != null) {
            image.changes = false;
            image.close();
            image.flush();
        }
    }

    private static void validateStarDistZip(Path zipPath) throws IOException {
        Path file = zipPath == null ? null : zipPath.toAbsolutePath().normalize();
        if (file == null || !Files.isRegularFile(file)) {
            throw new IOException("StarDist model zip does not exist: " + file);
        }
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new IOException("StarDist model output must be a .zip file: " + file);
        }
        try {
            StarDistModelZipValidator.validate(file,
                    "Not a StarDist / CSBDeep SavedModel: missing saved_model.pb "
                    + "(or config.json + thresholds.json): " + file);
        } catch (IOException e) {
            throw new IOException("Invalid StarDist model zip output '" + file
                    + "': " + e.getMessage(), e);
        }
    }

    public static TrainingArtifacts prepareTrainingArtifacts(Path datasetDir,
                                                            String modelName,
                                                            Config config) throws IOException {
        Config safeConfig = config == null ? Config.fromSystemProperties() : config;
        Path dir = datasetDir == null ? null : datasetDir.toAbsolutePath().normalize();
        if (dir == null) {
            throw new IOException("StarDist dataset directory must not be null.");
        }
        if (!Files.isDirectory(dir.resolve("raw")) || !Files.isDirectory(dir.resolve("labels"))) {
            throw new IOException("StarDist dataset must contain raw/ and labels/ folders: " + dir);
        }
        validatePackagedLabelIdentity(dir);
        Files.createDirectories(dir);
        Path scriptFile = dir.resolve(SCRIPT_FILENAME);
        Path commandFile = dir.resolve(COMMAND_FILENAME);
        Path logFile = runLogFile(dir, LOG_FILENAME);
        Path outputZip = dir.resolve(OUTPUT_ZIP_FILENAME);
        Path modelsDir = dir.resolve(MODELS_DIR);
        Path splitManifest = dir.resolve(SPLIT_MANIFEST_FILENAME);
        Path reproducibilityFile = dir.resolve(REPRODUCIBILITY_FILENAME);
        Map<String, String> environment = reproducibilityEnvironment(safeConfig.seed);
        String cleanName = safeModelName(modelName);

        writeSplitManifest(dir, splitManifest, safeConfig);
        writeRequestedReproducibility(reproducibilityFile, safeConfig);
        String script = buildTrainingScript();
        Files.write(scriptFile, script.getBytes(StandardCharsets.UTF_8));
        List<String> command = buildCommand(scriptFile, dir, outputZip,
                modelsDir, cleanName, splitManifest, reproducibilityFile, safeConfig);
        Files.write(commandFile,
                Collections.singletonList(displayCommand(command)),
                StandardCharsets.UTF_8);
        return new TrainingArtifacts(dir, scriptFile, commandFile, logFile,
                outputZip, splitManifest, reproducibilityFile, environment,
                command, script);
    }

    private static Path runLogFile(Path directory, String baseName) {
        String stem = baseName;
        String suffix = "";
        int dot = baseName.lastIndexOf('.');
        if (dot > 0) {
            stem = baseName.substring(0, dot);
            suffix = baseName.substring(dot);
        }
        return directory.resolve(stem + '-' + System.currentTimeMillis() + '-'
                + LOG_SEQUENCE.incrementAndGet() + suffix);
    }

    public static List<String> buildCommand(Path scriptFile,
                                            Path datasetDir,
                                            Path outputZip,
                                            Path modelsDir,
                                            String modelName,
                                            Config config) {
        Path dir = datasetDir.toAbsolutePath().normalize();
        return buildCommand(scriptFile, datasetDir, outputZip, modelsDir, modelName,
                dir.resolve(SPLIT_MANIFEST_FILENAME),
                dir.resolve(REPRODUCIBILITY_FILENAME), config);
    }

    private static List<String> buildCommand(Path scriptFile,
                                             Path datasetDir,
                                             Path outputZip,
                                             Path modelsDir,
                                             String modelName,
                                             Path splitManifest,
                                             Path reproducibilityFile,
                                             Config config) {
        Config safeConfig = config == null ? Config.fromSystemProperties() : config;
        List<String> command = new ArrayList<String>();
        String condaEnv = clean(safeConfig.condaEnvironment);
        if (!condaEnv.isEmpty()) {
            command.add(cleanOrDefault(safeConfig.condaExecutable, "conda"));
            command.add("run");
            command.add("-n");
            command.add(condaEnv);
        }
        command.add(cleanOrDefault(safeConfig.pythonExecutable, "python"));
        command.add(scriptFile.toAbsolutePath().normalize().toString());
        command.add("--dataset");
        command.add(datasetDir.toAbsolutePath().normalize().toString());
        command.add("--output-zip");
        command.add(outputZip.toAbsolutePath().normalize().toString());
        command.add("--models-dir");
        command.add(modelsDir.toAbsolutePath().normalize().toString());
        command.add("--split-manifest");
        command.add(splitManifest.toAbsolutePath().normalize().toString());
        command.add("--reproducibility-file");
        command.add(reproducibilityFile.toAbsolutePath().normalize().toString());
        command.add("--model-name");
        command.add(safeModelName(modelName));
        command.add("--epochs");
        command.add(String.valueOf(Math.max(1, safeConfig.epochs)));
        command.add("--batch-size");
        command.add(String.valueOf(Math.max(1, safeConfig.batchSize)));
        command.add("--steps-per-epoch");
        command.add(String.valueOf(Math.max(1, safeConfig.stepsPerEpoch)));
        command.add("--learning-rate");
        command.add(String.valueOf(safeConfig.learningRate));
        command.add("--n-rays");
        command.add(String.valueOf(Math.max(8, safeConfig.nRays)));
        command.add("--grid");
        command.add(String.valueOf(Math.max(1, safeConfig.grid)));
        command.add("--validation-fraction");
        command.add(String.valueOf(safeConfig.validationFraction));
        command.add("--seed");
        command.add(String.valueOf(safeConfig.seed));
        if (safeConfig.useGpu) {
            command.add("--use-gpu");
        }
        return command;
    }

    private static void writeSplitManifest(Path datasetDir,
                                           Path target,
                                           Config config) throws IOException {
        List<SampleRecord> samples = readSampleRecords(datasetDir);
        Map<String, List<SampleRecord>> byGroup =
                new TreeMap<String, List<SampleRecord>>();
        for (SampleRecord sample : samples) {
            List<SampleRecord> group = byGroup.get(sample.groupId);
            if (group == null) {
                group = new ArrayList<SampleRecord>();
                byGroup.put(sample.groupId, group);
            }
            group.add(sample);
        }

        List<String> groups = new ArrayList<String>(byGroup.keySet());
        Collections.shuffle(groups, new Random(config.seed));
        boolean validationEnabled = groups.size() >= 2 && config.validationFraction > 0.0;
        int validationGroups = validationEnabled
                ? Math.max(1, (int) Math.round(groups.size() * config.validationFraction))
                : 0;
        validationGroups = Math.min(validationGroups, Math.max(0, groups.size() - 1));
        Set<String> validation = new TreeSet<String>();
        validation.addAll(groups.subList(0, validationGroups));

        Map<String, Object> root = JsonIO.object();
        root.put("version", Integer.valueOf(1));
        root.put("seed", Integer.valueOf(config.seed));
        root.put("algorithm", "java.util.Random group shuffle v1");
        root.put("validationFraction", Double.valueOf(config.validationFraction));
        root.put("groupCount", Integer.valueOf(groups.size()));
        root.put("validationEnabled", Boolean.valueOf(validationEnabled));
        if (!validationEnabled) {
            root.put("validationDisabledReason", groups.size() < 2
                    ? "Fewer than two independent source/session groups; validation and threshold optimization are disabled."
                    : "Validation fraction is zero; validation and threshold optimization are disabled.");
        }

        List<Object> assignments = new ArrayList<Object>();
        for (SampleRecord sample : samples) {
            Map<String, Object> assignment = JsonIO.object();
            assignment.put("sample", sample.sample);
            assignment.put("sourceImage", sample.sourceImage);
            assignment.put("sessionId", sample.sessionId);
            assignment.put("groupId", sample.groupId);
            assignment.put("partition", validation.contains(sample.groupId)
                    ? "validation" : "train");
            assignments.add(assignment);
        }
        root.put("assignments", assignments);
        Files.write(target, (JsonIO.write(root) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static List<SampleRecord> readSampleRecords(Path datasetDir) throws IOException {
        Set<String> rawNames = tiffNames(datasetDir.resolve("raw"));
        Map<String, SampleRecord> records =
                new TreeMap<String, SampleRecord>(String.CASE_INSENSITIVE_ORDER);
        Path manifest = datasetDir.resolve(StarDistDatasetPackager.SAMPLE_MANIFEST_FILENAME);
        if (Files.isRegularFile(manifest)) {
            Object parsed;
            try (InputStream input = Files.newInputStream(manifest)) {
                parsed = MiniJson.parseUtf8(input, MiniJson.DEFAULT_LIMITS,
                        manifest.toString());
            }
            if (!(parsed instanceof Map)) {
                throw new IOException("StarDist sample manifest root must be an object: "
                        + manifest);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) parsed;
            for (Object value : JsonIO.asList(root.get("samples"))) {
                Map<String, Object> item = JsonIO.asObject(value);
                String sample = clean(JsonIO.stringValue(item.get("sample")));
                String source = clean(JsonIO.stringValue(item.get("sourceImage")));
                String session = clean(JsonIO.stringValue(item.get("sessionId")));
                String group = clean(JsonIO.stringValue(item.get("groupId")));
                if (sample.isEmpty() || source.isEmpty() || session.isEmpty()
                        || group.isEmpty() || !rawNames.contains(sample)) {
                    throw new IOException("Invalid StarDist sample provenance record in "
                            + manifest + ": " + item);
                }
                if (records.put(sample,
                        new SampleRecord(sample, source, session, group)) != null) {
                    throw new IOException("Duplicate StarDist sample provenance for '"
                            + sample + "' in " + manifest);
                }
            }
        }
        for (String rawName : rawNames) {
            if (!records.containsKey(rawName)) {
                if (Files.isRegularFile(manifest)) {
                    throw new IOException("StarDist sample manifest does not describe raw TIFF '"
                            + rawName + "': " + manifest);
                }
                String source = legacySourceName(rawName);
                records.put(rawName, new SampleRecord(rawName, source,
                        "legacy-unmanifested", "legacy:" + source));
            }
        }
        return new ArrayList<SampleRecord>(records.values());
    }

    private static String legacySourceName(String fileName) {
        String source = fileName == null ? "" : fileName;
        source = source.replaceFirst("(?i)_C\\d+_z\\d+(?:_tile\\d+)?\\.tiff?$", "");
        return source.isEmpty() ? fileName : source;
    }

    private static void writeRequestedReproducibility(Path target,
                                                      Config config) throws IOException {
        Map<String, Object> evidence = JsonIO.object();
        evidence.put("version", Integer.valueOf(1));
        evidence.put("backend", "StarDist2D");
        evidence.put("seed", Integer.valueOf(config.seed));
        evidence.put("requestedDevice", config.useGpu ? "gpu" : "cpu");
        evidence.put("deterministicModeRequested", Boolean.TRUE);
        evidence.put("splitManifest", SPLIT_MANIFEST_FILENAME);
        evidence.put("runtimeEvidence", "pending");
        Files.write(target, (JsonIO.write(evidence) + "\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, String> reproducibilityEnvironment(int seed) {
        Map<String, String> environment = new LinkedHashMap<String, String>();
        environment.put("PYTHONHASHSEED", String.valueOf(seed));
        environment.put("TF_DETERMINISTIC_OPS", "1");
        environment.put("TF_CUDNN_DETERMINISTIC", "1");
        environment.put("CUBLAS_WORKSPACE_CONFIG", ":4096:8");
        return environment;
    }

    private static final class SampleRecord {
        final String sample;
        final String sourceImage;
        final String sessionId;
        final String groupId;

        SampleRecord(String sample, String sourceImage, String sessionId, String groupId) {
            this.sample = sample;
            this.sourceImage = sourceImage;
            this.sessionId = sessionId;
            this.groupId = groupId;
        }
    }

    public static String buildTrainingScript() {
        return ""
                + "import argparse\n"
                + "import json\n"
                + "import os\n"
                + "import platform\n"
                + "import random\n"
                + "import shutil\n"
                + "from pathlib import Path\n"
                + "\n"
                + "def parse_args():\n"
                + "    parser = argparse.ArgumentParser(description='FLASH StarDist 2D training')\n"
                + "    parser.add_argument('--dataset', required=True)\n"
                + "    parser.add_argument('--output-zip', required=True)\n"
                + "    parser.add_argument('--models-dir', required=True)\n"
                + "    parser.add_argument('--split-manifest', required=True)\n"
                + "    parser.add_argument('--reproducibility-file', required=True)\n"
                + "    parser.add_argument('--model-name', required=True)\n"
                + "    parser.add_argument('--epochs', type=int, default=100)\n"
                + "    parser.add_argument('--batch-size', type=int, default=1)\n"
                + "    parser.add_argument('--steps-per-epoch', type=int, default=100)\n"
                + "    parser.add_argument('--learning-rate', type=float, default=0.0003)\n"
                + "    parser.add_argument('--n-rays', type=int, default=32)\n"
                + "    parser.add_argument('--grid', type=int, default=2)\n"
                + "    parser.add_argument('--validation-fraction', type=float, default=0.2)\n"
                + "    parser.add_argument('--seed', type=int, default=42)\n"
                + "    parser.add_argument('--use-gpu', action='store_true')\n"
                + "    return parser.parse_args()\n"
                + "\n"
                + "\n"
                + "def write_json(path, value):\n"
                + "    path.parent.mkdir(parents=True, exist_ok=True)\n"
                + "    temporary = path.with_name(path.name + '.tmp')\n"
                + "    temporary.write_text(json.dumps(value, sort_keys=True, indent=2) + '\\n', encoding='utf-8')\n"
                + "    temporary.replace(path)\n"
                + "\n"
                + "\n"
                + "def seed_everything(seed):\n"
                + "    os.environ.setdefault('TF_DETERMINISTIC_OPS', '1')\n"
                + "    os.environ.setdefault('TF_CUDNN_DETERMINISTIC', '1')\n"
                + "    os.environ.setdefault('CUBLAS_WORKSPACE_CONFIG', ':4096:8')\n"
                + "    random.seed(seed)\n"
                + "    import numpy as np\n"
                + "    np.random.seed(seed)\n"
                + "    status = 'best-effort'\n"
                + "    api_status = 'requested'\n"
                + "    detail = 'Third-party augmentation and device kernels may remain nondeterministic.'\n"
                + "    import tensorflow as tf\n"
                + "    tf.random.set_seed(seed)\n"
                + "    try:\n"
                + "        tf.keras.utils.set_random_seed(seed)\n"
                + "    except AttributeError:\n"
                + "        pass\n"
                + "    try:\n"
                + "        tf.config.experimental.enable_op_determinism()\n"
                + "        api_status = 'enabled'\n"
                + "        detail = 'TensorFlow deterministic API enabled; third-party augmentation and device kernels may remain nondeterministic.'\n"
                + "    except Exception as error:\n"
                + "        api_status = 'unsupported'\n"
                + "        detail = 'TensorFlow deterministic API unavailable: {}; third-party/device kernels may remain nondeterministic.'.format(error)\n"
                + "    return np, tf, status, api_status, detail\n"
                + "\n"
                + "\n"
                + "def squeeze_2d(array, np):\n"
                + "    squeezed = np.squeeze(array)\n"
                + "    if squeezed.ndim != 2:\n"
                + "        raise RuntimeError('Expected 2D image plane after squeezing, got shape {}'.format(squeezed.shape))\n"
                + "    return squeezed\n"
                + "\n"
                + "\n"
                + "def canonical_labels(array, name, np):\n"
                + "    labels = squeeze_2d(array, np)\n"
                + "    if not np.all(np.isfinite(labels)):\n"
                + "        raise RuntimeError('LABEL_IDENTITY_UNSUPPORTED: {} contains non-finite labels'.format(name))\n"
                + "    rounded = np.rint(labels)\n"
                + "    if not np.array_equal(labels, rounded):\n"
                + "        raise RuntimeError('LABEL_IDENTITY_UNSUPPORTED: {} contains fractional labels'.format(name))\n"
                + "    if rounded.size and (rounded.min() < 0 or rounded.max() > 16777216):\n"
                + "        raise RuntimeError('LABEL_IDENTITY_UNSUPPORTED: {} labels must be in 0..16777216'.format(name))\n"
                + "    return rounded.astype(np.int32, copy=False)\n"
                + "\n"
                + "\n"
                + "def load_pairs(dataset, split_manifest, np, imread, normalize):\n"
                + "    raw_dir = dataset / 'raw'\n"
                + "    label_dir = dataset / 'labels'\n"
                + "    names = sorted([p.name for p in raw_dir.glob('*.tif') if (label_dir / p.name).is_file()])\n"
                + "    names += sorted([p.name for p in raw_dir.glob('*.tiff') if (label_dir / p.name).is_file()])\n"
                + "    if not names:\n"
                + "        raise RuntimeError('No matching raw/label TIFF pairs found in {}'.format(dataset))\n"
                + "    split = json.loads(split_manifest.read_text(encoding='utf-8'))\n"
                + "    assignments = {item['sample']: item['partition'] for item in split['assignments']}\n"
                + "    if set(assignments) != set(names):\n"
                + "        raise RuntimeError('Split manifest samples do not exactly match the training TIFF pairs')\n"
                + "    X_train, Y_train, X_val, Y_val = [], [], [], []\n"
                + "    for name in names:\n"
                + "        raw = squeeze_2d(imread(str(raw_dir / name)), np)\n"
                + "        labels = canonical_labels(imread(str(label_dir / name)), name, np)\n"
                + "        normalized = normalize(raw, 1, 99.8, axis=(0, 1))\n"
                + "        if assignments[name] == 'validation':\n"
                + "            X_val.append(normalized)\n"
                + "            Y_val.append(labels)\n"
                + "        elif assignments[name] == 'train':\n"
                + "            X_train.append(normalized)\n"
                + "            Y_train.append(labels)\n"
                + "        else:\n"
                + "            raise RuntimeError('Unknown split partition for {}: {}'.format(name, assignments[name]))\n"
                + "    validation_enabled = bool(split.get('validationEnabled', False))\n"
                + "    if not X_train:\n"
                + "        raise RuntimeError('Split manifest produced an empty training partition')\n"
                + "    if validation_enabled and not X_val:\n"
                + "        raise RuntimeError('Validation is enabled but the validation partition is empty')\n"
                + "    if not validation_enabled and X_val:\n"
                + "        raise RuntimeError('Validation is disabled but validation samples were assigned')\n"
                + "    return X_train, Y_train, X_val, Y_val, validation_enabled, split\n"
                + "\n"
                + "\n"
                + "def newest_zip(root):\n"
                + "    candidates = list(root.rglob('*.zip'))\n"
                + "    if not candidates:\n"
                + "        return None\n"
                + "    candidates.sort(key=lambda p: p.stat().st_mtime, reverse=True)\n"
                + "    return candidates[0]\n"
                + "\n"
                + "\n"
                + "def main():\n"
                + "    args = parse_args()\n"
                + "    dataset = Path(args.dataset)\n"
                + "    output_zip = Path(args.output_zip)\n"
                + "    models_dir = Path(args.models_dir)\n"
                + "    models_dir.mkdir(parents=True, exist_ok=True)\n"
                + "    np, tf, deterministic_status, deterministic_api_status, deterministic_detail = seed_everything(args.seed)\n"
                + "    from tifffile import imread\n"
                + "    from csbdeep.utils import normalize\n"
                + "    import stardist\n"
                + "    from stardist.models import Config2D, StarDist2D\n"
                + "    X_train, Y_train, X_val, Y_val, validation_enabled, split = load_pairs(\n"
                + "        dataset, Path(args.split_manifest), np, imread, normalize)\n"
                + "    devices = [device.name for device in tf.config.list_physical_devices()]\n"
                + "    evidence = {\n"
                + "        'version': 1,\n"
                + "        'backend': 'StarDist2D',\n"
                + "        'backendVersion': getattr(stardist, '__version__', 'unknown'),\n"
                + "        'tensorflowVersion': getattr(tf, '__version__', 'unknown'),\n"
                + "        'numpyVersion': getattr(np, '__version__', 'unknown'),\n"
                + "        'pythonVersion': platform.python_version(),\n"
                + "        'seed': args.seed,\n"
                + "        'requestedDevice': 'gpu' if args.use_gpu else 'cpu',\n"
                + "        'visibleDevices': devices,\n"
                + "        'deterministicModeRequested': True,\n"
                + "        'deterministicModeStatus': deterministic_status,\n"
                + "        'deterministicApiStatus': deterministic_api_status,\n"
                + "        'deterministicModeDetail': deterministic_detail,\n"
                + "        'splitManifest': str(Path(args.split_manifest)),\n"
                + "        'validationEnabled': validation_enabled,\n"
                + "        'groupCount': split.get('groupCount'),\n"
                + "        'evidenceWrittenBeforeModelConstruction': True,\n"
                + "    }\n"
                + "    write_json(Path(args.reproducibility_file), evidence)\n"
                + "    conf = Config2D(n_rays=args.n_rays, grid=(args.grid, args.grid), use_gpu=args.use_gpu,\n"
                + "                    train_epochs=args.epochs, train_batch_size=args.batch_size,\n"
                + "                    train_learning_rate=args.learning_rate,\n"
                + "                    train_steps_per_epoch=args.steps_per_epoch)\n"
                + "    model = StarDist2D(conf, name=args.model_name, basedir=str(models_dir))\n"
                + "    print('FLASH_EPOCH 0/{}'.format(args.epochs), flush=True)\n"
                + "    if validation_enabled:\n"
                + "        model.train(X_train, Y_train, validation_data=(X_val, Y_val),\n"
                + "                    epochs=args.epochs, steps_per_epoch=args.steps_per_epoch)\n"
                + "    else:\n"
                + "        print('FLASH_VALIDATION_DISABLED=' + split.get('validationDisabledReason', 'not requested'), flush=True)\n"
                + "        model.train(X_train, Y_train, epochs=args.epochs, steps_per_epoch=args.steps_per_epoch)\n"
                + "    print('FLASH_EPOCH {}/{}'.format(args.epochs, args.epochs), flush=True)\n"
                + "    if validation_enabled:\n"
                + "        model.optimize_thresholds(X_val, Y_val)\n"
                + "    exported = model.export_TF()\n"
                + "    source = Path(str(exported)) if exported is not None else newest_zip(models_dir)\n"
                + "    if source is None or not source.is_file():\n"
                + "        source = newest_zip(models_dir)\n"
                + "    if source is None or not source.is_file():\n"
                + "        raise RuntimeError('model.export_TF() did not produce a zip file')\n"
                + "    output_zip.parent.mkdir(parents=True, exist_ok=True)\n"
                + "    if source.resolve() != output_zip.resolve():\n"
                + "        shutil.copy2(str(source), str(output_zip))\n"
                + "    print('FLASH_EXPORT_ZIP=' + str(output_zip), flush=True)\n"
                + "\n"
                + "\n"
                + "if __name__ == '__main__':\n"
                + "    main()\n";
    }

    private static String failureMessage(String label,
                                         int exitCode,
                                         Path logFile,
                                         CellposeLocalTrainingService.DiagnosticSnapshot diagnostics) {
        StringBuilder message = new StringBuilder(label)
                .append(" failed with exit code ")
                .append(exitCode)
                .append(". Log: ")
                .append(logFile);
        appendTail(message, "stderr", diagnostics == null
                ? Collections.<String>emptyList() : diagnostics.stderrTail);
        appendTail(message, "stdout", diagnostics == null
                ? Collections.<String>emptyList() : diagnostics.stdoutTail);
        return message.toString();
    }

    private static IOException trainingIoFailure(String label,
                                                 IOException cause,
                                                 Path logFile,
                                                 CellposeLocalTrainingService.DiagnosticSnapshot diagnostics) {
        StringBuilder message = new StringBuilder(label)
                .append(" failed: ")
                .append(cause == null ? "unknown I/O failure" : cause.getMessage())
                .append(". Log: ")
                .append(logFile);
        appendTail(message, "stderr", diagnostics == null
                ? Collections.<String>emptyList() : diagnostics.stderrTail);
        appendTail(message, "stdout", diagnostics == null
                ? Collections.<String>emptyList() : diagnostics.stdoutTail);
        return new IOException(message.toString(), cause);
    }

    private static String trainingInterruptionMessage(String label,
                                                      Path logFile,
                                                      CellposeLocalTrainingService.DiagnosticSnapshot diagnostics) {
        StringBuilder message = new StringBuilder(label)
                .append(" interrupted. Log: ").append(logFile);
        appendTail(message, "stderr", diagnostics == null
                ? Collections.<String>emptyList() : diagnostics.stderrTail);
        appendTail(message, "stdout", diagnostics == null
                ? Collections.<String>emptyList() : diagnostics.stdoutTail);
        return message.toString();
    }

    private static void appendTail(StringBuilder message, String name, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        message.append(". Last ").append(name).append(": ");
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) message.append(" | ");
            message.append(lines.get(i));
        }
    }

    private static Path parseExportZip(Path datasetDir, String line) {
        String marker = "FLASH_EXPORT_ZIP=";
        String text = line == null ? "" : line.trim();
        int index = text.indexOf(marker);
        if (index < 0) {
            return null;
        }
        String value = text.substring(index + marker.length()).trim();
        if (value.isEmpty()) {
            return null;
        }
        Path path = Paths.get(value);
        if (!path.isAbsolute()) {
            path = datasetDir.resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    private static String safeModelName(String value) {
        String input = clean(value).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        boolean separator = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                out.append(c);
                separator = false;
            } else if (c == '_' || c == '-') {
                if (out.length() > 0) {
                    out.append(c);
                    separator = false;
                }
            } else if (!separator && out.length() > 0) {
                out.append('_');
                separator = true;
            }
        }
        while (out.length() > 0
                && (out.charAt(out.length() - 1) == '_'
                || out.charAt(out.length() - 1) == '-')) {
            out.deleteCharAt(out.length() - 1);
        }
        return out.length() == 0 ? "flash_stardist_model" : out.toString();
    }

    private static String displayCommand(List<String> command) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < command.size(); i++) {
            if (i > 0) out.append(' ');
            out.append(quoteForDisplay(command.get(i)));
        }
        return out.toString();
    }

    private static String quoteForDisplay(String value) {
        String text = value == null ? "" : value;
        if (text.indexOf(' ') < 0 && text.indexOf('\t') < 0 && text.indexOf('"') < 0) {
            return text;
        }
        return "\"" + text.replace("\"", "\\\"") + "\"";
    }

    private static String cleanOrDefault(String value, String fallback) {
        String text = clean(value);
        return text.isEmpty() ? fallback : text;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static int intProperty(String name, int fallback, int min) {
        String value = System.getProperty(name, "").trim();
        if (value.isEmpty()) return fallback;
        try {
            return Math.max(min, Integer.parseInt(value));
        } catch (NumberFormatException e) {
            warnPropertyFallback(name, value, String.valueOf(fallback));
            return fallback;
        }
    }

    private static double doubleProperty(String name, double fallback, double min) {
        String value = System.getProperty(name, "").trim();
        if (value.isEmpty()) return fallback;
        try {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed)) {
                warnPropertyFallback(name, value, String.valueOf(fallback));
                return fallback;
            }
            return Math.max(min, parsed);
        } catch (NumberFormatException e) {
            warnPropertyFallback(name, value, String.valueOf(fallback));
            return fallback;
        }
    }

    private static boolean booleanProperty(String name, boolean fallback) {
        String value = System.getProperty(name, "").trim();
        if (value.isEmpty()) return fallback;
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        warnPropertyFallback(name, value, String.valueOf(fallback));
        return fallback;
    }

    private static void warnPropertyFallback(String name, String value, String fallback) {
        System.err.println("[FLASH] Invalid system property " + name + "='" + value
                + "'; using " + fallback + ".");
    }

    private static double requireFinite(String name, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite.");
        }
        return value;
    }

    public interface ProgressSink {
        void update(double fraction, String message);
    }

    public interface LineConsumer {
        void accept(String line) throws IOException;
    }

    public interface ProcessRunner {
        ProcessResult run(ProcessSpec spec,
                          LineConsumer stdout,
                          LineConsumer stderr) throws IOException, InterruptedException;
    }

    public static final ProgressSink NO_PROGRESS = new ProgressSink() {
        @Override public void update(double fraction, String message) {
        }
    };

    public static final class Config {
        public final boolean enabled;
        public final String pythonExecutable;
        public final String condaEnvironment;
        public final String condaExecutable;
        public final int epochs;
        public final int batchSize;
        public final int stepsPerEpoch;
        public final double learningRate;
        public final int nRays;
        public final int grid;
        public final double validationFraction;
        public final int seed;
        public final boolean useGpu;
        public final int timeoutSeconds;
        public final int stallTimeoutSeconds;

        public Config(boolean enabled,
                      String pythonExecutable,
                      String condaEnvironment,
                      String condaExecutable,
                      int epochs,
                      int batchSize,
                      int stepsPerEpoch,
                      double learningRate,
                      int nRays,
                      int grid,
                      double validationFraction,
                      int seed,
                      boolean useGpu) {
            this(enabled, pythonExecutable, condaEnvironment, condaExecutable,
                    epochs, batchSize, stepsPerEpoch, learningRate, nRays, grid,
                    validationFraction, seed, useGpu,
                    intProperty(TIMEOUT_SECONDS_PROPERTY, 6 * 60 * 60, 0),
                    intProperty(STALL_TIMEOUT_SECONDS_PROPERTY, 30 * 60, 0));
        }

        public Config(boolean enabled,
                      String pythonExecutable,
                      String condaEnvironment,
                      String condaExecutable,
                      int epochs,
                      int batchSize,
                      int stepsPerEpoch,
                      double learningRate,
                      int nRays,
                      int grid,
                      double validationFraction,
                      int seed,
                      boolean useGpu,
                      int timeoutSeconds,
                      int stallTimeoutSeconds) {
            this.enabled = enabled;
            this.pythonExecutable = cleanOrDefault(pythonExecutable, "python");
            this.condaEnvironment = clean(condaEnvironment);
            this.condaExecutable = cleanOrDefault(condaExecutable, "conda");
            this.epochs = Math.max(1, epochs);
            this.batchSize = Math.max(1, batchSize);
            this.stepsPerEpoch = Math.max(1, stepsPerEpoch);
            this.learningRate = Math.max(0.0,
                    requireFinite("StarDist learning rate", learningRate));
            this.nRays = Math.max(8, nRays);
            this.grid = Math.max(1, grid);
            this.validationFraction = Math.max(0.0, Math.min(0.9,
                    requireFinite("StarDist validation fraction", validationFraction)));
            this.seed = seed;
            this.useGpu = useGpu;
            this.timeoutSeconds = Math.max(0, timeoutSeconds);
            this.stallTimeoutSeconds = Math.max(0, stallTimeoutSeconds);
        }

        public static Config fromSystemProperties() {
            return new Config(
                    booleanProperty(LOCAL_ENABLED_PROPERTY, false),
                    System.getProperty(PYTHON_PROPERTY, "python"),
                    System.getProperty(CONDA_ENV_PROPERTY, ""),
                    System.getProperty(CONDA_EXECUTABLE_PROPERTY, "conda"),
                    intProperty(EPOCHS_PROPERTY, 100, 1),
                    intProperty(BATCH_SIZE_PROPERTY, 1, 1),
                    intProperty(STEPS_PER_EPOCH_PROPERTY, 100, 1),
                    doubleProperty(LEARNING_RATE_PROPERTY, 0.0003, 0.0),
                    32,
                    2,
                    doubleProperty(VALIDATION_FRACTION_PROPERTY, 0.2, 0.0),
                    intProperty(SEED_PROPERTY, 42, Integer.MIN_VALUE),
                    booleanProperty(USE_GPU_PROPERTY, false),
                    intProperty(TIMEOUT_SECONDS_PROPERTY, 6 * 60 * 60, 0),
                    intProperty(STALL_TIMEOUT_SECONDS_PROPERTY, 30 * 60, 0));
        }
    }

    public static final class TrainingArtifacts {
        public final Path datasetDir;
        public final Path scriptFile;
        public final Path commandFile;
        public final Path logFile;
        public final Path outputZip;
        public final Path splitManifest;
        public final Path reproducibilityFile;
        public final Map<String, String> environment;
        public final List<String> command;
        public final String scriptText;

        TrainingArtifacts(Path datasetDir,
                          Path scriptFile,
                          Path commandFile,
                          Path logFile,
                          Path outputZip,
                          Path splitManifest,
                          Path reproducibilityFile,
                          Map<String, String> environment,
                          List<String> command,
                          String scriptText) {
            this.datasetDir = datasetDir;
            this.scriptFile = scriptFile;
            this.commandFile = commandFile;
            this.logFile = logFile;
            this.outputZip = outputZip;
            this.splitManifest = splitManifest;
            this.reproducibilityFile = reproducibilityFile;
            this.environment = Collections.unmodifiableMap(
                    new LinkedHashMap<String, String>(environment));
            this.command = Collections.unmodifiableList(new ArrayList<String>(command));
            this.scriptText = scriptText == null ? "" : scriptText;
        }
    }

    public static final class TrainingResult {
        public final Path outputZip;
        public final Path logFile;
        public final Path scriptFile;
        public final Path commandFile;
        public final int exitCode;

        public TrainingResult(Path outputZip,
                              Path logFile,
                              Path scriptFile,
                              Path commandFile,
                              int exitCode) {
            this.outputZip = outputZip;
            this.logFile = logFile;
            this.scriptFile = scriptFile;
            this.commandFile = commandFile;
            this.exitCode = exitCode;
        }
    }

    public static final class ProcessSpec {
        public final List<String> command;
        public final Path workingDirectory;
        public final int timeoutSeconds;
        public final int stallTimeoutSeconds;
        public final Map<String, String> environment;

        ProcessSpec(List<String> command, Path workingDirectory) {
            this(command, workingDirectory, 0, 0,
                    Collections.<String, String>emptyMap());
        }

        ProcessSpec(List<String> command,
                    Path workingDirectory,
                    int timeoutSeconds,
                    int stallTimeoutSeconds) {
            this(command, workingDirectory, timeoutSeconds, stallTimeoutSeconds,
                    Collections.<String, String>emptyMap());
        }

        ProcessSpec(List<String> command,
                    Path workingDirectory,
                    int timeoutSeconds,
                    int stallTimeoutSeconds,
                    Map<String, String> environment) {
            this.command = Collections.unmodifiableList(new ArrayList<String>(
                    command == null ? Collections.<String>emptyList() : command));
            this.workingDirectory = workingDirectory;
            this.timeoutSeconds = Math.max(0, timeoutSeconds);
            this.stallTimeoutSeconds = Math.max(0, stallTimeoutSeconds);
            this.environment = Collections.unmodifiableMap(
                    new LinkedHashMap<String, String>(environment == null
                            ? Collections.<String, String>emptyMap() : environment));
        }
    }

    public static final class ProcessResult {
        public final int exitCode;

        public ProcessResult(int exitCode) {
            this.exitCode = exitCode;
        }
    }

    private static final class LoggingLineConsumer implements LineConsumer {
        private final Path datasetDir;
        private final Path[] reportedZip;
        private final ProgressSink progress;

        LoggingLineConsumer(Path datasetDir,
                            Path[] reportedZip,
                            ProgressSink progress) {
            this.datasetDir = datasetDir;
            this.reportedZip = reportedZip;
            this.progress = progress;
        }

        @Override public void accept(String line) {
            Path zip = parseExportZip(datasetDir, line);
            if (zip != null) {
                reportedZip[0] = zip;
            }
            StarDistTrainingProgressParser.Progress parsed =
                    StarDistTrainingProgressParser.parse(line);
            if (parsed != null) {
                progress.update(0.05 + (0.90 * parsed.fraction), parsed.message);
            }
        }
    }

    private static final class DiagnosticAdapter implements LineConsumer {
        private final CellposeLocalTrainingService.LineConsumer diagnostics;
        private final LineConsumer delegate;

        DiagnosticAdapter(CellposeLocalTrainingService.LineConsumer diagnostics,
                          LineConsumer delegate) {
            this.diagnostics = diagnostics;
            this.delegate = delegate;
        }

        @Override public void accept(String line) throws IOException {
            diagnostics.accept(line);
            if (delegate != null) {
                delegate.accept(line);
            }
        }
    }

    static final class DefaultProcessRunner implements ProcessRunner {
        @Override public ProcessResult run(ProcessSpec spec,
                                           LineConsumer stdout,
                                           LineConsumer stderr) throws IOException, InterruptedException {
            ProcessBuilder builder = new ProcessBuilder(spec.command);
            if (spec.workingDirectory != null) {
                builder.directory(spec.workingDirectory.toFile());
            }
            builder.environment().putAll(spec.environment);
            Process process = builder.start();
            CellposeLocalTrainingService.ManagedProcessResult result =
                    CellposeLocalTrainingService.runManagedProcess(process,
                            "Local StarDist training", spec.timeoutSeconds,
                            spec.stallTimeoutSeconds,
                            new CellposeAdapter(stdout), new CellposeAdapter(stderr));
            return new ProcessResult(result.exitCode);
        }
    }

    private static final class CellposeAdapter
            implements CellposeLocalTrainingService.LineConsumer {
        private final LineConsumer delegate;

        CellposeAdapter(LineConsumer delegate) {
            this.delegate = delegate;
        }

        @Override public void accept(String line) throws IOException {
            if (delegate != null) {
                delegate.accept(line);
            }
        }
    }
}
