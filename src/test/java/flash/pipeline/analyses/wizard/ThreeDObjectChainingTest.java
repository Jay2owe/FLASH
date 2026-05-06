package flash.pipeline.analyses.wizard;

import flash.pipeline.ui.PipelineDialog;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

public class ThreeDObjectChainingTest {

    @Test
    public void spatialWizardLaunchesOnlyWhenSpatialIntentIsTicked() {
        AtomicInteger launches = new AtomicInteger(0);
        SimulatedWizard spatial = new SimulatedWizard(true, launches);
        spatial.runAndMaybeLaunchSpatial();
        assertEquals(1, launches.get());

        SimulatedWizard notSpatial = new SimulatedWizard(false, launches);
        notSpatial.runAndMaybeLaunchSpatial();
        assertEquals(1, launches.get());
    }

    private static final class SimulatedWizard extends ThreeDObjectWizard {
        private final boolean spatial;

        private SimulatedWizard(boolean spatial, final AtomicInteger launches) {
            super(flash.pipeline.ui.wizard.WizardFlow.MainPanelBinding.NULL,
                    ThreeDObjectWizardTest.dapiIba1AbetaConfig(),
                    ThreeDObjectWizardTest.dapiIba1AbetaIdentities(),
                    null,
                    false,
                    new SpatialWizardLauncher() {
                        public void launch(DerivedConfig config) {
                            launches.incrementAndGet();
                        }
                    });
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
