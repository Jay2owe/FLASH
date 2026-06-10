package flash.pipeline.cellpose;

import flash.pipeline.runtime.DependencyRegistry;
import flash.pipeline.ui.NextStepLabels;
import flash.pipeline.ui.PipelineDialog;
import ij.IJ;
import ij.Prefs;

import javax.swing.JButton;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.function.LongSupplier;

public final class CellposeRuntime {
    public static final String PREF_PYTHON_PATH = "flash.pipeline.cellpose.pythonPath";
    private static final long PROBE_TIMEOUT_SECONDS = 20;
    private static final long COMMAND_TIMEOUT_SECONDS = 1800;
    static final long MISSING_TTL_MS = 30_000L;
    static final long READY_TTL_MS = 5 * 60_000L;
    private static final Object LOCK = new Object();
    // Pin moved to DependencyRegistry so all version pins live together.
    public static final String SUPPORTED_CELLPOSE_VERSION =
            DependencyRegistry.SUPPORTED_CELLPOSE_VERSION;
    private static final String SUPPORTED_CELLPOSE_PIP_SPEC = "cellpose==" + SUPPORTED_CELLPOSE_VERSION;
    private static final ProbeBackend DEFAULT_PROBE_BACKEND = new ProbeBackend() {
        @Override public Status probeConfigured() {
            return probeConfiguredInternal();
        }
    };
    private static final LongSupplier SYSTEM_CLOCK = new LongSupplier() {
        @Override public long getAsLong() {
            return System.currentTimeMillis();
        }
    };
    private static final ExecutorService PROBE_EXECUTOR = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "FLASH Cellpose runtime probe");
            thread.setDaemon(true);
            return thread;
        }
    });
    private static volatile Status cached;
    private static volatile long cachedAtMs;
    private static volatile CompletableFuture<Status> inFlight;
    private static volatile ProbeBackend probeBackend = DEFAULT_PROBE_BACKEND;
    private static volatile LongSupplier clock = SYSTEM_CLOCK;
    private static long cacheGeneration;

    private CellposeRuntime() {}

    interface ProbeBackend {
        Status probeConfigured();
    }

    public enum SetupResult {
        READY,
        BACK,
        CANCEL
    }

    public static final class Status {
        public final String pythonPath;
        public final boolean configured;
        public final boolean executableExists;
        public final boolean ready;
        public final String cellposeVersion;
        public final boolean gpuAvailable;
        public final String message;
        public final String details;
        public final boolean unknown;

        Status(String pythonPath, boolean configured, boolean executableExists, boolean ready,
               String cellposeVersion, boolean gpuAvailable, String message, String details) {
            this(pythonPath, configured, executableExists, ready, cellposeVersion, gpuAvailable,
                    message, details, false);
        }

        Status(String pythonPath, boolean configured, boolean executableExists, boolean ready,
               String cellposeVersion, boolean gpuAvailable, String message, String details,
               boolean unknown) {
            this.pythonPath = pythonPath == null ? "" : pythonPath;
            this.configured = configured;
            this.executableExists = executableExists;
            this.ready = ready;
            this.cellposeVersion = cellposeVersion == null ? "" : cellposeVersion;
            this.gpuAvailable = gpuAvailable;
            this.message = message == null ? "" : message;
            this.details = details == null ? "" : details;
            this.unknown = unknown;
        }

        public static Status unknown() {
            return new Status("", false, false, false, "", false,
                    "Checking Cellpose...",
                    "The Cellpose runtime probe has not completed yet.",
                    true);
        }

        public boolean isUnknown() {
            return unknown;
        }

        public String summary() {
            if (!ready) return message;
            String version = cellposeVersion.isEmpty() ? "unknown" : cellposeVersion;
            return "Cellpose " + version + " ready. GPU available: " + gpuAvailable + ".";
        }
    }

    private static final class CommandResult {
        final int exitCode;
        final boolean timedOut;
        final String output;

        CommandResult(int exitCode, boolean timedOut, String output) {
            this.exitCode = exitCode;
            this.timedOut = timedOut;
            this.output = output == null ? "" : output;
        }
    }

    public static final class InstallResult {
        public final boolean success;
        public final String pythonPath;
        public final String message;
        public final String details;

        InstallResult(boolean success, String pythonPath, String message, String details) {
            this.success = success;
            this.pythonPath = pythonPath == null ? "" : pythonPath;
            this.message = message == null ? "" : message;
            this.details = details == null ? "" : details;
        }
    }

    private static final class ExistingInstallDetection {
        final String pythonPath;
        final Status status;

        ExistingInstallDetection(String pythonPath, Status status) {
            this.pythonPath = pythonPath == null ? "" : pythonPath;
            this.status = status;
        }

        boolean detected() {
            return !pythonPath.isEmpty() && status != null;
        }

        boolean ready() {
            return detected() && status.ready;
        }
    }

    public static String getPythonPath() {
        return Prefs.get(PREF_PYTHON_PATH, "").trim();
    }

    public static void setPythonPath(String pythonPath) {
        String normalized = pythonPath == null ? "" : pythonPath.trim();
        Prefs.set(PREF_PYTHON_PATH, normalized);
        Prefs.savePreferences();
        invalidateCache();
    }

    public static Status probeConfigured() {
        Status status = cached;
        if (isFresh(status, nowMs())) {
            return status;
        }
        try {
            return probeAsync().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Status(getPythonPath(), true, false, false, "", false,
                    "Cellpose probe was interrupted.",
                    "The current thread was interrupted while waiting for Cellpose runtime status.");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return new Status(getPythonPath(), true, false, false, "", false,
                    "Cellpose probe failed.",
                    cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
    }

    private static Status probeConfiguredInternal() {
        return probe(getPythonPath());
    }

    public static Status cachedStatus() {
        Status status = cached;
        return status == null ? Status.unknown() : status;
    }

    public static CompletableFuture<Status> probeAsync() {
        final long now = nowMs();
        synchronized (LOCK) {
            if (isFresh(cached, now)) {
                return CompletableFuture.completedFuture(cached);
            }
            if (inFlight != null) {
                return inFlight;
            }
            final long generation = cacheGeneration;
            final CompletableFuture<Status> future = CompletableFuture.supplyAsync(new Supplier<Status>() {
                @Override public Status get() {
                    return probeBackend.probeConfigured();
                }
            }, PROBE_EXECUTOR);
            inFlight = future;
            future.whenComplete(new java.util.function.BiConsumer<Status, Throwable>() {
                @Override public void accept(Status status, Throwable throwable) {
                    synchronized (LOCK) {
                        if (cacheGeneration == generation && inFlight == future) {
                            if (throwable == null && status != null && !status.unknown) {
                                cached = status;
                                cachedAtMs = nowMs();
                            }
                            inFlight = null;
                        }
                    }
                }
            });
            return future;
        }
    }

    public static void invalidateCache() {
        synchronized (LOCK) {
            cacheGeneration++;
            cached = null;
            cachedAtMs = 0L;
            inFlight = null;
        }
    }

    static void setProbeBackendForTest(ProbeBackend backend) {
        synchronized (LOCK) {
            probeBackend = backend == null ? DEFAULT_PROBE_BACKEND : backend;
            cacheGeneration++;
            cached = null;
            cachedAtMs = 0L;
            inFlight = null;
        }
    }

    static void setClockForTest(LongSupplier testClock) {
        synchronized (LOCK) {
            clock = testClock == null ? SYSTEM_CLOCK : testClock;
            cacheGeneration++;
            cached = null;
            cachedAtMs = 0L;
            inFlight = null;
        }
    }

    static void resetTestHooks() {
        synchronized (LOCK) {
            probeBackend = DEFAULT_PROBE_BACKEND;
            clock = SYSTEM_CLOCK;
            cacheGeneration++;
            cached = null;
            cachedAtMs = 0L;
            inFlight = null;
        }
    }

    private static boolean isFresh(Status status, long nowMs) {
        if (status == null || status.unknown) return false;
        long ageMs = Math.max(0L, nowMs - cachedAtMs);
        long ttlMs = status.ready ? READY_TTL_MS : MISSING_TTL_MS;
        return ageMs <= ttlMs;
    }

    private static long nowMs() {
        LongSupplier supplier = clock;
        return supplier == null ? System.currentTimeMillis() : supplier.getAsLong();
    }

    private static void updateCache(Status status) {
        if (status == null || status.unknown) return;
        String current = normalizeExecutablePath(getPythonPath());
        String statusPath = normalizeExecutablePath(status.pythonPath);
        if (!current.equalsIgnoreCase(statusPath)) return;
        synchronized (LOCK) {
            cacheGeneration++;
            cached = status;
            cachedAtMs = nowMs();
            inFlight = null;
        }
    }

    public static File getManagedEnvDir() {
        return new File(System.getProperty("user.home"),
                ".ihf-pipeline" + File.separator + "cellpose" + File.separator + "cpu-" + SUPPORTED_CELLPOSE_VERSION);
    }

    public static String getManagedPythonPath() {
        File envDir = getManagedEnvDir();
        if (isWindows()) {
            return new File(envDir, "Scripts" + File.separator + "python.exe").getAbsolutePath();
        }
        return new File(envDir, "bin" + File.separator + "python").getAbsolutePath();
    }

    public static boolean hasManagedInstall() {
        return new File(getManagedPythonPath()).isFile();
    }

    public static Status probe(String pythonPath) {
        String trimmed = pythonPath == null ? "" : pythonPath.trim();
        if (trimmed.isEmpty()) {
            return new Status("", false, false, false, "", false,
                    "Cellpose is not configured yet.",
                    "Set the path to the Python executable inside your Cellpose environment.\n"
                            + "Supported Cellpose version: " + SUPPORTED_CELLPOSE_VERSION);
        }

        File executable = new File(trimmed);
        if (!executable.isFile()) {
            return new Status(trimmed, true, false, false, "", false,
                    "Configured Cellpose Python path does not exist.",
                    "Expected a Python executable at:\n" + trimmed);
        }

        List<String> command = new ArrayList<String>();
        command.add(trimmed);
        command.add("-c");
        command.add(
                "import cellpose\n" +
                "version = 'unknown'\n" +
                "try:\n" +
                "    import importlib.metadata as md\n" +
                "    version = md.version('cellpose')\n" +
                "except Exception:\n" +
                "    version = str(getattr(cellpose, '__version__', 'unknown'))\n" +
                "print('CELLPOSE_VERSION=' + version)\n" +
                "gpu = False\n" +
                "try:\n" +
                "    import torch\n" +
                "    gpu = bool(torch.cuda.is_available())\n" +
                "except Exception:\n" +
                "    gpu = False\n" +
                "print('GPU_AVAILABLE=' + ('true' if gpu else 'false'))"
        );

        try {
            CommandResult probeResult = runCommand(command, null, PROBE_TIMEOUT_SECONDS, false);
            if (probeResult.timedOut) {
                return new Status(trimmed, true, true, false, "", false,
                        "Cellpose probe timed out.",
                        "The configured Python path did not respond within " + PROBE_TIMEOUT_SECONDS + " seconds.");
            }
            if (probeResult.exitCode != 0) {
                return new Status(trimmed, true, true, false, "", false,
                        "Python started but Cellpose could not be imported.",
                        probeResult.output.trim());
            }

            String version = "";
            boolean gpuAvailable = false;
            String[] lines = probeResult.output.split("\\R");
            for (String rawLine : lines) {
                String parsed = rawLine == null ? "" : rawLine.trim();
                if (parsed.startsWith("CELLPOSE_VERSION=")) {
                    version = parsed.substring("CELLPOSE_VERSION=".length()).trim();
                } else if (parsed.startsWith("GPU_AVAILABLE=")) {
                    gpuAvailable = "true".equalsIgnoreCase(parsed.substring("GPU_AVAILABLE=".length()).trim());
                }
            }

            if (!SUPPORTED_CELLPOSE_VERSION.equals(version)) {
                String foundVersion = version.isEmpty() ? "unknown" : version;
                return new Status(trimmed, true, true, false, version, gpuAvailable,
                        "Unsupported Cellpose version detected.",
                        "Supported version: " + SUPPORTED_CELLPOSE_VERSION + "\n"
                                + "Detected version: " + foundVersion + "\n"
                                + "Reinstall the pinned version with:\n"
                                + "python -m pip install \"" + SUPPORTED_CELLPOSE_PIP_SPEC + "\"");
            }

            return new Status(trimmed, true, true, true, version, gpuAvailable,
                    "Cellpose is ready.",
                    probeResult.output.trim());
        } catch (Exception e) {
            return new Status(trimmed, true, true, false, "", false,
                    "Could not run the configured Python executable.",
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    public static SetupResult ensureConfigured(String dialogTitle, boolean allowBack) {
        String pythonPath = getPythonPath();
        while (true) {
            Status status = probe(pythonPath);
            if (status.ready) {
                if (!pythonPath.trim().isEmpty()) {
                    setPythonPath(pythonPath);
                }
                return SetupResult.READY;
            }

            ExistingInstallDetection detection = detectExistingInstall(pythonPath);
            String action = showSetupChoiceDialog(dialogTitle, allowBack, status, detection);
            if ("install_managed_cpu".equals(action)) {
                InstallResult install = installManagedCpu();
                if (install.success) {
                    pythonPath = install.pythonPath;
                    setPythonPath(pythonPath);
                } else {
                    IJ.showMessage(dialogTitle, install.message + (install.details.isEmpty() ? "" : "\n\n" + install.details));
                }
                continue;
            }
            if ("use_managed_cpu".equals(action)) {
                pythonPath = getManagedPythonPath();
                continue;
            }
            if ("gpu_guide".equals(action)) {
                showGpuSetupHelp(dialogTitle);
                continue;
            }
            if ("use_existing_install".equals(action)) {
                if (detection.ready()) {
                    pythonPath = detection.pythonPath;
                    continue;
                }
                String existing = promptForExistingInstall(dialogTitle, allowBack, pythonPath);
                if ("__BACK__".equals(existing)) {
                    continue;
                }
                if ("__CANCEL__".equals(existing)) {
                    return SetupResult.CANCEL;
                }
                pythonPath = existing;
                continue;
            }
            return "back".equals(action) ? SetupResult.BACK : SetupResult.CANCEL;
        }
    }

    public static String defaultSetupLabel() {
        Status status = cachedStatus();
        if (status.unknown) {
            probeAsync();
            return "Checking Cellpose runtime...";
        }
        if (status.ready) {
            return "Configured: Cellpose " + (status.cellposeVersion.isEmpty() ? "unknown" : status.cellposeVersion)
                    + ", GPU available=" + status.gpuAvailable;
        }
        return "Requires a managed or existing Cellpose " + SUPPORTED_CELLPOSE_VERSION
                + " install and runs slower than StarDist.";
    }

    public static String normalizeExecutablePath(String pythonPath) {
        if (pythonPath == null) return "";
        String trimmed = pythonPath.trim();
        if (trimmed.isEmpty()) return "";
        return trimmed.replace('/', File.separatorChar).replace('\\', File.separatorChar);
    }

    public static boolean looksLikeWindowsPython(String pythonPath) {
        return pythonPath != null && pythonPath.toLowerCase(Locale.ROOT).endsWith("python.exe");
    }

    public static InstallResult installManagedCpu() {
        File envDir = getManagedEnvDir();
        String managedPython = getManagedPythonPath();
        IJ.log("Cellpose CPU install requested.");
        IJ.log("Managed Cellpose environment: " + envDir.getAbsolutePath());
        IJ.showStatus("Installing Cellpose CPU environment...");

        List<String> bootstrap = findBootstrapPythonCommand();
        if (bootstrap == null) {
            IJ.showStatus("");
            return new InstallResult(false, managedPython,
                    "The plugin could not find a base Python interpreter to create the managed Cellpose environment.",
                    "Install Python 3.10 or newer, restart Fiji, then click Install CPU again.\n"
                            + "After installation, the plugin will create:\n"
                            + envDir.getAbsolutePath());
        }

        File parent = envDir.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            IJ.showStatus("");
            return new InstallResult(false, managedPython,
                    "Could not create the managed Cellpose folder.",
                    parent.getAbsolutePath());
        }

        try {
            if (!new File(managedPython).isFile()) {
                List<String> createEnv = new ArrayList<String>(bootstrap);
                createEnv.addAll(Arrays.asList("-m", "venv", envDir.getAbsolutePath()));
                CommandResult createResult = runCommand(createEnv, null, COMMAND_TIMEOUT_SECONDS);
                if (createResult.timedOut || createResult.exitCode != 0 || !new File(managedPython).isFile()) {
                    IJ.showStatus("");
                    return new InstallResult(false, managedPython,
                            "Could not create the managed Cellpose environment.",
                            formatCommandFailure(createEnv, createResult));
                }
            }

            List<String> upgradePip = Arrays.asList(
                    managedPython, "-m", "pip", "install", "--upgrade", "pip", "setuptools", "wheel");
            CommandResult pipResult = runCommand(upgradePip, envDir, COMMAND_TIMEOUT_SECONDS);
            if (pipResult.timedOut || pipResult.exitCode != 0) {
                IJ.showStatus("");
                return new InstallResult(false, managedPython,
                        "Could not prepare pip inside the managed Cellpose environment.",
                        formatCommandFailure(upgradePip, pipResult));
            }

            List<String> installCellpose = Arrays.asList(
                    managedPython, "-m", "pip", "install", SUPPORTED_CELLPOSE_PIP_SPEC);
            CommandResult installResult = runCommand(installCellpose, envDir, COMMAND_TIMEOUT_SECONDS);
            if (installResult.timedOut || installResult.exitCode != 0) {
                IJ.showStatus("");
                return new InstallResult(false, managedPython,
                        "Could not install Cellpose into the managed environment.",
                        formatCommandFailure(installCellpose, installResult));
            }

            Status verify = probe(managedPython);
            IJ.showStatus("");
            if (!verify.ready) {
                return new InstallResult(false, managedPython,
                        "The managed Cellpose install finished, but verification failed.",
                        verify.message + (verify.details.isEmpty() ? "" : "\n\n" + verify.details));
            }

            setPythonPath(managedPython);
            updateCache(verify);
            return new InstallResult(true, managedPython,
                    "Cellpose CPU is ready.",
                    verify.summary());
        } catch (Exception e) {
            IJ.showStatus("");
            return new InstallResult(false, managedPython,
                    "Could not finish the managed Cellpose installation.",
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    public static InstallResult installManagedGpu() {
        File envDir = getManagedEnvDir();
        String managedPython = getManagedPythonPath();
        IJ.log("Cellpose GPU install requested.");
        IJ.showStatus("Installing GPU support for Cellpose...");

        try {
            if (!hasManagedInstall()) {
                InstallResult cpuInstall = installManagedCpu();
                if (!cpuInstall.success) return cpuInstall;
            }

            List<String> uninstallTorch = Arrays.asList(
                    managedPython, "-m", "pip", "uninstall", "-y", "torch", "torchvision", "torchaudio"
            );
            IJ.log("Uninstalling existing PyTorch...");
            runCommand(uninstallTorch, envDir, COMMAND_TIMEOUT_SECONDS);

            List<String> installTorch = Arrays.asList(
                    managedPython, "-m", "pip", "install", "torch", "torchvision", "torchaudio", "numpy<2.1", "--force-reinstall", "--index-url", "https://download.pytorch.org/whl/cu118"
            );
            CommandResult torchResult = runCommand(installTorch, envDir, COMMAND_TIMEOUT_SECONDS);
            if (torchResult.timedOut || torchResult.exitCode != 0) {
                IJ.showStatus("");
                return new InstallResult(false, managedPython,
                        "Could not install GPU PyTorch.",
                        formatCommandFailure(installTorch, torchResult));
            }

            Status verify = probe(managedPython);
            IJ.showStatus("");
            if (!verify.gpuAvailable) {
                return new InstallResult(false, managedPython,
                        "GPU installation finished, but GPU is still not available. Check your drivers.",
                        verify.summary());
            }

            updateCache(verify);
            return new InstallResult(true, managedPython, "Cellpose GPU support is ready.", verify.summary());
        } catch (Exception e) {
            IJ.showStatus("");
            return new InstallResult(false, managedPython,
                    "Could not finish GPU installation.",
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static void showGpuSetupHelp(String dialogTitle) {
        String msg =
                "GPU setup is an advanced path and is not part of the managed CPU install.\n\n"
                        + "Recommended steps:\n"
                        + "1. Get the managed CPU install working first.\n"
                        + "2. Create or choose a separate Python environment for GPU use.\n"
                        + "3. Install " + SUPPORTED_CELLPOSE_PIP_SPEC + ".\n"
                        + "4. Install the correct GPU-enabled PyTorch build for that machine.\n"
                        + "5. Choose 'Use Existing Install' and point the plugin to that environment's python.exe.\n"
                        + "6. In the Cellpose parameter dialog, turn on Use GPU.\n\n"
                        + "The plugin will only enable GPU use if verification reports GPU available.";
        IJ.showMessage(dialogTitle + " - GPU Setup", msg);
    }

    private static String showSetupChoiceDialog(String dialogTitle, boolean allowBack, Status status,
                                                ExistingInstallDetection detection) {
        PipelineDialog pd = new PipelineDialog(dialogTitle);
        if (allowBack) pd.enableBackButton();
        pd.addHeader("Cellpose Setup");
        pd.addMessage(status.message);
        if (!status.details.isEmpty()) {
            pd.addHelpText(status.details.replace("\n", "<br>"));
        }
        File managedEnvDir = getManagedEnvDir();
        pd.addHelpText("Recommended path: let the plugin create and own a managed CPU Cellpose install.");
        pd.addHelpText("Managed install location:<br><code>" + managedEnvDir.getAbsolutePath() + "</code>");
        pd.addHelpText("Advanced path: use an existing Python only if you already manage Cellpose yourself.");
        JButton installCpuBtn = pd.addFooterButton("Install Managed CPU");
        JButton useManagedBtn = pd.addFooterButton("Use Managed CPU");
        String existingLabel = detection != null && detection.ready()
                ? "Use Existing Install (Detected)"
                : "Use Existing Install";
        JButton useExistingBtn = pd.addFooterButton(existingLabel);
        JButton gpuHelpBtn = pd.addFooterButton("GPU Guide");
        installCpuBtn.addActionListener(e -> pd.closeWithAction("install_managed_cpu"));
        useManagedBtn.addActionListener(e -> pd.closeWithAction("use_managed_cpu"));
        useExistingBtn.addActionListener(e -> pd.closeWithAction("use_existing_install"));
        gpuHelpBtn.addActionListener(e -> pd.closeWithAction("gpu_guide"));
        pd.setPrimaryButtonText(NextStepLabels.EXISTING_CELLPOSE_INSTALL);
        if (detection != null && detection.detected()) {
            pd.addHelpText("Detected existing install:<br><code>" + detection.pythonPath + "</code>");
            pd.addHelpText(detection.status.ready
                    ? ("Detected status: ready; GPU available=" + detection.status.gpuAvailable)
                    : ("Detected status: " + detection.status.message));
        } else {
            pd.addHelpText("No supported existing install was detected automatically.");
        }
        pd.addHelpText("Choose one of the buttons below. OK also opens the advanced existing-install path.");
        if (!pd.showDialog()) {
            String action = pd.getActionCommand();
            if (!action.isEmpty()) return action;
            return pd.wasBackPressed() ? "back" : "cancel";
        }
        return "use_existing_install";
    }

    private static String promptForExistingInstall(String dialogTitle, boolean allowBack, String currentPath) {
        String pythonPath = currentPath == null ? "" : currentPath.trim();
        while (true) {
            Status status = probe(pythonPath);
            PipelineDialog pd = new PipelineDialog(dialogTitle + " - Existing Install");
            pd.addHeader("Use Existing Cellpose Install");
            pd.addMessage("Advanced path: point the plugin to an existing Python where "
                    + SUPPORTED_CELLPOSE_PIP_SPEC + " is already installed.");
            if (allowBack) pd.enableBackButton();
            if (!status.message.isEmpty() && !pythonPath.isEmpty()) {
                pd.addHelpText(status.message);
            }
            if (!status.details.isEmpty() && !pythonPath.isEmpty()) {
                pd.addHelpText(status.details.replace("\n", "<br>"));
            }
            pd.addStringField("Existing Python Executable", pythonPath, 36);
            pd.addHelpText("Examples:<br>"
                    + "<code>%USERPROFILE%\\miniforge3\\envs\\cellpose-3\\python.exe</code><br>"
                    + "<code>%USERPROFILE%\\project\\.venv\\Scripts\\python.exe</code>");
            pd.addHelpText("Suggested commands for an existing install:<br>"
                    + "<code>python -m venv .venv</code><br>"
                    + "<code>.venv\\Scripts\\python -m pip install \"" + SUPPORTED_CELLPOSE_PIP_SPEC + "\"</code><br>"
                    + "or<br>"
                    + "<code>conda create --name cellpose-3 python=3.10</code><br>"
                    + "<code>conda activate cellpose-3</code><br>"
                    + "<code>pip install \"" + SUPPORTED_CELLPOSE_PIP_SPEC + "\"</code>");
            pd.setPrimaryButtonText(NextStepLabels.VERIFY_CELLPOSE_INSTALL);
            if (!pd.showDialog()) {
                return pd.wasBackPressed() ? "__BACK__" : "__CANCEL__";
            }
            pythonPath = pd.getNextString().trim();
            if (pythonPath.isEmpty()) {
                IJ.showMessage(dialogTitle, "Enter the path to an existing Cellpose Python executable, then click OK to verify it.");
                continue;
            }
            return pythonPath;
        }
    }

    private static ExistingInstallDetection detectExistingInstall(String currentPath) {
        List<String> candidates = new ArrayList<String>();
        addExistingInstallCandidate(candidates, currentPath);

        String pathPython = resolvePythonExecutable(Arrays.asList(isWindows() ? "python" : "python3"));
        addExistingInstallCandidate(candidates, pathPython);
        if (isWindows()) {
            addExistingInstallCandidate(candidates, resolvePythonExecutable(Arrays.asList("py", "-3")));
        } else {
            addExistingInstallCandidate(candidates, resolvePythonExecutable(Arrays.asList("python")));
        }

        String userHome = System.getProperty("user.home", "");
        if (!userHome.isEmpty()) {
            String[] roots = new String[]{
                    userHome + File.separator + "miniforge3",
                    userHome + File.separator + "miniconda3",
                    userHome + File.separator + "anaconda3",
                    userHome + File.separator + ".conda"
            };
            String[] envNames = new String[]{"cellpose-3", "cellpose", "cellpose-gpu"};
            for (String root : roots) {
                for (String envName : envNames) {
                    File envDir = new File(root, "envs" + File.separator + envName);
                    String python = isWindows()
                            ? new File(envDir, "python.exe").getAbsolutePath()
                            : new File(envDir, "bin" + File.separator + "python").getAbsolutePath();
                    if (isWindows() && !new File(python).isFile()) {
                        python = new File(envDir, "Scripts" + File.separator + "python.exe").getAbsolutePath();
                    }
                    addExistingInstallCandidate(candidates, python);
                }
            }
        }

        ExistingInstallDetection fallback = null;
        for (String candidate : candidates) {
            Status candidateStatus = probe(candidate);
            ExistingInstallDetection detection = new ExistingInstallDetection(candidate, candidateStatus);
            if (candidateStatus.ready) {
                return detection;
            }
            if (fallback == null && candidateStatus.executableExists) {
                fallback = detection;
            }
        }
        return fallback;
    }

    private static void addExistingInstallCandidate(List<String> candidates, String pythonPath) {
        String normalized = normalizeExecutablePath(pythonPath);
        if (normalized.isEmpty()) return;
        if (normalized.equalsIgnoreCase(normalizeExecutablePath(getManagedPythonPath()))) return;
        for (String existing : candidates) {
            if (existing.equalsIgnoreCase(normalized)) return;
        }
        if (new File(normalized).isFile()) {
            candidates.add(normalized);
        }
    }

    private static String resolvePythonExecutable(List<String> launcher) {
        if (launcher == null || launcher.isEmpty()) return "";
        try {
            List<String> command = new ArrayList<String>(launcher);
            command.add("-c");
            command.add("import sys; print(sys.executable)");
            CommandResult result = runCommand(command, null, 15);
            if (result.timedOut || result.exitCode != 0) return "";
            String[] lines = result.output.split("\\R");
            for (String line : lines) {
                String trimmed = normalizeExecutablePath(line == null ? "" : line.trim());
                if (!trimmed.isEmpty() && new File(trimmed).isFile()) {
                    return trimmed;
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private static String formatCommandFailure(List<String> command, CommandResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("Command: ").append(String.join(" ", command)).append('\n');
        if (result.timedOut) {
            sb.append("Result: timed out after ").append(COMMAND_TIMEOUT_SECONDS).append(" seconds.\n");
        } else {
            sb.append("Exit code: ").append(result.exitCode).append('\n');
        }
        String output = result.output.trim();
        if (!output.isEmpty()) {
            sb.append('\n').append(output);
        }
        return sb.toString().trim();
    }

    private static List<String> findBootstrapPythonCommand() {
        List<List<String>> candidates = new ArrayList<List<String>>();
        if (isWindows()) {
            candidates.add(Arrays.asList("py", "-3"));
            candidates.add(Arrays.asList("python"));
        } else {
            candidates.add(Arrays.asList("python3"));
            candidates.add(Arrays.asList("python"));
        }

        for (List<String> candidate : candidates) {
            try {
                List<String> probe = new ArrayList<String>(candidate);
                probe.add("--version");
                CommandResult result = runCommand(probe, null, 15);
                if (!result.timedOut && result.exitCode == 0) {
                    return new ArrayList<String>(candidate);
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static CommandResult runCommand(List<String> command, File workDir, long timeoutSeconds) throws Exception {
        return runCommand(command, workDir, timeoutSeconds, true);
    }

    private static CommandResult runCommand(List<String> command, File workDir,
                                            long timeoutSeconds, final boolean logOutput) throws Exception {
        Process process = null;
        final StringBuilder output = new StringBuilder();
        Thread outputThread = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            if (workDir != null) pb.directory(workDir);
            pb.redirectErrorStream(true);
            process = pb.start();
            outputThread = startOutputDrainer(process.getInputStream(), output, logOutput);
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(2L, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
                return new CommandResult(-1, true, outputText(output));
            }
            return new CommandResult(process.exitValue(), false, outputText(output));
        } finally {
            if (process != null) {
                try {
                    process.getInputStream().close();
                } catch (Exception ignored) {}
                try {
                    process.getErrorStream().close();
                } catch (Exception ignored) {}
                try {
                    process.getOutputStream().close();
                } catch (Exception ignored) {}
            }
            if (outputThread != null) {
                try {
                    outputThread.join(2000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private static Thread startOutputDrainer(final InputStream stream,
                                             final StringBuilder output,
                                             final boolean logOutput) {
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(
                            stream, StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (output) {
                            output.append(line).append('\n');
                        }
                        if (logOutput) {
                            IJ.log(line);
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }, "FLASH Cellpose command output");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static String outputText(StringBuilder output) {
        synchronized (output) {
            return output.toString();
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public static boolean isNvidiaGpuLikelyAvailable() {
        Process process = null;
        try {
            process = new ProcessBuilder("nvidia-smi").start();
            process.getInputStream().close();
            process.getErrorStream().close();
            process.getOutputStream().close();
            if (!process.waitFor(3L, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
