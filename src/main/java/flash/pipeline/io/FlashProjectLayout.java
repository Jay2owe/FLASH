package flash.pipeline.io;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Central path policy for files written under a FLASH project directory.
 * Write helpers always point at {@code FLASH/}; read helpers list the new path
 * first, followed by legacy locations for old projects.
 */
public final class FlashProjectLayout {
    public static final String FLASH_DIR = "FLASH";
    public static final String CONFIGURATION_DIR = "00 - Configuration";
    public static final String PRESETS_DIR = "Presets";
    public static final String REPORTS_DIR = "Reports";
    public static final String CACHE_DIR = "Cache";
    public static final String STATUS_DIR = "Status";
    public static final String QUALITY_REPORT_DIR = "Quality Report";
    public static final String TIF_CACHE_DIR = "TIF";
    public static final String ANALYSIS_STATUS_DIR = "Analysis";
    public static final String AUDIT_DIR = "Audit";

    public static final String LEGACY_BIN_DIR = ".bin";
    public static final String LEGACY_STATUS_DIR = ".flash-status";
    public static final String LEGACY_QUALITY_REPORT_DIR = "Quality_Report";
    public static final String LEGACY_TIF_CACHE_DIR = ".tif_cache";
    public static final String LEGACY_CUSTOM_FILTER_PRESET_DIR = "Custom Filter Presets";
    public static final String CUSTOM_FILTER_PRESET_DIR = "Custom Filter Presets";
    public static final String CHANNEL_DATA_FILENAME = "Channel_Data.txt";

    private static final String DATA_ANALYSIS_DIR = "Data Analysis";
    private static final String IMAGE_ANALYSIS_DIR = "Image Analysis";
    private static final String IMAGES_DIR = "Images";
    private static final String IMAGEJ_EXPORTS_DIR = "ImageJ Exports";

    private final File projectRoot;

    public enum AnalysisFolder {
        ROIS("01 - Regions of Interest",
                "ROIs",
                DATA_ANALYSIS_DIR + File.separator + "Attributes"),
        DECONVOLUTION("02 - 3D Deconvolution",
                IMAGE_ANALYSIS_DIR + File.separator + "Deconvolved"),
        SPLIT_MERGE("03 - Split and Merge",
                IMAGES_DIR),
        INTENSITY("04 - Fluorescence Intensity",
                DATA_ANALYSIS_DIR + File.separator + "ROI Intensities"),
        OBJECTS("05 - 3D Object Analysis",
                DATA_ANALYSIS_DIR + File.separator + "Objects"),
        SPATIAL("06 - Spatial Analysis",
                DATA_ANALYSIS_DIR + File.separator + "Spatial",
                DATA_ANALYSIS_DIR + File.separator + "Morphometry"),
        LINE_DISTANCE("07 - Line Distance",
                DATA_ANALYSIS_DIR + File.separator + "Lines",
                DATA_ANALYSIS_DIR + File.separator + "Objects"),
        SPECTRAL("08 - Spectral Decontamination",
                DATA_ANALYSIS_DIR + File.separator + "Spectral Decontamination",
                IMAGE_ANALYSIS_DIR + File.separator + "Spectral Decontamination"),
        AGGREGATION("09 - Result Aggregation",
                IMAGEJ_EXPORTS_DIR),
        STATISTICS("10 - Statistical Analysis",
                IMAGEJ_EXPORTS_DIR),
        EXCEL("11 - Excel Summary Export",
                IMAGEJ_EXPORTS_DIR);

        private final String directoryName;
        private final String[] legacyRelativePaths;

        AnalysisFolder(String directoryName, String... legacyRelativePaths) {
            this.directoryName = directoryName;
            this.legacyRelativePaths = legacyRelativePaths;
        }

        public String directoryName() {
            return directoryName;
        }
    }

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

    public File configurationWriteDir() {
        return new File(flashRoot(), CONFIGURATION_DIR);
    }

    public File legacyBinDir() {
        return new File(projectRoot, LEGACY_BIN_DIR);
    }

    public List<File> configurationReadDirs() {
        return immutableList(configurationWriteDir(), legacyBinDir());
    }

    public File existingConfigurationDir() {
        return firstExistingDirectory(configurationReadDirs());
    }

    public File channelDataWriteFile() {
        return new File(configurationWriteDir(), CHANNEL_DATA_FILENAME);
    }

    public List<File> channelDataReadFiles() {
        return immutableList(channelDataWriteFile(), new File(legacyBinDir(), CHANNEL_DATA_FILENAME));
    }

    public File channelDataReadFile() {
        File existing = firstExistingFile(channelDataReadFiles());
        return existing == null ? channelDataWriteFile() : existing;
    }

    public File analysisWriteDir(AnalysisFolder folder) {
        requireFolder(folder);
        return new File(flashRoot(), folder.directoryName());
    }

    public List<File> analysisLegacyDirs(AnalysisFolder folder) {
        requireFolder(folder);
        List<File> out = new ArrayList<File>();
        for (int i = 0; i < folder.legacyRelativePaths.length; i++) {
            out.add(new File(projectRoot, folder.legacyRelativePaths[i]));
        }
        return Collections.unmodifiableList(out);
    }

    public List<File> analysisReadDirs(AnalysisFolder folder) {
        List<File> out = new ArrayList<File>();
        out.add(analysisWriteDir(folder));
        out.addAll(analysisLegacyDirs(folder));
        return Collections.unmodifiableList(out);
    }

    public File objectDataWriteDir() {
        return new File(analysisWriteDir(AnalysisFolder.OBJECTS), "Objects");
    }

    public File intensityDataWriteDir() {
        return analysisWriteDir(AnalysisFolder.INTENSITY);
    }

    public File intensityAnalysisDetailsWriteDir() {
        return new File(intensityDataWriteDir(), "Analysis Details");
    }

    public File objectAnalysisDetailsWriteDir() {
        return new File(objectDataWriteDir(), "Analysis Details");
    }

    public List<File> objectAnalysisDetailsReadDirs() {
        return immutableList(objectAnalysisDetailsWriteDir(),
                new File(projectRoot, DATA_ANALYSIS_DIR + File.separator + "Objects"
                        + File.separator + "Analysis Details"));
    }

    public File objectImageOutputsWriteDir() {
        return new File(analysisWriteDir(AnalysisFolder.OBJECTS), "Image Outputs");
    }

    public File spatialDataWriteDir() {
        return new File(analysisWriteDir(AnalysisFolder.SPATIAL), "Spatial");
    }

    public File spatialMorphometryWriteDir() {
        return new File(analysisWriteDir(AnalysisFolder.SPATIAL), "Morphometry");
    }

    public File spatialImageOutputsWriteDir() {
        return new File(analysisWriteDir(AnalysisFolder.SPATIAL), "Image Outputs");
    }

    public File lineDistanceWriteDir() {
        return analysisWriteDir(AnalysisFolder.LINE_DISTANCE);
    }

    public File lineSetWriteDir() {
        return new File(lineDistanceWriteDir(), "Line Sets");
    }

    public File aggregationWriteDir() {
        return analysisWriteDir(AnalysisFolder.AGGREGATION);
    }

    public List<File> aggregationReadDirs() {
        return analysisReadDirs(AnalysisFolder.AGGREGATION);
    }

    public File statisticsWriteDir() {
        return analysisWriteDir(AnalysisFolder.STATISTICS);
    }

    public List<File> statisticsReadDirs() {
        return analysisReadDirs(AnalysisFolder.STATISTICS);
    }

    public File conditionManifestWriteFile(String fileName) {
        return new File(aggregationWriteDir(), fileName);
    }

    public List<File> conditionManifestReadFiles(String fileName) {
        List<File> out = new ArrayList<File>();
        for (File dir : aggregationReadDirs()) {
            out.add(new File(dir, fileName));
        }
        return Collections.unmodifiableList(out);
    }

    public File statisticsWriteFile(String fileName) {
        return new File(statisticsWriteDir(), fileName);
    }

    public File excelWriteDir() {
        return analysisWriteDir(AnalysisFolder.EXCEL);
    }

    public List<File> excelReadDirs() {
        return analysisReadDirs(AnalysisFolder.EXCEL);
    }

    public File excelWriteFile(String fileName) {
        return new File(excelWriteDir(), fileName);
    }

    public List<File> intensityDataReadDirs() {
        return analysisReadDirs(AnalysisFolder.INTENSITY);
    }

    public List<File> intensityAnalysisDetailsReadDirs() {
        return immutableList(intensityAnalysisDetailsWriteDir(),
                new File(projectRoot, DATA_ANALYSIS_DIR + File.separator + "ROI Intensities"
                        + File.separator + "Analysis Details"));
    }

    public List<File> objectDataReadDirs() {
        return immutableList(objectDataWriteDir(),
                new File(projectRoot, DATA_ANALYSIS_DIR + File.separator + "Objects"));
    }

    public List<File> objectImageOutputReadDirs() {
        return immutableList(objectImageOutputsWriteDir(),
                new File(projectRoot, IMAGE_ANALYSIS_DIR));
    }

    public List<File> spatialDataReadDirs() {
        return immutableList(spatialDataWriteDir(),
                new File(projectRoot, DATA_ANALYSIS_DIR + File.separator + "Spatial"));
    }

    public List<File> spatialMorphometryReadDirs() {
        return immutableList(spatialMorphometryWriteDir(),
                new File(projectRoot, DATA_ANALYSIS_DIR + File.separator + "Morphometry"));
    }

    public List<File> spatialImageOutputReadDirs() {
        return immutableList(spatialImageOutputsWriteDir(),
                new File(projectRoot, IMAGE_ANALYSIS_DIR));
    }

    public List<File> lineDistanceReadDirs() {
        return immutableList(lineDistanceWriteDir(),
                new File(projectRoot, DATA_ANALYSIS_DIR + File.separator + "Objects"));
    }

    public List<File> lineSetReadDirs() {
        return immutableList(lineSetWriteDir(),
                new File(projectRoot, DATA_ANALYSIS_DIR + File.separator + "Lines"));
    }

    public File presetsRoot() {
        return new File(flashRoot(), PRESETS_DIR);
    }

    public File presetWriteDir(String categoryName) {
        if (categoryName == null || categoryName.trim().isEmpty()) {
            throw new IllegalArgumentException("Preset category must not be blank.");
        }
        return new File(presetsRoot(), categoryName);
    }

    public List<File> presetsReadDirs() {
        return immutableList(presetsRoot(), new File(legacyBinDir(), LEGACY_CUSTOM_FILTER_PRESET_DIR));
    }

    public File customFilterPresetWriteDir() {
        return presetWriteDir(CUSTOM_FILTER_PRESET_DIR);
    }

    public List<File> customFilterPresetReadDirs() {
        return immutableList(customFilterPresetWriteDir(),
                new File(legacyBinDir(), LEGACY_CUSTOM_FILTER_PRESET_DIR));
    }

    public File reportsRoot() {
        return new File(flashRoot(), REPORTS_DIR);
    }

    public List<File> reportsReadDirs() {
        return immutableList(reportsRoot(), new File(projectRoot, LEGACY_QUALITY_REPORT_DIR));
    }

    public File qualityReportWriteDir() {
        return new File(reportsRoot(), QUALITY_REPORT_DIR);
    }

    public List<File> qualityReportReadDirs() {
        return immutableList(qualityReportWriteDir(),
                new File(projectRoot, LEGACY_QUALITY_REPORT_DIR));
    }

    public File cacheRoot() {
        return new File(flashRoot(), CACHE_DIR);
    }

    public File tifCacheWriteDir() {
        return new File(cacheRoot(), TIF_CACHE_DIR);
    }

    public List<File> tifCacheReadDirs() {
        return immutableList(tifCacheWriteDir(),
                new File(projectRoot, LEGACY_TIF_CACHE_DIR));
    }

    public File statusRoot() {
        return new File(flashRoot(), STATUS_DIR);
    }

    public List<File> statusReadDirs() {
        return immutableList(statusRoot(), new File(projectRoot, LEGACY_STATUS_DIR));
    }

    public File analysisStatusWriteDir() {
        return new File(statusRoot(), ANALYSIS_STATUS_DIR);
    }

    public List<File> analysisStatusReadDirs() {
        return immutableList(analysisStatusWriteDir(),
                new File(projectRoot, LEGACY_STATUS_DIR));
    }

    public File auditRoot() {
        return new File(statusRoot(), AUDIT_DIR);
    }

    public File statusWriteFile(String fileName) {
        return new File(statusRoot(), fileName);
    }

    public List<File> statusReadFiles(String fileName) {
        return immutableList(statusWriteFile(fileName), new File(projectRoot, fileName));
    }

    private static void requireFolder(AnalysisFolder folder) {
        if (folder == null) {
            throw new IllegalArgumentException("Analysis folder must not be null.");
        }
    }

    private static File firstExistingDirectory(List<File> dirs) {
        for (int i = 0; i < dirs.size(); i++) {
            File dir = dirs.get(i);
            if (dir.isDirectory()) return dir;
        }
        return null;
    }

    private static File firstExistingFile(List<File> files) {
        for (int i = 0; i < files.size(); i++) {
            File file = files.get(i);
            if (file.isFile()) return file;
        }
        return null;
    }

    private static List<File> immutableList(File first, File second) {
        List<File> out = new ArrayList<File>();
        out.add(first);
        out.add(second);
        return Collections.unmodifiableList(out);
    }
}
