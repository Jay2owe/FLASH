package flash.pipeline.ui.config;

import flash.pipeline.segmentation.catalog.ModelCatalog;
import flash.pipeline.segmentation.catalog.ModelCatalogIO;
import flash.pipeline.segmentation.catalog.ModelEntry;
import flash.pipeline.ui.preview.PreviewPairPanel;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JRadioButton;
import java.io.File;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

public class SegmentationMethodStageTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

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
    public void stageKeyRoutesClassicalToMergedStageAndAiToAiStages() {
        assertEquals(ClassicalSegmentationStage.class.getName(),
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
    public void trainedRfTokenLoadsAsCatalogNamedChoiceAndRoundTripsUnchanged() throws Exception {
        File projectRoot = temp.newFolder("project");
        writeSmileRfCatalog(projectRoot, "test_model_key", "Test Microglia RF");
        String token = "trained_rf:test_model_key:base=classical";
        TokenStore store = new TokenStore(token);
        SegmentationMethodStage stage = new SegmentationMethodStage(store);
        ConfigQcContext context = ConfigQcContext.fromImages(
                projectRoot,
                null,
                null,
                Collections.<ImagePlus>emptyList(),
                Arrays.asList("IBA1"),
                0);

        JComponent controls = stage.buildControls(context, new RecordingActions());
        JRadioButton selected = selectedRadio(controls);

        assertNotNull(selected);
        assertEquals(token, selected.getActionCommand());
        assertEquals("Trained RF: Test Microglia RF", selected.getText());
        assertNotEquals(SegmentationMethodStage.CLASSICAL, selected.getActionCommand());

        stage.lockIn(context);

        assertEquals(token, store.token);
    }

    @Test
    public void trainedRfOverDeepBaseDisplaysBaseInPickerLabel() throws Exception {
        File projectRoot = temp.newFolder("project-deep-rf");
        writeSmileRfCatalog(projectRoot, "microglia_v3", "microglia_v3");

        assertPickerLabel(projectRoot,
                "trained_rf:microglia_v3:"
                        + "base=stardist%3A0.5%3A0.3%3Aarea%3D20-2000%3Amodel%3Duser_stardist_v1",
                "Trained RF (over StarDist): microglia_v3");
        assertPickerLabel(projectRoot,
                "trained_rf:microglia_v3:"
                        + "base=cellpose%3A30.0%3A0.4%3A0.0%3Agpu%3Dfalse%3Amodel%3Dcellpose_cyto3",
                "Trained RF (over Cellpose): microglia_v3");
    }

    @Test
    public void selectingClassicalAfterTrainedRfWritesPlainClassical() {
        String token = "trained_rf:test_model_key:base=classical";
        TokenStore store = new TokenStore(token);
        SegmentationMethodStage stage = new SegmentationMethodStage(store);
        JComponent controls = stage.buildControls(null, new RecordingActions());

        selectRadio(controls, SegmentationMethodStage.CLASSICAL);
        stage.lockIn(null);

        assertEquals("classical", store.token);
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

    private static JRadioButton selectedRadio(java.awt.Component component) {
        if (component instanceof JRadioButton) {
            JRadioButton button = (JRadioButton) component;
            if (button.isSelected()) {
                return button;
            }
        }
        if (component instanceof java.awt.Container) {
            java.awt.Component[] children = ((java.awt.Container) component).getComponents();
            for (int i = 0; i < children.length; i++) {
                JRadioButton found = selectedRadio(children[i]);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void assertPickerLabel(File projectRoot, String token, String expected) {
        TokenStore store = new TokenStore(token);
        SegmentationMethodStage stage = new SegmentationMethodStage(store);
        ConfigQcContext context = ConfigQcContext.fromImages(
                projectRoot,
                null,
                null,
                Collections.<ImagePlus>emptyList(),
                Arrays.asList("IBA1"),
                0);

        JComponent controls = stage.buildControls(context, new RecordingActions());
        JRadioButton selected = selectedRadio(controls);

        assertNotNull(selected);
        assertEquals(token, selected.getActionCommand());
        assertEquals(expected, selected.getText());
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

    private static void writeSmileRfCatalog(File projectRoot, String modelKey, String name) throws Exception {
        ModelEntry entry = new ModelEntry(
                modelKey,
                name,
                "Test RF model",
                ModelEntry.Engine.SMILE_RF,
                ModelEntry.Source.USER_TRAINED,
                "files/" + modelKey + "/model.model",
                null,
                null,
                null,
                null,
                Collections.<String, Object>emptyMap(),
                Collections.<String, Object>emptyMap(),
                false);
        ModelCatalogIO.writeProject(projectRoot.toPath(),
                new ModelCatalog(projectRoot.toPath(), Collections.singletonList(entry)));
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

    private static final class TokenStore implements SegmentationMethodStage.MethodStore {
        String token;

        TokenStore(String token) {
            this.token = token;
        }

        @Override public String getChoice() {
            return SegmentationMethodStage.choiceForMethodToken(token);
        }

        @Override public boolean selectChoice(String choice) {
            if (SegmentationMethodStage.STARDIST.equals(choice)) {
                token = "stardist:0.5:0.4";
            } else if (SegmentationMethodStage.CELLPOSE.equals(choice)) {
                token = "cellpose:30.0:0.4:0.0";
            } else if (SegmentationMethodStage.ENHANCED_CLASSICAL.equals(choice)) {
                token = "enhanced_classical:thresh=1:minSize=1:maxSize=10";
            } else {
                token = "classical";
            }
            return true;
        }

        @Override public String getMethodToken() {
            return token;
        }

        @Override public void setMethodToken(String methodToken) {
            token = methodToken;
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
