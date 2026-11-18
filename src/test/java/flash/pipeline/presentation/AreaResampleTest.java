package flash.pipeline.presentation;

import org.junit.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AreaResampleTest {

    @Test
    public void largeReductionAveragesSparsePunctaInsteadOfAliasing() {
        // One bright pixel per 10 x 10 block, shrunk tenfold into a figure
        // cell: a bilinear draw lands between them and shows black.
        BufferedImage source = new BufferedImage(800, 800, BufferedImage.TYPE_INT_RGB);
        for (int y = 3; y < 800; y += 10) {
            for (int x = 3; x < 800; x += 10) source.setRGB(x, y, 0xFFFFFF);
        }
        BufferedImage cell = new BufferedImage(80, 80, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = cell.createGraphics();
        try {
            AreaResample.drawFitted(g, source, 0, 0, 80, 80);
        } finally {
            g.dispose();
        }
        for (int y = 0; y < 80; y++) {
            for (int x = 0; x < 80; x++) {
                int grey = cell.getRGB(x, y) & 0xff;
                assertTrue("pixel " + x + "," + y + " was " + grey, grey >= 2 && grey <= 3);
            }
        }
    }

    @Test
    public void enlargingRepeatsPixels() {
        BufferedImage source = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
        source.setRGB(0, 0, 0xFFFFFF);
        BufferedImage out = AreaResample.toSize(source, 4, 2);
        assertEquals(0xFFFFFF, out.getRGB(1, 1) & 0xFFFFFF);
        assertEquals(0, out.getRGB(2, 0) & 0xFFFFFF);
    }
}
