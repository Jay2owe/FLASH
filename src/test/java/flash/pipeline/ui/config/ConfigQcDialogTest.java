package flash.pipeline.ui.config;

import flash.pipeline.ui.preview.PreviewPairPanel;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Test;

import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

public class ConfigQcDialogTest {

    @Test
    public void constructsShellWithFakeStageInHeadlessSafePath() {
        RecordingStage stage = new RecordingStage("Display range");
        ConfigQcContext context = contextWithTwoImages();

        ConfigQcDialog dialog = ConfigQcDialog.createForTest(context, Arrays.asList(stage));

        assertEquals("Display range", dialog.stageTextForTest());
        assertEquals("C2 - IBA1", dialog.channelTextForTest());
        assertEquals("Stage 1 / 1    Image 1 / 2    - Image A", dialog.progressTextForTest());
        assertEquals("Image A", dialog.imageTextForTest());
        assertEquals(1, stage.enterCount);
        assertNotNull(stage.actions);
        assertSame(dialog.previewForTest().largeViewButton(), dialog.largeViewButtonForTest());
        assertSame(dialog.previewForTest().displayControlsButton(), dialog.displayControlsButtonForTest());
        assertTrue(dialog.displayControlsButtonForTest().isVisible());
    }

    @Test
    public void headerText_includesFilename() {
        RecordingStage stage = new RecordingStage("Display range");
        ConfigQcDialog dialog = ConfigQcDialog.createForTest(contextWithTwoImages(), Arrays.asList(stage));

        assertTrue(dialog.progressTextForTest().contains("Image 1 / 2"));
        assertTrue(dialog.progressTextForTest().contains("Image A"));
    }

    @Test
    public void footer_doesNotContainLargeView_orAdjustBcButtons() {
        RecordingStage stage = new RecordingStage("Display range");
        ConfigQcDialog dialog = ConfigQcDialog.createForTest(contextWithTwoImages(), Arrays.asList(stage));
        JComponent footer = footerFrom(dialog);

        assertFalse(containsComponent(footer, dialog.largeViewButtonForTest()));
        assertFalse(containsComponent(footer, dialog.displayControlsButtonForTest()));
        assertTrue(containsComponent(dialog.getContent(), dialog.largeViewButtonForTest()));
        assertTrue(containsComponent(dialog.getContent(), dialog.displayControlsButtonForTest()));
    }

    @Test
    public void hidesPreviewDisplayButtonWhenStageDisallowsIt() {
        RecordingStage stage = new RecordingStage("Display range");
        stage.previewDisplayControls = false;

        ConfigQcDialog dialog = ConfigQcDialog.createForTest(contextWithTwoImages(), Arrays.asList(stage));

        assertFalse(dialog.displayControlsButtonForTest().isVisible());
    }

    @Test
    public void lockInButton_isSoftGreen_cancelButton_isSoftRed() {
        RecordingStage stage = new RecordingStage("Display range");
        ConfigQcDialog dialog = ConfigQcDialog.createForTest(contextWithTwoImages(), Arrays.asList(stage));
        JButton lock = dialog.lockInButtonForTest();
        JButton cancel = dialog.cancelButtonForTest();

        assertEquals(new Color(235, 248, 239), lock.getBackground());
        assertEquals(new Color(37, 103, 62), lock.getForeground());
        assertEquals(new Color(252, 240, 240), cancel.getBackground());
        assertEquals(new Color(137, 44, 44), cancel.getForeground());
        assertTrue(lock.isOpaque());
        assertTrue(cancel.isOpaque());
    }

    @Test
    public void escapeKey_firesCancel() {
        RecordingStage stage = new RecordingStage("Display range");
        ConfigQcDialog dialog = ConfigQcDialog.createForTest(contextWithTwoImages(), Arrays.asList(stage));
        JComponent content = dialog.getContent();
        Object actionKey = content.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .get(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
        Action action = content.getActionMap().get(actionKey);

        assertNotNull(actionKey);
        assertNotNull(action);

        action.actionPerformed(new ActionEvent(content, ActionEvent.ACTION_PERFORMED, "qc-cancel"));

        assertEquals(ConfigQcResult.CANCEL, dialog.resultForTest());
        assertEquals(1, stage.leaveCount);
    }

    @Test
    public void defaultButton_isLockIn() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                RecordingStage stage = new RecordingStage("Display range");
                ConfigQcDialog dialog = ConfigQcDialog.createModeless(
                        null, contextWithTwoImages(), Arrays.asList(stage));

                assertSame(dialog.lockInButtonForTest(), dialog.defaultButtonForTest());
                dialog.actionsForTest().cancel();
            }
        });
    }

    @Test
    public void rebuildCurrentStage_callsResetStageToolstripState() {
        RecordingStage first = new RecordingStage("First") {
            @Override public void onEnter(ConfigQcContext context, PreviewPairPanel preview) {
                super.onEnter(context, preview);
                preview.setSourceToggleVisible(true);
            }
        };
        RecordingStage second = new RecordingStage("Second");
        ConfigQcDialog dialog = ConfigQcDialog.createForTest(
                contextWithOneImage(), Arrays.asList(first, second));

        assertTrue(visibleRadioButtonWithText(dialog.getContent(), "Raw"));

        dialog.lockInForTest();

        assertEquals("Second", dialog.stageTextForTest());
        assertFalse(visibleRadioButtonWithText(dialog.getContent(), "Raw"));
    }

    @Test
    public void dialogMinimumSize_is1080x720() {
        Dimension minimum = ConfigQcDialog.minimumDialogSizeForTest();

        assertEquals(1080, minimum.width);
        assertEquals(720, minimum.height);
    }

    @Test
    public void lockInAdvancesImagesBeforeMovingToNextStage() {
        RecordingStage first = new RecordingStage("First");
        RecordingStage second = new RecordingStage("Second");
        ConfigQcContext context = contextWithTwoImages();
        ConfigQcDialog dialog = ConfigQcDialog.createForTest(context, Arrays.asList(first, second));
        dialog.previewForTest().setCurrentZ(3);

        dialog.lockInForTest();

        assertEquals(0, dialog.stageIndexForTest());
        assertEquals(1, context.getCurrentImageIndex());
        assertEquals(1, first.lockCount);
        assertEquals("Image B", dialog.imageTextForTest());
        assertEquals(1, dialog.previewForTest().getCurrentZ());
        assertEquals("Moved to image 2 / 2: Image B", dialog.statusTextForTest());

        dialog.lockInForTest();

        assertEquals(1, dialog.stageIndexForTest());
        assertEquals(0, context.getCurrentImageIndex());
        assertEquals(2, first.lockCount);
        assertEquals(1, first.leaveCount);
        assertEquals(1, second.enterCount);
        assertEquals("Second", dialog.stageTextForTest());
    }

    @Test
    public void skipAndRestartDelegateToCurrentStage() {
        RecordingStage stage = new RecordingStage("Particle size");
        ConfigQcContext context = contextWithTwoImages();
        ConfigQcDialog dialog = ConfigQcDialog.createForTest(context, Arrays.asList(stage));
        dialog.previewForTest().setCurrentZ(3);

        dialog.skipForTest();

        assertEquals(1, stage.skipCount);
        assertEquals(1, context.getCurrentImageIndex());
        assertEquals(1, dialog.previewForTest().getCurrentZ());
        assertEquals("Moved to image 2 / 2: Image B", dialog.statusTextForTest());

        dialog.restartForTest();

        assertEquals(1, stage.restartCount);
        assertEquals(0, context.getCurrentImageIndex());
        assertEquals("Image A", dialog.imageTextForTest());
    }

    @Test
    public void previousImageKeepsSameStageAndPreloadsCurrentEditedParameters() {
        SnapshotStage stage = new SnapshotStage();
        ConfigQcContext context = contextWithTwoImages();
        ConfigQcDialog dialog = ConfigQcDialog.createForTest(context, Arrays.asList(stage));

        assertFalse(dialog.previousImageButtonForTest().isEnabled());
        dialog.lockInForTest();
        assertTrue(dialog.previousImageButtonForTest().isEnabled());
        assertEquals(0, dialog.stageIndexForTest());
        assertEquals(1, context.getCurrentImageIndex());

        stage.setValueForTest("edited on image B");
        dialog.previousImageForTest();

        assertEquals(0, dialog.stageIndexForTest());
        assertEquals(0, context.getCurrentImageIndex());
        assertEquals(1, stage.previousSnapshotCount);
        assertEquals(1, stage.restartImageIndex);
        assertEquals("edited on image B", stage.currentValueForTest());
        assertEquals("Moved back to image 1 / 2: Image A", dialog.statusTextForTest());
        assertFalse(dialog.previousImageButtonForTest().isEnabled());
    }

    @Test
    public void backToPreviousStagePreloadsLockedParameters() {
        StoredStage first = new StoredStage();
        RecordingStage second = new RecordingStage("Second");
        ConfigQcDialog dialog = ConfigQcDialog.createForTest(
                contextWithOneImage(), Arrays.asList(first, second));

        first.setValueForTest("locked threshold");
        dialog.lockInForTest();
        assertEquals("Second", dialog.stageTextForTest());

        dialog.backForTest();

        assertEquals("Stored", dialog.stageTextForTest());
        assertEquals("locked threshold", first.currentValueForTest());
    }

    @Test
    public void backFromFirstStageReturnsBackResult() {
        RecordingStage stage = new RecordingStage("Threshold");
        ConfigQcDialog dialog = ConfigQcDialog.createForTest(contextWithTwoImages(), Arrays.asList(stage));

        dialog.backForTest();

        assertEquals(ConfigQcResult.BACK, dialog.resultForTest());
        assertEquals(1, stage.leaveCount);
    }

    @Test
    public void actionsUpdateStatusAndPreviewState() {
        RecordingStage stage = new RecordingStage("Filter");
        ConfigQcDialog dialog = ConfigQcDialog.createForTest(contextWithTwoImages(), Arrays.asList(stage));

        stage.actions.setStatus("Waiting for preview.");
        assertEquals("Waiting for preview.", dialog.statusTextForTest());

        stage.actions.setAdjustedPreview(stack("Adjusted", 2), "Preview complete.");
        assertEquals("Preview complete.", dialog.statusTextForTest());

        stage.actions.markPreviewStale("Press Preview Filter.");
        assertEquals("Press Preview Filter.", dialog.statusTextForTest());
    }

    @Test
    public void jumpToStageActionRebuildsInsideSameDialog() {
        RecordingStage first = new RecordingStage("Preview stage");
        SnapshotStage method = new SnapshotStage();
        ConfigQcDialog dialog = ConfigQcDialog.createForTest(
                contextWithTwoImages(), Arrays.<ConfigQcStage>asList(first, method));

        first.actions.jumpToStage(SnapshotStage.class.getName());

        assertEquals(1, dialog.stageIndexForTest());
        assertEquals("Snapshot", dialog.stageTextForTest());
        assertEquals(1, first.leaveCount);
        assertEquals("Segmentation method changed.", dialog.statusTextForTest());
    }

    @Test
    public void jumpToStageDetachesPreviewImagesBeforeClosingPreviousStage() {
        ClosingPreviewStage first = new ClosingPreviewStage();
        SnapshotStage method = new SnapshotStage();
        ConfigQcDialog dialog = ConfigQcDialog.createForTest(
                contextWithTwoImages(), Arrays.<ConfigQcStage>asList(first, method));

        first.actions.jumpToStage(SnapshotStage.class.getName());

        assertEquals(1, dialog.stageIndexForTest());
        assertEquals("Snapshot", dialog.stageTextForTest());
        assertEquals(1, first.leaveCount);
        assertTrue(first.closedPreviewImages);
        assertEquals("Segmentation method changed.", dialog.statusTextForTest());
    }

    @Test(timeout = 3000)
    public void shellOpensWithDummyControlsWhenDisplayIsAvailable() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                RecordingStage stage = new RecordingStage("Dummy stage");
                final ConfigQcDialog dialog = ConfigQcDialog.createModeless(
                        null, contextWithTwoImages(), Arrays.asList(stage));
                Timer closer = new Timer(75, e -> dialog.actionsForTest().cancel());
                closer.setRepeats(false);
                closer.start();

                ConfigQcResult result = dialog.showDialog();

                assertEquals(ConfigQcResult.CANCEL, result);
                assertEquals(1, stage.enterCount);
                assertEquals(1, stage.leaveCount);
            }
        });
    }

    private static ConfigQcContext contextWithTwoImages() {
        return ConfigQcContext.fromImages(
                null,
                null,
                null,
                Arrays.asList(stack("Image A", 3), stack("Image B", 3)),
                Arrays.asList("DAPI", "IBA1"),
                1);
    }

    private static ConfigQcContext contextWithOneImage() {
        return ConfigQcContext.fromImages(
                null,
                null,
                null,
                Arrays.asList(stack("Image A", 3)),
                Arrays.asList("DAPI", "IBA1"),
                1);
    }

    private static ImagePlus stack(String title, int slices) {
        ImageStack stack = new ImageStack(3, 3);
        for (int i = 0; i < slices; i++) {
            ByteProcessor processor = new ByteProcessor(3, 3);
            processor.set(1, 1, i + 1);
            stack.addSlice(processor);
        }
        return new ImagePlus(title, stack);
    }

    private static JComponent footerFrom(ConfigQcDialog dialog) {
        BorderLayout layout = (BorderLayout) dialog.getContent().getLayout();
        return (JComponent) layout.getLayoutComponent(BorderLayout.SOUTH);
    }

    private static boolean containsComponent(Component root, Component target) {
        if (root == target) {
            return true;
        }
        if (root instanceof Container) {
            Component[] children = ((Container) root).getComponents();
            for (int i = 0; i < children.length; i++) {
                if (containsComponent(children[i], target)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean visibleRadioButtonWithText(Component root, String text) {
        if (root instanceof JRadioButton
                && text.equals(((JRadioButton) root).getText())
                && root.isVisible()) {
            return true;
        }
        if (root instanceof Container) {
            Component[] children = ((Container) root).getComponents();
            for (int i = 0; i < children.length; i++) {
                if (visibleRadioButtonWithText(children[i], text)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static class RecordingStage implements ConfigQcStage {
        private final String title;
        private int enterCount;
        private int leaveCount;
        private int lockCount;
        private int skipCount;
        private int restartCount;
        private ConfigQcActions actions;
        private boolean previewDisplayControls = true;

        RecordingStage(String title) {
            this.title = title;
        }

        @Override public String title() {
            return title;
        }

        @Override public boolean showPreviewDisplayControls() {
            return previewDisplayControls;
        }

        @Override public void onEnter(ConfigQcContext context, PreviewPairPanel preview) {
            enterCount++;
        }

        @Override public JComponent buildControls(ConfigQcContext context, ConfigQcActions actions) {
            this.actions = actions;
            JPanel panel = new JPanel();
            panel.add(new JLabel(title));
            return panel;
        }

        @Override public boolean lockIn(ConfigQcContext context) {
            lockCount++;
            return true;
        }

        @Override public void skipCurrentImage(ConfigQcContext context) {
            skipCount++;
        }

        @Override public void restartStage(ConfigQcContext context) {
            restartCount++;
        }

        @Override public void onLeave(ConfigQcContext context) {
            leaveCount++;
        }
    }

    private static final class SnapshotStage implements ConfigQcStage {
        private JTextField field;
        private String restartValue = "initial";
        private int previousSnapshotCount;
        private int restartImageIndex = -1;

        @Override public String title() {
            return "Snapshot";
        }

        @Override public JComponent buildControls(ConfigQcContext context, ConfigQcActions actions) {
            field = new JTextField(restartValue);
            JPanel panel = new JPanel();
            panel.add(field);
            return panel;
        }

        @Override public boolean lockIn(ConfigQcContext context) {
            return true;
        }

        @Override public void previousImage(ConfigQcContext context) {
            previousSnapshotCount++;
            restartImageIndex = context.getCurrentImageIndex();
            restartValue = currentValueForTest();
        }

        void setValueForTest(String value) {
            field.setText(value);
        }

        String currentValueForTest() {
            return field == null ? "" : field.getText();
        }
    }

    private static final class ClosingPreviewStage implements ConfigQcStage {
        private ConfigQcActions actions;
        private ImagePlus originalPreview;
        private ImagePlus adjustedPreview;
        private int leaveCount;
        private boolean closedPreviewImages;

        @Override public String title() {
            return "Closing Preview";
        }

        @Override public void onEnter(ConfigQcContext context, PreviewPairPanel preview) {
            originalPreview = stack("temporary original", 3);
            adjustedPreview = stack("temporary adjusted", 3);
            preview.setOriginal(originalPreview);
            preview.setAdjusted(adjustedPreview);
        }

        @Override public JComponent buildControls(ConfigQcContext context, ConfigQcActions actions) {
            this.actions = actions;
            JPanel panel = new JPanel();
            panel.add(new JLabel("Closing Preview"));
            return panel;
        }

        @Override public boolean lockIn(ConfigQcContext context) {
            return true;
        }

        @Override public void onLeave(ConfigQcContext context) {
            leaveCount++;
            if (originalPreview != null) {
                originalPreview.flush();
            }
            if (adjustedPreview != null) {
                adjustedPreview.flush();
            }
            closedPreviewImages = true;
        }
    }

    private static final class StoredStage implements ConfigQcStage {
        private JTextField field;
        private String storedValue = "initial";

        @Override public String title() {
            return "Stored";
        }

        @Override public JComponent buildControls(ConfigQcContext context, ConfigQcActions actions) {
            field = new JTextField(storedValue);
            JPanel panel = new JPanel();
            panel.add(field);
            return panel;
        }

        @Override public boolean lockIn(ConfigQcContext context) {
            storedValue = currentValueForTest();
            return true;
        }

        void setValueForTest(String value) {
            field.setText(value);
        }

        String currentValueForTest() {
            return field == null ? "" : field.getText();
        }
    }
}
