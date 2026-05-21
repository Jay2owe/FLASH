package flash.pipeline.report;

import flash.pipeline.analyses.wizard.IntensitySpatialConfig;
import flash.pipeline.naming.ChannelFilenameCodec;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.io.IoUtils;

import ij.IJ;
import ij.ImagePlus;
import ij.process.ImageProcessor;
import ij.process.LUT;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Collects QC data from all analyses and produces an HTML report.
 * Thread-safe: all mutation methods are synchronized.
 */
public class QualityReport {

    static final String MAX_CHANNEL_QC_PROPERTY = "flash.qc.maxChannelImages";
    private static final int DEFAULT_MAX_CHANNEL_QC_RECORDS = 120;

    private boolean enabled = false;
    private String directory;
    private final long startTime = System.currentTimeMillis();

    // Global settings
    private String projectDir;
    private boolean headless;
    private boolean parallel;
    private int threadCount;
    private boolean verboseLogging;
    private String overwriteBehavior;

    // Per-analysis sections (ordered)
    private final List<AnalysisSection> sections = new ArrayList<AnalysisSection>();

    // 3D Object Analysis visual QC data
    private final Map<String, List<ChannelQC>> imageQcData = new LinkedHashMap<String, List<ChannelQC>>();
    private int channelQcRecordCount = 0;
    private int skippedChannelQcRecords = 0;
    private boolean channelQcLimitLogged = false;
    private final List<SpectralPreviewQC> spectralPreviewData = new ArrayList<SpectralPreviewQC>();

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setDirectory(String directory) {
        this.directory = directory;
        this.projectDir = directory;
    }

    public synchronized void setGlobalSettings(boolean headless, boolean parallel, int threadCount,
                                                boolean verboseLogging, String overwriteBehavior) {
        this.headless = headless;
        this.parallel = parallel;
        this.threadCount = threadCount;
        this.verboseLogging = verboseLogging;
        this.overwriteBehavior = overwriteBehavior;
    }

    // ── Analysis parameter sections ──

    public synchronized void addSection(String analysisName, Map<String, String> params) {
        if (!enabled) return;
        sections.add(new AnalysisSection(analysisName, params, System.currentTimeMillis()));
    }

    public synchronized void addSplitMergeParams(String[] channelNames, String[] channelColors,
                                                   String[] processMethodPerCh, double[] saturationsPerCh,
                                                   String[] customMinMaxPerCh, boolean createMerge,
                                                   String additionalMergeSpec, boolean subtractBackground,
                                                   int backgroundIndex, boolean[] subtractFromChannels,
                                                   String zSliceSummary) {
        if (!enabled) return;
        Map<String, String> params = new LinkedHashMap<String, String>();
        for (int i = 0; i < channelNames.length; i++) {
            StringBuilder sb = new StringBuilder();
            sb.append("Method=").append(processMethodPerCh[i]);
            sb.append(", Color=").append(channelColors[i]);
            if ("Automatic".equals(processMethodPerCh[i])) {
                sb.append(", Saturation=").append(saturationsPerCh[i]);
            }
            if ("Custom Min-Max Display Ranges".equals(processMethodPerCh[i]) && customMinMaxPerCh != null) {
                sb.append(", Range=").append(customMinMaxPerCh[i]);
            }
            params.put("Channel: " + channelNames[i], sb.toString());
        }
        params.put("Z-Slice Subset", zSliceSummary != null ? zSliceSummary : "Full stack");
        params.put("Create Merge", String.valueOf(createMerge));
        if (additionalMergeSpec != null && !additionalMergeSpec.trim().isEmpty()) {
            params.put("Additional Merges", additionalMergeSpec);
        }
        params.put("Background Subtraction", String.valueOf(subtractBackground));
        if (subtractBackground) {
            params.put("Background Channel", channelNames[backgroundIndex]);
            StringBuilder targets = new StringBuilder();
            for (int i = 0; i < channelNames.length; i++) {
                if (subtractFromChannels[i] && i != backgroundIndex) {
                    if (targets.length() > 0) targets.append(", ");
                    targets.append(channelNames[i]);
                }
            }
            params.put("Subtract From", targets.toString());
        }
        sections.add(new AnalysisSection("Split and Merge Image Channels", params, System.currentTimeMillis()));
        writeReport();
    }

    public synchronized void add3DObjectParams(String[] channelNames, String[] channelSummaries,
                                               boolean extractProcessLength, boolean runSpatial,
                                               Map<String, Double> markerThresholds,
                                               String zSliceSummary) {
        if (!enabled) return;
        Map<String, String> params = new LinkedHashMap<String, String>();
        for (int i = 0; i < channelNames.length; i++) {
            String summary = channelSummaries != null && i < channelSummaries.length
                    ? channelSummaries[i] : "";
            params.put("Channel: " + channelNames[i], summary);
        }
        params.put("Z-Slice Subset", zSliceSummary != null ? zSliceSummary : "Full stack");
        params.put("Extract Process Length", String.valueOf(extractProcessLength));
        params.put("Spatial Distance Analysis", String.valueOf(runSpatial));
        if (markerThresholds != null) {
            for (Map.Entry<String, Double> e : markerThresholds.entrySet()) {
                params.put("Coloc Threshold: " + e.getKey(), e.getValue() + "%");
            }
        }
        sections.add(new AnalysisSection("3D Object Analysis", params, System.currentTimeMillis()));
    }

    public synchronized void addIntensityParams(String[] channelNames, boolean[] binarization,
                                                  String[] thresholds, boolean roiAnalysis,
                                                  String roiChannelChoice, String filterDescription,
                                                  String zSliceSummary) {
        addIntensityParams(channelNames, binarization, thresholds, roiAnalysis,
                roiChannelChoice, filterDescription, zSliceSummary, null, null);
    }

    public synchronized void addIntensityParams(String[] channelNames, boolean[] binarization,
                                                  String[] thresholds, boolean roiAnalysis,
                                                  String roiChannelChoice, String filterDescription,
                                                  String zSliceSummary,
                                                  IntensitySpatialConfig spatialConfig,
                                                  String dependencyWarnings) {
        if (!enabled) return;
        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("Filter", filterDescription != null ? filterDescription : "Basic background and noise removal");
        params.put("Z-Slice Subset", zSliceSummary != null ? zSliceSummary : "Full stack");
        params.put("CSV Schema", "IntDen=filtered full ROI, %Area=filtered full ROI area fraction, "
                + "IntDen_Unfiltered=raw full ROI; binarized channels also write IntDen_binarized and %Area_binarized");
        params.put("ROI Analysis", String.valueOf(roiAnalysis));
        if (roiAnalysis && roiChannelChoice != null && !"None".equals(roiChannelChoice)) {
            params.put("ROI Channel Mask", roiChannelChoice);
        }
        for (int i = 0; i < channelNames.length; i++) {
            String val = binarization[i] ? "Binarized (threshold=" + thresholds[i] + ")" : "No binarization";
            params.put("Channel: " + channelNames[i], val);
        }
        IntensitySpatialConfig safeSpatial = spatialConfig == null
                ? IntensitySpatialConfig.disabled()
                : spatialConfig;
        params.put("Intensity Spatial", String.valueOf(safeSpatial.isEnabled()));
        if (safeSpatial.isEnabled()) {
            params.put("Spatial Families",
                    IntensitySpatialConfig.joinAnalysisTokens(safeSpatial.getEnabledAnalyses()));
            params.put("Spatial 2D Source", safeSpatial.getSpatialSourceMode().token());
            params.put("Spatial MIP", String.valueOf(safeSpatial.isMipEnabled()));
            params.put("Spatial Native 3D", String.valueOf(safeSpatial.isNative3dEnabled()));
            params.put("Spatial Overlays", String.valueOf(safeSpatial.isOverlaysEnabled()));
            params.put("Spatial Dependency Warnings",
                    dependencyWarnings == null || dependencyWarnings.trim().isEmpty()
                            ? "None recorded at setup; run-time skips are logged and written as NaN."
                            : dependencyWarnings.trim());
        }
        sections.add(new AnalysisSection("Fluorescence Intensity Analysis", params, System.currentTimeMillis()));
        writeReport();
    }

    public synchronized void addGenericAnalysis(String analysisName, long durationMs) {
        if (!enabled) return;
        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("Status", "Completed");
        params.put("Duration", formatDuration(durationMs));
        sections.add(new AnalysisSection(analysisName, params, System.currentTimeMillis()));
        writeReport();
    }

    public synchronized void addSpectralDecontaminationSection(Map<String, String> params,
                                                               List<SpectralPreviewQC> previews) {
        if (!enabled) return;
        spectralPreviewData.clear();
        if (previews != null) {
            spectralPreviewData.addAll(previews);
        }
        sections.add(new AnalysisSection("Spectral Decontamination", params, System.currentTimeMillis()));
        writeReport();
    }

    // ── Visual QC for 3D Object Analysis ──

    /**
     * Capture QC images for one channel of one image.
     * Takes the original channel (unfiltered) and the binary mask (filtered).
     * Both should be Z-stacks; we take the middle slice.
     *
     * @param imageName   display name of the image
     * @param channelName channel name
     * @param original    unfiltered channel image (Z-stack)
     * @param mask        binary mask image (Z-stack)
     * @param lutColor    LUT color name from .bin config (e.g. "Red", "Green")
     */
    public synchronized void addChannelQC(String imageName, String channelName,
                                           ImagePlus original, ImagePlus mask, String lutColor) {
        if (!enabled) return;
        if (!reserveChannelQcRecord(imageName, channelName)) return;
        if (original == null) {
            addChannelQcRecord(imageName, new ChannelQC(channelName, lutColor, null, null, null));
            return;
        }

        try {
            // Take the middle Z-slice; use the smaller stack if dimensions differ
            int origSlices = original.getNSlices();
            int maskSlices = mask == null ? origSlices : mask.getNSlices();
            int midSlice = Math.max(1, Math.min(origSlices, maskSlices) / 2);

            // Get original slice as BufferedImage with LUT color
            ImageProcessor origIp = original.getStack().getProcessor(Math.min(midSlice, origSlices)).duplicate();
            BufferedImage origColored = applyLutColor(origIp, lutColor);

            BufferedImage maskBi = null;
            BufferedImage overlay = null;
            if (mask != null) {
                // Get mask slice as BufferedImage (binary -> white)
                ImageProcessor maskIp = mask.getStack().getProcessor(Math.min(midSlice, maskSlices)).duplicate();
                maskBi = maskIp.getBufferedImage();

                // Create overlay: original + semi-transparent white mask
                overlay = new BufferedImage(origColored.getWidth(), origColored.getHeight(), BufferedImage.TYPE_INT_ARGB);
                Graphics2D g2d = overlay.createGraphics();
                g2d.drawImage(origColored, 0, 0, null);
                // Create white mask version
                BufferedImage whiteMask = new BufferedImage(maskBi.getWidth(), maskBi.getHeight(), BufferedImage.TYPE_INT_ARGB);
                Graphics2D mg = whiteMask.createGraphics();
                for (int y = 0; y < maskBi.getHeight(); y++) {
                    for (int x = 0; x < maskBi.getWidth(); x++) {
                        int val = maskBi.getRGB(x, y) & 0xFF;
                        if (val > 0) {
                            whiteMask.setRGB(x, y, 0xFFFFFFFF); // opaque white
                        } else {
                            whiteMask.setRGB(x, y, 0x00000000); // transparent
                        }
                    }
                }
                mg.dispose();
                g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.4f));
                g2d.drawImage(whiteMask, 0, 0, null);
                g2d.dispose();

                // Save overlay TIF to disk
                File overlayDir = new File(reportDir(), "overlays");
                IoUtils.mustMkdirs(overlayDir);
                String safeName = (imageName == null ? "image" : imageName)
                        .replaceAll("[^a-zA-Z0-9_\\-]", "_");
                File overlayFile = new File(overlayDir, safeName + "_" + ChannelFilenameCodec.toSafe(channelName) + "_overlay.tif");
                ImagePlus overlayImp = new ImagePlus("overlay", new ij.process.ColorProcessor(overlay));
                IJ.saveAsTiff(overlayImp, overlayFile.getAbsolutePath());
                overlayImp.flush();
            }

            // Encode as base64 JPEGs for HTML
            String origB64 = toBase64Jpeg(origColored, 0.7f);
            String maskB64 = maskBi == null ? null : toBase64Jpeg(maskBi, 0.7f);
            String overlayB64 = overlay == null ? null : toBase64Jpeg(overlay, 0.7f);

            ChannelQC qc = new ChannelQC(channelName, lutColor, origB64, maskB64, overlayB64);
            addChannelQcRecord(imageName, qc);

        } catch (Exception e) {
            IJ.log("  QC Report: failed to capture " + channelName + " for " + imageName + ": " + e.getMessage());
            // Fallback: still add the channel with whatever we managed to capture
            try {
                int fallbackSlice = Math.max(1, original.getNSlices() / 2);
                ImageProcessor fbIp = original.getStack().getProcessor(fallbackSlice).duplicate();
                String origB64Only = toBase64Jpeg(applyLutColor(fbIp, lutColor), 0.7f);
                ChannelQC fallbackQc = new ChannelQC(channelName, lutColor, origB64Only, null, null);
                addChannelQcRecord(imageName, fallbackQc);
            } catch (Exception ignored) {
                // nothing more we can do
            }
        }
    }

    private boolean reserveChannelQcRecord(String imageName, String channelName) {
        int max = maxChannelQcRecords();
        if (channelQcRecordCount >= max) {
            skippedChannelQcRecords++;
            if (!channelQcLimitLogged) {
                IJ.log("  QC Report: skipped additional segmentation thumbnails after "
                        + max + " image/channel entries. Set "
                        + MAX_CHANNEL_QC_PROPERTY + " to change the cap.");
                channelQcLimitLogged = true;
            }
            return false;
        }
        channelQcRecordCount++;
        return true;
    }

    private static int maxChannelQcRecords() {
        int configured = Integer.getInteger(MAX_CHANNEL_QC_PROPERTY,
                DEFAULT_MAX_CHANNEL_QC_RECORDS).intValue();
        return Math.max(0, configured);
    }

    private void addChannelQcRecord(String imageName, ChannelQC qc) {
        List<ChannelQC> channels = imageQcData.get(imageName);
        if (channels == null) {
            channels = new ArrayList<ChannelQC>();
            imageQcData.put(imageName, channels);
        }
        channels.add(qc);
    }

    /** Call after all 3D Object Analysis images are processed to write the report. */
    public synchronized void write3DObjectQC() {
        if (!enabled) return;
        writeReport();
    }

    // ── HTML generation ──

    private void writeReport() {
        if (directory == null) return;
        try {
            File reportDir = reportDir();
            IoUtils.mustMkdirs(reportDir);
            File reportFile = new File(reportDir, "QC_Report.html");
            HtmlReportWriter.write(reportFile, this);
        } catch (Exception e) {
            IJ.log("QC Report: failed to write HTML: " + e.getMessage());
        }
    }

    // ── Accessors for HtmlReportWriter ──

    public String getProjectDir() { return projectDir; }
    public long getStartTime() { return startTime; }
    public boolean isHeadless() { return headless; }
    public boolean isParallel() { return parallel; }
    public int getThreadCount() { return threadCount; }
    public boolean isVerboseLogging() { return verboseLogging; }
    public String getOverwriteBehavior() { return overwriteBehavior; }
    public List<AnalysisSection> getSections() { return sections; }
    public Map<String, List<ChannelQC>> getImageQcData() {
        List<Map.Entry<String, List<ChannelQC>>> entries =
                new ArrayList<Map.Entry<String, List<ChannelQC>>>(imageQcData.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<String, List<ChannelQC>>>() {
            @Override
            public int compare(Map.Entry<String, List<ChannelQC>> a,
                               Map.Entry<String, List<ChannelQC>> b) {
                return String.CASE_INSENSITIVE_ORDER.compare(safeOrderKey(a.getKey()), safeOrderKey(b.getKey()));
            }
        });
        LinkedHashMap<String, List<ChannelQC>> ordered =
                new LinkedHashMap<String, List<ChannelQC>>();
        for (Map.Entry<String, List<ChannelQC>> entry : entries) {
            List<ChannelQC> channels = new ArrayList<ChannelQC>(entry.getValue());
            Collections.sort(channels, new Comparator<ChannelQC>() {
                @Override
                public int compare(ChannelQC a, ChannelQC b) {
                    return String.CASE_INSENSITIVE_ORDER.compare(safeOrderKey(a.channelName), safeOrderKey(b.channelName));
                }
            });
            ordered.put(entry.getKey(), channels);
        }
        return ordered;
    }

    private static String safeOrderKey(String value) {
        return value == null ? "" : value;
    }
    public int getSkippedChannelQcRecords() { return skippedChannelQcRecords; }
    public List<SpectralPreviewQC> getSpectralPreviewData() { return spectralPreviewData; }

    // ── Helper classes ──

    public static class AnalysisSection {
        public final String name;
        public final Map<String, String> params;
        public final long timestamp;

        AnalysisSection(String name, Map<String, String> params, long timestamp) {
            this.name = name;
            this.params = params;
            this.timestamp = timestamp;
        }
    }

    public static class ChannelQC {
        public final String channelName;
        public final String lutColor;
        public final String originalB64;
        public final String maskB64;
        public final String overlayB64;

        ChannelQC(String channelName, String lutColor, String originalB64, String maskB64, String overlayB64) {
            this.channelName = channelName;
            this.lutColor = lutColor;
            this.originalB64 = originalB64;
            this.maskB64 = maskB64;
            this.overlayB64 = overlayB64;
        }
    }

    public static class SpectralPreviewQC {
        public final String conditionName;
        public final String conditionRole;
        public final String selectionRole;
        public final String imageLabel;
        public final BufferedImage rawTargetImage;
        public final BufferedImage correctedTargetImage;
        public final BufferedImage overlayImage;
        public final List<String> metricLines;
        public final List<String> coefficientLines;
        public final List<String> warningLines;

        public SpectralPreviewQC(String conditionName,
                                 String conditionRole,
                                 String selectionRole,
                                 String imageLabel,
                                 BufferedImage rawTargetImage,
                                 BufferedImage correctedTargetImage,
                                 BufferedImage overlayImage,
                                 List<String> metricLines,
                                 List<String> coefficientLines,
                                 List<String> warningLines) {
            this.conditionName = conditionName == null ? "" : conditionName.trim();
            this.conditionRole = conditionRole == null ? "" : conditionRole.trim();
            this.selectionRole = selectionRole == null ? "" : selectionRole.trim();
            this.imageLabel = imageLabel == null ? "" : imageLabel.trim();
            this.rawTargetImage = rawTargetImage;
            this.correctedTargetImage = correctedTargetImage;
            this.overlayImage = overlayImage;
            this.metricLines = metricLines == null
                    ? new ArrayList<String>()
                    : new ArrayList<String>(metricLines);
            this.coefficientLines = coefficientLines == null
                    ? new ArrayList<String>()
                    : new ArrayList<String>(coefficientLines);
            this.warningLines = warningLines == null
                    ? new ArrayList<String>()
                    : new ArrayList<String>(warningLines);
        }
    }

    // ── Image utilities ──

    /** Apply a named LUT color to a grayscale ImageProcessor and return a colored BufferedImage. */
    private static BufferedImage applyLutColor(ImageProcessor ip, String lutColor) {
        int w = ip.getWidth();
        int h = ip.getHeight();
        BufferedImage colored = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);

        // Parse LUT color to RGB multipliers
        float rMul = 1f, gMul = 1f, bMul = 1f;
        if (lutColor != null) {
            String lc = lutColor.toLowerCase(Locale.ROOT).trim();
            if ("red".equals(lc))          { rMul = 1f; gMul = 0f; bMul = 0f; }
            else if ("green".equals(lc))    { rMul = 0f; gMul = 1f; bMul = 0f; }
            else if ("blue".equals(lc))     { rMul = 0f; gMul = 0f; bMul = 1f; }
            else if ("cyan".equals(lc))     { rMul = 0f; gMul = 1f; bMul = 1f; }
            else if ("magenta".equals(lc))  { rMul = 1f; gMul = 0f; bMul = 1f; }
            else if ("yellow".equals(lc))   { rMul = 1f; gMul = 1f; bMul = 0f; }
            // "grays" or unknown -> all 1.0
        }

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int val = ip.get(x + y * w);
                // Clamp to 0-255
                val = Math.max(0, Math.min(255, val));
                int r = Math.round(val * rMul);
                int g = Math.round(val * gMul);
                int b = Math.round(val * bMul);
                colored.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return colored;
    }

    /** Encode a BufferedImage as a base64 JPEG string. */
    static String toBase64Jpeg(BufferedImage img, float quality) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
            if (!writers.hasNext()) {
                // Fallback: PNG
                ImageIO.write(img, "png", baos);
                return java.util.Base64.getEncoder().encodeToString(baos.toByteArray());
            }
            ImageWriter writer = writers.next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);

            // JPEG doesn't support alpha — convert to RGB if needed
            BufferedImage rgb;
            if (img.getType() == BufferedImage.TYPE_INT_ARGB || img.getType() == BufferedImage.TYPE_4BYTE_ABGR) {
                rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = rgb.createGraphics();
                g.setColor(Color.BLACK);
                g.fillRect(0, 0, img.getWidth(), img.getHeight());
                g.drawImage(img, 0, 0, null);
                g.dispose();
            } else {
                rgb = img;
            }

            ImageOutputStream ios = null;
            try {
                ios = ImageIO.createImageOutputStream(baos);
                writer.setOutput(ios);
                writer.write(null, new IIOImage(rgb, null, null), param);
            } finally {
                writer.dispose();
                if (ios != null) {
                    ios.close();
                }
            }
            return java.util.Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (IOException e) {
            return "";
        }
    }

    private static String formatDuration(long ms) {
        long secs = ms / 1000;
        long mins = secs / 60;
        secs %= 60;
        if (mins > 0) return mins + "m " + secs + "s";
        return secs + "s";
    }

    private File reportDir() {
        return FlashProjectLayout.forDirectory(directory).qualityReportWriteDir();
    }
}
