package flash.pipeline.results;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Writes analysis details for the Split and Merge Image Channels analysis.
 * Output: SplitAndMerge_Details.txt under the caller-provided run-records
 * directory (typically FLASH/Results/Run Records/analysis_details/Split and Merge/).
 *
 * Records per-channel display range settings, merge configuration,
 * background subtraction, orientation, and timing information.
 */
public final class SplitAndMergeDetailsWriter {

    private SplitAndMergeDetailsWriter() {}

    /**
     * Writes a SplitAndMerge_Details.txt file summarising the processing parameters.
     *
     * @param outputDirectory       the split/merge root output directory
     * @param channelNames          array of channel names
     * @param channelColors         array of channel colour names
     * @param processMethodPerCh    display range method per channel (None/Automatic/Manual/Custom)
     * @param saturationsPerCh      saturation percentage per channel (used when method is Automatic)
     * @param customMinMaxPerCh     custom min-max string per channel (used when method is Custom)
     * @param createMerge           whether a full merge was created
     * @param additionalMergeSpec   additional merge specification string (e.g. "1-2 3-4"), may be null
     * @param subtractBackground    whether background subtraction was enabled
     * @param backgroundIndex       index of the background channel (-1 if none)
     * @param subtractFromChannels  which channels had background subtracted
     * @param orientation           hemisphere string used for orientation (may be null)
     * @param imagesProcessed       total number of images processed
     * @param startTimeMillis       processing start time in millis (for runtime calculation)
     */
    public static File write(
            File outputDirectory,
            String[] channelNames,
            String[] channelColors,
            String[] processMethodPerCh,
            double[] saturationsPerCh,
            String[] customMinMaxPerCh,
            boolean createMerge,
            String additionalMergeSpec,
            boolean subtractBackground,
            int backgroundIndex,
            boolean[] subtractFromChannels,
            int imagesProcessed,
            long startTimeMillis
    ) throws Exception {

        File detailsDir = new File(outputDirectory, "Analysis Details");
        flash.pipeline.io.IoUtils.mustMkdirs(detailsDir);

        File detailsFile = new File(detailsDir, "SplitAndMerge_Details.txt");

        int nCh = channelNames != null ? channelNames.length : 0;

        try (Writer w = new OutputStreamWriter(new FileOutputStream(detailsFile), StandardCharsets.UTF_8)) {
            // Header
            w.write("Split and Merge Image Channels - Analysis Details\n");
            w.write("=================================================\n\n");

            // Timestamp
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            w.write("Processing Timestamp: " + timestamp + "\n");
            w.write("Images Processed: " + imagesProcessed + "\n\n");

            // Per-channel settings
            w.write("--- Per-Channel Settings ---\n\n");
            for (int c = 0; c < nCh; c++) {
                String name = channelNames[c];
                String color = (channelColors != null && c < channelColors.length)
                        ? channelColors[c] : "N/A";
                String method = (processMethodPerCh != null && c < processMethodPerCh.length)
                        ? processMethodPerCh[c] : "None";

                w.write("<" + name + ">\n");
                w.write("\tColor: " + color + "\n");
                w.write("\tDisplay Range Method: " + method + "\n");

                if ("Automatic".equals(method)) {
                    double sat = (saturationsPerCh != null && c < saturationsPerCh.length)
                            ? saturationsPerCh[c] : 0.35;
                    w.write("\tSaturation: " + sat + "\n");
                }

                if ("Custom Min-Max Display Ranges".equals(method)) {
                    String mm = (customMinMaxPerCh != null && c < customMinMaxPerCh.length)
                            ? customMinMaxPerCh[c] : "None";
                    w.write("\tMin-Max Display Range: " + mm + "\n");
                }

                w.write("</" + name + ">\n\n");
            }

            // Merge settings
            w.write("--- Merge Settings ---\n\n");
            w.write("Create Merge (all channels): " + (createMerge ? "Yes" : "No") + "\n");
            if (additionalMergeSpec != null && !additionalMergeSpec.trim().isEmpty()) {
                w.write("Additional Merges: " + additionalMergeSpec + "\n");
            } else {
                w.write("Additional Merges: None\n");
            }
            w.write("\n");

            // Background subtraction
            w.write("--- Background Subtraction ---\n\n");
            w.write("Enabled: " + (subtractBackground ? "Yes" : "No") + "\n");
            if (subtractBackground && backgroundIndex >= 0 && backgroundIndex < nCh) {
                w.write("Background Channel: " + channelNames[backgroundIndex]
                        + " (Channel " + (backgroundIndex + 1) + ")\n");
                StringBuilder subtractFrom = new StringBuilder();
                for (int c = 0; c < nCh; c++) {
                    if (subtractFromChannels != null && c < subtractFromChannels.length
                            && subtractFromChannels[c] && c != backgroundIndex) {
                        if (subtractFrom.length() > 0) subtractFrom.append(", ");
                        subtractFrom.append(channelNames[c]);
                    }
                }
                w.write("Subtracted From: " + (subtractFrom.length() > 0 ? subtractFrom.toString() : "None") + "\n");
            }
            w.write("\n");

            // Runtime
            long totalSec = (System.currentTimeMillis() - startTimeMillis) / 1000;
            String unit = "Seconds";
            long value = totalSec;
            if (String.valueOf(Math.round(value)).length() > 3) {
                unit = "Minutes";
                value = value / 60;
            }
            if (String.valueOf(Math.round(value)).length() > 3) {
                unit = "Hours";
                value = value / 60;
            }
            w.write("Runtime: " + value + " " + unit + "\n");
        }

        return detailsFile;
    }
}
