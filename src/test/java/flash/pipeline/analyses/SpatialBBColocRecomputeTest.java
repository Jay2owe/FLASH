package flash.pipeline.analyses;

import flash.pipeline.analyses.wizard.SpatialSetupConfig;
import flash.pipeline.io.CalibrationIO;
import flash.pipeline.io.CsvTableIO;
import flash.pipeline.io.FlashProjectLayout;
import ij.ImagePlus;
import ij.ImageStack;
import ij.io.FileSaver;
import ij.process.ShortProcessor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.PrintWriter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end regression for the standalone-Spatial bounding-box recompute path
 * ({@link SpatialAnalysis#runBBColocIfNeeded}) driven through the real
 * {@code execute() -> runEarlyPhase()} flow with a disk-backed
 * {@code DiskLabelImageProvider}. Two channels, one object each, saved as label
 * TIFs in the segmentation image folder; their object CSVs carry NO bounding-box
 * columns, so Spatial must recompute all three families (overlap, BB-CPC, volume
 * fill) from the label images and persist them.
 *
 * <p>The geometry mirrors {@link BBColocIntegrationTest} (which exercises the
 * producer side in 3D Object Analysis), so a divergence between producer and
 * consumer formulas/direction would surface here as a value mismatch.
 *
 * <p>Per-channel BB thresholds differ (A=20, B=50) to confirm the recompute names
 * and evaluates the integer-threshold flag column per source channel, not from a
 * single shared threshold.
 */
public class SpatialBBColocRecomputeTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void recomputeWritesAllThreeBBFamiliesInBothDirectionsFromDiskLabels() throws Exception {
        File root = temp.newFolder("spatial-bb-recompute");
        Fixture fixture = createFixture(root);

        runBB(root);

        // ---- Direction A <- partner B (A is source). A box[0..3]^3-ish; B fully inside. ----
        CsvTableIO.ChannelData a = CsvTableIO.loadChannelCsv(fixture.aCsv, "A");
        assertNotNull(a);
        // Family C overlap: A box ∩ B box = 4 voxels / A box 16 = 25%.
        assertEquals(25.0, a.getDouble(0, "A_BBColoc_B"), 1e-6);
        assertEquals(1.0, a.getDouble(0, "A_BBColoc20_B"), 0.0);   // 25 >= A threshold 20
        // Family A BB-CPC: A centroid inside B box; B centroid inside A box.
        assertEquals(1.0, a.getDouble(0, "A_BBCPCColoc_B"), 0.0);
        assertEquals(1.0, a.getDouble(0, "A_BBCPCContains_B"), 0.0);
        // Family B volume fill: B's 4 voxels fill 4 of A's 16-voxel box = 25%.
        assertEquals(25.0, a.getDouble(0, "A_BBVolColoc_B"), 1e-6);
        assertEquals(25.0, a.getDouble(0, "A_BBVolColocTotal_B"), 1e-6);
        assertEquals(1.0, a.getDouble(0, "A_BBVolColoc20_B"), 0.0);
        // BB-CPC roll-up reads the just-written coloc cell.
        assertEquals(1.0, a.getDouble(0, "A_BBCPCTargetsHit"), 0.0);
        assertEquals("B", a.get(0, "A_BBCPCPattern"));

        // ---- Direction B <- partner A (B is source). B box fully covered by A. ----
        CsvTableIO.ChannelData b = CsvTableIO.loadChannelCsv(fixture.bCsv, "B");
        assertNotNull(b);
        // B box ∩ A box = 4 voxels / B box 4 = 100%.
        assertEquals(100.0, b.getDouble(0, "B_BBColoc_A"), 1e-6);
        assertEquals(1.0, b.getDouble(0, "B_BBColoc50_A"), 0.0);   // 100 >= B threshold 50, names with 50
        assertEquals(1.0, b.getDouble(0, "B_BBCPCColoc_A"), 0.0);
        assertEquals(1.0, b.getDouble(0, "B_BBCPCContains_A"), 0.0);
        // A fully fills B's 4-voxel box = 100%.
        assertEquals(100.0, b.getDouble(0, "B_BBVolColoc_A"), 1e-6);
        assertEquals(100.0, b.getDouble(0, "B_BBVolColocTotal_A"), 1e-6);
        assertEquals(1.0, b.getDouble(0, "B_BBVolColoc50_A"), 0.0);
        assertEquals(1.0, b.getDouble(0, "B_BBCPCTargetsHit"), 0.0);
        assertEquals("A", b.get(0, "B_BBCPCPattern"));

        // A's flag column must use A's threshold int (20), never B's (50), and vice versa.
        assertFalse("A must not carry B's threshold int in its flag name",
                a.colIdx.containsKey("A_BBColoc50_B"));
        assertFalse("B must not carry A's threshold int in its flag name",
                b.colIdx.containsKey("B_BBColoc20_A"));
    }

    /**
     * Once the continuous BB columns exist in both directions, a re-run reuses them
     * (skip guard) and does not depend on the label images. Deleting the TIFs and
     * re-running must leave the values intact rather than zero-filling.
     */
    @Test
    public void recomputeIsSkippedWhenColumnsAlreadyPresentAndLabelsAreGone() throws Exception {
        File root = temp.newFolder("spatial-bb-reuse");
        Fixture fixture = createFixture(root);

        runBB(root);
        double firstOverlap = CsvTableIO.loadChannelCsv(fixture.aCsv, "A").getDouble(0, "A_BBColoc_B");
        assertEquals(25.0, firstOverlap, 1e-6);

        // Remove the label images: a recompute would now find no partner voxels and zero-fill.
        assertTrue(fixture.aLabels.delete());
        assertTrue(fixture.bLabels.delete());

        runBB(root);

        CsvTableIO.ChannelData a = CsvTableIO.loadChannelCsv(fixture.aCsv, "A");
        assertNotNull(a);
        assertEquals("reused continuous column must be preserved, not recomputed to 0",
                25.0, a.getDouble(0, "A_BBColoc_B"), 1e-6);
        assertEquals(25.0, a.getDouble(0, "A_BBVolColoc_B"), 1e-6);
    }

    private static void runBB(File root) {
        SpatialSetupConfig.DerivedConfig config = new SpatialSetupConfig.DerivedConfig();
        config.doBBOverlap = true;
        config.doBBCpc = true;
        config.doBBVol = true;
        config.bbColocThresholdPercent = 30.0;          // fallback; overridden per channel below
        config.bbThresholds.put("A", 20.0);
        config.bbThresholds.put("B", 50.0);
        SpatialAnalysis analysis = new SpatialAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.setWizardConfig(config);
        analysis.execute(root.getAbsolutePath());
    }

    private static Fixture createFixture(File root) throws Exception {
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(root.getAbsolutePath());
        File objectsDir = layout.tablesObjectsWriteDir();
        assertTrue(objectsDir.mkdirs());
        CalibrationIO.write(objectsDir, 1.0, 1.0, 1.0, "um");

        File animalDir = new File(layout.analysisImagesSegmentationDir(), "Mouse1");
        assertTrue(animalDir.mkdirs());

        // Channel A: 4x4 block at x[0..3] y[0..3] (z=0) -> box volume 16, centroid (1.5,1.5,0).
        ImagePlus aLabel = labels(8, 8, 2, 0, 3, 0, 3, 0, 0);
        // Channel B: 2x2 block at x[1..2] y[1..2] (z=0) -> box volume 4, fully inside A's box.
        ImagePlus bLabel = labels(8, 8, 2, 1, 2, 1, 2, 0, 0);

        File aLabels = new File(animalDir, "A_objects_LH_SCN.tif");
        File bLabels = new File(animalDir, "B_objects_LH_SCN.tif");
        assertTrue(new FileSaver(aLabel).saveAsTiffStack(aLabels.getAbsolutePath()));
        assertTrue(new FileSaver(bLabel).saveAsTiffStack(bLabels.getAbsolutePath()));

        File aCsv = new File(objectsDir, "A.csv");
        File bCsv = new File(objectsDir, "B.csv");
        writeChannelCsv(aCsv, 16);   // Volume column value is cosmetic here
        writeChannelCsv(bCsv, 4);

        return new Fixture(aCsv, bCsv, aLabels, bLabels);
    }

    private static void writeChannelCsv(File csv, int volume) throws Exception {
        PrintWriter out = new PrintWriter(csv, "UTF-8");
        try {
            out.println("SCN,Animal Name,Hemisphere,Region,ROI,Label,XM,YM,ZM,Volume (micron^3)");
            out.println("1,Mouse1,LH,SCN,,1,1.5,1.5,0," + volume);
        } finally {
            out.close();
        }
    }

    /** Single labelled cuboid (label 1) in a w x h x slices ShortProcessor stack. */
    private static ImagePlus labels(int w, int h, int slices,
                                    int x0, int x1, int y0, int y1, int z0, int z1) {
        ImageStack stack = new ImageStack(w, h);
        for (int z = 0; z < slices; z++) {
            ShortProcessor sp = new ShortProcessor(w, h);
            if (z >= z0 && z <= z1) {
                for (int y = y0; y <= y1; y++) {
                    for (int x = x0; x <= x1; x++) {
                        sp.set(x, y, 1);
                    }
                }
            }
            stack.addSlice(sp);
        }
        return new ImagePlus("labels", stack);
    }

    private static final class Fixture {
        final File aCsv;
        final File bCsv;
        final File aLabels;
        final File bLabels;

        private Fixture(File aCsv, File bCsv, File aLabels, File bLabels) {
            this.aCsv = aCsv;
            this.bCsv = bCsv;
            this.aLabels = aLabels;
            this.bLabels = bLabels;
        }
    }
}
