package flash.pipeline.segmentation;

import flash.pipeline.click.training.ObjectClassifierTrainer;
import flash.pipeline.click.training.ObjectFeatureExtractor;
import flash.pipeline.objects.ObjectsCounter3DWrapper;
import flash.pipeline.stardist.StarDist3DRunner;
import ij.ImagePlus;
import ij.measure.ResultsTable;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TrainedRfRunnerStarDistBaseTest {

    @Before
    public void requireMcib3d() {
        Assume.assumeTrue(ObjectsCounter3DWrapper.isMcib3dAvailable());
    }

    @Test
    public void runsStarDistThenAppliesRfFilter() {
        ObjectClassifierTrainer.TrainingResult trained =
                TrainedRfRunnerTestSupport.trainKeepingHighValues(ObjectFeatureExtractor.FEATURE_VOLUME);
        final ImagePlus baseLabels = TrainedRfRunnerTestSupport.fiveLabelImage();
        ImagePlus raw = TrainedRfRunnerTestSupport.rawLike(baseLabels);
        final boolean[] baseCalled = new boolean[] {false};

        TrainedRfParameters params = new TrainedRfParameters(
                "rf_over_stardist",
                null,
                null,
                trained.model,
                trained.featureNames,
                SegmentationTokenParser.parse("stardist:0.5:0.3:model=user_stardist_v1"),
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
    public void includesStarDistQualityFeatureWhenPresent() {
        ObjectClassifierTrainer.TrainingResult trained =
                TrainedRfRunnerTestSupport.trainKeepingHighValues(ObjectFeatureExtractor.FEATURE_QUALITY);
        final ImagePlus baseLabels = TrainedRfRunnerTestSupport.fiveLabelImage();
        baseLabels.setProperty(StarDist3DRunner.OBJECT_STATS_PROPERTY, qualityTable());
        ImagePlus raw = TrainedRfRunnerTestSupport.rawLike(baseLabels);

        TrainedRfParameters params = new TrainedRfParameters(
                "rf_over_stardist",
                null,
                null,
                trained.model,
                trained.featureNames,
                SegmentationTokenParser.parse("stardist:0.5:0.3:model=user_stardist_v1"),
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

    private static ResultsTable qualityTable() {
        ResultsTable table = new ResultsTable();
        for (int label = 1; label <= 5; label++) {
            int row = label - 1;
            table.incrementCounter();
            table.setValue("Label", row, label);
            table.setValue(StarDist3DRunner.STATS_QUALITY_MEAN, row, label);
        }
        return table;
    }
}
