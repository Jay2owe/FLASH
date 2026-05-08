package flash.pipeline.ui;

import flash.pipeline.FLASH_Pipeline;
import org.junit.Test;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Dimension;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class PipelineDialogLayoutTest {

    @Test
    public void displaySizeCapsOversizedPreferredWidthToSmallScreen() {
        Dimension size = PipelineDialog.computeDisplaySize(
                new Dimension(2200, 500), new Dimension(1366, 768));

        assertTrue("Dialog width should fit within 92% of a 1366px screen",
                size.width <= 1256);
        assertEquals(500, size.height);
    }

    @Test
    public void displaySizeAddsScrollbarAllowanceBeforeHeightCap() {
        Dimension size = PipelineDialog.computeDisplaySize(
                new Dimension(800, 1200), new Dimension(1366, 768));

        assertEquals(830, size.width);
        assertEquals(614, size.height);
    }

    @Test
    public void displaySizeLeavesNormalPreferredSizeAlone() {
        Dimension size = PipelineDialog.computeDisplaySize(
                new Dimension(700, 400), new Dimension(1366, 768));

        assertEquals(new Dimension(700, 400), size);
    }

    @Test
    public void headerToggleSitsBesideHeaderTextButBodyToggleStaysRightAligned() throws Exception {
        PipelineDialog dialog = new PipelineDialog("Layout");
        ToggleSwitch sectionToggle = dialog.addHeaderToggle("Setup", false);
        ToggleSwitch bodyToggle = dialog.addToggle("Create Bin File", false);

        JPanel content = contentPanel(dialog);
        JPanel headerRow = (JPanel) content.getComponent(1);
        assertTrue(headerRow.getComponent(0) instanceof JLabel);
        assertEquals(8, headerRow.getComponent(1).getPreferredSize().width);
        assertSame(sectionToggle, headerRow.getComponent(2));

        JPanel bodyRow = (JPanel) content.getComponent(5);
        assertTrue(bodyRow.getComponent(0) instanceof JLabel);
        assertTrue("Body-row spacer should still expand, keeping individual toggles at the right edge",
                bodyRow.getComponent(1).getMaximumSize().width > 1000);
        assertSame(bodyToggle, bodyRow.getComponent(2));

        backingDialog(dialog).dispose();
    }

    @Test
    public void trailingToggleActionDoesNotEnterBooleanRetrievalOrder() throws Exception {
        PipelineDialog dialog = new PipelineDialog("Trailing Action");
        JButton help = HelpButton.question("About first toggle");
        ToggleSwitch first = dialog.addToggleWithStatus("First analysis", true, new JLabel(), help);
        ToggleSwitch second = dialog.addToggleWithStatus("Second analysis", false, new JLabel());

        JPanel content = contentPanel(dialog);
        JPanel firstRow = (JPanel) content.getComponent(0);
        assertSame(first, firstRow.getComponent(4));
        assertSame(help, firstRow.getComponent(6));

        assertTrue(dialog.getNextBoolean());
        assertFalse(dialog.getNextBoolean());
        assertSame(second, ((JPanel) content.getComponent(2)).getComponent(4));
        backingDialog(dialog).dispose();
    }

    @Test
    public void analysisHelpHeaderCreatesQuestionButtonWithoutChangingBooleanOrder() throws Exception {
        PipelineDialog dialog = new PipelineDialog("Analysis Help Header");
        JButton help = dialog.addAnalysisHelpHeader("Set Up Configuration", FLASH_Pipeline.IDX_CREATE_BIN);
        ToggleSwitch toggle = dialog.addToggle("Run setup", true);

        JPanel content = contentPanel(dialog);
        JPanel headerRow = (JPanel) content.getComponent(1);
        assertTrue(headerRow.getComponent(0) instanceof JLabel);
        assertEquals("Set Up Configuration", ((JLabel) headerRow.getComponent(0)).getText());
        assertEquals(6, headerRow.getComponent(1).getPreferredSize().width);
        assertSame(help, headerRow.getComponent(2));
        assertEquals("?", help.getText());
        assertEquals("About Set Up Configuration", help.getToolTipText());
        assertEquals("About Set Up Configuration",
                help.getAccessibleContext().getAccessibleName());
        assertTrue("header help button should have a click handler",
                help.getActionListeners().length > 0);

        assertTrue(dialog.getNextBoolean());
        assertSame(toggle, ((JPanel) content.getComponent(5)).getComponent(2));
        backingDialog(dialog).dispose();
    }

    @Test
    public void footerUtilityButtonsShareRowWithActionButtons() throws Exception {
        PipelineDialog dialog = new PipelineDialog("Footer");
        dialog.addFooterButton("Check my data");
        dialog.addFooterButton("Options");
        dialog.addFooterButton("Dependencies");

        JPanel buttonBar = buttonBar(dialog);

        assertTrue("Footer should keep utility buttons and OK/Cancel in one horizontal row",
                buttonBar.getLayout() instanceof java.awt.BorderLayout);
        assertEquals(2, buttonBar.getComponentCount());
        backingDialog(dialog).dispose();
    }

    @Test
    public void helpTextDoesNotExpandToWideInitialDialogWidth() throws Exception {
        PipelineDialog dialog = new PipelineDialog("Help Width");
        JLabel help = dialog.addHelpText("This is a deliberately long help sentence that should wrap compactly "
                + "instead of making the opening dialog much wider than the controls require.");
        backingDialog(dialog).setSize(new Dimension(1000, 500));

        rerenderHelpText(dialog);

        assertTrue("Help preferred width was " + help.getPreferredSize().width
                        + "; it should stay compact after rerendering from a wide dialog",
                help.getPreferredSize().width <= 520);
        backingDialog(dialog).dispose();
    }

    @Test
    public void messageTextUsesCompactSwingHtmlWidth() throws Exception {
        PipelineDialog dialog = new PipelineDialog("Message Width");
        JLabel message = dialog.addMessage("D:\\Microscopy Data\\Experiment 01\\FLASH");

        assertTrue("Message preferred width should stay close to its configured 280px wrap width",
                message.getPreferredSize().width <= 320);
        backingDialog(dialog).dispose();
    }

    @Test
    public void primaryAndCancelButtonsUseActionColoursAndPrimaryTextCanChange() throws Exception {
        PipelineDialog dialog = new PipelineDialog("Actions");

        assertEquals("OK", button(dialog, "okButton").getText());
        dialog.setPrimaryButtonText("Run");

        JButton ok = button(dialog, "okButton");
        JButton cancel = button(dialog, "cancelButton");
        assertEquals("Run", ok.getText());
        assertEquals(new Color(235, 248, 239), ok.getBackground());
        assertEquals(new Color(252, 240, 240), cancel.getBackground());
        assertEquals(new Color(37, 103, 62), ok.getForeground());
        assertEquals(new Color(137, 44, 44), cancel.getForeground());
        assertTrue(ok.isOpaque());
        assertTrue(cancel.isOpaque());
        backingDialog(dialog).dispose();
    }

    private static JPanel contentPanel(PipelineDialog dialog) throws Exception {
        Field field = PipelineDialog.class.getDeclaredField("contentPanel");
        field.setAccessible(true);
        return (JPanel) field.get(dialog);
    }

    private static JDialog backingDialog(PipelineDialog dialog) throws Exception {
        Field field = PipelineDialog.class.getDeclaredField("dialog");
        field.setAccessible(true);
        return (JDialog) field.get(dialog);
    }

    private static JPanel buttonBar(PipelineDialog dialog) throws Exception {
        Field field = PipelineDialog.class.getDeclaredField("buttonBar");
        field.setAccessible(true);
        return (JPanel) field.get(dialog);
    }

    private static JButton button(PipelineDialog dialog, String fieldName) throws Exception {
        Field field = PipelineDialog.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (JButton) field.get(dialog);
    }

    private static void rerenderHelpText(PipelineDialog dialog) throws Exception {
        Method method = PipelineDialog.class.getDeclaredMethod("rerenderHelpText");
        method.setAccessible(true);
        method.invoke(dialog);
    }
}
