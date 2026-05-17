package flash.pipeline.click.training.stardist;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

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
    public static final String TIMEOUT_SECONDS_PROPERTY =
            "flash.stardist.training.timeoutSeconds";
    public static final String STALL_TIMEOUT_SECONDS_PROPERTY =
            "flash.stardist.training.stallTimeoutSeconds";

    private static final String SCRIPT_FILENAME = "train_stardist_flash.py";
    private static final String COMMAND_FILENAME = "train_stardist_command.txt";
    private static final String LOG_FILENAME = "stardist_training.log";
    private static final String OUTPUT_ZIP_FILENAME = "TF_SavedModel.zip";
    private static final String MODELS_DIR = "stardist_model";

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
        final IOException[] logFailure = new IOException[] {null};
        final Object logLock = new Object();
        final StreamTail stdoutTail = new StreamTail();
        final StreamTail stderrTail = new StreamTail();
        ProgressSink safeProgress = progress == null ? NO_PROGRESS : progress;
        safeProgress.update(0.0, "Starting local StarDist training...");

        BufferedWriter writer = Files.newBufferedWriter(
                artifacts.logFile,
                StandardCharsets.UTF_8);
        try {
            writeLog(writer, logLock, logFailure, "FLASH",
                    "Command: " + displayCommand(artifacts.command));
            ProcessSpec spec = new ProcessSpec(artifacts.command, artifacts.datasetDir,
                    config.timeoutSeconds, config.stallTimeoutSeconds);
            ProcessResult result = runner.run(spec,
                    new LoggingLineConsumer(writer, logLock, logFailure, "STDOUT",
                            artifacts.datasetDir, reportedZip, safeProgress, stdoutTail),
                    new LoggingLineConsumer(writer, logLock, logFailure, "STDERR",
                            artifacts.datasetDir, reportedZip, safeProgress, stderrTail));
            if (logFailure[0] != null) {
                throw logFailure[0];
            }
            int exitCode = result == null ? -1 : result.exitCode;
            if (exitCode != 0) {
                throw new IOException(failureMessage("Local StarDist training",
                        exitCode, artifacts.logFile, stdoutTail, stderrTail));
            }
        } finally {
            writer.close();
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
        Set<String> missingLabels = new HashSet<String>(rawNames);
        missingLabels.removeAll(labelNames);
        if (!missingLabels.isEmpty()) {
            throw new IOException("StarDist training dataset is missing label TIFFs in "
                    + labelsDir + ": " + missingLabels);
        }
        Set<String> extraLabels = new HashSet<String>(labelNames);
        extraLabels.removeAll(rawNames);
        if (!extraLabels.isEmpty()) {
            throw new IOException("StarDist training dataset has label TIFFs without raw pairs in "
                    + labelsDir + ": " + extraLabels);
        }
    }

    private static Set<String> tiffNames(Path dir) throws IOException {
        Set<String> out = new HashSet<String>();
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

    private static void validateStarDistZip(Path zipPath) throws IOException {
        Path file = zipPath == null ? null : zipPath.toAbsolutePath().normalize();
        if (file == null || !Files.isRegularFile(file)) {
            throw new IOException("StarDist model zip does not exist: " + file);
        }
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new IOException("StarDist model output must be a .zip file: " + file);
        }
        StarDistZipScan scan;
        try (ZipFile zip = new ZipFile(file.toFile())) {
            scan = scanStarDistZip(zip);
        } catch (IOException e) {
            throw new IOException("StarDist model zip could not be read: "
                    + file + ": " + e.getMessage(), e);
        }
        if (!scan.hasFileEntry) {
            throw new IOException("StarDist model zip is empty: " + file);
        }
        if (scan.marker == null) {
            throw new IOException("Not a StarDist / CSBDeep SavedModel: missing saved_model.pb "
                    + "(or config.json + thresholds.json): " + file);
        }
    }

    private static StarDistZipScan scanStarDistZip(ZipFile zip) {
        boolean hasFileEntry = false;
        boolean hasTopLevelSavedModel = false;
        boolean hasSingleDirectorySavedModel = false;
        boolean hasModelTfSavedModel = false;
        Set<String> configParents = new HashSet<String>();
        Set<String> thresholdsParents = new HashSet<String>();

        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) {
                continue;
            }
            String entryName = normalizedZipEntryName(entry.getName());
            if (entryName.isEmpty()) {
                continue;
            }
            hasFileEntry = true;
            if ("saved_model.pb".equals(entryName)) {
                hasTopLevelSavedModel = true;
            }
            if (isSingleDirectorySavedModel(entryName)) {
                hasSingleDirectorySavedModel = true;
            }
            if (isModelTfSavedModel(entryName)) {
                hasModelTfSavedModel = true;
            }
            String fileName = zipFileName(entryName);
            String parent = zipParent(entryName);
            if ("config.json".equals(fileName)) {
                configParents.add(parent);
            } else if ("thresholds.json".equals(fileName)) {
                thresholdsParents.add(parent);
            }
        }

        String marker = null;
        if (hasTopLevelSavedModel) {
            marker = "top-level saved_model.pb";
        } else if (hasModelTfSavedModel) {
            marker = "model.tf SavedModel layout";
        } else if (hasSingleDirectorySavedModel) {
            marker = "single-directory saved_model.pb";
        } else if (hasMatchingCsbDeepMetadata(configParents, thresholdsParents)) {
            marker = "CSBDeep config.json + thresholds.json";
        }
        return new StarDistZipScan(hasFileEntry, marker);
    }

    private static String normalizedZipEntryName(String rawName) {
        String name = rawName == null ? "" : rawName.replace('\\', '/').trim();
        while (name.startsWith("/")) {
            name = name.substring(1);
        }
        while (name.startsWith("./")) {
            name = name.substring(2);
        }
        while (name.indexOf("//") >= 0) {
            name = name.replace("//", "/");
        }
        return name;
    }

    private static boolean isSingleDirectorySavedModel(String name) {
        int firstSlash = name.indexOf('/');
        return firstSlash > 0
                && firstSlash == name.lastIndexOf('/')
                && name.endsWith("/saved_model.pb");
    }

    private static boolean isModelTfSavedModel(String name) {
        return "model.tf/saved_model.pb".equals(name)
                || name.endsWith("/model.tf/saved_model.pb");
    }

    private static boolean hasMatchingCsbDeepMetadata(Set<String> configParents,
                                                      Set<String> thresholdsParents) {
        for (String parent : configParents) {
            if (thresholdsParents.contains(parent)) {
                return true;
            }
        }
        return false;
    }

    private static String zipParent(String name) {
        int slash = name.lastIndexOf('/');
        return slash < 0 ? "" : name.substring(0, slash);
    }

    private static String zipFileName(String name) {
        int slash = name.lastIndexOf('/');
        return slash < 0 ? name : name.substring(slash + 1);
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
        Files.createDirectories(dir);
        Path scriptFile = dir.resolve(SCRIPT_FILENAME);
        Path commandFile = dir.resolve(COMMAND_FILENAME);
        Path logFile = dir.resolve(LOG_FILENAME);
        Path outputZip = dir.resolve(OUTPUT_ZIP_FILENAME);
        Path modelsDir = dir.resolve(MODELS_DIR);
        String cleanName = safeModelName(modelName);

        String script = buildTrainingScript();
        Files.write(scriptFile, script.getBytes(StandardCharsets.UTF_8));
        List<String> command = buildCommand(scriptFile, dir, outputZip,
                modelsDir, cleanName, safeConfig);
        Files.write(commandFile,
                Collections.singletonList(displayCommand(command)),
                StandardCharsets.UTF_8);
        return new TrainingArtifacts(dir, scriptFile, commandFile, logFile,
                outputZip, command, script);
    }

    public static List<String> buildCommand(Path scriptFile,
                                            Path datasetDir,
                                            Path outputZip,
                                            Path modelsDir,
                                            String modelName,
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

    public static String buildTrainingScript() {
        return ""
                + "import argparse\n"
                + "import shutil\n"
                + "from pathlib import Path\n"
                + "\n"
                + "import numpy as np\n"
                + "from tifffile import imread\n"
                + "from csbdeep.utils import normalize\n"
                + "from stardist.models import Config2D, StarDist2D\n"
                + "\n"
                + "\n"
                + "def parse_args():\n"
                + "    parser = argparse.ArgumentParser(description='FLASH StarDist 2D training')\n"
                + "    parser.add_argument('--dataset', required=True)\n"
                + "    parser.add_argument('--output-zip', required=True)\n"
                + "    parser.add_argument('--models-dir', required=True)\n"
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
                + "def squeeze_2d(array):\n"
                + "    squeezed = np.squeeze(array)\n"
                + "    if squeezed.ndim != 2:\n"
                + "        raise RuntimeError('Expected 2D image plane after squeezing, got shape {}'.format(squeezed.shape))\n"
                + "    return squeezed\n"
                + "\n"
                + "\n"
                + "def load_pairs(dataset):\n"
                + "    raw_dir = dataset / 'raw'\n"
                + "    label_dir = dataset / 'labels'\n"
                + "    names = sorted([p.name for p in raw_dir.glob('*.tif') if (label_dir / p.name).is_file()])\n"
                + "    names += sorted([p.name for p in raw_dir.glob('*.tiff') if (label_dir / p.name).is_file()])\n"
                + "    if not names:\n"
                + "        raise RuntimeError('No matching raw/label TIFF pairs found in {}'.format(dataset))\n"
                + "    X = []\n"
                + "    Y = []\n"
                + "    for name in names:\n"
                + "        raw = squeeze_2d(imread(str(raw_dir / name)))\n"
                + "        labels = squeeze_2d(imread(str(label_dir / name))).astype(np.uint16, copy=False)\n"
                + "        X.append(normalize(raw, 1, 99.8, axis=(0, 1)))\n"
                + "        Y.append(labels)\n"
                + "    return X, Y\n"
                + "\n"
                + "\n"
                + "def split_train_validation(X, Y, fraction, seed):\n"
                + "    n = len(X)\n"
                + "    indices = np.arange(n)\n"
                + "    rng = np.random.RandomState(seed)\n"
                + "    rng.shuffle(indices)\n"
                + "    if n <= 1:\n"
                + "        return X, Y, X, Y\n"
                + "    n_val = max(1, int(round(n * max(0.0, min(0.9, fraction)))))\n"
                + "    val = set(indices[:n_val])\n"
                + "    X_train = [x for i, x in enumerate(X) if i not in val]\n"
                + "    Y_train = [y for i, y in enumerate(Y) if i not in val]\n"
                + "    X_val = [x for i, x in enumerate(X) if i in val]\n"
                + "    Y_val = [y for i, y in enumerate(Y) if i in val]\n"
                + "    if not X_train:\n"
                + "        X_train, Y_train = X, Y\n"
                + "    if not X_val:\n"
                + "        X_val, Y_val = X_train, Y_train\n"
                + "    return X_train, Y_train, X_val, Y_val\n"
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
                + "    X, Y = load_pairs(dataset)\n"
                + "    X_train, Y_train, X_val, Y_val = split_train_validation(X, Y, args.validation_fraction, args.seed)\n"
                + "    conf = Config2D(n_rays=args.n_rays, grid=(args.grid, args.grid), use_gpu=args.use_gpu,\n"
                + "                    train_epochs=args.epochs, train_batch_size=args.batch_size,\n"
                + "                    train_learning_rate=args.learning_rate,\n"
                + "                    train_steps_per_epoch=args.steps_per_epoch)\n"
                + "    model = StarDist2D(conf, name=args.model_name, basedir=str(models_dir))\n"
                + "    print('FLASH_EPOCH 0/{}'.format(args.epochs), flush=True)\n"
                + "    model.train(X_train, Y_train, validation_data=(X_val, Y_val),\n"
                + "                epochs=args.epochs, steps_per_epoch=args.steps_per_epoch)\n"
                + "    print('FLASH_EPOCH {}/{}'.format(args.epochs, args.epochs), flush=True)\n"
                + "    model.optimize_thresholds(X_val, Y_val)\n"
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

    private static void writeLog(BufferedWriter writer,
                                 Object lock,
                                 IOException[] failure,
                                 String stream,
                                 String line) {
        synchronized (lock) {
            try {
                writer.write("[" + stream + "] " + (line == null ? "" : line));
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                failure[0] = e;
            }
        }
    }

    private static String failureMessage(String label,
                                         int exitCode,
                                         Path logFile,
                                         StreamTail stdout,
                                         StreamTail stderr) {
        StringBuilder message = new StringBuilder(label)
                .append(" failed with exit code ")
                .append(exitCode)
                .append(". Log: ")
                .append(logFile);
        appendTail(message, "stderr", stderr);
        appendTail(message, "stdout", stdout);
        return message.toString();
    }

    private static void appendTail(StringBuilder message, String name, StreamTail tail) {
        List<String> lines = tail == null ? Collections.<String>emptyList() : tail.lines();
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
            return fallback;
        }
    }

    private static double doubleProperty(String name, double fallback, double min) {
        String value = System.getProperty(name, "").trim();
        if (value.isEmpty()) return fallback;
        try {
            return Math.max(min, Double.parseDouble(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public interface ProgressSink {
        void update(double fraction, String message);
    }

    public interface LineConsumer {
        void accept(String line);
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
            this.learningRate = Math.max(0.0, learningRate);
            this.nRays = Math.max(8, nRays);
            this.grid = Math.max(1, grid);
            this.validationFraction = Math.max(0.0, Math.min(0.9, validationFraction));
            this.seed = seed;
            this.useGpu = useGpu;
            this.timeoutSeconds = Math.max(0, timeoutSeconds);
            this.stallTimeoutSeconds = Math.max(0, stallTimeoutSeconds);
        }

        public static Config fromSystemProperties() {
            return new Config(
                    Boolean.getBoolean(LOCAL_ENABLED_PROPERTY),
                    System.getProperty(PYTHON_PROPERTY, "python"),
                    System.getProperty(CONDA_ENV_PROPERTY, ""),
                    System.getProperty(CONDA_EXECUTABLE_PROPERTY, "conda"),
                    intProperty(EPOCHS_PROPERTY, 100, 1),
                    intProperty(BATCH_SIZE_PROPERTY, 1, 1),
                    intProperty(STEPS_PER_EPOCH_PROPERTY, 100, 1),
                    doubleProperty(LEARNING_RATE_PROPERTY, 0.0003, 0.0),
                    32,
                    2,
                    0.2,
                    42,
                    Boolean.getBoolean(USE_GPU_PROPERTY),
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
        public final List<String> command;
        public final String scriptText;

        TrainingArtifacts(Path datasetDir,
                          Path scriptFile,
                          Path commandFile,
                          Path logFile,
                          Path outputZip,
                          List<String> command,
                          String scriptText) {
            this.datasetDir = datasetDir;
            this.scriptFile = scriptFile;
            this.commandFile = commandFile;
            this.logFile = logFile;
            this.outputZip = outputZip;
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

        ProcessSpec(List<String> command, Path workingDirectory) {
            this(command, workingDirectory, 0, 0);
        }

        ProcessSpec(List<String> command,
                    Path workingDirectory,
                    int timeoutSeconds,
                    int stallTimeoutSeconds) {
            this.command = Collections.unmodifiableList(new ArrayList<String>(
                    command == null ? Collections.<String>emptyList() : command));
            this.workingDirectory = workingDirectory;
            this.timeoutSeconds = Math.max(0, timeoutSeconds);
            this.stallTimeoutSeconds = Math.max(0, stallTimeoutSeconds);
        }
    }

    public static final class ProcessResult {
        public final int exitCode;

        public ProcessResult(int exitCode) {
            this.exitCode = exitCode;
        }
    }

    private static final class LoggingLineConsumer implements LineConsumer {
        private final BufferedWriter writer;
        private final Object logLock;
        private final IOException[] logFailure;
        private final String stream;
        private final Path datasetDir;
        private final Path[] reportedZip;
        private final ProgressSink progress;
        private final StreamTail tail;

        LoggingLineConsumer(BufferedWriter writer,
                            Object logLock,
                            IOException[] logFailure,
                            String stream,
                            Path datasetDir,
                            Path[] reportedZip,
                            ProgressSink progress,
                            StreamTail tail) {
            this.writer = writer;
            this.logLock = logLock;
            this.logFailure = logFailure;
            this.stream = stream;
            this.datasetDir = datasetDir;
            this.reportedZip = reportedZip;
            this.progress = progress;
            this.tail = tail;
        }

        @Override public void accept(String line) {
            if (tail != null) {
                tail.add(line);
            }
            writeLog(writer, logLock, logFailure, stream, line);
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

    private static final class StarDistZipScan {
        final boolean hasFileEntry;
        final String marker;

        StarDistZipScan(boolean hasFileEntry, String marker) {
            this.hasFileEntry = hasFileEntry;
            this.marker = marker;
        }
    }

    private static final class StreamTail {
        private static final int MAX_LINES = 12;
        private final Deque<String> lines = new ArrayDeque<String>();

        synchronized void add(String line) {
            String value = line == null ? "" : line.trim();
            if (value.length() > 500) {
                value = value.substring(0, 500) + "...";
            }
            lines.addLast(value);
            while (lines.size() > MAX_LINES) {
                lines.removeFirst();
            }
        }

        synchronized List<String> lines() {
            return new ArrayList<String>(lines);
        }
    }

    private static final class DefaultProcessRunner implements ProcessRunner {
        @Override public ProcessResult run(ProcessSpec spec,
                                           LineConsumer stdout,
                                           LineConsumer stderr) throws IOException, InterruptedException {
            ProcessBuilder builder = new ProcessBuilder(spec.command);
            if (spec.workingDirectory != null) {
                builder.directory(spec.workingDirectory.toFile());
            }
            final Process process = builder.start();
            final AtomicLong lastOutputMs = new AtomicLong(System.currentTimeMillis());
            Thread outThread = streamThread(process, true, stdout, lastOutputMs);
            Thread errThread = streamThread(process, false, stderr, lastOutputMs);
            outThread.start();
            errThread.start();
            int exit;
            try {
                exit = waitForProcess(process, spec, lastOutputMs);
            } catch (InterruptedException e) {
                terminateProcess(process);
                throw e;
            } finally {
                outThread.join();
                errThread.join();
            }
            return new ProcessResult(exit);
        }

        private static Thread streamThread(final Process process,
                                           final boolean stdout,
                                           final LineConsumer consumer,
                                           final AtomicLong lastOutputMs) {
            return new Thread(new Runnable() {
                @Override public void run() {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(
                            stdout ? process.getInputStream() : process.getErrorStream(),
                            StandardCharsets.UTF_8));
                    try {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            lastOutputMs.set(System.currentTimeMillis());
                            if (consumer != null) {
                                consumer.accept(line);
                            }
                        }
                    } catch (IOException ignored) {
                    } finally {
                        try {
                            reader.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
            }, stdout ? "flash-stardist-train-stdout" : "flash-stardist-train-stderr");
        }

        private static int waitForProcess(Process process,
                                          ProcessSpec spec,
                                          AtomicLong lastOutputMs) throws IOException, InterruptedException {
            long started = System.currentTimeMillis();
            long maxRuntimeMs = spec.timeoutSeconds <= 0
                    ? 0L
                    : TimeUnit.SECONDS.toMillis(spec.timeoutSeconds);
            long stallMs = spec.stallTimeoutSeconds <= 0
                    ? 0L
                    : TimeUnit.SECONDS.toMillis(spec.stallTimeoutSeconds);
            while (true) {
                if (process.waitFor(1, TimeUnit.SECONDS)) {
                    return process.exitValue();
                }
                long now = System.currentTimeMillis();
                if (maxRuntimeMs > 0L && now - started > maxRuntimeMs) {
                    terminateProcess(process);
                    throw new IOException("Local StarDist training timed out after "
                            + spec.timeoutSeconds + " seconds.");
                }
                if (stallMs > 0L && now - lastOutputMs.get() > stallMs) {
                    terminateProcess(process);
                    throw new IOException("Local StarDist training produced no output for "
                            + spec.stallTimeoutSeconds + " seconds.");
                }
            }
        }

        private static void terminateProcess(Process process) throws InterruptedException {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }
}
