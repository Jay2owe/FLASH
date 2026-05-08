package flash.pipeline;

import flash.pipeline.recipes.PipelineRecipe;
import flash.pipeline.recipes.PipelineRecipeIO;
import flash.pipeline.ui.PipelineDialog;
import flash.pipeline.ui.ToggleSwitch;
import org.junit.Test;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class FLASH_PipelineRecipeTest {

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
        String[] labels = new String[FLASH_Pipeline.IDX_SPECTRAL_DECONTAMINATION + 1];
        labels[FLASH_Pipeline.IDX_CREATE_BIN] = "Create Bin File";
        labels[FLASH_Pipeline.IDX_DRAW_ROIS] = "Draw and Save ROIs";
        labels[FLASH_Pipeline.IDX_DECONVOLUTION] = "3D Deconvolution";
        labels[FLASH_Pipeline.IDX_SPLIT_MERGE] = "Make Presentation-Ready Images";
        labels[FLASH_Pipeline.IDX_3D_OBJECT] = "3D Object Analysis";
        labels[FLASH_Pipeline.IDX_SPATIAL] = "Spatial Analysis";
        labels[FLASH_Pipeline.IDX_LINE_DISTANCE] = "Line Distance Analysis";
        labels[FLASH_Pipeline.IDX_INTENSITY] = "Fluorescence Intensity Analysis";
        labels[FLASH_Pipeline.IDX_AGGREGATION] = "Combine results per condition / animal";
        labels[FLASH_Pipeline.IDX_STATISTICS] = "Statistical Analysis";
        labels[FLASH_Pipeline.IDX_EXCEL_EXPORT] = "Excel Summary Export";

        String summary = FLASH_Pipeline.buildRecipeSelectionSummary(recipe, labels);

        assertTrue(summary.startsWith("This will tick: "));
        assertFalse(summary.contains("3D Deconvolution"));
    }

    @Test
    public void quickStartSaveRecipeButtonIsDistinctAndLeftAligned() throws Exception {
        FLASH_Pipeline pipeline = new FLASH_Pipeline();
        PipelineDialog dialog = new PipelineDialog("Recipes");
        try {
            JPanel quickStart = quickStartPanel(pipeline, dialog);
            JButton standard = findButton(quickStart, "Standard 3D + Intensity");
            JButton save = findButton(quickStart, "Save selection as recipe...");
            JButton help = findButton(quickStart, "?");
            JLabel caption = findLabelContaining(quickStart, "Pick a recipe");

            assertNotNull(standard);
            assertNotNull(save);
            assertNotNull(help);
            assertNotNull(caption);
            assertSame(standard, standard.getParent().getComponent(0));
            assertSame(findButton(quickStart, "Full pipeline").getParent(), save.getParent());
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

    private static JPanel quickStartPanel(FLASH_Pipeline pipeline, PipelineDialog dialog) throws Exception {
        Method method = FLASH_Pipeline.class.getDeclaredMethod(
                "buildQuickStartPanel", PipelineDialog.class, ToggleSwitch[].class);
        method.setAccessible(true);
        return (JPanel) method.invoke(pipeline, dialog,
                new ToggleSwitch[FLASH_Pipeline.IDX_SPECTRAL_DECONTAMINATION + 1]);
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
