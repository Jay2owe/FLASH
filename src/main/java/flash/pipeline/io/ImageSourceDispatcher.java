package flash.pipeline.io;

import ij.IJ;
import flash.pipeline.intelligence.JunkFileFilter;
import flash.pipeline.project.ProjectFile;
import flash.pipeline.project.ProjectFileIO;
import flash.pipeline.project.ProjectMetadataSeeder;
import flash.pipeline.project.ProjectPathResolver;
import flash.pipeline.ui.PipelineDialog;
import flash.pipeline.ui.ToggleSwitch;

import javax.swing.JComboBox;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Picks the right input mode for a project directory and returns a
 * {@link DeferredImageSupplier} or per-series metadata list. Replaces
 * the LIF-only entry points in {@link LifIO} with a source-agnostic factory.
 *
 * <p>Discovery rule (applied in order):
 * <ol>
 *   <li>≥1 container file in the project dir → CONTAINER mode. Loose TIFFs in
 *       the project dir or in {@code input/} are ignored. If multiple containers
 *       exist, the actual file is resolved later by
 *       {@link #selectContainer(File)}, which prompts the user (interactive)
 *       or throws (headless).</li>
 *   <li>{@code projectDir/input/} contains ≥1 TIFF → TIFF_INPUT_SUBFOLDER mode.
 *       Loose TIFFs in the project dir alongside an input/ folder are still
 *       a "mixed sources" error.</li>
 *   <li>Project dir contains ≥1 loose TIFF → TIFF_LOOSE mode (file mover
 *       UI is added in a later stage).</li>
 *   <li>Otherwise → error.</li>
 * </ol>
 *
 * <p>Container vs TIFF disambiguation: {@code .ome.tif} and {@code .ome.tiff}
 * are treated as containers (Bio-Formats reads them as multi-series files),
 * so they are excluded from the bare-TIFF list.
 */
public final class ImageSourceDispatcher {

    public static final List<String> CONTAINER_EXTENSIONS =
            Collections.unmodifiableList(Arrays.asList(
                    ".lif", ".lof", ".czi", ".nd2", ".lsm",
                    ".oib", ".oif", ".ims", ".ome.tif", ".ome.tiff"));

    public static final List<String> TIFF_EXTENSIONS =
            Collections.unmodifiableList(Arrays.asList(".tif", ".tiff"));

    public enum SourceMode { CONTAINER, TIFF_INPUT_SUBFOLDER, TIFF_LOOSE }

    public static final String SUPPRESS_CALIBRATION_WARNING_MARKER =
            "suppressCalibrationWarning";

    private static final String CALIBRATION_WARNING_MESSAGE =
            "Input TIFFs have no physical calibration.\n"
            + "Morphometric measurements (volume, surface area, length, Morph_*) "
            + "will be in pixel units, not microns.";

    private static final Set<String> WARNED =
            Collections.synchronizedSet(new HashSet<String>());
    private static final Set<String> LOGGED_PROJECT_MANIFESTS =
            Collections.synchronizedSet(new HashSet<String>());
    private static final Map<String, String> CONTAINER_CHOICE_CACHE =
            Collections.synchronizedMap(new HashMap<String, String>());
    private static Boolean headlessOverrideForTests = null;
    private static String containerChoiceOverrideForTests = null;

    private static final Comparator<File> CASE_INSENSITIVE_NAME = new Comparator<File>() {
        @Override
        public int compare(File a, File b) {
            return a.getName().compareToIgnoreCase(b.getName());
        }
    };

    private ImageSourceDispatcher() {}

    /**
     * Returns the input mode for {@code directory} without side effects.
     *
     * <p>If any container files are present, CONTAINER mode is returned and any
     * loose / sub-folder TIFFs are ignored. The actual container is resolved
     * later by {@link #selectContainer(File)}.
     *
     * @throws IllegalArgumentException if the directory is missing, contains
     *         both an input/ TIFF folder and loose project-root TIFFs (with no
     *         container to disambiguate), or has no compatible input at all.
     */
    public static SourceMode detectMode(String directory) {
        File dir = requireDirectory(directory);
        // When a project.json is present, sources come from its item list
        // rather than directory scanning.
        ProjectSources projectSources = tryReadProjectSources(dir);
        if (projectSources != null) {
            if (projectSources.hasContainers()) {
                return SourceMode.CONTAINER;
            }
            if (projectSources.hasTiffs()) {
                return SourceMode.TIFF_LOOSE;
            }
            throw emptyProjectSourcesError(dir);
        }

        List<File> containers = listContainers(dir);
        if (!containers.isEmpty()) {
            return SourceMode.CONTAINER;
        }
        List<File> looseTiffs = listTiffs(dir);
        File inputSub = new File(dir, "input");
        List<File> subTiffs = inputSub.isDirectory()
                ? listTiffs(inputSub)
                : Collections.<File>emptyList();
        if (!subTiffs.isEmpty() && !looseTiffs.isEmpty()) {
            throw mixedSourcesError(dir, Collections.<File>emptyList(), looseTiffs, subTiffs);
        }
        if (!subTiffs.isEmpty()) {
            return SourceMode.TIFF_INPUT_SUBFOLDER;
        }
        if (!looseTiffs.isEmpty()) {
            return SourceMode.TIFF_LOOSE;
        }
        throw new IllegalArgumentException(
                "No compatible input found in " + dir.getAbsolutePath()
                + ". Expected one of: a multi-series container file"
                + " (.lif/.czi/.nd2/...) OR .tif files in input/ OR loose .tif files.");
    }

    /** Source files + per-container series narrowing, sourced from project.json. */
    private static final class ProjectSources {
        final List<File> containers;
        /** Parallel to {@link #containers}; empty entry = "all series in that container". */
        final List<List<Integer>> includedSeriesPerContainer;
        final List<File> tiffs;
        final String displayName;
        ProjectSources(List<File> containers, List<List<Integer>> included,
                       List<File> tiffs, String displayName) {
            this.containers = containers;
            this.includedSeriesPerContainer = included;
            this.tiffs = tiffs;
            this.displayName = displayName;
        }
        boolean hasContainers() {
            return containers != null && !containers.isEmpty();
        }
        boolean hasTiffs() {
            return tiffs != null && !tiffs.isEmpty();
        }
    }

    private static final class ProjectLocation {
        final File projectRoot;
        final File settingsDir;

        ProjectLocation(File projectRoot, File settingsDir) {
            this.projectRoot = projectRoot;
            this.settingsDir = settingsDir;
        }
    }

    private static ProjectSources tryReadProjectSources(File selectedDirectory) {
        ProjectLocation projectLocation = findProjectManifestLocation(selectedDirectory);
        if (projectLocation == null) {
            return null;
        }
        File outputRoot = projectLocation.projectRoot;
        File settingsDir = projectLocation.settingsDir;
        ProjectFile project = ProjectFileIO.read(settingsDir);
        if (project == null || project.items == null) {
            throw new IllegalArgumentException(
                    "Could not read project.json at "
                            + new File(settingsDir, ProjectFileIO.FILE_NAME).getAbsolutePath()
                            + ". FLASH will not fall back to scanning "
                            + selectedDirectory.getAbsolutePath()
                            + " because that can use the wrong source file.");
        }
        ProjectPathResolver.relocateForLoad(project,
                new File(settingsDir, ProjectFileIO.FILE_NAME), outputRoot, false);
        syncProjectOrientationMetadata(outputRoot, settingsDir, project);
        List<File> containers = new ArrayList<File>();
        List<List<Integer>> includes = new ArrayList<List<Integer>>();
        List<File> tiffs = new ArrayList<File>();
        for (ProjectFile.Item item : project.items) {
            if (item == null || !item.include) continue;
            if (item.path == null || item.path.trim().isEmpty()) continue;
            File source = new File(item.path);
            if (!source.isFile()) {
                throw new IllegalArgumentException(
                        "project.json at " + outputRoot.getAbsolutePath()
                                + " refers to a missing included source file: "
                                + source.getAbsolutePath());
            }
            if (isContainerExtension(source.getName())) {
                containers.add(source);
                includes.add(item.series == null
                        ? Collections.<Integer>emptyList()
                        : new ArrayList<Integer>(item.series));
            } else if (isBareTiffExtension(source.getName())) {
                validateTiffSeriesSelection(source, item.series);
                tiffs.add(source);
            } else {
                throw new IllegalArgumentException(
                        "project.json at " + outputRoot.getAbsolutePath()
                                + " includes unsupported source file: "
                                + source.getAbsolutePath());
            }
        }
        if (!containers.isEmpty() && !tiffs.isEmpty()) {
            throw new IllegalArgumentException(
                    "project.json at " + outputRoot.getAbsolutePath()
                            + " mixes multi-series container files and bare TIFF files. "
                            + "FLASH currently requires those source types to be opened as separate projects.");
        }
        logProjectManifestUse(outputRoot, settingsDir, containers, includes, tiffs);
        return new ProjectSources(containers, includes, tiffs, projectDisplayName(project, outputRoot));
    }

    private static ProjectLocation findProjectManifestLocation(File selectedDirectory) {
        ProjectLocation fromConfig = projectLocationFromConfigurationSelection(selectedDirectory);
        if (fromConfig != null && ProjectFileIO.exists(fromConfig.settingsDir)) {
            return fromConfig;
        }

        ProjectLocation fromFlash = projectLocationFromFlashSelection(selectedDirectory);
        if (fromFlash != null && ProjectFileIO.exists(fromFlash.settingsDir)) {
            return fromFlash;
        }

        ProjectLocation fromRoot = new ProjectLocation(
                selectedDirectory,
                FlashProjectLayout.forDirectory(selectedDirectory.getAbsolutePath())
                        .configurationWriteDir());
        return ProjectFileIO.exists(fromRoot.settingsDir) ? fromRoot : null;
    }

    private static ProjectLocation projectLocationFromConfigurationSelection(File selectedDirectory) {
        File projectRoot = FlashProjectLayout.projectRootForConfigurationDir(selectedDirectory);
        if (projectRoot == null) {
            return null;
        }
        File settingsDir;
        if (FlashProjectLayout.SETTINGS_DIR.equals(selectedDirectory.getName())) {
            settingsDir = selectedDirectory;
        } else {
            settingsDir = new File(selectedDirectory, FlashProjectLayout.SETTINGS_DIR);
        }
        return new ProjectLocation(projectRoot, settingsDir);
    }

    private static ProjectLocation projectLocationFromFlashSelection(File selectedDirectory) {
        if (selectedDirectory == null
                || !FlashProjectLayout.FLASH_DIR.equals(selectedDirectory.getName())) {
            return null;
        }
        File projectRoot = selectedDirectory.getParentFile();
        if (projectRoot == null) {
            return null;
        }
        File settingsDir = new File(
                new File(selectedDirectory, FlashProjectLayout.CONFIGURATION_DIR),
                FlashProjectLayout.SETTINGS_DIR);
        return new ProjectLocation(projectRoot, settingsDir);
    }

    private static void syncProjectOrientationMetadata(File outputRoot,
                                                       File settingsDir,
                                                       ProjectFile project) {
        try {
            ProjectMetadataSeeder.seedOrientationManifest(outputRoot, project);
        } catch (IOException e) {
            IJ.log("[FLASH Project] Could not sync image orientation metadata from "
                    + new File(settingsDir, ProjectFileIO.FILE_NAME).getAbsolutePath()
                    + ": " + e.getMessage());
        }
    }

    private static void logProjectManifestUse(File outputRoot,
                                              File settingsDir,
                                              List<File> containers,
                                              List<List<Integer>> includes,
                                              List<File> tiffs) {
        File projectJson = new File(settingsDir, ProjectFileIO.FILE_NAME);
        String key = projectJson.getAbsolutePath();
        if (!LOGGED_PROJECT_MANIFESTS.add(key)) {
            return;
        }

        IJ.log("[FLASH Project] Reading project manifest: " + key);
        IJ.log("[FLASH Project] Output root: "
                + (outputRoot == null ? "(unknown)" : outputRoot.getAbsolutePath()));
        IJ.log("[FLASH Project] Included sources from project.json: "
                + (containers == null ? 0 : containers.size()) + " container(s), "
                + (tiffs == null ? 0 : tiffs.size()) + " TIFF file(s).");

        if (containers != null) {
            for (int i = 0; i < containers.size(); i++) {
                List<Integer> selected = includes == null || i >= includes.size()
                        ? Collections.<Integer>emptyList()
                        : includes.get(i);
                IJ.log("  [FLASH Project] Container source: "
                        + containers.get(i).getAbsolutePath()
                        + " | series=" + seriesSelectionLabel(selected));
            }
        }
        if (tiffs != null) {
            int shown = Math.min(10, tiffs.size());
            for (int i = 0; i < shown; i++) {
                IJ.log("  [FLASH Project] TIFF source: " + tiffs.get(i).getAbsolutePath());
            }
            if (tiffs.size() > shown) {
                IJ.log("  [FLASH Project] ... and " + (tiffs.size() - shown)
                        + " more TIFF source(s).");
            }
        }
    }

    private static String seriesSelectionLabel(List<Integer> selected) {
        if (selected == null || selected.isEmpty()) {
            return "all";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < selected.size(); i++) {
            if (i > 0) sb.append(',');
            Integer index = selected.get(i);
            sb.append(index == null ? "null" : index.toString());
        }
        return sb.toString();
    }

    private static boolean isContainerExtension(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        for (String ext : CONTAINER_EXTENSIONS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    private static boolean isBareTiffExtension(String name) {
        if (name == null || isContainerExtension(name)) return false;
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        for (String ext : TIFF_EXTENSIONS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    private static void validateTiffSeriesSelection(File source, List<Integer> series) {
        if (series == null || series.isEmpty()) return;
        for (Integer s : series) {
            if (s != null && s.intValue() != 0) {
                throw new IllegalArgumentException(
                        "project.json selects non-zero series for single-series TIFF source: "
                                + source.getAbsolutePath());
            }
        }
    }

    private static String projectDisplayName(ProjectFile project, File outputRoot) {
        if (project != null && project.name != null && !project.name.trim().isEmpty()) {
            return project.name.trim();
        }
        return outputRoot == null ? "" : outputRoot.getName();
    }

    /**
     * Returns a {@link DeferredImageSupplier} for {@code directory} using the
     * mode chosen by {@link #detectMode(String)}.
     */
    public static DeferredImageSupplier createSupplier(String directory) throws Exception {
        File dir = requireDirectory(directory);
        ProjectSources projectSources = tryReadProjectSources(dir);
        if (projectSources != null) {
            if (projectSources.hasContainers()) {
                return DeferredImageSupplier.multiContainer(
                        projectSources.containers, projectSources.includedSeriesPerContainer);
            }
            if (projectSources.hasTiffs()) {
                return new DeferredImageSupplier(projectSources.tiffs, projectSources.displayName);
            }
            throw emptyProjectSourcesError(dir);
        }

        SourceMode mode = detectMode(directory);
        if (mode == SourceMode.TIFF_LOOSE
                && LooseTiffRelocator.shouldPrompt(directory)
                && !LooseTiffRelocator.isHeadless()) {
            List<File> looseTiffs = listTiffs(dir);
            LooseTiffRelocator.Choice choice =
                    LooseTiffRelocator.promptAndMaybeMove(directory, looseTiffs);
            if (choice == LooseTiffRelocator.Choice.MOVED) {
                mode = detectMode(directory);
            }
        }
        switch (mode) {
            case CONTAINER:
                return new DeferredImageSupplier(selectContainer(dir));
            case TIFF_INPUT_SUBFOLDER:
                return new DeferredImageSupplier(
                        listTiffs(new File(dir, "input")), "input");
            case TIFF_LOOSE:
                return new DeferredImageSupplier(listTiffs(dir), dir.getName());
            default:
                throw new IllegalStateException("Unhandled SourceMode: " + mode);
        }
    }

    /**
     * Returns included project container files from {@code project.json}, or an
     * empty list when the directory is not a saved project or has no container
     * sources. The returned paths have already been relocated for this machine.
     */
    public static List<File> projectContainerFiles(String directory) {
        File dir = requireDirectory(directory);
        ProjectSources projectSources = tryReadProjectSources(dir);
        if (projectSources == null || !projectSources.hasContainers()) {
            return Collections.emptyList();
        }
        return new ArrayList<File>(projectSources.containers);
    }

    /**
     * Returns included project TIFF files from {@code project.json}, or an
     * empty list when the directory is not a saved project or has no TIFF
     * sources. The returned paths have already been relocated for this machine.
     */
    public static List<File> projectTiffFiles(String directory) {
        File dir = requireDirectory(directory);
        ProjectSources projectSources = tryReadProjectSources(dir);
        if (projectSources == null || !projectSources.hasTiffs()) {
            return Collections.emptyList();
        }
        return new ArrayList<File>(projectSources.tiffs);
    }

    /** True when {@code directory} has a saved FLASH {@code project.json}. */
    public static boolean hasProjectManifest(String directory) {
        File dir = requireDirectory(directory);
        return findProjectManifestLocation(dir) != null;
    }

    /**
     * Returns per-series metadata for {@code directory}. CONTAINER mode
     * delegates to {@link LifIO#readAllSeriesMetadata(File)}; TIFF modes call
     * {@link DeferredImageSupplier#readTiffFolderMetadata(List, String)}.
     */
    public static List<SeriesMeta> readAllMetadata(String directory) throws Exception {
        File dir = requireDirectory(directory);
        ProjectSources projectSources = tryReadProjectSources(dir);
        if (projectSources != null) {
            if (projectSources.hasTiffs()) {
                return DeferredImageSupplier.readTiffFolderMetadata(
                        projectSources.tiffs, projectSources.displayName);
            }
            List<SeriesMeta> out = new ArrayList<SeriesMeta>();
            int globalOffset = 0;
            for (int c = 0; c < projectSources.containers.size(); c++) {
                File container = projectSources.containers.get(c);
                List<SeriesMeta> perContainer = LifIO.readAllSeriesMetadata(container);
                List<Integer> include = projectSources.includedSeriesPerContainer.get(c);
                List<SeriesMeta> ordered =
                        selectedSeriesMetadataInProjectOrder(perContainer, include, container);
                for (SeriesMeta meta : ordered) {
                    out.add(meta.withIndex(globalOffset++));
                }
            }
            if (!out.isEmpty()) {
                return out;
            }
            throw emptyProjectSourcesError(dir);
        }

        SourceMode mode = detectMode(directory);
        switch (mode) {
            case CONTAINER:
                return LifIO.readAllSeriesMetadata(selectContainer(dir));
            case TIFF_INPUT_SUBFOLDER:
                return DeferredImageSupplier.readTiffFolderMetadata(
                        listTiffs(new File(dir, "input")), "input");
            case TIFF_LOOSE:
                return DeferredImageSupplier.readTiffFolderMetadata(
                        listTiffs(dir), dir.getName());
            default:
                throw new IllegalStateException("Unhandled SourceMode: " + mode);
        }
    }

    static List<SeriesMeta> selectedSeriesMetadataInProjectOrder(
            List<SeriesMeta> perContainer,
            List<Integer> include,
            File container) {
        List<SeriesMeta> ordered = new ArrayList<SeriesMeta>();
        boolean includeAll = include == null || include.isEmpty();
        if (includeAll) {
            if (perContainer != null) {
                ordered.addAll(perContainer);
            }
            return ordered;
        }
        for (Integer localIndex : include) {
            if (localIndex == null) continue;
            ordered.add(findSeriesMeta(perContainer, localIndex.intValue(), container));
        }
        return ordered;
    }

    private static SeriesMeta findSeriesMeta(List<SeriesMeta> metas,
                                             int localIndex,
                                             File container) {
        if (metas != null) {
            for (SeriesMeta meta : metas) {
                if (meta != null && meta.index == localIndex) {
                    return meta;
                }
            }
        }
        throw new IllegalArgumentException(
                "project.json selects series index " + localIndex
                        + " outside metadata for "
                        + (container == null ? "container source" : container.getAbsolutePath()));
    }

    /**
     * Resolves which container file to use for {@code dir}.
     *
     * <p>If exactly one container exists, it is returned. If multiple containers
     * exist, the user is prompted via {@link PipelineDialog} to pick one and
     * the selection is cached for the rest of the JVM session (so subsequent
     * analyses don't re-prompt for the same directory). In headless mode with
     * multiple containers, throws {@link IllegalArgumentException} listing the
     * candidates.
     */
    public static File selectContainer(File dir) {
        if (dir == null || !dir.isDirectory()) {
            throw new IllegalArgumentException(
                    "Not a directory: " + (dir == null ? "null" : dir.getAbsolutePath()));
        }
        List<File> containers = listContainers(dir);
        if (containers.isEmpty()) {
            throw new IllegalArgumentException(
                    "No container files in " + dir.getAbsolutePath());
        }
        if (containers.size() == 1) {
            return containers.get(0);
        }

        String dirKey = dir.getAbsolutePath();
        String cached = CONTAINER_CHOICE_CACHE.get(dirKey);
        if (cached != null) {
            for (File c : containers) {
                if (c.getName().equals(cached)) return c;
            }
            CONTAINER_CHOICE_CACHE.remove(dirKey);
        }

        if (containerChoiceOverrideForTests != null) {
            for (File c : containers) {
                if (c.getName().equals(containerChoiceOverrideForTests)) {
                    CONTAINER_CHOICE_CACHE.put(dirKey, c.getName());
                    return c;
                }
            }
            throw new IllegalArgumentException(
                    "Test container choice override '"
                    + containerChoiceOverrideForTests
                    + "' not found among: " + joinNames(containers));
        }

        if (isHeadless()) {
            throw new IllegalArgumentException(
                    "Multiple container files found in " + dir.getAbsolutePath()
                    + " — headless run cannot prompt. Candidates: "
                    + joinNames(containers));
        }

        File chosen = promptForContainer(dir, containers);
        if (chosen == null) {
            throw new IllegalArgumentException(
                    "No container selected for " + dir.getAbsolutePath());
        }
        CONTAINER_CHOICE_CACHE.put(dirKey, chosen.getName());
        return chosen;
    }

    private static File promptForContainer(File dir, List<File> containers) {
        String[] names = new String[containers.size()];
        for (int i = 0; i < containers.size(); i++) {
            names[i] = containers.get(i).getName();
        }
        PipelineDialog dialog = new PipelineDialog("Select container file");
        dialog.addMessage("Multiple container files were found in:<br>"
                + dir.getAbsolutePath()
                + "<br><br>Pick which one to use for this session.");
        JComboBox<String> combo = dialog.addChoice("Container", names, names[0]);
        if (!dialog.showDialog()) return null;
        Object selected = combo.getSelectedItem();
        String pick = selected == null ? names[0] : selected.toString();
        for (File c : containers) {
            if (c.getName().equals(pick)) return c;
        }
        return containers.get(0);
    }

    /** Test hook — clears the per-directory container selection cache. */
    static void clearContainerChoiceCacheForTests() {
        CONTAINER_CHOICE_CACHE.clear();
    }

    /** Test hook — forces selectContainer to pick a specific filename. */
    static void setContainerChoiceOverrideForTests(String name) {
        containerChoiceOverrideForTests = name;
    }

    /**
     * Warns once per session when a TIFF-folder source has no physical
     * calibration. This is informational only; users may continue in pixel
     * units or suppress future warnings for the project with a marker file.
     */
    public static void maybeWarnUncalibrated(String directory) {
        if (directory == null) return;

        File dir = new File(directory);
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
        if (hasMarker(layout, SUPPRESS_CALIBRATION_WARNING_MARKER)) return;
        if (!WARNED.add(dir.getAbsolutePath())) return;

        SourceMode mode;
        try {
            mode = detectMode(directory);
        } catch (Exception e) {
            return;
        }
        if (mode == SourceMode.CONTAINER) return;

        List<SeriesMeta> metas;
        try {
            metas = readAllMetadata(directory);
        } catch (Exception e) {
            return;
        }
        if (metas == null || metas.isEmpty()) return;

        SeriesMeta first = metas.get(0);
        if (first == null || first.isCalibrated()) return;

        if (isHeadless()) {
            IJ.log("WARNING: " + CALIBRATION_WARNING_MESSAGE);
            return;
        }

        PipelineDialog dialog = new PipelineDialog("Calibration missing");
        dialog.addMessage(CALIBRATION_WARNING_MESSAGE.replace("\n", "<br>"));
        ToggleSwitch dontShowAgain = dialog.addToggle(
                "Don't show again for this project", false);
        if (dialog.showDialog() && dontShowAgain.isSelected()) {
            writeCalibrationWarningSuppressMarker(layout);
        }
    }

    static void clearCalibrationWarningThrottleForTests() {
        WARNED.clear();
    }

    static void setCalibrationWarningHeadlessOverrideForTests(Boolean override) {
        headlessOverrideForTests = override;
    }

    /**
     * Returns container files in {@code dir}, sorted case-insensitively by
     * name. Junk filenames are filtered via
     * {@link JunkFileFilter#listCleanFiles(File)}.
     */
    public static List<File> listContainers(File dir) {
        if (dir == null || !dir.isDirectory()) return new ArrayList<File>();
        File[] candidates = JunkFileFilter.listCleanFiles(dir);
        List<File> out = new ArrayList<File>();
        for (File candidate : candidates) {
            if (matchesContainerExtension(candidate.getName())) {
                out.add(candidate);
            }
        }
        Collections.sort(out, CASE_INSENSITIVE_NAME);
        return out;
    }

    /**
     * Returns bare-TIFF files in {@code dir}, sorted case-insensitively by
     * name. {@code .ome.tif} / {@code .ome.tiff} are excluded — they belong
     * to the container list.
     */
    public static List<File> listTiffs(File dir) {
        if (dir == null || !dir.isDirectory()) return new ArrayList<File>();
        File[] candidates = JunkFileFilter.listCleanFiles(dir);
        List<File> out = new ArrayList<File>();
        for (File candidate : candidates) {
            String name = candidate.getName();
            if (matchesContainerExtension(name)) continue;
            if (matchesBareTiffExtension(name)) {
                out.add(candidate);
            }
        }
        Collections.sort(out, CASE_INSENSITIVE_NAME);
        return out;
    }

    private static boolean matchesContainerExtension(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String ext : CONTAINER_EXTENSIONS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    private static boolean matchesBareTiffExtension(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String ext : TIFF_EXTENSIONS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    private static boolean hasMarker(FlashProjectLayout layout, String fileName) {
        try {
            return ProjectStatusStore.hasMarker(layout.projectRoot(), fileName);
        } catch (java.io.IOException e) {
            return false;
        }
    }

    private static File requireDirectory(String directory) {
        if (directory == null || directory.isEmpty()) {
            throw new IllegalArgumentException("Directory path is null or empty");
        }
        File dir = new File(directory);
        if (dir.isFile()
                && ProjectFileIO.FILE_NAME.equalsIgnoreCase(dir.getName())
                && dir.getParentFile() != null) {
            return dir.getParentFile();
        }
        if (!dir.isDirectory()) {
            throw new IllegalArgumentException("Not a directory: " + directory);
        }
        return dir;
    }

    private static IllegalArgumentException mixedSourcesError(File dir,
                                                              List<File> containers,
                                                              List<File> looseTiffs,
                                                              List<File> subTiffs) {
        StringBuilder sb = new StringBuilder();
        sb.append("Mixed input sources in ").append(dir.getAbsolutePath())
          .append(" — expected exactly one container file OR a TIFF folder, not both.");
        if (!containers.isEmpty()) {
            sb.append("\nContainers: ").append(joinNames(containers));
        }
        if (!looseTiffs.isEmpty()) {
            sb.append("\nLoose TIFFs: ").append(joinNames(looseTiffs));
        }
        if (!subTiffs.isEmpty()) {
            sb.append("\ninput/ TIFFs: ").append(joinNames(subTiffs));
        }
        return new IllegalArgumentException(sb.toString());
    }

    private static IllegalArgumentException emptyProjectSourcesError(File dir) {
        return new IllegalArgumentException(
                "project.json at " + dir.getAbsolutePath()
                        + " has no included source items. "
                        + "Tick at least one source row in the project builder.");
    }

    private static String joinNames(List<File> files) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < files.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(files.get(i).getName());
        }
        return sb.toString();
    }

    private static boolean isHeadless() {
        if (headlessOverrideForTests != null) {
            return headlessOverrideForTests.booleanValue();
        }
        try {
            Method method = IJ.class.getMethod("isHeadless");
            Object value = method.invoke(null);
            if (value instanceof Boolean) return ((Boolean) value).booleanValue();
        } catch (Exception ignored) {
            // Older ImageJ versions do not expose IJ.isHeadless().
        }
        return java.awt.GraphicsEnvironment.isHeadless();
    }

    private static void writeCalibrationWarningSuppressMarker(FlashProjectLayout layout) {
        try {
            ProjectStatusStore.setMarker(layout.projectRoot(),
                    SUPPRESS_CALIBRATION_WARNING_MARKER, true);
        } catch (java.io.IOException e) {
            IJ.log("FLASH: failed to write calibration warning marker: "
                    + e.getMessage());
        }
    }
}
