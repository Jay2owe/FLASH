package flash.pipeline.cellpose;

import flash.pipeline.image.GpuConcurrency;
import flash.pipeline.ui.wizard.JsonIO;

import ij.IJ;
import ij.ImagePlus;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class CellposePersistentWorker implements Closeable {

    private static final String SCRIPT_RESOURCE =
            "/flash/pipeline/cellpose/cellpose_loop.py";
    private static final long READY_TIMEOUT_SECONDS = 30L;
    private static final long CLOSE_TIMEOUT_SECONDS = 5L;

    private final ImagePlus referenceInput;
    private final ImagePlus runtimeInput;
    private final String channelName;
    private final Path scriptPath;
    private final ExecutorService executor;
    private final TailBuffer stderrTail = new TailBuffer(80);

    private Process process;
    private BufferedWriter stdin;
    private BufferedReader stdout;
    private Thread stderrThread;
    private boolean permitAcquired;
    private boolean closed;

    public CellposePersistentWorker(Path imagePath,
                                    Path outputDir,
                                    ImagePlus referenceInput,
                                    ImagePlus runtimeInput,
                                    String model,
                                    boolean useGpu,
                                    String channelName) throws Exception {
        if (imagePath == null) {
            throw new IllegalArgumentException("imagePath must not be null");
        }
        if (outputDir == null) {
            throw new IllegalArgumentException("outputDir must not be null");
        }
        if (runtimeInput == null) {
            throw new IllegalArgumentException("runtimeInput must not be null");
        }
        this.referenceInput = referenceInput == null ? runtimeInput : referenceInput;
        this.runtimeInput = runtimeInput;
        this.channelName = channelName == null ? "" : channelName;
        this.scriptPath = extractScript();
        this.executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "flash-cellpose-persistent-worker");
                thread.setDaemon(true);
                return thread;
            }
        });
        try {
            start(imagePath, outputDir, model, useGpu);
            waitUntilReady();
        } catch (Exception e) {
            close();
            throw e;
        }
    }

    public Future<CellposeWorkerResult> submit(final CellposeWorkerRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        return executor.submit(new Callable<CellposeWorkerResult>() {
            @Override public CellposeWorkerResult call() throws Exception {
                return runRequest(request);
            }
        });
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (stdin != null) {
                try {
                    stdin.close();
                } catch (Exception ignored) {
                }
            }
            if (process != null) {
                try {
                    if (!process.waitFor(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        process.destroy();
                        if (!process.waitFor(1L, TimeUnit.SECONDS)) {
                            killProcessTree(process);
                            process.destroyForcibly();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    killProcessTree(process);
                    process.destroyForcibly();
                }
            }
            closeProcessStreams();
        } finally {
            executor.shutdownNow();
            if (permitAcquired) {
                permitAcquired = false;
                GpuConcurrency.gpuSemaphore().release();
            }
            try {
                Files.deleteIfExists(scriptPath);
            } catch (Exception ignored) {
            }
        }
    }

    String stderrTailForTest() {
        return stderrTail.text();
    }

    private void start(Path imagePath,
                       Path outputDir,
                       String model,
                       boolean useGpu) throws Exception {
        CellposeRuntime.Status runtime = CellposeRuntime.probeConfigured();
        if (!runtime.ready) {
            throw new IllegalStateException(runtime.message
                    + (runtime.details.isEmpty() ? "" : "\n" + runtime.details));
        }
        Files.createDirectories(outputDir);
        List<String> command = buildCommand(runtime.pythonPath,
                imagePath, outputDir, model, useGpu);
        String chTag = channelName.isEmpty() ? "" : " [" + channelName + "]";
        IJ.log("    Cellpose persistent" + chTag + " command: "
                + String.join(" ", command));

        GpuConcurrency.gpuSemaphore().acquire();
        permitAcquired = true;

        ProcessBuilder pb = new ProcessBuilder(command);
        Map<String, String> env = pb.environment();
        int threads = GpuConcurrency.threadsPerInference();
        String threadText = Integer.toString(threads);
        env.put("OMP_NUM_THREADS", threadText);
        env.put("MKL_NUM_THREADS", threadText);
        env.put("OPENBLAS_NUM_THREADS", threadText);
        env.put("NUMEXPR_NUM_THREADS", threadText);
        IJ.log("    Cellpose persistent" + chTag + " thread cap: "
                + threadText + " threads (OMP/MKL/OPENBLAS/NUMEXPR)");

        process = pb.start();
        stdin = new BufferedWriter(new OutputStreamWriter(
                process.getOutputStream(), StandardCharsets.UTF_8));
        stdout = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8));
        startStderrDrainer(process.getErrorStream());
    }

    private List<String> buildCommand(String pythonPath,
                                      Path imagePath,
                                      Path outputDir,
                                      String model,
                                      boolean useGpu) {
        List<String> command = new ArrayList<String>();
        command.add(CellposeRuntime.normalizeExecutablePath(pythonPath));
        command.add(scriptPath.toString());
        command.add(CellposeModel.fromToken(model).token());
        command.add(imagePath.toString());
        command.add(outputDir.toString());
        if (useGpu) {
            command.add("--gpu");
        }
        boolean hasSecondChannel = runtimeInput.getNChannels() > 1;
        if (hasSecondChannel) {
            command.add("--has-second-channel");
            command.add("--channel-axis");
            command.add(runtimeInput.getNSlices() > 1 ? "1" : "0");
        }
        if (runtimeInput.getNSlices() > 1) {
            command.add("--do-3d");
            command.add("--z-axis");
            command.add("0");
            Double anisotropy = Cellpose3DRunner.computeAnisotropy(runtimeInput);
            if (anisotropy != null) {
                command.add("--anisotropy");
                command.add(String.valueOf(anisotropy.doubleValue()));
            }
        }
        return command;
    }

    private void waitUntilReady() throws Exception {
        Future<Map<String, Object>> ready = executor.submit(
                new Callable<Map<String, Object>>() {
                    @Override public Map<String, Object> call() throws Exception {
                        return readProtocolObject();
                    }
                });
        Map<String, Object> message;
        try {
            message = ready.get(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            ready.cancel(true);
            throw new IllegalStateException("Cellpose helper did not become ready within "
                    + READY_TIMEOUT_SECONDS + " seconds." + stderrSuffix());
        }
        Object readyValue = message.get("ready");
        if (!(readyValue instanceof Boolean)
                || !((Boolean) readyValue).booleanValue()) {
            throw new IllegalStateException("Cellpose helper returned an unexpected "
                    + "startup message: " + JsonIO.write(message) + stderrSuffix());
        }
    }

    private CellposeWorkerResult runRequest(CellposeWorkerRequest request) throws Exception {
        if (closed) {
            return CellposeWorkerResult.failure(request.id(), "Cellpose helper is closed.");
        }
        if (process == null || !process.isAlive()) {
            return CellposeWorkerResult.failure(request.id(),
                    "Cellpose helper process is not running." + stderrSuffix());
        }
        Map<String, Object> json = requestPayload(request, runtimeInput);
        stdin.write(JsonIO.write(json));
        stdin.write('\n');
        stdin.flush();

        Map<String, Object> response = readProtocolObject();
        String id = stringValue(response.get("id"), request.id());
        String error = stringValue(response.get("error"), "");
        if (!error.isEmpty()) {
            String traceback = stringValue(response.get("traceback"), "");
            return CellposeWorkerResult.failure(id, error
                    + (traceback.isEmpty() ? "" : "\n" + traceback)
                    + stderrSuffix());
        }
        String maskPath = stringValue(response.get("mask_path"), "");
        if (maskPath.isEmpty()) {
            return CellposeWorkerResult.failure(id,
                    "Cellpose helper did not return a mask path." + stderrSuffix());
        }
        ImagePlus label = Cellpose3DRunner.readMaskImage(
                Paths.get(maskPath), referenceInput, channelName);
        if (label == null) {
            return CellposeWorkerResult.failure(id,
                    "Cellpose helper returned an unreadable mask: "
                            + maskPath + stderrSuffix());
        }
        return CellposeWorkerResult.success(id, label,
                longValue(response.get("duration_ms"), 0L),
                cellprobPath(response));
    }

    static Map<String, Object> requestPayloadForTest(CellposeWorkerRequest request,
                                                     ImagePlus runtimeInput) {
        return requestPayload(request, runtimeInput);
    }

    static Optional<Path> cellprobPathForTest(Map<String, Object> response) {
        return cellprobPath(response);
    }

    private static Map<String, Object> requestPayload(CellposeWorkerRequest request,
                                                      ImagePlus runtimeInput) {
        Map<String, Object> json = new LinkedHashMap<String, Object>();
        json.put("id", request.id());
        json.put("diameter", Double.valueOf(Double.parseDouble(
                Cellpose3DRunner.formatDiameterPixels(runtimeInput,
                        request.diameter()))));
        json.put("flow_threshold", Double.valueOf(request.flowThreshold()));
        json.put("cellprob_threshold", Double.valueOf(request.cellprobThreshold()));
        if (request.dumpCellprob()) {
            json.put("dump_cellprob", Boolean.TRUE);
        }
        return json;
    }

    private static Optional<Path> cellprobPath(Map<String, Object> response) {
        String path = stringValue(response == null ? null : response.get("cellprob_path"), "");
        if (path.trim().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Paths.get(path));
    }

    private Map<String, Object> readProtocolObject() throws Exception {
        while (true) {
            if (stdout == null) {
                throw new IllegalStateException("Cellpose helper stdout is closed."
                        + stderrSuffix());
            }
            String line = stdout.readLine();
            if (line == null) {
                throw new IllegalStateException("Cellpose helper exited before "
                        + "writing a protocol response." + stderrSuffix());
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!trimmed.startsWith("{")) {
                stderrTail.add("[stdout] " + trimmed);
                continue;
            }
            try {
                return JsonIO.parseObject(trimmed);
            } catch (Exception e) {
                stderrTail.add("[stdout] " + trimmed);
            }
        }
    }

    private void startStderrDrainer(final InputStream stream) {
        stderrThread = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(stream, StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stderrTail.add(line);
                    }
                } catch (Exception ignored) {
                }
            }
        }, "flash-cellpose-persistent-stderr");
        stderrThread.setDaemon(true);
        stderrThread.start();
    }

    private void closeProcessStreams() {
        if (stdout != null) {
            try {
                stdout.close();
            } catch (Exception ignored) {
            }
        }
        if (process != null) {
            try {
                process.getErrorStream().close();
            } catch (Exception ignored) {
            }
            try {
                process.getInputStream().close();
            } catch (Exception ignored) {
            }
            try {
                process.getOutputStream().close();
            } catch (Exception ignored) {
            }
        }
    }

    private Path extractScript() throws Exception {
        InputStream in = CellposePersistentWorker.class.getResourceAsStream(
                SCRIPT_RESOURCE);
        if (in == null) {
            throw new IllegalStateException("Missing bundled Cellpose helper: "
                    + SCRIPT_RESOURCE);
        }
        Path temp = Files.createTempFile("flash_cellpose_loop_", ".py");
        try {
            Files.copy(in, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
        return temp;
    }

    private String stderrSuffix() {
        String tail = stderrTail.text();
        return tail.isEmpty() ? "" : "\nHelper output:\n" + tail;
    }

    private static void killProcessTree(Process process) {
        if (process == null) {
            return;
        }
        Long pid = processId(process);
        if (pid == null || !isWindows()) {
            return;
        }
        try {
            Process taskkill = new ProcessBuilder(
                    "taskkill", "/F", "/T", "/PID", String.valueOf(pid.longValue()))
                    .start();
            taskkill.getInputStream().close();
            taskkill.getErrorStream().close();
            taskkill.getOutputStream().close();
            taskkill.waitFor(3L, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    private static Long processId(Process process) {
        try {
            Method pidMethod = Process.class.getMethod("pid");
            Object value = pidMethod.invoke(process);
            if (value instanceof Number) {
                return Long.valueOf(((Number) value).longValue());
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
                    return Long.valueOf(((Number) value).longValue());
                }
            } catch (Exception ignored) {
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private static String stringValue(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        return String.valueOf(value);
    }

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static final class TailBuffer {
        private final int maxLines;
        private final ArrayDeque<String> lines = new ArrayDeque<String>();

        TailBuffer(int maxLines) {
            this.maxLines = Math.max(1, maxLines);
        }

        synchronized void add(String line) {
            if (line == null || line.isEmpty()) {
                return;
            }
            lines.addLast(line);
            while (lines.size() > maxLines) {
                lines.removeFirst();
            }
        }

        synchronized String text() {
            StringBuilder out = new StringBuilder();
            for (String line : lines) {
                if (out.length() > 0) {
                    out.append('\n');
                }
                out.append(line);
            }
            return out.toString();
        }
    }
}
