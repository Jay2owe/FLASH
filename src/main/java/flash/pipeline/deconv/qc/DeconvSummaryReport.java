package flash.pipeline.deconv.qc;

import flash.pipeline.deconv.DeconvolutionIO;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Incremental batch summary writer for deconvolution runs.
 */
public final class DeconvSummaryReport {

    private static final String HEADER =
            "image\tchannel\tengine\talgorithm\titerations\tregularization\tpsfModel\tsizeXYZ\telapsedMs\tpeakRamMB\tcacheHit\twarnings";

    private final File reportFile;
    private final Set<String> imageNames = new LinkedHashSet<String>();
    private int channelCount = 0;
    private int cacheHitCount = 0;
    private int warningCount = 0;
    private boolean initialized = false;
    private boolean finished = false;

    public DeconvSummaryReport(File rootDir) throws IOException {
        this(new File(DeconvolutionIO.deconvOutDir(rootDir), "deconv_summary.txt"), true);
    }

    DeconvSummaryReport(File reportFile, boolean initialize) throws IOException {
        this.reportFile = reportFile;
        if (initialize) {
            initializeFile();
        }
    }

    public File getReportFile() {
        return reportFile;
    }

    public synchronized void appendRow(Row row) throws IOException {
        if (row == null || finished) return;
        initializeFile();
        appendLine(row.toLine());
        imageNames.add(row.image);
        channelCount++;
        if (row.cacheHit) {
            cacheHitCount++;
        }
        warningCount += row.warningCount();
    }

    public synchronized void finish(long totalElapsedMs) throws IOException {
        if (finished) return;
        initializeFile();
        appendLine(String.format(
                Locale.ROOT,
                "# Batch totals: images=%d channels=%d cacheHits=%d warnings=%d totalTimeS=%.3f",
                imageNames.size(),
                channelCount,
                cacheHitCount,
                warningCount,
                totalElapsedMs / 1000.0
        ));
        finished = true;
    }

    private void initializeFile() throws IOException {
        if (initialized) return;
        ensureDirectory(reportFile.getParentFile());
        Files.write(reportFile.toPath(),
                (HEADER + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        initialized = true;
    }

    private void appendLine(String line) throws IOException {
        Files.write(reportFile.toPath(),
                (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    private static void ensureDirectory(File dir) throws IOException {
        if (dir == null) return;
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("Could not create directory: " + dir.getAbsolutePath());
        }
    }

    public static final class Row {
        public final String image;
        public final String channel;
        public final String engine;
        public final String algorithm;
        public final int iterations;
        public final double regularization;
        public final String psfModel;
        public final String sizeXYZ;
        public final long elapsedMs;
        public final double peakRamMB;
        public final boolean cacheHit;
        public final List<String> warnings;

        public Row(String image,
                   String channel,
                   String engine,
                   String algorithm,
                   int iterations,
                   double regularization,
                   String psfModel,
                   String sizeXYZ,
                   long elapsedMs,
                   double peakRamMB,
                   boolean cacheHit,
                   List<String> warnings) {
            this.image = safe(image);
            this.channel = safe(channel);
            this.engine = safe(engine);
            this.algorithm = safe(algorithm);
            this.iterations = iterations;
            this.regularization = regularization;
            this.psfModel = safe(psfModel);
            this.sizeXYZ = safe(sizeXYZ);
            this.elapsedMs = elapsedMs;
            this.peakRamMB = peakRamMB;
            this.cacheHit = cacheHit;
            this.warnings = warnings == null ? new ArrayList<String>() : new ArrayList<String>(warnings);
        }

        int warningCount() {
            int count = 0;
            for (String warning : warnings) {
                if (warning != null && !warning.trim().isEmpty()) {
                    count++;
                }
            }
            return count;
        }

        String toLine() {
            return safe(image)
                    + '\t' + safe(channel)
                    + '\t' + safe(engine)
                    + '\t' + safe(algorithm)
                    + '\t' + iterations
                    + '\t' + String.format(Locale.ROOT, "%.6f", regularization)
                    + '\t' + safe(psfModel)
                    + '\t' + safe(sizeXYZ)
                    + '\t' + elapsedMs
                    + '\t' + String.format(Locale.ROOT, "%.1f", peakRamMB)
                    + '\t' + cacheHit
                    + '\t' + joinWarnings(warnings);
        }

        private static String joinWarnings(List<String> warnings) {
            if (warnings == null || warnings.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (String warning : warnings) {
                String cleaned = safe(warning);
                if (cleaned.isEmpty()) continue;
                if (sb.length() > 0) sb.append(',');
                sb.append(cleaned);
            }
            return sb.toString();
        }
    }

    private static String safe(String value) {
        if (value == null) return "";
        return value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').trim();
    }
}
