package flash.pipeline.ui.variations;

import flash.pipeline.image.FilterMacroEditorModel;

import org.junit.Test;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ChainRibbonPaintTest {

    @Test
    public void mixedStatesPaintExpectedPillColours() {
        ChainRibbon ribbon = new ChainRibbon(FilterMacroEditorModel.parse(
                "run(\"Gaussian Blur...\", \"sigma=2 stack\");\n"
                        + "run(\"Subtract Background...\", \"rolling=20 stack\");\n"
                        + "run(\"Median...\", \"radius=1 stack\");\n"
                        + "run(\"Add...\", \"value=4 stack\");"));
        ribbon.setStepState(1, ChainRibbon.StepState.SWEPT);
        ribbon.setStepState(2, ChainRibbon.StepState.BYPASSED);
        ribbon.setStepState(3, ChainRibbon.StepState.OFF);

        Dimension size = ribbon.getPreferredSize();
        ribbon.setSize(size);
        ribbon.doLayout();
        BufferedImage image = new BufferedImage(size.width, size.height,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, size.width, size.height);
        ribbon.paint(g);
        g.dispose();

        assertNear(sample(image, ribbon.stepBoundsForTest(0), 8, 0),
                ChainRibbon.FIXED_FILL, 10);
        assertNear(sample(image, ribbon.stepBoundsForTest(1), 8, 0),
                ChainRibbon.SWEPT_FILL, 10);
        Rectangle bypassed = ribbon.stepBoundsForTest(2);
        // BYPASSED is stroke-only; sample on the top edge midpoint where the
        // border is straight (avoids the rounded-corner antialias blend).
        assertNear(new Color(image.getRGB(bypassed.x + bypassed.width / 2,
                bypassed.y + 1), true),
                ChainRibbon.BYPASSED_STROKE, 35);
        assertNear(sample(image, ribbon.stepBoundsForTest(3), 8, 0),
                blend(ChainRibbon.FIXED_FILL, Color.WHITE, 102), 18);
    }

    @Test
    public void emptyMacroRendersWithoutPillsOrException() {
        ChainRibbon ribbon = new ChainRibbon(FilterMacroEditorModel.parse(""));
        Dimension size = ribbon.getPreferredSize();
        ribbon.setSize(Math.max(1, size.width), Math.max(1, size.height));
        ribbon.doLayout();

        BufferedImage image = new BufferedImage(Math.max(1, ribbon.getWidth()),
                Math.max(1, ribbon.getHeight()), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            ribbon.paint(g);
        } finally {
            g.dispose();
        }

        assertEquals(0, ribbon.stepCount());
    }

    private static Color sample(BufferedImage image,
                                Rectangle bounds,
                                int insetX,
                                int offsetY) {
        int x = bounds.x + insetX;
        int y = bounds.y + bounds.height / 2 + offsetY;
        return new Color(image.getRGB(x, y), true);
    }

    private static Color blend(Color base, Color overlay, int alpha) {
        double a = alpha / 255.0d;
        int red = (int) Math.round(base.getRed() * (1.0d - a)
                + overlay.getRed() * a);
        int green = (int) Math.round(base.getGreen() * (1.0d - a)
                + overlay.getGreen() * a);
        int blue = (int) Math.round(base.getBlue() * (1.0d - a)
                + overlay.getBlue() * a);
        return new Color(red, green, blue);
    }

    private static void assertNear(Color actual, Color expected, int tolerance) {
        assertTrue("red expected " + expected + " got " + actual,
                Math.abs(actual.getRed() - expected.getRed()) <= tolerance);
        assertTrue("green expected " + expected + " got " + actual,
                Math.abs(actual.getGreen() - expected.getGreen()) <= tolerance);
        assertTrue("blue expected " + expected + " got " + actual,
                Math.abs(actual.getBlue() - expected.getBlue()) <= tolerance);
    }
}
