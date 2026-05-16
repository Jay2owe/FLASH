package flash.pipeline.ui.config;

import flash.pipeline.cellpose.CellposeRuntime;
import flash.pipeline.segmentation.catalog.ModelEntry;
import ij.ImagePlus;
import ij.process.ByteProcessor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.swing.JButton;
import javax.swing.JComponent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class CellposeMissingModelBannerButtonTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void missingModelBannerManageButtonOpensModelManager() throws Exception {
        File root = temp.newFolder("missing-root");
        CellposeParameterStage stage = new CellposeParameterStage(
                new RecordingStore("cellpose:20.0:0.5:0.1:gpu=false:model=user_missing"),
                new RecordingSizeStore("0-Infinity"),
                new RecordingPreviewAdapter(),
                new RecordingRuntimeAdapter(),
                Arrays.asList("Primary", "Companion"),
                0,
                false);
        final int[] calls = {0};
        final Path[] openedRoot = {null};
        final ModelEntry.Engine[] openedEngine = {null};
        stage.setModelManagerLauncherForTest(new CellposeParameterStage.ModelManagerLauncher() {
            @Override public void show(Window owner, Path managerRoot, ModelEntry.Engine engine) {
                calls[0]++;
                openedRoot[0] = managerRoot;
                openedEngine[0] = engine;
            }
        });

        JComponent controls = stage.buildControls(context(root), new RecordingActions());
        JButton manage = findButton(controls, "Open Manage models...");

        assertNotNull(manage);
        manage.doClick();
        assertEquals(1, calls[0]);
        assertEquals(root.toPath(), openedRoot[0]);
        assertEquals(ModelEntry.Engine.CELLPOSE, openedEngine[0]);
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

    private static JButton findButton(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof JButton && text.equals(((JButton) child).getText())) {
                return (JButton) child;
            }
            if (child instanceof Container) {
                JButton found = findButton((Container) child, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static final class RecordingStore implements CellposeParameterStage.ParameterStore {
        private final String token;

        RecordingStore(String token) {
            this.token = token;
        }

        @Override public String getMethodToken() {
            return token;
        }

        @Override public void save(String methodToken) {
        }
    }

    private static final class RecordingSizeStore implements CellposeParameterStage.SizeStore {
        private final String token;

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
        @Override public void setStatus(String text) {
        }

        @Override public void markPreviewStale(String text) {
        }

        @Override public void setAdjustedPreview(ImagePlus image, String text) {
        }

        @Override public void registerPreviewButton(JButton button) {
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
}
