package flash.pipeline.ui.config;

import flash.pipeline.ui.preview.PreviewPairPanel;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class CellposeParameterStageTest {

    @Test
    public void parsesAndRendersMethodTokenWithCompanionChannel() {
        String token = "cellpose:30.0:cyto3:0.4:0.0:gpu=false:chan2=1";

        CellposeParameterStage.Parameters params =
                CellposeParameterStage.parseMethod(token, true, 3, 0);

        assertEquals(token, CellposeParameterStage.formatMethod(params));
    }

    @Test
    public void unsupportedModelDropsCompanionChannel() {
        CellposeParameterStage.Parameters params =
                CellposeParameterStage.parseMethod(
                        "cellpose:12.0:nuclei:0.5:-1.0:gpu=true:chan2=1",
                        true,
                        3,
                        0);

        assertEquals(-1, params.secondChannelIndex);
        assertFalse(CellposeParameterStage.formatMethod(params).contains("chan2="));
    }

    @Test
    public void textFieldEditMarksPreviewStaleWithoutRunningPreview() {
        RecordingStore store = new RecordingStore("cellpose:30.0:cyto3:0.4:0.0:gpu=false");
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        RecordingActions actions = new RecordingActions();
        CellposeParameterStage stage = stage(store, adapter);

        stage.buildControls(context(), actions);
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));
        adapter.previewRuns = 0;

        stage.setDiameterForTest("44.0");

        assertTrue(stage.isPreviewStaleForTest());
        assertTrue(actions.status.contains("Preview"));
        assertEquals("Field edits must not execute Cellpose preview",
                0, adapter.previewRuns);
    }

    @Test
    public void previewUsesSelectedCompanionChannelOnlyWhenRequested() throws Exception {
        RecordingStore store = new RecordingStore("cellpose:30.0:cyto3:0.4:0.0:gpu=false:chan2=1");
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        RecordingActions actions = new RecordingActions();
        CellposeParameterStage stage = stage(store, adapter);

        stage.buildControls(context(), actions);
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));

        assertEquals(0, adapter.previewRuns);

        stage.runPreviewNowForTest();

        assertEquals(1, adapter.previewRuns);
        assertEquals(1, adapter.lastCompanionIndex);
        assertFalse(stage.isPreviewStaleForTest());
        assertNotNull(actions.adjustedPreview);
        assertEquals("Objects detected: 2", actions.status);
        assertEquals(3, stage.largePreviewPaneCountForTest());
    }

    @Test
    public void sourceToggleSwapsRawAndFilteredWithoutRunningPreview() {
        RecordingStore store = new RecordingStore("cellpose:30.0:cyto3:0.4:0.0:gpu=false");
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        CellposeParameterStage stage = stage(store, adapter);

        stage.buildControls(context(), new RecordingActions());
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));
        adapter.previewRuns = 0;

        assertEquals(1, adapter.rawSourceCreations);
        assertEquals(1, adapter.filteredSourceCreations);
        assertTrue(stage.currentSourceTitleForTest().startsWith("filtered"));
        assertEquals(2, stage.largePreviewPaneCountForTest());

        stage.selectRawSourceForTest();

        assertTrue(stage.currentSourceTitleForTest().startsWith("raw"));
        assertEquals(0, adapter.previewRuns);
    }

    private static CellposeParameterStage stage(RecordingStore store,
                                                RecordingPreviewAdapter adapter) {
        return new CellposeParameterStage(
                store,
                adapter,
                new RecordingRuntimeAdapter(),
                Arrays.asList("Primary", "Companion", "Other"),
                0,
                false);
    }

    private static ConfigQcContext context() {
        return ConfigQcContext.fromImages(
                null,
                null,
                null,
                Arrays.asList(image("QC image")),
                Arrays.asList("Primary", "Companion", "Other"),
                0);
    }

    private static ImagePlus image(String title) {
        ImageStack stack = new ImageStack(3, 3);
        ByteProcessor processor = new ByteProcessor(3, 3);
        processor.set(1, 1, 12);
        stack.addSlice(processor);
        return new ImagePlus(title, stack);
    }

    private static final class RecordingStore implements CellposeParameterStage.ParameterStore {
        String token;

        RecordingStore(String token) {
            this.token = token;
        }

        @Override public String getMethodToken() {
            return token;
        }

        @Override public void save(String methodToken) {
            token = methodToken;
        }
    }

    private static final class RecordingPreviewAdapter implements CellposeParameterStage.PreviewAdapter {
        int rawSourceCreations;
        int filteredSourceCreations;
        int previewRuns;
        int lastCompanionIndex = -2;

        @Override public ImagePlus createRawSource(ConfigQcContext context) {
            rawSourceCreations++;
            ImagePlus source = context.getCurrentImagePlus().duplicate();
            source.setTitle("raw");
            return source;
        }

        @Override public ImagePlus createFilteredSource(ConfigQcContext context) {
            filteredSourceCreations++;
            ImagePlus source = context.getCurrentImagePlus().duplicate();
            source.setTitle("filtered");
            return source;
        }

        @Override public ImagePlus createFilteredCompanionSource(ConfigQcContext context, int channelIndex) {
            lastCompanionIndex = channelIndex;
            ImagePlus companion = context.getCurrentImagePlus().duplicate();
            companion.setTitle("companion");
            return companion;
        }

        @Override public ImagePlus runPreview(ImagePlus filteredSource,
                                              ImagePlus filteredCompanionSource,
                                              CellposeParameterStage.Parameters parameters) {
            previewRuns++;
            ByteProcessor processor = new ByteProcessor(2, 1);
            processor.set(0, 0, 1);
            processor.set(1, 0, 2);
            return new ImagePlus("labels", processor);
        }

        @Override public int countLabels(ImagePlus labelImage) {
            return (int) labelImage.getProcessor().getStats().max;
        }

        @Override public void close(ImagePlus image) {
            if (image != null) image.flush();
        }
    }

    private static final class RecordingRuntimeAdapter implements CellposeParameterStage.RuntimeAdapter {
        @Override public String runtimeSummary() {
            return "Cellpose test runtime.";
        }

        @Override public boolean nvidiaGpuLikelyAvailable() {
            return false;
        }

        @Override public CellposeParameterStage.GpuInstallResult installGpuSupport() {
            return new CellposeParameterStage.GpuInstallResult(false, "not installed", "");
        }
    }

    private static final class RecordingActions implements ConfigQcActions {
        String status = "";
        ImagePlus adjustedPreview;

        @Override public void setStatus(String text) {
            status = text;
        }

        @Override public void markPreviewStale(String text) {
            status = text;
        }

        @Override public void setAdjustedPreview(ImagePlus image, String text) {
            adjustedPreview = image;
            status = text;
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
