package flash.pipeline.deconv.routing;

import java.util.Optional;

/**
 * The two routing groups a channel can be steered independently for:
 * <ul>
 *   <li>{@link #ANALYSIS} — filters, segmentation, object threshold, particle size and intensity
 *       (all coupled by the shared filtering pipeline); the measurement path.</li>
 *   <li>{@link #DISPLAY} — display range, LUT and exported/figure pixels.</li>
 * </ul>
 *
 * <p>{@link #groupFor(int)} is the single authoritative analysis&rarr;group mapping so the four
 * pixel-consuming analyses cannot drift. The analysis indices are the {@code FLASH_Pipeline.IDX_*}
 * values; they are inlined here (rather than imported) to keep this package ImageJ-free and
 * trivially unit-testable.</p>
 */
public enum DeconvRoutingGroup {
    ANALYSIS,
    DISPLAY;

    // Mirrors flash.pipeline.FLASH_Pipeline.IDX_* — keep in sync if those ever change.
    private static final int IDX_SPLIT_MERGE = 3;            // DISPLAY (Make Presentation Images)
    private static final int IDX_3D_OBJECT = 4;              // ANALYSIS
    private static final int IDX_INTENSITY = 7;              // ANALYSIS
    private static final int IDX_REPRESENTATIVE_FIGURE = 12; // DISPLAY

    /**
     * The routing group a pixel-consuming analysis belongs to, or {@link Optional#empty()} for
     * analyses that consume no raw/deconvolved pixels directly (Spatial and LineDistance read
     * {@code _objects_*.tif} label maps; Aggregation/Statistics/Excel/CreateBin/DrawROIs/
     * Deconvolution/Spectral/Orientation consume no image pixels at all). An empty result means
     * "not routed / exempt from deconvolution routing and preflight".
     */
    public static Optional<DeconvRoutingGroup> groupFor(int analysisIndex) {
        switch (analysisIndex) {
            case IDX_SPLIT_MERGE:
            case IDX_REPRESENTATIVE_FIGURE:
                return Optional.of(DISPLAY);
            case IDX_3D_OBJECT:
            case IDX_INTENSITY:
                return Optional.of(ANALYSIS);
            default:
                return Optional.empty();
        }
    }

    /** True when {@link #groupFor(int)} routes this analysis (i.e. it is a direct pixel consumer). */
    public static boolean isRouted(int analysisIndex) {
        return groupFor(analysisIndex).isPresent();
    }
}
