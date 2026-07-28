package flash.pipeline.feedback;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Writes a user-reviewed feedback report as one portable ZIP file. */
final class FeedbackBundleWriter {

    private FeedbackBundleWriter() {
    }

    static File writeDefault(Map<String, String> entries) throws IOException {
        File home = new File(System.getProperty("user.home", "."));
        File desktop = new File(home, "Desktop");
        File outputDir = desktop.isDirectory() ? desktop : home;
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        File output = new File(outputDir, "FLASH-feedback-" + stamp + ".zip");
        for (int suffix = 2; output.exists() && suffix < 1000; suffix++) {
            output = new File(outputDir, "FLASH-feedback-" + stamp + "-" + suffix + ".zip");
        }
        return write(entries, output);
    }

    static File write(Map<String, String> entries, File output) throws IOException {
        if (entries == null || entries.isEmpty()) {
            throw new IOException("Feedback bundle has no content.");
        }
        if (output == null) throw new IOException("Feedback bundle path is missing.");
        File parent = output.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create feedback output folder: " + parent);
        }
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(
                new FileOutputStream(output)))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                String name = entry.getKey() == null ? "diagnostic.txt" : entry.getKey();
                ZipEntry zipEntry = new ZipEntry(name);
                zip.putNextEntry(zipEntry);
                byte[] bytes = (entry.getValue() == null ? "" : entry.getValue())
                        .getBytes(StandardCharsets.UTF_8);
                zip.write(bytes);
                zip.closeEntry();
            }
        }
        return output;
    }
}
