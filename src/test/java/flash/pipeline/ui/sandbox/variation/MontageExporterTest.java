package flash.pipeline.ui.sandbox.variation;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MontageExporterTest {

    @Test
    public void buildMontageHandlesRawPlusTwoVisibleVariants() {
        ImagePlus montage = MontageExporter.buildMontage(Arrays.asList(
                tile("RAW", image("raw", 8, 8, 32)),
                tile("sigma=2", image("sigma", 8, 8, 96)),
                tile("median=3", image("median", 4, 4, 160))));

        assertNotNull(montage);
        assertTrue(montage.getWidth() >= 16);
        assertTrue(montage.getHeight() >= 16);
    }

    @Test
    public void buildMontageSkipsErrorTilesAndNullTiles() {
        ImagePlus montage = MontageExporter.buildMontage(Arrays.asList(
                null,
                TilePanel.forError("bad", new RuntimeException("boom")),
                tile("ok", image("ok", 8, 8, 96))));

        assertNotNull(montage);
        assertTrue(montage.getWidth() >= 8);
        assertTrue(montage.getHeight() >= 8);
    }

    @Test
    public void buildMontageReturnsNullWhenNoImageTilesExist() {
        assertNull(MontageExporter.buildMontage(Collections.singletonList(
                TilePanel.forError("bad", new RuntimeException("boom")))));
    }

    @Test
    public void gridDimsFavourNearSquareLayout() {
        assertArrayEquals(new int[] { 2, 2 }, MontageExporter.gridDims(3));
        assertArrayEquals(new int[] { 3, 3 }, MontageExporter.gridDims(9));
    }

    private static TilePanel tile(String label, ImagePlus imp) {
        return new TilePanel(imp, label, "RAW".equals(label));
    }

    private static ImagePlus image(String title, int width, int height, int value) {
        ImageStack stack = new ImageStack(width, height);
        for (int i = 0; i < 2; i++) {
            ByteProcessor processor = new ByteProcessor(width, height);
            processor.setValue(value + i);
            processor.fill();
            stack.addSlice("s" + i, processor);
        }
        return new ImagePlus(title, stack);
    }
}
