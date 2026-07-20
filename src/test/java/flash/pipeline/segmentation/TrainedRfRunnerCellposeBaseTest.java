package flash.pipeline.segmentation;

import flash.pipeline.cellpose.Cellpose3DRunner;
import flash.pipeline.click.training.ObjectClassifierTrainer;
import flash.pipeline.click.training.ObjectFeatureExtractor;
import flash.pipeline.objects.ObjectsCounter3DWrapper;
import ij.ImagePlus;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TrainedRfRunnerCellposeBaseTest {

    @Before
    public void requireMcib3d() {
        Assume.assumeTrue(ObjectsCounter3DWrapper.isMcib3dAvailable());
    }

    @Test
    public void runsCellposeThenAppliesRfFilter() {
        ObjectClassifierTrainer.TrainingResult trained =
                TrainedRfRunnerTestSupport.trainKeepingHighValues(ObjectFeatureExtractor.FEATURE_VOLUME);
        final ImagePlus baseLabels = TrainedRfRunnerTestSupport.fiveLabelImage();
        ImagePlus raw = TrainedRfRunnerTestSupport.rawLike(baseLabels);
        final boolean[] baseCalled = new boolean[] {false};

        TrainedRfParameters params = new TrainedRfParameters(
                "rf_over_cellpose",
                null,
                null,
                trained.model,
                trained.featureNames,
                SegmentationTokenParser.parse("cellpose:30.0:0.4:0.0:gpu=false:model=cellpose_cyto3"),
                0.5,
                1,
                1,
                Integer.MAX_VALUE,
                raw,
                null,
                new TrainedRfRunner.BaseRunner() {
                    @Override public ImagePlus run(ImagePlus channelImage, TrainedRfParameters params) {
                        baseCalled[0] = true;
                        return baseLabels;
                    }
                },
                null);

        ImagePlus filtered = new TrainedRfRunner().run(raw, params);

        assertTrue(baseCalled[0]);
        assertEquals(new HashSet<Integer>(Arrays.asList(
                Integer.valueOf(1), Integer.valueOf(2), Integer.valueOf(3))),
                TrainedRfRunnerTestSupport.labelsIn(filtered));
    }

    @Test
    public void includesCellprobFeatureWhenAuxImagePresent() {
        ObjectClassifierTrainer.TrainingResult trained =
                TrainedRfRunnerTestSupport.trainKeepingHighValues(ObjectFeatureExtractor.FEATURE_MEAN_CELLPROB);
        final ImagePlus baseLabels = TrainedRfRunnerTestSupport.fiveLabelImage();
        ImagePlus cellprob = TrainedRfRunnerTestSupport.valueImageByLabel(baseLabels);
        baseLabels.setProperty(Cellpose3DRunner.CELLPROB_IMAGE_PROPERTY, cellprob);
        ImagePlus raw = TrainedRfRunnerTestSupport.rawLike(baseLabels);

        TrainedRfParameters params = new TrainedRfParameters(
                "rf_over_cellpose",
                null,
                null,
                trained.model,
                trained.featureNames,
                SegmentationTokenParser.parse("cellpose:30.0:0.4:0.0:gpu=false:model=cellpose_cyto3"),
                0.5,
                1,
                1,
                Integer.MAX_VALUE,
                raw,
                null,
                new TrainedRfRunner.BaseRunner() {
                    @Override public ImagePlus run(ImagePlus channelImage, TrainedRfParameters params) {
                        return baseLabels;
                    }
                },
                null);

        ImagePlus filtered = new TrainedRfRunner().run(raw, params);

        assertEquals(new HashSet<Integer>(Arrays.asList(
                Integer.valueOf(1), Integer.valueOf(2), Integer.valueOf(3))),
                TrainedRfRunnerTestSupport.labelsIn(filtered));
    }
}
