package flash.pipeline.analyses.wizard;

import flash.pipeline.ui.PipelineDialog;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ThreeDObjectChainingTest {

    @Test
    public void spatialIntentOnlySetsRunSpatialFlag() {
        SimulatedWizard spatial = new SimulatedWizard(true);
        ThreeDObjectWizard.DerivedConfig spatialConfig = spatial.runAndMaybeLaunchSpatial();
        assertTrue(spatialConfig.runSpatial);

        SimulatedWizard notSpatial = new SimulatedWizard(false);
        ThreeDObjectWizard.DerivedConfig notSpatialConfig = notSpatial.runAndMaybeLaunchSpatial();
        assertFalse(notSpatialConfig.runSpatial);
    }

    private static final class SimulatedWizard extends ThreeDObjectWizard {
        private final boolean spatial;

        private SimulatedWizard(boolean spatial) {
            super(flash.pipeline.ui.wizard.WizardFlow.MainPanelBinding.NULL,
                    ThreeDObjectWizardTest.dapiIba1AbetaConfig(),
                    ThreeDObjectWizardTest.dapiIba1AbetaIdentities(),
                    null,
                    false);
            this.spatial = spatial;
        }

        @Override
        protected ScreenResult showScreen(Screen screen, int screenIndex, boolean finish) {
            if ("What are you trying to measure?".equals(screen.title())) {
                putAnswer("intent.spatial", Boolean.valueOf(spatial));
                putAnswer("intent.coloc", Boolean.FALSE);
                putAnswer("intent.process", Boolean.FALSE);
            }
            return ScreenResult.NEXT;
        }
    }
}
