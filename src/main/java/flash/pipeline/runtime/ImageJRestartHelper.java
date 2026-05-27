package flash.pipeline.runtime;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Starts a short-lived external helper that relaunches ImageJ/Fiji after the
 * current JVM exits. The caller is responsible for asking ImageJ to quit after
 * the helper has been started.
 */
public final class ImageJRestartHelper {

    private static final String LOG_FILE_NAME = "FLASH-restart-imagej.log";
    private static final int WAIT_SECONDS = 300;

    public static final class RestartResult {
        private final boolean success;
        private final String message;
        private final File scriptFile;
        private final File launchTarget;

        RestartResult(boolean success, String message, File scriptFile, File launchTarget) {
            this.success = success;
            this.message = message == null ? "" : message;
            this.scriptFile = scriptFile;
            this.launchTarget = launchTarget;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }

        public File getScriptFile() {
            return scriptFile;
        }

        public File getLaunchTarget() {
            return launchTarget;
        }
    }

    static final class LaunchSpec {
        static final String MODE_EXECUTABLE = "executable";
        static final String MODE_MAC_APP = "mac_app";

        private final File launchTarget;
        private final File workingDirectory;
        private final String mode;

        LaunchSpec(File launchTarget, File workingDirectory, String mode) {
            this.launchTarget = launchTarget;
            this.workingDirectory = workingDirectory;
            this.mode = mode == null || mode.trim().isEmpty() ? MODE_EXECUTABLE : mode;
        }

        File getLaunchTarget() {
            return launchTarget;
        }

        File getWorkingDirectory() {
            return workingDirectory;
        }

        String getMode() {
            return mode;
        }
    }

    private ImageJRestartHelper() {}

    public static boolean canRestartAutomatically() {
        return resolveLaunchSpec(DependencyRegistry.resolveFijiDir()) != null && isSupportedOs();
    }

    public static RestartResult launchRestartHelper() {
        return launchRestartHelper(DependencyRegistry.resolveFijiDir());
    }

    static RestartResult launchRestartHelper(File fijiDir) {
        if (!isSupportedOs()) {
            return new RestartResult(false,
                    "Automatic restart is not supported on this operating system. Close and reopen ImageJ/Fiji manually.",
                    null,
                    null);
        }

        LaunchSpec spec = resolveLaunchSpec(fijiDir);
        if (spec == null) {
            String base = fijiDir == null ? "unknown Fiji.app folder" : fijiDir.getAbsolutePath();
            return new RestartResult(false,
                    "Could not find an ImageJ/Fiji launcher in " + base + ". Close and reopen ImageJ/Fiji manually.",
                    null,
                    null);
        }

        try {
            boolean windows = isWindows();
            File script = File.createTempFile("FLASH-restart-imagej-helper-", windows ? ".ps1" : ".sh");
            File log = restartLogFile(fijiDir);
            if (windows) {
                writeWindowsRestartScript(script);
            } else {
                writePosixRestartScript(script);
            }

            List<String> command = windows
                    ? windowsCommand(script, spec, log)
                    : posixCommand(script, spec, log);
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log));
            builder.start();
            return new RestartResult(true,
                    "Restart helper started. ImageJ/Fiji will reopen after the current process exits.",
                    script,
                    spec.getLaunchTarget());
        } catch (Exception e) {
            return new RestartResult(false,
                    "Could not start the restart helper: " + e.getClass().getSimpleName()
                            + ": " + safeMessage(e),
                    null,
                    spec.getLaunchTarget());
        }
    }

    static LaunchSpec resolveLaunchSpec(File fijiDir) {
        if (fijiDir == null || !fijiDir.isDirectory()) {
            return null;
        }

        if (isWindows()) {
            File launcher = firstExisting(fijiDir,
                    "ImageJ-win64.exe",
                    "ImageJ-win32.exe",
                    "ImageJ.exe",
                    "Fiji.exe",
                    "ImageJ-win64",
                    "ImageJ-win32");
            return launcher == null ? null : new LaunchSpec(launcher, launcher.getParentFile(), LaunchSpec.MODE_EXECUTABLE);
        }

        if (isMac()) {
            if (looksLikeMacApp(fijiDir)) {
                File workingDir = fijiDir.getParentFile() == null ? fijiDir : fijiDir.getParentFile();
                return new LaunchSpec(fijiDir, workingDir, LaunchSpec.MODE_MAC_APP);
            }
            File childApp = new File(fijiDir, "Fiji.app");
            if (looksLikeMacApp(childApp)) {
                return new LaunchSpec(childApp, fijiDir, LaunchSpec.MODE_MAC_APP);
            }
            File launcher = firstExisting(fijiDir,
                    "ImageJ-macosx.exe",
                    "ImageJ-macosx",
                    "Contents/MacOS/ImageJ-macosx",
                    "ImageJ");
            return launcher == null ? null : new LaunchSpec(launcher, launcher.getParentFile(), LaunchSpec.MODE_EXECUTABLE);
        }

        File launcher = firstExisting(fijiDir,
                "ImageJ-linux64.exe",
                "ImageJ-linux32.exe",
                "ImageJ-linux.exe",
                "ImageJ-linux64",
                "ImageJ-linux32",
                "ImageJ-linux",
                "ImageJ");
        return launcher == null ? null : new LaunchSpec(launcher, launcher.getParentFile(), LaunchSpec.MODE_EXECUTABLE);
    }

    static void writeWindowsRestartScript(File script) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(script), StandardCharsets.UTF_8))) {
            writer.write("# FLASH ImageJ/Fiji restart helper.\n");
            writer.write("# Purpose: wait for the current ImageJ/Fiji PID to exit, then launch the resolved ImageJ executable.\n");
            writer.write("# Inputs are literal paths supplied by FLASH; this script does not evaluate command text.\n");
            writer.write("param([string]$ParentPid,[string]$LauncherPath,[string]$WorkingDir,[string]$LogPath)\n");
            writer.write("$ErrorActionPreference = 'Continue'\n");
            writer.write("function Write-RestartLog([string]$Message) {\n");
            writer.write("  $stamp = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'\n");
            writer.write("  Add-Content -LiteralPath $LogPath -Value \"$stamp $Message\" -ErrorAction SilentlyContinue\n");
            writer.write("}\n");
            writer.write("try {\n");
            writer.write("  Write-RestartLog \"Waiting for ImageJ/Fiji process $ParentPid to close.\"\n");
            writer.write("  if ($ParentPid -match '^[0-9]+$') {\n");
            writer.write("    for ($i = 0; $i -lt " + WAIT_SECONDS + "; $i++) {\n");
            writer.write("      if (-not (Get-Process -Id ([int]$ParentPid) -ErrorAction SilentlyContinue)) { break }\n");
            writer.write("      Start-Sleep -Seconds 1\n");
            writer.write("    }\n");
            writer.write("    if (Get-Process -Id ([int]$ParentPid) -ErrorAction SilentlyContinue) {\n");
            writer.write("      Write-RestartLog 'Timed out waiting for ImageJ/Fiji to close; not launching a second copy.'\n");
            writer.write("      exit 1\n");
            writer.write("    }\n");
            writer.write("  } else {\n");
            writer.write("    Start-Sleep -Seconds 2\n");
            writer.write("  }\n");
            writer.write("  Start-Sleep -Seconds 1\n");
            writer.write("  if (-not (Test-Path -LiteralPath $LauncherPath)) {\n");
            writer.write("    Write-RestartLog \"Launcher not found: ${LauncherPath}\"\n");
            writer.write("    exit 1\n");
            writer.write("  }\n");
            writer.write("  Write-RestartLog \"Launching ${LauncherPath}\"\n");
            writer.write("  Start-Process -FilePath $LauncherPath -WorkingDirectory $WorkingDir\n");
            writer.write("  Remove-Item -LiteralPath $MyInvocation.MyCommand.Path -Force -ErrorAction SilentlyContinue\n");
            writer.write("  exit 0\n");
            writer.write("} catch {\n");
            writer.write("  Write-RestartLog \"FAILED to restart ImageJ/Fiji: $($_.Exception.Message)\"\n");
            writer.write("  exit 1\n");
            writer.write("}\n");
        }
    }

    static void writePosixRestartScript(File script) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(script), StandardCharsets.UTF_8))) {
            writer.write("#!/bin/sh\n");
            writer.write("# FLASH ImageJ/Fiji restart helper.\n");
            writer.write("# Purpose: wait for the current ImageJ/Fiji PID to exit, then launch the resolved ImageJ executable.\n");
            writer.write("# Inputs are literal paths supplied by FLASH; this script does not evaluate command text.\n");
            writer.write("PARENT_PID=\"$1\"\n");
            writer.write("LAUNCH_TARGET=\"$2\"\n");
            writer.write("WORKING_DIR=\"$3\"\n");
            writer.write("LOG_PATH=\"$4\"\n");
            writer.write("MODE=\"$5\"\n");
            writer.write("log_msg() {\n");
            writer.write("  stamp=`date '+%Y-%m-%d %H:%M:%S'`\n");
            writer.write("  printf '%s %s\\n' \"$stamp\" \"$1\" >> \"$LOG_PATH\" 2>/dev/null\n");
            writer.write("}\n");
            writer.write("log_msg \"Waiting for ImageJ/Fiji process $PARENT_PID to close.\"\n");
            writer.write("case \"$PARENT_PID\" in\n");
            writer.write("  ''|*[!0-9]*) sleep 2 ;;\n");
            writer.write("  *)\n");
            writer.write("    i=0\n");
            writer.write("    while kill -0 \"$PARENT_PID\" 2>/dev/null; do\n");
            writer.write("      if [ \"$i\" -ge " + WAIT_SECONDS + " ]; then\n");
            writer.write("        log_msg 'Timed out waiting for ImageJ/Fiji to close; not launching a second copy.'\n");
            writer.write("        exit 1\n");
            writer.write("      fi\n");
            writer.write("      i=`expr \"$i\" + 1`\n");
            writer.write("      sleep 1\n");
            writer.write("    done\n");
            writer.write("    ;;\n");
            writer.write("esac\n");
            writer.write("sleep 1\n");
            writer.write("if [ \"$MODE\" = 'mac_app' ]; then\n");
            writer.write("  log_msg \"Opening $LAUNCH_TARGET\"\n");
            writer.write("  open -n \"$LAUNCH_TARGET\" >> \"$LOG_PATH\" 2>&1 &\n");
            writer.write("else\n");
            writer.write("  if [ ! -e \"$LAUNCH_TARGET\" ]; then\n");
            writer.write("    log_msg \"Launcher not found: $LAUNCH_TARGET\"\n");
            writer.write("    exit 1\n");
            writer.write("  fi\n");
            writer.write("  cd \"$WORKING_DIR\" || exit 1\n");
            writer.write("  log_msg \"Launching $LAUNCH_TARGET\"\n");
            writer.write("  \"$LAUNCH_TARGET\" >> \"$LOG_PATH\" 2>&1 &\n");
            writer.write("fi\n");
            writer.write("rm -f \"$0\" >/dev/null 2>&1\n");
            writer.write("exit 0\n");
        }
    }

    static List<String> windowsCommand(File script, LaunchSpec spec, File log) {
        List<String> command = new ArrayList<String>();
        command.add("powershell.exe");
        command.add("-NoProfile");
        command.add("-NonInteractive");
        // Keep the helper hidden so restart does not flash a console window.
        // Do not bypass PowerShell execution policy; if policy blocks scripts,
        // the UI reports failure and the user can restart ImageJ manually.
        command.add("-WindowStyle");
        command.add("Hidden");
        command.add("-File");
        command.add(script.getAbsolutePath());
        command.add(currentProcessId());
        command.add(spec.getLaunchTarget().getAbsolutePath());
        command.add(spec.getWorkingDirectory().getAbsolutePath());
        command.add(log.getAbsolutePath());
        return command;
    }

    private static List<String> posixCommand(File script, LaunchSpec spec, File log) {
        List<String> command = new ArrayList<String>();
        command.add("sh");
        command.add(script.getAbsolutePath());
        command.add(currentProcessId());
        command.add(spec.getLaunchTarget().getAbsolutePath());
        command.add(spec.getWorkingDirectory().getAbsolutePath());
        command.add(log.getAbsolutePath());
        command.add(spec.getMode());
        return command;
    }

    private static File restartLogFile(File fijiDir) {
        File dir = fijiDir != null && fijiDir.isDirectory() && fijiDir.canWrite()
                ? fijiDir
                : new File(System.getProperty("java.io.tmpdir"));
        return new File(dir, LOG_FILE_NAME);
    }

    private static File firstExisting(File baseDir, String... relativePaths) {
        if (baseDir == null || relativePaths == null) {
            return null;
        }
        for (String relativePath : relativePaths) {
            File candidate = new File(baseDir, relativePath);
            if (candidate.exists()) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean looksLikeMacApp(File dir) {
        return dir != null
                && dir.isDirectory()
                && dir.getName().toLowerCase(Locale.ROOT).endsWith(".app")
                && (new File(dir, "Contents").isDirectory()
                || new File(dir, "ImageJ-macosx").exists());
    }

    private static String currentProcessId() {
        String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
        if (runtimeName == null) {
            return "";
        }
        int at = runtimeName.indexOf('@');
        String pid = at >= 0 ? runtimeName.substring(0, at) : runtimeName;
        return pid == null ? "" : pid.trim();
    }

    private static boolean isSupportedOs() {
        return isWindows() || isMac() || isUnixLike();
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name");
        return os != null && os.toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean isMac() {
        String os = System.getProperty("os.name");
        return os != null && os.toLowerCase(Locale.ROOT).contains("mac");
    }

    private static boolean isUnixLike() {
        String os = System.getProperty("os.name");
        if (os == null) {
            return false;
        }
        String lower = os.toLowerCase(Locale.ROOT);
        return lower.contains("linux")
                || lower.contains("unix")
                || lower.contains("bsd")
                || lower.contains("sunos")
                || lower.contains("aix");
    }

    private static String safeMessage(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().trim().isEmpty()) {
            return "no detail message";
        }
        return throwable.getMessage().trim();
    }
}
