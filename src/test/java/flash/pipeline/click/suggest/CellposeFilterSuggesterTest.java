package flash.pipeline.click.suggest;

import flash.pipeline.cellpose.Cellpose3DRunner;
import flash.pipeline.click.ClickStore;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import ij.process.ShortProcessor;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

public class CellposeFilterSuggesterTest {

    @Test
    public void suggestsCellprobWhenAllBadHaveLowCellprob() {
        ImagePlus labels = labels(new int[][]{{1, 2, 3, 4}});
        ImagePlus cellprob = values(new float[][]{{0.1f, 0.2f, 0.3f, 0.9f}});

        CellposeFilterSuggester.CellposeSuggestion suggestion = suggest(labels, cellprob,
                negatives(c(1, 0), c(2, 1), c(3, 2)), positives(c(4, 3)));

        assertNotNull(suggestion.cellprobThreshold);
    }

    @Test
    public void suggestsCellprobWhenImageCarriesRunnerCellprobProperty() {
        ImagePlus labels = labels(new int[][]{{1, 2, 3, 4}});
        ImagePlus cellprob = values(new float[][]{{0.1f, 0.2f, 0.3f, 0.9f}});
        labels.setProperty(Cellpose3DRunner.CELLPROB_IMAGE_PROPERTY, cellprob);

        CellposeFilterSuggester.CellposeSuggestion suggestion = suggest(labels,
                (ImagePlus) labels.getProperty(Cellpose3DRunner.CELLPROB_IMAGE_PROPERTY),
                negatives(c(1, 0), c(2, 1), c(3, 2)), positives(c(4, 3)));

        assertNotNull(suggestion.cellprobThreshold);
    }

    @Test
    public void sparseWideLabelsProduceExactCellprobSuggestionWithoutDenseRange() {
        ImagePlus labels = wideLabels(new int[] {65_536, 70_000, 16_777_216, 1});
        ImagePlus cellprob = values(new float[][] {{0.1f, 0.2f, 0.3f, 0.9f}});

        CellposeFilterSuggester.CellposeSuggestion suggestion = suggest(labels, cellprob,
                negatives(c(65_536, 0), c(70_000, 1), c(16_777_216, 2)),
                positives(c(1, 3)));

        assertNotNull(suggestion.cellprobThreshold);
        assertEquals(0.31d, suggestion.cellprobThreshold.doubleValue(), 1.0e-6);
    }

    @Test
    public void suggestsDiameterWhenBadAreSmallAndCellprobUnavailable() {
        ImagePlus labels = labels(new int[][]{
                {1, 2, 3, 4, 4, 4, 4, 4},
                {0, 0, 0, 4, 4, 4, 4, 4}
        });

        CellposeFilterSuggester.CellposeSuggestion suggestion = suggest(labels, null,
                negatives(c(1, 0), c(2, 1), c(3, 2)), positives(c(4, 3)));

        assertNotNull(suggestion.diameter);
    }

    @Test
    public void prefersCellprobOverDiameter() {
        ImagePlus labels = labels(new int[][]{
                {1, 2, 3, 4, 4, 4, 4, 4},
                {0, 0, 0, 4, 4, 4, 4, 4}
        });
        ImagePlus cellprob = values(new float[][]{
                {0.1f, 0.2f, 0.3f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f},
                {0, 0, 0, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f}
        });

        CellposeFilterSuggester.CellposeSuggestion suggestion = suggest(labels, cellprob,
                negatives(c(1, 0), c(2, 1), c(3, 2)), positives(c(4, 3)));

        assertNotNull(suggestion.cellprobThreshold);
        assertFalse(suggestion.diameter != null);
    }

    @Test
    public void returnsAllNullsWhenInsufficientData() {
        ImagePlus labels = labels(new int[][]{{1, 2, 3}});

        CellposeFilterSuggester.CellposeSuggestion suggestion = suggest(labels, null,
                negatives(c(1, 0), c(2, 1)), Collections.<ClickStore.Click>emptyList());

        assertFalse(suggestion.hasSuggestion());
    }

    private static CellposeFilterSuggester.CellposeSuggestion suggest(
            ImagePlus labels, ImagePlus cellprob,
            List<ClickStore.Click> negative, List<ClickStore.Click> positive) {
        return new CellposeFilterSuggester().suggest(new SuggestionContext(
                labels, labels, cellprob, negative, positive,
                Collections.<String, Double>emptyMap()));
    }

    private static List<ClickStore.Click> negatives(ClickStore.Click... clicks) {
        return Arrays.asList(clicks);
    }

    private static List<ClickStore.Click> positives(ClickStore.Click... clicks) {
        return Arrays.asList(clicks);
    }

    private static ClickStore.Click c(int label, int x) {
        return new ClickStore.Click("img", 1, label, 1, x, 0,
                ClickStore.Verdict.NEGATIVE, 1L);
    }

    private static ImagePlus labels(int[][] values) {
        int height = values.length;
        int width = values[0].length;
        ShortProcessor processor = new ShortProcessor(width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                processor.set(x, y, values[y][x]);
            }
        }
        ImageStack stack = new ImageStack(width, height);
        stack.addSlice(processor);
        return new ImagePlus("labels", stack);
    }

    private static ImagePlus wideLabels(int[] values) {
        FloatProcessor processor = new FloatProcessor(values.length, 1);
        for (int x = 0; x < values.length; x++) {
            processor.setf(x, 0, values[x]);
        }
        ImageStack stack = new ImageStack(values.length, 1);
        stack.addSlice(processor);
        return new ImagePlus("wide-labels", stack);
    }

    private static ImagePlus values(float[][] values) {
        int height = values.length;
        int width = values[0].length;
        FloatProcessor processor = new FloatProcessor(width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                processor.setf(x, y, values[y][x]);
            }
        }
        ImageStack stack = new ImageStack(width, height);
        stack.addSlice(processor);
        return new ImagePlus("values", stack);
    }
}
