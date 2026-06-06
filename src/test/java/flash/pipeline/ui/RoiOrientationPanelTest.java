package flash.pipeline.ui;

import flash.pipeline.naming.OrientationManifestRow;
import flash.pipeline.orientation.BroadcastScope;
import flash.pipeline.orientation.OrientationBatchController;
import flash.pipeline.orientation.OrientationPresetStore;
import flash.pipeline.orientation.OrientationTransformState;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JButton;

import static flash.pipeline.naming.OrientationManifestRow.Hemisphere.LH;
import static flash.pipeline.naming.OrientationManifestRow.Hemisphere.RH;
import static flash.pipeline.naming.OrientationManifestRow.Hemisphere.UNKNOWN;
import static flash.pipeline.naming.OrientationManifestRow.RotationDegrees.DEG_90;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

public class RoiOrientationPanelTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

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

    @Test
    public void instructionHtmlMentionsReuseAndBroadcastControls() {
        String html = RoiOrientationPanel.instructionHtml("Image 1/3", "Mouse");

        assertTrue(html.contains("Repeat last"));
        assertTrue(html.contains("saved preset"));
        assertTrue(html.contains("apply-to buttons"));
        assertTrue(html.contains("all later images"));
        assertTrue(html.contains("mirror later LH/RH images"));
    }

    @Test
    public void displayActionsDelegateThroughInstanceHandler() {
        DisplayTarget target = new DisplayTarget(OrientationTransformState.identity());
        RoiOrientationPanel panel = new RoiOrientationPanel(
                null, target, "Image 1/3", "Mouse");

        panel.performDisplayLutToggle();
        panel.performBrightnessContrastAction();

        assertEquals(1, target.lutToggleCount);
        assertEquals(1, target.brightnessContrastCount);

        panel.close();
    }

    @Test
    public void dialogLocationNearImageUsesSameScreenRightSideWhenThereIsRoom() {
        Point location = RoiOrientationPanel.dialogLocationNearImage(
                new Rectangle(100, 50, 300, 200),
                new Dimension(250, 120),
                new Rectangle(0, 0, 1000, 700));

        assertEquals(new Point(412, 50), location);
    }

    @Test
    public void dialogLocationNearImageFlipsLeftAndStaysOnSameScreen() {
        Point location = RoiOrientationPanel.dialogLocationNearImage(
                new Rectangle(700, 650, 260, 100),
                new Dimension(250, 120),
                new Rectangle(0, 0, 1000, 700));

        assertEquals(new Point(438, 580), location);
    }

    @Test
    public void batchHemisphereButtonUsesCurrentHemisphereLabel() throws Exception {
        assumeUiAvailable();

        BatchFixture lh = batchFixture(LH, OrientationTransformState.identity(), 2);
        RoiOrientationPanel lhPanel = batchPanel(lh, "Saved");
        assertEquals("All LH images", lhPanel.hemisphereRuleButtonForTests().getText());
        lhPanel.close();

        BatchFixture rh = batchFixture(RH, OrientationTransformState.identity(), 2);
        RoiOrientationPanel rhPanel = batchPanel(rh, "Saved");
        assertEquals("All RH images", rhPanel.hemisphereRuleButtonForTests().getText());
        rhPanel.close();
    }

    @Test
    public void batchHemisphereScopedButtonsDisableForUnknownHemisphere() throws Exception {
        assumeUiAvailable();
        BatchFixture fixture = batchFixture(UNKNOWN, OrientationTransformState.identity(), 2);
        RoiOrientationPanel panel = batchPanel(fixture, "Saved");

        assertFalse(panel.hemisphereRuleButtonForTests().isEnabled());
        assertFalse(panel.mirrorRuleButtonForTests().isEnabled());
        assertTrue(panel.allImagesButtonForTests().isEnabled());

        panel.close();
    }

    @Test
    public void repeatLastStartsDisabledAndEnablesAfterManualAction() throws Exception {
        assumeUiAvailable();
        BatchFixture fixture = batchFixture(LH, OrientationTransformState.identity(), 2);
        RoiOrientationPanel panel = batchPanel(fixture, "Saved");

        assertFalse(panel.repeatLastButtonForTests().isEnabled());

        panel.performOrientationAction(RoiOrientationPanel.OrientationAction.ROTATE_RIGHT);

        assertTrue(panel.repeatLastButtonForTests().isEnabled());
        assertEquals(DEG_90, fixture.target.getState().rotateDegrees);

        panel.close();
    }

    @Test
    public void presetButtonAppliesPresetAndSaveAddsButtonImmediately() throws Exception {
        assumeUiAvailable();
        BatchFixture fixture = batchFixture(
                LH, transform(DEG_90, true, false), 2);
        fixture.controller.savePreset("Sideways slides");
        fixture.target.setState(OrientationTransformState.identity());
        RoiOrientationPanel panel = batchPanel(fixture, "Fresh preset");

        JButton presetButton = panel.presetButtonForTests("Sideways slides");
        assertNotNull(presetButton);
        presetButton.doClick();

        assertEquals(DEG_90, fixture.target.getState().rotateDegrees);
        assertTrue(fixture.target.getState().flipHorizontal);

        panel.savePresetButtonForTests().doClick();

        assertNotNull(panel.presetButtonForTests("Fresh preset"));
        assertEquals(2, fixture.controller.presets().size());

        panel.close();
    }

    @Test
    public void allImagesRuleAndClearButtonsUpdateControllerRule() throws Exception {
        assumeUiAvailable();
        BatchFixture fixture = batchFixture(
                LH, transform(DEG_90, false, false), 3);
        RoiOrientationPanel panel = batchPanel(fixture, "Saved");

        panel.allImagesButtonForTests().doClick();

        assertNotNull(fixture.controller.activeRule());
        assertEquals(BroadcastScope.ALL_LITERAL, fixture.controller.activeRule().scope);
        assertTrue(panel.ruleStatusLabelForTests().getText().contains("Rule active"));

        panel.thisImageButtonForTests().doClick();
        assertNull(fixture.controller.activeRule());

        panel.allImagesButtonForTests().doClick();
        panel.clearRuleButtonForTests().doClick();
        assertNull(fixture.controller.activeRule());

        panel.close();
    }

    private static void assumeUiAvailable() {
        assumeFalse("RoiOrientationPanel Swing rows are not built in headless mode.",
                GraphicsEnvironment.isHeadless());
    }

    private RoiOrientationPanel batchPanel(BatchFixture fixture, final String promptName) {
        return new RoiOrientationPanel(null, fixture.target,
                "Image 1/2", "Mouse", fixture.controller,
                suggestion -> promptName);
    }

    private BatchFixture batchFixture(OrientationManifestRow.Hemisphere hemisphere,
                                      OrientationTransformState state,
                                      int totalImages) throws Exception {
        File projectDir = temp.newFolder();
        OrientationBatchController controller = new OrientationBatchController(
                new OrientationPresetStore(projectDir.getAbsolutePath()),
                totalImages);
        BatchTarget target = new BatchTarget(hemisphere, state);
        controller.bindCurrent(target, 0);
        return new BatchFixture(controller, target);
    }

    private static OrientationTransformState transform(
            OrientationManifestRow.RotationDegrees rotateDegrees,
            boolean flipHorizontal,
            boolean flipVertical) {
        return new OrientationTransformState(
                rotateDegrees, flipHorizontal, flipVertical);
    }

    private static final class BatchFixture {
        private final OrientationBatchController controller;
        private final BatchTarget target;

        BatchFixture(OrientationBatchController controller, BatchTarget target) {
            this.controller = controller;
            this.target = target;
        }
    }

    private static class FakeTarget
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

    private static final class BatchTarget extends FakeTarget
            implements OrientationBatchController.CurrentImage {
        private final OrientationManifestRow.Hemisphere hemisphere;
        private final List<OrientationTransformState> appliedStates =
                new ArrayList<OrientationTransformState>();

        BatchTarget(OrientationManifestRow.Hemisphere hemisphere,
                    OrientationTransformState state) {
            super(state);
            this.hemisphere = hemisphere;
        }

        @Override
        public OrientationManifestRow.Hemisphere hemisphere() {
            return hemisphere;
        }

        @Override
        public OrientationTransformState state() {
            return getState();
        }

        @Override
        public void applyState(OrientationTransformState next) {
            OrientationTransformState safeNext = next == null
                    ? OrientationTransformState.identity()
                    : next;
            appliedStates.add(safeNext);
            setState(safeNext);
        }
    }

    private static final class DisplayTarget extends FakeTarget {
        private int lutToggleCount;
        private int brightnessContrastCount;

        DisplayTarget(OrientationTransformState state) {
            super(state);
        }

        @Override
        public boolean displayControlsAvailable() {
            return true;
        }

        @Override
        public String lutToggleButtonText() {
            return "Red LUT";
        }

        @Override
        public void toggleDisplayLut() {
            lutToggleCount++;
        }

        @Override
        public void adjustBrightnessContrast() {
            brightnessContrastCount++;
        }
    }
}
