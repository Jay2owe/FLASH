package flash.pipeline;

import flash.pipeline.io.ProjectStatusStore;
import flash.pipeline.recipes.PipelineRecipe;
import flash.pipeline.recipes.PipelineRecipeIO;
import flash.pipeline.ui.PipelineDialog;
import flash.pipeline.ui.ToggleSwitch;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class FLASH_PipelineRecipeTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void standardAndFullRecipesDoNotSelect3DDeconvolution() throws Exception {
        assertFalse(PipelineRecipeIO.loadFromResources("standard-3d-intensity")
                .getAnalyses().contains("Deconvolution"));
        assertFalse(PipelineRecipeIO.loadFromResources("full-pipeline")
                .getAnalyses().contains("Deconvolution"));
    }

    @Test
    public void fullRecipeHoverSummaryDoesNotMention3DDeconvolution() throws Exception {
        PipelineRecipe recipe = PipelineRecipeIO.loadFromResources("full-pipeline");
        String[] labels = new String[FLASH_Pipeline.IDX_REPRESENTATIVE_FIGURE + 1];
        labels[FLASH_Pipeline.IDX_CREATE_BIN] = "Create Bin File";
        labels[FLASH_Pipeline.IDX_DRAW_ROIS] = "Draw ROIs and Orientate Images";
        labels[FLASH_Pipeline.IDX_DECONVOLUTION] = "3D Deconvolution";
        labels[FLASH_Pipeline.IDX_SPLIT_MERGE] = "Make Presentation Images";
        labels[FLASH_Pipeline.IDX_3D_OBJECT] = "3D Object Analysis";
        labels[FLASH_Pipeline.IDX_SPATIAL] = "Spatial Analysis";
        labels[FLASH_Pipeline.IDX_LINE_DISTANCE] = "Line Distance Analysis";
        labels[FLASH_Pipeline.IDX_INTENSITY] = "Fluorescence Intensity Analysis";
        labels[FLASH_Pipeline.IDX_AGGREGATION] = "Combine results per condition / animal";
        labels[FLASH_Pipeline.IDX_STATISTICS] = "Statistical Analysis";
        labels[FLASH_Pipeline.IDX_EXCEL_EXPORT] = "Excel Summary Export";
        labels[FLASH_Pipeline.IDX_REPRESENTATIVE_FIGURE] = "Make Representative Image Figure";

        String summary = FLASH_Pipeline.buildRecipeSelectionSummary(recipe, labels);

        assertTrue(summary.startsWith("This will tick: "));
        assertFalse(summary.contains("3D Deconvolution"));
    }

    @Test
    public void presentationRecipeSelectsPresentationImagesAndRepresentativeFigure() throws Exception {
        PipelineRecipe recipe = PipelineRecipeIO.loadFromResources("presentation");

        assertEquals(Arrays.asList("SplitMerge", "RepresentativeFigure"), recipe.getAnalyses());
    }

    @Test
    public void fastPresentableResultsRecipeSelectsDisplayIntensityResultsAndValidation() throws Exception {
        PipelineRecipe recipe = PipelineRecipeIO.loadFromResources("fast-presentable-results");

        assertEquals(Arrays.asList("SplitMerge", "RepresentativeFigure", "Intensity",
                "Aggregation", "Statistics", "Excel"), recipe.getAnalyses());
    }

    @Test
    public void presentationRecipeButtonTicksPresentationImagesAndRepresentativeFigure() throws Exception {
        FLASH_Pipeline pipeline = new FLASH_Pipeline();
        PipelineDialog dialog = new PipelineDialog("Recipes");
        try {
            ToggleSwitch[] toggles = new ToggleSwitch[FLASH_Pipeline.IDX_REPRESENTATIVE_FIGURE + 1];
            toggles[FLASH_Pipeline.IDX_SPLIT_MERGE] = new ToggleSwitch(false);
            toggles[FLASH_Pipeline.IDX_REPRESENTATIVE_FIGURE] = new ToggleSwitch(false);
            toggles[FLASH_Pipeline.IDX_INTENSITY] = new ToggleSwitch(true);
            JPanel quickStart = quickStartPanel(pipeline, dialog, toggles);

            findButton(quickStart, "Presentation").doClick();

            assertTrue(toggles[FLASH_Pipeline.IDX_SPLIT_MERGE].isSelected());
            assertTrue(toggles[FLASH_Pipeline.IDX_REPRESENTATIVE_FIGURE].isSelected());
            assertFalse(toggles[FLASH_Pipeline.IDX_INTENSITY].isSelected());
        } finally {
            dialog.closeWithAction("test");
        }
    }

    @Test
    public void fastPresentableResultsButtonTicksDisplayIntensityResultsAndValidation() throws Exception {
        FLASH_Pipeline pipeline = new FLASH_Pipeline();
        PipelineDialog dialog = new PipelineDialog("Recipes");
        try {
            ToggleSwitch[] toggles = new ToggleSwitch[FLASH_Pipeline.IDX_REPRESENTATIVE_FIGURE + 1];
            for (int i = 0; i < toggles.length; i++) {
                toggles[i] = new ToggleSwitch(false);
            }
            toggles[FLASH_Pipeline.IDX_3D_OBJECT].setSelected(true);
            JPanel quickStart = quickStartPanel(pipeline, dialog, toggles);

            findButton(quickStart, "Fast Presentable Results").doClick();

            assertTrue(toggles[FLASH_Pipeline.IDX_SPLIT_MERGE].isSelected());
            assertTrue(toggles[FLASH_Pipeline.IDX_REPRESENTATIVE_FIGURE].isSelected());
            assertTrue(toggles[FLASH_Pipeline.IDX_INTENSITY].isSelected());
            assertTrue(toggles[FLASH_Pipeline.IDX_AGGREGATION].isSelected());
            assertTrue(toggles[FLASH_Pipeline.IDX_STATISTICS].isSelected());
            assertTrue(toggles[FLASH_Pipeline.IDX_EXCEL_EXPORT].isSelected());
            assertFalse(toggles[FLASH_Pipeline.IDX_3D_OBJECT].isSelected());
            assertFalse(toggles[FLASH_Pipeline.IDX_DECONVOLUTION].isSelected());
        } finally {
            dialog.closeWithAction("test");
        }
    }

    @Test
    public void quickStartSaveRecipeButtonIsDistinctAndLeftAligned() throws Exception {
        FLASH_Pipeline pipeline = new FLASH_Pipeline();
        PipelineDialog dialog = new PipelineDialog("Recipes");
        try {
            JPanel quickStart = quickStartPanel(pipeline, dialog);
            JButton standard = findButton(quickStart, "Standard 3D + Intensity");
            JButton presentation = findButton(quickStart, "Presentation");
            JButton fastPresentableResults = findButton(quickStart, "Fast Presentable Results");
            JButton lastRun = findButton(quickStart, "Last run");
            JButton clear = findButton(quickStart, "Clear Recipe");
            JButton save = findButton(quickStart, "Save selection as recipe...");
            JButton help = findButton(quickStart, "?");
            JLabel caption = findLabelContaining(quickStart, "Pick a recipe");

            assertNotNull(standard);
            assertNotNull(presentation);
            assertNotNull(fastPresentableResults);
            assertNotNull(lastRun);
            assertNotNull(clear);
            assertNotNull(save);
            assertNotNull(help);
            assertNotNull(caption);
            assertSame(standard, standard.getParent().getComponent(0));
            assertSame(findButton(quickStart, "Full pipeline").getParent(), presentation.getParent());
            assertSame(lastRun.getParent(), clear.getParent());
            assertSame(fastPresentableResults, fastPresentableResults.getParent().getComponent(0));
            assertEquals(Component.LEFT_ALIGNMENT, caption.getAlignmentX(), 0.001f);
            assertEquals(new Color(232, 245, 253), save.getBackground());
            assertEquals(new Color(15, 87, 140), save.getForeground());
            assertEquals(save.getBackground(), help.getBackground());
            assertEquals(save.getForeground(), help.getForeground());
            assertTrue(save.isOpaque());
            assertTrue(save.isContentAreaFilled());
        } finally {
            dialog.closeWithAction("test");
        }
    }

    @Test
    public void quickStartDirectoryShowsTailAndKeepsFullPathTooltip() throws Exception {
        File project = temp.newFolder("Amyloid Project", "2, 4, and 8 Weeks", "MOAB-2.AF488");
        String fullPath = project.getAbsolutePath();
        FLASH_Pipeline pipeline = new FLASH_Pipeline();
        setDirectory(pipeline, fullPath);
        PipelineDialog dialog = new PipelineDialog("Recipes");
        try {
            JPanel quickStart = quickStartPanel(pipeline, dialog);
            JLabel directory = findLabelContaining(quickStart, "MOAB-2.AF488");

            assertNotNull(directory);
            assertEquals("...\\2, 4, and 8 Weeks\\MOAB-2.AF488", directory.getText());
            assertEquals(fullPath, directory.getToolTipText());
        } finally {
            dialog.closeWithAction("test");
        }
    }

    @Test
    public void quickStartProjectSwitchButtonReturnsChangeProjectAction() throws Exception {
        FLASH_Pipeline pipeline = new FLASH_Pipeline();
        PipelineDialog dialog = new PipelineDialog("Recipes");
        try {
            JPanel quickStart = quickStartPanel(pipeline, dialog);

            assertNotNull(findLabelContaining(quickStart, "Current Project"));
            assertNotNull(findButton(quickStart, "Edit setup..."));

            findButton(quickStart, "Change project...").doClick();

            assertEquals("change_project", dialog.getActionCommand());
        } finally {
            dialog.closeWithAction("test");
        }
    }

    @Test
    public void quickStartEditSetupButtonReturnsEditAction() throws Exception {
        FLASH_Pipeline pipeline = new FLASH_Pipeline();
        PipelineDialog dialog = new PipelineDialog("Recipes");
        try {
            JPanel quickStart = quickStartPanel(pipeline, dialog);

            findButton(quickStart, "Edit setup...").doClick();

            assertEquals("edit_project_setup", dialog.getActionCommand());
        } finally {
            dialog.closeWithAction("test");
        }
    }

    @Test
    public void lastRunRecipeButtonRestoresOnlyWhenClicked() throws Exception {
        File project = temp.newFolder("last-run-button");
        Map<String, Object> recipe = new LinkedHashMap<String, Object>();
        recipe.put("name", "last-run");
        recipe.put("analyses", Arrays.asList("SplitMerge", "Statistics"));
        ProjectStatusStore.writeLastRunRecipe(project.getAbsolutePath(), recipe);

        FLASH_Pipeline pipeline = new FLASH_Pipeline();
        java.lang.reflect.Field directory = FLASH_Pipeline.class.getDeclaredField("directory");
        directory.setAccessible(true);
        directory.set(pipeline, project.getAbsolutePath());

        PipelineDialog dialog = new PipelineDialog("Recipes");
        try {
            ToggleSwitch[] toggles = new ToggleSwitch[FLASH_Pipeline.IDX_REPRESENTATIVE_FIGURE + 1];
            JPanel quickStart = quickStartPanel(pipeline, dialog, toggles);
            toggles[FLASH_Pipeline.IDX_SPLIT_MERGE] = new ToggleSwitch(false);
            toggles[FLASH_Pipeline.IDX_STATISTICS] = new ToggleSwitch(false);

            assertFalse(toggles[FLASH_Pipeline.IDX_SPLIT_MERGE].isSelected());
            assertFalse(toggles[FLASH_Pipeline.IDX_STATISTICS].isSelected());

            findButton(quickStart, "Last run").doClick();

            assertTrue(toggles[FLASH_Pipeline.IDX_SPLIT_MERGE].isSelected());
            assertTrue(toggles[FLASH_Pipeline.IDX_STATISTICS].isSelected());
        } finally {
            dialog.closeWithAction("test");
        }
    }

    @Test
    public void applySelectionsToTogglesSetsTrueAndFalseStates() {
        ToggleSwitch[] toggles = new ToggleSwitch[FLASH_Pipeline.IDX_EXCEL_EXPORT + 1];
        toggles[FLASH_Pipeline.IDX_CREATE_BIN] = new ToggleSwitch(false);
        toggles[FLASH_Pipeline.IDX_DRAW_ROIS] = new ToggleSwitch(true);
        toggles[FLASH_Pipeline.IDX_INTENSITY] = new ToggleSwitch(false);
        boolean[] selections = new boolean[toggles.length];
        selections[FLASH_Pipeline.IDX_CREATE_BIN] = true;
        selections[FLASH_Pipeline.IDX_INTENSITY] = true;

        int applied = FLASH_Pipeline.applySelectionsToToggles(toggles, selections);

        assertEquals(2, applied);
        assertTrue(toggles[FLASH_Pipeline.IDX_CREATE_BIN].isSelected());
        assertFalse(toggles[FLASH_Pipeline.IDX_DRAW_ROIS].isSelected());
        assertTrue(toggles[FLASH_Pipeline.IDX_INTENSITY].isSelected());
    }

    @Test
    public void readLastRunRecipeSelectionsUsesProjectStatusRecipe() throws Exception {
        File project = temp.newFolder("pipeline-recipe-restore");
        Map<String, Object> recipe = new LinkedHashMap<String, Object>();
        recipe.put("name", "last-run");
        recipe.put("analyses", Arrays.asList("SplitMerge", "Statistics"));
        ProjectStatusStore.writeLastRunRecipe(project.getAbsolutePath(), recipe);

        boolean[] selections = FLASH_Pipeline.readLastRunRecipeSelections(
                project.getAbsolutePath(), FLASH_Pipeline.IDX_REPRESENTATIVE_FIGURE + 1);

        assertTrue(selections[FLASH_Pipeline.IDX_SPLIT_MERGE]);
        assertTrue(selections[FLASH_Pipeline.IDX_STATISTICS]);
        assertFalse(selections[FLASH_Pipeline.IDX_CREATE_BIN]);
    }

    private static JPanel quickStartPanel(FLASH_Pipeline pipeline, PipelineDialog dialog) throws Exception {
        return quickStartPanel(pipeline, dialog,
                new ToggleSwitch[FLASH_Pipeline.IDX_REPRESENTATIVE_FIGURE + 1]);
    }

    private static JPanel quickStartPanel(FLASH_Pipeline pipeline,
                                          PipelineDialog dialog,
                                          ToggleSwitch[] toggles) throws Exception {
        Method method = FLASH_Pipeline.class.getDeclaredMethod(
                "buildQuickStartPanel", PipelineDialog.class, ToggleSwitch[].class);
        method.setAccessible(true);
        return (JPanel) method.invoke(pipeline, dialog, toggles);
    }

    private static void setDirectory(FLASH_Pipeline pipeline, String directory) throws Exception {
        Field field = FLASH_Pipeline.class.getDeclaredField("directory");
        field.setAccessible(true);
        field.set(pipeline, directory);
    }

    private static JButton findButton(Container container, String text) {
        for (Component component : container.getComponents()) {
            if (component instanceof JButton && text.equals(((JButton) component).getText())) {
                return (JButton) component;
            }
            if (component instanceof Container) {
                JButton found = findButton((Container) component, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static JLabel findLabelContaining(Container container, String text) {
        for (Component component : container.getComponents()) {
            if (component instanceof JLabel && ((JLabel) component).getText().contains(text)) {
                return (JLabel) component;
            }
            if (component instanceof Container) {
                JLabel found = findLabelContaining((Container) component, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
