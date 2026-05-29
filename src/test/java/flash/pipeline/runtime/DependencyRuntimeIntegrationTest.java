package flash.pipeline.runtime;

import flash.pipeline.FLASH_Pipeline;
import flash.pipeline.analyses.Analysis;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DependencyRuntimeIntegrationTest {

    private static String originalHeadlessProperty;
    private FakeUiHooks ui;

    @BeforeClass
    public static void forceHeadlessProperty() {
        originalHeadlessProperty = System.getProperty("java.awt.headless");
        System.setProperty("java.awt.headless", "true");
    }

    @AfterClass
    public static void restoreHeadlessProperty() {
        if (originalHeadlessProperty == null) {
            System.clearProperty("java.awt.headless");
        } else {
            System.setProperty("java.awt.headless", originalHeadlessProperty);
        }
    }

    @Before
    public void setUp() {
        ui = new FakeUiHooks();
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
    public void missingPoiReportsMissingBlocksExcelAndStillInitializesAnalysisMap() throws Exception {
        EnumMap<DependencyId, DependencyStatus> statuses =
                DependencyRuntimeTestSupport.withStatuses(
                        DependencyId.APACHE_POI_RUNTIME,
                        DependencyStatus.missing("Apache POI jars missing"));
        DependencyService service = DependencyRuntimeTestSupport.serviceWith(statuses);
        FeatureDependencyGate.configure(service, null);
        ui.headless = true;

        FLASH_Pipeline pipeline = new FLASH_Pipeline();
        invokeInitAnalyses(pipeline);
        Map<Integer, Analysis> analysisMap = analysisMap(pipeline);

        assertEquals(12, analysisMap.size());
        assertNotNull(analysisMap.get(FLASH_Pipeline.IDX_EXCEL_EXPORT));
        assertEquals(DependencyStatus.State.MISSING,
                service.getStatus(DependencyId.APACHE_POI_RUNTIME).getState());
        assertFalse(FeatureDependencyGate.gate(DependencyId.APACHE_POI_RUNTIME, "Excel Summary Export"));
        assertTrue(join(ui.logs).contains("Missing dependency: Apache POI runtime"));
        assertTrue(join(ui.logs).contains("Blocked analysis: Excel Summary Export"));
    }

    @Test
    public void threeDObjectAnalysisClassloadsWithoutMcib3dProbeUntilExplicitCall() throws Exception {
        Class<?> analysisClass = Class.forName(
                "flash.pipeline.analyses.ThreeDObjectAnalysis",
                true,
                Thread.currentThread().getContextClassLoader());
        Field cachedAvailability = analysisClass.getDeclaredField("mcib3dAvailable");
        cachedAvailability.setAccessible(true);
        cachedAvailability.set(null, null);

        analysisClass.getDeclaredConstructor().newInstance();

        assertNull(cachedAvailability.get(null));

        Method lazyProbe = analysisClass.getDeclaredMethod("isMcib3dAvailable");
        lazyProbe.setAccessible(true);
        Object result = lazyProbe.invoke(null);

        assertTrue(result instanceof Boolean);
        assertEquals(result, cachedAvailability.get(null));
    }

    @Test
    public void missingStarDistGateReferencesRuntimeAndOffersFixNow() {
        EnumMap<DependencyId, DependencyStatus> statuses =
                DependencyRuntimeTestSupport.withStatuses(
                        DependencyId.STARDIST_RUNTIME,
                        DependencyStatus.missing("TrackMate-StarDist jar missing"));
        FeatureDependencyGate.configure(DependencyRuntimeTestSupport.serviceWith(statuses), null);
        ui.headless = false;
        ui.nextAction = "close";

        assertFalse(FeatureDependencyGate.gate(DependencyId.STARDIST_RUNTIME, "StarDist segmentation"));

        assertEquals(1, ui.prompts.size());
        assertTrue(ui.prompts.get(0).plainMessage.contains("StarDist"));
        assertTrue(ui.prompts.get(0).buttonLabels.contains("Auto-Fix StarDist"));
    }

    @Test
    public void missingBioFormatsBlocksLifPathButLeavesOtherDependencyGatesUnaffected() throws Exception {
        EnumMap<DependencyId, DependencyStatus> statuses =
                DependencyRuntimeTestSupport.withStatuses(
                        DependencyId.BIO_FORMATS_RUNTIME,
                        DependencyStatus.missing("Missing runtime class: loci.plugins.BF"));
        FeatureDependencyGate.configure(DependencyRuntimeTestSupport.serviceWith(statuses), null);
        ui.headless = true;

        FLASH_Pipeline pipeline = new FLASH_Pipeline();
        invokeInitAnalyses(pipeline);
        assertNotNull(analysisMap(pipeline).get(4));

        assertFalse(FeatureDependencyGate.gate(DependencyId.BIO_FORMATS_RUNTIME, ".lif opening"));
        assertTrue(join(ui.logs).contains("Missing dependency: Bio-Formats / OME / SCIFIO runtime"));
        assertTrue(join(ui.logs).contains("Blocked analysis: .lif opening"));
        assertTrue(FeatureDependencyGate.gate(DependencyId.JTS_CORE, "Spatial Analysis"));
    }

    private static void invokeInitAnalyses(FLASH_Pipeline pipeline) throws Exception {
        Method initAnalyses = FLASH_Pipeline.class.getDeclaredMethod("initAnalyses");
        initAnalyses.setAccessible(true);
        initAnalyses.invoke(pipeline);
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, Analysis> analysisMap(FLASH_Pipeline pipeline) throws Exception {
        Field analysisMap = FLASH_Pipeline.class.getDeclaredField("analysisMap");
        analysisMap.setAccessible(true);
        return (Map<Integer, Analysis>) analysisMap.get(pipeline);
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

    private static final class PromptRecord {
        private final String plainMessage;
        private final String buttonLabels;

        PromptRecord(String plainMessage, String buttonLabels) {
            this.plainMessage = plainMessage;
            this.buttonLabels = buttonLabels;
        }
    }

    private static final class FakeUiHooks implements FeatureDependencyGate.UiHooks {
        private boolean headless;
        private String nextAction = "close";
        private final List<String> logs = new ArrayList<String>();
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
            logs.add(title + "\n" + message);
        }

        @Override
        public String showMissingDependencyDialog(String analysis,
                                                  String requirement,
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
            prompts.add(new PromptRecord(plainMessage, labels.toString()));
            return nextAction;
        }
    }
}
