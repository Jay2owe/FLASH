package flash.pipeline.analyses.wizard;

import flash.pipeline.runtime.DependencyFixResult;
import flash.pipeline.runtime.DependencyId;
import flash.pipeline.runtime.DependencyStatus;
import flash.pipeline.ui.wizard.WizardFlow;

import org.junit.Test;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class IntensitySpatialWizardTest {

    @Test
    public void noneIntentDisablesSpatialCleanly() {
        Map<String, Object> answers = new LinkedHashMap<String, Object>();
        answers.put("intensity.spatial.intent", IntensitySpatialWizard.INTENT_NONE);

        IntensitySpatialConfig config = IntensitySpatialWizard.deriveConfig(
                channels("DAPI", "IBA1"), new boolean[]{false, false}, 6, answers);

        assertFalse(config.isEnabled());
        assertTrue(config.getEnabledAnalyses().isEmpty());
        assertFalse(config.isMipEnabled());
        assertFalse(config.isNative3dEnabled());
    }

    @Test
    public void exploratoryIntentLeavesAdvancedAnalysesOffByDefault() {
        Map<String, Object> answers = new LinkedHashMap<String, Object>();
        answers.put("intensity.spatial.intent", IntensitySpatialWizard.INTENT_ALL);

        IntensitySpatialConfig config = IntensitySpatialWizard.deriveConfig(
                channels("DAPI", "IBA1"), new boolean[]{false, true}, 8, answers);

        assertTrue(config.isEnabled());
        assertTrue(config.getEnabledAnalyses().contains(IntensitySpatialConfig.AnalysisKey.PATCHINESS));
        assertTrue(config.getEnabledAnalyses().contains(IntensitySpatialConfig.AnalysisKey.CROSSMARK));
        assertTrue(config.getEnabledAnalyses().contains(IntensitySpatialConfig.AnalysisKey.DISTANCE_SHELL));
        assertFalse(config.getEnabledAnalyses().contains(IntensitySpatialConfig.AnalysisKey.PERIODICITY));
        assertFalse(config.isNative3dEnabled());
        assertTrue(config.isMipEnabled());
    }

    @Test
    public void channelLocksClearInvalidCrossChannelSelectionsWithoutBinarizing() {
        boolean[] binarization = new boolean[]{false};
        Map<String, Object> answers = new LinkedHashMap<String, Object>();
        answers.put("intensity.spatial.intent", IntensitySpatialWizard.INTENT_ALL);
        answers.put("intensity.spatial.analysis.patchiness", Boolean.TRUE);
        answers.put("intensity.spatial.analysis.crossmark", Boolean.TRUE);
        answers.put("intensity.spatial.analysis.distance_shell", Boolean.TRUE);

        IntensitySpatialConfig config = IntensitySpatialWizard.deriveConfig(
                channels("DAPI"), binarization, 1, answers);

        assertTrue(config.getEnabledAnalyses().contains(IntensitySpatialConfig.AnalysisKey.PATCHINESS));
        assertFalse(config.getEnabledAnalyses().contains(IntensitySpatialConfig.AnalysisKey.CROSSMARK));
        assertFalse(config.getEnabledAnalyses().contains(IntensitySpatialConfig.AnalysisKey.DISTANCE_SHELL));
        assertFalse(binarization[0]);
    }

    @Test
    public void native3dSelectionsRequireNativeModeAndEnoughSlices() {
        IntensitySpatialConfig nativeOff = IntensitySpatialConfig.builder()
                .enabled(true)
                .enabledAnalyses(EnumSet.of(
                        IntensitySpatialConfig.AnalysisKey.CROSSMARK,
                        IntensitySpatialConfig.AnalysisKey.CROSSMARK_3D))
                .native3dEnabled(false)
                .build()
                .validateForChannelSetup(2, new boolean[]{true, true});

        assertTrue(nativeOff.getEnabledAnalyses().contains(IntensitySpatialConfig.AnalysisKey.CROSSMARK));
        assertFalse(nativeOff.getEnabledAnalyses().contains(IntensitySpatialConfig.AnalysisKey.CROSSMARK_3D));

        Map<String, Object> answers = new LinkedHashMap<String, Object>();
        answers.put("intensity.spatial.intent", IntensitySpatialWizard.INTENT_ALL);
        answers.put("intensity.spatial.analysis.crossmark_3d", Boolean.TRUE);
        answers.put("intensity.spatial.native3d", Boolean.TRUE);

        IntensitySpatialConfig tooShallow = IntensitySpatialWizard.deriveConfig(
                channels("DAPI", "IBA1"), new boolean[]{true, true}, 4, answers);
        assertFalse(tooShallow.isNative3dEnabled());
        assertFalse(tooShallow.getEnabledAnalyses().contains(IntensitySpatialConfig.AnalysisKey.CROSSMARK_3D));

        IntensitySpatialConfig deepEnough = IntensitySpatialWizard.deriveConfig(
                channels("DAPI", "IBA1"), new boolean[]{true, true}, 8, answers);
        assertTrue(deepEnough.isNative3dEnabled());
        assertTrue(deepEnough.getEnabledAnalyses().contains(IntensitySpatialConfig.AnalysisKey.CROSSMARK_3D));
    }

    @Test
    public void presetConfigSeedsHeadlessWizardRoundTrip() {
        IntensitySpatialConfig preset = IntensitySpatialConfig.builder()
                .enabled(true)
                .enabledAnalyses(EnumSet.of(
                        IntensitySpatialConfig.AnalysisKey.PATCHINESS,
                        IntensitySpatialConfig.AnalysisKey.ENTROPY_MI))
                .mipEnabled(true)
                .shellCount(8)
                .build();

        IntensitySpatialWizard wizard = new IntensitySpatialWizard(
                WizardFlow.MainPanelBinding.NULL,
                channels("DAPI", "IBA1"),
                new boolean[]{false, true},
                8,
                preset,
                new FakeDependencies(),
                true);

        wizard.run();
        IntensitySpatialConfig derived = wizard.deriveCurrentConfig();

        assertTrue(derived.isEnabled());
        assertTrue(derived.getEnabledAnalyses().contains(IntensitySpatialConfig.AnalysisKey.PATCHINESS));
        assertTrue(derived.getEnabledAnalyses().contains(IntensitySpatialConfig.AnalysisKey.ENTROPY_MI));
        assertTrue(derived.isMipEnabled());
        assertEquals(8, derived.getShellCount());
    }

    @Test
    public void missingDependencyMakesAffectedToggleUnavailableOnly() {
        FakeDependencies dependencies = new FakeDependencies();
        dependencies.missing = DependencyId.COLOC2_RUNTIME;
        IntensitySpatialWizard wizard = new IntensitySpatialWizard(
                WizardFlow.MainPanelBinding.NULL,
                channels("DAPI", "IBA1"),
                new boolean[]{false, true},
                8,
                IntensitySpatialConfig.disabled(),
                dependencies,
                true);

        IntensitySpatialWizard.Availability crossmark =
                wizard.availabilityFor(IntensitySpatialConfig.AnalysisKey.CROSSMARK);
        IntensitySpatialWizard.Availability anisotropy =
                wizard.availabilityFor(IntensitySpatialConfig.AnalysisKey.ANISOTROPY);

        assertFalse(crossmark.available);
        assertSame(DependencyId.COLOC2_RUNTIME, crossmark.dependencyId);
        assertTrue(anisotropy.available);
    }

    private static String[] channels(String... names) {
        return names;
    }

    private static final class FakeDependencies implements IntensitySpatialWizard.DependencyActions {
        DependencyId missing;

        public DependencyStatus getStatus(DependencyId id) {
            return id == missing
                    ? DependencyStatus.missing(id.name() + " missing")
                    : DependencyStatus.present(id.name() + " present");
        }

        public DependencyFixResult runDialogAction(DependencyId id, String actionId) {
            return new DependencyFixResult(id, true, id != missing, false, "");
        }
    }
}
