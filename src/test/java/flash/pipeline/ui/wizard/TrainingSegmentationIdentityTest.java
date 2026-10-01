package flash.pipeline.ui.wizard;

import flash.pipeline.click.ClickStore;
import flash.pipeline.click.SegmentationFingerprint;
import ij.ImagePlus;
import ij.process.ShortProcessor;
import java.io.IOException;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class TrainingSegmentationIdentityTest {
    @Test public void randomForestCannotLearnVerdictsFromReplacementLabelNumbers() throws Exception {
        ImagePlus labels = new ImagePlus("labels", new ShortProcessor(2, 2));
        labels.getProcessor().set(0, 1);
        ClickStore.Click click = new ClickStore.Click("image", 1, 1, 1, 0, 0,
                ClickStore.Verdict.NEGATIVE, 1, SegmentationFingerprint.of(labels));
        labels.getProcessor().set(0, 0);
        labels.getProcessor().set(1, 1);
        TrainCustomEngineWorkflow.ClickSelection selection =
                new TrainCustomEngineWorkflow.ClickSelection(1, Collections.singletonList(click), null);
        TrainCustomEngineWorkflow.RfTrainingService trainer =
                TrainCustomEngineWorkflow.ImageTrainingServices.rf(
                        name -> new ImagePlus("raw", new ShortProcessor(2, 2)), name -> labels, 1);
        try {
            trainer.train(TrainCustomEngineWorkflow.Base.CLASSICAL, selection,
                    TrainCustomEngineWorkflow.NO_PROGRESS);
            fail("Stale object verdict was accepted for feature extraction");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Segmentation has changed"));
        }
    }
}
