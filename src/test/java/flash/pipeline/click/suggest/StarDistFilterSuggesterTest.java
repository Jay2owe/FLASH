package flash.pipeline.click.suggest;

import flash.pipeline.click.ClickStore;
import flash.pipeline.stardist.StarDist3DRunner;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.ResultsTable;
import ij.process.ShortProcessor;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

public class StarDistFilterSuggesterTest {

    @Test
    public void suggestsMinQualityWhenAllBadShareLowQuality() {
        ImagePlus labels = labels();
        labels.setProperty(StarDist3DRunner.OBJECT_STATS_PROPERTY,
                stats(new double[]{0.1, 0.2, 0.3, 0.9},
                        new double[]{50, 50, 50, 50},
                        new double[]{10, 10, 10, 10}));

        StarDistFilterSuggester.StarDistSuggestion suggestion = suggest(labels,
                negatives(c(1, 0), c(2, 1), c(3, 2)), positives(c(4, 3)));

        assertNotNull(suggestion.minQuality);
    }

    @Test
    public void suggestsMinAreaWhenAllBadAreSmall() {
        ImagePlus labels = labels();
        labels.setProperty(StarDist3DRunner.OBJECT_STATS_PROPERTY,
                stats(new double[]{0.5, 0.5, 0.5, 0.5},
                        new double[]{5, 6, 7, 100},
                        new double[]{10, 10, 10, 10}));

        StarDistFilterSuggester.StarDistSuggestion suggestion = suggest(labels,
                negatives(c(1, 0), c(2, 1), c(3, 2)), positives(c(4, 3)));

        assertNotNull(suggestion.minArea);
    }

    @Test
    public void prefersSingleKnobOverCombined() {
        ImagePlus labels = labels();
        labels.setProperty(StarDist3DRunner.OBJECT_STATS_PROPERTY,
                stats(new double[]{0.1, 0.2, 0.3, 0.9},
                        new double[]{5, 6, 7, 100},
                        new double[]{10, 10, 10, 10}));

        StarDistFilterSuggester.StarDistSuggestion suggestion = suggest(labels,
                negatives(c(1, 0), c(2, 1), c(3, 2)), positives(c(4, 3)));

        assertNotNull(suggestion.minQuality);
        assertFalse(suggestion.minArea != null);
    }

    @Test
    public void returnsAllNullsWhenNoStatsTable() {
        StarDistFilterSuggester.StarDistSuggestion suggestion = suggest(labels(),
                negatives(c(1, 0), c(2, 1), c(3, 2)), positives(c(4, 3)));

        assertFalse(suggestion.hasSuggestion());
    }

    private static StarDistFilterSuggester.StarDistSuggestion suggest(
            ImagePlus labels, List<ClickStore.Click> negative, List<ClickStore.Click> positive) {
        return new StarDistFilterSuggester().suggest(new SuggestionContext(
                null, labels, null, negative, positive,
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

    private static ImagePlus labels() {
        ShortProcessor processor = new ShortProcessor(4, 1);
        for (int x = 0; x < 4; x++) {
            processor.set(x, 0, x + 1);
        }
        ImageStack stack = new ImageStack(4, 1);
        stack.addSlice(processor);
        return new ImagePlus("labels", stack);
    }

    private static ResultsTable stats(double[] quality, double[] area, double[] intensity) {
        ResultsTable table = new ResultsTable();
        for (int i = 0; i < quality.length; i++) {
            table.incrementCounter();
            table.setValue("Label", i, i + 1);
            table.setValue(StarDist3DRunner.STATS_QUALITY_MEAN, i, quality[i]);
            table.setValue(StarDist3DRunner.STATS_AREA_MEAN, i, area[i]);
            table.setValue(StarDist3DRunner.STATS_INTENSITY_MEAN, i, intensity[i]);
        }
        return table;
    }
}
