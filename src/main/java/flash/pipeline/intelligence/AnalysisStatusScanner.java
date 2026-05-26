package flash.pipeline.intelligence;

import flash.pipeline.FLASH_Pipeline;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.io.ImageSourceDispatcher;
import flash.pipeline.io.OrientationManifestIO;
import flash.pipeline.roi.RoiIO;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
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
    public static final String STATUS_DIR = FlashProjectLayout.STATUS_DIR + File.separator
            + FlashProjectLayout.SETTINGS_DIR + File.separator
            + FlashProjectLayout.ANALYSIS_STATUS_DIR;
    public static final String CREATE_BIN_ID = "createBin";
    public static final String AGGREGATION_ID = "aggregation";

    private static final String BIN_CONFIG = ".bin" + File.separator + "Channel_Data.txt";

    private final Map<Integer, String> tooltips = new HashMap<Integer, String>();

    public Map<Integer, AnalysisStatus> scan(File directory) {
        Map<Integer, AnalysisStatus> out = new HashMap<Integer, AnalysisStatus>();
        tooltips.clear();
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory.getAbsolutePath());
        String currentBinHash = sha256(firstExistingFile(layout.channelDataReadFiles()));

        put(out, FLASH_Pipeline.IDX_CREATE_BIN,
                sidecarStatus(directory, CREATE_BIN_ID, currentBinHash,
                        firstExistingFile(layout.channelDataReadFiles()) != null),
                "Set Up Configuration");
        boolean roiOutputs = hasRoiOutputs(layout);
        boolean orientationManifest = OrientationManifestIO.getExistingFile(directory.getAbsolutePath()) != null;
        put(out, FLASH_Pipeline.IDX_DRAW_ROIS,
                fallbackStatus(directory, roiOutputs),
                "Draw and Save ROIs");
        tooltips.put(Integer.valueOf(FLASH_Pipeline.IDX_DRAW_ROIS),
                roiTooltip(roiOutputs, orientationManifest));
        put(out, FLASH_Pipeline.IDX_DECONVOLUTION,
                fallbackStatus(directory, hasAnyFile(layout.analysisReadDirs(
                        FlashProjectLayout.AnalysisFolder.DECONVOLUTION))),
                "3D Deconvolution");
        put(out, FLASH_Pipeline.IDX_SPLIT_MERGE,
                fallbackStatus(directory, hasAnyFile(layout.presentationImagesDir())),
                "Split and Merge Image Channels");
        put(out, FLASH_Pipeline.IDX_3D_OBJECT,
                fallbackStatus(directory, hasCsv(
                        java.util.Collections.singletonList(layout.tablesObjectsWriteDir()))),
                "3D Object Analysis");
        put(out, FLASH_Pipeline.IDX_SPATIAL,
                fallbackStatus(directory, hasCsv(layout.spatialDataReadDirs())
                        || hasCsv(layout.spatialMorphometryReadDirs())),
                "Spatial Analysis");
        put(out, FLASH_Pipeline.IDX_LINE_DISTANCE,
                fallbackStatus(directory, hasFile(layout.lineDistanceReadDirs(),
                        "Spatial_Distances.csv")),
                "Line Distance Analysis");
        put(out, FLASH_Pipeline.IDX_INTENSITY,
                fallbackStatus(directory, hasCsv(
                        java.util.Collections.singletonList(layout.tablesIntensityWriteDir()))),
                "Fluorescence Intensity Analysis");
        put(out, FLASH_Pipeline.IDX_AGGREGATION,
                sidecarStatus(directory, AGGREGATION_ID, currentBinHash,
                        firstExistingFile(layout.aggregationReadFiles(
                                FlashProjectLayout.MASTER_OBJECTS_FILENAME,
                                FlashProjectLayout.LEGACY_MASTER_OBJECTS_FILENAME,
                                FlashProjectLayout.MASTER_INTENSITIES_FILENAME,
                                FlashProjectLayout.LEGACY_MASTER_INTENSITIES_FILENAME)) != null),
                "Master Aggregation");
        put(out, FLASH_Pipeline.IDX_STATISTICS,
                fallbackStatus(directory, firstExistingFile(layout.statisticsReadFiles(
                        FlashProjectLayout.STATISTICS_FILENAME,
                        FlashProjectLayout.LEGACY_STATISTICS_FILENAME)) != null),
                "Statistical Analysis");
        put(out, FLASH_Pipeline.IDX_EXCEL_EXPORT,
                fallbackStatus(directory, firstExistingFile(layout.excelReadFiles(
                        FlashProjectLayout.SUMMARY_WORKBOOK_FILENAME,
                        FlashProjectLayout.LEGACY_SUMMARY_WORKBOOK_FILENAME)) != null),
                "Excel Summary Export");
        put(out, FLASH_Pipeline.IDX_SPECTRAL_DECONTAMINATION,
                fallbackStatus(directory, hasCsv(layout.analysisReadDirs(
                        FlashProjectLayout.AnalysisFolder.SPECTRAL))),
                "Spectral Decontamination");

        return out;
    }

    public String tooltipFor(int analysisIndex) {
        String tooltip = tooltips.get(Integer.valueOf(analysisIndex));
        return tooltip == null ? "Not run on this folder" : tooltip;
    }

    public static void writeSidecar(File directory, String analysisId, int imageCount) throws IOException {
        if (directory == null || analysisId == null || analysisId.trim().isEmpty()) return;
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory.getAbsolutePath());
        File statusDir = layout.analysisStatusWriteDir();
        if (!statusDir.isDirectory() && !statusDir.mkdirs() && !statusDir.isDirectory()) {
            throw new IOException("Failed to create " + statusDir.getAbsolutePath());
        }

        Map<String, Object> json = new HashMap<String, Object>();
        json.put("binHash", sha256(firstExistingFile(layout.channelDataReadFiles())));
        json.put("ranAt", isoNow());
        json.put("imageCount", Integer.valueOf(Math.max(0, imageCount)));
        Files.write(new File(statusDir, analysisId + ".json").toPath(),
                MiniJson.write(json).getBytes(StandardCharsets.UTF_8));
    }

    public static void writeSidecar(String directory, String analysisId, int imageCount) throws IOException {
        if (directory == null) return;
        writeSidecar(new File(directory), analysisId, imageCount);
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

    private AnalysisStatus sidecarStatus(File directory, String analysisId, String currentBinHash, boolean fallbackDone) {
        File sidecar = firstExistingFile(statusSidecarReadFiles(directory, analysisId));
        if (sidecar != null && sidecar.isFile()) {
            SidecarStatus data = readSidecar(sidecar);
            if (data != null) {
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
        }
        return fallbackDone ? AnalysisStatus.DONE : AnalysisStatus.NOT_STARTED;
    }

    private AnalysisStatus fallbackStatus(File directory, boolean outputExists) {
        return outputExists ? AnalysisStatus.DONE : AnalysisStatus.NOT_STARTED;
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

    private SidecarStatus readSidecar(File sidecar) {
        try {
            Object parsed = MiniJson.parse(new String(Files.readAllBytes(sidecar.toPath()), StandardCharsets.UTF_8));
            if (!(parsed instanceof Map)) return null;
            Map<?, ?> map = (Map<?, ?>) parsed;
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
        } catch (IOException e) {
            return null;
        }
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

    private static List<File> statusSidecarReadFiles(File directory, String analysisId) {
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory.getAbsolutePath());
        List<File> out = new java.util.ArrayList<File>();
        for (File dir : layout.analysisStatusReadDirs()) {
            out.add(new File(dir, analysisId + ".json"));
        }
        return out;
    }

    private static File firstExistingFile(List<File> files) {
        if (files == null) return null;
        for (int i = 0; i < files.size(); i++) {
            File file = files.get(i);
            if (file != null && file.isFile()) return file;
        }
        return null;
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
