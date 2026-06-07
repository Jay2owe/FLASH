package flash.pipeline.representative;

import flash.pipeline.presentation.PresentationTileConfig;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class AnnotationPlacementTest {

    @Test
    public void cornerFractionsAreDistinctPerCorner() {
        assertEquals(0.04, AnnotationPlacement.cornerFraction(
                PresentationTileConfig.Position.TOP_LEFT)[0], 1e-9);
        assertEquals(0.04, AnnotationPlacement.cornerFraction(
                PresentationTileConfig.Position.TOP_LEFT)[1], 1e-9);
        assertEquals(0.96, AnnotationPlacement.cornerFraction(
                PresentationTileConfig.Position.BOTTOM_RIGHT)[0], 1e-9);
        assertEquals(0.96, AnnotationPlacement.cornerFraction(
                PresentationTileConfig.Position.BOTTOM_RIGHT)[1], 1e-9);
        assertEquals(0.96, AnnotationPlacement.cornerFraction(
                PresentationTileConfig.Position.TOP_RIGHT)[0], 1e-9);
        assertEquals(0.04, AnnotationPlacement.cornerFraction(
                PresentationTileConfig.Position.TOP_RIGHT)[1], 1e-9);
    }

    @Test
    public void snapsWhenNearACornerAndNotInTheMiddle() {
        assertEquals(PresentationTileConfig.Position.TOP_LEFT,
                AnnotationPlacement.snapToNearestCorner(0.05, 0.05, 0.06));
        assertEquals(PresentationTileConfig.Position.BOTTOM_RIGHT,
                AnnotationPlacement.snapToNearestCorner(0.95, 0.94, 0.06));
        assertNull("centre of the tile should not snap",
                AnnotationPlacement.snapToNearestCorner(0.5, 0.5, 0.06));
    }

    @Test
    public void clampFractionStaysInUnitRange() {
        assertEquals(0.0, AnnotationPlacement.clampFraction(-0.3), 1e-9);
        assertEquals(1.0, AnnotationPlacement.clampFraction(1.7), 1e-9);
        assertEquals(0.42, AnnotationPlacement.clampFraction(0.42), 1e-9);
    }
}
