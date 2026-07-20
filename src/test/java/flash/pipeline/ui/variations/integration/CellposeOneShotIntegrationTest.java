package flash.pipeline.ui.variations.integration;

import flash.pipeline.cellpose.Cellpose3DRunner;
import flash.pipeline.cellpose.CellposeRuntime;
import flash.pipeline.click.training.cellpose.CellposeLocalTrainingService;
import flash.pipeline.testutil.TestWait;
import flash.pipeline.ui.variations.CropSpec;
import flash.pipeline.ui.variations.VariationResult;
import flash.pipeline.ui.variations.strategy.CellposeOneShot;

import ij.IJ;
import ij.ImagePlus;

import org.junit.Assume;
import org.junit.Ignore;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Deterministic process-lifecycle fixtures plus the opt-in live Cellpose test.
 * <p>
 * Remove {@link Ignore} from the live test and run after configuring Cellpose:
 * {@code .\mvnw.cmd '-Denforcer.skip=true' '-Dtest=flash.pipeline.ui.variations.integration.CellposeOneShotIntegrationTest' test}
 */
public class CellposeOneShotIntegrationTest {

    private static final int BURST_LINES = 512;

    @Test
    public void sharedProcessFixtureDrainsBothStreamsAndReportsCrash() throws Exception {
        StubProcess burst = StubProcess.launch(StubMode.BURST);
        long burstPid = burst.rootPid();
        try {
            burst.awaitReady();
            assertTrue("Burst helper did not exit", burst.awaitExit(5_000L));
            burst.awaitDrainers();
            assertEquals(0, burst.exitCode());
            assertEquals(BURST_LINES + 2, burst.stdoutLineCount());
            assertEquals(BURST_LINES + 1, burst.stderrLineCount());
            assertTrue("stdout tail was not bounded",
                    burst.stdoutRetainedLineCount() <= 128);
            assertTrue("stderr tail was not bounded",
                    burst.stderrRetainedLineCount() <= 128);
            assertTrue(burst.stdoutContains("BURST_STDOUT_DONE"));
            assertTrue(burst.stderrContains("BURST_STDERR_DONE"));
        } finally {
            burst.close();
        }
        StubProcess.awaitPidExit(burstPid);

        StubProcess crash = StubProcess.launch(StubMode.CRASH);
        long crashPid = crash.rootPid();
        try {
            crash.awaitReady();
            assertTrue("Crash helper did not exit", crash.awaitExit(5_000L));
            crash.awaitDrainers();
            assertEquals(23, crash.exitCode());
            assertTrue(crash.stdoutContains("CRASH_STDOUT"));
            assertTrue(crash.stderrContains("CRASH_STDERR"));
        } finally {
            crash.close();
        }
        StubProcess.awaitPidExit(crashPid);
    }

    @Test
    public void oneShotTimeoutAndCancellationAreBoundedAndLeakFree() throws Exception {
        StubProcess helper = StubProcess.launch(StubMode.HANG);
        long pid = helper.rootPid();
        try {
            helper.awaitReady();
            assertFalse("Hanging helper unexpectedly exited",
                    helper.awaitExit(100L));
            helper.cancel();
            helper.assertStopped();
            helper.awaitDrainers();
        } finally {
            helper.close();
        }
        StubProcess.awaitPidExit(pid);
    }

    @Test
    public void oneShotDiagnosticsBoundHundredThousandLineTailAndKeepFinalUnicodeBlock()
            throws Exception {
        Path folder = Files.createTempDirectory("flash-cellpose-diag-\u03bc space-");
        Path logFile = folder.resolve("one shot \u8111 diagnostics.log");
        String prefix = "B20-one-shot-" + System.nanoTime();
        String before = IJ.getLog();
        before = before == null ? "" : before;
        try {
            CellposeLocalTrainingService.DiagnosticSnapshot snapshot =
                    invokeManagedCommand(diagnosticCommand(100_000), folder,
                            logFile, 30L, prefix);

            assertEquals(100_002L, snapshot.stdoutLineCount);
            assertEquals(100_002L, snapshot.stderrLineCount);
            assertTrue(snapshot.stdoutTail.size() <= 64);
            assertTrue(snapshot.stderrTail.size() <= 64);
            assertTrue(snapshot.stdoutRetainedCharacters <= 64 * 1024);
            assertTrue(snapshot.stderrRetainedCharacters <= 64 * 1024);
            assertTrue(snapshot.stdoutTail.contains(DiagnosticOutputMain.STDOUT_FINAL));
            assertTrue(snapshot.stderrTail.contains(DiagnosticOutputMain.STDERR_FINAL));

            String durable = new String(Files.readAllBytes(logFile), StandardCharsets.UTF_8);
            assertTrue(durable.contains("[STDOUT] READY"));
            assertTrue(durable.contains("[STDERR] ERR_READY"));
            assertTrue(durable.contains("[STDOUT] " + DiagnosticOutputMain.STDOUT_FINAL));
            assertTrue(durable.contains("[STDERR] " + DiagnosticOutputMain.STDERR_FINAL));
            assertTrue(durable.indexOf("[STDOUT] OUT-0")
                    < durable.indexOf("[STDOUT] " + DiagnosticOutputMain.STDOUT_FINAL));

            String after = IJ.getLog();
            after = after == null ? "" : after;
            String added = after.startsWith(before) ? after.substring(before.length()) : after;
            assertTrue("Shared ImageJ logging was not throttled: "
                            + occurrences(added, prefix),
                    occurrences(added, prefix) <= 240);
        } finally {
            deleteTree(folder);
        }
    }

    @Test
    public void oneShotManagedTimeoutTerminatesRootAndDescendant() throws Exception {
        Path folder = Files.createTempDirectory("flash-cellpose-tree-");
        long rootPid = -1L;
        long childPid = -1L;
        try {
            invokeManagedCommand(stubManagedCommand(StubMode.TREE, 8), folder,
                    folder.resolve("tree-timeout.log"), 1L, "B20-tree-timeout");
            org.junit.Assert.fail("Expected managed one-shot timeout");
        } catch (IOException expected) {
            rootPid = numberAfter(causeMessages(expected), "PID ");
            childPid = numberAfter(expected.getMessage(), "DESCENDANT_PID=");
            assertTrue(expected.getMessage().contains("tree-timeout.log"));
            assertTrue(causeMessages(expected).contains("timed out"));
        } finally {
            deleteTree(folder);
        }
        assertTrue("Root PID was not reported", rootPid > 0L);
        assertTrue("Descendant PID was not retained in the bounded tail", childPid > 0L);
        try {
            StubProcess.awaitPidExit(rootPid);
            StubProcess.awaitPidExit(childPid);
            assertFalse(StubProcess.isPidAlive(rootPid));
            assertFalse(StubProcess.isPidAlive(childPid));
        } finally {
            forcePidCleanup(rootPid);
            forcePidCleanup(childPid);
        }
    }

    private static CellposeLocalTrainingService.DiagnosticSnapshot invokeManagedCommand(
            List<String> command,
            Path workingDirectory,
            Path logFile,
            long timeoutSeconds,
            String prefix) throws Exception {
        Method method = Cellpose3DRunner.class.getDeclaredMethod("runManagedCommand",
                List.class, Path.class, Path.class, long.class, String.class);
        method.setAccessible(true);
        try {
            return (CellposeLocalTrainingService.DiagnosticSnapshot) method.invoke(null,
                    command, workingDirectory, logFile,
                    Long.valueOf(timeoutSeconds), prefix);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        }
    }

    private static List<String> diagnosticCommand(int lines) {
        return javaCommand(DiagnosticOutputMain.class, String.valueOf(lines));
    }

    private static List<String> stubManagedCommand(StubMode mode, int lines) {
        return javaCommand(StubProcessMain.class, mode.name(), String.valueOf(lines));
    }

    private static List<String> javaCommand(Class<?> mainClass, String... arguments) {
        List<String> command = new ArrayList<String>();
        String executable = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
        command.add(Paths.get(System.getProperty("java.home"), "bin", executable)
                .toAbsolutePath().normalize().toString());
        command.add("-cp");
        String classpath = System.getProperty("surefire.test.class.path");
        if (classpath == null || classpath.trim().isEmpty()) {
            classpath = System.getProperty("java.class.path");
        }
        command.add(classpath);
        command.add(mainClass.getName());
        Collections.addAll(command, arguments);
        return command;
    }

    private static String causeMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null) {
                messages.append(current.getMessage()).append('\n');
            }
            current = current.getCause();
        }
        return messages.toString();
    }

    private static long numberAfter(String text, String marker) {
        int start = text == null ? -1 : text.indexOf(marker);
        if (start < 0) {
            return -1L;
        }
        start += marker.length();
        int end = start;
        while (end < text.length() && Character.isDigit(text.charAt(end))) {
            end++;
        }
        if (end == start) {
            return -1L;
        }
        return Long.parseLong(text.substring(start, end));
    }

    private static void forcePidCleanup(long pid) {
        if (pid <= 0L) {
            return;
        }
        Process killer = null;
        try {
            List<String> command = new ArrayList<String>();
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT)
                    .contains("win")) {
                Collections.addAll(command, "taskkill", "/PID", String.valueOf(pid),
                        "/T", "/F");
            } else {
                Collections.addAll(command, "kill", "-KILL", String.valueOf(pid));
            }
            killer = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(new File(System.getProperty("os.name", "")
                            .toLowerCase(Locale.ROOT).contains("win") ? "NUL" : "/dev/null"))
                    .start();
            killer.waitFor(3L, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        } finally {
            if (killer != null && killer.isAlive()) {
                killer.destroyForcibly();
            }
        }
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        Stream<Path> stream = Files.walk(root);
        try {
            Path[] paths = stream.sorted(Collections.reverseOrder()).toArray(Path[]::new);
            for (int i = 0; i < paths.length; i++) {
                Files.deleteIfExists(paths[i]);
            }
        } finally {
            stream.close();
        }
    }

    /** B20-local output helper; the shared A56 process fixture below is unchanged. */
    public static final class DiagnosticOutputMain {
        static final String STDOUT_FINAL =
                "FINAL_STDOUT=path with spaces/\u03bcglia/\u8111/no-newline";
        static final String STDERR_FINAL =
                "FINAL_STDERR=trace with spaces/\u03b1\u03b2\u03b3/no-newline";

        private DiagnosticOutputMain() {
        }

        public static void main(String[] args) throws Exception {
            int lines = Integer.parseInt(args[0]);
            BufferedWriter stdout = new BufferedWriter(new OutputStreamWriter(
                    System.out, StandardCharsets.UTF_8));
            BufferedWriter stderr = new BufferedWriter(new OutputStreamWriter(
                    System.err, StandardCharsets.UTF_8));
            stdout.write("READY");
            stdout.newLine();
            stderr.write("ERR_READY");
            stderr.newLine();
            for (int i = 0; i < lines; i++) {
                stdout.write("OUT-" + i);
                stdout.newLine();
                stderr.write("ERR-" + i);
                stderr.newLine();
                if ((i & 1023) == 0) {
                    stdout.flush();
                    stderr.flush();
                }
            }
            stdout.write(STDOUT_FINAL);
            stderr.write(STDERR_FINAL);
            stdout.flush();
            stderr.flush();
        }
    }

    @Ignore("TODO(cellpose-live-runtime): requires an installed Cellpose runtime; covered by the live-engine validation lane.")
    @Test
    public void threeCellSweepUsesOneShotFallbackWhenCellposeIsAvailable() throws Exception {
        CellposeRuntime.Status status = CellposeRuntime.probeConfigured();
        Assume.assumeTrue(status.message + "\n" + status.details, status.ready);

        ImagePlus source = VariationIntegrationTestSupport.loadSyntheticBlobStack();
        CellposeOneShot strategy = new CellposeOneShot(source,
                CropSpec.full(),
                null,
                new VariationIntegrationTestSupport.RealCellposePreviewAdapter(),
                VariationIntegrationTestSupport.cellposeBaseParameters(),
                null);
        List<VariationResult> results = new ArrayList<VariationResult>();

        strategy.dispatch(VariationIntegrationTestSupport.cellposeThreeCellSweep(),
                results::add,
                () -> false);

        assertEquals(3, results.size());
        for (int i = 0; i < results.size(); i++) {
            VariationResult result = results.get(i);
            assertFalse(result.hasError());
            assertNotNull(result.label());
            assertTrue("Expected Cellpose objects in cell " + i,
                    result.nObjects() > 0);
        }
    }

    /**
     * Deterministic subprocess behaviors shared by later Cellpose lifecycle tests.
     * The helper is Java-only so the contract does not depend on a Cellpose install.
     */
    public enum StubMode {
        BURST,
        CRASH,
        HANG,
        PERSISTENT,
        TREE,
        LEAF
    }

    /**
     * Owns a stub process, drains both streams, and enforces bounded tree cleanup.
     */
    public static final class StubProcess implements AutoCloseable {
        private static final int TAIL_LINES = 128;
        private static final long READY_TIMEOUT_MS = 5_000L;
        private static final long DRAIN_TIMEOUT_MS = 3_000L;
        private static final long STOP_TIMEOUT_MS = 5_000L;

        private final Process process;
        private final BufferedWriter stdin;
        private final LineCollector stdout;
        private final LineCollector stderr;
        private final long rootPid;
        private boolean closed;

        private StubProcess(Process process) throws Exception {
            this.process = process;
            this.rootPid = processId(process);
            if (rootPid <= 0L) {
                process.destroyForcibly();
                throw new IllegalStateException("Could not determine stub process ID");
            }
            this.stdin = new BufferedWriter(new OutputStreamWriter(
                    process.getOutputStream(), StandardCharsets.UTF_8));
            this.stdout = new LineCollector(process.getInputStream(),
                    "flash-cellpose-stub-stdout-" + rootPid);
            this.stderr = new LineCollector(process.getErrorStream(),
                    "flash-cellpose-stub-stderr-" + rootPid);
            stdout.start();
            stderr.start();
        }

        public static StubProcess launch(StubMode mode) throws Exception {
            return launch(mode, null, BURST_LINES);
        }

        public static StubProcess launch(StubMode mode,
                                         Path workingDirectory,
                                         int burstLines) throws Exception {
            if (mode == null || mode == StubMode.LEAF) {
                throw new IllegalArgumentException("A root stub mode is required");
            }
            List<String> command = javaCommand(mode, Math.max(1, burstLines));
            ProcessBuilder builder = new ProcessBuilder(command);
            if (workingDirectory != null) {
                builder.directory(workingDirectory.toFile());
            }
            return new StubProcess(builder.start());
        }

        public long rootPid() {
            return rootPid;
        }

        public void awaitReady() throws Exception {
            stdout.awaitReady(READY_TIMEOUT_MS);
        }

        public void awaitStdout(String line) throws Exception {
            stdout.awaitLine(line, READY_TIMEOUT_MS);
        }

        public void awaitStderr(String line) throws Exception {
            stderr.awaitLine(line, READY_TIMEOUT_MS);
        }

        public boolean awaitExit(long timeoutMillis) throws InterruptedException {
            if (timeoutMillis <= 0L) {
                throw new IllegalArgumentException("timeoutMillis must be positive");
            }
            return process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
        }

        public int exitCode() {
            return process.exitValue();
        }

        public void writeLine(String line) throws IOException {
            stdin.write(line == null ? "" : line);
            stdin.write('\n');
            stdin.flush();
        }

        public int stdoutLineCount() {
            return stdout.lineCount();
        }

        public int stderrLineCount() {
            return stderr.lineCount();
        }

        public int stdoutRetainedLineCount() {
            return stdout.retainedLineCount();
        }

        public int stderrRetainedLineCount() {
            return stderr.retainedLineCount();
        }

        public boolean stdoutContains(String line) {
            return stdout.contains(line);
        }

        public boolean stderrContains(String line) {
            return stderr.contains(line);
        }

        public long descendantPid() {
            String value = stdout.valueAfterPrefix("DESCENDANT_PID=");
            if (value == null) {
                return -1L;
            }
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException ignored) {
                return -1L;
            }
        }

        public void awaitDrainers() throws Exception {
            stdout.awaitStopped(DRAIN_TIMEOUT_MS);
            stderr.awaitStopped(DRAIN_TIMEOUT_MS);
        }

        public void cancel() throws Exception {
            if (closed) {
                return;
            }
            List<Object> descendants = descendantHandles(process);
            terminateWindowsTree(rootPid);
            terminateHandles(descendants);
            process.destroy();
            if (!process.waitFor(500L, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
            closeQuietly(stdin);
            closeQuietly(process.getInputStream());
            closeQuietly(process.getErrorStream());
            closeQuietly(process.getOutputStream());
            awaitStopped(process, descendants);
            awaitDrainers();
            closed = true;
        }

        public void assertStopped() throws Exception {
            assertFalse("Root process " + rootPid + " is still alive", process.isAlive());
            awaitPidExit(rootPid);
            long childPid = descendantPid();
            if (childPid > 0L) {
                awaitPidExit(childPid);
            }
            assertFalse("stdout drainer is still alive", stdout.isAlive());
            assertFalse("stderr drainer is still alive", stderr.isAlive());
        }

        @Override
        public void close() throws Exception {
            if (!closed) {
                cancel();
            }
            assertStopped();
        }

        public static void awaitPidExit(final long pid) throws Exception {
            if (pid <= 0L) {
                throw new IllegalArgumentException("pid must be positive");
            }
            TestWait.await("process " + pid + " to exit", STOP_TIMEOUT_MS,
                    new TestWait.Condition() {
                        @Override public boolean isMet() throws Exception {
                            return !isPidAlive(pid);
                        }
                    });
        }

        public static boolean isPidAlive(long pid) throws Exception {
            Class<?> handleType;
            try {
                handleType = Class.forName("java.lang.ProcessHandle");
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(
                        "This process fixture requires reflective ProcessHandle support", e);
            }
            Method of = handleType.getMethod("of", long.class);
            Optional<?> handle = (Optional<?>) of.invoke(null, Long.valueOf(pid));
            if (!handle.isPresent()) {
                return false;
            }
            return ((Boolean) handleType.getMethod("isAlive")
                    .invoke(handle.get())).booleanValue();
        }

        private static void awaitStopped(final Process root,
                                         final List<Object> descendants) throws Exception {
            final Class<?> handleType = processHandleTypeOrNull();
            TestWait.await("stub process tree to terminate", STOP_TIMEOUT_MS,
                    new TestWait.Condition() {
                        @Override public boolean isMet() throws Exception {
                            if (root.isAlive()) {
                                return false;
                            }
                            if (handleType == null) {
                                return true;
                            }
                            Method isAlive = handleType.getMethod("isAlive");
                            for (Object descendant : descendants) {
                                if (((Boolean) isAlive.invoke(descendant)).booleanValue()) {
                                    return false;
                                }
                            }
                            return true;
                        }
                    });
        }

        private static void terminateHandles(List<Object> handles) {
            Class<?> handleType = processHandleTypeOrNull();
            if (handleType == null) {
                return;
            }
            try {
                Method destroy = handleType.getMethod("destroy");
                Method destroyForcibly = handleType.getMethod("destroyForcibly");
                Collections.reverse(handles);
                for (Object handle : handles) {
                    try {
                        destroy.invoke(handle);
                        destroyForcibly.invoke(handle);
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
        }

        private static List<Object> descendantHandles(Process root) {
            Class<?> handleType = processHandleTypeOrNull();
            if (handleType == null) {
                return new ArrayList<Object>();
            }
            try {
                Object rootHandle = Process.class.getMethod("toHandle").invoke(root);
                Stream<?> stream = (Stream<?>) handleType.getMethod("descendants")
                        .invoke(rootHandle);
                try {
                    Object[] values = stream.toArray();
                    List<Object> result = new ArrayList<Object>(values.length);
                    Collections.addAll(result, values);
                    return result;
                } finally {
                    stream.close();
                }
            } catch (Exception ignored) {
                return new ArrayList<Object>();
            }
        }

        private static Class<?> processHandleTypeOrNull() {
            try {
                return Class.forName("java.lang.ProcessHandle");
            } catch (ClassNotFoundException ignored) {
                return null;
            }
        }

        private static void terminateWindowsTree(long pid) {
            if (!System.getProperty("os.name", "")
                    .toLowerCase(Locale.ROOT).contains("win")) {
                return;
            }
            Process taskkill = null;
            try {
                ProcessBuilder builder = new ProcessBuilder("taskkill", "/F", "/T",
                        "/PID", String.valueOf(pid));
                builder.redirectErrorStream(true);
                builder.redirectOutput(new File("NUL"));
                taskkill = builder.start();
                closeQuietly(taskkill.getOutputStream());
                if (!taskkill.waitFor(3L, TimeUnit.SECONDS)) {
                    taskkill.destroyForcibly();
                }
            } catch (Exception ignored) {
            } finally {
                if (taskkill != null) {
                    closeQuietly(taskkill.getInputStream());
                    closeQuietly(taskkill.getErrorStream());
                    closeQuietly(taskkill.getOutputStream());
                }
            }
        }

        private static List<String> javaCommand(StubMode mode, int burstLines) {
            List<String> command = new ArrayList<String>();
            command.add(javaExecutable());
            command.add("-cp");
            command.add(testClasspath());
            command.add(StubProcessMain.class.getName());
            command.add(mode.name());
            command.add(String.valueOf(burstLines));
            return command;
        }

        private static String javaExecutable() {
            String suffix = System.getProperty("os.name", "")
                    .toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
            Path executable = Paths.get(System.getProperty("java.home"), "bin", suffix);
            return executable.toAbsolutePath().normalize().toString();
        }

        private static String testClasspath() {
            String classpath = System.getProperty("surefire.test.class.path");
            if (classpath == null || classpath.trim().isEmpty()) {
                classpath = System.getProperty("java.class.path");
            }
            if (classpath == null || classpath.trim().isEmpty()) {
                throw new IllegalStateException("No test classpath is available");
            }
            return classpath;
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

        private static void closeQuietly(java.io.Closeable closeable) {
            if (closeable == null) {
                return;
            }
            try {
                closeable.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static final class LineCollector implements Runnable {
        private final InputStream input;
        private final String threadName;
        private final ArrayDeque<String> tail = new ArrayDeque<String>();
        private Thread thread;
        private int lineCount;
        private boolean readySeen;

        private LineCollector(InputStream input, String threadName) {
            this.input = input;
            this.threadName = threadName;
        }

        void start() {
            thread = new Thread(this, threadName);
            thread.setDaemon(true);
            thread.start();
        }

        @Override public void run() {
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        input, StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    add(line);
                }
            } catch (IOException ignored) {
                // Forced cancellation closes the pipe to unblock this bounded drainer.
            }
        }

        synchronized void add(String line) {
            lineCount++;
            if ("READY".equals(line)) {
                readySeen = true;
            }
            tail.addLast(line);
            while (tail.size() > StubProcess.TAIL_LINES) {
                tail.removeFirst();
            }
        }

        synchronized int lineCount() {
            return lineCount;
        }

        synchronized int retainedLineCount() {
            return tail.size();
        }

        synchronized boolean contains(String line) {
            return tail.contains(line);
        }

        synchronized boolean readySeen() {
            return readySeen;
        }

        synchronized String valueAfterPrefix(String prefix) {
            for (String line : tail) {
                if (line.startsWith(prefix)) {
                    return line.substring(prefix.length());
                }
            }
            return null;
        }

        void awaitLine(final String line, long timeoutMillis) throws Exception {
            TestWait.await("stub output line '" + line + "'", timeoutMillis,
                    new TestWait.Condition() {
                        @Override public boolean isMet() {
                            return contains(line);
                        }
                    });
        }

        void awaitReady(long timeoutMillis) throws Exception {
            TestWait.await("stub readiness signal", timeoutMillis,
                    new TestWait.Condition() {
                        @Override public boolean isMet() {
                            return readySeen();
                        }
                    });
        }

        void awaitStopped(long timeoutMillis) throws Exception {
            final Thread running = thread;
            if (running == null) {
                return;
            }
            TestWait.await("drainer " + threadName + " to stop", timeoutMillis,
                    new TestWait.Condition() {
                        @Override public boolean isMet() {
                            return !running.isAlive();
                        }
                    });
        }

        boolean isAlive() {
            return thread != null && thread.isAlive();
        }
    }

    /** Entry point launched by {@link StubProcess}. */
    public static final class StubProcessMain {
        private StubProcessMain() {
        }

        public static void main(String[] args) throws Exception {
            StubMode mode = StubMode.valueOf(args[0]);
            int burstLines = args.length > 1 ? Integer.parseInt(args[1]) : BURST_LINES;
            PrintWriter stdout = new PrintWriter(new OutputStreamWriter(
                    System.out, StandardCharsets.UTF_8), true);
            PrintWriter stderr = new PrintWriter(new OutputStreamWriter(
                    System.err, StandardCharsets.UTF_8), true);
            if (mode == StubMode.LEAF) {
                stdout.println("LEAF_READY");
                stderr.println("LEAF_STDERR_READY");
                blockForever();
                return;
            }
            if (mode == StubMode.TREE) {
                Process child = new ProcessBuilder(StubProcess.javaCommand(
                        StubMode.LEAF, burstLines))
                        .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                        .redirectError(ProcessBuilder.Redirect.INHERIT)
                        .start();
                stdout.println("DESCENDANT_PID=" + StubProcess.processId(child));
                stdout.println("READY");
                blockForever();
                return;
            }

            stdout.println("READY");
            if (mode == StubMode.BURST) {
                String payload = payload();
                for (int i = 0; i < burstLines; i++) {
                    stdout.println("OUT-" + i + '-' + payload);
                    stderr.println("ERR-" + i + '-' + payload);
                }
                stdout.println("BURST_STDOUT_DONE");
                stderr.println("BURST_STDERR_DONE");
                return;
            }
            if (mode == StubMode.CRASH) {
                stdout.println("CRASH_STDOUT");
                stderr.println("CRASH_STDERR");
                System.exit(23);
            }
            if (mode == StubMode.HANG) {
                blockForever();
                return;
            }
            if (mode == StubMode.PERSISTENT) {
                BufferedReader input = new BufferedReader(new InputStreamReader(
                        System.in, StandardCharsets.UTF_8));
                String line;
                while ((line = input.readLine()) != null) {
                    if ("crash".equals(line)) {
                        stderr.println("PERSISTENT_CRASH");
                        System.exit(37);
                    }
                    if ("hang".equals(line)) {
                        stdout.println("HANGING");
                        blockForever();
                    }
                    stdout.println("RESULT=" + line);
                    stderr.println("TRACE=" + line);
                }
            }
        }

        private static String payload() {
            StringBuilder payload = new StringBuilder();
            for (int i = 0; i < 256; i++) {
                payload.append((char) ('a' + (i % 26)));
            }
            return payload.toString();
        }

        private static void blockForever() throws InterruptedException {
            Object monitor = new Object();
            synchronized (monitor) {
                while (true) {
                    monitor.wait();
                }
            }
        }
    }
}
