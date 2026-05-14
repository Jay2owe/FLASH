package flash.pipeline.ui.variations;

import ij.ImagePlus;
import ij.process.ByteProcessor;

import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertTrue;

public class MacroVariationsDialogTwoRibbonsTest {

    @Test
    public void stableShapeAndDownstreamRibbonsPaintOnOppositeCorners()
            throws Exception {
        final AtomicReference<BufferedImage> ref =
                new AtomicReference<BufferedImage>();
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                VariationCellPanel cell = new VariationCellPanel(
                        ParameterCombo.builder().build(),
                        new ImagePlus("source", new ByteProcessor(8, 8)),
                        null,
                        null);
                cell.setSize(220, 180);
                cell.doLayout();
                cell.setRibbonLabel("STABLE SHAPE");
                cell.setBorderHint(VariationCellPanel.BorderHint.KNEE);
                cell.setRibbonLabel("HELPS DOWNSTREAM");
                cell.setDownstreamDelta(12);
                BufferedImage image = new BufferedImage(220, 180,
                        BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = image.createGraphics();
                try {
                    cell.paint(g);
                } finally {
                    g.dispose();
                }
                ref.set(image);
            }
        });

        BufferedImage image = ref.get();
        assertTrue(isNear(new Color(image.getRGB(12, 12), true),
                new Color(0xF0, 0xE4, 0x42)));
        assertTrue(isNear(new Color(image.getRGB(207, 12), true),
                new Color(0x56, 0xB4, 0xE9)));
    }

    private static boolean isNear(Color actual, Color expected) {
        return Math.abs(actual.getRed() - expected.getRed()) < 8
                && Math.abs(actual.getGreen() - expected.getGreen()) < 8
                && Math.abs(actual.getBlue() - expected.getBlue()) < 8;
    }
}
