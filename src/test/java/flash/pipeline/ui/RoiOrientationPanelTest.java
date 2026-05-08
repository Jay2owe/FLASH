package flash.pipeline.ui;

import flash.pipeline.naming.OrientationManifestRow;
import flash.pipeline.orientation.OrientationTransformState;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RoiOrientationPanelTest {

    @Test
    public void applyActionRotatesAndRequestsRedraw() {
        FakeTarget target = new FakeTarget(OrientationTransformState.identity());

        RoiOrientationPanel.applyAction(
                target, RoiOrientationPanel.OrientationAction.ROTATE_LEFT);

        assertEquals(OrientationManifestRow.RotationDegrees.DEG_270,
                target.state.rotateDegrees);
        assertEquals(1, target.clearCount);
        assertEquals(1, target.redrawCount);
    }

    @Test
    public void applyActionFlipsHorizontalAndVertical() {
        FakeTarget target = new FakeTarget(OrientationTransformState.identity());

        RoiOrientationPanel.applyAction(
                target, RoiOrientationPanel.OrientationAction.FLIP_HORIZONTAL);
        RoiOrientationPanel.applyAction(
                target, RoiOrientationPanel.OrientationAction.FLIP_VERTICAL);

        assertTrue(target.state.flipHorizontal);
        assertTrue(target.state.flipVertical);
        assertEquals(2, target.clearCount);
        assertEquals(2, target.redrawCount);
    }

    @Test
    public void resetOnIdentityDoesNotClearOrRedraw() {
        FakeTarget target = new FakeTarget(OrientationTransformState.identity());

        RoiOrientationPanel.applyAction(
                target, RoiOrientationPanel.OrientationAction.RESET);

        assertTrue(target.state.isIdentity());
        assertEquals(0, target.clearCount);
        assertEquals(0, target.redrawCount);
    }

    @Test
    public void resetChangedStateClearsTransform() {
        FakeTarget target = new FakeTarget(
                OrientationTransformState.fromCsv("90", true, false));

        RoiOrientationPanel.applyAction(
                target, RoiOrientationPanel.OrientationAction.RESET);

        assertEquals(OrientationManifestRow.RotationDegrees.DEG_0,
                target.state.rotateDegrees);
        assertFalse(target.state.flipHorizontal);
        assertFalse(target.state.flipVertical);
        assertEquals(1, target.clearCount);
        assertEquals(1, target.redrawCount);
    }

    @Test
    public void combinedPanelActionAppliesOrientationDecisionThroughInstanceHandler() {
        FakeTarget target = new FakeTarget(OrientationTransformState.identity());
        RoiOrientationPanel panel = new RoiOrientationPanel(
                null, target, "Image 1/3", "Mouse <LH> & SCN");

        panel.performOrientationAction(RoiOrientationPanel.OrientationAction.ROTATE_RIGHT);

        assertEquals(OrientationManifestRow.RotationDegrees.DEG_90,
                target.state.rotateDegrees);
        assertEquals(1, target.clearCount);
        assertEquals(1, target.redrawCount);
        assertTrue(RoiOrientationPanel
                .instructionHtml("Image 1/3", "Mouse <LH> & SCN")
                .contains("Mouse &lt;LH&gt; &amp; SCN"));

        panel.close();
    }

    private static final class FakeTarget
            implements RoiOrientationPanel.OrientationActionTarget {
        private OrientationTransformState state;
        private int clearCount;
        private int redrawCount;

        FakeTarget(OrientationTransformState state) {
            this.state = state;
        }

        @Override
        public OrientationTransformState getState() {
            return state;
        }

        @Override
        public void setState(OrientationTransformState state) {
            this.state = state;
        }

        @Override
        public void redrawFromState() {
            redrawCount++;
        }

        @Override
        public void clearUnsavedRoiAfterOrientationChange() {
            clearCount++;
        }

        @Override
        public String statusText() {
            return "";
        }
    }
}
