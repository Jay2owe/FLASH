package flash.pipeline.io;

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
 * <p>Written by {@code ThreeDObjectAnalysis} from the first image's
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
        }

        /** Convenience: returns true if the unit looks like a real physical unit (not "pixel"). */
        public boolean isCalibrated() {
            return unit != null
                    && !"pixel".equalsIgnoreCase(unit)
                    && !"pixels".equalsIgnoreCase(unit);
        }

        public boolean hasStackDepth() {
            return !Double.isNaN(stackDepth) && stackDepth > 0;
        }

        @Override
        public String toString() {
            return "PixelCalibration[" + pixelWidth + " x " + pixelHeight
                    + " x " + pixelDepth + " " + unit
                    + (hasStackDepth() ? ", stackDepth=" + stackDepth : "")
                    + "]";
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
        File file = new File(objectsDir, FILENAME);
        try {
            PrintWriter pw = new PrintWriter(file, "UTF-8");
            try {
                pw.println("# Image calibration written by FLASH (Fluorescence Automated Spatial Histology)");
                pw.println("pixelWidth=" + pixelWidth);
                pw.println("pixelHeight=" + pixelHeight);
                pw.println("pixelDepth=" + pixelDepth);
                if (!Double.isNaN(stackDepth) && stackDepth > 0) {
                    pw.println("stackDepth=" + stackDepth);
                }
                pw.println("unit=" + (unit != null ? unit : "pixel"));
            } finally {
                pw.close();
            }
            IJ.log("  Calibration saved: " + pixelWidth + " x " + pixelHeight
                    + " x " + pixelDepth + " " + unit
                    + ((!Double.isNaN(stackDepth) && stackDepth > 0)
                    ? " (stack depth " + stackDepth + ")" : ""));
        } catch (IOException e) {
            IJ.log("  Warning: could not write calibration file: " + e.getMessage());
        }
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

        double pw = 1.0, ph = 1.0, pd = 1.0, sd = Double.NaN;
        String unit = "pixel";

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
            }
        } catch (Exception e) {
            IJ.log("  Warning: could not read calibration file: " + e.getMessage());
            return null;
        }

        return new PixelCalibration(pw, ph, pd, sd, unit);
    }

    /**
     * Reads calibration for a given experiment directory. Looks in the current
     * object output folder first, then legacy object output folders.
     *
     * @return the parsed calibration, or {@code null} if not found
     */
    public static PixelCalibration readFromDirectory(String directory) {
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
        for (File objectsDir : layout.objectDataReadDirs()) {
            PixelCalibration calibration = read(objectsDir);
            if (calibration != null) {
                return calibration;
            }
        }
        return null;
    }
}
