package flash.pipeline.intensity.spatial;

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
 * Mean-field Poisson shot-noise null model for raw intensity distributions.
 */
public final class NullModelAnalysis implements IntensitySpatialAnalysis {
    public static final String COLUMN_P = "Intensity_NullModelP";
    public static final String COLUMN_Z = "Intensity_NullModelZ";
    public static final String COLUMN_PASS = "Intensity_NullModelPass";

    @Override
    public IntensitySpatialConfig.AnalysisKey key() {
        return IntensitySpatialConfig.AnalysisKey.NULLMODEL;
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
        return Collections.emptySet();
    }

    @Override
    public List<String> columns(IntensitySpatialConfig config, boolean binarizedPartner) {
        return java.util.Arrays.asList(COLUMN_P, COLUMN_Z, COLUMN_PASS);
    }

    @Override
    public int estimatedCost() {
        return 1;
    }

    @Override
    public IntensitySpatialResult measure(IntensitySpatialContext context) {
        Samples samples = Samples.from(context.image(), context.sliceIndex(), context.roi());
        LinkedHashMap<String, Double> values = new LinkedHashMap<String, Double>();
        if (samples.count < 2 || samples.mean <= 0.0) {
            context.warn("null model has insufficient positive raw signal; returning NaN.");
            values.put(COLUMN_P, Double.valueOf(Double.NaN));
            values.put(COLUMN_Z, Double.valueOf(Double.NaN));
            values.put(COLUMN_PASS, Double.valueOf(Double.NaN));
            return new IntensitySpatialResult(values);
        }

        double sampleVariance = samples.sampleVariance();
        double df = samples.count - 1.0;
        double chi = df * sampleVariance / samples.mean;
        double z = (chi - df) / Math.sqrt(2.0 * df);
        double p = 2.0 * (1.0 - normalCdf(Math.abs(z)));
        p = Math.max(0.0, Math.min(1.0, p));

        values.put(COLUMN_P, Double.valueOf(p));
        values.put(COLUMN_Z, Double.valueOf(z));
        values.put(COLUMN_PASS, Double.valueOf(p >= 0.05 ? 1.0 : 0.0));
        return new IntensitySpatialResult(values);
    }

    private static double normalCdf(double z) {
        return 0.5 * (1.0 + erf(z / Math.sqrt(2.0)));
    }

    private static double erf(double x) {
        boolean negative = x < 0.0;
        x = Math.abs(x);
        double t = 1.0 / (1.0 + 0.3275911 * x);
        double t2 = t * t;
        double t3 = t2 * t;
        double t4 = t3 * t;
        double t5 = t4 * t;
        double poly = 0.254829592 * t
                - 0.284496736 * t2
                + 1.421413741 * t3
                - 1.453152027 * t4
                + 1.061405429 * t5;
        double result = 1.0 - poly * Math.exp(-x * x);
        return negative ? -result : result;
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

    private static final class Samples {
        final int count;
        final double mean;
        final double sumSquares;

        private Samples(int count, double mean, double sumSquares) {
            this.count = count;
            this.mean = mean;
            this.sumSquares = sumSquares;
        }

        double sampleVariance() {
            return count < 2 ? Double.NaN : sumSquares / (count - 1.0);
        }

        static Samples from(ImagePlus image, int slice, Roi roi) {
            if (image == null || image.getStackSize() < slice) {
                return new Samples(0, Double.NaN, Double.NaN);
            }
            ImageProcessor ip = image.getStack().getProcessor(slice);
            Rectangle bounds = clippedBounds(image, roi);
            int count = 0;
            double mean = 0.0;
            double m2 = 0.0;
            for (int y = bounds.y; y < bounds.y + bounds.height; y++) {
                for (int x = bounds.x; x < bounds.x + bounds.width; x++) {
                    if (roi != null && !roi.contains(x, y)) continue;
                    double value = ip.getf(x, y);
                    if (Double.isNaN(value) || Double.isInfinite(value)) continue;
                    count++;
                    double delta = value - mean;
                    mean += delta / count;
                    double delta2 = value - mean;
                    m2 += delta * delta2;
                }
            }
            return new Samples(count, mean, m2);
        }
    }
}
