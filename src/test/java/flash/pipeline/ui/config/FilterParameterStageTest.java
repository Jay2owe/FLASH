package flash.pipeline.ui.config;

import flash.pipeline.image.FilterMacroParser.OpType;
import flash.pipeline.image.dag.Combiner;
import flash.pipeline.image.dag.CombinerOp;
import flash.pipeline.image.dag.DagIR;
import flash.pipeline.image.dag.DagLine;
import flash.pipeline.image.dag.DagNode;
import flash.pipeline.image.dag.DagToIjmEmitter;
import flash.pipeline.ui.preview.PreviewPairPanel;
import flash.pipeline.ui.sandbox.FilterCatalog;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Test;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
    public void restartKeepsCurrentEditedMacroAfterStageRebuild() {
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
        stage.setParameterForTest("sigma", "6");

        stage.restartStage(context);
        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));

        assertTrue(stage.currentMacroForTest().contains("sigma=6"));
        assertEquals("", store.savedMacro);
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
    public void presetDescriptionSitsLeftAlignedUnderFilterDropdown() {
        RecordingMacroStore store = new RecordingMacroStore("Default", DEFAULT_MACRO);
        FilterParameterStage.PresetDescriptionProvider descriptions =
                new FilterParameterStage.PresetDescriptionProvider() {
                    @Override public String describe(String presetName) {
                        return "Default filter explanation";
                    }
                };
        FilterParameterStage stage = new FilterParameterStage(
                Arrays.asList("Default", "Custom"),
                store,
                new RecordingPreviewAdapter(),
                null,
                descriptions);

        JComponent controls = stage.buildControls(context(), new RecordingActions());
        JLabel description = findLabel(controls, "Default filter explanation");

        assertNotNull(description);
        assertEquals(SwingConstants.LEFT, description.getHorizontalAlignment());
        assertTrue(description.getParent().getLayout() instanceof GridBagLayout);
        GridBagConstraints constraints = ((GridBagLayout) description.getParent().getLayout())
                .getConstraints(description);
        assertEquals(1, constraints.gridx);
        assertEquals(1, constraints.gridy);
        assertEquals(GridBagConstraints.HORIZONTAL, constraints.fill);
    }

    @Test
    public void controlsUseTallerExpandableScrollableAccordion() {
        RecordingMacroStore store = new RecordingMacroStore("Default", TWO_STEP_MACRO);
        FilterParameterStage stage = new FilterParameterStage(
                Arrays.asList("Default", "Custom"), store, new RecordingPreviewAdapter(), null, null);

        stage.buildControls(context(), new RecordingActions());

        JScrollPane scroll = stage.parameterScrollPaneForTest();
        assertNotNull("accordion must be wrapped in a scroll pane", scroll);
        assertTrue("scroll pane must wrap the accordion panel",
                stage.parameterScrollWrapsParameterPanelForTest());
        assertFalse("accordion panel must not keep its old titled border",
                stage.parameterPanelHasBorderForTest());
        assertEquals(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                scroll.getVerticalScrollBarPolicy());
        assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
                scroll.getHorizontalScrollBarPolicy());
        assertTrue("filter controls should use the dialog's expandable control area",
                stage.controlsCanExpand());
        Dimension preferred = scroll.getPreferredSize();
        assertTrue("accordion preferred height should fit its loaded content",
                preferred.height >= stage.parameterPanelPreferredHeightForTest());
    }

    @Test
    public void accordionPreferredHeightGrowsWithLoadedDefaultContent() {
        RecordingMacroStore store = new RecordingMacroStore("Default", manyStepMacro(24));
        FilterParameterStage stage = new FilterParameterStage(
                Arrays.asList("Default", "Custom"), store, new RecordingPreviewAdapter(), null, null);

        stage.buildControls(context(), new RecordingActions());

        Dimension preferred = stage.parameterScrollPaneForTest().getPreferredSize();
        assertTrue("accordion preferred height should not be capped at the old 380px limit",
                preferred.height > 390);
        assertTrue("accordion preferred height should fit its loaded content",
                preferred.height >= stage.parameterPanelPreferredHeightForTest());
    }

    @Test
    public void previewButtonRegistersAndTracksStaleCycle() throws Exception {
        RecordingMacroStore store = new RecordingMacroStore("Default", DEFAULT_MACRO);
        RecordingPreviewAdapter previewAdapter = new RecordingPreviewAdapter();
        RecordingActions actions = new RecordingActions();
        FilterParameterStage stage = new FilterParameterStage(
                Arrays.asList("Default", "Custom"), store, previewAdapter, null, null);

        stage.buildControls(context(), actions);
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));

        assertNotNull("preview button must be registered with dialog actions",
                actions.registeredPreviewButton);
        assertEquals("Run Preview", stage.previewButtonTextForTest());
        assertTrue("newly entered filter previews are stale",
                actions.previewButtonStale);

        stage.runPreviewNowForTest();

        assertFalse("successful preview clears the stale flag",
                actions.previewButtonStale);

        stage.setParameterForTest("sigma", "4");

        assertTrue("editing parameters marks the preview stale again",
                actions.previewButtonStale);
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
    public void asyncPresetOptionRefreshKeepsSelectionAndDoesNotLoadMacro() throws Exception {
        RecordingMacroStore store = new RecordingMacroStore(
                "Default", DEFAULT_MACRO, CUSTOM_MACRO);
        FilterParameterStage stage = new FilterParameterStage(
                Arrays.asList("Default", "Custom"),
                store,
                new RecordingPreviewAdapter(),
                null,
                null);

        stage.buildControls(context(), new RecordingActions());
        store.presetMacroLoads = 0;
        int generation = stage.beginPresetOptionsRefresh();

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                stage.refreshPresetOptionsIfCurrent(generation,
                        Arrays.asList("Default", "IBA1 cleanup filter", "Custom"), "Default");
            }
        });

        assertEquals("Default", stage.selectedPresetForTest());
        assertTrue(stage.hasPresetOptionForTest("IBA1 cleanup filter"));
        assertEquals("model replacement must not fire presetChanged",
                0, store.presetMacroLoads);
    }

    @Test
    public void staleAsyncPresetOptionRefreshIsIgnoredAfterLeave() throws Exception {
        RecordingMacroStore store = new RecordingMacroStore("Default", DEFAULT_MACRO);
        FilterParameterStage stage = new FilterParameterStage(
                Arrays.asList("Default", "Custom"),
                store,
                new RecordingPreviewAdapter(),
                null,
                null);
        ConfigQcContext context = context();

        stage.buildControls(context, new RecordingActions());
        int generation = stage.beginPresetOptionsRefresh();
        stage.onLeave(context);
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                stage.refreshPresetOptionsIfCurrent(generation,
                        Arrays.asList("Default", "Stale cleanup", "Custom"), "Default");
            }
        });

        assertFalse(stage.hasPresetOptionForTest("Stale cleanup"));
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
    public void presetChangeRebuildsAccordionForNewMacro() {
        Map<String, String> macros = new HashMap<String, String>();
        macros.put("Default", DEFAULT_MACRO);
        macros.put("Two step", TWO_STEP_MACRO);
        MultiPresetMacroStore store = new MultiPresetMacroStore("Default", macros);
        FilterParameterStage stage = new FilterParameterStage(
                Arrays.asList("Default", "Two step", "Custom"),
                store, new RecordingPreviewAdapter(), null, null);

        stage.buildControls(context(), new RecordingActions());

        assertEquals(1, stage.sectionCountForTest());

        stage.setPresetForTest("Two step");

        assertEquals("Two step", stage.selectedPresetForTest());
        assertEquals(2, stage.sectionCountForTest());
        assertTrue(stage.currentMacroForTest().contains("run(\"Median..."));
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

    // ── Stage 04 inline structural editing ───────────────────────────────

    private static final String TWO_STEP_MACRO =
            "run(\"Gaussian Blur...\", \"sigma=2 stack\");\n"
            + "run(\"Median...\", \"radius=3 stack\");\n";

    @Test
    public void add_appendsToEndAndExpandsRow() {
        RecordingMacroStore store = new RecordingMacroStore("Default", DEFAULT_MACRO);
        FilterParameterStage stage = new FilterParameterStage(
                Arrays.asList("Default", "Custom"), store, new RecordingPreviewAdapter(), null, null);

        stage.buildControls(context(), new RecordingActions());
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));

        int sectionsBefore = stage.sectionCountForTest();
        stage.simulateAddFilterForTest("Median");

        int sectionsAfter = stage.sectionCountForTest();
        assertEquals("appendNode must add exactly one new accordion section",
                sectionsBefore + 1, sectionsAfter);
        assertTrue("appended row must be auto-expanded",
                stage.isSectionExpandedForTest(sectionsAfter - 1));
        assertTrue("currentMacro must include the appended Median run() line",
                stage.currentMacroForTest().contains("run(\"Median..."));
    }

    @Test
    public void fieldEditAfterInlineBuilderMutationDoesNotTreatDuplicateTitleAsStepParameter() {
        RecordingMacroStore store = new RecordingMacroStore("Default", DEFAULT_MACRO);
        FilterParameterStage stage = new FilterParameterStage(
                Arrays.asList("Default", "Custom"), store, new RecordingPreviewAdapter(), null, null);

        stage.buildControls(context(), new RecordingActions());
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));

        stage.simulateAddFilterForTest("Subtract Background");
        stage.setParameterForTest("sigma", "4");

        String macroAfterEdit = stage.currentMacroForTest();
        assertTrue("Gaussian Blur must keep its edited sigma argument",
                macroAfterEdit.contains("run(\"Gaussian Blur...\", \"sigma=4 stack\");"));
        assertTrue("Subtract Background must keep its rolling argument",
                macroAfterEdit.contains("run(\"Subtract Background...\", \"rolling=50 stack\");"));
        assertFalse("Emitter setup title must not be copied onto Gaussian Blur",
                macroAfterEdit.contains("run(\"Gaussian Blur...\", \"title=line_A"));

        stage.simulateReorderForTest(0, 1);

        assertEquals("4", stage.parameterFieldValueForTest("sigma"));
        assertEquals("50", stage.parameterFieldValueForTest("rolling"));
    }

    @Test
    public void legacyAddFailureLeavesCurrentMacroUnchanged() {
        RecordingMacroStore store = new RecordingMacroStore("Default", DEFAULT_MACRO);
        RecordingActions actions = new RecordingActions();
        FilterParameterStage stage = new FilterParameterStage(
                Arrays.asList("Default", "Custom"), store, new RecordingPreviewAdapter(), null, null);

        stage.buildControls(context(), actions);
        String before = stage.currentMacroForTest();

        stage.simulateAddFilterForTest(legacyEntry("Plugin Filter"));

        assertEquals("legacy add without safe Recorder capture must not mutate currentMacro",
                before, stage.currentMacroForTest());
        assertFalse("failed legacy add must not append a blank plugin run line",
                stage.currentMacroForTest().contains("Plugin Filter"));
        assertTrue("failed legacy add should explain why the command was not added",
                actions.status.contains("parameter capture")
                        || actions.status.contains("preview image")
                        || actions.status.contains("Command was not added"));
    }

    @Test
    public void add_betweenSteps_dragReordersUnderlyingDag() {
        RecordingMacroStore store = new RecordingMacroStore("Default", TWO_STEP_MACRO);
        FilterParameterStage stage = new FilterParameterStage(
                Arrays.asList("Default", "Custom"), store, new RecordingPreviewAdapter(), null, null);

        stage.buildControls(context(), new RecordingActions());
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));

        String before = stage.currentMacroForTest();
        assertTrue(before.indexOf("run(\"Gaussian Blur") < before.indexOf("run(\"Median"));

        stage.simulateReorderForTest(0, 1);

        String after = stage.currentMacroForTest();
        assertTrue("Reorder must place Median before Gaussian Blur in emitted macro",
                after.indexOf("run(\"Median") < after.indexOf("run(\"Gaussian Blur"));
    }

    @Test
    public void eye_disablesNodeAndRemovesFromEmittedIjm() {
        RecordingMacroStore store = new RecordingMacroStore("Default", TWO_STEP_MACRO);
        FilterParameterStage stage = new FilterParameterStage(
                Arrays.asList("Default", "Custom"), store, new RecordingPreviewAdapter(), null, null);

        stage.buildControls(context(), new RecordingActions());
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));

        // Disable the Gaussian Blur step (section index 0).
        stage.simulateEyeToggleForTest(0);

        String currentMacro = stage.currentMacroForTest();
        assertFalse("Disabled step's run() must be absent from currentMacro",
                currentMacro.contains("run(\"Gaussian Blur..."));
        assertTrue("Embedded DAG JSON must record the disabled flag for round-trip",
                currentMacro.contains("\"disabled\":true"));
        assertTrue("Median step must remain in currentMacro",
                currentMacro.contains("run(\"Median..."));
    }

    @Test
    public void delete_removesNodeAndRebuildsAccordion() {
        RecordingMacroStore store = new RecordingMacroStore("Default", TWO_STEP_MACRO);
        FilterParameterStage stage = new FilterParameterStage(
                Arrays.asList("Default", "Custom"), store, new RecordingPreviewAdapter(), null, null);

        stage.buildControls(context(), new RecordingActions());
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));

        int sectionsBefore = stage.sectionCountForTest();
        assertEquals(2, sectionsBefore);

        stage.simulateDeleteForTest(1);

        int sectionsAfter = stage.sectionCountForTest();
        assertEquals("Delete must remove exactly one accordion section",
                sectionsBefore - 1, sectionsAfter);
        String currentMacro = stage.currentMacroForTest();
        assertFalse("Median run() must be absent after delete",
                currentMacro.contains("run(\"Median..."));
        assertTrue("Gaussian Blur run() must remain after deleting Median",
                currentMacro.contains("run(\"Gaussian Blur..."));
    }

    @Test
    public void branched_showsBannerAndDisablesStructuralControls() {
        String branchedMacro = buildBranchedFixtureMacro();
        Map<String, String> macros = new HashMap<String, String>();
        macros.put("Default", DEFAULT_MACRO);
        macros.put("Branched", branchedMacro);
        MultiPresetMacroStore store = new MultiPresetMacroStore("Default", macros);

        FilterParameterStage stage = new FilterParameterStage(
                Arrays.asList("Default", "Branched", "Custom"),
                store, new RecordingPreviewAdapter(), null, null);

        stage.buildControls(context(), new RecordingActions());
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));

        // Sanity: Default preset is linear -> banner hidden.
        assertFalse(stage.isBranchedBannerVisibleForTest());
        assertTrue(stage.isAddFilterEnabledForTest());

        stage.setPresetForTest("Branched");

        assertFalse("Loaded preset must be detected as non-linear",
                stage.isLinearForTest());
        assertTrue("Branched-preset banner must be visible",
                stage.isBranchedBannerVisibleForTest());
        assertFalse("+ Add filter… must be disabled on branched DAGs",
                stage.isAddFilterEnabledForTest());
        assertTrue("Custom macro button must be visible on branched DAGs",
                stage.isCustomBuilderButtonVisibleForTest());
    }

    @Test
    public void branched_clearsBannerAfterCanvasSaveReducesToLinear() {
        String branchedMacro = buildBranchedFixtureMacro();
        Map<String, String> macros = new HashMap<String, String>();
        macros.put("Default", DEFAULT_MACRO);
        macros.put("Branched", branchedMacro);
        MultiPresetMacroStore store = new MultiPresetMacroStore("Default", macros);

        FilterParameterStage.CustomFilterBuilder mock = new FilterParameterStage.CustomFilterBuilder() {
            @Override
            public FilterParameterStage.CustomFilterResult open(ConfigQcContext context,
                                                                String currentPreset,
                                                                String currentMacro) {
                // Simulate the user opening the canvas, editing the combiner
                // away, and saving — the resulting macro is linear.
                return new FilterParameterStage.CustomFilterResult(true, "Default", DEFAULT_MACRO);
            }
        };

        FilterParameterStage stage = new FilterParameterStage(
                Arrays.asList("Default", "Branched", "Custom"),
                store, new RecordingPreviewAdapter(), mock, null);

        stage.buildControls(context(), new RecordingActions());
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));
        stage.setPresetForTest("Branched");

        assertTrue("Banner must be visible before canvas save",
                stage.isBranchedBannerVisibleForTest());

        stage.simulateOpenCustomFilterBuilderForTest();

        assertFalse("Banner must clear once canvas save reduces DAG to linear",
                stage.isBranchedBannerVisibleForTest());
        assertTrue("Linear DAG must re-enable + Add filter…",
                stage.isAddFilterEnabledForTest());
        assertTrue("Custom macro button must remain available on linear pipelines",
                stage.isCustomBuilderButtonVisibleForTest());
    }

    private static String buildBranchedFixtureMacro() {
        DagLine lineA = new DagLine("line_A",
                Collections.singletonList(new DagNode("node_1", OpType.GAUSSIAN_BLUR, "sigma=2 stack")));
        DagLine lineB = new DagLine("line_B",
                Collections.singletonList(new DagNode("node_2", OpType.MEDIAN, "radius=2 stack")));
        Combiner combiner = new Combiner("combiner_1", CombinerOp.AND,
                Arrays.asList("line_A", "line_B"));
        DagIR dag = new DagIR(1,
                Arrays.asList(lineA, lineB),
                Collections.singletonList(combiner),
                "combiner_1",
                "native");
        return DagToIjmEmitter.emit(dag);
    }

    @Test
    public void customMacroButtonIsVisibleOnLinearFilters() {
        RecordingMacroStore store = new RecordingMacroStore("Default", DEFAULT_MACRO);
        FilterParameterStage stage = new FilterParameterStage(
                Arrays.asList("Default", "Custom"), store, new RecordingPreviewAdapter(), null, null);

        stage.buildControls(context(), new RecordingActions());

        assertNotNull(stage.customBuilderButtonTextForTest());
        assertTrue("button label must expose the custom macro chooser",
                stage.customBuilderButtonTextForTest().toLowerCase().contains("custom macro"));
        assertTrue("custom macro chooser must be visible in the linear embedded filter UI",
                stage.isCustomBuilderButtonVisibleForTest());
    }

    @Test
    public void customMacroBuilderResultLoadsBackIntoEmbeddedAccordion() {
        RecordingMacroStore store = new RecordingMacroStore("Default", DEFAULT_MACRO);
        final boolean[] receivedCurrentMacro = new boolean[]{false};
        FilterParameterStage.CustomFilterBuilder mock = new FilterParameterStage.CustomFilterBuilder() {
            @Override
            public FilterParameterStage.CustomFilterResult open(ConfigQcContext context,
                                                                String currentPreset,
                                                                String currentMacro) {
                receivedCurrentMacro[0] = "Default".equals(currentPreset)
                        && DEFAULT_MACRO.equals(currentMacro);
                return new FilterParameterStage.CustomFilterResult(
                        true, "Imported Custom", CUSTOM_MACRO);
            }
        };
        FilterParameterStage stage = new FilterParameterStage(
                Arrays.asList("Default", "Custom"), store, new RecordingPreviewAdapter(), mock, null);
        ConfigQcContext context = context();

        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));
        stage.simulateOpenCustomFilterBuilderForTest();

        assertTrue("custom macro chooser must receive the current embedded macro",
                receivedCurrentMacro[0]);
        assertEquals("Imported Custom", stage.selectedPresetForTest());
        assertEquals(CUSTOM_MACRO, stage.currentMacroForTest());
        assertTrue("returned macro must rebuild the embedded parameter accordion",
                stage.hasFieldBindingForTest("radius"));
    }

    @Test
    public void lockInCachesConfirmedFilteredPreview() throws Exception {
        RecordingMacroStore store = new RecordingMacroStore(
                "Default",
                DEFAULT_MACRO);
        FilterParameterStage stage = new FilterParameterStage(
                Arrays.asList("Default", "Custom"), store, new RecordingPreviewAdapter(), null, null);
        ConfigQcContext context = context();

        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));
        stage.runPreviewNowForTest();

        assertTrue(stage.lockIn(context));

        assertNotNull(context.duplicateCurrentFilteredStack(DEFAULT_MACRO));
    }

    @Test
    public void staleFilteredPreviewIsNotCachedOnLockIn() throws Exception {
        RecordingMacroStore store = new RecordingMacroStore(
                "Default",
                DEFAULT_MACRO);
        FilterParameterStage stage = new FilterParameterStage(
                Arrays.asList("Default", "Custom"), store, new RecordingPreviewAdapter(), null, null);
        ConfigQcContext context = context();

        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));
        stage.runPreviewNowForTest();
        stage.setParameterForTest("sigma", "4");

        assertTrue(stage.lockIn(context));

        assertNull(context.duplicateCurrentFilteredStack(stage.currentMacroForTest()));
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

    private static String manyStepMacro(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append("run(\"Median...\", \"radius=")
                    .append(i + 1)
                    .append(" stack\");\n");
        }
        return sb.toString();
    }

    private static JLabel findLabel(Component component, String text) {
        if (component instanceof JLabel && text.equals(((JLabel) component).getText())) {
            return (JLabel) component;
        }
        if (component instanceof Container) {
            Component[] children = ((Container) component).getComponents();
            for (int i = 0; i < children.length; i++) {
                JLabel found = findLabel(children[i], text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static FilterCatalog.Entry legacyEntry(String commandName) {
        try {
            Constructor<FilterCatalog.Entry> constructor = FilterCatalog.Entry.class
                    .getDeclaredConstructor(String.class, String.class, OpType.class,
                            String.class, boolean.class, boolean.class, String.class, String.class);
            constructor.setAccessible(true);
            return constructor.newInstance("Fiji commands", commandName, OpType.UNKNOWN,
                    "", false, true, commandName, "Fiji commands > " + commandName);
        } catch (Exception e) {
            throw new AssertionError("Unable to create legacy catalog entry for test", e);
        }
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
        JButton registeredPreviewButton;
        boolean previewButtonStale;

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

        @Override public void registerPreviewButton(JButton button) {
            registeredPreviewButton = button;
        }

        @Override public void setPreviewButtonStale(boolean stale) {
            previewButtonStale = stale;
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
