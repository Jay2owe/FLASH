package flash.pipeline.objects;

/**
 * Compute-time configuration for {@link ObjectIntensityProfiler}. A plain mutable value object so
 * the dialog/CLI layers (3D Object Analysis producer, Spatial Analysis consumer) can populate it
 * without depending on any UI types. Defaults reproduce the locked design when config is absent.
 *
 * <p>"Source" = the object-defining channel; "partner" = any channel whose raw intensity is sampled inside the source object's box
 * (partner == source is valid and describes the object's own internal distribution).
 */
public final class OipConfig {

    /** Voxels profiled within each object's bounding box. */
    public enum Region {
        /** All voxels in the (optionally padded) box, including background/neighbours — reveals
         *  signal <em>around</em> an object (e.g. a marker ring around a nuclear core). */
        WHOLE_BOX,
        /** Only voxels whose label equals the source object's label — pure intra-object
         *  distribution (the macro's "Only Object"). */
        OBJECT_VOXELS
    }

    /** Per-object, per-partner intensity normalization applied before cross-object averaging. */
    public enum IntensityNorm {
        /** (v - min) / (max - min) over the partner's box intensities → [0,1]. */
        PER_OBJECT_MINMAX,
        /** v / mean. */
        DIVIDE_BY_MEAN,
        /** (v - mean) / sd. */
        ZSCORE
    }

    // Profile families (the six toggles; nothing runs if all are false).
    public boolean doRadial = true;
    public boolean doMarginal = true;
    public boolean doPrincipalAxis = true;
    public boolean doAngular = false;
    public boolean doShell = false;
    public boolean doWithinBox = false;

    public Region region = Region.WHOLE_BOX;
    public IntensityNorm intensityNorm = IntensityNorm.PER_OBJECT_MINMAX;

    /** Number of bins for the radial profile (distance 0..1). */
    public int radialBins = 20;
    /** Number of angular bins around the centroid (0..2π) for ring-completeness. */
    public int angularBins = 12;
    /** Number of concentric shells (inner..outer). */
    public int shells = 3;
    /** Fixed length of the marginal/principal-axis curves over the normalized [-1,1] axis. */
    public int resampleN = 50;
    /** Box expansion per side, percent of each axis extent, before profiling. */
    public double boxPadPct = 0.0;
    /** Fraction (percent) of the per-partner max used as the "present" cutoff for ring-completeness
     *  and shell flags — the cheap threshold the Spatial consumer re-derives. */
    public double ringThresholdPct = 50.0;

    public boolean anyProfileEnabled() {
        return doRadial || doMarginal || doPrincipalAxis || doAngular || doShell || doWithinBox;
    }

    /** Defensive copy (the engine never mutates config, but callers may reuse and tweak one). */
    public OipConfig copy() {
        OipConfig c = new OipConfig();
        c.doRadial = doRadial;
        c.doMarginal = doMarginal;
        c.doPrincipalAxis = doPrincipalAxis;
        c.doAngular = doAngular;
        c.doShell = doShell;
        c.doWithinBox = doWithinBox;
        c.region = region;
        c.intensityNorm = intensityNorm;
        c.radialBins = radialBins;
        c.angularBins = angularBins;
        c.shells = shells;
        c.resampleN = resampleN;
        c.boxPadPct = boxPadPct;
        c.ringThresholdPct = ringThresholdPct;
        return c;
    }
}
