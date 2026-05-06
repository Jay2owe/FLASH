package flash.pipeline.runtime;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FeatureDependencyGateTest {

    private FakeUiHooks ui;
    private CountingOpener opener;

    @BeforeClass
    public static void forceHeadlessProperty() {
        System.setProperty("java.awt.headless", "true");
    }

    @Before
    public void setUp() {
        ui = new FakeUiHooks();
        opener = new CountingOpener();
        FeatureDependencyGate.setUiMode(false);
        FeatureDependencyGate.setUiHooksForTesting(ui);
    }

    @After
    public void tearDown() {
        FeatureDependencyGate.configure(new DependencyService(), null);
        FeatureDependencyGate.setUiHooksForTesting(null);
        FeatureDependencyGate.setUiMode(false);
    }

    @Test
    public void presentDependencyPassesWithoutUiSideEffects() {
        FeatureDependencyGate.configure(
                DependencyRuntimeTestSupport.serviceWith(DependencyRuntimeTestSupport.allPresent()),
                opener);
        ui.headless = false;

        assertTrue(FeatureDependencyGate.gate(DependencyId.APACHE_POI_RUNTIME, "Excel Summary Export"));

        assertEquals(0, ui.prompts.size());
        assertEquals(0, ui.logs.size());
        assertEquals(0, ui.messages.size());
        assertEquals(0, opener.calls);
    }

    @Test
    public void presentDependencyChecksReuseStatusSnapshotAcrossGateCalls() {
        CountingStatusProvider provider = new CountingStatusProvider(DependencyRuntimeTestSupport.allPresent());
        FeatureDependencyGate.configure(new DependencyService(provider), opener);
        ui.headless = false;

        assertTrue(FeatureDependencyGate.gate(DependencyId.APACHE_POI_RUNTIME, "Excel Summary Export"));
        assertTrue(FeatureDependencyGate.gate(DependencyId.BIO_FORMATS_RUNTIME, "3D Deconvolution"));

        assertEquals(1, provider.calls);
        assertEquals(DependencyId.values().length, provider.lastSpecCount);
        assertEquals(0, ui.prompts.size());
        assertEquals(0, opener.calls);
    }

    @Test
    public void missingDependencyInHeadlessModeLogsClearMessageAndDoesNotPrompt() {
        EnumMap<DependencyId, DependencyStatus> statuses =
                DependencyRuntimeTestSupport.withStatuses(
                        DependencyId.APACHE_POI_RUNTIME,
                        DependencyStatus.missing("poi-3.17.jar missing"));
        FeatureDependencyGate.configure(DependencyRuntimeTestSupport.serviceWith(statuses), opener);
        ui.headless = true;

        assertFalse(FeatureDependencyGate.gate(DependencyId.APACHE_POI_RUNTIME, "Excel Summary Export"));

        assertEquals(0, ui.prompts.size());
        assertTrue(join(ui.logs).contains("Blocked feature: Excel Summary Export"));
        assertTrue(join(ui.logs).contains("Missing dependency: Apache POI runtime"));
        assertTrue(join(ui.logs).contains("poi-3.17.jar missing"));
        assertEquals(0, opener.calls);
    }

    @Test
    public void missingNonFixableDependencyOffersOpenDependenciesOnly() {
        EnumMap<DependencyId, DependencyStatus> statuses =
                DependencyRuntimeTestSupport.withStatuses(
                        DependencyId.MCIB3D_CORE,
                        DependencyStatus.missing("mcib3d-core classes missing"));
        FeatureDependencyGate.configure(DependencyRuntimeTestSupport.serviceWith(statuses), opener);
        ui.headless = false;
        ui.nextAction = "open_dependencies";

        assertFalse(FeatureDependencyGate.gate(DependencyId.MCIB3D_CORE, "3D morphometry"));

        assertEquals(1, ui.prompts.size());
        assertEquals("Open Dependencies,Close", ui.prompts.get(0).buttonLabels);
        assertFalse(ui.prompts.get(0).buttonLabels.contains("Fix Now"));
        assertTrue(ui.prompts.get(0).plainMessage.contains("mcib3d-core"));
        assertEquals(1, opener.calls);
    }

    private static String join(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(value);
        }
        return sb.toString();
    }

    private static final class CountingOpener implements FeatureDependencyGate.DependenciesDialogOpener {
        private int calls;

        @Override
        public void openDependenciesDialog() {
            calls++;
        }
    }

    private static final class CountingStatusProvider implements DependencyService.StatusSnapshotProvider {
        private final EnumMap<DependencyId, DependencyStatus> statuses;
        private int calls;
        private int lastSpecCount;

        CountingStatusProvider(EnumMap<DependencyId, DependencyStatus> statuses) {
            this.statuses = statuses;
        }

        @Override
        public EnumMap<DependencyId, DependencyStatus> snapshot(List<DependencySpec> specs) {
            calls++;
            lastSpecCount = specs == null ? 0 : specs.size();
            return new EnumMap<DependencyId, DependencyStatus>(statuses);
        }
    }

    private static final class PromptRecord {
        private final String feature;
        private final String plainMessage;
        private final String buttonLabels;

        PromptRecord(String feature, String plainMessage, String buttonLabels) {
            this.feature = feature;
            this.plainMessage = plainMessage;
            this.buttonLabels = buttonLabels;
        }
    }

    private static final class FakeUiHooks implements FeatureDependencyGate.UiHooks {
        private boolean headless;
        private String nextAction = "close";
        private final List<String> logs = new ArrayList<String>();
        private final List<String> messages = new ArrayList<String>();
        private final List<PromptRecord> prompts = new ArrayList<PromptRecord>();

        @Override
        public boolean isHeadless() {
            return headless;
        }

        @Override
        public void log(String message) {
            logs.add(message);
        }

        @Override
        public void showMessage(String title, String message) {
            messages.add(title + "\n" + message);
        }

        @Override
        public String showMissingDependencyDialog(String feature,
                                                  DependencySpec spec,
                                                  DependencyStatus status,
                                                  String plainMessage,
                                                  List<FeatureDependencyGate.GateAction> actions) {
            StringBuilder labels = new StringBuilder();
            for (FeatureDependencyGate.GateAction action : actions) {
                if (labels.length() > 0) {
                    labels.append(',');
                }
                labels.append(action.getLabel());
            }
            prompts.add(new PromptRecord(feature, plainMessage, labels.toString()));
            return nextAction;
        }
    }
}
