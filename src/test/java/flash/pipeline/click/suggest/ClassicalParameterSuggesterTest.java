package flash.pipeline.click.suggest;

import flash.pipeline.click.ClickStore;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import ij.process.ShortProcessor;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ClassicalParameterSuggesterTest {

    @Test
    public void suggestsThresholdRaiseWhenAllBadShareLowIntensity() {
        Fixture f = fixture(new int[][]{
                {1, 2, 3, 4},
                {1, 2, 3, 4}
        }, new float[][]{
                {10, 15, 20, 100},
                {10, 15, 20, 100}
        });

        ClassicalParameterSuggester.ClassicalSuggestion suggestion = suggest(f,
                negatives(c(1, 0, 0), c(2, 1, 0), c(3, 2, 0)),
                positives(c(4, 3, 0)));

        assertNotNull(suggestion.thresholdLow);
        assertEquals(21.0, suggestion.thresholdLow.doubleValue(), 0.001);
        assertEquals(3, suggestion.badRemoved);
        assertEquals(0, suggestion.collateralRemoved);
    }

    @Test
    public void suggestsMinSizeWhenAllBadAreSmall() {
        Fixture f = fixture(new int[][]{
                {1, 2, 3, 4, 4, 4},
                {0, 0, 0, 4, 4, 4}
        }, fill(2, 6, 100f));

        ClassicalParameterSuggester.ClassicalSuggestion suggestion = suggest(f,
                negatives(c(1, 0, 0), c(2, 1, 0), c(3, 2, 0)),
                positives(c(4, 3, 0)));

        assertNotNull(suggestion.minSize);
        assertEquals(2, suggestion.minSize.intValue());
        assertEquals(3, suggestion.badRemoved);
    }

    @Test
    public void suggestsMaxSizeWhenAllBadAreLarge() {
        Fixture f = fixture(new int[][]{
                {1, 1, 1, 2, 2, 2, 3, 3, 3, 4},
                {1, 1, 1, 2, 2, 2, 3, 3, 3, 4}
        }, fill(2, 10, 100f));

        ClassicalParameterSuggester.ClassicalSuggestion suggestion = suggest(f,
                negatives(c(1, 0, 0), c(2, 3, 0), c(3, 6, 0)),
                positives(c(4, 9, 0)));

        assertNotNull(suggestion.maxSize);
        assertEquals(5, suggestion.maxSize.intValue());
        assertEquals(3, suggestion.badRemoved);
    }

    @Test
    public void refusesSuggestionThatWouldEliminatePositive() {
        Fixture f = fixture(new int[][]{
                {1, 2, 3, 4},
                {1, 2, 3, 4}
        }, new float[][]{
                {10, 15, 20, 12},
                {10, 15, 20, 12}
        });

        ClassicalParameterSuggester.ClassicalSuggestion suggestion = suggest(f,
                negatives(c(1, 0, 0), c(2, 1, 0), c(3, 2, 0)),
                positives(c(4, 3, 0)));

        assertFalse(suggestion.hasSuggestion());
    }

    @Test
    public void prefersThresholdOverSizeWhenBothWork() {
        Fixture f = fixture(new int[][]{
                {1, 2, 3, 4, 4, 4},
                {0, 0, 0, 4, 4, 4}
        }, new float[][]{
                {10, 12, 14, 100, 100, 100},
                {0, 0, 0, 100, 100, 100}
        });

        ClassicalParameterSuggester.ClassicalSuggestion suggestion = suggest(f,
                negatives(c(1, 0, 0), c(2, 1, 0), c(3, 2, 0)),
                positives(c(4, 3, 0)));

        assertNotNull(suggestion.thresholdLow);
        assertEquals(15.0, suggestion.thresholdLow.doubleValue(), 0.001);
    }

    @Test
    public void returnsAllNullsWhenInsufficientData() {
        Fixture f = fixture(new int[][]{{1, 2, 3}}, new float[][]{{10, 20, 30}});

        ClassicalParameterSuggester.ClassicalSuggestion suggestion = suggest(f,
                negatives(c(1, 0, 0), c(2, 1, 0)),
                Collections.<ClickStore.Click>emptyList());

        assertFalse(suggestion.hasSuggestion());
    }

    @Test
    public void countsCollateral() {
        Fixture f = fixture(new int[][]{
                {1, 2, 3, 4, 5},
                {1, 2, 3, 4, 5}
        }, new float[][]{
                {10, 15, 20, 18, 100},
                {10, 15, 20, 18, 100}
        });

        ClassicalParameterSuggester.ClassicalSuggestion suggestion = suggest(f,
                negatives(c(1, 0, 0), c(2, 1, 0), c(3, 2, 0)),
                positives(c(5, 4, 0)));

        assertTrue(suggestion.hasSuggestion());
        assertEquals(3, suggestion.badRemoved);
        assertEquals(1, suggestion.collateralRemoved);
    }

    private static ClassicalParameterSuggester.ClassicalSuggestion suggest(
            Fixture fixture, java.util.List<ClickStore.Click> negative,
            java.util.List<ClickStore.Click> positive) {
        return new ClassicalParameterSuggester().suggest(new SuggestionContext(
                fixture.raw, fixture.labels, null, negative, positive,
                Collections.<String, Double>emptyMap()));
    }

    private static java.util.List<ClickStore.Click> negatives(ClickStore.Click... clicks) {
        return Arrays.asList(clicks);
    }

    private static java.util.List<ClickStore.Click> positives(ClickStore.Click... clicks) {
        return Arrays.asList(clicks);
    }

    private static ClickStore.Click c(int label, int x, int y) {
        return new ClickStore.Click("img", 1, label, 1, x, y,
                ClickStore.Verdict.NEGATIVE, 1L);
    }

    private static Fixture fixture(int[][] labels, float[][] values) {
        return new Fixture(image("labels", labels), image("raw", values));
    }

    private static float[][] fill(int height, int width, float value) {
        float[][] out = new float[height][width];
        for (int y = 0; y < height; y++) {
            Arrays.fill(out[y], value);
        }
        return out;
    }

    private static ImagePlus image(String title, int[][] values) {
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
        return new ImagePlus(title, stack);
    }

    private static ImagePlus image(String title, float[][] values) {
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
        return new ImagePlus(title, stack);
    }

    private static final class Fixture {
        final ImagePlus labels;
        final ImagePlus raw;

        Fixture(ImagePlus labels, ImagePlus raw) {
            this.labels = labels;
            this.raw = raw;
        }
    }
}
