package flash.pipeline.io;

import java.io.File;
import java.util.Collections;
import java.util.List;

/**
 * Central path policy for files written under a FLASH project directory.
 * User-facing output helpers point at the single {@code FLASH/Results/}
 * tree. Configuration, cache, and status helpers use their canonical runtime
 * locations only.
 */
public final class FlashProjectLayout {
    public static final String FLASH_DIR = "FLASH";
    public static final String RESULTS_DIR = "Results";
    public static final String TABLES_DIR = "Tables";
    public static final String PROJECT_SUMMARY_DIR = "Project Summary";
    public static final String ROIS_DIR = "ROIs";
    public static final String OBJECTS_DIR = "Objects";
    public static final String INTENSITY_DIR = "Intensity";
    public static final String SPATIAL_DIR = "Spatial";
    public static final String MORPHOMETRY_DIR = "Morphometry";
    public static final String LINE_DISTANCE_DIR = "Line Distance";
    public static final String SPECTRAL_DECONTAMINATION_DIR = "Spectral Decontamination";
    public static final String PRESENTATION_IMAGES_DIR = "Presentation Images";
    public static final String IMAGES_DIR = "Images";
    public static final String ANNOTATED_IMAGES_DIR = "Annotated Images";
    public static final String TILES_DIR = "Tiles";
    public static final String OME_TIFF_DIR = "OME-TIFF";
    public static final String ANALYSIS_IMAGES_DIR = "Analysis Images";
    public static final String OBJECT_MASKS_LABELMAPS_DIR = "Masks and Label Maps";
    public static final String OBJECT_MASKED_IMAGES_DIR = "Masked Images";
    public static final String OBJECT_FILTERED_INPUTS_DIR = "Filtered Inputs";
    public static final String INTENSITY_OVERLAYS_DIR = "Intensity Overlays";
    public static final String SPATIAL_HEATMAPS_DIR = "Spatial Heatmaps";
    public static final String DECONVOLUTION_DIR = "Deconvolution";
    public static final String QC_DIR = "QC";
    public static final String QC_OVERLAYS_DIR = "overlays";
    public static final String RUN_RECORDS_DIR = "Run Records";
    public static final String SETTINGS_SNAPSHOTS_DIR = "settings_snapshots";
    public static final String REPLAY_COMMANDS_DIR = "replay_commands";
    public static final String ANALYSIS_DETAILS_DIR = "analysis_details";
    public static final String START_HERE_FILENAME = "START_HERE.html";
    public static final String RUN_HISTORY_FILENAME = "run_history.csv";
    public static final String QC_REPORT_FILENAME = "QC_Report.html";
    public static final String SETTINGS_DIR = ".settings";
    public static final String CONFIGURATION_DIR = "Config";
    public static final String PRESETS_DIR = "Presets";
    public static final String CACHE_DIR = "Cache";
    public static final String STATUS_DIR = "Status";
    public static final String TIF_CACHE_DIR = "TIF";
    public static final String ANALYSIS_STATUS_DIR = "Analysis";

    public static final String CUSTOM_FILTER_PRESET_DIR = "Custom Filter Presets";
    public static final String CONDITIONS_FILENAME = "Conditions.csv";
    public static final String MASTER_OBJECTS_FILENAME = "3D Objects.csv";
    public static final String MASTER_INTENSITIES_FILENAME = "Image Intensities.csv";
    public static final String STATISTICS_FILENAME = "Statistics.csv";
    public static final String SUMMARY_WORKBOOK_FILENAME = "Summary.xlsx";
    public static final String ORIENTATION_MANIFEST_FILENAME = "Image Orientation.csv";
    public static final String ORIENTATION_ALIASES_FILENAME = "Image Orientation Aliases.csv";

    private final File projectRoot;

    private FlashProjectLayout(File projectRoot) {
        this.projectRoot = projectRoot;
    }

    public static FlashProjectLayout forDirectory(String directory) {
        if (directory == null || directory.trim().isEmpty()) {
            throw new IllegalArgumentException("Project directory must not be blank.");
        }
        return new FlashProjectLayout(new File(directory));
    }

    public File projectRoot() {
        return projectRoot;
    }

    public File flashRoot() {
        return new File(projectRoot, FLASH_DIR);
    }

    public File resultsRoot() {
        return new File(flashRoot(), RESULTS_DIR);
    }

    public File tablesRoot() {
        return new File(resultsRoot(), TABLES_DIR);
    }

    public File tablesProjectSummaryWriteDir() {
        return new File(tablesRoot(), PROJECT_SUMMARY_DIR);
    }

    public File projectSummaryWriteFile(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            throw new IllegalArgumentException("Project summary filename must not be blank.");
        }
        return new File(tablesProjectSummaryWriteDir(), fileName);
    }

    public File tablesRoiWriteDir() {
        return new File(tablesRoot(), ROIS_DIR);
    }

    public File tablesObjectsWriteDir() {
        return new File(tablesRoot(), OBJECTS_DIR);
    }

    public File tablesIntensityWriteDir() {
        return new File(tablesRoot(), INTENSITY_DIR);
    }

    public File tablesSpatialWriteDir() {
        return new File(tablesRoot(), SPATIAL_DIR);
    }

    public File tablesMorphometryWriteDir() {
        return new File(tablesRoot(), MORPHOMETRY_DIR);
    }

    public File tablesLineDistanceWriteDir() {
        return new File(tablesRoot(), LINE_DISTANCE_DIR);
    }

    public File tablesSpectralWriteDir() {
        return new File(tablesRoot(), SPECTRAL_DECONTAMINATION_DIR);
    }

    public File presentationImagesRoot() {
        return new File(resultsRoot(), PRESENTATION_IMAGES_DIR);
    }

    public File presentationImagesDir() {
        return new File(presentationImagesRoot(), IMAGES_DIR);
    }

    public File presentationAnnotatedDir() {
        return new File(presentationImagesRoot(), ANNOTATED_IMAGES_DIR);
    }

    public File presentationTilesDir() {
        return new File(presentationImagesRoot(), TILES_DIR);
    }

    public File presentationOmeTiffDir() {
        return new File(presentationImagesRoot(), OME_TIFF_DIR);
    }

    public File analysisImagesRoot() {
        return new File(resultsRoot(), ANALYSIS_IMAGES_DIR);
    }

    public File analysisImagesRoiDir() {
        return new File(analysisImagesRoot(), ROIS_DIR);
    }

    public File analysisImagesObjectsRoot() {
        return new File(analysisImagesRoot(), OBJECTS_DIR);
    }

    public File analysisImagesObjectsMasksDir() {
        return new File(analysisImagesObjectsRoot(), OBJECT_MASKS_LABELMAPS_DIR);
    }

    public File analysisImagesObjectsMaskedDir() {
        return new File(analysisImagesObjectsRoot(), OBJECT_MASKED_IMAGES_DIR);
    }

    public File analysisImagesObjectsFilteredDir() {
        return new File(analysisImagesObjectsRoot(), OBJECT_FILTERED_INPUTS_DIR);
    }

    public File analysisImagesIntensityOverlaysDir() {
        return new File(analysisImagesRoot(), INTENSITY_OVERLAYS_DIR);
    }

    public File analysisImagesSpatialHeatmapsDir() {
        return new File(analysisImagesRoot(), SPATIAL_HEATMAPS_DIR);
    }

    public File analysisImagesSpectralDir() {
        return new File(analysisImagesRoot(), SPECTRAL_DECONTAMINATION_DIR);
    }

    public File analysisImagesDeconvolutionDir() {
        return new File(analysisImagesRoot(), DECONVOLUTION_DIR);
    }

    public File qcRoot() {
        return new File(resultsRoot(), QC_DIR);
    }

    public File qcReportWriteFile() {
        return new File(qcRoot(), QC_REPORT_FILENAME);
    }

    public File qcOverlaysWriteDir() {
        return new File(qcRoot(), QC_OVERLAYS_DIR);
    }

    public File runRecordsRoot() {
        return new File(resultsRoot(), RUN_RECORDS_DIR);
    }

    public File runHistoryWriteFile() {
        return new File(runRecordsRoot(), RUN_HISTORY_FILENAME);
    }

    public File settingsSnapshotsWriteDir() {
        return new File(runRecordsRoot(), SETTINGS_SNAPSHOTS_DIR);
    }

    public File replayCommandsWriteDir() {
        return new File(runRecordsRoot(), REPLAY_COMMANDS_DIR);
    }

    public File analysisDetailsWriteDir() {
        return new File(runRecordsRoot(), ANALYSIS_DETAILS_DIR);
    }

    public File summaryWorkbookWriteFile() {
        return new File(resultsRoot(), SUMMARY_WORKBOOK_FILENAME);
    }

    public File startHereWriteFile() {
        return new File(resultsRoot(), START_HERE_FILENAME);
    }

    public File settingsRoot() {
        return new File(flashRoot(), SETTINGS_DIR);
    }

    public static File settingsDir(File directory) {
        return new File(directory, SETTINGS_DIR);
    }

    public File visibleConfigurationDir() {
        return new File(flashRoot(), CONFIGURATION_DIR);
    }

    public File configurationWriteDir() {
        return settingsDir(visibleConfigurationDir());
    }

    public static File projectRootForConfigurationDir(File configDir) {
        if (configDir == null) return null;

        File parent = configDir.getParentFile();

        if (parent != null && CONFIGURATION_DIR.equals(parent.getName())) {
            File flashDir = parent.getParentFile();
            if (flashDir != null && FLASH_DIR.equals(flashDir.getName())
                    && flashDir.getParentFile() != null) {
                return flashDir.getParentFile();
            }
        }

        if (parent != null && FLASH_DIR.equals(parent.getName())
                && parent.getParentFile() != null) {
            return parent.getParentFile();
        }

        return parent;
    }

    public List<File> configurationReadDirs() {
        return Collections.singletonList(configurationWriteDir());
    }

    public File existingConfigurationDir() {
        File dir = configurationWriteDir();
        return dir.isDirectory() ? dir : null;
    }

    public File presetsRoot() {
        return new File(settingsRoot(), PRESETS_DIR);
    }

    public File presetWriteDir(String categoryName) {
        if (categoryName == null || categoryName.trim().isEmpty()) {
            throw new IllegalArgumentException("Preset category must not be blank.");
        }
        return new File(presetsRoot(), categoryName);
    }

    public List<File> presetReadDirs(String categoryName) {
        if (categoryName == null || categoryName.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.singletonList(presetWriteDir(categoryName));
    }

    public List<File> presetsReadDirs() {
        return Collections.singletonList(presetsRoot());
    }

    public File customFilterPresetWriteDir() {
        return presetWriteDir(CUSTOM_FILTER_PRESET_DIR);
    }

    public List<File> customFilterPresetReadDirs() {
        return Collections.singletonList(customFilterPresetWriteDir());
    }

    public File cacheRoot() {
        return new File(flashRoot(), CACHE_DIR);
    }

    public File tifCacheWriteDir() {
        return new File(cacheRoot(), TIF_CACHE_DIR);
    }

    public List<File> tifCacheReadDirs() {
        return Collections.singletonList(tifCacheWriteDir());
    }

    public File statusRoot() {
        return new File(flashRoot(), STATUS_DIR);
    }

    public File statusSettingsRoot() {
        return settingsDir(statusRoot());
    }

    public List<File> statusReadDirs() {
        return Collections.singletonList(statusSettingsRoot());
    }

    public File analysisStatusWriteDir() {
        return new File(statusSettingsRoot(), ANALYSIS_STATUS_DIR);
    }

    public List<File> analysisStatusReadDirs() {
        return Collections.singletonList(analysisStatusWriteDir());
    }

    public File statusWriteFile(String fileName) {
        return new File(statusSettingsRoot(), fileName);
    }

    public List<File> statusReadFiles(String fileName) {
        return Collections.singletonList(statusWriteFile(fileName));
    }

}
