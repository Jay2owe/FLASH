package flash.pipeline.objects;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-object output of {@link ObjectIntensityProfiler}: the binned intensity profiles and the
 * compact scalar descriptors, for one source object across every partner channel sampled.
 *
 * <p>Curves are fixed-length and live on a normalized axis (radial 0..1; marginal/principal −1..+1
 * centred on the centroid; angular 0..2π) so they are directly averageable across objects. Each bin
 * stores the <em>mean</em> partner intensity of the voxels falling in it; empty bins are
 * {@link Double#NaN}. Both {@code raw} (un-normalized, for the long CSV) and {@code norm}
 * (per-object normalized, for aggregation/figures) are kept.
 */
public final class ObjectProfileResult {

    public final String sourceChannel;
    public final int label;
    public final int voxelCount;

    /** Calibrated max distance from centroid to a box corner (radial normalization denominator). */
    public double rMax;

    // Shape (partner-independent), from PCA of the object's voxel cloud.
    public double[] eigenvalues = new double[3];     // descending λ1≥λ2≥λ3
    public double[][] eigenvectors = new double[3][3]; // column k ↔ eigenvalue k
    public double elongation = Double.NaN;           // √(λ1/λ2)
    public double flatness = Double.NaN;             // √(λ2/λ3)

    public final Map<String, PartnerProfiles> byPartner = new LinkedHashMap<String, PartnerProfiles>();

    public ObjectProfileResult(String sourceChannel, int label, int voxelCount) {
        this.sourceChannel = sourceChannel;
        this.label = label;
        this.voxelCount = voxelCount;
    }

    public PartnerProfiles partner(String name) {
        PartnerProfiles p = byPartner.get(name);
        if (p == null) {
            p = new PartnerProfiles(name);
            byPartner.put(name, p);
        }
        return p;
    }

    /** Profiles + scalars for one partner channel sampled inside this source object. */
    public static final class PartnerProfiles {
        public final String partnerChannel;

        // Curves — mean partner intensity per bin (NaN where no voxels). Raw + per-object normalized.
        public double[] radialRaw, radialNorm;
        public double[] marginalXRaw, marginalXNorm;
        public double[] marginalYRaw, marginalYNorm;
        public double[] marginalZRaw, marginalZNorm;
        public double[] pcMajorRaw, pcMajorNorm;
        public double[] pcMinorRaw, pcMinorNorm;
        public double[] pcThirdRaw, pcThirdNorm;
        public double[] angularRaw, angularNorm;

        // Scalar descriptors (per source→partner).
        public double radialCoreEdge = Double.NaN;   // inner-third mean ÷ outer-third mean
        public double radialPeakR = Double.NaN;      // normalized radius of the peak bin
        public double radialPolarity = Double.NaN;   // ‖intensity-weighted offset‖ ÷ rMax
        public double ringCompleteness = Double.NaN; // fraction of angular bins above cutoff
        public double angularUniformity = Double.NaN;// 1 − CV of angular bins
        public double shellInner = Double.NaN;
        public double shellMid = Double.NaN;
        public double shellOuter = Double.NaN;
        public double pcMajorPolarity = Double.NaN;  // signed, ÷ half-major extent
        public double withinBoxPearson = Double.NaN;
        public double withinBoxManders = Double.NaN;

        PartnerProfiles(String partnerChannel) {
            this.partnerChannel = partnerChannel;
        }
    }
}
