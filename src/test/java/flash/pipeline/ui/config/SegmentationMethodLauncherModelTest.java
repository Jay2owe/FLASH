package flash.pipeline.ui.config;

import org.junit.Test;

import javax.swing.AbstractButton;
import javax.swing.JComponent;
import java.awt.Component;
import java.awt.Container;

import static org.junit.Assert.*;

public class SegmentationMethodLauncherModelTest {
    @Test
    public void trainCustomEngineEntryIsAlwaysPresent() {
        SegmentationMethodLauncherModel model = new SegmentationMethodLauncherModel();

        boolean found = false;
        for (SegmentationMethodLauncherModel.Entry entry : model.entries()) {
            if (SegmentationMethodLauncherModel.TRAIN_CUSTOM_ENGINE.equals(entry.value)) {
                found = true;
                assertTrue(entry.launcher);
                assertEquals(SegmentationMethodLauncherModel.TRAIN_CUSTOM_ENGINE_DISPLAY,
                        entry.displayText);
            }
        }

        assertTrue(found);
        assertTrue(model.isLauncher(SegmentationMethodLauncherModel.TRAIN_CUSTOM_ENGINE));
        assertTrue(model.isLauncher(SegmentationMethodLauncherModel.TRAIN_CUSTOM_ENGINE_DISPLAY));
        assertFalse(model.isKnownMethod(SegmentationMethodLauncherModel.TRAIN_CUSTOM_ENGINE));
    }

    @Test
    public void selectingLauncherTriggersWorkflowWithoutPersistingLauncherToken() {
        RecordingMethodStore store = new RecordingMethodStore("classical");
        RecordingLauncher launcher = new RecordingLauncher(true, "trained_rf:rf_model:base=classical");
        SegmentationMethodStage stage = new SegmentationMethodStage(store, launcher);

        JComponent controls = stage.buildControls(null, new RecordingActions());
        AbstractButton train = findButton(controls, SegmentationMethodLauncherModel.TRAIN_CUSTOM_ENGINE_DISPLAY);

        assertNotNull(train);
        train.doClick();

        assertEquals(1, launcher.launches);
        assertEquals("trained_rf:rf_model:base=classical", store.token);
        assertFalse(SegmentationMethodLauncherModel.TRAIN_CUSTOM_ENGINE.equals(store.token));
    }

    @Test
    public void cancellingLauncherRestoresPreviousMethod() {
        RecordingMethodStore store = new RecordingMethodStore("stardist:0.5:0.4:model=old");
        RecordingLauncher launcher = new RecordingLauncher(false, "cellpose:30.0:0.4:0.0:model=temp");
        SegmentationMethodStage stage = new SegmentationMethodStage(store, launcher);

        JComponent controls = stage.buildControls(null, new RecordingActions());
        AbstractButton train = findButton(controls, SegmentationMethodLauncherModel.TRAIN_CUSTOM_ENGINE_DISPLAY);
        assertNotNull(train);

        train.doClick();

        assertEquals(1, launcher.launches);
        assertEquals("stardist:0.5:0.4:model=old", store.token);
    }

    private static AbstractButton findButton(Component root, String text) {
        if (root instanceof AbstractButton && text.equals(((AbstractButton) root).getText())) {
            return (AbstractButton) root;
        }
        if (root instanceof Container) {
            Component[] children = ((Container) root).getComponents();
            for (int i = 0; i < children.length; i++) {
                AbstractButton found = findButton(children[i], text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static final class RecordingMethodStore implements SegmentationMethodStage.MethodStore {
        String token;

        RecordingMethodStore(String token) {
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

    private static final class RecordingLauncher
            implements SegmentationMethodStage.TrainCustomEngineLauncher {
        final boolean applied;
        final String tokenToWrite;
        int launches;

        RecordingLauncher(boolean applied, String tokenToWrite) {
            this.applied = applied;
            this.tokenToWrite = tokenToWrite;
        }

        @Override public boolean launch(ConfigQcContext context,
                                        SegmentationMethodStage.MethodStore methodStore) {
            launches++;
            methodStore.setMethodToken(tokenToWrite);
            return applied;
        }
    }

    private static final class RecordingActions implements ConfigQcActions {
        @Override public void setStatus(String text) {}
        @Override public void markPreviewStale(String text) {}
        @Override public void setAdjustedPreview(ij.ImagePlus image, String text) {}
        @Override public void nextImage() {}
        @Override public void skipCurrentImage() {}
        @Override public void restartStage() {}
        @Override public void cancel() {}
    }
}
