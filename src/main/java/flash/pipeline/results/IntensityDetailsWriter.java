package flash.pipeline.results;

import flash.pipeline.analyses.wizard.IntensitySpatialConfig;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.io.FlashProjectLayout.AnalysisFolder;
import flash.pipeline.naming.ChannelFilenameCodec;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Writes macro-style per-channel Analysis Details for Intensity Analysis.
 * Output: FLASH/Image Analysis/Image Intensities/Analysis Details/&lt;channel&gt;.txt
 * Mirrors the tag structure used by ObjectAnalysisDetailsWriter:
 * {@code <Filter Macro>}, {@code <Analysis Macro>}, {@code <Threshold>}, {@code <In ROI>}.
 */
public final class IntensityDetailsWriter {

    private IntensityDetailsWriter() {}

    public static File analysisDetailsWriteDir(File projectDirectory) {
        if (projectDirectory == null) {
            throw new IllegalArgumentException("Project directory must not be null.");
        }
        File intensityRoot = FlashProjectLayout.forDirectory(projectDirectory.getAbsolutePath())
                .analysisWriteDir(AnalysisFolder.INTENSITY);
        return new File(intensityRoot, "Analysis Details");
    }

    /**
     * Writes per-channel details files into an "Analysis Details" folder.
     * <p>
     * Records the filter source and macro text that were applied during
     * measurement.
     *
     * @param analysisDetailsDir  target directory for .txt files
     * @param binDir              .bin config directory (unused, kept for API consistency)
     * @param channelName         channel name (e.g. "DAPI")
     * @param channelIndex1Based  1-based channel index (unused, kept for API consistency)
     * @param filterEnabled       whether filtering was applied
     * @param filterSourceLabel   display label for the filter source that ran
     * @param actualMacroText     macro text that ran for this channel
     * @param binarized           whether binarization was applied
     * @param thresholdValue      threshold value used for binarization
     * @param inRoi               ROI channel name, or null if no ROI analysis
     */
    public static void writePerChannel(
            File analysisDetailsDir,
            File binDir,
            String channelName,
            int channelIndex1Based,
            boolean filterEnabled,
            String filterSourceLabel,
            String actualMacroText,
            boolean binarized,
            String thresholdValue,
            String inRoi
    ) throws Exception {
        writePerChannel(analysisDetailsDir, binDir, channelName, channelIndex1Based,
                filterEnabled, filterSourceLabel, actualMacroText, binarized,
                thresholdValue, inRoi, null, null, null, null, null);
    }

    public static void writePerChannel(
            File analysisDetailsDir,
            File binDir,
            String channelName,
            int channelIndex1Based,
            boolean filterEnabled,
            String filterSourceLabel,
            String actualMacroText,
            boolean binarized,
            String thresholdValue,
            String inRoi,
            IntensitySpatialConfig spatialConfig,
            String zSliceSummary,
            String overlayPath,
            String dependencySummary,
            String partialFailureSummary
    ) throws Exception {
        flash.pipeline.io.IoUtils.mustMkdirs(analysisDetailsDir);

        File out = new File(analysisDetailsDir, ChannelFilenameCodec.toSafe(channelName) + ".txt");
        File tmp = File.createTempFile(out.getName(), ".tmp", analysisDetailsDir);

        String filterMacro = "";
        if (filterEnabled && actualMacroText != null) {
            filterMacro = actualMacroText;
            if (!filterMacro.endsWith("\n")) filterMacro += "\n";
        }

        boolean moved = false;
        try {
            try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
                w.write("// Fluorescence Intensity Analysis - " + channelName + "\n");
                w.write("// Filter source: " + (filterSourceLabel == null ? "" : filterSourceLabel) + "\n");

                // Filter Macro - matches ObjectAnalysisDetailsWriter tag name.
                w.write("\n");
                w.write("<Filter Macro>\n");
                w.write(filterMacro);
                w.write("</Filter Macro>\n");

                // Analysis Macro - documents the measurement steps.
                w.write("\n");
                w.write("<Analysis Macro>\n");
                w.write("// Base CSV columns: IntDen and %Area are measured on the filtered full ROI.\n");
                w.write("selectImage(" + channelName + "_filtered);\n");
                w.write("run(\"Set Measurements...\", \"integrated area_fraction redirect=None decimal=3\");\n");
                w.write("// Per-slice: run(\"Measure\");\n");
                if (binarized) {
                    w.write("\n");
                    w.write("// Binarized CSV columns: IntDen_binarized and %Area_binarized.\n");
                    w.write("selectImage(" + channelName + "_filtered);\n");
                    w.write("setThreshold(" + (thresholdValue != null ? thresholdValue : "0")
                            + ", 65535);\n");
                    w.write("run(\"Convert to Mask\", \"background=Light\");\n");
                    w.write("// Apply binary mask to raw signal: mask>0 keeps the raw pixel, mask==0 sets 0.\n");
                    w.write("run(\"Set Measurements...\", \"integrated area_fraction redirect=None decimal=3\");\n");
                    w.write("// Per-slice: run(\"Measure\");\n");
                }
                if (inRoi != null && !"None".equals(inRoi)) {
                    w.write("// ROI channel mask: " + inRoi + "\n");
                    w.write("// Apply ROI channel mask: mask>0 keeps the measurement pixel, mask==0 sets 0.\n");
                }
                w.write("\n");
                w.write("// IntDen_Unfiltered CSV column: raw signal measurement (no filter).\n");
                w.write("selectImage(" + channelName + "_raw);\n");
                w.write("run(\"Set Measurements...\", \"integrated redirect=None decimal=3\");\n");
                w.write("// Per-slice: run(\"Measure\");\n");
                w.write("</Analysis Macro>\n");

                if (binarized) {
                    w.write("\n");
                    w.write("<Threshold>\n");
                    w.write(thresholdValue == null ? "" : thresholdValue);
                    w.write("\n</Threshold>\n");
                }

                if (inRoi != null) {
                    w.write("\n");
                    w.write("<In ROI>\n");
                    w.write(inRoi);
                    w.write("\n</In ROI>\n");
                }

                writeSpatialDetails(w, spatialConfig, zSliceSummary, binarized, inRoi,
                        overlayPath, dependencySummary, partialFailureSummary);
            }

            moveIntoPlace(tmp.toPath(), out.toPath());
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(tmp.toPath());
            }
        }
    }

    private static void moveIntoPlace(Path tmp, Path out) throws Exception {
        try {
            Files.move(tmp, out, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeSpatialDetails(Writer w,
                                            IntensitySpatialConfig spatialConfig,
                                            String zSliceSummary,
                                            boolean binarized,
                                            String inRoi,
                                            String overlayPath,
                                            String dependencySummary,
                                            String partialFailureSummary) throws Exception {
        IntensitySpatialConfig safeConfig = spatialConfig == null
                ? IntensitySpatialConfig.disabled()
                : spatialConfig;
        w.write("\n");
        w.write("<Intensity Spatial Analysis>\n");
        w.write("Enabled: " + safeConfig.isEnabled() + "\n");
        if (safeConfig.isEnabled()) {
            w.write("Selected analyses: "
                    + IntensitySpatialConfig.joinAnalysisTokens(safeConfig.getEnabledAnalyses()) + "\n");
            w.write("2D spatial source: " + safeConfig.getSpatialSourceMode().token() + "\n");
            w.write("MIP output: " + safeConfig.isMipEnabled() + "\n");
            w.write("Native 3D output: " + safeConfig.isNative3dEnabled() + "\n");
            w.write("Overlays: " + safeConfig.isOverlaysEnabled() + "\n");
            w.write("Overlay path: " + emptyFallback(overlayPath, "Not written") + "\n");
        } else {
            w.write("Selected analyses: none\n");
            w.write("2D spatial source: full_stack\n");
            w.write("MIP output: false\n");
            w.write("Native 3D output: false\n");
            w.write("Overlays: false\n");
        }
        w.write("Z-slice mode: " + emptyFallback(zSliceSummary, "Full stack") + "\n");
        w.write("Binarized partner columns: " + binarized + "\n");
        w.write("Partner mask usage: " + emptyFallback(inRoi, "None") + "\n");
        w.write("Dependency gates: " + emptyFallback(dependencySummary,
                "Optional spatial dependencies are checked at run time; missing analyses write NaN and log warnings.") + "\n");
        w.write("Partial failures: " + emptyFallback(partialFailureSummary,
                "Per-image/channel/ROI analysis failures are logged and their metric columns are written as NaN.") + "\n");
        w.write("</Intensity Spatial Analysis>\n");
    }

    private static String emptyFallback(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
