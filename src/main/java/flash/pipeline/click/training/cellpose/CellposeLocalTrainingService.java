package flash.pipeline.click.training.cellpose;

import flash.pipeline.cellpose.CellposeRuntime;
import flash.pipeline.intelligence.MiniJson;
import flash.pipeline.ui.wizard.JsonIO;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Hidden local Cellpose 3 training runner for datasets exported by FLASH.
 */
public final class CellposeLocalTrainingService {
    public static final String LOCAL_ENABLED_PROPERTY =
            "flash.cellpose.training.local.enabled";
    public static final String PYTHON_PROPERTY =
            "flash.cellpose.training.python";
    public static final String EPOCHS_PROPERTY =
            "flash.cellpose.training.epochs";
    public static final String BATCH_SIZE_PROPERTY =
            "flash.cellpose.training.batchSize";
    public static final String LEARNING_RATE_PROPERTY =
            "flash.cellpose.training.learningRate";
    public static final String WEIGHT_DECAY_PROPERTY =
            "flash.cellpose.training.weightDecay";
    public static final String SEED_PROPERTY =
            "flash.cellpose.training.seed";
    public static final String TIMEOUT_SECONDS_PROPERTY =
            "flash.cellpose.training.timeoutSeconds";
    public static final String STALL_TIMEOUT_SECONDS_PROPERTY =
            "flash.cellpose.training.stallTimeoutSeconds";

    static final String COMMAND_FILENAME = "train_command.txt";
    static final String LOG_FILENAME = "cellpose_training.log";
    static final String MODELS_DIR = "models";
    static final String REPRODUCIBILITY_FILENAME = "training_reproducibility.json";
    private static final String REPRODUCIBILITY_DIR = ".flash-repro";
    private static final String SITE_CUSTOMIZE_FILENAME = "sitecustomize.py";
    private static final String TRAINING_WRAPPER_PREFIX = "train_cellpose_flash-";
    private static final String ARTIFACT_MARKER_PREFIX = ".flash-cellpose-artifact-";
    private static final int ARTIFACT_MARKER_VERSION = 1;
    private static final String FALLBACK_BASE_MODEL = "cyto3";
    private static final AtomicLong LOG_SEQUENCE = new AtomicLong();
    private static final AtomicLong ARTIFACT_SEQUENCE = new AtomicLong();

    private final Config config;
    private final ProcessRunner runner;

    public CellposeLocalTrainingService() {
        this(Config.fromSystemProperties(), new DefaultProcessRunner());
    }

    public CellposeLocalTrainingService(Config config, ProcessRunner runner) {
        this.config = config == null ? Config.fromSystemProperties() : config;
        this.runner = runner == null ? new DefaultProcessRunner() : runner;
    }

    public boolean isEnabled() {
        return config.enabled;
    }

    public TrainingResult train(CellposeDatasetPackager.PackagingResult packageResult,
                                String modelName,
                                ProgressSink progress) throws IOException, InterruptedException {
        if (packageResult == null || packageResult.outputDir == null) {
            throw new IOException("Cellpose training dataset is not available.");
        }
        return train(packageResult.outputDir, packageResult.trainCommandFile, progress);
    }

    public TrainingResult train(Path datasetDir,
                                Path trainCommandFile,
                                ProgressSink progress) throws IOException, InterruptedException {
        if (!config.enabled) {
            throw new IOException("Local Cellpose training is disabled. Set -D"
                    + LOCAL_ENABLED_PROPERTY + "=true to enable the hidden backend.");
        }
        validateTrainingDataset(datasetDir);
        TrainingArtifacts artifacts = prepareTrainingArtifacts(
                datasetDir, trainCommandFile, metadataBaseModel(datasetDir), config);
        ProgressSink safeProgress = progress == null ? NO_PROGRESS : progress;
        safeProgress.update(0.0, "Starting local Cellpose training...");

        try (ProcessDiagnostics diagnostics = new ProcessDiagnostics(artifacts.logFile)) {
            diagnostics.writeMetadata("Command: " + displayCommand(artifacts.command));
            diagnostics.writeMetadata("Reproducibility: seed=" + config.seed
                    + ", deterministic mode requested, evidence="
                    + artifacts.reproducibilityFile);
            ProcessSpec spec = new ProcessSpec(artifacts.command, artifacts.datasetDir,
                    config.timeoutSeconds, config.stallTimeoutSeconds,
                    artifacts.environment);
            ProcessResult result;
            try {
                result = runner.run(spec,
                        diagnostics.stdout(new LoggingLineConsumer(
                                safeProgress)),
                        diagnostics.stderr(new LoggingLineConsumer(
                                safeProgress)));
            } catch (IOException e) {
                throw trainingIoFailure("Local Cellpose training", e,
                        artifacts.logFile, diagnostics.snapshot());
            } catch (InterruptedException e) {
                InterruptedException interrupted = new InterruptedException(
                        trainingInterruptionMessage("Local Cellpose training",
                                artifacts.logFile, diagnostics.snapshot()));
                interrupted.initCause(e);
                Thread.currentThread().interrupt();
                throw interrupted;
            }
            int exitCode = result == null ? -1 : result.exitCode;
            if (exitCode != 0) {
                throw new IOException(failureMessage("Local Cellpose training",
                        exitCode, artifacts.logFile, diagnostics.snapshot()));
            }
        }

        Path modelFile = requireValidatedArtifact(artifacts);
        safeProgress.update(1.0, "Local Cellpose training complete.");
        return new TrainingResult(modelFile, artifacts.logFile, artifacts.commandFile,
                artifacts.modelsDir, 0);
    }

    private static void validateTrainingDataset(Path datasetDir) throws IOException {
        Path dir = datasetDir == null ? null : datasetDir.toAbsolutePath().normalize();
        if (dir == null || !Files.isDirectory(dir)) {
            throw new IOException("Cellpose training dataset directory does not exist: " + dir);
        }
        List<Path> images = new ArrayList<Path>();
        List<Path> masks = new ArrayList<Path>();
        Stream<Path> stream = Files.list(dir);
        try {
            Iterator<Path> iterator = stream.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                String name = lowerName(path);
                if (!isTiffName(name)) {
                    continue;
                }
                if (isMaskName(name)) {
                    masks.add(path);
                } else {
                    images.add(path);
                }
            }
        } finally {
            stream.close();
        }
        Collections.sort(images);
        Collections.sort(masks);
        if (images.isEmpty()) {
            throw new IOException("Cellpose training dataset has no image TIFFs: " + dir);
        }
        List<String> missingMasks = new ArrayList<String>();
        for (int i = 0; i < images.size(); i++) {
            Path image = images.get(i);
            Path mask = dir.resolve(maskNameFor(image.getFileName().toString()));
            if (!Files.isRegularFile(mask)) {
                missingMasks.add(image.getFileName().toString() + " -> " + mask.getFileName());
            }
        }
        if (!missingMasks.isEmpty()) {
            throw new IOException("Cellpose training dataset is missing mask TIFF pairs in "
                    + dir + ": " + missingMasks);
        }
        if (masks.size() != images.size()) {
            throw new IOException("Cellpose training dataset image/mask count mismatch in "
                    + dir + ": " + images.size() + " images, " + masks.size() + " masks.");
        }
    }

    private static boolean isTiffName(String lowerName) {
        return lowerName.endsWith(".tif") || lowerName.endsWith(".tiff");
    }

    private static boolean isMaskName(String lowerName) {
        return lowerName.endsWith("_masks.tif") || lowerName.endsWith("_masks.tiff");
    }

    private static String maskNameFor(String imageName) {
        String name = imageName == null ? "" : imageName;
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".tiff")) {
            return name.substring(0, name.length() - 5) + "_masks.tiff";
        }
        if (lower.endsWith(".tif")) {
            return name.substring(0, name.length() - 4) + "_masks.tif";
        }
        return name + "_masks.tif";
    }

    private static String lowerName(Path path) {
        Path name = path == null ? null : path.getFileName();
        return name == null ? "" : name.toString().toLowerCase(Locale.ROOT);
    }

    public static TrainingArtifacts prepareTrainingArtifacts(Path datasetDir,
                                                            Path trainCommandFile,
                                                            String baseModel,
                                                            Config config) throws IOException {
        Config safeConfig = config == null ? Config.fromSystemProperties() : config;
        Path dir = datasetDir == null ? null : datasetDir.toAbsolutePath().normalize();
        if (dir == null) {
            throw new IOException("Cellpose dataset directory must not be null.");
        }
        Files.createDirectories(dir);
        Path modelsDir = dir.resolve(MODELS_DIR);
        Files.createDirectories(modelsDir);
        if (Files.isSymbolicLink(modelsDir)) {
            throw new IOException("Cellpose models directory must not be a symbolic link: "
                    + modelsDir);
        }
        Path realDatasetDir = dir.toRealPath();
        Path realModelsDir = modelsDir.toRealPath();
        if (!realDatasetDir.equals(realModelsDir.getParent())) {
            throw new IOException("Cellpose models directory must resolve directly under the "
                    + "dataset directory: " + modelsDir);
        }
        Path logFile = runLogFile(dir, LOG_FILENAME);
        Path commandFile = commandFile(dir, trainCommandFile);
        Path reproducibilityFile = dir.resolve(REPRODUCIBILITY_FILENAME);
        Path reproducibilityDir = dir.resolve(REPRODUCIBILITY_DIR);
        Files.createDirectories(reproducibilityDir);
        Path siteCustomize = reproducibilityDir.resolve(SITE_CUSTOMIZE_FILENAME);
        Files.write(siteCustomize, buildSiteCustomizeScript()
                .getBytes(StandardCharsets.UTF_8));
        writeRequestedReproducibility(reproducibilityFile, safeConfig);
        Map<String, String> environment = reproducibilityEnvironment(
                reproducibilityDir, reproducibilityFile, safeConfig.seed);
        long artifactSequence = ARTIFACT_SEQUENCE.incrementAndGet();
        String runToken = System.currentTimeMillis() + "-" + artifactSequence;
        String modelName = "flash_cellpose_" + System.currentTimeMillis()
                + "_" + artifactSequence;
        Path expectedModelFile = modelsDir.resolve(modelName).toAbsolutePath().normalize();
        Path artifactMarkerFile = dir.resolve(ARTIFACT_MARKER_PREFIX + runToken + ".json")
                .toAbsolutePath().normalize();
        Path wrapperFile = dir.resolve(TRAINING_WRAPPER_PREFIX + runToken + ".py")
                .toAbsolutePath().normalize();
        if (Files.exists(expectedModelFile) || Files.exists(artifactMarkerFile)) {
            throw new IOException("Cellpose training run identity already exists under " + dir + ".");
        }
        if (Files.isSymbolicLink(wrapperFile)) {
            throw new IOException("Cellpose training wrapper must not be a symbolic link: "
                    + wrapperFile);
        }
        Files.write(wrapperFile, buildTrainingWrapperScript()
                .getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        List<String> backendCommand = buildBackendCommand(
                dir, baseModel, safeConfig, modelName);
        List<String> command = buildWrappedCommand(safeConfig.pythonExecutable,
                wrapperFile, modelsDir, artifactMarkerFile, expectedModelFile,
                backendCommand);
        String commandText = displayCommand(command);
        Files.write(commandFile, Collections.singletonList(commandText), StandardCharsets.UTF_8);
        if (command.isEmpty()) {
            throw new IOException("Cellpose training command is empty: " + commandFile);
        }
        return new TrainingArtifacts(dir, commandFile, logFile, modelsDir,
                reproducibilityFile, siteCustomize, wrapperFile,
                artifactMarkerFile, expectedModelFile,
                environment, command, commandText);
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

    public static List<String> buildCommand(Path datasetDir,
                                            String baseModel,
                                            Config config) {
        Config safeConfig = config == null ? Config.fromSystemProperties() : config;
        Path dir = datasetDir == null ? Paths.get(".") : datasetDir.toAbsolutePath().normalize();
        return buildBackendCommand(dir, baseModel, safeConfig, null);
    }

    private static List<String> buildBackendCommand(Path datasetDir,
                                                    String baseModel,
                                                    Config config,
                                                    String modelName) {
        Config safeConfig = config == null ? Config.fromSystemProperties() : config;
        Path dir = datasetDir == null ? Paths.get(".") : datasetDir.toAbsolutePath().normalize();
        List<String> command = new ArrayList<String>();
        command.add(cleanOrDefault(safeConfig.pythonExecutable, "python"));
        command.add("-m");
        command.add("cellpose");
        command.add("--train");
        command.add("--dir");
        command.add(dir.toString());
        command.add("--pretrained_model");
        command.add(cleanOrDefault(baseModel, FALLBACK_BASE_MODEL));
        command.add("--learning_rate");
        command.add(String.valueOf(safeConfig.learningRate));
        command.add("--weight_decay");
        command.add(String.valueOf(safeConfig.weightDecay));
        command.add("--n_epochs");
        command.add(String.valueOf(Math.max(1, safeConfig.epochs)));
        command.add("--batch_size");
        command.add(String.valueOf(Math.max(1, safeConfig.batchSize)));
        if (modelName != null && !modelName.trim().isEmpty()) {
            command.add("--model_name_out");
            command.add(modelName.trim());
        }
        return command;
    }

    private static List<String> buildWrappedCommand(String pythonExecutable,
                                                    Path wrapperFile,
                                                    Path modelsDir,
                                                    Path artifactMarkerFile,
                                                    Path expectedModelFile,
                                                    List<String> backendCommand) {
        List<String> command = new ArrayList<String>();
        command.add(cleanOrDefault(pythonExecutable, "python"));
        command.add(wrapperFile.toString());
        command.add("--models-root");
        command.add(modelsDir.toString());
        command.add("--artifact-marker");
        command.add(artifactMarkerFile.toString());
        command.add("--expected-model");
        command.add(expectedModelFile.toString());
        command.add("--supported-version");
        command.add(CellposeRuntime.SUPPORTED_CELLPOSE_VERSION);
        command.add("--");
        if (backendCommand != null && backendCommand.size() > 1) {
            command.addAll(backendCommand.subList(1, backendCommand.size()));
        }
        return command;
    }

    private static Map<String, String> reproducibilityEnvironment(Path seedHookDir,
                                                                   Path evidenceFile,
                                                                   int seed) {
        Map<String, String> environment = new LinkedHashMap<String, String>();
        String inheritedPythonPath = System.getenv("PYTHONPATH");
        String pythonPath = seedHookDir.toAbsolutePath().normalize().toString();
        if (inheritedPythonPath != null && !inheritedPythonPath.trim().isEmpty()) {
            pythonPath += File.pathSeparator + inheritedPythonPath;
        }
        environment.put("PYTHONPATH", pythonPath);
        environment.put("PYTHONHASHSEED", String.valueOf(seed));
        environment.put("CUBLAS_WORKSPACE_CONFIG", ":4096:8");
        environment.put("FLASH_TRAINING_SEED", String.valueOf(seed));
        environment.put("FLASH_TRAINING_REPRO_FILE",
                evidenceFile.toAbsolutePath().normalize().toString());
        return environment;
    }

    private static void writeRequestedReproducibility(Path target,
                                                      Config config) throws IOException {
        Map<String, Object> evidence = JsonIO.object();
        evidence.put("version", Integer.valueOf(1));
        evidence.put("backend", "Cellpose");
        evidence.put("seed", Integer.valueOf(config.seed));
        evidence.put("requestedDevice", "cellpose-cli-default");
        evidence.put("deterministicModeRequested", Boolean.TRUE);
        evidence.put("runtimeEvidence", "pending");
        Files.write(target, (JsonIO.write(evidence) + "\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    static String buildSiteCustomizeScript() {
        return ""
                + "import json\n"
                + "import os\n"
                + "import platform\n"
                + "import random\n"
                + "from pathlib import Path\n"
                + "\n"
                + "seed = int(os.environ['FLASH_TRAINING_SEED'])\n"
                + "target = Path(os.environ['FLASH_TRAINING_REPRO_FILE'])\n"
                + "random.seed(seed)\n"
                + "import numpy as np\n"
                + "np.random.seed(seed)\n"
                + "import torch\n"
                + "torch.manual_seed(seed)\n"
                + "if torch.cuda.is_available():\n"
                + "    torch.cuda.manual_seed_all(seed)\n"
                + "status = 'best-effort'\n"
                + "api_status = 'requested'\n"
                + "detail = 'Cellpose augmentation and device kernels may remain nondeterministic.'\n"
                + "try:\n"
                + "    torch.use_deterministic_algorithms(True, warn_only=True)\n"
                + "    api_status = 'enabled'\n"
                + "except TypeError:\n"
                + "    try:\n"
                + "        torch.use_deterministic_algorithms(True)\n"
                + "        api_status = 'enabled'\n"
                + "    except Exception as error:\n"
                + "        api_status = 'unsupported'\n"
                + "        detail = 'PyTorch deterministic API unavailable: {}; Cellpose/device kernels may remain nondeterministic.'.format(error)\n"
                + "except Exception as error:\n"
                + "    api_status = 'unsupported'\n"
                + "    detail = 'PyTorch deterministic API unavailable: {}; Cellpose/device kernels may remain nondeterministic.'.format(error)\n"
                + "if hasattr(torch.backends, 'cudnn'):\n"
                + "    torch.backends.cudnn.benchmark = False\n"
                + "    torch.backends.cudnn.deterministic = True\n"
                + "try:\n"
                + "    from importlib.metadata import version as package_version\n"
                + "except ImportError:\n"
                + "    from importlib_metadata import version as package_version\n"
                + "try:\n"
                + "    cellpose_version = package_version('cellpose')\n"
                + "except Exception:\n"
                + "    cellpose_version = 'unknown'\n"
                + "evidence = {\n"
                + "    'version': 1,\n"
                + "    'backend': 'Cellpose',\n"
                + "    'backendVersion': cellpose_version,\n"
                + "    'numpyVersion': getattr(np, '__version__', 'unknown'),\n"
                + "    'torchVersion': getattr(torch, '__version__', 'unknown'),\n"
                + "    'pythonVersion': platform.python_version(),\n"
                + "    'seed': seed,\n"
                + "    'requestedDevice': 'cellpose-cli-default',\n"
                + "    'availableDevice': 'cuda' if torch.cuda.is_available() else 'cpu',\n"
                + "    'deterministicModeRequested': True,\n"
                + "    'deterministicModeStatus': status,\n"
                + "    'deterministicApiStatus': api_status,\n"
                + "    'deterministicModeDetail': detail,\n"
                + "    'evidenceWrittenBeforeCellposeImportAndModelConstruction': True,\n"
                + "}\n"
                + "temporary = target.with_name(target.name + '.tmp')\n"
                + "temporary.write_text(json.dumps(evidence, sort_keys=True, indent=2) + '\\n', encoding='utf-8')\n"
                + "temporary.replace(target)\n";
    }

    static String buildTrainingWrapperScript() {
        return ""
                + "from __future__ import print_function\n"
                + "import argparse\n"
                + "import hashlib\n"
                + "import json\n"
                + "import os\n"
                + "import subprocess\n"
                + "import sys\n"
                + "from pathlib import Path\n"
                + "try:\n"
                + "    from importlib import metadata as importlib_metadata\n"
                + "except ImportError:\n"
                + "    import importlib_metadata\n"
                + "\n"
                + "def confined(path, root):\n"
                + "    candidate = path.resolve(strict=False)\n"
                + "    candidate.relative_to(root)\n"
                + "    return candidate\n"
                + "\n"
                + "separator = sys.argv.index('--')\n"
                + "parser = argparse.ArgumentParser()\n"
                + "parser.add_argument('--models-root', required=True)\n"
                + "parser.add_argument('--artifact-marker', required=True)\n"
                + "parser.add_argument('--expected-model', required=True)\n"
                + "parser.add_argument('--supported-version', required=True)\n"
                + "args = parser.parse_args(sys.argv[1:separator])\n"
                + "backend = sys.argv[separator + 1:]\n"
                + "if len(backend) < 2 or backend[0:2] != ['-m', 'cellpose']:\n"
                + "    raise RuntimeError('FLASH wrapper accepts only the Cellpose module backend.')\n"
                + "actual_version = str(importlib_metadata.version('cellpose')).strip()\n"
                + "if actual_version != args.supported_version:\n"
                + "    raise RuntimeError('Unsupported Cellpose version: expected {}, found {}.'.format(args.supported_version, actual_version))\n"
                + "models_root_path = Path(args.models_root)\n"
                + "marker_path = Path(args.artifact_marker)\n"
                + "expected_path = Path(args.expected_model)\n"
                + "if models_root_path.is_symlink() or marker_path.is_symlink() or expected_path.is_symlink():\n"
                + "    raise RuntimeError('Cellpose artifact paths must not be symbolic links.')\n"
                + "models_root = models_root_path.resolve(strict=True)\n"
                + "marker = marker_path.resolve(strict=False)\n"
                + "expected = confined(expected_path, models_root)\n"
                + "if models_root.parent != marker.parent:\n"
                + "    raise RuntimeError('Cellpose models root must resolve directly under the dataset root.')\n"
                + "if expected.parent != models_root:\n"
                + "    raise RuntimeError('Expected Cellpose artifact must be directly under the models root.')\n"
                + "if marker.parent != models_root.parent:\n"
                + "    raise RuntimeError('Cellpose artifact marker must be under the dataset root.')\n"
                + "if marker.exists() or expected.exists():\n"
                + "    raise RuntimeError('Cellpose run artifact identity already exists.')\n"
                + "exit_code = subprocess.call([sys.executable] + backend)\n"
                + "if exit_code != 0:\n"
                + "    raise SystemExit(exit_code)\n"
                + "if expected.is_symlink() or not expected.is_file():\n"
                + "    raise RuntimeError('Cellpose did not create the exact declared model artifact: {}'.format(expected))\n"
                + "expected = expected.resolve(strict=True)\n"
                + "expected.relative_to(models_root)\n"
                + "if expected.stat().st_size <= 0:\n"
                + "    raise RuntimeError('Cellpose declared an empty model artifact.')\n"
                + "from cellpose import models\n"
                + "model = models.CellposeModel(gpu=False, pretrained_model=str(expected))\n"
                + "if not getattr(model, 'pretrained_model', None):\n"
                + "    raise RuntimeError('Cellpose did not load the declared model weights.')\n"
                + "del model\n"
                + "artifact_bytes = expected.stat().st_size\n"
                + "artifact_sha256 = hashlib.sha256()\n"
                + "with expected.open('rb') as artifact_stream:\n"
                + "    for block in iter(lambda: artifact_stream.read(1024 * 1024), b''):\n"
                + "        artifact_sha256.update(block)\n"
                + "relative = expected.relative_to(marker.parent).as_posix()\n"
                + "record = {\n"
                + "    'version': 1,\n"
                + "    'status': 'success',\n"
                + "    'artifactKind': 'cellpose-model-weights',\n"
                + "    'artifacts': [relative],\n"
                + "    'candidateCount': 1,\n"
                + "    'validatedBy': 'CellposeModel',\n"
                + "    'cellposeVersion': actual_version,\n"
                + "    'artifactBytes': artifact_bytes,\n"
                + "    'artifactSha256': artifact_sha256.hexdigest(),\n"
                + "}\n"
                + "temporary = marker.with_name(marker.name + '.tmp-' + str(os.getpid()))\n"
                + "if temporary.exists() or temporary.is_symlink():\n"
                + "    raise RuntimeError('Cellpose artifact marker temporary path already exists.')\n"
                + "temporary.write_text(json.dumps(record, sort_keys=True, ensure_ascii=False) + '\\n', encoding='utf-8')\n"
                + "os.replace(str(temporary), str(marker))\n"
                + "print('FLASH_CELLPOSE_ARTIFACT_MARKER=' + str(marker), flush=True)\n";
    }

    public static List<String> parseCommandLine(String commandLine) throws IOException {
        String text = commandLine == null ? "" : commandLine.trim();
        List<String> out = new ArrayList<String>();
        if (text.isEmpty()) {
            return out;
        }
        StringBuilder token = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                token.append('"');
                i++;
            } else if (c == '"') {
                inQuote = !inQuote;
            } else if (Character.isWhitespace(c) && !inQuote) {
                if (token.length() > 0) {
                    out.add(token.toString());
                    token.setLength(0);
                }
            } else {
                token.append(c);
            }
        }
        if (inQuote) {
            throw new IOException("Unclosed quote in Cellpose training command.");
        }
        if (token.length() > 0) {
            out.add(token.toString());
        }
        return out;
    }

    private static Path commandFile(Path datasetDir, Path trainCommandFile) {
        if (trainCommandFile != null) {
            return trainCommandFile.toAbsolutePath().normalize();
        }
        return datasetDir.resolve(COMMAND_FILENAME).toAbsolutePath().normalize();
    }

    private static String metadataBaseModel(Path datasetDir) {
        if (datasetDir == null) {
            return FALLBACK_BASE_MODEL;
        }
        Path metadata = datasetDir.toAbsolutePath().normalize().resolve("metadata.json");
        if (!Files.isRegularFile(metadata)) {
            return FALLBACK_BASE_MODEL;
        }
        try {
            Map<String, Object> root = JsonIO.parseObject(
                    new String(Files.readAllBytes(metadata), StandardCharsets.UTF_8));
            return cleanOrDefault(JsonIO.stringValue(root.get("baseModel")), FALLBACK_BASE_MODEL);
        } catch (Exception ignored) {
            return FALLBACK_BASE_MODEL;
        }
    }

    private static Path requireValidatedArtifact(TrainingArtifacts artifacts) throws IOException {
        Path marker = artifacts == null ? null : artifacts.artifactMarkerFile;
        if (marker == null || Files.isSymbolicLink(marker) || !Files.isRegularFile(marker)) {
            throw new IOException("Local Cellpose training finished without its machine-readable "
                    + "artifact marker: " + marker + ". Log: "
                    + (artifacts == null ? "<unknown>" : artifacts.logFile));
        }
        Object parsed;
        try (InputStream input = Files.newInputStream(marker)) {
            parsed = MiniJson.parseUtf8(input, MiniJson.DEFAULT_LIMITS, marker.toString());
        }
        if (!(parsed instanceof Map)) {
            throw new IOException("Cellpose artifact marker root must be an object: " + marker);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) parsed;
        List<Object> declared = JsonIO.asList(root.get("artifacts"));
        if (JsonIO.intValue(root.get("version"), -1) != ARTIFACT_MARKER_VERSION
                || !"success".equals(JsonIO.stringValue(root.get("status")))
                || !"cellpose-model-weights".equals(
                        JsonIO.stringValue(root.get("artifactKind")))
                || !"CellposeModel".equals(JsonIO.stringValue(root.get("validatedBy")))
                || !CellposeRuntime.SUPPORTED_CELLPOSE_VERSION.equals(
                        JsonIO.stringValue(root.get("cellposeVersion")))) {
            throw new IOException("Cellpose artifact marker is unsupported or was not validated "
                    + "by the configured backend: " + marker);
        }
        if (declared.size() != 1
                || JsonIO.intValue(root.get("candidateCount"), -1) != 1) {
            throw new IOException("Cellpose artifact marker is ambiguous: expected exactly one "
                    + "validated model, found " + declared.size() + " in " + marker);
        }
        String relativeText = JsonIO.stringValue(declared.get(0)).trim();
        if (relativeText.isEmpty()) {
            throw new IOException("Cellpose artifact marker does not name a model: " + marker);
        }
        Path relative;
        try {
            relative = Paths.get(relativeText);
        } catch (RuntimeException e) {
            throw new IOException("Cellpose artifact marker contains an invalid path: "
                    + relativeText, e);
        }
        if (relative.isAbsolute()) {
            throw new IOException("Cellpose artifact marker path must be relative: " + relativeText);
        }
        Path selected = artifacts.datasetDir.resolve(relative).toAbsolutePath().normalize();
        Path expected = artifacts.expectedModelFile.toAbsolutePath().normalize();
        Path modelsRoot = artifacts.modelsDir.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(modelsRoot)) {
            throw new IOException("Cellpose models directory became a symbolic link during training: "
                    + modelsRoot);
        }
        if (!selected.equals(expected) || !selected.startsWith(modelsRoot)) {
            throw new IOException("Cellpose artifact marker selected an undeclared or auxiliary file: "
                    + relativeText + "; expected " + expected.getFileName() + ".");
        }
        if (Files.isSymbolicLink(selected) || !Files.isRegularFile(selected)
                || Files.size(selected) <= 0L) {
            throw new IOException("Cellpose artifact marker references a missing, linked, or empty "
                    + "model file: " + selected);
        }
        Path realDatasetRoot = artifacts.datasetDir.toRealPath();
        Path realRoot = modelsRoot.toRealPath();
        Path realSelected = selected.toRealPath();
        if (!realDatasetRoot.equals(realRoot.getParent())
                || !realSelected.startsWith(realRoot)
                || !realSelected.equals(expected.toRealPath())) {
            throw new IOException("Cellpose artifact resolves outside the intended models root: "
                    + selected);
        }
        Object byteValue = root.get("artifactBytes");
        long declaredBytes = byteValue instanceof Number
                ? ((Number) byteValue).longValue() : -1L;
        long actualBytes = Files.size(realSelected);
        if (declaredBytes <= 0L || declaredBytes != actualBytes) {
            throw new IOException("Cellpose artifact size changed after backend validation: declared "
                    + declaredBytes + " bytes, found " + actualBytes + " at " + realSelected);
        }
        String declaredDigest = JsonIO.stringValue(root.get("artifactSha256"))
                .trim().toLowerCase(Locale.ROOT);
        if (!declaredDigest.matches("[0-9a-f]{64}")) {
            throw new IOException("Cellpose artifact marker has a missing or malformed SHA-256 digest: "
                    + marker);
        }
        String actualDigest = sha256(realSelected);
        if (!declaredDigest.equals(actualDigest)) {
            throw new IOException("Cellpose artifact changed after backend validation: SHA-256 mismatch "
                    + "for " + realSelected);
        }
        return realSelected;
    }

    private static String sha256(Path file) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable for Cellpose artifact validation.", e);
        }
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = Files.newInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder(64);
        byte[] bytes = digest.digest();
        for (int i = 0; i < bytes.length; i++) {
            hex.append(String.format(Locale.ROOT, "%02x", Integer.valueOf(bytes[i] & 0xff)));
        }
        return hex.toString();
    }

    private static String failureMessage(String label,
                                         int exitCode,
                                         Path logFile,
                                         DiagnosticSnapshot diagnostics) {
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
                                                 DiagnosticSnapshot diagnostics) {
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
                                                      DiagnosticSnapshot diagnostics) {
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

    static String displayCommand(List<String> command) {
        StringBuilder out = new StringBuilder();
        List<String> safe = command == null
                ? Collections.<String>emptyList()
                : command;
        for (int i = 0; i < safe.size(); i++) {
            if (i > 0) out.append(' ');
            out.append(quoteForDisplay(safe.get(i)));
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
        String text = value == null ? "" : value.trim();
        return text.isEmpty() ? fallback : text;
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
        public final int epochs;
        public final int batchSize;
        public final double learningRate;
        public final double weightDecay;
        public final int seed;
        public final int timeoutSeconds;
        public final int stallTimeoutSeconds;

        public Config(boolean enabled,
                      String pythonExecutable,
                      int epochs,
                      int batchSize,
                      double learningRate,
                      double weightDecay) {
            this(enabled, pythonExecutable, epochs, batchSize, learningRate, weightDecay,
                    42,
                    intProperty(TIMEOUT_SECONDS_PROPERTY, 6 * 60 * 60, 0),
                    intProperty(STALL_TIMEOUT_SECONDS_PROPERTY, 30 * 60, 0));
        }

        public Config(boolean enabled,
                      String pythonExecutable,
                      int epochs,
                      int batchSize,
                      double learningRate,
                      double weightDecay,
                      int seed) {
            this(enabled, pythonExecutable, epochs, batchSize, learningRate, weightDecay,
                    seed,
                    intProperty(TIMEOUT_SECONDS_PROPERTY, 6 * 60 * 60, 0),
                    intProperty(STALL_TIMEOUT_SECONDS_PROPERTY, 30 * 60, 0));
        }

        public Config(boolean enabled,
                      String pythonExecutable,
                      int epochs,
                      int batchSize,
                      double learningRate,
                      double weightDecay,
                      int timeoutSeconds,
                      int stallTimeoutSeconds) {
            this(enabled, pythonExecutable, epochs, batchSize, learningRate, weightDecay,
                    42, timeoutSeconds, stallTimeoutSeconds);
        }

        public Config(boolean enabled,
                      String pythonExecutable,
                      int epochs,
                      int batchSize,
                      double learningRate,
                      double weightDecay,
                      int seed,
                      int timeoutSeconds,
                      int stallTimeoutSeconds) {
            this.enabled = enabled;
            this.pythonExecutable = cleanOrDefault(pythonExecutable, "python");
            this.epochs = Math.max(1, epochs);
            this.batchSize = Math.max(1, batchSize);
            this.learningRate = Math.max(0.0,
                    requireFinite("Cellpose learning rate", learningRate));
            this.weightDecay = Math.max(0.0,
                    requireFinite("Cellpose weight decay", weightDecay));
            this.seed = seed;
            this.timeoutSeconds = Math.max(0, timeoutSeconds);
            this.stallTimeoutSeconds = Math.max(0, stallTimeoutSeconds);
        }

        public static Config fromSystemProperties() {
            String python = System.getProperty(PYTHON_PROPERTY, "").trim();
            if (python.isEmpty()) {
                python = CellposeRuntime.getPythonPath();
            }
            if (python == null || python.trim().isEmpty()) {
                python = "python";
            }
            return new Config(
                    booleanProperty(LOCAL_ENABLED_PROPERTY, false),
                    python,
                    intProperty(EPOCHS_PROPERTY, 100, 1),
                    intProperty(BATCH_SIZE_PROPERTY, 1, 1),
                    doubleProperty(LEARNING_RATE_PROPERTY, 0.00001, 0.0),
                    doubleProperty(WEIGHT_DECAY_PROPERTY, 0.1, 0.0),
                    intProperty(SEED_PROPERTY, 42, Integer.MIN_VALUE),
                    intProperty(TIMEOUT_SECONDS_PROPERTY, 6 * 60 * 60, 0),
                    intProperty(STALL_TIMEOUT_SECONDS_PROPERTY, 30 * 60, 0));
        }
    }

    public static final class TrainingArtifacts {
        public final Path datasetDir;
        public final Path commandFile;
        public final Path logFile;
        public final Path modelsDir;
        public final Path reproducibilityFile;
        public final Path siteCustomizeFile;
        public final Path wrapperFile;
        public final Path artifactMarkerFile;
        public final Path expectedModelFile;
        public final Map<String, String> environment;
        public final List<String> command;
        public final String commandText;

        TrainingArtifacts(Path datasetDir,
                          Path commandFile,
                          Path logFile,
                          Path modelsDir,
                          Path reproducibilityFile,
                          Path siteCustomizeFile,
                          Path wrapperFile,
                          Path artifactMarkerFile,
                          Path expectedModelFile,
                          Map<String, String> environment,
                          List<String> command,
                          String commandText) {
            this.datasetDir = datasetDir;
            this.commandFile = commandFile;
            this.logFile = logFile;
            this.modelsDir = modelsDir;
            this.reproducibilityFile = reproducibilityFile;
            this.siteCustomizeFile = siteCustomizeFile;
            this.wrapperFile = wrapperFile;
            this.artifactMarkerFile = artifactMarkerFile;
            this.expectedModelFile = expectedModelFile;
            this.environment = Collections.unmodifiableMap(
                    new LinkedHashMap<String, String>(environment));
            this.command = Collections.unmodifiableList(new ArrayList<String>(
                    command == null ? Collections.<String>emptyList() : command));
            this.commandText = commandText == null ? "" : commandText;
        }
    }

    public static final class TrainingResult {
        public final Path modelFile;
        public final Path logFile;
        public final Path commandFile;
        public final Path modelsDir;
        public final int exitCode;

        public TrainingResult(Path modelFile,
                              Path logFile,
                              Path commandFile,
                              Path modelsDir,
                              int exitCode) {
            this.modelFile = modelFile;
            this.logFile = logFile;
            this.commandFile = commandFile;
            this.modelsDir = modelsDir;
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
        private final ProgressSink progress;

        LoggingLineConsumer(ProgressSink progress) {
            this.progress = progress;
        }

        @Override public void accept(String line) {
            CellposeTrainingProgressParser.Progress parsed =
                    CellposeTrainingProgressParser.parse(line);
            if (parsed != null) {
                progress.update(0.05 + (0.90 * parsed.fraction), parsed.message);
            }
        }
    }

    /**
     * Complete on-disk diagnostics plus a deterministic, bounded in-memory tail.
     * Each stream keeps its own order so scheduling between stdout and stderr cannot
     * change the failure summary.
     */
    public static final class ProcessDiagnostics implements Closeable {
        private static final int FLUSH_INTERVAL_LINES = 256;

        private final Path logFile;
        private final BufferedWriter writer;
        private final Object lock = new Object();
        private final BoundedTail stdoutTail = new BoundedTail();
        private final BoundedTail stderrTail = new BoundedTail();
        private long writtenLines;
        private boolean closed;

        public ProcessDiagnostics(Path logFile) throws IOException {
            if (logFile == null) {
                throw new IOException("Process diagnostic log path must not be null.");
            }
            this.logFile = logFile.toAbsolutePath().normalize();
            Path parent = this.logFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            this.writer = Files.newBufferedWriter(this.logFile, StandardCharsets.UTF_8);
        }

        public Path logFile() {
            return logFile;
        }

        public void writeMetadata(String line) throws IOException {
            record("FLASH", line, null);
        }

        public LineConsumer stdout(LineConsumer delegate) {
            return new DiagnosticLineConsumer(this, "STDOUT", stdoutTail, delegate);
        }

        public LineConsumer stderr(LineConsumer delegate) {
            return new DiagnosticLineConsumer(this, "STDERR", stderrTail, delegate);
        }

        public DiagnosticSnapshot snapshot() {
            synchronized (lock) {
                return new DiagnosticSnapshot(logFile, stdoutTail.lines(), stderrTail.lines(),
                        stdoutTail.totalLines(), stderrTail.totalLines(),
                        stdoutTail.retainedCharacters(), stderrTail.retainedCharacters());
            }
        }

        private void record(String stream, String line, BoundedTail tail) throws IOException {
            synchronized (lock) {
                if (closed) {
                    throw new IOException("Process diagnostic log is already closed: " + logFile);
                }
                String value = line == null ? "" : line;
                writer.write("[" + stream + "] ");
                writer.write(value);
                writer.newLine();
                if (tail != null) {
                    tail.add(value);
                }
                writtenLines++;
                if (writtenLines % FLUSH_INTERVAL_LINES == 0L) {
                    writer.flush();
                }
            }
        }

        @Override public void close() throws IOException {
            synchronized (lock) {
                if (closed) {
                    return;
                }
                closed = true;
                writer.close();
            }
        }
    }

    public static final class DiagnosticSnapshot {
        public final Path logFile;
        public final List<String> stdoutTail;
        public final List<String> stderrTail;
        public final long stdoutLineCount;
        public final long stderrLineCount;
        public final int stdoutRetainedCharacters;
        public final int stderrRetainedCharacters;

        DiagnosticSnapshot(Path logFile,
                           List<String> stdoutTail,
                           List<String> stderrTail,
                           long stdoutLineCount,
                           long stderrLineCount,
                           int stdoutRetainedCharacters,
                           int stderrRetainedCharacters) {
            this.logFile = logFile;
            this.stdoutTail = Collections.unmodifiableList(stdoutTail);
            this.stderrTail = Collections.unmodifiableList(stderrTail);
            this.stdoutLineCount = stdoutLineCount;
            this.stderrLineCount = stderrLineCount;
            this.stdoutRetainedCharacters = stdoutRetainedCharacters;
            this.stderrRetainedCharacters = stderrRetainedCharacters;
        }
    }

    public static final class ManagedProcessResult {
        public final int exitCode;
        public final long rootPid;

        ManagedProcessResult(int exitCode, long rootPid) {
            this.exitCode = exitCode;
            this.rootPid = rootPid;
        }
    }

    private static final class DiagnosticLineConsumer implements LineConsumer {
        private final ProcessDiagnostics diagnostics;
        private final String stream;
        private final BoundedTail tail;
        private final LineConsumer delegate;

        DiagnosticLineConsumer(ProcessDiagnostics diagnostics,
                               String stream,
                               BoundedTail tail,
                               LineConsumer delegate) {
            this.diagnostics = diagnostics;
            this.stream = stream;
            this.tail = tail;
            this.delegate = delegate;
        }

        @Override public void accept(String line) throws IOException {
            diagnostics.record(stream, line, tail);
            if (delegate != null) {
                delegate.accept(line);
            }
        }
    }

    private static final class BoundedTail {
        private static final int MAX_LINES = 64;
        private static final int MAX_CHARACTERS = 64 * 1024;
        private static final int MAX_LINE_CHARACTERS = 2048;

        private final Deque<String> lines = new ArrayDeque<String>();
        private long totalLines;
        private int retainedCharacters;

        void add(String line) {
            String value = boundedLine(line == null ? "" : line);
            totalLines++;
            lines.addLast(value);
            retainedCharacters += value.length();
            while (lines.size() > MAX_LINES || retainedCharacters > MAX_CHARACTERS) {
                retainedCharacters -= lines.removeFirst().length();
            }
        }

        private static String boundedLine(String value) {
            if (value.length() <= MAX_LINE_CHARACTERS) {
                return value;
            }
            int side = (MAX_LINE_CHARACTERS - 32) / 2;
            int omitted = value.length() - (2 * side);
            return value.substring(0, side) + " [... " + omitted
                    + " chars omitted ...] " + value.substring(value.length() - side);
        }

        List<String> lines() {
            return new ArrayList<String>(lines);
        }

        long totalLines() {
            return totalLines;
        }

        int retainedCharacters() {
            return retainedCharacters;
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
            ManagedProcessResult result = runManagedProcess(process,
                    "Local Cellpose training", spec.timeoutSeconds,
                    spec.stallTimeoutSeconds, stdout, stderr);
            return new ProcessResult(result.exitCode);
        }
    }

    /**
     * Owns a process and every descendant observed while it is live. All exits are
     * bounded; cancellation is represented by thread interruption.
     */
    public static ManagedProcessResult runManagedProcess(Process process,
                                                         String label,
                                                         long timeoutSeconds,
                                                         long stallTimeoutSeconds,
                                                         LineConsumer stdout,
                                                         LineConsumer stderr)
            throws IOException, InterruptedException {
        return runManagedProcessInternal(process, label, timeoutSeconds,
                stallTimeoutSeconds, stdout, stderr, true);
    }

    static ManagedProcessResult runManagedProcessJava8FallbackForTest(
            Process process,
            String label,
            long timeoutSeconds,
            long stallTimeoutSeconds,
            LineConsumer stdout,
            LineConsumer stderr) throws IOException, InterruptedException {
        return runManagedProcessInternal(process, label, timeoutSeconds,
                stallTimeoutSeconds, stdout, stderr, false);
    }

    private static ManagedProcessResult runManagedProcessInternal(
            Process process,
            String label,
            long timeoutSeconds,
            long stallTimeoutSeconds,
            LineConsumer stdout,
            LineConsumer stderr,
            boolean allowProcessHandle) throws IOException, InterruptedException {
        if (process == null) {
            throw new IOException("Cannot manage a null process.");
        }
        String safeLabel = cleanOrDefault(label, "External process");
        final AtomicLong lastOutputMs = new AtomicLong(System.currentTimeMillis());
        final TreeTracker tracker = new TreeTracker(process, allowProcessHandle);
        final StreamDrainer outDrainer = new StreamDrainer(process.getInputStream(),
                stdout, lastOutputMs, safeLabel + " stdout");
        final StreamDrainer errDrainer = new StreamDrainer(process.getErrorStream(),
                stderr, lastOutputMs, safeLabel + " stderr");
        final InterruptFlag interrupt = new InterruptFlag();
        final long started = System.currentTimeMillis();
        final long runtimeMs = safeMillis(timeoutSeconds);
        final long stallMs = safeMillis(stallTimeoutSeconds);
        IOException failure = null;
        InterruptedException interruption = null;
        boolean exited = false;

        tracker.snapshot();
        outDrainer.start();
        errDrainer.start();
        while (!exited && failure == null && interruption == null) {
            tracker.snapshot();
            IOException drainerFailure = drainerFailure(outDrainer, errDrainer);
            if (drainerFailure != null) {
                failure = drainerFailure;
                break;
            }
            try {
                exited = process.waitFor(100L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                interrupt.interrupted = true;
                interruption = e;
                break;
            }
            long now = System.currentTimeMillis();
            if (!exited && runtimeMs > 0L && now - started >= runtimeMs) {
                failure = new IOException(safeLabel + " (PID " + tracker.rootPid
                        + ") timed out after " + timeoutSeconds + " seconds.");
            } else if (!exited && stallMs > 0L && now - lastOutputMs.get() >= stallMs) {
                failure = new IOException(safeLabel + " (PID " + tracker.rootPid
                        + ") produced no output for " + stallTimeoutSeconds + " seconds.");
            }
        }

        if (exited && failure == null && interruption == null) {
            boolean joined = joinDrainers(outDrainer, errDrainer, 3_000L, interrupt);
            IOException drainerFailure = drainerFailure(outDrainer, errDrainer);
            if (drainerFailure != null) {
                failure = drainerFailure;
            } else if (!joined) {
                failure = new IOException(safeLabel
                        + " output drainers did not finish within 3000 ms.");
            } else if (interrupt.interrupted) {
                interruption = new InterruptedException(safeLabel
                        + " was interrupted while joining output drainers.");
            }
        }

        if (failure != null || interruption != null) {
            CleanupOutcome cleanup = terminateAndClose(process, tracker,
                    outDrainer, errDrainer, interrupt);
            IOException cleanupFailure = cleanup.failure(safeLabel);
            if (interruption != null) {
                if (failure != null) {
                    interruption.addSuppressed(failure);
                }
                if (cleanupFailure != null) {
                    interruption.addSuppressed(cleanupFailure);
                }
                Thread.currentThread().interrupt();
                throw interruption;
            }
            if (interrupt.interrupted) {
                InterruptedException interrupted = new InterruptedException(
                        safeLabel + " was interrupted during cleanup.");
                interrupted.addSuppressed(failure);
                if (cleanupFailure != null) {
                    interrupted.addSuppressed(cleanupFailure);
                }
                Thread.currentThread().interrupt();
                throw interrupted;
            }
            if (cleanupFailure != null) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }

        tracker.snapshot();
        List<Long> survivors = tracker.survivingPids();
        if (!survivors.isEmpty()) {
            CleanupOutcome cleanup = terminateAndClose(process, tracker,
                    outDrainer, errDrainer, interrupt);
            IOException orphanFailure = new IOException(safeLabel
                    + " exited but left owned descendants alive: " + survivors);
            IOException cleanupFailure = cleanup.failure(safeLabel);
            if (cleanupFailure != null) {
                orphanFailure.addSuppressed(cleanupFailure);
            }
            if (interrupt.interrupted) {
                InterruptedException interrupted = new InterruptedException(
                        safeLabel + " was interrupted during descendant cleanup.");
                interrupted.addSuppressed(orphanFailure);
                Thread.currentThread().interrupt();
                throw interrupted;
            }
            throw orphanFailure;
        }

        IOException closeFailure = closeProcessStreams(process);
        if (closeFailure != null) {
            throw closeFailure;
        }
        return new ManagedProcessResult(process.exitValue(), tracker.rootPid);
    }

    private static long safeMillis(long seconds) {
        if (seconds <= 0L) {
            return 0L;
        }
        if (seconds > Long.MAX_VALUE / 1000L) {
            return Long.MAX_VALUE;
        }
        return TimeUnit.SECONDS.toMillis(seconds);
    }

    private static IOException drainerFailure(StreamDrainer stdout,
                                               StreamDrainer stderr) {
        IOException first = stdout.failure.get();
        IOException second = stderr.failure.get();
        if (first == null) {
            return second;
        }
        if (second != null && second != first) {
            first.addSuppressed(second);
        }
        return first;
    }

    private static boolean joinDrainers(StreamDrainer stdout,
                                        StreamDrainer stderr,
                                        long timeoutMillis,
                                        InterruptFlag interrupt) {
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(Math.max(1L, timeoutMillis));
        joinUntil(stdout.thread, deadline, interrupt);
        joinUntil(stderr.thread, deadline, interrupt);
        return !stdout.thread.isAlive() && !stderr.thread.isAlive();
    }

    private static void joinUntil(Thread thread,
                                  long deadlineNanos,
                                  InterruptFlag interrupt) {
        while (thread.isAlive()) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0L) {
                return;
            }
            long millis = Math.max(1L, Math.min(250L,
                    TimeUnit.NANOSECONDS.toMillis(remaining)));
            try {
                thread.join(millis);
            } catch (InterruptedException e) {
                interrupt.interrupted = true;
            }
        }
    }

    private static CleanupOutcome terminateAndClose(Process process,
                                                     TreeTracker tracker,
                                                     StreamDrainer stdout,
                                                     StreamDrainer stderr,
                                                     InterruptFlag interrupt) {
        tracker.snapshot();
        tracker.terminate(false, interrupt);
        waitForProcess(process, 750L, interrupt);
        tracker.terminate(true, interrupt);
        waitForProcess(process, 2_000L, interrupt);
        stdout.closureExpected = true;
        stderr.closureExpected = true;
        IOException closeFailure = closeProcessStreams(process);
        boolean joined = joinDrainers(stdout, stderr, 3_000L, interrupt);
        if (!joined) {
            stdout.thread.interrupt();
            stderr.thread.interrupt();
            joinDrainers(stdout, stderr, 500L, interrupt);
        }
        return new CleanupOutcome(tracker.survivingPids(),
                stdout.thread.isAlive(), stderr.thread.isAlive(), closeFailure);
    }

    private static IOException closeProcessStreams(Process process) {
        IOException failure = null;
        failure = closeProcessStream(process.getInputStream(), "stdout", failure);
        failure = closeProcessStream(process.getErrorStream(), "stderr", failure);
        failure = closeProcessStream(process.getOutputStream(), "stdin", failure);
        return failure;
    }

    private static IOException closeProcessStream(Closeable stream,
                                                  String name,
                                                  IOException failure) {
        try {
            stream.close();
        } catch (IOException e) {
            IOException wrapped = new IOException("Failed to close process " + name
                    + " stream: " + e.getMessage(), e);
            if (failure == null) {
                return wrapped;
            }
            failure.addSuppressed(wrapped);
        }
        return failure;
    }

    private static boolean waitForProcess(Process process,
                                          long timeoutMillis,
                                          InterruptFlag interrupt) {
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(Math.max(1L, timeoutMillis));
        while (process.isAlive()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                return false;
            }
            try {
                if (process.waitFor(Math.max(1L, Math.min(100L,
                        TimeUnit.NANOSECONDS.toMillis(remaining))),
                        TimeUnit.MILLISECONDS)) {
                    return true;
                }
            } catch (InterruptedException e) {
                interrupt.interrupted = true;
            }
        }
        return true;
    }

    private static final class StreamDrainer implements Runnable {
        private final InputStream input;
        private final LineConsumer consumer;
        private final AtomicLong lastOutputMs;
        private final String name;
        private final AtomicReference<IOException> failure =
                new AtomicReference<IOException>();
        private final Thread thread;
        private volatile boolean closureExpected;

        StreamDrainer(InputStream input,
                      LineConsumer consumer,
                      AtomicLong lastOutputMs,
                      String name) {
            this.input = input;
            this.consumer = consumer;
            this.lastOutputMs = lastOutputMs;
            this.name = name;
            this.thread = new Thread(this, "flash-process-"
                    + name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-"));
            this.thread.setDaemon(true);
        }

        void start() {
            thread.start();
        }

        @Override public void run() {
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    input, StandardCharsets.UTF_8));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    lastOutputMs.set(System.currentTimeMillis());
                    if (consumer != null) {
                        consumer.accept(line);
                    }
                }
            } catch (IOException e) {
                if (!closureExpected) {
                    failure.compareAndSet(null,
                            new IOException(name + " drainer failed: " + e.getMessage(), e));
                }
            } catch (RuntimeException e) {
                failure.compareAndSet(null,
                        new IOException(name + " drainer failed: " + e.getMessage(), e));
            } catch (Error e) {
                failure.compareAndSet(null,
                        new IOException(name + " drainer failed: " + e.getMessage(), e));
                if (e instanceof VirtualMachineError || e instanceof ThreadDeath) {
                    throw e;
                }
            } finally {
                try {
                    reader.close();
                } catch (IOException e) {
                    if (!closureExpected) {
                        failure.compareAndSet(null,
                                new IOException(name + " drainer close failed: "
                                        + e.getMessage(), e));
                    }
                }
            }
        }
    }

    private static final class CleanupOutcome {
        private final List<Long> survivingPids;
        private final boolean stdoutAlive;
        private final boolean stderrAlive;
        private final IOException closeFailure;

        CleanupOutcome(List<Long> survivingPids,
                       boolean stdoutAlive,
                       boolean stderrAlive,
                       IOException closeFailure) {
            this.survivingPids = survivingPids;
            this.stdoutAlive = stdoutAlive;
            this.stderrAlive = stderrAlive;
            this.closeFailure = closeFailure;
        }

        IOException failure(String label) {
            if (survivingPids.isEmpty() && !stdoutAlive && !stderrAlive
                    && closeFailure == null) {
                return null;
            }
            IOException failure = new IOException(label
                    + " cleanup incomplete; surviving PIDs="
                    + survivingPids + ", stdoutDrainerAlive=" + stdoutAlive
                    + ", stderrDrainerAlive=" + stderrAlive + '.');
            if (closeFailure != null) {
                failure.addSuppressed(closeFailure);
            }
            return failure;
        }
    }

    private static final class InterruptFlag {
        private boolean interrupted;
    }

    private static final class TreeTracker {
        private final Process root;
        private final long rootPid;
        private final LinkedHashMap<Long, Object> handles =
                new LinkedHashMap<Long, Object>();
        private final LinkedHashMap<Long, Long> osPids =
                new LinkedHashMap<Long, Long>();
        private final boolean allowProcessHandle;
        private boolean handlesComplete;
        private long lastWindowsSnapshotNanos;

        TreeTracker(Process root, boolean allowProcessHandle) {
            this.root = root;
            this.rootPid = processId(root);
            this.allowProcessHandle = allowProcessHandle;
        }

        void snapshot() {
            if (snapshotHandles()) {
                return;
            }
            if (isWindows() && rootPid > 0L && root.isAlive()) {
                long now = System.nanoTime();
                if (lastWindowsSnapshotNanos == 0L
                        || now - lastWindowsSnapshotNanos
                        >= TimeUnit.MILLISECONDS.toNanos(250L)) {
                    snapshotWindowsPids();
                    lastWindowsSnapshotNanos = System.nanoTime();
                }
            } else if (!isWindows() && rootPid > 0L) {
                snapshotPosixPids();
            }
        }

        private boolean snapshotHandles() {
            if (!allowProcessHandle) {
                handlesComplete = false;
                return false;
            }
            try {
                Class<?> handleType = Class.forName("java.lang.ProcessHandle");
                Object rootHandle = Process.class.getMethod("toHandle").invoke(root);
                rememberHandle(handleType, rootHandle);
                Stream<?> descendants = (Stream<?>) handleType.getMethod("descendants")
                        .invoke(rootHandle);
                try {
                    Object[] values = descendants.toArray();
                    for (int i = 0; i < values.length; i++) {
                        rememberHandle(handleType, values[i]);
                    }
                } finally {
                    descendants.close();
                }
                handlesComplete = true;
                return true;
            } catch (Exception ignored) {
                handlesComplete = false;
                return false;
            }
        }

        private void rememberHandle(Class<?> handleType, Object handle) throws Exception {
            long pid = ((Number) handleType.getMethod("pid").invoke(handle)).longValue();
            handles.put(Long.valueOf(pid), handle);
        }

        private void snapshotPosixPids() {
            List<String> command = new ArrayList<String>();
            Collections.addAll(command, "ps", "-eo", "pid=,ppid=");
            UtilityCapture capture = captureUtility(command, 2_000L);
            if (!capture.success()) {
                return;
            }
            try {
                Map<Long, Long> parents = new LinkedHashMap<Long, Long>();
                for (int i = 0; i < capture.lines.size(); i++) {
                    String[] fields = capture.lines.get(i).trim().split("\\s+");
                    if (fields.length >= 2) {
                        try {
                            parents.put(Long.valueOf(Long.parseLong(fields[0])),
                                    Long.valueOf(Long.parseLong(fields[1])));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                boolean changed;
                do {
                    changed = false;
                    for (Map.Entry<Long, Long> entry : parents.entrySet()) {
                        long parent = entry.getValue().longValue();
                        if (parent == rootPid || osPids.containsKey(Long.valueOf(parent))) {
                            if (!osPids.containsKey(entry.getKey())) {
                                osPids.put(entry.getKey(), entry.getKey());
                                changed = true;
                            }
                        }
                    }
                } while (changed);
            } catch (RuntimeException ignored) {
            }
        }

        void terminate(boolean forcibly, InterruptFlag interrupt) {
            snapshot();
            if (isWindows()) {
                if (!handlesComplete) {
                    snapshotWindowsPids();
                }
                terminateWindowsTrackedPids(
                        new ArrayList<Long>(osPids.keySet()), rootPid, interrupt);
            }
            if (!handles.isEmpty()) {
                destroyHandles(forcibly);
            }
            if (!isWindows() && !handlesComplete) {
                terminatePosix(forcibly, interrupt);
            }
            if (forcibly) {
                root.destroyForcibly();
            } else {
                root.destroy();
            }
        }

        private void destroyHandles(boolean forcibly) {
            try {
                Class<?> handleType = Class.forName("java.lang.ProcessHandle");
                Method destroy = handleType.getMethod(forcibly
                        ? "destroyForcibly" : "destroy");
                List<Object> ordered = new ArrayList<Object>(handles.values());
                Collections.reverse(ordered);
                for (int i = 0; i < ordered.size(); i++) {
                    try {
                        destroy.invoke(ordered.get(i));
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
        }

        private void terminatePosix(boolean forcibly, InterruptFlag interrupt) {
            List<String> command = new ArrayList<String>();
            command.add("kill");
            command.add(forcibly ? "-KILL" : "-TERM");
            List<Long> pids = new ArrayList<Long>(osPids.keySet());
            Collections.reverse(pids);
            for (int i = 0; i < pids.size(); i++) {
                command.add(String.valueOf(pids.get(i)));
            }
            if (rootPid > 0L) {
                command.add(String.valueOf(rootPid));
            }
            if (command.size() > 2) {
                runUtility(command, interrupt);
            }
        }

        List<Long> survivingPids() {
            LinkedHashMap<Long, Long> alive = new LinkedHashMap<Long, Long>();
            for (Map.Entry<Long, Object> entry : handles.entrySet()) {
                if (isHandleAlive(entry.getValue())) {
                    alive.put(entry.getKey(), entry.getKey());
                }
            }
            for (Long pid : osPids.keySet()) {
                if (isPidAlive(pid.longValue())) {
                    alive.put(pid, pid);
                }
            }
            if (root.isAlive() && rootPid > 0L) {
                alive.put(Long.valueOf(rootPid), Long.valueOf(rootPid));
            }
            List<Long> survivors = new ArrayList<Long>(alive.keySet());
            Collections.sort(survivors);
            return survivors;
        }

        private void snapshotWindowsPids() {
            String script = "Get-CimInstance Win32_Process | ForEach-Object { "
                    + "[Console]::Out.WriteLine(('{0},{1}' -f $_.ProcessId,$_.ParentProcessId)) }";
            List<String> command = new ArrayList<String>();
            Collections.addAll(command, "powershell.exe", "-NoProfile",
                    "-NonInteractive", "-Command", script);
            UtilityCapture capture = captureUtility(command, 5_000L);
            if (capture.success()) {
                rememberOsDescendants(capture.lines, ",");
            }
        }

        private void rememberOsDescendants(List<String> lines, String separator) {
            Map<Long, Long> parents = new LinkedHashMap<Long, Long>();
            for (int i = 0; i < lines.size(); i++) {
                String[] fields = lines.get(i).trim().split(separator);
                if (fields.length >= 2) {
                    try {
                        parents.put(Long.valueOf(Long.parseLong(fields[0].trim())),
                                Long.valueOf(Long.parseLong(fields[1].trim())));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            boolean changed;
            do {
                changed = false;
                for (Map.Entry<Long, Long> entry : parents.entrySet()) {
                    long parent = entry.getValue().longValue();
                    if (parent == rootPid || osPids.containsKey(Long.valueOf(parent))) {
                        if (!osPids.containsKey(entry.getKey())) {
                            osPids.put(entry.getKey(), entry.getKey());
                            changed = true;
                        }
                    }
                }
            } while (changed);
        }
    }

    private static final class ArraysSupport {
        private ArraysSupport() {
        }

        static List<String> windowsTaskkill(long pid) {
            List<String> command = new ArrayList<String>();
            command.add("taskkill");
            command.add("/PID");
            command.add(String.valueOf(pid));
            command.add("/T");
            command.add("/F");
            return command;
        }
    }

    private static void terminateWindowsTrackedPids(List<Long> trackedPids,
                                                     long rootPid,
                                                     InterruptFlag interrupt) {
        List<Long> ordered = new ArrayList<Long>(trackedPids == null
                ? Collections.<Long>emptyList() : trackedPids);
        Collections.reverse(ordered);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        for (int i = 0; i < ordered.size(); i++) {
            long pid = ordered.get(i).longValue();
            if (pid <= 0L || pid == rootPid) {
                continue;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                break;
            }
            runUtility(ArraysSupport.windowsTaskkill(pid), interrupt,
                    Math.max(1L, Math.min(1_000L,
                            TimeUnit.NANOSECONDS.toMillis(remaining))));
        }
        if (rootPid > 0L) {
            long remaining = deadline - System.nanoTime();
            if (remaining > 0L) {
                runUtility(ArraysSupport.windowsTaskkill(rootPid), interrupt,
                        Math.max(1L, Math.min(1_000L,
                                TimeUnit.NANOSECONDS.toMillis(remaining))));
            }
        }
    }

    private static boolean isHandleAlive(Object handle) {
        if (handle == null) {
            return false;
        }
        try {
            Class<?> handleType = Class.forName("java.lang.ProcessHandle");
            return ((Boolean) handleType.getMethod("isAlive").invoke(handle)).booleanValue();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isPidAlive(long pid) {
        if (pid <= 0L) {
            return false;
        }
        try {
            Class<?> handleType = Class.forName("java.lang.ProcessHandle");
            Optional<?> handle = (Optional<?>) handleType.getMethod("of", long.class)
                    .invoke(null, Long.valueOf(pid));
            return handle.isPresent() && isHandleAlive(handle.get());
        } catch (Exception ignored) {
            if (isWindows()) {
                List<String> command = new ArrayList<String>();
                Collections.addAll(command, "tasklist", "/FI", "PID eq " + pid,
                        "/NH", "/FO", "CSV");
                UtilityCapture capture = captureUtility(command, 2_000L);
                if (!capture.complete || capture.interrupted) {
                    return true;
                }
                String quotedPid = "\"" + pid + "\"";
                for (int i = 0; i < capture.lines.size(); i++) {
                    if (capture.lines.get(i).contains(quotedPid)) {
                        return true;
                    }
                }
                return false;
            }
            List<String> command = new ArrayList<String>();
            Collections.addAll(command, "kill", "-0", String.valueOf(pid));
            UtilityCapture capture = captureUtility(command, 1_000L);
            return !capture.complete || capture.interrupted || capture.exitCode == 0;
        }
    }

    private static long processId(Process process) {
        try {
            Object value = Process.class.getMethod("pid").invoke(process);
            if (value instanceof Number) {
                return ((Number) value).longValue();
            }
        } catch (Exception ignored) {
        }
        Class<?> type = process.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField("pid");
                field.setAccessible(true);
                Object value = field.get(process);
                if (value instanceof Number) {
                    return ((Number) value).longValue();
                }
            } catch (Exception ignored) {
            }
            type = type.getSuperclass();
        }
        return -1L;
    }

    private static void runUtility(List<String> command, InterruptFlag interrupt) {
        runUtility(command, interrupt, 3_000L);
    }

    private static void runUtility(List<String> command,
                                   InterruptFlag interrupt,
                                   long timeoutMillis) {
        Process utility = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            builder.redirectOutput(new File(isWindows() ? "NUL" : "/dev/null"));
            utility = builder.start();
            closeQuietly(utility.getOutputStream());
            try {
                if (!utility.waitFor(Math.max(1L, timeoutMillis),
                        TimeUnit.MILLISECONDS)) {
                    utility.destroyForcibly();
                }
            } catch (InterruptedException e) {
                interrupt.interrupted = true;
                utility.destroyForcibly();
            }
        } catch (Exception ignored) {
        } finally {
            if (utility != null) {
                closeQuietly(utility.getInputStream());
                closeQuietly(utility.getErrorStream());
                closeQuietly(utility.getOutputStream());
            }
        }
    }

    private static UtilityCapture captureUtility(List<String> command,
                                                 long timeoutMillis) {
        Process utility = null;
        UtilityOutputDrainer drainer = null;
        InterruptFlag interrupt = new InterruptFlag();
        boolean exited = false;
        boolean forcedTermination = false;
        int exitCode = -1;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            utility = builder.start();
            closeQuietly(utility.getOutputStream());
            drainer = new UtilityOutputDrainer(utility.getInputStream());
            drainer.start();
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
                    Math.max(1L, timeoutMillis));
            while (utility.isAlive() && !interrupt.interrupted) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    break;
                }
                try {
                    utility.waitFor(Math.max(1L, Math.min(100L,
                            TimeUnit.NANOSECONDS.toMillis(remaining))),
                            TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    interrupt.interrupted = true;
                }
            }
            exited = !utility.isAlive();
            if (!exited) {
                forcedTermination = true;
                utility.destroyForcibly();
                waitForProcess(utility, 500L, interrupt);
                exited = !utility.isAlive();
            }
            if (exited) {
                exitCode = utility.exitValue();
            }
            long drainDeadline = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(1_000L);
            joinUntil(drainer.thread, drainDeadline, interrupt);
            if (drainer.thread.isAlive()) {
                closeQuietly(utility.getInputStream());
                joinUntil(drainer.thread, System.nanoTime()
                        + TimeUnit.MILLISECONDS.toNanos(500L), interrupt);
            }
            return new UtilityCapture(drainer.lines(), exitCode,
                    exited && !forcedTermination && !interrupt.interrupted
                            && !drainer.thread.isAlive()
                            && drainer.failure.get() == null && !drainer.truncated,
                    interrupt.interrupted);
        } catch (IOException e) {
            return new UtilityCapture(Collections.<String>emptyList(), -1,
                    false, interrupt.interrupted);
        } finally {
            if (utility != null) {
                if (utility.isAlive()) {
                    utility.destroyForcibly();
                }
                closeQuietly(utility.getInputStream());
                closeQuietly(utility.getErrorStream());
                closeQuietly(utility.getOutputStream());
            }
            if (interrupt.interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class UtilityCapture {
        private final List<String> lines;
        private final int exitCode;
        private final boolean complete;
        private final boolean interrupted;

        UtilityCapture(List<String> lines,
                       int exitCode,
                       boolean complete,
                       boolean interrupted) {
            this.lines = lines;
            this.exitCode = exitCode;
            this.complete = complete;
            this.interrupted = interrupted;
        }

        boolean success() {
            return complete && !interrupted && exitCode == 0;
        }
    }

    private static final class UtilityOutputDrainer implements Runnable {
        private static final int MAX_LINES = 200_000;
        private static final int MAX_CHARACTERS = 8 * 1024 * 1024;

        private final InputStream input;
        private final List<String> retained = new ArrayList<String>();
        private final AtomicReference<IOException> failure =
                new AtomicReference<IOException>();
        private final Thread thread;
        private int retainedCharacters;
        private volatile boolean truncated;

        UtilityOutputDrainer(InputStream input) {
            this.input = input;
            this.thread = new Thread(this, "flash-process-helper-output");
            this.thread.setDaemon(true);
        }

        void start() {
            thread.start();
        }

        @Override public void run() {
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    input, StandardCharsets.UTF_8));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (retained) {
                        if (retained.size() < MAX_LINES
                                && retainedCharacters + line.length() <= MAX_CHARACTERS) {
                            retained.add(line);
                            retainedCharacters += line.length();
                        } else {
                            truncated = true;
                        }
                    }
                }
            } catch (IOException e) {
                failure.compareAndSet(null, e);
            } finally {
                closeQuietly(reader);
            }
        }

        List<String> lines() {
            synchronized (retained) {
                return new ArrayList<String>(retained);
            }
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT)
                .contains("win");
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }
}
