package flash.pipeline.ui.config;

import flash.pipeline.cellpose.CellposeRuntime;
import flash.pipeline.segmentation.SegmentationRunFailureException;
import flash.pipeline.ui.preview.PreviewPairPanel;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Test;

import javax.swing.JButton;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class Cellpose3DRunnerErrorSurfacingTest {

    @Test
    public void runnerFailureMessageReachesPreviewStatus() throws Exception {
        RecordingActions actions = new RecordingActions();
        CellposeParameterStage stage = stage(
                new Store("cellpose:30.0:cyto3:0.4:0.0:gpu=false"),
                Adapter.failing("Cellpose failed: Python environment is broken"));

        stage.buildControls(context(), actions);
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));
        stage.runPreviewNowForTest();

        assertTrue(actions.status.contains("Python environment is broken"));
        assertFalse(actions.status.contains("returned no label map"));
        assertTrue(actions.previewButtonStale);
    }

    @Test
    public void longRunnerFailureMessageIsTruncatedWithLogHint() throws Exception {
        RecordingActions actions = new RecordingActions();
        CellposeParameterStage stage = stage(
                new Store("cellpose:30.0:cyto3:0.4:0.0:gpu=false"),
                Adapter.failing("Cellpose failed: " + repeat("Python traceback line ", 20)));

        stage.buildControls(context(), actions);
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));
        stage.runPreviewNowForTest();

        assertTrue(actions.status.startsWith("Cellpose preview failed: Cellpose failed: Python traceback line"));
        assertTrue(actions.status.endsWith("...see log for full"));
        assertTrue(actions.status.length() <= 200);
    }

    @Test
    public void nullLabelMapStillReportsNoLabelMap() throws Exception {
        RecordingActions actions = new RecordingActions();
        CellposeParameterStage stage = stage(
                new Store("cellpose:30.0:cyto3:0.4:0.0:gpu=false"),
                Adapter.nullLabelMap());

        stage.buildControls(context(), actions);
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));
        stage.runPreviewNowForTest();

        assertEquals("Cellpose returned no label map.", actions.status);
        assertTrue(actions.previewButtonStale);
    }

    @Test
    public void emptyLabelMapCompletesWithoutNoLabelError() throws Exception {
        RecordingActions actions = new RecordingActions();
        CellposeParameterStage stage = stage(
                new Store("cellpose:30.0:cyto3:0.4:0.0:gpu=false"),
                Adapter.emptyLabelMap());

        stage.buildControls(context(), actions);
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));
        stage.runPreviewNowForTest();

        assertEquals("Objects: 0 ready", actions.status);
        assertNotNull(actions.adjustedPreview);
    }

    private static CellposeParameterStage stage(Store store, Adapter adapter) {
        return new CellposeParameterStage(
                store,
                new SizeStore(),
                adapter,
                new RuntimeAdapter(),
                Arrays.asList("Primary", "Companion"),
                0,
                false);
    }

    private static ConfigQcContext context() {
        return ConfigQcContext.fromImages(
                null,
                null,
                null,
                Arrays.asList(image("QC image")),
                Arrays.asList("Primary", "Companion"),
                0);
    }

    private static ImagePlus image(String title) {
        ImageStack stack = new ImageStack(3, 3);
        ByteProcessor processor = new ByteProcessor(3, 3);
        processor.set(1, 1, 12);
        stack.addSlice(processor);
        return new ImagePlus(title, stack);
    }

    private static String repeat(String text, int times) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < times; i++) {
            out.append(text);
        }
        return out.toString();
    }

    private static final class Store implements CellposeParameterStage.ParameterStore {
        private final String token;

        Store(String token) {
            this.token = token;
        }

        @Override public String getMethodToken() {
            return token;
        }

        @Override public void save(String methodToken) {
        }
    }

    private static final class SizeStore implements CellposeParameterStage.SizeStore {
        @Override public String get() {
            return "0-Infinity";
        }

        @Override public void set(String token) {
        }
    }

    private static final class Adapter implements CellposeParameterStage.PreviewAdapter {
        private final String failureMessage;
        private final boolean returnNull;

        static Adapter failing(String message) {
            return new Adapter(message, false);
        }

        static Adapter nullLabelMap() {
            return new Adapter(null, true);
        }

        static Adapter emptyLabelMap() {
            return new Adapter(null, false);
        }

        private Adapter(String failureMessage, boolean returnNull) {
            this.failureMessage = failureMessage;
            this.returnNull = returnNull;
        }

        @Override public ImagePlus createRawSource(ConfigQcContext context) {
            return context.getCurrentImagePlus().duplicate();
        }

        @Override public ImagePlus createFilteredSource(ConfigQcContext context) {
            return context.getCurrentImagePlus().duplicate();
        }

        @Override public ImagePlus createFilteredCompanionSource(ConfigQcContext context,
                                                                 int channelIndex) {
            return context.getCurrentImagePlus().duplicate();
        }

        @Override public ImagePlus runPreview(ImagePlus filteredSource,
                                              ImagePlus filteredCompanionSource,
                                              CellposeParameterStage.Parameters parameters) {
            if (failureMessage != null) {
                throw new SegmentationRunFailureException(
                        failureMessage,
                        new IllegalStateException(failureMessage));
            }
            if (returnNull) {
                return null;
            }
            return new ImagePlus("empty labels", new ByteProcessor(3, 3));
        }

        @Override public int countLabels(ImagePlus labelImage) {
            return labelImage == null ? 0 : (int) labelImage.getProcessor().getStats().max;
        }

        @Override public void close(ImagePlus image) {
            if (image != null) image.flush();
        }
    }

    private static final class RuntimeAdapter implements CellposeParameterStage.RuntimeAdapter {
        @Override public CellposeRuntime.Status cachedRuntimeStatus() {
            return CellposeRuntime.Status.unknown();
        }

        @Override public CompletableFuture<CellposeRuntime.Status> probeRuntimeAsync() {
            return CompletableFuture.completedFuture(CellposeRuntime.Status.unknown());
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
        boolean previewButtonStale;

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

        @Override public void setPreviewButtonStale(boolean stale) {
            previewButtonStale = stale;
        }

        @Override public void registerPreviewButton(JButton button) {
            previewButtonStale = true;
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
