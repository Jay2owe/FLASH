package flash.pipeline.analyses;

import flash.pipeline.naming.OrientationManifestRow;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ImageOrientationSetupModelTest {

    @Test
    public void transformStateRotatesInNinetyDegreeSteps() {
        ImageOrientationSetupAnalysis.TransformState initial =
                ImageOrientationSetupAnalysis.transformFrom("0", false, false);

        assertEquals(OrientationManifestRow.RotationDegrees.DEG_270,
                initial.rotateLeft().rotateDegrees);
        assertEquals(OrientationManifestRow.RotationDegrees.DEG_90,
                initial.rotateRight().rotateDegrees);
        assertEquals(OrientationManifestRow.RotationDegrees.DEG_0,
                initial.rotateRight().rotateRight().rotateRight().rotateRight().rotateDegrees);
    }

    @Test
    public void transformStateTogglesFlipsAndResetClearsAllTransforms() {
        ImageOrientationSetupAnalysis.TransformState changed =
                ImageOrientationSetupAnalysis.transformFrom("90", false, true)
                        .flipHorizontal()
                        .flipVertical();

        assertEquals(OrientationManifestRow.RotationDegrees.DEG_90, changed.rotateDegrees);
        assertTrue(changed.flipHorizontal);
        assertFalse(changed.flipVertical);

        ImageOrientationSetupAnalysis.TransformState reset = changed.reset();

        assertEquals(OrientationManifestRow.RotationDegrees.DEG_0, reset.rotateDegrees);
        assertFalse(reset.flipHorizontal);
        assertFalse(reset.flipVertical);
    }

    @Test
    public void alternatingAssignmentsPairRowsAsSameAnimalByDefault() {
        List<ImageOrientationSetupAnalysis.AlternatingAssignment> assignments =
                ImageOrientationSetupAnalysis.buildAlternatingAssignments(
                        4, true, true, "Mouse", 1, "SCN");

        assertEquals("Mouse1", assignments.get(0).animalName);
        assertEquals(OrientationManifestRow.Hemisphere.LH, assignments.get(0).hemisphere);
        assertEquals("Mouse1", assignments.get(1).animalName);
        assertEquals(OrientationManifestRow.Hemisphere.RH, assignments.get(1).hemisphere);
        assertEquals("Mouse2", assignments.get(2).animalName);
        assertEquals(OrientationManifestRow.Hemisphere.LH, assignments.get(2).hemisphere);
        assertEquals("Mouse2", assignments.get(3).animalName);
        assertEquals(OrientationManifestRow.Hemisphere.RH, assignments.get(3).hemisphere);
        assertEquals("SCN", assignments.get(3).region);
    }

    @Test
    public void alternatingAssignmentsCanInvertPatternAndAvoidPairing() {
        List<ImageOrientationSetupAnalysis.AlternatingAssignment> assignments =
                ImageOrientationSetupAnalysis.buildAlternatingAssignments(
                        3, false, false, "Animal", 7, "PVN");

        assertEquals("Animal7", assignments.get(0).animalName);
        assertEquals(OrientationManifestRow.Hemisphere.RH, assignments.get(0).hemisphere);
        assertEquals("Animal8", assignments.get(1).animalName);
        assertEquals(OrientationManifestRow.Hemisphere.LH, assignments.get(1).hemisphere);
        assertEquals("Animal9", assignments.get(2).animalName);
        assertEquals(OrientationManifestRow.Hemisphere.RH, assignments.get(2).hemisphere);
        assertEquals("PVN", assignments.get(2).region);
    }
}
