package flash.pipeline.runrecord.ui;

import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.project.ProjectBuilderDialog;
import flash.pipeline.project.ProjectFileIO;
import flash.pipeline.project.RecentProjectsStore;
import ij.IJ;
import ij.plugin.PlugIn;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.util.Locale;

/**
 * ImageJ menu entry for the FLASH run-record browser.
 */
public final class RunsBrowserPlugin implements PlugIn {

    public void run(final String arg) {
        if (GraphicsEnvironment.isHeadless()) {
            IJ.log("[FLASH] Runs browser is not available in headless mode.");
            return;
        }
        Runnable opener = new Runnable() {
            public void run() {
                File projectRoot = resolveProjectRootArgument(arg);
                if (projectRoot == null) {
                    projectRoot = pickProjectRoot();
                }
                if (projectRoot != null) {
                    RunsBrowserDialog.open(null, projectRoot);
                }
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            opener.run();
        } else {
            SwingUtilities.invokeLater(opener);
        }
    }

    static File resolveProjectRootArgument(String arg) {
        String path = extractPath(arg);
        if (path == null || path.trim().isEmpty()) {
            return null;
        }
        return normaliseProjectRoot(new File(path));
    }

    private static File pickProjectRoot() {
        ProjectBuilderDialog.Result picked = ProjectBuilderDialog.open(
                null, RecentProjectsStore.resolveStoreDir(), null);
        return picked == null || picked.outputRoot == null
                ? null
                : picked.outputRoot.getAbsoluteFile();
    }

    private static File normaliseProjectRoot(File file) {
        if (file == null) {
            return null;
        }
        File candidate = file.getAbsoluteFile();
        if (candidate.isFile()) {
            if (ProjectFileIO.FILE_NAME.equals(candidate.getName())) {
                return FlashProjectLayout.projectRootForConfigurationDir(candidate.getParentFile());
            }
            candidate = candidate.getParentFile();
        }
        if (candidate == null) {
            return null;
        }
        if (FlashProjectLayout.SETTINGS_DIR.equals(candidate.getName())) {
            File root = FlashProjectLayout.projectRootForConfigurationDir(candidate);
            return root == null ? candidate : root.getAbsoluteFile();
        }
        if (FlashProjectLayout.CONFIGURATION_DIR.equals(candidate.getName())) {
            File root = FlashProjectLayout.projectRootForConfigurationDir(
                    new File(candidate, FlashProjectLayout.SETTINGS_DIR));
            return root == null ? candidate : root.getAbsoluteFile();
        }
        if (FlashProjectLayout.FLASH_DIR.equals(candidate.getName()) && candidate.getParentFile() != null) {
            return candidate.getParentFile().getAbsoluteFile();
        }
        return candidate;
    }

    private static String extractPath(String arg) {
        if (arg == null) {
            return null;
        }
        String trimmed = arg.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        String[] keys = {"projectroot=", "project=", "directory=", "dir="};
        for (String key : keys) {
            int index = lower.indexOf(key);
            if (index >= 0) {
                return stripContainer(trimmed.substring(index + key.length()).trim());
            }
        }
        if (trimmed.indexOf('=') < 0) {
            return stripContainer(trimmed);
        }
        return null;
    }

    private static String stripContainer(String value) {
        if (value == null) {
            return null;
        }
        String out = value.trim();
        if (out.startsWith("[") && out.indexOf(']') > 0) {
            return out.substring(1, out.indexOf(']')).trim();
        }
        if (out.startsWith("\"") && out.indexOf('"', 1) > 1) {
            return out.substring(1, out.indexOf('"', 1)).trim();
        }
        if (out.startsWith("'") && out.indexOf('\'', 1) > 1) {
            return out.substring(1, out.indexOf('\'', 1)).trim();
        }
        return out;
    }
}
