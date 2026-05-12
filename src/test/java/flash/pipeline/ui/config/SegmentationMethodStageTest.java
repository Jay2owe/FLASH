package flash.pipeline.ui.config;

import flash.pipeline.ui.preview.PreviewPairPanel;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Test;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JRadioButton;
import java.lang.reflect.Field;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class SegmentationMethodStageTest {

    @Test
    public void lockInStoresChoiceAndMovesStraightToMethodSpecificStage() {
        RecordingStore store = new RecordingStore();
        SegmentationMethodStage methodStage = new SegmentationMethodStage(store);
        RecordingStage nextStage = new RecordingStage();
        ConfigQcContext context = ConfigQcContext.fromImages(
                null,
                null,
                null,
                Arrays.asList(stack("Image A"), stack("Image B")),
                Arrays.asList("DAPI"),
                0);

        ConfigQcDialog dialog = ConfigQcDialog.createForTest(
                context,
                Arrays.<ConfigQcStage>asList(methodStage, nextStage));
        selectRadio(dialog.getContent(), SegmentationMethodStage.STARDIST);

        dialog.lockInForTest();

        assertEquals(SegmentationMethodStage.STARDIST, store.choice);
        assertEquals(1, dialog.stageIndexForTest());
        assertEquals(0, context.getCurrentImageIndex());
        assertEquals(1, nextStage.enterCount);
    }

    @Test
    public void stageKeyRoutesClassicalToThresholdAndAiToAiStages() {
        assertEquals(ChannelThresholdStage.class.getName(),
                SegmentationMethodStage.stageKeyForChoice(SegmentationMethodStage.CLASSICAL));
        assertEquals(StarDistParameterStage.class.getName(),
                SegmentationMethodStage.stageKeyForChoice(SegmentationMethodStage.STARDIST));
        assertEquals(CellposeParameterStage.class.getName(),
                SegmentationMethodStage.stageKeyForChoice(SegmentationMethodStage.CELLPOSE));
    }

    @Test
    public void changeMethodButtonNavigatesToExistingMethodStageWithoutPopup() {
        RecordingStore store = new RecordingStore();
        RecordingActions actions = new RecordingActions();
        JComponent panel = SegmentationMethodStage.buildChangeMethodPanel(store, actions);

        JButton button = findButton(panel, "Change Segmentation Method");
        assertNotNull(button);
        button.doClick();

        assertEquals(SegmentationMethodStage.CLASSICAL, store.choice);
        assertEquals("Choose a segmentation method for this channel.", actions.status);
        assertEquals(SegmentationMethodStage.class.getName(), actions.jumpTarget);
    }

    @Test
    public void onEnterShowsSelectedChannelInsteadOfChannelOne() throws Exception {
        SegmentationMethodStage stage = new SegmentationMethodStage(new RecordingStore());
        ImagePlus source = twoChannelStack("Image A");
        ConfigQcContext context = ConfigQcContext.fromImages(
                null,
                null,
                null,
                Arrays.asList(source),
                Arrays.asList("DAPI", "IBA1"),
                1);
        PreviewPairPanel preview = new PreviewPairPanel("Original", "Adjusted");
        preview.setOriginal(source);

        stage.onEnter(context, preview);

        ImagePlus shown = originalImageFrom(preview);
        assertNotNull(shown);
        assertEquals(1, shown.getNChannels());
        assertEquals(255, shown.getStack().getProcessor(1).getPixel(0, 0));

        stage.onLeave(context);
    }

    private static void selectRadio(JComponent root, String actionCommand) {
        JRadioButton button = findRadio(root, actionCommand);
        if (button == null) {
            throw new AssertionError("No radio button found for " + actionCommand);
        }
        button.setSelected(true);
    }

    private static JRadioButton findRadio(java.awt.Component component, String actionCommand) {
        if (component instanceof JRadioButton) {
            JRadioButton button = (JRadioButton) component;
            if (actionCommand.equals(button.getActionCommand())) {
                return button;
            }
        }
        if (component instanceof java.awt.Container) {
            java.awt.Component[] children = ((java.awt.Container) component).getComponents();
            for (int i = 0; i < children.length; i++) {
                JRadioButton found = findRadio(children[i], actionCommand);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JButton findButton(java.awt.Component component, String text) {
        if (component instanceof JButton) {
            JButton button = (JButton) component;
            if (text.equals(button.getText())) {
                return button;
            }
        }
        if (component instanceof java.awt.Container) {
            java.awt.Component[] children = ((java.awt.Container) component).getComponents();
            for (int i = 0; i < children.length; i++) {
                JButton found = findButton(children[i], text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static ImagePlus stack(String title) {
        ImageStack stack = new ImageStack(2, 2);
        stack.addSlice(new ByteProcessor(2, 2));
        return new ImagePlus(title, stack);
    }

    private static ImagePlus twoChannelStack(String title) {
        ImageStack stack = new ImageStack(2, 2);
        ByteProcessor channelOne = new ByteProcessor(2, 2);
        ByteProcessor channelTwo = new ByteProcessor(2, 2);
        channelOne.set(0, 0, 10);
        channelTwo.set(0, 0, 255);
        stack.addSlice("C1", channelOne);
        stack.addSlice("C2", channelTwo);
        ImagePlus image = new ImagePlus(title, stack);
        image.setDimensions(2, 1, 1);
        image.setOpenAsHyperStack(true);
        image.setPosition(1, 1, 1);
        return image;
    }

    private static ImagePlus originalImageFrom(PreviewPairPanel preview) throws Exception {
        Field field = PreviewPairPanel.class.getDeclaredField("originalImage");
        field.setAccessible(true);
        return (ImagePlus) field.get(preview);
    }

    private static final class RecordingStore implements SegmentationMethodStage.MethodStore {
        String choice = SegmentationMethodStage.CLASSICAL;

        @Override public String getChoice() {
            return choice;
        }

        @Override public boolean selectChoice(String choice) {
            this.choice = choice;
            return true;
        }
    }

    private static final class RecordingActions implements ConfigQcActions {
        String status = "";
        String jumpTarget = "";

        @Override public void setStatus(String text) {
            status = text;
        }

        @Override public void markPreviewStale(String text) {
        }

        @Override public void setAdjustedPreview(ImagePlus image, String text) {
        }

        @Override public void nextImage() {
        }

        @Override public void skipCurrentImage() {
        }

        @Override public void restartStage() {
        }

        @Override public void cancel() {
        }

        @Override public void jumpToStage(String stageKey) {
            jumpTarget = stageKey;
        }
    }

    private static final class RecordingStage implements ConfigQcStage {
        int enterCount;

        @Override public String title() {
            return "Next";
        }

        @Override public JComponent buildControls(ConfigQcContext context, ConfigQcActions actions) {
            return new javax.swing.JPanel();
        }

        @Override public void onEnter(ConfigQcContext context, flash.pipeline.ui.preview.PreviewPairPanel preview) {
            enterCount++;
        }

        @Override public boolean lockIn(ConfigQcContext context) {
            return true;
        }
    }
}
