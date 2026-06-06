package flash.pipeline.orientation;

import flash.pipeline.naming.OrientationManifestRow;

/**
 * Immutable forward rule captured from one ROI orientation decision.
 */
public final class BroadcastRule {
    public final BroadcastScope scope;
    public final OrientationTransformState transform;
    public final OrientationManifestRow.Hemisphere sourceHemisphere;

    public BroadcastRule(BroadcastScope scope,
                         OrientationTransformState transform,
                         OrientationManifestRow.Hemisphere sourceHemisphere) {
        this.scope = scope == null ? BroadcastScope.THIS_IMAGE : scope;
        this.transform = transform == null ? OrientationTransformState.identity() : transform;
        this.sourceHemisphere = normalizeHemisphere(sourceHemisphere);
    }

    /**
     * Explicit current-image-only rule. Later controllers should use null for no active rule.
     */
    public static BroadcastRule thisImageOnly() {
        return new BroadcastRule(
                BroadcastScope.THIS_IMAGE,
                OrientationTransformState.identity(),
                OrientationManifestRow.Hemisphere.UNKNOWN);
    }

    public boolean isActive() {
        return scope != BroadcastScope.THIS_IMAGE;
    }

    public boolean appliesTo(OrientationManifestRow.Hemisphere target) {
        OrientationManifestRow.Hemisphere targetHemisphere = normalizeHemisphere(target);

        switch (scope) {
            case THIS_IMAGE:
                return false;
            case SAME_HEMISPHERE:
                return isKnown(sourceHemisphere) && targetHemisphere == sourceHemisphere;
            case ALL_LITERAL:
            case ALL_MIRRORED:
                return true;
            default:
                return false;
        }
    }

    public OrientationTransformState transformFor(OrientationManifestRow.Hemisphere target) {
        OrientationManifestRow.Hemisphere targetHemisphere = normalizeHemisphere(target);
        if (!appliesTo(targetHemisphere)) {
            return null;
        }

        if (scope == BroadcastScope.ALL_MIRRORED
                && isKnown(sourceHemisphere)
                && isKnown(targetHemisphere)
                && targetHemisphere != sourceHemisphere) {
            return transform.flipHorizontal();
        }

        return transform;
    }

    private static boolean isKnown(OrientationManifestRow.Hemisphere hemisphere) {
        return hemisphere == OrientationManifestRow.Hemisphere.LH
                || hemisphere == OrientationManifestRow.Hemisphere.RH;
    }

    private static OrientationManifestRow.Hemisphere normalizeHemisphere(
            OrientationManifestRow.Hemisphere hemisphere) {
        return hemisphere == null ? OrientationManifestRow.Hemisphere.UNKNOWN : hemisphere;
    }
}
