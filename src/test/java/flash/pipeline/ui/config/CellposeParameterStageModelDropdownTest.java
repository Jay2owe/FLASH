package flash.pipeline.ui.config;

import flash.pipeline.cellpose.CellposeRuntime;
import flash.pipeline.segmentation.catalog.ModelCatalog;
import flash.pipeline.segmentation.catalog.ModelCatalogIO;
import flash.pipeline.segmentation.catalog.ModelEntry;
import flash.pipeline.ui.preview.PreviewPairPanel;
import ij.ImagePlus;
import ij.process.ByteProcessor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CellposeParameterStageModelDropdownTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void stockEntriesAppearBeforeUserEntries() throws Exception {
        File root = projectWithUserModel("user_alpha", "AAA User Cellpose", 21.0, 0.55, -0.2);
        CellposeParameterStage stage = stage(root,
                new RecordingStore("cellpose:30.0:0.4:0.0:gpu=false:model=cellpose_cyto3"));

        stage.buildControls(context(root), new RecordingActions());

        List<String> keys = stage.modelKeysForTest();
        int firstUser = keys.indexOf("user_alpha");
        assertTrue(firstUser > 0);
        for (int i = 0; i < firstUser; i++) {
            assertTrue("Stock entries should appear before user entries: " + keys,
                    keys.get(i).startsWith("cellpose_"));
        }
        assertTrue(stage.manageModelsButtonEnabledForTest());
    }

    @Test
    public void selectingModelUpdatesTokenAndDetectionDefaults() throws Exception {
        File root = projectWithUserModel("user_microglia_iba1_v3",
                "User Microglia IBA1", 22.0, 0.7, -0.1);
        RecordingStore store = new RecordingStore(
                "cellpose:30.0:0.4:0.0:gpu=false:model=cellpose_cyto3");
        CellposeParameterStage stage = stage(root, store);

        stage.buildControls(context(root), new RecordingActions());
        stage.setModelForTest("user_microglia_iba1_v3");

        assertEquals("user_microglia_iba1_v3", stage.selectedModelKeyForTest());
        assertTrue(stage.defaultsApplyVisibleForTest());
        assertEquals("cellpose:30.0:0.4:0.0:gpu=false:model=cellpose_cyto3",
                store.token);
        assertEquals("cellpose:22.0:0.7:-0.1:gpu=false:model=user_microglia_iba1_v3",
                stage.currentMethodForTest());
        stage.applyPendingDefaultsForTest();
        assertEquals("cellpose:22.0:0.7:-0.1:gpu=false:model=user_microglia_iba1_v3",
                store.token);
        assertEquals(store.token, stage.currentMethodForTest());
    }

    @Test
    public void selectingModelRerunsPreviewWhenPreviewIsActive() throws Exception {
        File root = projectWithUserModel("user_preview", "User Preview", 24.0, 0.6, 0.1);
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        CellposeParameterStage stage = stage(root,
                new RecordingStore("cellpose:30.0:0.4:0.0:gpu=false:model=cellpose_cyto3"),
                adapter);

        stage.buildControls(context(root), new RecordingActions());
        stage.onEnter(context(root), new PreviewPairPanel("Original", "Adjusted"));
        stage.runPreviewNowForTest();
        adapter.previewRuns = 0;

        stage.setModelForTest("user_preview");
        assertEquals(0, adapter.previewRuns);
        stage.applyPendingDefaultsForTest();
        waitForPreviewRuns(adapter, 1);

        assertEquals("user_preview", adapter.lastParameters.modelToken);
        assertEquals(24.0, adapter.lastParameters.diameter, 0.001);
        assertEquals(0.6, adapter.lastParameters.flowThreshold, 0.001);
        assertEquals(0.1, adapter.lastParameters.cellprobThreshold, 0.001);
    }

    @Test
    public void missingModelOnReloadShowsReplacementSelector() throws Exception {
        File root = temp.newFolder("missing-root");
        CellposeParameterStage stage = stage(root,
                new RecordingStore("cellpose:20.0:0.5:0.1:gpu=false:model=user_missing"));

        stage.buildControls(context(root), new RecordingActions());

        assertTrue(stage.missingModelNoticeTextForTest().contains("user_missing"));
        assertTrue(stage.replacementSelectorVisibleForTest());
        assertEquals("cellpose_cyto3", stage.selectedModelKeyForTest());
    }

    private File projectWithUserModel(String key, String name,
                                      double diameter, double flow,
                                      double cellprob) throws Exception {
        Path root = temp.newFolder(key + "-root").toPath();
        Path source = temp.newFile(key + ".cellpose").toPath();
        Files.write(source, "model".getBytes(StandardCharsets.UTF_8));
        ModelCatalog catalog = ModelCatalogIO.read(root);
        catalog.add(userCellpose(key, name, diameter, flow, cellprob), source);
        ModelCatalogIO.writeProject(root, catalog);
        return root.toFile();
    }

    private static CellposeParameterStage stage(File root, RecordingStore store) {
        return stage(root, store, new RecordingPreviewAdapter());
    }

    private static CellposeParameterStage stage(File root, RecordingStore store,
                                                RecordingPreviewAdapter adapter) {
        return new CellposeParameterStage(
                store,
                new RecordingSizeStore("0-Infinity"),
                adapter,
                new RecordingRuntimeAdapter(),
                Arrays.asList("Primary", "Companion"),
                0,
                false);
    }

    private static ConfigQcContext context(File root) {
        return ConfigQcContext.fromImages(
                root,
                null,
                null,
                Arrays.asList(new ImagePlus("QC", new ByteProcessor(3, 3))),
                Arrays.asList("Primary", "Companion"),
                0);
    }

    private static ModelEntry userCellpose(String key, String name,
                                           double diameter, double flow,
                                           double cellprob) {
        return new ModelEntry(key, name, null,
                ModelEntry.Engine.CELLPOSE, ModelEntry.Source.USER_IMPORTED,
                null, null, null, null, null,
                defaults(diameter, flow, cellprob), null, false);
    }

    private static Map<String, Object> defaults(double diameter, double flow,
                                                double cellprob) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("diameter", Double.valueOf(diameter));
        out.put("flowThreshold", Double.valueOf(flow));
        out.put("cellprobThreshold", Double.valueOf(cellprob));
        return out;
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

    private static final class RecordingSizeStore implements CellposeParameterStage.SizeStore {
        final String token;

        RecordingSizeStore(String token) {
            this.token = token;
        }

        @Override public String get() {
            return token;
        }

        @Override public void set(String token) {
        }
    }

    private static final class RecordingPreviewAdapter implements CellposeParameterStage.PreviewAdapter {
        int previewRuns;
        CellposeParameterStage.Parameters lastParameters;

        @Override public ImagePlus createRawSource(ConfigQcContext context) {
            return context.getCurrentImagePlus().duplicate();
        }

        @Override public ImagePlus createFilteredSource(ConfigQcContext context) {
            return context.getCurrentImagePlus().duplicate();
        }

        @Override public ImagePlus createFilteredCompanionSource(ConfigQcContext context, int channelIndex) {
            return context.getCurrentImagePlus().duplicate();
        }

        @Override public ImagePlus runPreview(ImagePlus filteredSource,
                                              ImagePlus filteredCompanionSource,
                                              CellposeParameterStage.Parameters parameters) {
            previewRuns++;
            lastParameters = parameters;
            return new ImagePlus("labels", new ByteProcessor(1, 1));
        }

        @Override public int countLabels(ImagePlus labelImage) {
            return 0;
        }

        @Override public void close(ImagePlus image) {
            if (image != null) image.flush();
        }
    }

    private static final class RecordingRuntimeAdapter implements CellposeParameterStage.RuntimeAdapter {
        @Override public CellposeRuntime.Status cachedRuntimeStatus() {
            return CellposeRuntime.probe("");
        }

        @Override public CompletableFuture<CellposeRuntime.Status> probeRuntimeAsync() {
            return CompletableFuture.completedFuture(CellposeRuntime.probe(""));
        }

        @Override public boolean nvidiaGpuLikelyAvailable() {
            return false;
        }

        @Override public CellposeParameterStage.GpuInstallResult installGpuSupport() {
            return new CellposeParameterStage.GpuInstallResult(false, "not installed", "");
        }
    }

    private static final class RecordingActions implements ConfigQcActions {
        @Override public void setStatus(String text) {
        }

        @Override public void markPreviewStale(String text) {
        }

        @Override public void setAdjustedPreview(ImagePlus image, String text) {
        }

        @Override public void registerPreviewButton(javax.swing.JButton button) {
        }

        @Override public void setPreviewButtonStale(boolean stale) {
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

    private static void waitForPreviewRuns(RecordingPreviewAdapter adapter,
                                           int expectedRuns) throws Exception {
        long deadline = System.currentTimeMillis() + 3000L;
        while (System.currentTimeMillis() < deadline && adapter.previewRuns < expectedRuns) {
            Thread.sleep(10L);
        }
        javax.swing.SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
            }
        });
        assertEquals(expectedRuns, adapter.previewRuns);
    }
}
