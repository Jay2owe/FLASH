package flash.pipeline.analyses;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.ResultsTable;
import ij.process.ShortProcessor;
import flash.pipeline.bin.BinConfig;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Regression: when one channel has 0 objects, the OTHER (non-zero) channels
 * must still receive real volumetric and CPC colocalisation values.
 */
public class ColocZeroChannelTest {

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** Build a 16-bit label image with exactly the given labels at the specified pixel indices. */
    private static ImagePlus makeLabels(int w, int h, int nSlices, int[][] labelsPerSlice) {
        ImageStack stack = new ImageStack(w, h);
        for (int s = 0; s < nSlices; s++) {
            ShortProcessor sp = new ShortProcessor(w, h);
            int[] data = labelsPerSlice[s];
            for (int i = 0; i < data.length; i++) sp.set(i, data[i]);
            stack.addSlice(sp);
        }
        ImagePlus imp = new ImagePlus("labels", stack);
        return imp;
    }

    /** Build a placeholder row matching what appendStatsToChannelTable creates for a 0-object channel. */
    private static void addPlaceholderRow(ResultsTable t, int scnIndex, String animalName,
                                          String hemisphere, String region, String roiLabel) {
        t.incrementCounter();
        int r = t.size() - 1;
        t.setValue("Region", r, region);
        t.setValue("Hemisphere", r, hemisphere);
        t.setValue("SCN", r, scnIndex);
        t.setValue("ROI", r, roiLabel);
        t.setValue("Animal Name", r, animalName);
        t.setValue("Volume (micron^3)", r, 0);
    }

    /** Build N rows for a channel that detected N objects, with sequential Labels. */
    private static void addObjectRows(ResultsTable t, int n, int scnIndex, String animalName,
                                      String hemisphere, String region, String roiLabel) {
        for (int i = 0; i < n; i++) {
            t.incrementCounter();
            int r = t.size() - 1;
            t.setValue("Region", r, region);
            t.setValue("Hemisphere", r, hemisphere);
            t.setValue("SCN", r, scnIndex);
            t.setValue("ROI", r, roiLabel);
            t.setValue("Animal Name", r, animalName);
            t.setValue("Label", r, i + 1);
            t.setValue("Volume (micron^3)", r, 100);
        }
    }

    private static void registerImage(ThreeDObjectAnalysis analysis, String title, ImagePlus image) throws Exception {
        Field registryField = ThreeDObjectAnalysis.class.getDeclaredField("imageRegistry");
        registryField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, ImagePlus> registry = (Map<String, ImagePlus>) registryField.get(analysis);
        registry.put(title, image);
    }

    private static void invokeAppendColoc(ThreeDObjectAnalysis analysis, BinConfig cfg,
                                          boolean[] channelHasObjects,
                                          Map<String, ResultsTable> tables) throws Exception {
        Method m = ThreeDObjectAnalysis.class.getDeclaredMethod(
                "appendColocColumns",
                BinConfig.class, boolean[].class, Map.class, int.class,
                String.class, String.class, String.class, String.class);
        m.setAccessible(true);
        m.invoke(analysis, cfg, channelHasObjects, tables, 1, "AnimalA", "LH", "SCN", "SCN1");
    }

    private static String captureImageJLogOutput(ThrowingRunnable action) throws Exception {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, "UTF-8"));
        String ijLog = null;
        try {
            if (IJ.getLog() != null) IJ.log("\\Clear");
            action.run();
            ijLog = IJ.getLog();
        } finally {
            System.out.flush();
            System.setOut(originalOut);
        }
        return out.toString("UTF-8") + (ijLog == null ? "" : ijLog);
    }

    @Test
    public void volumetricColocFiresForChannelsWithObjectsWhenOneChannelHasZero() throws Exception {
        // 3-channel scenario:
        //   CH1 has 0 objects
        //   CH2 has 2 overlapping objects with CH3
        //   CH3 has 2 overlapping objects with CH2
        // Expected: pair (CH2, CH3) goes through the COMPUTE branch (not zero-fill).

        BinConfig cfg = new BinConfig();
        cfg.channelNames.add("CH1");
        cfg.channelNames.add("CH2");
        cfg.channelNames.add("CH3");

        // Build per-channel ResultsTable with one image's worth of rows.
        Map<String, ResultsTable> tables = new LinkedHashMap<>();
        tables.put("CH1", new ResultsTable());
        tables.put("CH2", new ResultsTable());
        tables.put("CH3", new ResultsTable());
        addPlaceholderRow(tables.get("CH1"), 1, "AnimalA", "LH", "SCN", "SCN1");
        addObjectRows(tables.get("CH2"), 2, 1, "AnimalA", "LH", "SCN", "SCN1");
        addObjectRows(tables.get("CH3"), 2, 1, "AnimalA", "LH", "SCN", "SCN1");

        // Build label images. CH2 and CH3 share two completely overlapping objects.
        int w = 10, h = 10;
        int[] sliceCH2 = new int[w * h];
        int[] sliceCH3 = new int[w * h];
        // Object 1: 3x3 block at (1,1) — labels match in both channels for full overlap
        for (int y = 1; y <= 3; y++) for (int x = 1; x <= 3; x++) {
            sliceCH2[y * w + x] = 1;
            sliceCH3[y * w + x] = 1;
        }
        // Object 2: 3x3 block at (5,5)
        for (int y = 5; y <= 7; y++) for (int x = 5; x <= 7; x++) {
            sliceCH2[y * w + x] = 2;
            sliceCH3[y * w + x] = 2;
        }
        ImagePlus ch2Objects = makeLabels(w, h, 1, new int[][] { sliceCH2 });
        ImagePlus ch3Objects = makeLabels(w, h, 1, new int[][] { sliceCH3 });
        // CH1 has an empty label image (would have been registered as such by the pipeline)
        ImagePlus ch1Objects = makeLabels(w, h, 1, new int[][] { new int[w * h] });

        // Reflectively instantiate ThreeDObjectAnalysis and populate its registry.
        ThreeDObjectAnalysis analysis = new ThreeDObjectAnalysis();
        registerImage(analysis, "CH1_objects", ch1Objects);
        registerImage(analysis, "CH2_objects", ch2Objects);
        registerImage(analysis, "CH3_objects", ch3Objects);

        boolean[] channelHasObjects = new boolean[] { false, true, true };

        String log = captureImageJLogOutput(new ThrowingRunnable() {
            @Override
            public void run() throws Exception {
                invokeAppendColoc(analysis, cfg, channelHasObjects, tables);
            }
        });

        // The compute path should have written a positive overlap for CH2's objects vs CH3
        // (and vice versa). If the pair fell through to !aHas||!bHas, all values would be 0.
        ResultsTable ch2 = tables.get("CH2");
        ResultsTable ch3 = tables.get("CH3");

        double ch2VsCh3Overlap0 = ch2.getValue("Colocalisation with CH3", 0);
        double ch2VsCh3Overlap1 = ch2.getValue("Colocalisation with CH3", 1);
        double ch3VsCh2Overlap0 = ch3.getValue("Colocalisation with CH2", 0);
        double ch3VsCh2Overlap1 = ch3.getValue("Colocalisation with CH2", 1);

        assertEquals("CH2 row 0 should have 100% overlap with CH3", 100.0, ch2VsCh3Overlap0, 0.001);
        assertEquals("CH2 row 1 should have 100% overlap with CH3", 100.0, ch2VsCh3Overlap1, 0.001);
        assertEquals("CH3 row 0 should have 100% overlap with CH2", 100.0, ch3VsCh2Overlap0, 0.001);
        assertEquals("CH3 row 1 should have 100% overlap with CH2", 100.0, ch3VsCh2Overlap1, 0.001);

        // Pairs involving CH1 should be zero
        assertEquals(0.0, ch2.getValue("Colocalisation with CH1", 0), 0.001);
        assertEquals(0.0, ch3.getValue("Colocalisation with CH1", 0), 0.001);
        assertTrue(log.contains("CH2 vs: CH1 (no objects in CH1), CH3 (2 interactions)"));
        assertTrue(log.contains("CH3 vs: CH1 (no objects in CH1), CH2 (2 interactions)"));
    }

    @Test
    public void volumetricColocKeepsPartialOverlapValuesWithoutDebugLogs() throws Exception {
        BinConfig cfg = new BinConfig();
        cfg.channelNames.add("CH1");
        cfg.channelNames.add("CH2");

        Map<String, ResultsTable> tables = new LinkedHashMap<>();
        tables.put("CH1", new ResultsTable());
        tables.put("CH2", new ResultsTable());
        addObjectRows(tables.get("CH1"), 2, 1, "AnimalA", "LH", "SCN", "SCN1");
        addObjectRows(tables.get("CH2"), 2, 1, "AnimalA", "LH", "SCN", "SCN1");

        int w = 4, h = 3;
        int[] sliceCH1 = new int[w * h];
        int[] sliceCH2 = new int[w * h];
        // CH1 objects are 4 pixels each; CH2 objects are 2 pixels fully inside them.
        sliceCH1[0] = 1; sliceCH1[1] = 1; sliceCH1[4] = 1; sliceCH1[5] = 1;
        sliceCH1[2] = 2; sliceCH1[3] = 2; sliceCH1[6] = 2; sliceCH1[7] = 2;
        sliceCH2[0] = 1; sliceCH2[1] = 1;
        sliceCH2[2] = 2; sliceCH2[3] = 2;

        ThreeDObjectAnalysis analysis = new ThreeDObjectAnalysis();
        registerImage(analysis, "CH1_objects", makeLabels(w, h, 1, new int[][] { sliceCH1 }));
        registerImage(analysis, "CH2_objects", makeLabels(w, h, 1, new int[][] { sliceCH2 }));

        String log = captureImageJLogOutput(new ThrowingRunnable() {
            @Override
            public void run() throws Exception {
                invokeAppendColoc(analysis, cfg, new boolean[] { true, true }, tables);
            }
        });

        ResultsTable ch1 = tables.get("CH1");
        ResultsTable ch2 = tables.get("CH2");
        assertEquals(50.0, ch1.getValue("Colocalisation with CH2", 0), 0.001);
        assertEquals(50.0, ch1.getValue("Colocalisation with CH2", 1), 0.001);
        assertEquals(100.0, ch2.getValue("Colocalisation with CH1", 0), 0.001);
        assertEquals(100.0, ch2.getValue("Colocalisation with CH1", 1), 0.001);
        assertTrue(log.contains("CH1 vs: CH2 (2 interactions)"));
        assertTrue(log.contains("CH2 vs: CH1 (2 interactions)"));
        assertFalse(log.contains("[COLOC DEBUG]"));
        assertFalse(log.contains("[COLOC] pixel overlap"));
    }

    @Test
    public void emptyObjectPairSkipsIntensityMetricWorkAndWritesEmptyMetrics() throws Exception {
        BinConfig cfg = new BinConfig();
        cfg.channelNames.add("CH1");
        cfg.channelNames.add("CH2");

        Map<String, ResultsTable> tables = new LinkedHashMap<>();
        tables.put("CH1", new ResultsTable());
        tables.put("CH2", new ResultsTable());
        addPlaceholderRow(tables.get("CH1"), 1, "AnimalA", "LH", "SCN", "SCN1");
        addObjectRows(tables.get("CH2"), 1, 1, "AnimalA", "LH", "SCN", "SCN1");

        int[] ch2Labels = new int[9];
        ch2Labels[0] = 1;
        ch2Labels[1] = 1;
        ThreeDObjectAnalysis analysis = new ThreeDObjectAnalysis();
        registerImage(analysis, "CH1_objects", makeLabels(3, 3, 1, new int[][] { new int[9] }));
        registerImage(analysis, "CH2_objects", makeLabels(3, 3, 1, new int[][] { ch2Labels }));

        // These mismatched raw images would log "channel dimensions differ" if intensity metrics ran.
        registerImage(analysis, "CH1_unfiltered", makeLabels(3, 3, 1, new int[][] { new int[9] }));
        registerImage(analysis, "CH2_unfiltered", makeLabels(4, 4, 1, new int[][] { new int[16] }));

        String log = captureImageJLogOutput(new ThrowingRunnable() {
            @Override
            public void run() throws Exception {
                invokeAppendColoc(analysis, cfg, new boolean[] { false, true }, tables);
            }
        });

        ResultsTable ch1 = tables.get("CH1");
        ResultsTable ch2 = tables.get("CH2");
        assertEquals(0.0, ch1.getValue("Colocalisation with CH2", 0), 0.001);
        assertEquals(0.0, ch2.getValue("Colocalisation with CH1", 0), 0.001);
        assertEquals("NaN", ch1.getStringValue("CH1_Pearson_CH2", 0));
        assertEquals("NaN", ch2.getStringValue("CH2_Pearson_CH1", 0));
        assertFalse(log.contains("channel dimensions differ"));
    }

    @Test
    public void cpcColocFiresForChannelsWithObjectsWhenOneChannelHasZero() throws Exception {
        // Same shape as the volumetric test, but exercising appendCpcColocColumns.
        // Ensures that a 0-object channel does not block CPC for the remaining pair.
        BinConfig cfg = new BinConfig();
        cfg.channelNames.add("CH1");
        cfg.channelNames.add("CH2");
        cfg.channelNames.add("CH3");

        Map<String, ResultsTable> tables = new LinkedHashMap<>();
        tables.put("CH1", new ResultsTable());
        tables.put("CH2", new ResultsTable());
        tables.put("CH3", new ResultsTable());
        addPlaceholderRow(tables.get("CH1"), 1, "AnimalA", "LH", "SCN", "SCN1");
        addObjectRows(tables.get("CH2"), 2, 1, "AnimalA", "LH", "SCN", "SCN1");
        addObjectRows(tables.get("CH3"), 2, 1, "AnimalA", "LH", "SCN", "SCN1");

        int w = 10, h = 10;
        int[] sliceCH2 = new int[w * h];
        int[] sliceCH3 = new int[w * h];
        for (int y = 1; y <= 3; y++) for (int x = 1; x <= 3; x++) {
            sliceCH2[y * w + x] = 1; sliceCH3[y * w + x] = 1;
        }
        for (int y = 5; y <= 7; y++) for (int x = 5; x <= 7; x++) {
            sliceCH2[y * w + x] = 2; sliceCH3[y * w + x] = 2;
        }
        ImagePlus ch1Objects = makeLabels(w, h, 1, new int[][] { new int[w * h] });
        ImagePlus ch2Objects = makeLabels(w, h, 1, new int[][] { sliceCH2 });
        ImagePlus ch3Objects = makeLabels(w, h, 1, new int[][] { sliceCH3 });

        ThreeDObjectAnalysis analysis = new ThreeDObjectAnalysis();
        registerImage(analysis, "CH1_objects", ch1Objects);
        registerImage(analysis, "CH2_objects", ch2Objects);
        registerImage(analysis, "CH3_objects", ch3Objects);

        boolean[] channelHasObjects = new boolean[] { false, true, true };

        Method m = ThreeDObjectAnalysis.class.getDeclaredMethod(
                "appendCpcColocColumns",
                BinConfig.class, boolean[].class, Map.class, int.class,
                String.class, String.class, String.class, String.class);
        m.setAccessible(true);
        m.invoke(analysis, cfg, channelHasObjects, tables, 1, "AnimalA", "LH", "SCN", "SCN1");

        ResultsTable ch2 = tables.get("CH2");
        ResultsTable ch3 = tables.get("CH3");

        // CH2 vs CH3 CPC must be 1 for each object (centroids fall inside partner objects).
        assertEquals(1.0, ch2.getValue("CH2_CPCColoc_CH3", 0), 0.001);
        assertEquals(1.0, ch2.getValue("CH2_CPCColoc_CH3", 1), 0.001);
        assertEquals(1.0, ch3.getValue("CH3_CPCColoc_CH2", 0), 0.001);
        assertEquals(1.0, ch3.getValue("CH3_CPCColoc_CH2", 1), 0.001);

        // CH1 pair must be zero
        assertEquals(0.0, ch2.getValue("CH2_CPCColoc_CH1", 0), 0.001);
        assertEquals(0.0, ch3.getValue("CH3_CPCColoc_CH1", 0), 0.001);
    }
}
