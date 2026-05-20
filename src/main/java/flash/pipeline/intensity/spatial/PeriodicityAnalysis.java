package flash.pipeline.intensity.spatial;

import edu.emory.mathcs.jtransforms.fft.DoubleFFT_2D;
import flash.pipeline.analyses.wizard.IntensitySpatialConfig;
import flash.pipeline.runtime.DependencyId;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.process.ImageProcessor;

import java.awt.Rectangle;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * 2D FFT power-spectrum periodicity metrics for raw intensity images.
 *
 * Angle is reported as the stripe orientation in image coordinates, where
 * 0 degrees is horizontal and 90 degrees is vertical. The FFT peak vector is
 * normal to the stripe axis, so it is rotated into that convention.
 */
public final class PeriodicityAnalysis implements IntensitySpatialAnalysis {
    public static final String COLUMN_WAVELENGTH = "Intensity_PeriodicityWavelength_um";
    public static final String COLUMN_ANGLE = "Intensity_PeriodicityAngle_deg";
    public static final String COLUMN_STRIPINESS = "Intensity_PeriodicityStripiness";
    public static final String COLUMN_PEAK_POWER = "Intensity_PeriodicityPeakPower";

    private static final int MAX_SIDE = 128;

    @Override
    public IntensitySpatialConfig.AnalysisKey key() {
        return IntensitySpatialConfig.AnalysisKey.PERIODICITY;
    }

    @Override
    public AnalysisValidity validity() {
        return AnalysisValidity.RAW_ONLY;
    }

    @Override
    public EnumSet<IntensitySpatialOutputMode> outputModes() {
        return EnumSet.of(IntensitySpatialOutputMode.BASE, IntensitySpatialOutputMode.MIP);
    }

    @Override
    public Set<DependencyId> dependencyIds() {
        return Collections.singleton(DependencyId.JTRANSFORMS_RUNTIME);
    }

    @Override
    public List<String> columns(IntensitySpatialConfig config, boolean binarizedPartner) {
        return java.util.Arrays.asList(COLUMN_WAVELENGTH, COLUMN_ANGLE,
                COLUMN_STRIPINESS, COLUMN_PEAK_POWER);
    }

    @Override
    public int estimatedCost() {
        return 8;
    }

    @Override
    public IntensitySpatialResult measure(IntensitySpatialContext context) {
        Plane plane = Plane.from(context.image(), context.sliceIndex(), context.roi());
        LinkedHashMap<String, Double> values = new LinkedHashMap<String, Double>();
        if (plane.count < 8 || plane.width < 4 || plane.height < 4) {
            context.warn("periodicity has insufficient valid ROI pixels; returning NaN.");
            putNan(values);
            return new IntensitySpatialResult(values);
        }
        if (plane.variance <= 0.0 || Double.isNaN(plane.variance)) {
            values.put(COLUMN_WAVELENGTH, Double.valueOf(Double.NaN));
            values.put(COLUMN_ANGLE, Double.valueOf(Double.NaN));
            values.put(COLUMN_STRIPINESS, Double.valueOf(0.0));
            values.put(COLUMN_PEAK_POWER, Double.valueOf(0.0));
            return new IntensitySpatialResult(values);
        }

        SpectrumPeak peak = SpectrumPeak.from(plane);
        if (peak == null || peak.totalPower <= 0.0 || peak.frequencyMagnitude <= 0.0) {
            putNan(values);
            return new IntensitySpatialResult(values);
        }

        values.put(COLUMN_WAVELENGTH, Double.valueOf(1.0 / peak.frequencyMagnitude));
        values.put(COLUMN_ANGLE, Double.valueOf(normalizeHalfTurn(90.0 - peak.frequencyAngleDegrees)));
        values.put(COLUMN_STRIPINESS, Double.valueOf(peak.stripiness));
        values.put(COLUMN_PEAK_POWER, Double.valueOf(peak.peakPower / peak.totalPower));
        return new IntensitySpatialResult(values);
    }

    private static void putNan(LinkedHashMap<String, Double> values) {
        values.put(COLUMN_WAVELENGTH, Double.valueOf(Double.NaN));
        values.put(COLUMN_ANGLE, Double.valueOf(Double.NaN));
        values.put(COLUMN_STRIPINESS, Double.valueOf(Double.NaN));
        values.put(COLUMN_PEAK_POWER, Double.valueOf(Double.NaN));
    }

    private static double normalizeHalfTurn(double degrees) {
        double out = degrees % 180.0;
        if (out < 0.0) out += 180.0;
        return out;
    }

    private static double pixelSize(ImagePlus image, boolean xAxis) {
        return CalibrationUtil.pixelSizeUm(image,
                xAxis ? CalibrationUtil.Axis.X : CalibrationUtil.Axis.Y);
    }

    private static Rectangle clippedBounds(ImagePlus image, Roi roi) {
        int width = image == null ? 0 : image.getWidth();
        int height = image == null ? 0 : image.getHeight();
        Rectangle raw = roi == null ? new Rectangle(0, 0, width, height) : roi.getBounds();
        int x0 = Math.max(0, raw.x);
        int y0 = Math.max(0, raw.y);
        int x1 = Math.min(width, raw.x + raw.width);
        int y1 = Math.min(height, raw.y + raw.height);
        return new Rectangle(x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0));
    }

    private static final class SpectrumPeak {
        final double frequencyMagnitude;
        final double frequencyAngleDegrees;
        final double peakPower;
        final double totalPower;
        final double stripiness;

        private SpectrumPeak(double frequencyMagnitude,
                             double frequencyAngleDegrees,
                             double peakPower,
                             double totalPower,
                             double stripiness) {
            this.frequencyMagnitude = frequencyMagnitude;
            this.frequencyAngleDegrees = frequencyAngleDegrees;
            this.peakPower = peakPower;
            this.totalPower = totalPower;
            this.stripiness = stripiness;
        }

        static SpectrumPeak from(Plane plane) {
            int stride = plane.width * 2;
            double[] data = new double[plane.height * stride];
            double sumWindowedSquares = 0.0;
            for (int y = 0; y < plane.height; y++) {
                double wy = hann(y, plane.height);
                for (int x = 0; x < plane.width; x++) {
                    double wx = hann(x, plane.width);
                    int index = y * plane.width + x;
                    double centered = plane.valid[index] ? plane.values[index] - plane.mean : 0.0;
                    double windowed = centered * wx * wy;
                    data[y * stride + 2 * x] = windowed;
                    sumWindowedSquares += windowed * windowed;
                }
            }
            if (sumWindowedSquares <= 0.0) {
                return null;
            }

            DoubleFFT_2D fft = new DoubleFFT_2D(plane.height, plane.width);
            fft.complexForward(data);

            int spectrumBins = Math.max(1, plane.width * plane.height / 2);
            double[] powers = new double[spectrumBins];
            int powerCount = 0;
            double bestPower = -1.0;
            int bestKx = 0;
            int bestKy = 0;
            double totalPower = 0.0;

            for (int ky = 0; ky < plane.height; ky++) {
                int signedKy = signedFrequencyIndex(ky, plane.height);
                for (int kx = 0; kx < plane.width; kx++) {
                    int signedKx = signedFrequencyIndex(kx, plane.width);
                    if (signedKx == 0 && signedKy == 0) continue;
                    if (signedKy < 0 || (signedKy == 0 && signedKx < 0)) continue;

                    int offset = ky * stride + 2 * kx;
                    double re = data[offset];
                    double im = data[offset + 1];
                    double power = re * re + im * im;
                    if (Double.isNaN(power) || Double.isInfinite(power)) continue;
                    totalPower += power;
                    if (powerCount < powers.length) {
                        powers[powerCount++] = power;
                    }
                    if (power > bestPower) {
                        bestPower = power;
                        bestKx = signedKx;
                        bestKy = signedKy;
                    }
                }
            }

            if (bestPower <= 0.0 || totalPower <= 0.0) {
                return null;
            }
            double fx = bestKx / (plane.width * plane.pixelWidthUm);
            double fy = bestKy / (plane.height * plane.pixelHeightUm);
            double magnitude = Math.sqrt(fx * fx + fy * fy);
            double frequencyAngle = normalizeHalfTurn(Math.toDegrees(Math.atan2(fy, fx)));
            double medianPower = medianPositive(powers, powerCount);
            double stripiness = medianPower > 0.0 ? bestPower / medianPower : Double.NaN;
            return new SpectrumPeak(magnitude, frequencyAngle, bestPower, totalPower, stripiness);
        }

        private static int signedFrequencyIndex(int index, int size) {
            return index > size / 2 ? index - size : index;
        }

        private static double hann(int index, int size) {
            if (size <= 1) return 1.0;
            return 0.5 - 0.5 * Math.cos(2.0 * Math.PI * index / (size - 1.0));
        }

        private static double medianPositive(double[] values, int count) {
            if (values == null || count <= 0) return Double.NaN;
            double[] copy = new double[count];
            int n = 0;
            for (int i = 0; i < count; i++) {
                double value = values[i];
                if (value > 0.0 && !Double.isNaN(value) && !Double.isInfinite(value)) {
                    copy[n++] = value;
                }
            }
            if (n == 0) return Double.NaN;
            java.util.Arrays.sort(copy, 0, n);
            int mid = n / 2;
            return (n & 1) == 1 ? copy[mid] : (copy[mid - 1] + copy[mid]) / 2.0;
        }
    }

    private static final class Plane {
        final int width;
        final int height;
        final double[] values;
        final boolean[] valid;
        final int count;
        final double mean;
        final double variance;
        final double pixelWidthUm;
        final double pixelHeightUm;

        private Plane(int width,
                      int height,
                      double[] values,
                      boolean[] valid,
                      int count,
                      double mean,
                      double variance,
                      double pixelWidthUm,
                      double pixelHeightUm) {
            this.width = width;
            this.height = height;
            this.values = values;
            this.valid = valid;
            this.count = count;
            this.mean = mean;
            this.variance = variance;
            this.pixelWidthUm = pixelWidthUm;
            this.pixelHeightUm = pixelHeightUm;
        }

        static Plane from(ImagePlus image, int slice, Roi roi) {
            if (image == null || image.getStackSize() < slice) {
                return empty();
            }
            Rectangle bounds = clippedBounds(image, roi);
            if (bounds.width <= 0 || bounds.height <= 0) {
                return empty();
            }
            int stepX = Math.max(1, (bounds.width + MAX_SIDE - 1) / MAX_SIDE);
            int stepY = Math.max(1, (bounds.height + MAX_SIDE - 1) / MAX_SIDE);
            int width = Math.max(1, (bounds.width + stepX - 1) / stepX);
            int height = Math.max(1, (bounds.height + stepY - 1) / stepY);
            double[] values = new double[width * height];
            boolean[] valid = new boolean[values.length];
            ImageProcessor ip = image.getStack().getProcessor(slice);
            int count = 0;
            double mean = 0.0;
            double m2 = 0.0;

            for (int yy = 0; yy < height; yy++) {
                int y0 = bounds.y + yy * stepY;
                int y1 = Math.min(bounds.y + bounds.height, y0 + stepY);
                for (int xx = 0; xx < width; xx++) {
                    int x0 = bounds.x + xx * stepX;
                    int x1 = Math.min(bounds.x + bounds.width, x0 + stepX);
                    double sum = 0.0;
                    int n = 0;
                    for (int y = y0; y < y1; y++) {
                        for (int x = x0; x < x1; x++) {
                            if (roi != null && !roi.contains(x, y)) continue;
                            double value = ip.getf(x, y);
                            if (Double.isNaN(value) || Double.isInfinite(value)) continue;
                            sum += value;
                            n++;
                        }
                    }
                    if (n == 0) continue;
                    double blockMean = sum / n;
                    int index = yy * width + xx;
                    values[index] = blockMean;
                    valid[index] = true;
                    count++;
                    double delta = blockMean - mean;
                    mean += delta / count;
                    double delta2 = blockMean - mean;
                    m2 += delta * delta2;
                }
            }

            for (int i = 0; i < values.length; i++) {
                if (!valid[i]) {
                    values[i] = count == 0 ? 0.0 : mean;
                }
            }
            double variance = count < 2 ? 0.0 : m2 / count;
            return new Plane(width, height, values, valid, count, mean, variance,
                    pixelSize(image, true) * stepX,
                    pixelSize(image, false) * stepY);
        }

        private static Plane empty() {
            return new Plane(0, 0, new double[0], new boolean[0], 0,
                    Double.NaN, Double.NaN, 1.0, 1.0);
        }
    }
}
