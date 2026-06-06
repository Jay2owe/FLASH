package flash.pipeline.orientation;

import flash.pipeline.naming.OrientationManifestRow;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static flash.pipeline.naming.OrientationManifestRow.Hemisphere.LH;
import static flash.pipeline.naming.OrientationManifestRow.Hemisphere.RH;
import static flash.pipeline.naming.OrientationManifestRow.Hemisphere.UNKNOWN;
import static flash.pipeline.naming.OrientationManifestRow.RotationDegrees.DEG_180;
import static flash.pipeline.naming.OrientationManifestRow.RotationDegrees.DEG_270;
import static flash.pipeline.naming.OrientationManifestRow.RotationDegrees.DEG_90;
import static flash.pipeline.orientation.BroadcastScope.ALL_LITERAL;
import static flash.pipeline.orientation.BroadcastScope.ALL_MIRRORED;
import static flash.pipeline.orientation.BroadcastScope.SAME_HEMISPHERE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OrientationBatchControllerTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void allLiteralRuleAppliesLiteralTransformOnOpen() throws Exception {
        OrientationBatchController controller = controller("all-literal", 2);
        FakeImage source = image(LH, transform(DEG_90, true, false));
        controller.bindCurrent(source, 0);
        controller.setRule(ALL_LITERAL);

        FakeImage target = image(RH, OrientationTransformState.identity());
        controller.bindCurrent(target, 1);

        assertTrue(controller.applyActiveRuleOnOpen());
        assertTransform(DEG_90, true, false, target.state());
        assertEquals(1, target.appliedStates.size());
    }

    @Test
    public void allLiteralRuleFromUnknownHemisphereAppliesLiterallyToKnownTargets()
            throws Exception {
        OrientationBatchController controller = controller("unknown-all-literal", 3);
        FakeImage source = image(UNKNOWN, transform(DEG_90, true, false));
        controller.bindCurrent(source, 0);
        controller.setRule(ALL_LITERAL);

        FakeImage lhTarget = image(LH, OrientationTransformState.identity());
        controller.bindCurrent(lhTarget, 1);
        assertTrue(controller.applyActiveRuleOnOpen());
        assertTransform(DEG_90, true, false, lhTarget.state());

        FakeImage rhTarget = image(RH, OrientationTransformState.identity());
        controller.bindCurrent(rhTarget, 2);
        assertTrue(controller.applyActiveRuleOnOpen());
        assertTransform(DEG_90, true, false, rhTarget.state());
    }

    @Test
    public void allMirroredRuleTogglesHorizontalFlipForOppositeHemisphere() throws Exception {
        OrientationBatchController controller = controller("all-mirrored", 2);
        FakeImage source = image(LH, transform(DEG_270, false, true));
        controller.bindCurrent(source, 0);
        controller.setRule(ALL_MIRRORED);

        FakeImage target = image(RH, OrientationTransformState.identity());
        controller.bindCurrent(target, 1);

        assertTrue(controller.applyActiveRuleOnOpen());
        assertTransform(DEG_270, true, true, target.state());
    }

    @Test
    public void sameHemisphereRuleSkipsOppositeHemisphereAndAppliesToMatchingHemisphere()
            throws Exception {
        OrientationBatchController controller = controller("same-hemisphere", 3);
        FakeImage source = image(LH, transform(DEG_180, false, true));
        controller.bindCurrent(source, 0);
        controller.setRule(SAME_HEMISPHERE);

        FakeImage rhTarget = image(RH, OrientationTransformState.identity());
        controller.bindCurrent(rhTarget, 1);
        assertFalse(controller.applyActiveRuleOnOpen());
        assertTransform(OrientationManifestRow.RotationDegrees.DEG_0, false, false,
                rhTarget.state());
        assertTrue(rhTarget.appliedStates.isEmpty());

        FakeImage lhTarget = image(LH, OrientationTransformState.identity());
        controller.bindCurrent(lhTarget, 2);
        assertTrue(controller.applyActiveRuleOnOpen());
        assertTransform(DEG_180, false, true, lhTarget.state());
    }

    @Test
    public void settingSecondRuleReplacesFirstRuleAndStatus() throws Exception {
        OrientationBatchController controller = controller("replace-rule", 4);
        FakeImage source = image(LH, transform(DEG_90, false, false));
        controller.bindCurrent(source, 0);
        controller.setRule(SAME_HEMISPHERE);

        assertEquals(SAME_HEMISPHERE, controller.activeRule().scope);
        assertTrue(controller.ruleStatusText().contains("all LH images"));

        controller.setRule(ALL_LITERAL);

        assertEquals(ALL_LITERAL, controller.activeRule().scope);
        assertTrue(controller.ruleStatusText().contains("all images"));
        assertFalse(controller.ruleStatusText().contains("all LH images"));
    }

    @Test
    public void manualStateAfterMirroredRuleDoesNotMutateStoredRule() throws Exception {
        OrientationBatchController controller = controller("manual-after-mirror", 2);
        FakeImage source = image(LH, transform(DEG_90, false, false));
        controller.bindCurrent(source, 0);
        controller.setRule(ALL_MIRRORED);

        controller.noteManualState(transform(DEG_180, true, true));

        FakeImage target = image(RH, OrientationTransformState.identity());
        controller.bindCurrent(target, 1);

        assertTrue(controller.applyActiveRuleOnOpen());
        assertTransform(DEG_90, true, false, target.state());
    }

    @Test
    public void repeatLastNoOpsUntilApplyThenReappliesStoredTransformLiterally()
            throws Exception {
        OrientationBatchController controller = controller("repeat-last", 2);
        FakeImage current = image(LH, OrientationTransformState.identity());
        controller.bindCurrent(current, 0);

        controller.repeatLast();

        assertTrue(current.appliedStates.isEmpty());
        assertFalse(controller.hasLastApplied());

        OrientationPreset preset =
                new OrientationPreset("Saved", transform(DEG_90, true, false));
        controller.applyPreset(preset);
        assertTrue(controller.hasLastApplied());

        FakeImage next = image(RH, OrientationTransformState.identity());
        controller.bindCurrent(next, 1);
        controller.repeatLast();

        assertTransform(DEG_90, true, false, next.state());
        assertEquals(1, next.appliedStates.size());
    }

    @Test
    public void savePresetPersistsAndRefreshesPresetList() throws Exception {
        File projectDir = temp.newFolder("save-preset");
        OrientationPresetStore store =
                new OrientationPresetStore(projectDir.getAbsolutePath());
        OrientationBatchController controller =
                new OrientationBatchController(store, 1);
        FakeImage current = image(LH, transform(DEG_270, true, true));
        controller.bindCurrent(current, 0);

        OrientationPreset saved = controller.savePreset("Sideways");

        assertEquals(Arrays.asList(saved), controller.presets());
        OrientationPresetStore freshStore =
                new OrientationPresetStore(projectDir.getAbsolutePath());
        assertEquals(Arrays.asList(saved), freshStore.load());
    }

    @Test
    public void clearRuleStopsFurtherAutoApplication() throws Exception {
        OrientationBatchController controller = controller("clear-rule", 2);
        FakeImage source = image(LH, transform(DEG_90, false, true));
        controller.bindCurrent(source, 0);
        controller.setRule(ALL_LITERAL);
        controller.clearRule();

        FakeImage target = image(RH, OrientationTransformState.identity());
        controller.bindCurrent(target, 1);

        assertFalse(controller.applyActiveRuleOnOpen());
        assertTransform(OrientationManifestRow.RotationDegrees.DEG_0, false, false,
                target.state());
        assertTrue(target.appliedStates.isEmpty());
    }

    @Test
    public void ruleStatusUsesAbsoluteCurrentIndexForAppendRuns() throws Exception {
        OrientationBatchController controller = controller("append-count", 5);
        FakeImage source = image(LH, transform(DEG_90, false, false));
        controller.bindCurrent(source, 2);
        controller.setRule(ALL_LITERAL);

        assertTrue(controller.ruleStatusText().contains("2 remaining"));

        FakeImage finalImage = image(RH, OrientationTransformState.identity());
        controller.bindCurrent(finalImage, 4);

        assertTrue(controller.ruleStatusText().contains("0 remaining"));
    }

    private OrientationBatchController controller(String folderName, int totalImages)
            throws Exception {
        File projectDir = temp.newFolder(folderName);
        return new OrientationBatchController(
                new OrientationPresetStore(projectDir.getAbsolutePath()),
                totalImages);
    }

    private static FakeImage image(OrientationManifestRow.Hemisphere hemisphere,
                                   OrientationTransformState state) {
        return new FakeImage(hemisphere, state);
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

    private static final class FakeImage
            implements OrientationBatchController.CurrentImage {
        private final OrientationManifestRow.Hemisphere hemisphere;
        private OrientationTransformState state;
        private final List<OrientationTransformState> appliedStates =
                new ArrayList<OrientationTransformState>();

        FakeImage(OrientationManifestRow.Hemisphere hemisphere,
                  OrientationTransformState state) {
            this.hemisphere = hemisphere;
            this.state = state == null ? OrientationTransformState.identity() : state;
        }

        @Override
        public OrientationManifestRow.Hemisphere hemisphere() {
            return hemisphere;
        }

        @Override
        public OrientationTransformState state() {
            return state;
        }

        @Override
        public void applyState(OrientationTransformState next) {
            OrientationTransformState safeNext = next == null
                    ? OrientationTransformState.identity()
                    : next;
            appliedStates.add(safeNext);
            state = safeNext;
        }
    }
}
