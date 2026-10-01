package flash.pipeline;

import flash.pipeline.io.ProjectStatusStore;
import flash.pipeline.recipes.PipelineRecipe;
import flash.pipeline.recipes.PipelineRecipeIO;
import flash.pipeline.ui.PipelineDialog;
import flash.pipeline.ui.ToggleSwitch;
import flash.pipeline.ui.main.StatusChip;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.swing.JButton;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.io.File;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
    public void presentationRecipeTicksPresentationImagesAndRepresentativeFigure() throws Exception {
        FLASH_Pipeline pipeline = new FLASH_Pipeline();
        PipelineDialog dialog = new PipelineDialog("Recipes");
        try {
            ToggleSwitch[] toggles = new ToggleSwitch[FLASH_Pipeline.IDX_REPRESENTATIVE_FIGURE + 1];
            toggles[FLASH_Pipeline.IDX_SPLIT_MERGE] = new ToggleSwitch(false);
            toggles[FLASH_Pipeline.IDX_REPRESENTATIVE_FIGURE] = new ToggleSwitch(false);
            toggles[FLASH_Pipeline.IDX_INTENSITY] = new ToggleSwitch(true);
            FLASH_Pipeline.MainStrip strip = pipeline.buildMainStatusStripForTests(dialog, toggles);

            chooseRecipe(strip, "Presentation");

            assertTrue(toggles[FLASH_Pipeline.IDX_SPLIT_MERGE].isSelected());
            assertTrue(toggles[FLASH_Pipeline.IDX_REPRESENTATIVE_FIGURE].isSelected());
            assertFalse(toggles[FLASH_Pipeline.IDX_INTENSITY].isSelected());
        } finally {
            dialog.closeWithAction("test");
        }
    }

    @Test
    public void fastPresentableResultsRecipeTicksDisplayIntensityResultsAndValidation() throws Exception {
        FLASH_Pipeline pipeline = new FLASH_Pipeline();
        PipelineDialog dialog = new PipelineDialog("Recipes");
        try {
            ToggleSwitch[] toggles = allToggles(false);
            toggles[FLASH_Pipeline.IDX_3D_OBJECT].setSelected(true);
            FLASH_Pipeline.MainStrip strip = pipeline.buildMainStatusStripForTests(dialog, toggles);

            chooseRecipe(strip, "Fast Presentable Results");

            assertTrue(toggles[FLASH_Pipeline.IDX_SPLIT_MERGE].isSelected());
            assertTrue(toggles[FLASH_Pipeline.IDX_REPRESENTATIVE_FIGURE].isSelected());
            assertTrue(toggles[FLASH_Pipeline.IDX_INTENSITY].isSelected());
            assertTrue(toggles[FLASH_Pipeline.IDX_AGGREGATION].isSelected());
            assertTrue(toggles[FLASH_Pipeline.IDX_STATISTICS].isSelected());
            assertTrue(toggles[FLASH_Pipeline.IDX_EXCEL_EXPORT].isSelected());
            assertFalse(toggles[FLASH_Pipeline.IDX_3D_OBJECT].isSelected());
            assertFalse(toggles[FLASH_Pipeline.IDX_DECONVOLUTION].isSelected());
            assertTrue(strip.recipeCaption.getText().contains("Applied recipe"));
        } finally {
            dialog.closeWithAction("test");
        }
    }

    @Test
    public void clearAllRecipeUnticksEveryAnalysis() throws Exception {
        FLASH_Pipeline pipeline = new FLASH_Pipeline();
        PipelineDialog dialog = new PipelineDialog("Recipes");
        try {
            ToggleSwitch[] toggles = allToggles(true);
            FLASH_Pipeline.MainStrip strip = pipeline.buildMainStatusStripForTests(dialog, toggles);

            chooseRecipe(strip, "Clear all");

            for (ToggleSwitch toggle : toggles) {
                assertFalse(toggle.isSelected());
            }
        } finally {
            dialog.closeWithAction("test");
        }
    }

    @Test
    public void stripReplacesRecipeButtonsWithOneMenuPlusSave() throws Exception {
        FLASH_Pipeline pipeline = new FLASH_Pipeline();
        PipelineDialog dialog = new PipelineDialog("Recipes");
        try {
            FLASH_Pipeline.MainStrip strip = pipeline.buildMainStatusStripForTests(dialog, allToggles(false));
            JButton save = findButton(strip.panel, "Save selection as recipe...");
            JButton help = findButton(strip.panel, "?");

            assertEquals(8, strip.recipeCombo.getItemCount());
            assertEquals("Choose a recipe...", strip.recipeCombo.getItemAt(0).label);
            assertNull(findButton(strip.panel, "Standard 3D + Intensity"));
            assertNull(findButton(strip.panel, "Clear Recipe"));
            assertNotNull(save);
            assertNotNull(help);
            assertNotNull(findButton(strip.panel, "Edit setup..."));
            assertEquals(new Color(232, 245, 253), save.getBackground());
            assertEquals(new Color(15, 87, 140), save.getForeground());
            assertEquals(save.getBackground(), help.getBackground());
            assertTrue(save.isOpaque());
        } finally {
            dialog.closeWithAction("test");
        }
    }

    @Test
    public void projectMenuShowsFolderNameWithFullPathTooltip() throws Exception {
        File project = temp.newFolder("Amyloid Project", "2, 4, and 8 Weeks", "MOAB-2.AF488");
        String fullPath = project.getAbsolutePath();
        FLASH_Pipeline pipeline = new FLASH_Pipeline();
        setDirectory(pipeline, fullPath);
        PipelineDialog dialog = new PipelineDialog("Recipes");
        try {
            FLASH_Pipeline.MainStrip strip = pipeline.buildMainStatusStripForTests(dialog, allToggles(false));

            assertEquals("MOAB-2.AF488", strip.projectCombo.getItemAt(0).name);
            assertEquals(fullPath, strip.projectCombo.getToolTipText());
            assertEquals("Open another project...",
                    strip.projectCombo.getItemAt(strip.projectCombo.getItemCount() - 1).name);
        } finally {
            dialog.closeWithAction("test");
        }
    }

    @Test
    public void openAnotherProjectReturnsChangeProjectAction() throws Exception {
        FLASH_Pipeline pipeline = new FLASH_Pipeline();
        PipelineDialog dialog = new PipelineDialog("Recipes");
        try {
            FLASH_Pipeline.MainStrip strip = pipeline.buildMainStatusStripForTests(dialog, allToggles(false));

            strip.projectCombo.setSelectedIndex(strip.projectCombo.getItemCount() - 1);

            assertEquals("change_project", dialog.getActionCommand());
        } finally {
            dialog.closeWithAction("test");
        }
    }

    @Test
    public void recentProjectReturnsOpenRecentActionWithItsPath() throws Exception {
        FLASH_Pipeline pipeline = new FLASH_Pipeline();
        PipelineDialog dialog = new PipelineDialog("Recipes");
        try {
            FLASH_Pipeline.MainStrip strip = pipeline.buildMainStatusStripForTests(dialog, allToggles(false));
            FLASH_Pipeline.ProjectChoice recent = new FLASH_Pipeline.ProjectChoice(
                    FLASH_Pipeline.ProjectChoice.Kind.RECENT, "Other", "C:/data/other/project.json");
            strip.projectCombo.addItem(recent);

            strip.projectCombo.setSelectedItem(recent);

            assertEquals(FLASH_Pipeline.OPEN_RECENT_ACTION_PREFIX + "C:/data/other/project.json",
                    dialog.getActionCommand());
        } finally {
            dialog.closeWithAction("test");
        }
    }

    @Test
    public void editSetupButtonReturnsEditAction() throws Exception {
        FLASH_Pipeline pipeline = new FLASH_Pipeline();
        PipelineDialog dialog = new PipelineDialog("Recipes");
        try {
            FLASH_Pipeline.MainStrip strip = pipeline.buildMainStatusStripForTests(dialog, allToggles(false));

            findButton(strip.panel, "Edit setup...").doClick();

            assertEquals("edit_project_setup", dialog.getActionCommand());
        } finally {
            dialog.closeWithAction("test");
        }
    }

    @Test
    public void lastRunRecipeRestoresOnlyWhenChosen() throws Exception {
        File project = temp.newFolder("last-run-button");
        Map<String, Object> recipe = new LinkedHashMap<String, Object>();
        recipe.put("name", "last-run");
        recipe.put("analyses", Arrays.asList("SplitMerge", "Statistics"));
        ProjectStatusStore.writeLastRunRecipe(project.getAbsolutePath(), recipe);

        FLASH_Pipeline pipeline = new FLASH_Pipeline();
        setDirectory(pipeline, project.getAbsolutePath());

        PipelineDialog dialog = new PipelineDialog("Recipes");
        try {
            ToggleSwitch[] toggles = allToggles(false);
            FLASH_Pipeline.MainStrip strip = pipeline.buildMainStatusStripForTests(dialog, toggles);

            assertFalse(toggles[FLASH_Pipeline.IDX_SPLIT_MERGE].isSelected());
            assertFalse(toggles[FLASH_Pipeline.IDX_STATISTICS].isSelected());

            chooseRecipe(strip, "Last run");

            assertTrue(toggles[FLASH_Pipeline.IDX_SPLIT_MERGE].isSelected());
            assertTrue(toggles[FLASH_Pipeline.IDX_STATISTICS].isSelected());
        } finally {
            dialog.closeWithAction("test");
        }
    }

    @Test
    public void projectChipsDescribeImagesAnimalsAndConditionState() {
        StatusChip images = new StatusChip("");
        StatusChip conditions = new StatusChip("");

        FLASH_Pipeline.applyProjectSummaryChips(images, conditions, "C:/p", 48,
                new LinkedHashSet<String>(Arrays.asList("A1", "A2")), null);

        assertEquals("48 images \u00B7 2 animals", images.getText());
        assertEquals(StatusChip.State.NEUTRAL, conditions.getState());
        assertTrue(conditions.getText().contains("No conditions yet"));
    }

    @Test
    public void dependencyChipTurnsAmberWithIssueCount() {
        StatusChip chip = new StatusChip("");

        FLASH_Pipeline.applyDependencyChip(chip, 2);
        assertEquals(StatusChip.State.WARN, chip.getState());
        assertTrue(chip.getText().endsWith("2 dependency issues"));

        FLASH_Pipeline.applyDependencyChip(chip, 0);
        assertEquals(StatusChip.State.OK, chip.getState());
        assertTrue(chip.getText().endsWith("Dependencies"));
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

    private static ToggleSwitch[] allToggles(boolean selected) {
        ToggleSwitch[] toggles = new ToggleSwitch[FLASH_Pipeline.IDX_REPRESENTATIVE_FIGURE + 1];
        for (int i = 0; i < toggles.length; i++) {
            toggles[i] = new ToggleSwitch(selected);
        }
        return toggles;
    }

    private static void chooseRecipe(FLASH_Pipeline.MainStrip strip, String label) {
        for (int i = 0; i < strip.recipeCombo.getItemCount(); i++) {
            if (label.equals(strip.recipeCombo.getItemAt(i).label)) {
                strip.recipeCombo.setSelectedIndex(i);
                return;
            }
        }
        throw new AssertionError("No recipe named " + label);
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
}
