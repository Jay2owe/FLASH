package flash.pipeline.representative;

import flash.pipeline.presentation.PresentationTileConfig;

/**
 * Pure geometry helpers shared by the tile annotation editor: the default
 * fractional anchor for each corner preset, and magnetic snapping of a dragged
 * fractional position back to the nearest corner.
 */
public final class AnnotationPlacement {

    private AnnotationPlacement() {
    }

    /** Edge fractions used for manual/snap corner anchors. */
    private static final double NEAR = 0.0;
    private static final double FAR = 1.0;

    /**
     * Approximate top-left fractional anchor ({@code [x, y]}) for a corner
     * preset. The renderer clamps the box inside the tile, so right/bottom
     * anchors place the box against the far edge.
     */
    public static double[] cornerFraction(PresentationTileConfig.Position position) {
        switch (position) {
            case TOP_RIGHT:
                return new double[]{FAR, NEAR};
            case BOTTOM_LEFT:
                return new double[]{NEAR, FAR};
            case BOTTOM_RIGHT:
                return new double[]{FAR, FAR};
            case TOP_LEFT:
            default:
                return new double[]{NEAR, NEAR};
        }
    }

    /**
     * The nearest corner whose anchor is within {@code threshold} (Euclidean,
     * in fraction space) of {@code (fracX, fracY)}, or {@code null} if none.
     */
    public static PresentationTileConfig.Position snapToNearestCorner(double fracX,
                                                                      double fracY,
                                                                      double threshold) {
        PresentationTileConfig.Position best = null;
        double bestDist = threshold;
        for (PresentationTileConfig.Position position : PresentationTileConfig.Position.values()) {
            double[] corner = cornerFraction(position);
            double dx = fracX - corner[0];
            double dy = fracY - corner[1];
            double dist = Math.sqrt(dx * dx + dy * dy);
            if (dist <= bestDist) {
                bestDist = dist;
                best = position;
            }
        }
        return best;
    }

    /** Clamp a raw fraction into {@code [0, 1]}. */
    public static double clampFraction(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}
