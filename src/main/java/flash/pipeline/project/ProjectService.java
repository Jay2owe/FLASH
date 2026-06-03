package flash.pipeline.project;

import flash.pipeline.bin.ChannelConfigIO;
import flash.pipeline.io.FlashProjectLayout;

import java.io.File;
import java.io.IOException;

/**
 * Swing-free project classification, resolution, and loading helpers shared by
 * GUI front doors and headless callers.
 */
public final class ProjectService {
    private static final int UPWARD_MARKER_SEARCH_DEPTH = 5;

    /** Classification result for a selected folder or FLASH project pointer. */
    public enum ProjectKind {
        NEW_EMPTY,
        VALID_FLASH,
        FOREIGN
    }

    private ProjectService() {
    }

    public static ProjectKind classify(File folder) {
        if (folder == null) {
            return ProjectKind.FOREIGN;
        }

        File selected = folder.getAbsoluteFile();
        if (hasFlashMarkerNear(selected)) {
            return ProjectKind.VALID_FLASH;
        }
        if (selected.isDirectory()
                && selected.canWrite()
                && !new File(selected, FlashProjectLayout.FLASH_DIR).isDirectory()
                && isEmptyDirectory(selected)) {
            return ProjectKind.NEW_EMPTY;
        }
        return ProjectKind.FOREIGN;
    }

    public static File resolveProjectJson(File pointerOrFolder) {
        if (pointerOrFolder == null) {
            return null;
        }
        File resolved = ProjectPathResolver.resolveProjectJson(pointerOrFolder);
        return resolved == null ? ProjectPathResolver.resolveProjectJsonNear(pointerOrFolder) : resolved;
    }

    public static ResolveOutcome resolveRecent(String storedProjectJsonPath) {
        if (storedProjectJsonPath == null || storedProjectJsonPath.trim().isEmpty()) {
            return new ResolveOutcome(null, storedProjectJsonPath, false);
        }
        File stored = new File(storedProjectJsonPath);
        File resolved = resolveProjectJson(stored);
        boolean relocated = resolved != null && !sameCanonicalFile(resolved, stored);
        return new ResolveOutcome(resolved, storedProjectJsonPath, relocated);
    }

    public static ProjectFile load(File projectJson) {
        if (projectJson == null) {
            return null;
        }
        File settingsDir = projectJson.getParentFile();
        ProjectFile loaded = ProjectFileIO.read(settingsDir);
        if (loaded == null) {
            return null;
        }
        File fallbackOutputRoot = FlashProjectLayout.projectRootForConfigurationDir(settingsDir);
        return ProjectPathResolver.relocateForLoad(loaded, projectJson, fallbackOutputRoot);
    }

    private static boolean hasFlashMarkerNear(File selected) {
        if (ProjectPathResolver.resolveProjectJsonNear(selected) != null) {
            return true;
        }
        File cursor = selected;
        for (int climbed = 0; cursor != null && climbed <= UPWARD_MARKER_SEARCH_DEPTH; climbed++) {
            if (hasMarkerAtSelectedLocation(cursor)) {
                return true;
            }
            cursor = cursor.getParentFile();
        }
        return false;
    }

    private static boolean hasMarkerAtSelectedLocation(File selected) {
        if (selected == null || !selected.exists() || selected.isFile()) {
            return false;
        }
        if (hasMarker(FlashProjectLayout.forDirectory(selected.getAbsolutePath()).configurationWriteDir())) {
            return true;
        }
        if (FlashProjectLayout.FLASH_DIR.equalsIgnoreCase(selected.getName())) {
            return hasMarker(new File(new File(selected, FlashProjectLayout.CONFIGURATION_DIR),
                    FlashProjectLayout.SETTINGS_DIR));
        }
        if (FlashProjectLayout.CONFIGURATION_DIR.equalsIgnoreCase(selected.getName())) {
            return hasMarker(new File(selected, FlashProjectLayout.SETTINGS_DIR));
        }
        return FlashProjectLayout.SETTINGS_DIR.equalsIgnoreCase(selected.getName()) && hasMarker(selected);
    }

    private static boolean hasMarker(File settingsDir) {
        return ProjectFileIO.exists(settingsDir) || ChannelConfigIO.exists(settingsDir);
    }

    private static boolean isEmptyDirectory(File folder) {
        String[] children = folder.list();
        return children != null && children.length == 0;
    }

    private static boolean sameCanonicalFile(File a, File b) {
        if (a == null || b == null) {
            return false;
        }
        try {
            return a.getCanonicalFile().equals(b.getCanonicalFile());
        } catch (IOException e) {
            return a.getAbsoluteFile().equals(b.getAbsoluteFile());
        }
    }

    /** Result of resolving a stored recent-project path on the current machine. */
    public static final class ResolveOutcome {
        public final File projectJson;
        public final String storedPath;
        public final boolean relocated;

        public ResolveOutcome(File projectJson, String storedPath, boolean relocated) {
            this.projectJson = projectJson;
            this.storedPath = storedPath;
            this.relocated = relocated;
        }
    }
}
