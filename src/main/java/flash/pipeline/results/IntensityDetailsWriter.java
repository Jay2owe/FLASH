package flash.pipeline.results;

import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.io.FlashProjectLayout.AnalysisFolder;
import flash.pipeline.naming.ChannelFilenameCodec;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

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
        flash.pipeline.io.IoUtils.mustMkdirs(analysisDetailsDir);

        File out = new File(analysisDetailsDir, ChannelFilenameCodec.toSafe(channelName) + ".txt");

        String filterMacro = "";
        if (filterEnabled && actualMacroText != null) {
            filterMacro = actualMacroText;
            if (!filterMacro.endsWith("\n")) filterMacro += "\n";
        }

        try (Writer w = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8)) {
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
            w.write("// Filtered signal measurement\n");
            w.write("selectImage(" + channelName + "_filtered);\n");
            if (binarized) {
                w.write("setThreshold(" + (thresholdValue != null ? thresholdValue : "0")
                        + ", 65535);\n");
                w.write("run(\"Convert to Mask\", \"background=Light\");\n");
                w.write("// Apply binary mask to raw signal: mask>0 keeps the raw pixel, mask==0 sets 0.\n");
            }
            if (inRoi != null && !"None".equals(inRoi)) {
                w.write("// ROI channel mask: " + inRoi + "\n");
                w.write("// Apply ROI channel mask: mask>0 keeps the measurement pixel, mask==0 sets 0.\n");
            }
            w.write("run(\"Set Measurements...\", \"integrated area_fraction redirect=None decimal=3\");\n");
            w.write("// Per-slice: run(\"Measure\");\n");
            w.write("\n");
            w.write("// Raw signal measurement (no filter)\n");
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
        }
    }
}
