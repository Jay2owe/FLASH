package flash.pipeline.project;

import flash.pipeline.io.FlashProjectLayout;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Portable path handling for saved FLASH project files.
 *
 * <p>Project files historically stored absolute paths. This resolver keeps
 * that format backward-compatible while recovering common moves, such as
 * opening the same Dropbox folder under a different Windows user profile.
 */
public final class ProjectPathResolver {
    public static final String K_PATH_RELATIVE_TO_OUTPUT_ROOT = "pathRelativeToOutputRoot";

    private static final int SOURCE_NAME_SEARCH_DEPTH = 3;

    private ProjectPathResolver() {
    }

    /**
     * Resolve a recent-project pointer to an existing {@code project.json}.
     * Returns {@code null} when the file cannot be found locally.
     */
    public static File resolveProjectJson(File storedProjectJson) {
        for (File candidate : pathCandidates(storedProjectJson)) {
            File projectJson = projectJsonFromSelectedLocation(candidate);
            if (projectJson != null && projectJson.isFile()) {
                return projectJson.getAbsoluteFile();
            }
        }
        return null;
    }

    /**
     * Convert a user-selected file or folder into a project.json file.
     * Accepts the project output root, FLASH folder, Config folder,
     * .settings folder, or project.json itself.
     */
    public static File projectJsonFromSelectedLocation(File selected) {
        if (selected == null || !selected.exists()) {
            return null;
        }
        File absolute = selected.getAbsoluteFile();
        if (absolute.isFile()) {
            return ProjectFileIO.FILE_NAME.equalsIgnoreCase(absolute.getName()) ? absolute : null;
        }
        if (!absolute.isDirectory()) {
            return null;
        }

        File direct = new File(absolute, ProjectFileIO.FILE_NAME);
        if (direct.isFile()) {
            return direct.getAbsoluteFile();
        }

        File configSettings = new File(new File(absolute, FlashProjectLayout.SETTINGS_DIR),
                ProjectFileIO.FILE_NAME);
        if (configSettings.isFile()) {
            return configSettings.getAbsoluteFile();
        }

        File flashConfig = new File(new File(new File(absolute, FlashProjectLayout.CONFIGURATION_DIR),
                FlashProjectLayout.SETTINGS_DIR), ProjectFileIO.FILE_NAME);
        if (flashConfig.isFile()) {
            return flashConfig.getAbsoluteFile();
        }

        File projectRootConfig = new File(
                FlashProjectLayout.forDirectory(absolute.getAbsolutePath()).configurationWriteDir(),
                ProjectFileIO.FILE_NAME);
        return projectRootConfig.isFile() ? projectRootConfig.getAbsoluteFile() : null;
    }

    /**
     * Adjust a decoded project so paths point at files on this machine where
     * that can be inferred safely.
     */
    public static ProjectFile relocateForLoad(ProjectFile project, File projectJson, File fallbackOutputRoot) {
        if (project == null) {
            return null;
        }
        File actualOutputRoot = fallbackOutputRoot != null
                ? fallbackOutputRoot.getAbsoluteFile()
                : outputRootForProjectJson(projectJson);
        File storedOutputRoot = blank(project.outputRoot) ? null : new File(project.outputRoot);
        File resolvedOutputRoot = resolveOutputRoot(storedOutputRoot, actualOutputRoot);
        if (resolvedOutputRoot != null) {
            project.outputRoot = resolvedOutputRoot.getAbsolutePath();
        }

        if (project.items != null) {
            for (ProjectFile.Item item : project.items) {
                File source = resolveSource(item, storedOutputRoot, actualOutputRoot);
                if (source != null) {
                    item.path = source.getAbsolutePath();
                }
            }
        }
        return project;
    }

    /**
     * Add portable relative hints for source files that live under the output
     * root. The absolute path remains present for older FLASH builds.
     */
    public static void addRelativePathHints(ProjectFile project, File outputRoot) {
        if (project == null || project.items == null) {
            return;
        }
        for (ProjectFile.Item item : project.items) {
            if (item == null || blank(item.path)) {
                continue;
            }
            String relative = relativePathIfUnder(outputRoot, new File(item.path));
            if (relative == null || relative.length() == 0) {
                if (item.extras != null) {
                    item.extras.remove(K_PATH_RELATIVE_TO_OUTPUT_ROOT);
                }
            } else {
                if (item.extras == null) {
                    item.extras = new java.util.LinkedHashMap<String, Object>();
                }
                item.extras.put(K_PATH_RELATIVE_TO_OUTPUT_ROOT, relative);
            }
        }
    }

    static List<File> pathCandidates(File storedPath) {
        List<File> out = new ArrayList<File>();
        Set<String> seen = new LinkedHashSet<String>();
        addCandidate(out, seen, storedPath);
        addCandidate(out, seen, relocateWindowsUserHome(storedPath));
        for (File candidate : cloudAnchorCandidates(storedPath)) {
            addCandidate(out, seen, candidate);
        }
        return out;
    }

    static String relativePathIfUnder(File root, File file) {
        if (root == null || file == null || blank(root.getPath()) || blank(file.getPath())) {
            return null;
        }
        try {
            File rootCanonical = root.getCanonicalFile();
            File fileCanonical = file.getCanonicalFile();
            String rootPath = rootCanonical.getPath();
            String filePath = fileCanonical.getPath();
            String rootCompare = comparisonPath(rootPath);
            String fileCompare = comparisonPath(filePath);
            String rootPrefix = withTrailingSeparator(rootCompare);
            if (!fileCompare.startsWith(rootPrefix)) {
                return null;
            }
            String relative = rootCanonical.toPath().relativize(fileCanonical.toPath()).toString();
            return relative.replace(File.separatorChar, '/');
        } catch (IOException e) {
            return null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static File nearestExistingParent(File file) {
        File cursor = file;
        if (cursor != null && cursor.isFile()) {
            cursor = cursor.getParentFile();
        }
        while (cursor != null && !cursor.exists()) {
            cursor = cursor.getParentFile();
        }
        return cursor != null && cursor.isDirectory() ? cursor.getAbsoluteFile() : null;
    }

    private static File resolveOutputRoot(File storedOutputRoot, File actualOutputRoot) {
        // REGRESSION GUARD: the project root is wherever project.json physically
        // lives right now (actualOutputRoot, derived from the file the user
        // actually opened), NOT the absolute path string saved inside it
        // (storedOutputRoot). channel_config.json sits in the same
        // FLASH/Config/.settings folder as project.json, so anchoring the root
        // to the physical location keeps config + outputs tied to the project
        // the user opened. Trusting the stored string broke reopening copied /
        // moved / restored projects: whenever the old folder still existed on
        // disk it won, and config was silently read from the wrong location.
        if (actualOutputRoot != null && actualOutputRoot.isDirectory()) {
            return actualOutputRoot.getAbsoluteFile();
        }
        if (storedOutputRoot != null && storedOutputRoot.isDirectory()) {
            return storedOutputRoot.getAbsoluteFile();
        }
        for (File candidate : pathCandidates(storedOutputRoot)) {
            if (candidate.isDirectory()) {
                return candidate.getAbsoluteFile();
            }
        }
        if (actualOutputRoot != null) {
            return actualOutputRoot.getAbsoluteFile();
        }
        return storedOutputRoot == null ? null : storedOutputRoot.getAbsoluteFile();
    }

    private static File resolveSource(ProjectFile.Item item, File storedOutputRoot, File actualOutputRoot) {
        if (item == null || blank(item.path)) {
            return null;
        }
        File storedSource = new File(item.path);
        if (storedSource.isFile()) {
            return storedSource.getAbsoluteFile();
        }

        File relativeHint = sourceFromRelativeHint(item.extras, actualOutputRoot);
        if (relativeHint != null) {
            return relativeHint;
        }

        if (storedOutputRoot != null && actualOutputRoot != null) {
            String relativeToOldRoot = relativePathIfUnder(storedOutputRoot, storedSource);
            File remapped = child(actualOutputRoot, relativeToOldRoot);
            if (remapped != null && remapped.isFile()) {
                return remapped.getAbsoluteFile();
            }
        }

        for (File candidate : pathCandidates(storedSource)) {
            if (candidate.isFile()) {
                return candidate.getAbsoluteFile();
            }
        }

        File uniqueByName = findUniqueByName(actualOutputRoot, storedSource.getName(), SOURCE_NAME_SEARCH_DEPTH);
        return uniqueByName == null ? null : uniqueByName.getAbsoluteFile();
    }

    private static File sourceFromRelativeHint(Map<String, Object> extras, File actualOutputRoot) {
        if (extras == null || actualOutputRoot == null) {
            return null;
        }
        Object value = extras.get(K_PATH_RELATIVE_TO_OUTPUT_ROOT);
        if (value == null) {
            return null;
        }
        File candidate = child(actualOutputRoot, String.valueOf(value).trim());
        return candidate != null && candidate.isFile() ? candidate.getAbsoluteFile() : null;
    }

    private static File child(File root, String relativePath) {
        if (root == null || blank(relativePath)) {
            return null;
        }
        String normalized = relativePath.replace('\\', File.separatorChar)
                .replace('/', File.separatorChar);
        File asFile = new File(normalized);
        if (asFile.isAbsolute()) {
            return null;
        }
        return new File(root, normalized);
    }

    private static File outputRootForProjectJson(File projectJson) {
        if (projectJson == null) {
            return null;
        }
        return FlashProjectLayout.projectRootForConfigurationDir(projectJson.getParentFile());
    }

    private static File relocateWindowsUserHome(File original) {
        if (original == null) {
            return null;
        }
        String currentHome = System.getProperty("user.home");
        if (blank(currentHome)) {
            return null;
        }
        String path = original.getPath().replace('/', '\\');
        String lower = path.toLowerCase(Locale.ROOT);
        int users = lower.indexOf("\\users\\");
        if (users < 0) {
            return null;
        }
        int userStart = users + "\\users\\".length();
        int nextSlash = path.indexOf('\\', userStart);
        if (nextSlash < 0) {
            return null;
        }
        String suffix = path.substring(nextSlash);
        return new File(currentHome + suffix);
    }

    private static List<File> cloudAnchorCandidates(File original) {
        List<File> out = new ArrayList<File>();
        if (original == null) {
            return out;
        }
        String[] parts = original.getPath().replace('\\', '/').split("/");
        for (int i = 0; i < parts.length; i++) {
            String anchor = parts[i];
            if (!isCloudAnchor(anchor)) {
                continue;
            }
            List<String> suffix = new ArrayList<String>();
            for (int j = i + 1; j < parts.length; j++) {
                if (!parts[j].isEmpty()) {
                    suffix.add(parts[j]);
                }
            }
            for (File anchorDir : currentAnchorDirs(anchor)) {
                out.add(appendSegments(anchorDir, suffix));
            }
        }
        return out;
    }

    private static boolean isCloudAnchor(String segment) {
        if (blank(segment)) {
            return false;
        }
        String lower = segment.toLowerCase(Locale.ROOT);
        return lower.contains("dropbox") || lower.startsWith("onedrive");
    }

    private static List<File> currentAnchorDirs(String anchorName) {
        List<File> out = new ArrayList<File>();
        Set<String> seen = new LinkedHashSet<String>();
        addAnchorUnderBase(out, seen, System.getProperty("user.home"), anchorName);
        addAnchorUnderBase(out, seen, System.getenv("USERPROFILE"), anchorName);

        String[] envNames = {"DROPBOX", "OneDrive", "OneDriveCommercial", "OneDriveConsumer"};
        for (String envName : envNames) {
            String env = System.getenv(envName);
            if (blank(env)) {
                continue;
            }
            File envDir = new File(env);
            if (envDir.getName().equalsIgnoreCase(anchorName)) {
                addAnchorIfDirectory(out, seen, envDir);
            }
            addAnchorIfDirectory(out, seen, new File(envDir, anchorName));
        }

        File cursor = new File("").getAbsoluteFile();
        while (cursor != null) {
            if (cursor.getName().equalsIgnoreCase(anchorName)) {
                addAnchorIfDirectory(out, seen, cursor);
            }
            cursor = cursor.getParentFile();
        }
        return out;
    }

    private static void addAnchorUnderBase(List<File> out, Set<String> seen,
                                           String basePath, String anchorName) {
        if (!blank(basePath)) {
            addAnchorIfDirectory(out, seen, new File(basePath, anchorName));
        }
    }

    private static File appendSegments(File root, List<String> suffix) {
        File out = root;
        for (String part : suffix) {
            out = new File(out, part);
        }
        return out;
    }

    private static void addAnchorIfDirectory(List<File> out, Set<String> seen, File dir) {
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        addCandidate(out, seen, dir);
    }

    private static void addCandidate(List<File> out, Set<String> seen, File candidate) {
        if (candidate == null) {
            return;
        }
        String key = comparisonPath(candidate.getAbsolutePath());
        if (seen.add(key)) {
            out.add(candidate.getAbsoluteFile());
        }
    }

    private static File findUniqueByName(File root, String fileName, int maxDepth) {
        if (root == null || !root.isDirectory() || blank(fileName)) {
            return null;
        }
        UniqueFileMatch match = new UniqueFileMatch();
        findUniqueByName(root, fileName, maxDepth, match);
        return match.ambiguous ? null : match.file;
    }

    private static void findUniqueByName(File dir, String fileName, int depthRemaining, UniqueFileMatch match) {
        if (match.ambiguous || dir == null || depthRemaining < 0) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (match.ambiguous) {
                return;
            }
            if (child.isFile() && child.getName().equalsIgnoreCase(fileName)) {
                if (match.file == null) {
                    match.file = child;
                } else {
                    match.ambiguous = true;
                    return;
                }
            } else if (child.isDirectory() && depthRemaining > 0
                    && !FlashProjectLayout.FLASH_DIR.equalsIgnoreCase(child.getName())) {
                findUniqueByName(child, fileName, depthRemaining - 1, match);
            }
        }
    }

    private static String comparisonPath(String path) {
        if (path == null) {
            return "";
        }
        String out = path.replace('\\', File.separatorChar).replace('/', File.separatorChar);
        if (File.separatorChar == '\\' || looksLikeWindowsPath(out)) {
            return out.toLowerCase(Locale.ROOT);
        }
        return out;
    }

    private static String withTrailingSeparator(String path) {
        if (path.endsWith(String.valueOf(File.separatorChar))) {
            return path;
        }
        return path + File.separatorChar;
    }

    private static boolean looksLikeWindowsPath(String path) {
        return path.length() >= 2 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':';
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static final class UniqueFileMatch {
        File file;
        boolean ambiguous;
    }
}
