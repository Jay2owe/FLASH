package flash.pipeline.ui.config;

import flash.pipeline.cellpose.CellposeRuntime;
import flash.pipeline.ui.preview.PreviewPairPanel;
import flash.pipeline.ui.variations.ParameterCombo;
import flash.pipeline.ui.variations.ParameterId;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Test;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Container;
import java.awt.image.IndexColorModel;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class CellposeParameterStageTest {

    @Test
    public void parsesAndRendersMethodTokenWithCompanionChannel() {
        String token = "cellpose:30.0:cyto3:0.4:0.0:gpu=false:chan2=1";

        CellposeParameterStage.Parameters params =
                CellposeParameterStage.parseMethod(token, true, 3, 0);

        assertEquals("cellpose:30.0:0.4:0.0:gpu=false:chan2=1:model=cellpose_cyto3",
                CellposeParameterStage.formatMethod(params));
    }

    @Test
    public void variationsButtonPresent_andDisabledWithoutPreview() {
        CellposeParameterStage stage = stage(
                new RecordingStore("cellpose:30.0:cyto3:0.4:0.0:gpu=false"),
                new RecordingPreviewAdapter());

        JComponent controls = stage.buildControls(context(), new RecordingActions());
        JButton variations = findButton(controls, "Parameter Variations...");

        assertNotNull(variations);
        assertFalse(variations.isEnabled());
        assertEquals("Run/prepare a preview before opening parameter variations.",
                variations.getToolTipText());

        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));

        assertTrue(variations.isEnabled());
    }

    @Test
    public void applyCombo_writesFieldsAndTriggersRefresh() throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        CellposeParameterStage stage = stage(
                new RecordingStore("cellpose:30.0:cyto3:0.4:0.0:gpu=false"),
                adapter);

        stage.buildControls(context(), new RecordingActions());
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));
        adapter.previewRuns = 0;

        stage.applyVariationComboForTest(ParameterCombo.builder()
                .put(ParameterId.DIAMETER, Double.valueOf(22.0d))
                .put(ParameterId.FLOW_THRESHOLD, Double.valueOf(0.6d))
                .put(ParameterId.CELLPROB_THRESHOLD, Double.valueOf(0.2d))
                .put(ParameterId.MODEL, "nuclei")
                .build());
        waitForPreviewRuns(adapter, 1);

        assertEquals("cellpose:22.0:0.6:0.2:gpu=false:model=cellpose_nuclei",
                stage.currentMethodForTest());
    }

    @Test
    public void unsupportedModelDropsCompanionChannel() {
        CellposeParameterStage.Parameters params =
                CellposeParameterStage.parseMethod(
                        "cellpose:12.0:nuclei:0.5:-1.0:gpu=true:chan2=1",
                        true,
                        3,
                        0);

        assertEquals(-1, params.secondChannelIndex);
        assertFalse(CellposeParameterStage.formatMethod(params).contains("chan2="));
    }

    @Test
    public void textFieldEditMarksPreviewStaleWithoutRunningPreview() {
        RecordingStore store = new RecordingStore("cellpose:30.0:cyto3:0.4:0.0:gpu=false");
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        RecordingActions actions = new RecordingActions();
        CellposeParameterStage stage = stage(store, adapter);

        stage.buildControls(context(), actions);
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));
        adapter.previewRuns = 0;

        stage.setDiameterForTest("44.0");

        assertTrue(stage.isPreviewStaleForTest());
        assertTrue(actions.status.contains("Preview"));
        assertTrue(actions.previewButtonStale);
        assertEquals("\u25CF Run Preview", actions.previewButton.getText());
        assertEquals("Field edits must not execute Cellpose preview",
                0, adapter.previewRuns);
    }

    @Test
    public void controlsUseCompactRowsWithRuntimeHints() {
        CellposeParameterStage stage = stage(
                new RecordingStore("cellpose:30.0:cyto3:0.4:0.0:gpu=false"),
                new RecordingPreviewAdapter());

        JComponent controls = stage.buildControls(context(), new RecordingActions());

        assertContainsText(controls, "Model");
        assertContainsText(controls, "Companion");
        assertContainsText(controls, "Use GPU");
        assertContainsText(controls, "Install GPU Support");
        assertContainsText(controls, "Detection:");
        assertContainsText(controls, "Diameter");
        assertContainsText(controls, "Flow threshold");
        assertContainsText(controls, "Cell probability");
        assertContainsText(controls, "Object size:");
        assertContainsText(controls, "cyto3: Recommended first-pass model");
        assertContainsText(controls, "Companion: optional second channel");
        assertContainsText(controls, "Runtime: Cellpose is not configured yet.");
        assertContainsText(controls, "Run Preview");
        assertFalse("Old duplicate help text should be removed",
                containsText(controls, "Edit parameters, then press"));
        assertTrue(stage.installGpuButtonReachableForTest());
    }

    @Test
    public void allParameterFieldsContributeToMethodToken() {
        CellposeParameterStage stage = stage(
                new RecordingStore("cellpose:30.0:cyto3:0.4:0.0:gpu=false"),
                new RecordingPreviewAdapter());

        stage.buildControls(context(), new RecordingActions());
        stage.setModelForTest("cyto2");
        stage.setCompanionForTest("C2 (Companion)");
        stage.setDiameterForTest("44");
        stage.setFlowForTest("0.6");
        stage.setCellprobForTest("0.2");
        stage.setUseGpuForTest(true);

        assertEquals("cellpose:44.0:0.6:0.2:gpu=true:chan2=1:model=cellpose_cyto2",
                stage.currentMethodForTest());
    }

    @Test
    public void hintsUpdateWhenModelAndCompanionChange() {
        CellposeParameterStage stage = stage(
                new RecordingStore("cellpose:30.0:cyto3:0.4:0.0:gpu=false"),
                new RecordingPreviewAdapter());

        stage.buildControls(context(), new RecordingActions());

        assertTrue(stage.modelHintTextForTest().contains("cyto3"));
        assertTrue(stage.companionHintTextForTest().contains("optional second channel"));
        assertEquals("Runtime: Cellpose is not configured yet.", stage.runtimeHintTextForTest());

        stage.setCompanionForTest("C2 (Companion)");
        assertTrue(stage.companionHintTextForTest().contains("using C2 (Companion)"));

        stage.setModelForTest("nuclei");
        assertTrue(stage.modelHintTextForTest().contains("nuclei"));
        assertTrue(stage.companionHintTextForTest().contains("not used"));
    }

    @Test
    public void previewUsesSelectedCompanionChannelOnlyWhenRequested() throws Exception {
        RecordingStore store = new RecordingStore("cellpose:30.0:cyto3:0.4:0.0:gpu=false:chan2=1");
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        RecordingActions actions = new RecordingActions();
        CellposeParameterStage stage = stage(store, adapter);

        stage.buildControls(context(), actions);
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));

        assertEquals(0, adapter.previewRuns);

        stage.runPreviewNowForTest();

        assertEquals(1, adapter.previewRuns);
        assertEquals(1, adapter.lastCompanionIndex);
        assertFalse(stage.isPreviewStaleForTest());
        assertNotNull(actions.adjustedPreview);
        assertEquals("Objects: 2 ready", actions.status);
        assertFalse(actions.previewButtonStale);
        assertEquals("Run Preview", actions.previewButton.getText());
        assertEquals(3, stage.largePreviewPaneCountForTest());
    }

    @Test
    public void sizeEditsAfterPreviewRelabelRemovedObjectsWithoutRerunning() throws Exception {
        RecordingStore store = new RecordingStore("cellpose:30.0:cyto3:0.4:0.0:gpu=false");
        RecordingSizeStore sizeStore = new RecordingSizeStore("0-Infinity");
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        RecordingActions actions = new RecordingActions();
        CellposeParameterStage stage = stage(store, sizeStore, adapter);

        stage.buildControls(context(), actions);
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));
        stage.runPreviewNowForTest();
        adapter.previewRuns = 0;

        stage.setSizeMinForTest("2");

        assertFalse(stage.isPreviewStaleForTest());
        assertEquals("Size edits must reuse cached label sizes",
                0, adapter.previewRuns);
        assertEquals("Objects: 1 kept; removed 1 small, 0 large", actions.status);
        assertEquals("Objects: 1 kept; removed 1 small, 0 large",
                stage.sizeCutoffSummaryForTest());
        assertFalse(actions.previewButtonStale);
        assertRemovedLabelUsesCutoffColor(actions.adjustedPreview, 1, 0xe53935);

        assertTrue(stage.lockIn(context()));
        assertEquals("2-Infinity", sizeStore.token);
    }

    @Test
    public void sourceToggleSwapsRawAndFilteredWithoutRunningPreview() {
        RecordingStore store = new RecordingStore("cellpose:30.0:cyto3:0.4:0.0:gpu=false");
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        CellposeParameterStage stage = stage(store, adapter);

        stage.buildControls(context(), new RecordingActions());
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));
        adapter.previewRuns = 0;

        assertEquals(1, adapter.rawSourceCreations);
        assertEquals(1, adapter.filteredSourceCreations);
        assertTrue(stage.currentSourceTitleForTest().startsWith("filtered"));
        assertEquals(2, stage.largePreviewPaneCountForTest());

        stage.selectRawSourceForTest();

        assertTrue(stage.currentSourceTitleForTest().startsWith("raw"));
        assertEquals(0, adapter.previewRuns);
    }

    @Test
    public void overlayToggleUsesSharedPreviewControls() throws Exception {
        RecordingStore store = new RecordingStore("cellpose:30.0:cyto3:0.4:0.0:gpu=false");
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        CellposeParameterStage stage = stage(store, adapter);

        stage.buildControls(context(), new RecordingActions());
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted",
                PreviewPairPanel.PreviewLayout.HORIZONTAL_SLIM));
        stage.runPreviewNowForTest();

        assertFalse(stage.objectOverlaySelectedForTest());

        stage.setShowOverlayForTest(true);

        assertTrue(stage.objectOverlaySelectedForTest());
    }

    @Test(timeout = 1000L)
    public void buildControlsReturnsBeforeRuntimeProbeFutureCompletes() {
        CompletableFuture<CellposeRuntime.Status> runtimeFuture =
                new CompletableFuture<CellposeRuntime.Status>();
        CellposeParameterStage stage = stage(
                new RecordingStore("cellpose:30.0:cyto3:0.4:0.0"),
                new RecordingPreviewAdapter(),
                new RecordingRuntimeAdapter(CellposeRuntime.Status.unknown(), runtimeFuture));

        stage.buildControls(context(), new RecordingActions());

        assertFalse(runtimeFuture.isDone());
        assertEquals("Runtime: Checking Cellpose...", stage.runtimeHintTextForTest());
    }

    @Test
    public void runtimeLabelUpdatesWhenAsyncProbeCompletes() throws Exception {
        CompletableFuture<CellposeRuntime.Status> runtimeFuture =
                new CompletableFuture<CellposeRuntime.Status>();
        CellposeParameterStage stage = stage(
                new RecordingStore("cellpose:30.0:cyto3:0.4:0.0"),
                new RecordingPreviewAdapter(),
                new RecordingRuntimeAdapter(CellposeRuntime.Status.unknown(), runtimeFuture));

        stage.buildControls(context(), new RecordingActions());
        runtimeFuture.complete(CellposeRuntime.probe(""));
        flushEdt();

        assertEquals("Runtime: Cellpose is not configured yet.", stage.runtimeHintTextForTest());
    }

    @Test
    public void runtimeLabelDoesNotUpdateAfterStageLeaves() throws Exception {
        CompletableFuture<CellposeRuntime.Status> runtimeFuture =
                new CompletableFuture<CellposeRuntime.Status>();
        CellposeParameterStage stage = stage(
                new RecordingStore("cellpose:30.0:cyto3:0.4:0.0"),
                new RecordingPreviewAdapter(),
                new RecordingRuntimeAdapter(CellposeRuntime.Status.unknown(), runtimeFuture));

        stage.buildControls(context(), new RecordingActions());
        stage.onLeave(context());
        runtimeFuture.complete(CellposeRuntime.probe(""));
        flushEdt();

        assertEquals("Runtime: Checking Cellpose...", stage.runtimeHintTextForTest());
    }

    @Test
    public void runtimeProbeCallbackDoesNotCaptureStageStrongly() throws Exception {
        Field[] fields = CellposeParameterStage.RuntimeProbeCallback.class.getDeclaredFields();
        boolean hasWeakStageReference = false;
        for (int i = 0; i < fields.length; i++) {
            Field field = fields[i];
            assertFalse("Runtime probe callback must not retain the enclosing stage",
                    "this$0".equals(field.getName()));
            if ("stageRef".equals(field.getName())) {
                hasWeakStageReference = WeakReference.class.equals(field.getType());
            }
        }
        assertTrue(hasWeakStageReference);
    }

    @Test
    public void restartKeepsCurrentEditedParametersAfterStageRebuild() {
        RecordingStore store = new RecordingStore("cellpose:30.0:cyto3:0.4:0.0:gpu=false");
        CellposeParameterStage stage = stage(store, new RecordingPreviewAdapter());
        ConfigQcContext context = context();

        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));
        stage.setDiameterForTest("44.0");

        stage.restartStage(context);
        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));

        assertTrue(stage.currentMethodForTest().startsWith("cellpose:44.0:0.4:0.0"));
        assertEquals("cellpose:30.0:cyto3:0.4:0.0:gpu=false", store.token);
    }

    private static CellposeParameterStage stage(RecordingStore store,
                                                RecordingPreviewAdapter adapter) {
        return stage(store, new RecordingSizeStore("0-Infinity"), adapter);
    }

    private static CellposeParameterStage stage(RecordingStore store,
                                                RecordingSizeStore sizeStore,
                                                RecordingPreviewAdapter adapter) {
        return stage(store, sizeStore, adapter, new RecordingRuntimeAdapter());
    }

    private static CellposeParameterStage stage(RecordingStore store,
                                                RecordingPreviewAdapter adapter,
                                                RecordingRuntimeAdapter runtimeAdapter) {
        return stage(store, new RecordingSizeStore("0-Infinity"), adapter, runtimeAdapter);
    }

    private static CellposeParameterStage stage(RecordingStore store,
                                                RecordingSizeStore sizeStore,
                                                RecordingPreviewAdapter adapter,
                                                RecordingRuntimeAdapter runtimeAdapter) {
        return new CellposeParameterStage(
                store,
                sizeStore,
                adapter,
                runtimeAdapter,
                Arrays.asList("Primary", "Companion", "Other"),
                0,
                false);
    }

    private static ConfigQcContext context() {
        return ConfigQcContext.fromImages(
                null,
                null,
                null,
                Arrays.asList(image("QC image")),
                Arrays.asList("Primary", "Companion", "Other"),
                0);
    }

    private static ImagePlus image(String title) {
        ImageStack stack = new ImageStack(3, 3);
        ByteProcessor processor = new ByteProcessor(3, 3);
        processor.set(1, 1, 12);
        stack.addSlice(processor);
        return new ImagePlus(title, stack);
    }

    private static void assertContainsText(Component root, String expected) {
        assertTrue("Missing helper text: " + expected, containsText(root, expected));
    }

    private static boolean containsText(Component component, String expected) {
        String text = null;
        if (component instanceof JLabel) {
            text = ((JLabel) component).getText();
        } else if (component instanceof AbstractButton) {
            text = ((AbstractButton) component).getText();
        } else if (component instanceof JTextComponent) {
            text = ((JTextComponent) component).getText();
        }
        if (text != null && text.contains(expected)) {
            return true;
        }
        if (component instanceof Container) {
            Component[] children = ((Container) component).getComponents();
            for (int i = 0; i < children.length; i++) {
                if (containsText(children[i], expected)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static JButton findButton(Container root, String text) {
        if (root == null || text == null) return null;
        for (Component component : root.getComponents()) {
            if (component instanceof JButton
                    && text.equals(((JButton) component).getText())) {
                return (JButton) component;
            }
            if (component instanceof Container) {
                JButton found = findButton((Container) component, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static final class RecordingStore implements CellposeParameterStage.ParameterStore {
        String token;

        RecordingStore(String token) {
            this.token = token;
        }

        @Override public String getMethodToken() {
            return token;
        }

        @Override public void save(String methodToken) {
            token = methodToken;
        }
    }

    private static final class RecordingPreviewAdapter implements CellposeParameterStage.PreviewAdapter {
        int rawSourceCreations;
        int filteredSourceCreations;
        int previewRuns;
        int lastCompanionIndex = -2;

        @Override public ImagePlus createRawSource(ConfigQcContext context) {
            rawSourceCreations++;
            ImagePlus source = context.getCurrentImagePlus().duplicate();
            source.setTitle("raw");
            return source;
        }

        @Override public ImagePlus createFilteredSource(ConfigQcContext context) {
            filteredSourceCreations++;
            ImagePlus source = context.getCurrentImagePlus().duplicate();
            source.setTitle("filtered");
            return source;
        }

        @Override public ImagePlus createFilteredCompanionSource(ConfigQcContext context, int channelIndex) {
            lastCompanionIndex = channelIndex;
            ImagePlus companion = context.getCurrentImagePlus().duplicate();
            companion.setTitle("companion");
            return companion;
        }

        @Override public ImagePlus runPreview(ImagePlus filteredSource,
                                              ImagePlus filteredCompanionSource,
                                              CellposeParameterStage.Parameters parameters) {
            previewRuns++;
            ByteProcessor processor = new ByteProcessor(4, 1);
            processor.set(0, 0, 1);
            processor.set(1, 0, 2);
            processor.set(2, 0, 2);
            processor.set(3, 0, 2);
            return new ImagePlus("labels", processor);
        }

        @Override public int countLabels(ImagePlus labelImage) {
            return (int) labelImage.getProcessor().getStats().max;
        }

        @Override public void close(ImagePlus image) {
            if (image != null) image.flush();
        }
    }

    private static final class RecordingSizeStore implements CellposeParameterStage.SizeStore {
        String token;

        RecordingSizeStore(String token) {
            this.token = token;
        }

        @Override public String get() {
            return token;
        }

        @Override public void set(String token) {
            this.token = token;
        }
    }

    private static final class RecordingRuntimeAdapter implements CellposeParameterStage.RuntimeAdapter {
        private final CellposeRuntime.Status cachedStatus;
        private final CompletableFuture<CellposeRuntime.Status> runtimeFuture;

        RecordingRuntimeAdapter() {
            this(CellposeRuntime.probe(""),
                    CompletableFuture.completedFuture(CellposeRuntime.probe("")));
        }

        RecordingRuntimeAdapter(CellposeRuntime.Status cachedStatus,
                                CompletableFuture<CellposeRuntime.Status> runtimeFuture) {
            this.cachedStatus = cachedStatus;
            this.runtimeFuture = runtimeFuture;
        }

        @Override public CellposeRuntime.Status cachedRuntimeStatus() {
            return cachedStatus;
        }

        @Override public CompletableFuture<CellposeRuntime.Status> probeRuntimeAsync() {
            return runtimeFuture;
        }

        @Override public boolean nvidiaGpuLikelyAvailable() {
            return false;
        }

        @Override public CellposeParameterStage.GpuInstallResult installGpuSupport() {
            return new CellposeParameterStage.GpuInstallResult(false, "not installed", "");
        }
    }

    private static final class RecordingActions implements ConfigQcActions {
        String status = "";
        ImagePlus adjustedPreview;
        JButton previewButton;
        boolean previewButtonStale;

        @Override public void setStatus(String text) {
            status = text;
        }

        @Override public void markPreviewStale(String text) {
            status = text;
        }

        @Override public void setAdjustedPreview(ImagePlus image, String text) {
            adjustedPreview = image;
            status = text;
        }

        @Override public void registerPreviewButton(JButton button) {
            previewButton = button;
            setPreviewButtonStale(true);
        }

        @Override public void setPreviewButtonStale(boolean stale) {
            previewButtonStale = stale;
            if (previewButton != null) {
                previewButton.setText(stale ? "\u25CF Run Preview" : "Run Preview");
            }
        }

        @Override public void nextImage() {
        }

        @Override public void skipCurrentImage() {
        }

        @Override public void restartStage() {
        }

        @Override public void cancel() {
        }
    }

    private static void assertRemovedLabelUsesCutoffColor(ImagePlus labelImage,
                                                          int label,
                                                          int expectedRgb) {
        assertNotNull(labelImage);
        assertTrue(labelImage.getProcessor().getColorModel() instanceof IndexColorModel);
        IndexColorModel model = (IndexColorModel) labelImage.getProcessor().getColorModel();
        int index = ((Math.max(1, label) - 1) % 255) + 1;
        int actual = (model.getRed(index) << 16)
                | (model.getGreen(index) << 8)
                | model.getBlue(index);
        assertEquals(expectedRgb, actual);
    }

    private static void waitForPreviewRuns(RecordingPreviewAdapter adapter,
                                           int expectedRuns) throws Exception {
        long deadline = System.currentTimeMillis() + 3000L;
        while (System.currentTimeMillis() < deadline && adapter.previewRuns < expectedRuns) {
            Thread.sleep(10L);
        }
        flushEdt();
        assertEquals(expectedRuns, adapter.previewRuns);
    }

    private static void flushEdt() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
            }
        });
    }
}
