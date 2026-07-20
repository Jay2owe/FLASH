package flash.pipeline.stardist;

import ij.ImagePlus;

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Disabled StarDist variation fast-path scaffold.
 * <p>
 * The intended optimisation is one StarDist inference per slice followed by
 * repeated StarDist2DNMS calls. It must stay disabled until GPU reachability and
 * TrackMate 3D linking parity are proven by integration tests.
 */
public final class StarDistVariationRunner {

    private StarDistVariationRunner() {
    }

    public static boolean isFastNmsParityVerified() {
        return false;
    }

    public static Map<ThresholdPair, ImagePlus> runVariations(
            ImagePlus input,
            String channelName,
            List<Double> probThreshes,
            List<Double> nmsThreshes,
            BooleanSupplier cancelCheck) {
        throw new UnsupportedOperationException(
                "StarDist fast NMS variations are disabled until the GPU reachability "
                        + "and TrackMate 3D linking parity tests pass.");
    }

    public static final class ThresholdPair {
        public final double probThresh;
        public final double nmsThresh;

        public ThresholdPair(double probThresh, double nmsThresh) {
            this.probThresh = requireUnitThreshold("probThresh", probThresh);
            this.nmsThresh = requireUnitThreshold("nmsThresh", nmsThresh);
        }

        private static double requireUnitThreshold(String name, double value) {
            if (Double.isNaN(value) || Double.isInfinite(value) || value < 0.0d || value > 1.0d) {
                throw new IllegalArgumentException(name + " must be finite and between 0 and 1");
            }
            return value;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof ThresholdPair)) return false;
            ThresholdPair other = (ThresholdPair) obj;
            return Double.compare(probThresh, other.probThresh) == 0
                    && Double.compare(nmsThresh, other.nmsThresh) == 0;
        }

        @Override
        public int hashCode() {
            long probBits = Double.doubleToLongBits(probThresh);
            long nmsBits = Double.doubleToLongBits(nmsThresh);
            int result = (int) (probBits ^ (probBits >>> 32));
            result = 31 * result + (int) (nmsBits ^ (nmsBits >>> 32));
            return result;
        }

        @Override
        public String toString() {
            return "ThresholdPair{probThresh=" + probThresh
                    + ", nmsThresh=" + nmsThresh + "}";
        }
    }
}
