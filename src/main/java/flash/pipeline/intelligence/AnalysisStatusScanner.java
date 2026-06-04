package flash.pipeline.intelligence;

import flash.pipeline.FLASH_Pipeline;
import flash.pipeline.bin.ChannelConfig;
import flash.pipeline.bin.ChannelConfigIO;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.io.ImageSourceDispatcher;
import flash.pipeline.io.OrientationManifestIO;
import flash.pipeline.io.ProjectStatusStore;
import flash.pipeline.roi.RoiIO;
import flash.pipeline.zslice.ZSliceRange;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Headless-safe filesystem scan for the main analysis-selection status badges.
 */
public class AnalysisStatusScanner {
    public static final String STATUS_DIR = FlashProjectLayout.SETTINGS_DIR + File.separator
            + FlashProjectLayout.STATUS_FILENAME;
    public static final String CREATE_BIN_ID = "createBin";
    public static final String AGGREGATION_ID = "aggregation";
    // Display min/max is presentation-owned by Split/Merge, so it must not stale
    // the setup or aggregation status badges when presentation ranges are saved.
    private static final String[] STATUS_HASH_CHANNEL_PROPERTIES = {
            ChannelConfig.P_NAME,
            ChannelConfig.P_COLOR,
            ChannelConfig.P_MARKER,
            ChannelConfig.P_THRESHOLD,
            ChannelConfig.P_SIZE,
            ChannelConfig.P_INTENSITY,
            ChannelConfig.P_SEGMENTATION,
            ChannelConfig.P_FILTER
    };

    private final Map<Integer, String> tooltips = new HashMap<Integer, String>();

    public Map<Integer, AnalysisStatus> scan(File directory) {
        Map<Integer, AnalysisStatus> out = new HashMap<Integer, AnalysisStatus>();
        tooltips.clear();
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory.getAbsolutePath());
        File channelConfig = new File(layout.configurationWriteDir(), ChannelConfigIO.FILE_NAME);
        String currentBinHash = statusRelevantBinHash(channelConfig);
        AnalysisStatus setupFallback = setupConfigurationStatus(layout.configurationWriteDir());

        put(out, FLASH_Pipeline.IDX_CREATE_BIN,
                sidecarStatus(directory, CREATE_BIN_ID, currentBinHash, setupFallback),
                "Set Up Configuration");
        boolean roiOutputs = hasRoiOutputs(layout);
        boolean orientationManifest = OrientationManifestIO.getExistingFile(directory.getAbsolutePath()) != null;
        put(out, FLASH_Pipeline.IDX_DRAW_ROIS,
                fallbackStatus(directory, roiOutputs),
                "Draw and Save ROIs");
        tooltips.put(Integer.valueOf(FLASH_Pipeline.IDX_DRAW_ROIS),
                roiTooltip(roiOutputs, orientationManifest));
        put(out, FLASH_Pipeline.IDX_DECONVOLUTION,
                fallbackStatus(directory, hasAnyFile(layout.analysisImagesDeconvolutionDir())),
                "3D Deconvolution");
        put(out, FLASH_Pipeline.IDX_SPLIT_MERGE,
                fallbackStatus(directory, hasAnyFile(layout.presentationImagesDir())),
                "Split and Merge Image Channels");
        put(out, FLASH_Pipeline.IDX_3D_OBJECT,
                fallbackStatus(directory, hasCsv(
                        java.util.Collections.singletonList(layout.tablesObjectsWriteDir()))),
                "3D Object Analysis");
        put(out, FLASH_Pipeline.IDX_SPATIAL,
                fallbackStatus(directory, hasCsv(
                        java.util.Collections.singletonList(layout.tablesSpatialWriteDir()))
                        || hasCsv(
                        java.util.Collections.singletonList(layout.tablesMorphometryWriteDir()))),
                "Spatial Analysis");
        put(out, FLASH_Pipeline.IDX_LINE_DISTANCE,
                fallbackStatus(directory, hasFile(
                        java.util.Collections.singletonList(layout.tablesLineDistanceWriteDir()),
                        "Spatial_Distances.csv")),
                "Line Distance Analysis");
        put(out, FLASH_Pipeline.IDX_INTENSITY,
                fallbackStatus(directory, hasCsv(
                        java.util.Collections.singletonList(layout.tablesIntensityWriteDir()))),
                "Fluorescence Intensity Analysis");
        put(out, FLASH_Pipeline.IDX_AGGREGATION,
                sidecarStatus(directory, AGGREGATION_ID, currentBinHash,
                        fallbackStatus(directory,
                                hasFile(layout.projectSummaryWriteFile(
                                        FlashProjectLayout.MASTER_OBJECTS_FILENAME))
                                        || hasFile(layout.projectSummaryWriteFile(
                                                FlashProjectLayout.MASTER_INTENSITIES_FILENAME)))),
                "Master Aggregation");
        put(out, FLASH_Pipeline.IDX_STATISTICS,
                fallbackStatus(directory,
                        hasFile(layout.projectSummaryWriteFile(FlashProjectLayout.STATISTICS_FILENAME))),
                "Statistical Analysis");
        put(out, FLASH_Pipeline.IDX_EXCEL_EXPORT,
                fallbackStatus(directory, hasFile(layout.summaryWorkbookWriteFile())),
                "Excel Summary Export");
        put(out, FLASH_Pipeline.IDX_SPECTRAL_DECONTAMINATION,
                fallbackStatus(directory, hasCsv(
                        java.util.Collections.singletonList(layout.tablesSpectralWriteDir()))),
                "Spectral Decontamination");

        return out;
    }

    public String tooltipFor(int analysisIndex) {
        String tooltip = tooltips.get(Integer.valueOf(analysisIndex));
        return tooltip == null ? "Not run on this folder" : tooltip;
    }

    private static boolean hasFile(File file) {
        return file != null && file.isFile();
    }

    public static void writeSidecar(File directory, String analysisId, int imageCount) throws IOException {
        if (directory == null || analysisId == null || analysisId.trim().isEmpty()) return;
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory.getAbsolutePath());

        Map<String, Object> json = new HashMap<String, Object>();
        json.put("binHash", statusRelevantBinHash(
                new File(layout.configurationWriteDir(), ChannelConfigIO.FILE_NAME)));
        json.put("ranAt", isoNow());
        json.put("imageCount", Integer.valueOf(Math.max(0, imageCount)));
        ProjectStatusStore.writeAnalysisStatus(directory, analysisId, json);
    }

    public static void writeSidecar(String directory, String analysisId, int imageCount) throws IOException {
        if (directory == null) return;
        writeSidecar(new File(directory), analysisId, imageCount);
    }

    /**
     * Appends a row to {@code Results/Run Records/run_history.csv}. Creates the file
     * with a header row on first call. Best-effort: IO errors are surfaced as IOExceptions.
     */
    public static void appendRunHistory(File directory,
                                        int analysisIndex,
                                        String analysisName,
                                        String timestamp) throws IOException {
        if (directory == null) return;
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory.getAbsolutePath());
        File runHistory = layout.runHistoryWriteFile();
        File parent = runHistory.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Could not create run-records folder: " + parent.getAbsolutePath());
        }
        boolean newFile = !runHistory.isFile();
        StringBuilder sb = new StringBuilder();
        if (newFile) {
            sb.append("timestamp,analysisIndex,analysisName\n");
        }
        sb.append(csvCell(timestamp == null ? isoNow() : timestamp));
        sb.append(',');
        sb.append(analysisIndex);
        sb.append(',');
        sb.append(csvCell(analysisName == null ? "" : analysisName));
        sb.append('\n');
        Files.write(runHistory.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8),
                newFile ? java.nio.file.StandardOpenOption.CREATE
                        : java.nio.file.StandardOpenOption.APPEND,
                java.nio.file.StandardOpenOption.WRITE);
    }

    private static String csvCell(String value) {
        if (value == null) return "";
        boolean needsQuote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!needsQuote) return value;
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    public static int estimateImageCount(String directory) {
        if (directory == null || directory.trim().isEmpty()) return 0;
        File root = new File(directory);
        if (!root.isDirectory()) return 0;
        int tiffs = ImageSourceDispatcher.listTiffs(root).size();
        File input = new File(root, "input");
        if (input.isDirectory()) {
            tiffs = Math.max(tiffs, ImageSourceDispatcher.listTiffs(input).size());
        }
        List<File> containers = ImageSourceDispatcher.listContainers(root);
        return Math.max(tiffs, containers.size());
    }

    private void put(Map<Integer, AnalysisStatus> out, int index, AnalysisStatus status, String label) {
        out.put(Integer.valueOf(index), status);
        if (!tooltips.containsKey(Integer.valueOf(index))) {
            tooltips.put(Integer.valueOf(index), defaultTooltip(status, label));
        }
    }

    private AnalysisStatus sidecarStatus(File directory, String analysisId, String currentBinHash,
                                         AnalysisStatus fallbackStatus) {
        Map<String, Object> status = Collections.emptyMap();
        try {
            status = ProjectStatusStore.readAnalysisStatus(directory, analysisId);
        } catch (IOException ignored) {
        }
        if (CREATE_BIN_ID.equals(analysisId)) {
            FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory.getAbsolutePath());
            AnalysisStatus setupStatus = setupConfigurationStatus(layout.configurationWriteDir());
            if (setupStatus != AnalysisStatus.DONE) {
                tooltips.put(Integer.valueOf(FLASH_Pipeline.IDX_CREATE_BIN),
                        setupConfigurationTooltip(layout.configurationWriteDir(), setupStatus));
                return setupStatus;
            }
        }
        if (!status.isEmpty()) {
            SidecarStatus data = readSidecar(status);
            String tooltip = sidecarTooltip(data, currentBinHash);
            int index = CREATE_BIN_ID.equals(analysisId)
                    ? FLASH_Pipeline.IDX_CREATE_BIN
                    : FLASH_Pipeline.IDX_AGGREGATION;
            tooltips.put(Integer.valueOf(index), tooltip);
            if (data.binHash != null && currentBinHash != null
                    && !data.binHash.equals(currentBinHash)) {
                return AnalysisStatus.STALE;
            }
            return AnalysisStatus.DONE;
        }
        return fallbackStatus == null ? AnalysisStatus.NOT_STARTED : fallbackStatus;
    }

    private AnalysisStatus fallbackStatus(File directory, boolean outputExists) {
        return outputExists ? AnalysisStatus.DONE : AnalysisStatus.NOT_STARTED;
    }

    private AnalysisStatus setupConfigurationStatus(File settingsDir) {
        ChannelConfigIO.ReadResult result = ChannelConfigIO.readResult(settingsDir);
        if (result.state == ChannelConfigIO.ReadState.OK) {
            return AnalysisStatus.DONE;
        }
        if (result.state == ChannelConfigIO.ReadState.ABSENT) {
            return AnalysisStatus.NOT_STARTED;
        }
        return AnalysisStatus.STALE;
    }

    private String setupConfigurationTooltip(File settingsDir, AnalysisStatus status) {
        ChannelConfigIO.ReadResult result = ChannelConfigIO.readResult(settingsDir);
        if (result.state == ChannelConfigIO.ReadState.INCOMPLETE) {
            return "Set Up Configuration is partially saved; finish setup to make it complete";
        }
        if (result.state == ChannelConfigIO.ReadState.CORRUPT) {
            return "Set Up Configuration file is damaged; run setup again";
        }
        if (result.state == ChannelConfigIO.ReadState.NEWER_VERSION) {
            return "Set Up Configuration was saved by a newer FLASH version";
        }
        if (status == AnalysisStatus.NOT_STARTED) {
            return "Not run on this folder";
        }
        return "Set Up Configuration needs attention";
    }

    private String roiTooltip(boolean roiOutputs, boolean orientationManifest) {
        if (roiOutputs && orientationManifest) {
            return "Draw and Save ROIs outputs and saved image orientation transforms found on this folder";
        }
        if (roiOutputs) {
            return "Draw and Save ROIs outputs found on this folder";
        }
        if (orientationManifest) {
            return "Saved image orientation transforms found; ROI outputs not found on this folder";
        }
        return "Not run on this folder";
    }

    private SidecarStatus readSidecar(Map<String, Object> map) {
        SidecarStatus out = new SidecarStatus();
        Object binHash = map.get("binHash");
        Object ranAt = map.get("ranAt");
        Object imageCount = map.get("imageCount");
        out.binHash = binHash == null ? "" : String.valueOf(binHash);
        out.ranAt = ranAt == null ? "" : String.valueOf(ranAt);
        if (imageCount instanceof Number) {
            out.imageCount = ((Number) imageCount).intValue();
        }
        return out;
    }

    private String sidecarTooltip(SidecarStatus data, String currentBinHash) {
        StringBuilder sb = new StringBuilder();
        sb.append("Last run ");
        sb.append(formatRanAt(data.ranAt));
        sb.append(", ");
        sb.append(Math.max(0, data.imageCount));
        sb.append(" images");
        if (data.binHash != null && currentBinHash != null && !data.binHash.equals(currentBinHash)) {
            sb.append(" (configuration has changed since)");
        }
        return sb.toString();
    }

    private String defaultTooltip(AnalysisStatus status, String label) {
        if (status == AnalysisStatus.DONE) {
            return label + " outputs found on this folder";
        }
        if (status == AnalysisStatus.STALE) {
            return label + " outputs found (configuration has changed since)";
        }
        return "Not run on this folder";
    }

    private static boolean hasFile(File directory, String relativePath) {
        return directory != null && new File(directory, relativePath).isFile();
    }

    private static boolean hasFile(List<File> dirs, String fileName) {
        if (dirs == null || fileName == null) return false;
        for (int i = 0; i < dirs.size(); i++) {
            File dir = dirs.get(i);
            if (dir != null && new File(dir, fileName).isFile()) return true;
        }
        return false;
    }

    private static boolean hasCsv(File dir) {
        if (dir == null || !dir.isDirectory()) return false;
        File[] files = dir.listFiles(new FilenameFilter() {
            @Override public boolean accept(File parent, String name) {
                return name != null && name.toLowerCase(Locale.US).endsWith(".csv");
            }
        });
        return files != null && files.length > 0;
    }

    private static boolean hasCsv(List<File> dirs) {
        if (dirs == null) return false;
        for (int i = 0; i < dirs.size(); i++) {
            if (hasCsv(dirs.get(i))) return true;
        }
        return false;
    }

    private static boolean hasAnyFile(File dir) {
        if (dir == null || !dir.isDirectory()) return false;
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (int i = 0; i < files.length; i++) {
            if (files[i].isFile()) return true;
            if (files[i].isDirectory()
                    && FlashProjectLayout.SETTINGS_DIR.equals(files[i].getName())) continue;
            if (files[i].isDirectory() && hasAnyFile(files[i])) return true;
        }
        return false;
    }

    private static boolean hasAnyFile(List<File> dirs) {
        if (dirs == null) return false;
        for (int i = 0; i < dirs.size(); i++) {
            if (hasAnyFile(dirs.get(i))) return true;
        }
        return false;
    }

    private static boolean hasRoiOutputs(FlashProjectLayout layout) {
        return !RoiIO.listRoiZipFiles(layout.projectRoot()).isEmpty()
                || !RoiIO.listRoiPropertiesCsvFiles(layout.projectRoot()).isEmpty();
    }

    private static String statusRelevantBinHash(File channelConfigFile) {
        if (channelConfigFile == null || !channelConfigFile.isFile()) return "";
        ChannelConfig cfg = ChannelConfigIO.read(channelConfigFile.getParentFile());
        if (cfg == null) return sha256(channelConfigFile);
        return sha256Text(canonicalStatusConfig(cfg));
    }

    private static String canonicalStatusConfig(ChannelConfig cfg) {
        StringBuilder sb = new StringBuilder();
        appendField(sb, "schemaVersion", Integer.valueOf(cfg.schemaVersion));
        appendField(sb, "zSliceMode", cfg.zSliceMode == null ? "" : cfg.zSliceMode.name());
        appendField(sb, "clickCaptureUsed", Boolean.valueOf(cfg.clickCaptureUsed));
        appendZSliceSelections(sb, cfg);
        if (cfg.channels != null) {
            for (int i = 0; i < cfg.channels.size(); i++) {
                ChannelConfig.Channel channel = cfg.channels.get(i);
                if (channel == null) continue;
                appendField(sb, "channel", Integer.valueOf(i));
                appendField(sb, "index", Integer.valueOf(channel.index));
                appendField(sb, "name", channel.name);
                appendField(sb, "color", channel.color);
                appendField(sb, "markerId", channel.markerId);
                appendField(sb, "markerShape", channel.markerShape);
                appendField(sb, "markerCrowdingSensitive",
                        Boolean.valueOf(channel.markerCrowdingSensitive));
                appendField(sb, "threshold", channel.threshold);
                appendField(sb, "size", channel.size);
                appendField(sb, "intensityThreshold", channel.intensityThreshold);
                appendField(sb, "segmentationMethod", channel.segmentationMethod);
                appendField(sb, "filterPreset", channel.filterPreset);
                appendStatusFields(sb, channel);
            }
        }
        return sb.toString();
    }

    private static void appendZSliceSelections(StringBuilder sb, ChannelConfig cfg) {
        if (cfg.zSliceSelections == null || cfg.zSliceSelections.isEmpty()) return;
        List<String> keys = new ArrayList<String>(cfg.zSliceSelections.keySet());
        Collections.sort(keys);
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            ZSliceRange range = cfg.zSliceSelections.get(key);
            appendField(sb, "zSliceSelection", key);
            appendField(sb, "zStart", range == null ? "" : Integer.valueOf(range.startSlice));
            appendField(sb, "zEnd", range == null ? "" : Integer.valueOf(range.endSlice));
        }
    }

    private static void appendStatusFields(StringBuilder sb, ChannelConfig.Channel channel) {
        for (int i = 0; i < STATUS_HASH_CHANNEL_PROPERTIES.length; i++) {
            String property = STATUS_HASH_CHANNEL_PROPERTIES[i];
            appendField(sb, "status." + property, statusOf(channel, property));
        }
    }

    private static ChannelConfig.PropertyStatus statusOf(ChannelConfig.Channel channel, String prop) {
        if (channel == null || channel.status == null) {
            return ChannelConfig.PropertyStatus.PENDING;
        }
        ChannelConfig.PropertyStatus value = channel.status.get(prop);
        return value == null ? ChannelConfig.PropertyStatus.PENDING : value;
    }

    private static void appendField(StringBuilder sb, String name, Object value) {
        sb.append(name).append('=').append(value == null ? "" : String.valueOf(value)).append('\n');
    }

    private static String sha256(File file) {
        if (file == null || !file.isFile()) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = Files.readAllBytes(file.toPath());
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (int i = 0; i < hash.length; i++) {
                String hex = Integer.toHexString(hash[i] & 0xff);
                if (hex.length() == 1) sb.append('0');
                sb.append(hex);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String sha256Text(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (int i = 0; i < hash.length; i++) {
                String hex = Integer.toHexString(hash[i] & 0xff);
                if (hex.length() == 1) sb.append('0');
                sb.append(hex);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String isoNow() {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return fmt.format(new Date());
    }

    private static String formatRanAt(String ranAt) {
        if (ranAt == null || ranAt.trim().isEmpty()) return "unknown time";
        int t = ranAt.indexOf('T');
        return t > 0 ? ranAt.substring(0, t) : ranAt;
    }

    private static final class SidecarStatus {
        String binHash = "";
        String ranAt = "";
        int imageCount = 0;
    }
}
