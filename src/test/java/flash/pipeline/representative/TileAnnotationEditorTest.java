package flash.pipeline.representative;

import flash.pipeline.presentation.PresentationTileConfig;
import org.junit.Test;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class TileAnnotationEditorTest {

    private static final Rectangle LABEL_BOX = new Rectangle(10, 10, 60, 20);
    private static final Rectangle LABEL_HANDLE = new Rectangle(64, 24, 12, 12);
    private static final Rectangle BAR_BOX = new Rectangle(10, 80, 80, 8);
    private static final Rectangle BAR_HANDLE = new Rectangle(84, 82, 12, 12);

    @Test
    public void handlesTakePriorityOverBodies() {
        assertEquals(TileAnnotationEditor.Grab.LABEL_RESIZE,
                TileAnnotationEditor.pickTarget(new Point(68, 28),
                        LABEL_BOX, LABEL_HANDLE, BAR_BOX, BAR_HANDLE));
        assertEquals(TileAnnotationEditor.Grab.BAR_RESIZE,
                TileAnnotationEditor.pickTarget(new Point(88, 86),
                        LABEL_BOX, LABEL_HANDLE, BAR_BOX, BAR_HANDLE));
    }

    @Test
    public void bodiesPickedWhenNotOnAHandle() {
        assertEquals(TileAnnotationEditor.Grab.LABEL_BODY,
                TileAnnotationEditor.pickTarget(new Point(20, 18),
                        LABEL_BOX, LABEL_HANDLE, BAR_BOX, BAR_HANDLE));
        assertEquals(TileAnnotationEditor.Grab.BAR_BODY,
                TileAnnotationEditor.pickTarget(new Point(20, 84),
                        LABEL_BOX, LABEL_HANDLE, BAR_BOX, BAR_HANDLE));
    }

    @Test
    public void emptySpaceAndNullRectsGrabNothing() {
        assertEquals(TileAnnotationEditor.Grab.NONE,
                TileAnnotationEditor.pickTarget(new Point(300, 300),
                        LABEL_BOX, LABEL_HANDLE, BAR_BOX, BAR_HANDLE));
        assertEquals(TileAnnotationEditor.Grab.NONE,
                TileAnnotationEditor.pickTarget(new Point(20, 18),
                        null, null, null, null));
    }

    @Test
    public void editReturnsInputWithoutUiForInvalidArguments() {
        PresentationTileConfig config = PresentationTileConfig.builder()
                .channelOrder(Arrays.asList("DAPI", "Merge"))
                .build();
        // selection == null and tileConfig == null both short-circuit before any
        // dialog is constructed, so these never block on a window.
        assertSame(config, TileAnnotationEditor.edit(null, null, config));
        assertNull(TileAnnotationEditor.edit(null, null, null));
    }
}
