package flash.pipeline.orientation;

import flash.pipeline.naming.OrientationManifestRow;
import flash.pipeline.naming.ResolvedImageMetadata;

/**
 * Non-UI orientation transform state used by ROI drawing.
 */
public final class OrientationTransformState {
    public final OrientationManifestRow.RotationDegrees rotateDegrees;
    public final boolean flipHorizontal;
    public final boolean flipVertical;

    public OrientationTransformState(OrientationManifestRow.RotationDegrees rotateDegrees,
                                     boolean flipHorizontal,
                                     boolean flipVertical) {
        this.rotateDegrees = rotateDegrees == null
                ? OrientationManifestRow.RotationDegrees.DEG_0 : rotateDegrees;
        this.flipHorizontal = flipHorizontal;
        this.flipVertical = flipVertical;
    }

    public static OrientationTransformState identity() {
        return new OrientationTransformState(
                OrientationManifestRow.RotationDegrees.DEG_0, false, false);
    }

    public static OrientationTransformState fromCsv(String rotateDegrees,
                                                    boolean flipHorizontal,
                                                    boolean flipVertical) {
        return new OrientationTransformState(
                OrientationManifestRow.RotationDegrees.fromCsv(rotateDegrees),
                flipHorizontal,
                flipVertical);
    }

    public static OrientationTransformState fromMetadata(ResolvedImageMetadata metadata) {
        if (metadata == null) return identity();

        boolean effectiveFlipHorizontal = metadata.flipHorizontal;
        if (metadata.viewPolicy == OrientationManifestRow.ViewPolicy.STANDARDIZE_TO_LEFT
                && "RH".equalsIgnoreCase(metadata.hemisphere)) {
            effectiveFlipHorizontal = !effectiveFlipHorizontal;
        } else if (metadata.viewPolicy == OrientationManifestRow.ViewPolicy.STANDARDIZE_TO_RIGHT
                && "LH".equalsIgnoreCase(metadata.hemisphere)) {
            effectiveFlipHorizontal = !effectiveFlipHorizontal;
        }

        return new OrientationTransformState(
                metadata.rotateDegrees,
                effectiveFlipHorizontal,
                metadata.flipVertical);
    }

    public OrientationTransformState rotateLeft() {
        return rotateBy(270);
    }

    public OrientationTransformState rotateRight() {
        return rotateBy(90);
    }

    public OrientationTransformState flipHorizontal() {
        return new OrientationTransformState(rotateDegrees, !flipHorizontal, flipVertical);
    }

    public OrientationTransformState flipVertical() {
        return new OrientationTransformState(rotateDegrees, flipHorizontal, !flipVertical);
    }

    public OrientationTransformState reset() {
        return identity();
    }

    public boolean isIdentity() {
        return rotateDegrees == OrientationManifestRow.RotationDegrees.DEG_0
                && !flipHorizontal
                && !flipVertical;
    }

    private OrientationTransformState rotateBy(int deltaDegrees) {
        int next = (rotateDegrees.degrees() + deltaDegrees) % 360;
        return new OrientationTransformState(
                OrientationManifestRow.RotationDegrees.fromDegrees(next),
                flipHorizontal,
                flipVertical);
    }
}
