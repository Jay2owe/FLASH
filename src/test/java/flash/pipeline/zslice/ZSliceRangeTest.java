package flash.pipeline.zslice;

import org.junit.Test;

import static org.junit.Assert.*;

public class ZSliceRangeTest {

    @Test
    public void parseValidRange() {
        ZSliceRange range = ZSliceRange.parse("11-30");
        assertNotNull(range);
        assertEquals(11, range.startSlice);
        assertEquals(30, range.endSlice);
        assertEquals(20, range.count());
    }

    @Test
    public void parseAllowsWhitespace() {
        ZSliceRange range = ZSliceRange.parse("  3 - 8 ");
        assertNotNull(range);
        assertEquals("3-8", range.toToken());
    }

    @Test
    public void parseRejectsInvalidInput() {
        assertNull(ZSliceRange.parse("abc"));
        assertNull(ZSliceRange.parse("3"));
        assertNull(ZSliceRange.parse("3-"));
        assertNull(ZSliceRange.parse("8-3"));
        assertNull(ZSliceRange.parse("0-1"));
        assertNull(ZSliceRange.parse(""));
    }

    @Test
    public void validityChecksBounds() {
        ZSliceRange range = new ZSliceRange(4, 10);
        assertTrue(range.isValidFor(10));
        assertFalse(range.isValidFor(9));
        assertFalse(range.coversFullStack(10));
    }

    @Test
    public void fullStackHelperCreatesExpectedRange() {
        ZSliceRange range = ZSliceRange.fullStack(12);
        assertEquals("1-12", range.toToken());
        assertTrue(range.coversFullStack(12));
    }
}
