package flash.pipeline.help;

import flash.pipeline.FLASH_Pipeline;
import org.junit.Test;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.GridLayout;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertTrue;

public class AnalysisHelpDialogTest {

    @Test
    public void minimalCatalogTopicBuildsRenderablePanel() {
        AnalysisHelpTopic topic = AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_CREATE_BIN);

        JPanel panel = AnalysisHelpDialog.buildContentPanel(topic);

        assertTrue(containsText(panel, "Set Up Configuration"));
        assertTrue(containsComponent(panel, WorkflowPanel.class));
        assertTrue(containsComponent(panel, HelpImagePanel.class));
        assertTrue(containsText(panel, "Image coming later"));
    }

    @Test
    public void missingImageResourceRendersPlaceholder() {
        AnalysisHelpTopic topic = new AnalysisHelpTopic(
                FLASH_Pipeline.IDX_CREATE_BIN,
                "test-topic",
                "Test Topic",
                "Summary",
                Collections.singletonList("Use it"),
                Collections.singletonList("Input"),
                Collections.singletonList("Setup"),
                Arrays.asList("First", "Second"),
                Collections.singletonList("Output"),
                Collections.singletonList("Pitfall"),
                Collections.singletonList(new AnalysisHelpTopic.HelpImage(
                        "/analysis-help/missing-image.png",
                        "Missing asset",
                        "This asset is intentionally absent.")));

        JPanel panel = AnalysisHelpDialog.buildContentPanel(topic);

        assertTrue(containsText(panel, "Missing asset"));
        assertTrue(containsText(panel, "Image coming later"));
    }

    @Test
    public void workflowSwitchesToVerticalLayoutWhenNarrow() {
        WorkflowPanel panel = new WorkflowPanel(Arrays.asList("Open inputs", "Run analysis", "Inspect outputs"));

        panel.setSize(300, 300);
        panel.doLayout();

        assertTrue(containsText(panel, "v"));
    }

    @Test
    public void imageGalleryUsesCappedTwoColumnLayout() {
        JPanel panel = AnalysisHelpDialog.buildContentPanel(
                AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_SPATIAL));

        JPanel gallery = findImageGallery(panel);

        assertTrue(gallery != null);
        GridLayout layout = (GridLayout) gallery.getLayout();
        assertTrue("image gallery should not widen the dialog with one card per image",
                layout.getColumns() <= 2);
        assertTrue("image gallery preferred width was " + gallery.getPreferredSize().width,
                gallery.getPreferredSize().width <= 620);
    }

    private static boolean containsComponent(Component component, Class<?> targetClass) {
        if (targetClass.isInstance(component)) {
            return true;
        }
        if (component instanceof JComponent) {
            Component[] children = ((JComponent) component).getComponents();
            for (int i = 0; i < children.length; i++) {
                if (containsComponent(children[i], targetClass)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsText(Component component, String expected) {
        if (component instanceof JLabel) {
            String text = ((JLabel) component).getText();
            if (text != null && text.contains(expected)) {
                return true;
            }
        }
        if (component instanceof JComponent) {
            Component[] children = ((JComponent) component).getComponents();
            for (int i = 0; i < children.length; i++) {
                if (containsText(children[i], expected)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static JPanel findImageGallery(Component component) {
        if (component instanceof JPanel
                && ((JPanel) component).getLayout() instanceof GridLayout
                && containsDirectComponent(component, HelpImagePanel.class)) {
            return (JPanel) component;
        }
        if (component instanceof JComponent) {
            Component[] children = ((JComponent) component).getComponents();
            for (int i = 0; i < children.length; i++) {
                JPanel gallery = findImageGallery(children[i]);
                if (gallery != null) {
                    return gallery;
                }
            }
        }
        return null;
    }

    private static boolean containsDirectComponent(Component component, Class<?> targetClass) {
        if (!(component instanceof JComponent)) {
            return false;
        }
        Component[] children = ((JComponent) component).getComponents();
        for (int i = 0; i < children.length; i++) {
            if (targetClass.isInstance(children[i])) {
                return true;
            }
        }
        return false;
    }
}
