package flash.pipeline.project;

import flash.pipeline.io.FlashProjectLayout;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
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

    /**
     * How many parent levels {@link #resolveProjectJsonNear} climbs before
     * giving up. Covers {@code .settings} → {@code Config} → {@code FLASH} →
     * project root and a little headroom, so standing a couple of folders deep
     * inside a project still resolves without matching unrelated projects far
     * above the selection.
     */
    private static final int UPWARD_SEARCH_DEPTH = 5;

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
     * Resolve a {@code project.json} from anywhere in or near a user selection.
     *
     * <p>Probes the selection itself with {@link #projectJsonFromSelectedLocation}
     * and, failing that, walks up a few parent levels probing each one. This
     * makes every reasonable pick resolve to the same project: the project
     * output folder (the one that contains {@code FLASH}), the {@code FLASH}
     * folder, the {@code Config} or {@code .settings} folder, a {@code
     * project.json} file, or merely standing inside any of those — which is
     * what happens when a file chooser hands back its current directory instead
     * of the folder the user meant to select.
     *
     * @return the resolved project.json, or {@code null} when no enclosing
     *         FLASH project is found near the selection.
     */
    public static File resolveProjectJsonNear(File selected) {
        if (selected == null) {
            return null;
        }
        File start = selected.getAbsoluteFile();
        File direct = projectJsonFromSelectedLocation(start);
        if (direct != null) {
            return direct;
        }
        // No project at or beneath the selection itself: climb toward an
        // enclosing FLASH project (e.g. the user picked FLASH/Results or the
        // chooser returned a folder one level inside the project root).
        File cursor = start.getParentFile();
        for (int climbed = 0; cursor != null && climbed < UPWARD_SEARCH_DEPTH; climbed++) {
            File found = projectJsonFromSelectedLocation(cursor);
            if (found != null) {
                return found;
            }
            cursor = cursor.getParentFile();
        }
        return null;
    }

    /**
     * Adjust a decoded project so paths point at files on this machine where
     * that can be inferred safely.
     */
    public static ProjectFile relocateForLoad(ProjectFile project, File projectJson, File fallbackOutputRoot) {
        return relocateForLoad(project, projectJson, fallbackOutputRoot, true);
    }

    /**
     * Adjust a decoded project so paths point at files on this machine.
     *
     * <p>Set {@code allowSourceNameSearch} to false for runtime image loading,
     * where accepting a same-named file under the output root can silently
     * process stale data instead of the manifest source.
     */
    public static ProjectFile relocateForLoad(ProjectFile project, File projectJson,
                                              File fallbackOutputRoot,
                                              boolean allowSourceNameSearch) {
        if (project == null) {
            return null;
        }
        File actualOutputRoot = physicallyOpenedOutputRoot(projectJson, fallbackOutputRoot);
        File storedOutputRoot = blank(project.outputRoot) ? null : new File(project.outputRoot);
        File resolvedOutputRoot = resolveOutputRoot(storedOutputRoot, actualOutputRoot);
        if (resolvedOutputRoot != null) {
            project.outputRoot = resolvedOutputRoot.getAbsolutePath();
        }

        if (project.items != null) {
            for (ProjectFile.Item item : project.items) {
                File source = resolveSource(item, storedOutputRoot, actualOutputRoot,
                        allowSourceNameSearch);
                if (source != null) {
                    item.path = source.getAbsolutePath();
                }
            }
        }
        return project;
    }

    /**
     * Revalidate and return the real file selected by {@link #relocateForLoad}.
     *
     * <p>Runtime image loading uses this method instead of reopening the raw
     * manifest string. A valid contained relative hint remains authoritative;
     * a hint that escapes the physically opened project fails closed. External
     * sources remain valid when there is no existing contained hint.
     *
     * @throws SourceResolutionException if the relative hint escapes the
     *         project or disagrees with the resolved contained source
     */
    public static File requireResolvedSource(ProjectFile.Item item, File openedOutputRoot) {
        if (item == null || blank(item.path)) {
            throw new SourceResolutionException("Project source path is empty.");
        }
        File root = realDirectory(openedOutputRoot);
        File contained = sourceFromRelativeHint(item.extras, root, item.path);
        File resolved = realExistingFile(new File(item.path));
        if (resolved == null) {
            throw new SourceResolutionException("Project refers to a missing included source file: "
                    + new File(item.path).getAbsolutePath());
        }
        if (contained != null && !sameRealFile(contained, resolved)) {
            throw ambiguousSourceError(root, contained, resolved);
        }
        return resolved;
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

    private static File resolveSource(ProjectFile.Item item, File storedOutputRoot,
                                      File actualOutputRoot,
                                      boolean allowSourceNameSearch) {
        if (item == null || blank(item.path)) {
            return null;
        }
        File storedSource = new File(item.path);

        List<File> containedCandidates = new ArrayList<File>();
        File relativeHint = sourceFromRelativeHint(item.extras, actualOutputRoot, item.path);
        addDistinctRealFile(containedCandidates, relativeHint);

        if (storedOutputRoot != null && actualOutputRoot != null) {
            String relativeToOldRoot = relativePathIfUnder(storedOutputRoot, storedSource);
            if (relativeToOldRoot != null) {
                File remapped = confinedExistingFile(actualOutputRoot, relativeToOldRoot,
                        item.path, false);
                addDistinctRealFile(containedCandidates, remapped);
            }
        }

        if (containedCandidates.size() > 1) {
            throw ambiguousSourceError(actualOutputRoot,
                    containedCandidates.get(0), containedCandidates.get(1));
        }
        if (!containedCandidates.isEmpty()) {
            return containedCandidates.get(0);
        }

        File existingStoredSource = realExistingFile(storedSource);
        if (existingStoredSource != null) {
            return existingStoredSource;
        }

        List<File> relocatedCandidates = new ArrayList<File>();
        for (File candidate : pathCandidates(storedSource)) {
            addDistinctRealFile(relocatedCandidates, realExistingFile(candidate));
        }
        if (relocatedCandidates.size() > 1) {
            throw ambiguousSourceError(actualOutputRoot,
                    relocatedCandidates.get(0), relocatedCandidates.get(1));
        }
        if (!relocatedCandidates.isEmpty()) {
            return relocatedCandidates.get(0);
        }

        if (!allowSourceNameSearch) {
            return null;
        }
        File uniqueByName = findUniqueByName(actualOutputRoot, storedSource.getName(), SOURCE_NAME_SEARCH_DEPTH);
        return uniqueByName == null ? null : realExistingFile(uniqueByName);
    }

    private static File sourceFromRelativeHint(Map<String, Object> extras, File actualOutputRoot,
                                               String storedSourcePath) {
        if (extras == null) {
            return null;
        }
        Object value = extras.get(K_PATH_RELATIVE_TO_OUTPUT_ROOT);
        if (value == null) {
            return null;
        }
        if (actualOutputRoot == null) {
            throw new SourceResolutionException(
                    "Cannot resolve project source hint '" + String.valueOf(value)
                            + "' because the physically opened project root is unavailable."
                            + (blank(storedSourcePath) ? "" : " Stored source: "
                            + new File(storedSourcePath).getAbsolutePath()));
        }
        return confinedExistingFile(actualOutputRoot, String.valueOf(value).trim(),
                storedSourcePath, true);
    }

    private static File confinedExistingFile(File root, String relativePath,
                                             String storedSourcePath,
                                             boolean rejectParentSegments) {
        if (root == null || blank(relativePath)) {
            return null;
        }
        String normalized = relativePath.replace('\\', File.separatorChar)
                .replace('/', File.separatorChar);
        Path relative;
        try {
            relative = Paths.get(normalized);
        } catch (InvalidPathException e) {
            throw unsafeRelativeHintError(root, relativePath, storedSourcePath,
                    "invalid path syntax", null);
        }
        if (relative.isAbsolute() || startsWithSeparator(normalized)
                || looksLikeWindowsPath(normalized)) {
            throw unsafeRelativeHintError(root, relativePath, storedSourcePath,
                    "absolute paths are not allowed", null);
        }
        if (rejectParentSegments && containsParentSegment(relative)) {
            throw unsafeRelativeHintError(root, relativePath, storedSourcePath,
                    "parent ('..') segments are not allowed", null);
        }

        File realRoot = realDirectory(root);
        if (realRoot == null) {
            throw new SourceResolutionException("Opened project root does not exist or is not a directory: "
                    + root.getAbsolutePath());
        }
        Path rootPath = realRoot.toPath();
        Path lexicalCandidate = rootPath.resolve(relative).normalize();
        if (!lexicalCandidate.startsWith(rootPath)) {
            throw unsafeRelativeHintError(realRoot, relativePath, storedSourcePath,
                    "lexical path escapes the opened project root", lexicalCandidate.toFile());
        }
        if (!Files.exists(lexicalCandidate)) {
            return null;
        }
        final Path realCandidate;
        try {
            realCandidate = lexicalCandidate.toRealPath();
        } catch (IOException e) {
            throw unsafeRelativeHintError(realRoot, relativePath, storedSourcePath,
                    "candidate could not be resolved to a real path", lexicalCandidate.toFile());
        }
        if (!realCandidate.startsWith(rootPath)) {
            throw unsafeRelativeHintError(realRoot, relativePath, storedSourcePath,
                    "symlink, junction, or reparse point escapes the opened project root",
                    realCandidate.toFile());
        }
        if (!Files.isRegularFile(realCandidate)) {
            return null;
        }
        return realCandidate.toFile();
    }

    private static File physicallyOpenedOutputRoot(File projectJson, File fallbackOutputRoot) {
        if (projectJson != null && projectJson.isFile()) {
            try {
                File realProjectJson = projectJson.toPath().toRealPath().toFile();
                File physicalRoot = outputRootForProjectJson(realProjectJson);
                File realPhysicalRoot = realDirectory(physicalRoot);
                if (realPhysicalRoot != null) {
                    return realPhysicalRoot;
                }
            } catch (IOException e) {
                throw new SourceResolutionException("Could not resolve the physically opened project file: "
                        + projectJson.getAbsolutePath(), e);
            }
        }
        File fallbackReal = realDirectory(fallbackOutputRoot);
        if (fallbackReal != null) {
            return fallbackReal;
        }
        return fallbackOutputRoot != null
                ? fallbackOutputRoot.getAbsoluteFile()
                : outputRootForProjectJson(projectJson);
    }

    private static File realDirectory(File directory) {
        if (directory == null || !directory.isDirectory()) {
            return null;
        }
        try {
            Path real = directory.toPath().toRealPath();
            return Files.isDirectory(real) ? real.toFile() : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static File realExistingFile(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        try {
            Path real = file.toPath().toRealPath();
            return Files.isRegularFile(real) ? real.toFile() : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean sameRealFile(File first, File second) {
        if (first == null || second == null) {
            return false;
        }
        try {
            return Files.isSameFile(first.toPath(), second.toPath());
        } catch (IOException e) {
            return first.equals(second);
        }
    }

    private static void addDistinctRealFile(List<File> files, File candidate) {
        if (candidate == null) {
            return;
        }
        for (File existing : files) {
            if (sameRealFile(existing, candidate)) {
                return;
            }
        }
        files.add(candidate);
    }

    private static boolean containsParentSegment(Path path) {
        for (Path part : path) {
            if ("..".equals(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWithSeparator(String path) {
        return path.startsWith("/") || path.startsWith("\\");
    }

    private static SourceResolutionException unsafeRelativeHintError(
            File root, String hint, String storedSourcePath, String reason, File resolvedCandidate) {
        StringBuilder message = new StringBuilder();
        message.append("Unsafe project source hint '").append(hint).append("': ")
                .append(reason).append(". Opened project root: ")
                .append(root == null ? "<unknown>" : root.getAbsolutePath());
        if (resolvedCandidate != null) {
            message.append("; resolved candidate: ").append(resolvedCandidate.getAbsolutePath());
        }
        if (!blank(storedSourcePath)) {
            message.append("; stored source: ").append(new File(storedSourcePath).getAbsolutePath());
        }
        return new SourceResolutionException(message.toString());
    }

    private static SourceResolutionException ambiguousSourceError(
            File root, File first, File second) {
        return new SourceResolutionException(
                "Ambiguous project source resolution under opened project root "
                        + (root == null ? "<unknown>" : root.getAbsolutePath())
                        + ": both " + first.getAbsolutePath() + " and "
                        + second.getAbsolutePath() + " are valid candidates.");
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
        File realRoot = realDirectory(root);
        if (realRoot == null) {
            return null;
        }
        findUniqueByName(realRoot, realRoot, fileName, maxDepth, match,
                new HashSet<String>());
        if (match.ambiguous) {
            throw ambiguousSourceError(realRoot, match.file, match.secondFile);
        }
        return match.file;
    }

    private static void findUniqueByName(File root, File dir, String fileName,
                                         int depthRemaining, UniqueFileMatch match,
                                         Set<String> visitedDirectories) {
        if (match.ambiguous || dir == null || depthRemaining < 0) {
            return;
        }
        File realDir = realDirectory(dir);
        if (realDir == null || !isRealPathUnder(root, realDir)
                || !visitedDirectories.add(comparisonPath(realDir.getAbsolutePath()))) {
            return;
        }
        File[] children = realDir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (match.ambiguous) {
                return;
            }
            if (child.isFile() && child.getName().equalsIgnoreCase(fileName)) {
                File realChild = realExistingFile(child);
                if (realChild == null || !isRealPathUnder(root, realChild)) {
                    continue;
                }
                if (match.file == null) {
                    match.file = realChild;
                } else if (!sameRealFile(match.file, realChild)) {
                    match.secondFile = realChild;
                    match.ambiguous = true;
                    return;
                }
            } else if (child.isDirectory() && depthRemaining > 0
                    && !FlashProjectLayout.FLASH_DIR.equalsIgnoreCase(child.getName())) {
                findUniqueByName(root, child, fileName, depthRemaining - 1, match,
                        visitedDirectories);
            }
        }
    }

    private static boolean isRealPathUnder(File root, File candidate) {
        File realRoot = realDirectory(root);
        if (realRoot == null || candidate == null) {
            return false;
        }
        File realCandidate = candidate.isDirectory()
                ? realDirectory(candidate) : realExistingFile(candidate);
        return realCandidate != null && realCandidate.toPath().startsWith(realRoot.toPath());
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
        File secondFile;
        boolean ambiguous;
    }

    /** Explicit failure for unsafe or ambiguous persisted project paths. */
    public static final class SourceResolutionException extends IllegalArgumentException {
        public SourceResolutionException(String message) {
            super(message);
        }

        public SourceResolutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
