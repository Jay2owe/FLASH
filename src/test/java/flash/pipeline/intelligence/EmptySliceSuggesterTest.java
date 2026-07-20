package flash.pipeline.intelligence;

import ij.ImagePlus;
import ij.ImageStack;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class EmptySliceSuggesterTest {

    @Test
    public void suggestTrimsDarkTopAndBottomStackSlices() {
        EmptySliceSuggester.Suggestion suggestion = EmptySliceSuggester.suggest(
                stackWithSliceValues(5, 5, 100, 120, 130, 125, 110, 100, 5, 5));

        assertNotNull(suggestion);
        assertEquals("3-8", suggestion.range.toToken());
        assertEquals(Arrays.asList(
                Integer.valueOf(1),
                Integer.valueOf(2),
                Integer.valueOf(9),
                Integer.valueOf(10)),
                suggestion.pureNoiseSlices);
        assertTrue(suggestion.trimsSlices());
        assertEquals("Slices 1-2 and 9-10 look like pure noise - override if needed.",
                suggestion.tooltip());
    }

    @Test
    public void suggestReturnsFullRangeForAllDarkStack() {
        EmptySliceSuggester.Suggestion suggestion = EmptySliceSuggester.suggest(
                stackWithSliceValues(5, 5, 5, 5, 5, 5));

        assertNotNull(suggestion);
        assertEquals("1-6", suggestion.range.toToken());
        assertEquals(Collections.emptyList(), suggestion.pureNoiseSlices);
        assertFalse(suggestion.trimsSlices());
    }

    @Test
    public void suggestReturnsFullRangeForAllBrightStack() {
        EmptySliceSuggester.Suggestion suggestion = EmptySliceSuggester.suggest(
                stackWithSliceValues(500, 500, 500, 500, 500, 500));

        assertNotNull(suggestion);
        assertEquals("1-6", suggestion.range.toToken());
        assertEquals(Collections.emptyList(), suggestion.pureNoiseSlices);
        assertFalse(suggestion.trimsSlices());
    }

    @Test
    public void suggestKeepsSingleBrightSlice() {
        EmptySliceSuggester.Suggestion suggestion = EmptySliceSuggester.suggest(
                stackWithSliceValues(5, 5, 5, 100, 5, 5));

        assertNotNull(suggestion);
        assertEquals("4-4", suggestion.range.toToken());
        assertEquals(Arrays.asList(
                Integer.valueOf(1),
                Integer.valueOf(2),
                Integer.valueOf(3),
                Integer.valueOf(5),
                Integer.valueOf(6)),
                suggestion.pureNoiseSlices);
        assertTrue(suggestion.trimsSlices());
    }

    @Test
    public void suggestSkipsStacksShorterThanSixSlices() {
        assertNull(EmptySliceSuggester.suggest(
                stackWithSliceValues(5, 100, 100, 100, 5)));
    }

    private static ImagePlus stackWithSliceValues(int... values) {
        ImageStack stack = new ImageStack(4, 4);
        for (int value : values) {
            short[] pixels = new short[16];
            Arrays.fill(pixels, (short) value);
            stack.addSlice("", pixels);
        }
        ImagePlus image = new ImagePlus("test", stack);
        image.setDimensions(1, values.length, 1);
        return image;
    }
}
