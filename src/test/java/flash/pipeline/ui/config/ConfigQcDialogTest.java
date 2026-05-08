package flash.pipeline.ui.config;

import flash.pipeline.ui.preview.PreviewPairPanel;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Test;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assume.assumeFalse;

public class ConfigQcDialogTest {

    @Test
    public void constructsShellWithFakeStageInHeadlessSafePath() {
        RecordingStage stage = new RecordingStage("Display range");
        ConfigQcContext context = contextWithTwoImages();

        ConfigQcDialog dialog = ConfigQcDialog.createForTest(context, Arrays.asList(stage));

        assertEquals("Set Up Configuration QC", dialog.titleTextForTest());
        assertEquals("Display range", dialog.stageTextForTest());
        assertEquals("C2 - IBA1", dialog.channelTextForTest());
        assertEquals("Stage 1 / 1    Image 1 / 2", dialog.progressTextForTest());
        assertEquals("Image A", dialog.imageTextForTest());
        assertEquals(1, stage.enterCount);
        assertNotNull(stage.actions);
        assertSame(dialog.previewForTest().largeViewButton(), dialog.largeViewButtonForTest());
    }

    @Test
    public void lockInAdvancesImagesBeforeMovingToNextStage() {
        RecordingStage first = new RecordingStage("First");
        RecordingStage second = new RecordingStage("Second");
        ConfigQcContext context = contextWithTwoImages();
        ConfigQcDialog dialog = ConfigQcDialog.createForTest(context, Arrays.asList(first, second));

        dialog.lockInForTest();

        assertEquals(0, dialog.stageIndexForTest());
        assertEquals(1, context.getCurrentImageIndex());
        assertEquals(1, first.lockCount);
        assertEquals("Image B", dialog.imageTextForTest());

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

        dialog.skipForTest();

        assertEquals(1, stage.skipCount);
        assertEquals(1, context.getCurrentImageIndex());

        dialog.restartForTest();

        assertEquals(1, stage.restartCount);
        assertEquals(0, context.getCurrentImageIndex());
        assertEquals("Image A", dialog.imageTextForTest());
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

    private static ImagePlus stack(String title, int slices) {
        ImageStack stack = new ImageStack(3, 3);
        for (int i = 0; i < slices; i++) {
            ByteProcessor processor = new ByteProcessor(3, 3);
            processor.set(1, 1, i + 1);
            stack.addSlice(processor);
        }
        return new ImagePlus(title, stack);
    }

    private static final class RecordingStage implements ConfigQcStage {
        private final String title;
        private int enterCount;
        private int leaveCount;
        private int lockCount;
        private int skipCount;
        private int restartCount;
        private ConfigQcActions actions;

        RecordingStage(String title) {
            this.title = title;
        }

        @Override public String title() {
            return title;
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
}
