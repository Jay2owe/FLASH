package flash.pipeline.representative;

import flash.pipeline.presentation.PresentationTileConfig;
import org.junit.Test;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class FigureLayoutEditorTest {

    @Test
    public void distributeReshapesByRowCount() {
        List<String> conditions = Arrays.asList("A", "B", "C", "D");

        String[][] one = FigureLayoutEditor.distribute(conditions, 1);
        assertEquals(1, one.length);
        assertEquals(4, one[0].length);
        assertEquals("A", one[0][0]);
        assertEquals("D", one[0][3]);

        String[][] two = FigureLayoutEditor.distribute(conditions, 2);
        assertEquals(2, two.length);
        assertEquals(2, two[0].length);
        assertEquals("C", two[1][0]);

        String[][] four = FigureLayoutEditor.distribute(conditions, 4);
        assertEquals(4, four.length);
        assertEquals(1, four[0].length);
        assertEquals("D", four[3][0]);
    }

    @Test
    public void distributeHandlesRaggedCountsAndFlattens() {
        String[][] grid = FigureLayoutEditor.distribute(Arrays.asList("A", "B", "C"), 2);
        assertEquals(2, grid.length);
        assertEquals(2, grid[0].length);
        assertEquals("C", grid[1][0]);
        assertNull(grid[1][1]);
        assertEquals(Arrays.asList("A", "B", "C"), FigureLayoutEditor.flatten(grid));
    }

    @Test
    public void fromGridDropsEmptyRowsAndKeepsOrder() {
        String[][] grid = {{"A", "B"}, {null, null}, {"C", null}};
        assertEquals(Arrays.asList(Arrays.asList("A", "B"), Arrays.asList("C")),
                FigureLayoutEditor.fromGrid(grid));
    }

    @Test
    public void swapCellsExchangesContents() {
        String[][] grid = {{"A", "B"}, {"C", null}};
        FigureLayoutEditor.swapCells(grid, 0, 0, 1, 1);
        assertNull(grid[0][0]);
        assertEquals("A", grid[1][1]);
        FigureLayoutEditor.swapCells(grid, 0, 1, 1, 0);
        assertEquals("C", grid[0][1]);
        assertEquals("B", grid[1][0]);
    }

    @Test
    public void cellAtFindsContainingCellAndNotGutters() {
        Rectangle[][] rects = FigureLayoutEditor.cellRects(2, 2, 50, 10, 10, 5, 0, 0);
        assertArrayEquals(new int[]{0, 0}, FigureLayoutEditor.cellAt(new Point(10, 10), rects));
        assertArrayEquals(new int[]{1, 1}, FigureLayoutEditor.cellAt(new Point(80, 80), rects));
        assertNull("a point in the gutter hits no cell",
                FigureLayoutEditor.cellAt(new Point(58, 58), rects));
    }

    @Test
    public void arrangeReturnsNullWithoutUiForInvalidArguments() {
        PresentationTileConfig tile = PresentationTileConfig.builder()
                .channelOrder(Arrays.asList("DAPI", "Merge")).build();
        RepresentativeLayout layout = RepresentativeLayout.allInOneRow(Arrays.asList("A"));
        // selection == null short-circuits before any dialog is constructed.
        assertNull(FigureLayoutEditor.arrange(null, null, layout, tile));
        assertNull(FigureLayoutEditor.arrange(null, null, null, null));
    }
}
