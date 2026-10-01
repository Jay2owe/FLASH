package flash.pipeline.io;

import flash.pipeline.intensity.spatial.CalibrationUtil;
import ij.IJ;
import ij.ImagePlus;
import ij.measure.Calibration;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * Reads and writes image calibration data to a simple properties file
 * ({@code calibration.properties}) stored alongside object CSVs.
 *
 * <p>Written by {@code ThreeDObjectAnalysis} after checking shared
 * calibration metadata; consumed by downstream analyses that need
 * pixel-to-physical-unit conversion without re-opening the source images.
 */
public final class CalibrationIO {

    private static final String FILENAME = "calibration.properties";

    private CalibrationIO() { }

    // ── Data holder ──────────────────────────────────────────────────

    /** Immutable snapshot of the calibration values needed by downstream analyses. */
    public static final class PixelCalibration {
        public final double pixelWidth;
        public final double pixelHeight;
        public final double pixelDepth;
        public final double stackDepth;
        public final String unit;
        private final CalibrationUtil.CanonicalCalibration canonical;

        public PixelCalibration(double pixelWidth, double pixelHeight,
                                double pixelDepth, String unit) {
            this(pixelWidth, pixelHeight, pixelDepth, Double.NaN, unit);
        }

        public PixelCalibration(double pixelWidth, double pixelHeight,
                                double pixelDepth, double stackDepth, String unit) {
            this.pixelWidth = pixelWidth;
            this.pixelHeight = pixelHeight;
            this.pixelDepth = pixelDepth;
            this.stackDepth = stackDepth;
            this.unit = unit;
            this.canonical = CalibrationUtil.canonicalize(
                    pixelWidth, pixelHeight, pixelDepth, unit);
        }

        /**
         * Returns the canonical X/Y/Z calibration. The raw public fields remain
         * unchanged as source provenance for legacy consumers.
         */
        public CalibrationUtil.CanonicalCalibration canonical() {
            return canonical;
        }

        /** Returns true only when every axis has a valid physical scale. */
        public boolean isCalibrated() {
            return canonical.isFullyPhysical();
        }

        public boolean hasStackDepth() {
            return Double.isFinite(stackDepth) && stackDepth > 0;
        }

        @Override
        public String toString() {
            return "PixelCalibration[" + pixelWidth + " x " + pixelHeight
                    + " x " + pixelDepth + " " + unit
                    + (hasStackDepth() ? ", stackDepth=" + stackDepth : "")
                    + "]";
        }
    }

    /**
     * One invocation's shared calibration guard. Create a new tracker for each
     * analysis run, and register every image before measuring it. Calls may be
     * concurrent. Differing physical or pixel scales invalidate the shared file and fail
     * the run; varying slice counts remove only the shared stack-depth fallback.
     */
    public static final class SharedCalibrationTracker {
        private PixelCalibration first;
        private boolean variableDepth;
        private String invalidReason;
        private File outputDirectory;

        /**
         * Seed the guard before processing when previous measurement rows are
         * retained. A previous unknown stack depth remains unavailable. Fresh
         * output runs should omit this call and use a new tracker directly.
         */
        public synchronized void seedFromExisting(File objectsDir) {
            bindDirectory(objectsDir);
            if (first != null) throw new IllegalStateException("Existing calibration must be seeded before registering images.");
            PixelCalibration persisted;
            try {
                persisted = read(outputDirectory);
            } catch (IllegalStateException failure) {
                invalidReason = failure.getMessage();
                throw failure;
            }
            PixelCalibration normalized = normalize(persisted);
            if (normalized == null) {
                invalidate("Cannot extend existing measurements because their persisted calibration "
                        + "is missing, unreadable or invalid. Rebuild results from sources before changing shared calibration.");
            }
            first = normalized;
            variableDepth = !normalized.hasStackDepth();
        }

        /** Returns true only when shared metadata was first written or changed. */
        public synchronized boolean register(File objectsDir, ImagePlus image) {
            bindDirectory(objectsDir);
            Calibration cal = image == null ? null : image.getCalibration();
            PixelCalibration next = cal == null ? null : normalize(new PixelCalibration(
                    cal.pixelWidth, cal.pixelHeight, cal.pixelDepth,
                    cal.pixelDepth * image.getNSlices(), cal.getUnit()));
            if (next == null) {
                return invalidate("Shared calibration cannot be trusted because image "
                        + (image == null ? "(missing)" : image.getTitle())
                        + " has missing, invalid or unknown-unit pixel scales.");
            }
            if (!next.hasStackDepth()) {
                return invalidate("Shared calibration cannot be trusted because image "
                        + image.getTitle() + " has an invalid stack depth.");
            }
            if (first == null) {
                first = next;
                publish(next.stackDepth);
                return true;
            }
            if (!first.unit.equals(next.unit)
                    || !sameScale(first.pixelWidth, next.pixelWidth)
                    || !sameScale(first.pixelHeight, next.pixelHeight)
                    || !sameScale(first.pixelDepth, next.pixelDepth)) {
                return invalidate("Mixed image calibration: " + image.getTitle()
                        + " uses " + next.pixelWidth + " x " + next.pixelHeight + " x "
                        + next.pixelDepth + " " + next.unit + " per pixel, but this run already uses "
                        + first.pixelWidth + " x " + first.pixelHeight + " x "
                        + first.pixelDepth + " " + first.unit + ". Shared calibration was invalidated; "
                        + "analyse matching pixel scales in separate groups before downstream quantification.");
            }
            if (!variableDepth && !sameScale(first.stackDepth, next.stackDepth)) {
                variableDepth = true;
                publish(Double.NaN);
                IJ.log("  Different image stack depths: shared stackDepth removed; "
                        + "downstream volume conversion must use each series' own depth.");
                return true;
            }
            return false;
        }

        private void bindDirectory(File objectsDir) {
            if (objectsDir == null) throw new IllegalArgumentException("Calibration output directory is missing.");
            File resolved = objectsDir.getAbsoluteFile();
            if (outputDirectory != null && !outputDirectory.equals(resolved)) {
                throw new IllegalArgumentException("A shared calibration tracker belongs to one output directory.");
            }
            outputDirectory = resolved;
            if (invalidReason != null) throw new IllegalStateException(invalidReason);
        }

        private static PixelCalibration normalize(PixelCalibration raw) {
            if (raw == null) return null;
            CalibrationUtil.CanonicalCalibration canonical = raw.canonical();
            if (canonical.isFullyPhysical()) {
                double depth = CalibrationUtil.canonicalize(raw.stackDepth, raw.unit).microns();
                return new PixelCalibration(canonical.x().microns(), canonical.y().microns(),
                        canonical.z().microns(), depth, "um");
            }
            if (canonical.x().isPixelUnit() && canonical.y().isPixelUnit()
                    && canonical.z().isPixelUnit() && positive(raw.pixelWidth)
                    && positive(raw.pixelHeight) && positive(raw.pixelDepth)) {
                return new PixelCalibration(raw.pixelWidth, raw.pixelHeight, raw.pixelDepth,
                        positive(raw.stackDepth) ? raw.stackDepth : Double.NaN, "pixel");
            }
            return null;
        }

        private static boolean positive(double scale) {
            return Double.isFinite(scale) && scale > 0;
        }

        private void publish(double depth) {
            try {
                writeChecked(outputDirectory, first.pixelWidth, first.pixelHeight,
                        first.pixelDepth, depth, first.unit, variableDepth ? "variable_depth" : "consistent");
            } catch (IOException failure) {
                invalidReason = "Could not publish required shared calibration: " + failure.getMessage();
                invalidateAfterPublicationFailure(failure);
                throw new IllegalStateException(invalidReason, failure);
            }
        }

        private boolean invalidate(String reason) {
            invalidReason = reason;
            try {
                writeChecked(outputDirectory, Double.NaN, Double.NaN, Double.NaN,
                        Double.NaN, "um", "invalid");
            } catch (IOException failure) {
                invalidateAfterPublicationFailure(failure);
                throw new IllegalStateException(reason + " Could not publish invalid calibration marker.", failure);
            }
            throw new IllegalStateException(reason);
        }

        private void invalidateAfterPublicationFailure(IOException failure) {
            try {
                java.nio.file.Files.deleteIfExists(new File(outputDirectory, FILENAME).toPath());
            } catch (IOException deletionFailure) {
                failure.addSuppressed(deletionFailure);
            }
        }

        private static boolean sameScale(double first, double second) {
            return Math.abs(first - second) <= Math.max(1e-12,
                    1e-9 * Math.max(Math.abs(first), Math.abs(second)));
        }
    }

    // ── Write ────────────────────────────────────────────────────────

    /**
     * Writes calibration from an {@link ImagePlus} into {@code objectsDir/calibration.properties}.
     * Silently does nothing if the image or calibration is null.
     */
    public static void writeFromImage(File objectsDir, ImagePlus imp) {
        if (imp == null) return;
        Calibration cal = imp.getCalibration();
        if (cal == null) return;
        double stackDepth = cal.pixelDepth * Math.max(1, imp.getNSlices());
        write(objectsDir, cal.pixelWidth, cal.pixelHeight, cal.pixelDepth, stackDepth, cal.getUnit());
    }

    /**
     * Writes explicit calibration values into {@code objectsDir/calibration.properties}.
     */
    public static void write(File objectsDir, double pixelWidth, double pixelHeight,
                             double pixelDepth, String unit) {
        write(objectsDir, pixelWidth, pixelHeight, pixelDepth, Double.NaN, unit);
    }

    /**
     * Writes explicit calibration values plus a persisted full-stack depth into
     * {@code objectsDir/calibration.properties}.
     */
    public static void write(File objectsDir, double pixelWidth, double pixelHeight,
                             double pixelDepth, double stackDepth, String unit) {
        try {
            writeChecked(objectsDir, pixelWidth, pixelHeight, pixelDepth, stackDepth, unit, null);
            IJ.log("  Calibration saved: " + pixelWidth + " x " + pixelHeight
                    + " x " + pixelDepth + " " + unit
                    + ((!Double.isNaN(stackDepth) && stackDepth > 0)
                    ? " (stack depth " + stackDepth + ")" : ""));
        } catch (IOException e) {
            IJ.log("  Warning: could not write calibration file: " + e.getMessage());
        }
    }

    private static void writeChecked(File objectsDir, double pixelWidth, double pixelHeight,
                                     double pixelDepth, double stackDepth, String unit, String status)
            throws IOException {
        CsvSupport.writeAtomically(new File(objectsDir, FILENAME), new CsvSupport.WriterAction() {
            @Override public void write(PrintWriter pw) {
                pw.println("# Image calibration written by FLASH (Fluorescence Automated Spatial Histology)");
                pw.println("pixelWidth=" + pixelWidth);
                pw.println("pixelHeight=" + pixelHeight);
                pw.println("pixelDepth=" + pixelDepth);
                if (Double.isFinite(stackDepth) && stackDepth > 0) pw.println("stackDepth=" + stackDepth);
                pw.println("unit=" + (unit != null ? unit : "pixel"));
                if (status != null) pw.println("calibrationStatus=" + status);
            }
        });
    }

    // ── Read ─────────────────────────────────────────────────────────

    /**
     * Reads calibration from {@code objectsDir/calibration.properties}.
     *
     * @return the parsed calibration, or {@code null} if the file does not exist or is unreadable
     */
    public static PixelCalibration read(File objectsDir) {
        File file = new File(objectsDir, FILENAME);
        if (!file.exists()) return null;

        double pw = Double.NaN, ph = Double.NaN, pd = Double.NaN, sd = Double.NaN;
        String unit = "pixel";
        String calibrationStatus = "";

        try (BufferedReader br = java.nio.file.Files.newBufferedReader(
                file.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String key = line.substring(0, eq).trim();
                String val = line.substring(eq + 1).trim();
                if ("pixelWidth".equals(key)) pw = Double.parseDouble(val);
                else if ("pixelHeight".equals(key)) ph = Double.parseDouble(val);
                else if ("pixelDepth".equals(key)) pd = Double.parseDouble(val);
                else if ("stackDepth".equals(key)) sd = Double.parseDouble(val);
                else if ("unit".equals(key)) unit = val;
                else if ("calibrationStatus".equals(key)) calibrationStatus = val;
            }
        } catch (Exception e) {
            IJ.log("  Warning: could not read calibration file: " + e.getMessage());
            return null;
        }

        if ("invalid".equals(calibrationStatus)) {
            throw new IllegalStateException("Shared image calibration is invalid in " + file
                    + "; the generating run had mixed or invalid pixel scales. "
                    + "Rerun groups with consistent physical calibration before downstream quantification.");
        }

        return new PixelCalibration(pw, ph, pd, sd, unit);
    }

    /**
     * Reads calibration for a given experiment directory from the Results/Tables/Objects folder.
     *
     * @return the parsed calibration, or {@code null} if not found
     */
    public static PixelCalibration readFromDirectory(String directory) {
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
        return read(layout.tablesObjectsWriteDir());
    }
}
