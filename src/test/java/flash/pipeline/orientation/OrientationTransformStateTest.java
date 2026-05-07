package flash.pipeline.orientation;

import flash.pipeline.naming.NameParts;
import flash.pipeline.naming.OrientationManifestRow;
import flash.pipeline.naming.ResolvedImageMetadata;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OrientationTransformStateTest {

    @Test
    public void identityHasNoRotationOrFlips() {
        OrientationTransformState state = OrientationTransformState.identity();

        assertEquals(OrientationManifestRow.RotationDegrees.DEG_0, state.rotateDegrees);
        assertFalse(state.flipHorizontal);
        assertFalse(state.flipVertical);
        assertTrue(state.isIdentity());
    }

    @Test
    public void rotateLeftAndRightMoveInNinetyDegreeSteps() {
        OrientationTransformState initial =
                OrientationTransformState.fromCsv("0", false, false);

        assertEquals(OrientationManifestRow.RotationDegrees.DEG_270,
                initial.rotateLeft().rotateDegrees);
        assertEquals(OrientationManifestRow.RotationDegrees.DEG_90,
                initial.rotateRight().rotateDegrees);
        assertEquals(OrientationManifestRow.RotationDegrees.DEG_0,
                initial.rotateRight().rotateRight().rotateRight().rotateRight().rotateDegrees);
    }

    @Test
    public void flipsToggleAndResetClearsAllTransforms() {
        OrientationTransformState changed =
                OrientationTransformState.fromCsv("90", false, true)
                        .flipHorizontal()
                        .flipVertical();

        assertEquals(OrientationManifestRow.RotationDegrees.DEG_90, changed.rotateDegrees);
        assertTrue(changed.flipHorizontal);
        assertFalse(changed.flipVertical);
        assertFalse(changed.isIdentity());

        OrientationTransformState reset = changed.reset();

        assertEquals(OrientationManifestRow.RotationDegrees.DEG_0, reset.rotateDegrees);
        assertFalse(reset.flipHorizontal);
        assertFalse(reset.flipVertical);
        assertTrue(reset.isIdentity());
    }

    @Test
    public void fromMetadataFlattensStrictFilenameLegacyTransform() {
        ResolvedImageMetadata metadata = ResolvedImageMetadata.fromNameParts(
                new NameParts("Exp", "Mouse", "RH", "SCN", true),
                ResolvedImageMetadata.Source.STRICT_FILENAME);

        OrientationTransformState state = OrientationTransformState.fromMetadata(metadata);

        assertEquals(OrientationManifestRow.RotationDegrees.DEG_270, state.rotateDegrees);
        assertTrue(state.flipHorizontal);
        assertFalse(state.flipVertical);
    }

    @Test
    public void fromMetadataFlattensViewPolicyHorizontalFlip() {
        ResolvedImageMetadata metadata = new ResolvedImageMetadata(
                "KEY",
                "Original",
                "Display",
                "Mouse",
                "RH",
                "SCN",
                OrientationManifestRow.RotationDegrees.DEG_90,
                false,
                true,
                OrientationManifestRow.ViewPolicy.STANDARDIZE_TO_LEFT,
                ResolvedImageMetadata.Source.SAVED_MANIFEST);

        OrientationTransformState state = OrientationTransformState.fromMetadata(metadata);

        assertEquals(OrientationManifestRow.RotationDegrees.DEG_90, state.rotateDegrees);
        assertTrue(state.flipHorizontal);
        assertTrue(state.flipVertical);
    }
}
