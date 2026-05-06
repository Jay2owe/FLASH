package flash.pipeline.results;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Java implementation of macro saveAnalysisDetails().
 * Writes the same tag-ish format to Details.txt.
 */
public final class AnalysisDetailsWriter {

    private AnalysisDetailsWriter() {}

    public static File write(
            File rootDirectory,
            String nameOfAnalysis,
            String[] channelNames,
            String[] filters,
            String[] thresholds,
            File[] roiFiles,
            String[] roiNames,
            long startTimeMillis
    ) throws Exception {

        File analysisDir = new File(rootDirectory, "Analysis Details");
        flash.pipeline.io.IoUtils.mustMkdirs(analysisDir);

        String date = new SimpleDateFormat("d-M-yyyy").format(new Date());
        File saveDir = new File(analysisDir, nameOfAnalysis + " (" + date + ")");
        flash.pipeline.io.IoUtils.mustMkdirs(saveDir);

        File details = new File(saveDir, "Details.txt");

        try (Writer w = new OutputStreamWriter(new FileOutputStream(details), StandardCharsets.UTF_8)) {
            // Macro: it only prints ROI names if roiFiles[0] != "N/A". In Java we treat null as N/A.
            if (roiFiles != null && roiFiles.length > 0 && roiFiles[0] != null) {
                if (roiNames != null && roiNames.length > 0) {
                    w.write("<ROINames> " + String.join(", ", roiNames) + " </ROINames>\n");
                }
            }

            for (int c = 0; channelNames != null && c < channelNames.length; c++) {
                w.write("<" + channelNames[c] + ">\n");
                if (filters != null && filters.length > 0 && !"N/A".equals(filters[0])) {
                    String f = c < filters.length ? filters[c] : "";
                    w.write("\t<Filter> " + f + " </Filter>\n");
                }
                String t = thresholds != null && c < thresholds.length ? thresholds[c] : "";
                w.write("\t<Threshold> " + t + " </Threshold>\n");
                w.write("</" + channelNames[c] + ">\n");
            }

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

        return details;
    }
}
