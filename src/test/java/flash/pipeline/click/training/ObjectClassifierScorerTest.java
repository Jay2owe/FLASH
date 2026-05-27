package flash.pipeline.click.training;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class ObjectClassifierScorerTest {
    private static final String[] NAMES = new String[] {"volume"};

    @Test
    public void filterLabelImageRelabelsConsecutively() {
        ImagePlus labels = labelsWithValues(2, 5, 5, 0, 2);
        List<ObjectClassifierScorer.ScoreResult> results = Arrays.asList(
                new ObjectClassifierScorer.ScoreResult(2, 0.95, true),
                new ObjectClassifierScorer.ScoreResult(5, 0.91, true));

        ImagePlus filtered = new ObjectClassifierScorer().filterLabelImage(labels, results);

        assertEquals(new HashSet<Integer>(Arrays.asList(Integer.valueOf(1), Integer.valueOf(2))),
                labelsIn(filtered));
        assertEquals(1, Math.round(filtered.getProcessor().getf(0, 0)));
        assertEquals(2, Math.round(filtered.getProcessor().getf(1, 0)));
    }

    @Test
    public void keepThresholdIsApplied() {
        ObjectClassifierTrainer.TrainingResult trained =
                new ObjectClassifierTrainer().train(rows(30, true), rows(30, false), 17);

        List<ObjectFeatureExtractor.FeatureRow> rows = Arrays.asList(
                new ObjectFeatureExtractor.FeatureRow(1, new double[] {100}, NAMES),
                new ObjectFeatureExtractor.FeatureRow(2, new double[] {1}, NAMES));
        List<ObjectClassifierScorer.ScoreResult> scores =
                new ObjectClassifierScorer().score(trained.model, trained.featureNames, rows, 0.8);

        assertTrue(scores.get(0).kept);
        assertFalse(scores.get(1).kept);
    }

    @Test
    public void scoreOnEmptyInputReturnsEmpty() {
        ObjectClassifierTrainer.TrainingResult trained =
                new ObjectClassifierTrainer().train(rows(20, true), rows(20, false), 19);

        assertTrue(new ObjectClassifierScorer()
                .score(trained.model, trained.featureNames,
                        Collections.<ObjectFeatureExtractor.FeatureRow>emptyList(), 0.5)
                .isEmpty());
    }

    @Test
    public void nonFiniteScoreFeatureFailsWithLabelAndFeatureName() {
        ObjectClassifierTrainer.TrainingResult trained =
                new ObjectClassifierTrainer().train(rows(30, true), rows(30, false), 17);
        List<ObjectFeatureExtractor.FeatureRow> rows = Arrays.asList(
                new ObjectFeatureExtractor.FeatureRow(42, new double[] {Double.NaN}, NAMES));

        try {
            new ObjectClassifierScorer().score(trained.model, trained.featureNames, rows, 0.5);
            fail("Expected non-finite score feature to fail fast.");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("volume"));
            assertTrue(expected.getMessage().contains("label 42"));
        }
    }

    private static List<ObjectFeatureExtractor.FeatureRow> rows(int count, boolean positive) {
        List<ObjectFeatureExtractor.FeatureRow> rows = new ArrayList<ObjectFeatureExtractor.FeatureRow>();
        for (int i = 0; i < count; i++) {
            rows.add(new ObjectFeatureExtractor.FeatureRow(i + 1,
                    new double[] {positive ? 80 + i : 1 + i}, NAMES));
        }
        return rows;
    }

    private static ImagePlus labelsWithValues(int... values) {
        ByteProcessor bp = new ByteProcessor(values.length, 1);
        for (int i = 0; i < values.length; i++) {
            bp.set(i, 0, values[i]);
        }
        ImageStack stack = new ImageStack(values.length, 1);
        stack.addSlice(bp);
        return new ImagePlus("labels", stack);
    }

    private static Set<Integer> labelsIn(ImagePlus image) {
        Set<Integer> out = new HashSet<Integer>();
        ImageProcessor ip = image.getProcessor();
        for (int i = 0; i < ip.getPixelCount(); i++) {
            int label = Math.round(ip.getf(i));
            if (label > 0) out.add(Integer.valueOf(label));
        }
        return out;
    }
}
