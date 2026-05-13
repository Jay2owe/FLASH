package flash.pipeline.ui.sandbox.variation;

import flash.pipeline.image.FilterMacroParser.OpType;
import flash.pipeline.image.dag.DagIR;
import flash.pipeline.image.dag.DagLine;
import flash.pipeline.image.dag.DagNode;
import flash.pipeline.image.variation.VariantPlan;
import flash.pipeline.image.variation.VariantResult;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import org.junit.Assume;
import org.junit.Test;

import javax.swing.JLabel;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class TilePanelTest {

    @Test
    public void appliedVariantShowsBadgeAndCaptionPrefix() {
        TilePanel tile = new TilePanel(
                new ImagePlus("variant", new FloatProcessor(1, 1)),
                "sigma=2",
                false,
                plan("sigma=2"));
        tile.setActions(new TileActionListener() {
            @Override public void onPromote(VariantPlan plan) {}

            @Override public void onSavePreset(VariantPlan plan) {}
        }, null);

        tile.setAppliedToBuilder(true);

        assertTrue(containsLabel(tile, "Applied"));
        assertTrue(containsLabel(tile, "Applied to builder: sigma=2"));
    }

    @Test
    public void errorTileKeepsPlanAndMessageWithoutImage() {
        VariantPlan plan = plan("bad");
        TilePanel tile = TilePanel.forError("bad", new RuntimeException("failed"), plan);

        assertTrue(tile.isErrorTile());
        assertFalse(tile.hasImage());
        assertSame(plan, tile.plan());
        assertTrue(containsLabel(tile, "bad"));
    }

    @Test
    public void mipModeDoesNotCrashOnTwoDimensionalInput() {
        TilePanel tile = new TilePanel(new ImagePlus("2d", new FloatProcessor(8, 8)),
                "2d",
                false,
                plan("2d"));

        tile.setMipMode(true);

        assertFalse(tile.mipModeForTest());
    }

    @Test
    public void gridConstructionUsesSuppliedRawSourceAndRendersErrorTiles() {
        assumeWindowAvailable();
        ImagePlus raw = stack("raw", 3);
        VariantResult ok = new VariantResult(plan("ok"), stack("ok", 3), null, 10);
        VariantResult bad = new VariantResult(plan("bad"), null,
                new RuntimeException("boom"), 5);

        VariantGridFrame frame = new VariantGridFrame("grid", raw, Arrays.asList(ok, bad));
        try {
            assertSame(raw, frame.rawTileForTest().getScrubImp());
            assertEquals(2, frame.visibleVariantTileCountForTest());
            assertEquals(1, countErrorTiles(frame));
            assertEquals(2, frame.driverForTest().size());
        } finally {
            frame.dispose();
        }
    }

    @Test
    public void gridReleasesOriginalVariantOutputAfterDisplayClone() {
        assumeWindowAvailable();
        CountingImagePlus raw = new CountingImagePlus("raw");
        CountingImagePlus output = new CountingImagePlus("output");
        VariantResult ok = new VariantResult(plan("ok"), output, null, 10);

        VariantGridFrame frame = new VariantGridFrame("grid", raw,
                Collections.singletonList(ok));
        try {
            assertEquals("original execution output should be released after display clone",
                    1, output.flushCount);
            assertEquals("raw display source remains owned by the grid",
                    0, raw.flushCount);
            assertEquals("display clone remains alive for the tile",
                    0, output.lastDuplicate.flushCount);
            assertSame(output.lastDuplicate, frame.tilesForTest().get(1).getScrubImp());
        } finally {
            frame.dispose();
        }
    }

    @Test
    public void eliminatingTileKeepsRawVisibleAndUnregistersImage() {
        assumeWindowAvailable();
        VariantResult first = new VariantResult(plan("first"), stack("first", 3), null, 1);
        VariantResult second = new VariantResult(plan("second"), stack("second", 3), null, 1);
        VariantResult third = new VariantResult(plan("third"), stack("third", 3), null, 1);
        VariantGridFrame frame = new VariantGridFrame("grid", stack("raw", 3),
                Arrays.asList(first, second, third));
        try {
            TilePanel firstVariant = frame.tilesForTest().get(1);

            frame.eliminateTile(firstVariant);

            assertTrue(frame.rawTileForTest().isVisible());
            assertEquals(2, frame.visibleVariantTileCountForTest());
            assertEquals(3, frame.driverForTest().size());
            assertTrue(frame.statusLabelForTest().getText().contains("2 visible of 3"));
        } finally {
            frame.dispose();
        }
    }

    @Test
    public void eliminatingToOneVariantLaunchesCompareHook() {
        assumeWindowAvailable();
        VariantResult first = new VariantResult(plan("first"), stack("first", 3), null, 1);
        VariantResult second = new VariantResult(plan("second"), stack("second", 3), null, 1);
        VariantGridFrame frame = new VariantGridFrame("grid", stack("raw", 3),
                Arrays.asList(first, second));
        final AtomicInteger compareCalls = new AtomicInteger();
        frame.setCompareLauncherForTest(new VariantGridFrame.CompareLauncher() {
            @Override
            public void open(VariantGridFrame owner,
                             TilePanel left,
                             TilePanel right,
                             TileActionListener actionListener) {
                compareCalls.incrementAndGet();
                assertTrue(left.isRawTile());
                assertEquals("second", right.label());
            }
        });
        try {
            frame.eliminateTile(frame.tilesForTest().get(1));

            assertEquals(1, compareCalls.get());
        } finally {
            frame.dispose();
        }
    }

    @Test
    public void sharedZBarDrivesVisibleImageTiles() {
        assumeWindowAvailable();
        VariantResult first = new VariantResult(plan("first"), stack("first", 5), null, 1);
        VariantResult second = new VariantResult(plan("second"), stack("second", 5), null, 1);
        VariantGridFrame frame = new VariantGridFrame("grid", stack("raw", 5),
                Arrays.asList(first, second));
        try {
            frame.zBarForTest().setValue(4);

            assertEquals(4, frame.rawTileForTest().getScrubImp().getCurrentSlice());
            assertEquals(4, frame.tilesForTest().get(1).getScrubImp().getCurrentSlice());
            assertEquals(4, frame.tilesForTest().get(2).getScrubImp().getCurrentSlice());
        } finally {
            frame.dispose();
        }
    }

    @Test
    public void gridMipToggleDoesNotCrashOnTwoDimensionalInput() {
        assumeWindowAvailable();
        VariantResult first = new VariantResult(plan("first"),
                new ImagePlus("first", new FloatProcessor(8, 8)), null, 1);
        VariantGridFrame frame = new VariantGridFrame("grid",
                new ImagePlus("raw", new FloatProcessor(8, 8)),
                Collections.singletonList(first));
        try {
            frame.mipToggleForTest().setSelected(true);

            assertFalse(frame.zBarForTest().isVisible());
        } finally {
            frame.dispose();
        }
    }

    private static void assumeWindowAvailable() {
        Assume.assumeFalse("JFrame construction requires a graphics environment",
                GraphicsEnvironment.isHeadless());
    }

    private static int countErrorTiles(VariantGridFrame frame) {
        int count = 0;
        for (TilePanel tile : frame.tilesForTest()) {
            if (tile.isErrorTile()) count++;
        }
        return count;
    }

    private static ImagePlus stack(String title, int slices) {
        ImageStack stack = new ImageStack(8, 8);
        for (int i = 0; i < slices; i++) {
            ByteProcessor processor = new ByteProcessor(8, 8);
            processor.set(i + 1);
            stack.addSlice("s" + i, processor);
        }
        return new ImagePlus(title, stack);
    }

    private static VariantPlan plan(String label) {
        return new VariantPlan(label, dag(), null);
    }

    private static final class CountingImagePlus extends ImagePlus {
        int flushCount;
        CountingImagePlus lastDuplicate;

        CountingImagePlus(String title) {
            super(title, new FloatProcessor(8, 8));
        }

        @Override
        public ImagePlus duplicate() {
            lastDuplicate = new CountingImagePlus(getTitle() + "-copy");
            return lastDuplicate;
        }

        @Override
        public void flush() {
            flushCount++;
            super.flush();
        }
    }

    private static DagIR dag() {
        DagLine line = new DagLine("line_A",
                Collections.singletonList(
                        new DagNode("n1", OpType.GAUSSIAN_BLUR, "sigma=2 stack")));
        return new DagIR(1,
                Collections.singletonList(line),
                Collections.emptyList(),
                "line_A",
                "native");
    }

    private static boolean containsLabel(Container container, String text) {
        for (Component child : container.getComponents()) {
            if (child instanceof JLabel && text.equals(((JLabel) child).getText())) {
                return true;
            }
            if (child instanceof Container && containsLabel((Container) child, text)) {
                return true;
            }
        }
        return false;
    }
}
