package flash.pipeline.orientation;

import flash.pipeline.naming.OrientationManifestRow;
import org.junit.Test;

import static flash.pipeline.naming.OrientationManifestRow.Hemisphere.LH;
import static flash.pipeline.naming.OrientationManifestRow.Hemisphere.RH;
import static flash.pipeline.naming.OrientationManifestRow.Hemisphere.UNKNOWN;
import static flash.pipeline.naming.OrientationManifestRow.RotationDegrees.DEG_0;
import static flash.pipeline.naming.OrientationManifestRow.RotationDegrees.DEG_90;
import static flash.pipeline.naming.OrientationManifestRow.RotationDegrees.DEG_180;
import static flash.pipeline.orientation.BroadcastScope.ALL_LITERAL;
import static flash.pipeline.orientation.BroadcastScope.ALL_MIRRORED;
import static flash.pipeline.orientation.BroadcastScope.SAME_HEMISPHERE;
import static flash.pipeline.orientation.BroadcastScope.THIS_IMAGE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class BroadcastRuleTest {

    @Test
    public void sameHemisphereFromLhMatchesOnlyLhAndReturnsLiteralTransform() {
        OrientationTransformState transform = transform(DEG_90, true, false);
        BroadcastRule rule = new BroadcastRule(SAME_HEMISPHERE, transform, LH);

        assertTrue(rule.isActive());
        assertTrue(rule.appliesTo(LH));
        assertFalse(rule.appliesTo(RH));
        assertFalse(rule.appliesTo(UNKNOWN));

        assertTransform(DEG_90, true, false, rule.transformFor(LH));
        assertNull(rule.transformFor(RH));
        assertNull(rule.transformFor(UNKNOWN));
    }

    @Test
    public void allLiteralMatchesEveryHemisphereWithoutMirroring() {
        OrientationTransformState transform = transform(DEG_180, true, true);
        BroadcastRule rule = new BroadcastRule(ALL_LITERAL, transform, LH);

        assertTrue(rule.appliesTo(LH));
        assertTrue(rule.appliesTo(RH));
        assertTrue(rule.appliesTo(UNKNOWN));

        assertTransform(DEG_180, true, true, rule.transformFor(LH));
        assertTransform(DEG_180, true, true, rule.transformFor(RH));
        assertTransform(DEG_180, true, true, rule.transformFor(UNKNOWN));
    }

    @Test
    public void allMirroredFromLhTogglesHorizontalOnlyForRhTarget() {
        OrientationTransformState transform = transform(DEG_90, false, true);
        BroadcastRule rule = new BroadcastRule(ALL_MIRRORED, transform, LH);

        assertTrue(rule.appliesTo(LH));
        assertTrue(rule.appliesTo(RH));
        assertTrue(rule.appliesTo(UNKNOWN));

        assertTransform(DEG_90, false, true, rule.transformFor(LH));
        assertTransform(DEG_90, true, true, rule.transformFor(RH));
        assertTransform(DEG_90, false, true, rule.transformFor(UNKNOWN));
    }

    @Test
    public void allMirroredFromUnknownSourceIsLiteralForEveryTarget() {
        OrientationTransformState transform = transform(DEG_180, true, false);
        BroadcastRule rule = new BroadcastRule(ALL_MIRRORED, transform, UNKNOWN);

        assertTransform(DEG_180, true, false, rule.transformFor(LH));
        assertTransform(DEG_180, true, false, rule.transformFor(RH));
        assertTransform(DEG_180, true, false, rule.transformFor(UNKNOWN));
    }

    @Test
    public void thisImageNeverForwardApplies() {
        BroadcastRule rule = new BroadcastRule(THIS_IMAGE, transform(DEG_90, true, true), LH);

        assertFalse(rule.isActive());
        assertFalse(rule.appliesTo(LH));
        assertFalse(rule.appliesTo(RH));
        assertFalse(rule.appliesTo(UNKNOWN));

        assertNull(rule.transformFor(LH));
        assertNull(rule.transformFor(RH));
        assertNull(rule.transformFor(UNKNOWN));
    }

    @Test
    public void doubleMirrorReturnsOriginalHorizontalFlip() {
        OrientationTransformState original = transform(DEG_180, true, true);
        BroadcastRule fromLh = new BroadcastRule(ALL_MIRRORED, original, LH);

        OrientationTransformState mirroredToRh = fromLh.transformFor(RH);
        BroadcastRule fromRh = new BroadcastRule(ALL_MIRRORED, mirroredToRh, RH);
        OrientationTransformState mirroredBackToLh = fromRh.transformFor(LH);

        assertTransform(DEG_180, true, true, mirroredBackToLh);
    }

    @Test
    public void nullConstructorValuesDefaultToThisImageIdentityUnknown() {
        BroadcastRule rule = new BroadcastRule(null, null, null);

        assertEquals(THIS_IMAGE, rule.scope);
        assertEquals(UNKNOWN, rule.sourceHemisphere);
        assertFalse(rule.isActive());
        assertTransform(DEG_0, false, false, rule.transform);
    }

    @Test
    public void presetRejectsBlankNamesDefaultsNullTransformAndComparesNamesCaseInsensitively() {
        OrientationPreset first = new OrientationPreset("  Standard left  ", null);
        OrientationPreset second = new OrientationPreset(
                "standard LEFT",
                OrientationTransformState.identity());

        assertEquals("Standard left", first.name);
        assertTransform(DEG_0, false, false, first.transform);
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, new OrientationPreset("Other", OrientationTransformState.identity()));
    }

    @Test(expected = IllegalArgumentException.class)
    public void presetRejectsNullName() {
        new OrientationPreset(null, OrientationTransformState.identity());
    }

    private static OrientationTransformState transform(
            OrientationManifestRow.RotationDegrees rotateDegrees,
            boolean flipHorizontal,
            boolean flipVertical) {
        return new OrientationTransformState(rotateDegrees, flipHorizontal, flipVertical);
    }

    private static void assertTransform(OrientationManifestRow.RotationDegrees rotateDegrees,
                                        boolean flipHorizontal,
                                        boolean flipVertical,
                                        OrientationTransformState actual) {
        assertEquals(rotateDegrees, actual.rotateDegrees);
        assertEquals(flipHorizontal, actual.flipHorizontal);
        assertEquals(flipVertical, actual.flipVertical);
    }
}
