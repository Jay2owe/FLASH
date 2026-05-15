package flash.pipeline.ui.config;

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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StarDistParameterStageModelDropdownTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void stockEntriesAppearBeforeUserEntries() throws Exception {
        File root = projectWithUserModel("user_alpha", "AAA User StarDist", 0.61, 0.22);
        StarDistParameterStage stage = new StarDistParameterStage(
                new RecordingStore("stardist:0.5:0.4"),
                new RecordingPreviewAdapter());

        stage.buildControls(context(root), new RecordingActions());

        List<String> keys = stage.modelKeysForTest();
        int firstUser = keys.indexOf("user_alpha");
        assertTrue(firstUser > 0);
        for (int i = 0; i < firstUser; i++) {
            assertTrue("Stock entries should appear before user entries: " + keys,
                    keys.get(i).startsWith("stardist_"));
        }
        assertTrue(stage.manageModelsButtonEnabledForTest());
    }

    @Test
    public void selectingModelUpdatesTokenAndProbNmsDefaults() throws Exception {
        File root = projectWithUserModel("user_microglia_iba1_v3",
                "User Microglia IBA1", 0.61, 0.22);
        RecordingStore store = new RecordingStore(
                "stardist:0.5:0.4:linking=7.0:area=2.0-20.0:model=stardist_versatile_fluo");
        StarDistParameterStage stage = new StarDistParameterStage(
                store, new RecordingPreviewAdapter());

        stage.buildControls(context(root), new RecordingActions());
        stage.selectModelForTest("user_microglia_iba1_v3");

        assertEquals("user_microglia_iba1_v3", stage.selectedModelKeyForTest());
        assertTrue(stage.defaultsApplyVisibleForTest());
        assertEquals("stardist:0.5:0.4:linking=7.0:area=2.0-20.0:"
                        + "model=stardist_versatile_fluo",
                store.token);
        assertEquals("stardist:0.61:0.22:linking=7.0:area=2.0-20.0:"
                        + "model=user_microglia_iba1_v3",
                stage.currentMethodForTest());
        stage.applyPendingDefaultsForTest();
        assertEquals("stardist:0.61:0.22:linking=7.0:area=2.0-20.0:"
                        + "model=user_microglia_iba1_v3",
                store.token);
        assertEquals(store.token, stage.currentMethodForTest());
    }

    @Test
    public void selectingModelRerunsPreviewWhenPreviewIsActive() throws Exception {
        File root = projectWithUserModel("user_preview", "User Preview", 0.62, 0.23);
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        StarDistParameterStage stage = new StarDistParameterStage(
                new RecordingStore("stardist:0.5:0.4"),
                adapter);

        stage.buildControls(context(root), new RecordingActions());
        stage.onEnter(context(root), new PreviewPairPanel("Original", "Adjusted"));
        stage.runPreviewNowForTest();
        adapter.previewRuns = 0;

        stage.selectModelForTest("user_preview");
        assertEquals(0, adapter.previewRuns);
        stage.applyPendingDefaultsForTest();
        waitForPreviewRuns(adapter, 1);

        assertEquals("user_preview", adapter.lastPreviewParameters.modelKey);
        assertEquals(0.62, adapter.lastPreviewParameters.probabilityThreshold, 0.001);
        assertEquals(0.23, adapter.lastPreviewParameters.nmsThreshold, 0.001);
    }

    private File projectWithUserModel(String key, String name, double prob, double nms) throws Exception {
        Path root = temp.newFolder(key + "-root").toPath();
        Path source = temp.newFile(key + ".zip").toPath();
        Files.write(source, "model".getBytes(StandardCharsets.UTF_8));
        ModelCatalog catalog = ModelCatalogIO.read(root);
        catalog.add(userStarDist(key, name, prob, nms), source);
        ModelCatalogIO.writeProject(root, catalog);
        return root.toFile();
    }

    private static ConfigQcContext context(File root) {
        return ConfigQcContext.fromImages(
                root,
                null,
                null,
                Arrays.asList(image()),
                Arrays.asList("IBA1"),
                0);
    }

    private static ImagePlus image() {
        ByteProcessor processor = new ByteProcessor(3, 3);
        processor.set(1, 1, 7);
        return new ImagePlus("QC", processor);
    }

    private static ModelEntry userStarDist(String key, String name, double prob, double nms) {
        return new ModelEntry(key, name, null,
                ModelEntry.Engine.STARDIST, ModelEntry.Source.USER_IMPORTED,
                null, null, null, null, null,
                defaults(prob, nms), null, false);
    }

    private static Map<String, Object> defaults(double prob, double nms) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("probThresh", Double.valueOf(prob));
        out.put("nmsThresh", Double.valueOf(nms));
        return out;
    }

    private static final class RecordingStore implements StarDistParameterStage.ParameterStore {
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

    private static final class RecordingPreviewAdapter implements StarDistParameterStage.PreviewAdapter {
        int previewRuns;
        StarDistParameterStage.Parameters lastPreviewParameters;

        @Override public ImagePlus createRawSource(ConfigQcContext context) {
            return context.getCurrentImagePlus().duplicate();
        }

        @Override public ImagePlus createFilteredSource(ConfigQcContext context) {
            return context.getCurrentImagePlus().duplicate();
        }

        @Override public ImagePlus runPreview(ImagePlus filteredSource,
                                              StarDistParameterStage.Parameters parameters) {
            previewRuns++;
            lastPreviewParameters = parameters;
            return new ImagePlus("labels", new ByteProcessor(1, 1));
        }

        @Override public int countLabels(ImagePlus labelImage) {
            return 0;
        }

        @Override public void close(ImagePlus image) {
            if (image != null) image.flush();
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
