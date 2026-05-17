package flash.pipeline.click.training.cellpose;

import flash.pipeline.cellpose.CellposeRuntime;
import flash.pipeline.ui.wizard.JsonIO;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    static final String COMMAND_FILENAME = "train_command.txt";
    static final String LOG_FILENAME = "cellpose_training.log";
    static final String MODELS_DIR = "models";
    private static final String FALLBACK_BASE_MODEL = "cyto3";

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
        TrainingArtifacts artifacts = prepareTrainingArtifacts(
                datasetDir, trainCommandFile, metadataBaseModel(datasetDir), config);
        final Path[] reportedModel = new Path[] {null};
        final IOException[] logFailure = new IOException[] {null};
        final Object logLock = new Object();
        ProgressSink safeProgress = progress == null ? NO_PROGRESS : progress;
        Map<String, Long> preexistingModels = snapshotModelFiles(artifacts.modelsDir);
        safeProgress.update(0.0, "Starting local Cellpose training...");

        BufferedWriter writer = Files.newBufferedWriter(
                artifacts.logFile,
                StandardCharsets.UTF_8);
        try {
            writeLog(writer, logLock, logFailure, "FLASH",
                    "Command: " + displayCommand(artifacts.command));
            ProcessSpec spec = new ProcessSpec(artifacts.command, artifacts.datasetDir);
            ProcessResult result = runner.run(spec,
                    new LoggingLineConsumer(writer, logLock, logFailure, "STDOUT",
                            artifacts.datasetDir, artifacts.modelsDir, reportedModel, safeProgress),
                    new LoggingLineConsumer(writer, logLock, logFailure, "STDERR",
                            artifacts.datasetDir, artifacts.modelsDir, reportedModel, safeProgress));
            if (logFailure[0] != null) {
                throw logFailure[0];
            }
            int exitCode = result == null ? -1 : result.exitCode;
            if (exitCode != 0) {
                throw new IOException("Local Cellpose training failed with exit code "
                        + exitCode + ". Log: " + artifacts.logFile);
            }
        } finally {
            writer.close();
        }

        Path modelFile = modelIfUsable(reportedModel[0], artifacts.modelsDir);
        if (modelFile == null) {
            modelFile = newestNewModelFile(artifacts.modelsDir, preexistingModels);
        }
        if (modelFile == null || !Files.isRegularFile(modelFile)) {
            throw new IOException("Local Cellpose training finished but no model file was found in "
                    + artifacts.modelsDir + ". Log: " + artifacts.logFile);
        }
        safeProgress.update(1.0, "Local Cellpose training complete.");
        return new TrainingResult(modelFile, artifacts.logFile, artifacts.commandFile,
                artifacts.modelsDir, 0);
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
        Path logFile = dir.resolve(LOG_FILENAME);
        Path commandFile = commandFile(dir, trainCommandFile);
        List<String> command;
        String commandText;
        if (Files.isRegularFile(commandFile)) {
            commandText = new String(Files.readAllBytes(commandFile), StandardCharsets.UTF_8).trim();
            command = parseCommandLine(commandText);
        } else {
            command = buildCommand(dir, baseModel, safeConfig);
            commandText = displayCommand(command);
            Files.write(commandFile, Collections.singletonList(commandText), StandardCharsets.UTF_8);
        }
        if (command.isEmpty()) {
            throw new IOException("Cellpose training command is empty: " + commandFile);
        }
        return new TrainingArtifacts(dir, commandFile, logFile, modelsDir,
                command, commandText);
    }

    public static List<String> buildCommand(Path datasetDir,
                                            String baseModel,
                                            Config config) {
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
        command.add("--train_batch_size");
        command.add(String.valueOf(Math.max(1, safeConfig.batchSize)));
        return command;
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

    private static Map<String, Long> snapshotModelFiles(Path modelsDir) throws IOException {
        Map<String, Long> out = new HashMap<String, Long>();
        List<Path> files = modelFiles(modelsDir);
        for (int i = 0; i < files.size(); i++) {
            Path file = files.get(i).toAbsolutePath().normalize();
            out.put(file.toString(), Long.valueOf(modifiedMillis(file)));
        }
        return out;
    }

    private static Path newestNewModelFile(Path modelsDir, Map<String, Long> preexisting) throws IOException {
        List<Path> files = modelFiles(modelsDir);
        Path newest = null;
        long newestTime = Long.MIN_VALUE;
        for (int i = 0; i < files.size(); i++) {
            Path file = files.get(i).toAbsolutePath().normalize();
            String key = file.toString();
            long modified = modifiedMillis(file);
            Long before = preexisting == null ? null : preexisting.get(key);
            if (before != null && modified <= before.longValue()) {
                continue;
            }
            if (newest == null || modified >= newestTime) {
                newest = file;
                newestTime = modified;
            }
        }
        return newest;
    }

    private static List<Path> modelFiles(Path modelsDir) throws IOException {
        List<Path> out = new ArrayList<Path>();
        if (modelsDir == null || !Files.isDirectory(modelsDir)) {
            return out;
        }
        Stream<Path> stream = Files.walk(modelsDir);
        try {
            Iterator<Path> iterator = stream.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (Files.isRegularFile(path)) {
                    out.add(path.toAbsolutePath().normalize());
                }
            }
        } finally {
            stream.close();
        }
        return out;
    }

    private static long modifiedMillis(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return Long.MIN_VALUE;
        }
    }

    private static Path modelIfUsable(Path candidate, Path modelsDir) {
        if (candidate == null || modelsDir == null) {
            return null;
        }
        Path model = candidate.toAbsolutePath().normalize();
        Path root = modelsDir.toAbsolutePath().normalize();
        if (!model.startsWith(root) || !Files.isRegularFile(model)) {
            return null;
        }
        return model;
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

    private static Path parseReportedModel(Path datasetDir, Path modelsDir, String line) {
        String text = line == null ? "" : line.trim();
        if (text.isEmpty()) {
            return null;
        }
        Path parsed = parseMarker(datasetDir, text, "FLASH_CELLPOSE_MODEL=");
        if (parsed == null) {
            parsed = parseMarker(datasetDir, text, "MODEL_PATH=");
        }
        if (parsed == null) {
            parsed = parseHumanModelPath(datasetDir, text);
        }
        return modelIfUsable(parsed, modelsDir);
    }

    private static Path parseMarker(Path datasetDir, String text, String marker) {
        int index = text.indexOf(marker);
        if (index < 0) {
            return null;
        }
        return pathFromValue(datasetDir, text.substring(index + marker.length()));
    }

    private static Path parseHumanModelPath(Path datasetDir, String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        String[] markers = new String[] {
                "model saved to",
                "saved model to",
                "model saved:"
        };
        for (int i = 0; i < markers.length; i++) {
            int index = lower.indexOf(markers[i]);
            if (index >= 0) {
                String value = text.substring(index + markers[i].length());
                while (value.startsWith(":") || value.startsWith("=")) {
                    value = value.substring(1).trim();
                }
                return pathFromValue(datasetDir, value);
            }
        }
        return null;
    }

    private static Path pathFromValue(Path datasetDir, String value) {
        String cleaned = stripQuotes(value);
        if (cleaned.isEmpty()) {
            return null;
        }
        try {
            Path path = Paths.get(cleaned);
            if (!path.isAbsolute() && datasetDir != null) {
                path = datasetDir.resolve(path);
            }
            return path.toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String stripQuotes(String value) {
        String text = value == null ? "" : value.trim();
        if (text.length() >= 2
                && ((text.startsWith("\"") && text.endsWith("\""))
                || (text.startsWith("'") && text.endsWith("'")))) {
            text = text.substring(1, text.length() - 1).trim();
        }
        return text;
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
        public final int epochs;
        public final int batchSize;
        public final double learningRate;
        public final double weightDecay;

        public Config(boolean enabled,
                      String pythonExecutable,
                      int epochs,
                      int batchSize,
                      double learningRate,
                      double weightDecay) {
            this.enabled = enabled;
            this.pythonExecutable = cleanOrDefault(pythonExecutable, "python");
            this.epochs = Math.max(1, epochs);
            this.batchSize = Math.max(1, batchSize);
            this.learningRate = Math.max(0.0, learningRate);
            this.weightDecay = Math.max(0.0, weightDecay);
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
                    Boolean.getBoolean(LOCAL_ENABLED_PROPERTY),
                    python,
                    intProperty(EPOCHS_PROPERTY, 100, 1),
                    intProperty(BATCH_SIZE_PROPERTY, 1, 1),
                    doubleProperty(LEARNING_RATE_PROPERTY, 0.00001, 0.0),
                    doubleProperty(WEIGHT_DECAY_PROPERTY, 0.1, 0.0));
        }
    }

    public static final class TrainingArtifacts {
        public final Path datasetDir;
        public final Path commandFile;
        public final Path logFile;
        public final Path modelsDir;
        public final List<String> command;
        public final String commandText;

        TrainingArtifacts(Path datasetDir,
                          Path commandFile,
                          Path logFile,
                          Path modelsDir,
                          List<String> command,
                          String commandText) {
            this.datasetDir = datasetDir;
            this.commandFile = commandFile;
            this.logFile = logFile;
            this.modelsDir = modelsDir;
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

        ProcessSpec(List<String> command, Path workingDirectory) {
            this.command = Collections.unmodifiableList(new ArrayList<String>(
                    command == null ? Collections.<String>emptyList() : command));
            this.workingDirectory = workingDirectory;
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
        private final Path modelsDir;
        private final Path[] reportedModel;
        private final ProgressSink progress;

        LoggingLineConsumer(BufferedWriter writer,
                            Object logLock,
                            IOException[] logFailure,
                            String stream,
                            Path datasetDir,
                            Path modelsDir,
                            Path[] reportedModel,
                            ProgressSink progress) {
            this.writer = writer;
            this.logLock = logLock;
            this.logFailure = logFailure;
            this.stream = stream;
            this.datasetDir = datasetDir;
            this.modelsDir = modelsDir;
            this.reportedModel = reportedModel;
            this.progress = progress;
        }

        @Override public void accept(String line) {
            writeLog(writer, logLock, logFailure, stream, line);
            Path model = parseReportedModel(datasetDir, modelsDir, line);
            if (model != null) {
                reportedModel[0] = model;
            }
            CellposeTrainingProgressParser.Progress parsed =
                    CellposeTrainingProgressParser.parse(line);
            if (parsed != null) {
                progress.update(0.05 + (0.90 * parsed.fraction), parsed.message);
            }
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
            Thread outThread = streamThread(process, true, stdout);
            Thread errThread = streamThread(process, false, stderr);
            outThread.start();
            errThread.start();
            int exit;
            try {
                exit = process.waitFor();
            } catch (InterruptedException e) {
                process.destroy();
                throw e;
            } finally {
                outThread.join();
                errThread.join();
            }
            return new ProcessResult(exit);
        }

        private static Thread streamThread(final Process process,
                                           final boolean stdout,
                                           final LineConsumer consumer) {
            return new Thread(new Runnable() {
                @Override public void run() {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(
                            stdout ? process.getInputStream() : process.getErrorStream(),
                            StandardCharsets.UTF_8));
                    try {
                        String line;
                        while ((line = reader.readLine()) != null) {
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
            }, stdout ? "flash-cellpose-train-stdout" : "flash-cellpose-train-stderr");
        }
    }
}
