package flash.pipeline.ui.config;

import flash.pipeline.ui.preview.PreviewPairPanel;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class FilterParameterStageTest {

    private static final String DEFAULT_MACRO =
            "run(\"Gaussian Blur...\", \"sigma=2 stack\");\n";
    private static final String CUSTOM_MACRO =
            "run(\"Median...\", \"radius=7 stack\");\n";
    private static final String AUTO_LOCAL_MACRO =
            "run(\"Auto Local Threshold\", \"method=Bernsen radius=15 parameter_1=0 parameter_2=0 white\");\n";

    @Test
    public void textFieldEditMarksPreviewStaleWithoutRunningFilter() {
        RecordingMacroStore store = new RecordingMacroStore(
                "Default",
                DEFAULT_MACRO);
        RecordingPreviewAdapter previewAdapter = new RecordingPreviewAdapter();
        RecordingActions actions = new RecordingActions();
        FilterParameterStage stage = new FilterParameterStage(
                Arrays.asList("Default", "Custom"), store, previewAdapter, null, null);

        stage.buildControls(context(), actions);
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));
        previewAdapter.previewRuns = 0;

        stage.setParameterForTest("sigma", "4");

        assertTrue(stage.isPreviewStaleForTest());
        assertTrue(actions.status.contains("Preview"));
        assertEquals("Field edits must not execute the filter preview",
                0, previewAdapter.previewRuns);
    }

    @Test
    public void previewRunsOnlyWhenExplicitlyRequested() throws Exception {
        RecordingMacroStore store = new RecordingMacroStore(
                "Default",
                DEFAULT_MACRO);
        RecordingPreviewAdapter previewAdapter = new RecordingPreviewAdapter();
        RecordingActions actions = new RecordingActions();
        FilterParameterStage stage = new FilterParameterStage(
                Arrays.asList("Default", "Custom"), store, previewAdapter, null, null);

        stage.buildControls(context(), actions);
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));

        assertEquals(0, previewAdapter.previewRuns);

        stage.runPreviewNowForTest();

        assertEquals(1, previewAdapter.previewRuns);
        assertFalse(stage.isPreviewStaleForTest());
        assertEquals("Filter preview complete.", actions.status);
        assertTrue(actions.adjustedPreviewSet);
    }

    @Test
    public void lockInWritesEditedMacroOnlyAfterLockIn() {
        RecordingMacroStore store = new RecordingMacroStore(
                "Default",
                DEFAULT_MACRO);
        FilterParameterStage stage = new FilterParameterStage(
                Arrays.asList("Default", "Custom"),
                store,
                new RecordingPreviewAdapter(),
                null,
                null);
        ConfigQcContext context = context();

        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));
        stage.setParameterForTest("sigma", "5");

        assertEquals("", store.savedMacro);

        assertTrue(stage.lockIn(context));

        assertEquals("Default", store.savedPreset);
        assertTrue(store.savedMacro.contains("sigma=5"));
    }

    @Test
    public void noSavedPresetDefaultsToDefaultFilterAndLoadsItsMacro() {
        RecordingMacroStore store = new RecordingMacroStore("", "", DEFAULT_MACRO);
        FilterParameterStage stage = new FilterParameterStage(
                Arrays.asList("Punctate Signal / High Background", "Default", "Custom"),
                store,
                new RecordingPreviewAdapter(),
                null,
                null);

        stage.buildControls(context(), new RecordingActions());

        assertEquals("Default", stage.selectedPresetForTest());
        assertEquals(DEFAULT_MACRO, stage.currentMacroForTest());
        assertEquals(0, store.initialMacroLoads);
        assertEquals(1, store.presetMacroLoads);
        assertEquals("Default", store.lastLoadedPreset);
    }

    @Test
    public void savedCustomPresetRemainsSelectedAndUsesInitialMacro() {
        RecordingMacroStore store = new RecordingMacroStore(
                "IBA1 cleanup filter", CUSTOM_MACRO, DEFAULT_MACRO);
        FilterParameterStage stage = new FilterParameterStage(
                Arrays.asList("Default", "IBA1 cleanup filter", "Custom"),
                store,
                new RecordingPreviewAdapter(),
                null,
                null);

        stage.buildControls(context(), new RecordingActions());

        assertEquals("IBA1 cleanup filter", stage.selectedPresetForTest());
        assertEquals(CUSTOM_MACRO, stage.currentMacroForTest());
        assertEquals(1, store.initialMacroLoads);
        assertEquals(0, store.presetMacroLoads);
    }

    @Test
    public void previewButtonImmediatelyRunsDefaultFilterWithoutPresetChange() throws Exception {
        RecordingMacroStore store = new RecordingMacroStore("", "", DEFAULT_MACRO);
        RecordingPreviewAdapter previewAdapter = new RecordingPreviewAdapter();
        RecordingActions actions = new RecordingActions();
        FilterParameterStage stage = new FilterParameterStage(
                Arrays.asList("Default", "Custom"), store, previewAdapter, null, null);

        stage.buildControls(context(), actions);
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));

        assertTrue(stage.previewButtonEnabledForTest());

        stage.runPreviewNowForTest();

        assertEquals(1, previewAdapter.previewRuns);
        assertEquals(DEFAULT_MACRO, previewAdapter.lastMacroContent);
        assertFalse(stage.isPreviewStaleForTest());
        assertEquals(1, store.presetMacroLoads);
    }

    // ── Fork state machine tests ─────────────────────────────────────────

    @Test
    public void fork_bundledLoadIsCleanAndReadOnly() {
        RecordingMacroStore store = new RecordingMacroStore("Default", DEFAULT_MACRO);
        FilterParameterStage stage = new FilterParameterStage(
                Arrays.asList("Default", "Custom"), store, new RecordingPreviewAdapter(), null, null);

        stage.buildControls(context(), new RecordingActions());

        assertFalse("dirty must be false on bundled load", stage.isDirtyForTest());
        assertFalse("save-as must be disabled when clean", stage.isSaveAsEnabledForTest());
        assertTrue("readOnlyBase must be true for a bundled preset",
                stage.isReadOnlyBaseForTest());
        assertEquals("Default", stage.renderedComboLabelForTest());
    }

    @Test
    public void fork_firstEditMarksDirtyAndRenamesCombo() {
        RecordingMacroStore store = new RecordingMacroStore("Default", DEFAULT_MACRO);
        FilterParameterStage stage = new FilterParameterStage(
                Arrays.asList("Default", "Custom"), store, new RecordingPreviewAdapter(), null, null);

        stage.buildControls(context(), new RecordingActions());
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));

        assertFalse(stage.isDirtyForTest());

        stage.setParameterForTest("sigma", "3.5");

        assertTrue("first edit must mark dirty", stage.isDirtyForTest());
        assertTrue("save-as must enable on first edit", stage.isSaveAsEnabledForTest());
        assertEquals("Default (modified)", stage.renderedComboLabelForTest());
    }

    @Test
    public void fork_manualRevertClearsDirty() {
        RecordingMacroStore store = new RecordingMacroStore("Default", DEFAULT_MACRO);
        FilterParameterStage stage = new FilterParameterStage(
                Arrays.asList("Default", "Custom"), store, new RecordingPreviewAdapter(), null, null);

        stage.buildControls(context(), new RecordingActions());
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));

        stage.setParameterForTest("sigma", "3.5");
        assertTrue(stage.isDirtyForTest());

        stage.setParameterForTest("sigma", "2");

        assertFalse("manual revert must clear dirty", stage.isDirtyForTest());
        assertFalse("save-as must disable when clean again", stage.isSaveAsEnabledForTest());
        assertEquals("Default", stage.renderedComboLabelForTest());
    }

    @Test
    public void fork_comboChangeWhileDirtyDiscardsWithoutPrompt() {
        Map<String, String> macros = new HashMap<String, String>();
        macros.put("Default", DEFAULT_MACRO);
        macros.put("Custom", CUSTOM_MACRO);
        MultiPresetMacroStore store = new MultiPresetMacroStore("Default", macros);
        FilterParameterStage stage = new FilterParameterStage(
                Arrays.asList("Default", "Custom"), store, new RecordingPreviewAdapter(), null, null);

        stage.buildControls(context(), new RecordingActions());
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));

        stage.setParameterForTest("sigma", "3.5");
        assertTrue(stage.isDirtyForTest());

        stage.setPresetForTest("Custom");

        assertFalse("combo change must discard the fork",
                stage.isDirtyForTest());
        assertFalse(stage.isSaveAsEnabledForTest());
        assertEquals("Custom", stage.selectedPresetForTest());
        assertEquals(CUSTOM_MACRO, stage.currentMacroForTest());
        assertEquals("Custom", stage.renderedComboLabelForTest());
    }

    @Test
    public void fork_saveAsRoundTripPersistsAndReselects() {
        RecordingMacroStore store = new RecordingMacroStore("Default", DEFAULT_MACRO);
        FilterParameterStage stage = new FilterParameterStage(
                Arrays.asList("Default", "Custom"), store, new RecordingPreviewAdapter(), null, null);

        stage.buildControls(context(), new RecordingActions());
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));

        stage.setParameterForTest("sigma", "3.5");
        assertTrue(stage.isDirtyForTest());

        stage.simulateSaveAsForTest("MyTuned");

        assertEquals("MyTuned", store.savedAsPresetName);
        assertTrue(store.savedAsMacro.contains("sigma=3.5"));
        assertEquals("MyTuned", stage.selectedPresetForTest());
        assertFalse("dirty must clear after save-as", stage.isDirtyForTest());
        assertFalse(stage.isSaveAsEnabledForTest());
        assertEquals("MyTuned", stage.renderedComboLabelForTest());
        assertFalse("custom presets are not bundled and so not read-only",
                stage.isReadOnlyBaseForTest());
    }

    @Test
    public void fork_editAfterSaveAsReforksCustom() {
        RecordingMacroStore store = new RecordingMacroStore("Default", DEFAULT_MACRO);
        FilterParameterStage stage = new FilterParameterStage(
                Arrays.asList("Default", "Custom"), store, new RecordingPreviewAdapter(), null, null);

        stage.buildControls(context(), new RecordingActions());
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));

        stage.setParameterForTest("sigma", "3.5");
        stage.simulateSaveAsForTest("MyTuned");
        assertFalse(stage.isDirtyForTest());

        stage.setParameterForTest("sigma", "5");

        assertTrue("editing after save-as must re-fork", stage.isDirtyForTest());
        assertEquals("MyTuned (modified)", stage.renderedComboLabelForTest());
        assertTrue(stage.isSaveAsEnabledForTest());
    }

    // ── Accordion tests ──────────────────────────────────────────────────

    @Test
    public void accordion_firstSectionExpandedOnEnter() {
        String multiSectionMacro =
                "// ===== Step 1 =====\n"
                + "run(\"Gaussian Blur...\", \"sigma=2 stack\");\n"
                + "// ===== Step 2 =====\n"
                + "run(\"Median...\", \"radius=3 stack\");\n";
        RecordingMacroStore store = new RecordingMacroStore("Default", multiSectionMacro);
        FilterParameterStage stage = new FilterParameterStage(
                Arrays.asList("Default", "Custom"), store, new RecordingPreviewAdapter(), null, null);

        stage.buildControls(context(), new RecordingActions());

        assertTrue("must have at least 2 sections", stage.sectionCountForTest() >= 2);
        assertTrue("first section expanded on enter", stage.isSectionExpandedForTest(0));
        assertFalse("subsequent sections collapsed on enter", stage.isSectionExpandedForTest(1));
    }

    @Test
    public void accordion_advancedKeysGateHiddenParametersByDefault() {
        RecordingMacroStore store = new RecordingMacroStore("Default", AUTO_LOCAL_MACRO);
        FilterParameterStage stage = new FilterParameterStage(
                Arrays.asList("Default", "Custom"), store, new RecordingPreviewAdapter(), null, null);

        stage.buildControls(context(), new RecordingActions());

        assertTrue("method must be visible by default",
                stage.hasFieldBindingForTest("method"));
        assertTrue("radius must be visible by default",
                stage.hasFieldBindingForTest("radius"));
        assertFalse("parameter_1 must be hidden behind Advanced…",
                stage.hasFieldBindingForTest("parameter_1"));
        assertFalse("parameter_2 must be hidden behind Advanced…",
                stage.hasFieldBindingForTest("parameter_2"));
    }

    @Test
    public void openInCanvasButtonIsRenamedAndStillVisible() {
        RecordingMacroStore store = new RecordingMacroStore("Default", DEFAULT_MACRO);
        FilterParameterStage stage = new FilterParameterStage(
                Arrays.asList("Default", "Custom"), store, new RecordingPreviewAdapter(), null, null);

        stage.buildControls(context(), new RecordingActions());

        assertNotNull(stage.customBuilderButtonTextForTest());
        assertTrue("button label must be renamed to 'Open in canvas...'",
                stage.customBuilderButtonTextForTest().toLowerCase().contains("open in canvas"));
    }

    private static ConfigQcContext context() {
        return ConfigQcContext.fromImages(
                null,
                null,
                null,
                Arrays.asList(stack("QC image")),
                Arrays.asList("IBA1"),
                0);
    }

    private static ImagePlus stack(String title) {
        ImageStack stack = new ImageStack(3, 3);
        ByteProcessor processor = new ByteProcessor(3, 3);
        processor.set(1, 1, 12);
        stack.addSlice(processor);
        return new ImagePlus(title, stack);
    }

    private static final class RecordingMacroStore implements FilterParameterStage.MacroStore {
        final String initialPreset;
        final String initialMacro;
        final String presetMacro;
        String savedPreset = "";
        String savedMacro = "";
        String savedAsPresetName = "";
        String savedAsMacro = "";
        String lastLoadedPreset = "";
        int initialMacroLoads;
        int presetMacroLoads;

        RecordingMacroStore(String initialPreset, String macro) {
            this(initialPreset, macro, macro);
        }

        RecordingMacroStore(String initialPreset, String initialMacro, String presetMacro) {
            this.initialPreset = initialPreset;
            this.initialMacro = initialMacro;
            this.presetMacro = presetMacro;
        }

        @Override public String getInitialPreset() {
            return initialPreset;
        }

        @Override public String loadInitialMacro() {
            initialMacroLoads++;
            return initialMacro;
        }

        @Override public String loadPresetMacro(String presetName) {
            presetMacroLoads++;
            lastLoadedPreset = presetName;
            return presetMacro;
        }

        @Override public void save(String presetName, String macroContent) {
            savedPreset = presetName;
            savedMacro = macroContent;
        }

        @Override public void saveAsPreset(String presetName, String macroContent) {
            savedAsPresetName = presetName;
            savedAsMacro = macroContent;
        }
    }

    private static final class MultiPresetMacroStore implements FilterParameterStage.MacroStore {
        final String initialPreset;
        final Map<String, String> macros;

        MultiPresetMacroStore(String initialPreset, Map<String, String> macros) {
            this.initialPreset = initialPreset;
            this.macros = macros;
        }

        @Override public String getInitialPreset() {
            return initialPreset;
        }

        @Override public String loadInitialMacro() {
            return macros.get(initialPreset);
        }

        @Override public String loadPresetMacro(String presetName) {
            return macros.containsKey(presetName) ? macros.get(presetName) : "";
        }

        @Override public void save(String presetName, String macroContent) {
            macros.put(presetName, macroContent);
        }

        @Override public void saveAsPreset(String presetName, String macroContent) {
            macros.put(presetName, macroContent);
        }
    }

    private static final class RecordingPreviewAdapter implements FilterParameterStage.PreviewAdapter {
        int previewRuns;
        String lastMacroContent = "";

        @Override public ImagePlus createSource(ConfigQcContext context) {
            return context.getCurrentImagePlus().duplicate();
        }

        @Override public ImagePlus createFilteredPreview(ImagePlus source, String macroContent) {
            previewRuns++;
            lastMacroContent = macroContent;
            ImagePlus preview = source.duplicate();
            preview.setTitle("preview");
            return preview;
        }

        @Override public void close(ImagePlus image) {
            if (image != null) image.flush();
        }
    }

    private static final class RecordingActions implements ConfigQcActions {
        String status = "";
        boolean adjustedPreviewSet;

        @Override public void setStatus(String text) {
            status = text;
        }

        @Override public void markPreviewStale(String text) {
            status = text;
        }

        @Override public void setAdjustedPreview(ImagePlus image, String text) {
            adjustedPreviewSet = image != null;
            status = text;
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
}
