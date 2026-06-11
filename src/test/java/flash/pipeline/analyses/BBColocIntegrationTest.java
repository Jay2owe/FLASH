package flash.pipeline.analyses;

import flash.pipeline.bin.BinConfig;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.ResultsTable;
import ij.process.ShortProcessor;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * Stage 05 integration: all three bounding-box families on synthetic label maps with a non-default
 * BB threshold, including empty-channel zero-fill. Drives the producer append methods directly
 * (ImageJ cannot boot under surefire, so we use the registry + reflection seam).
 */
public class BBColocIntegrationTest {

    private static ImagePlus makeLabels(int w, int h, int[] slice) {
        ImageStack stack = new ImageStack(w, h);
        ShortProcessor sp = new ShortProcessor(w, h);
        for (int i = 0; i < slice.length; i++) sp.set(i, slice[i]);
        stack.addSlice(sp);
        return new ImagePlus("labels", stack);
    }

    private static void fillRect(int[] slice, int w, int x0, int x1, int y0, int y1, int label) {
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) slice[y * w + x] = label;
        }
    }

    private static void addRow(ResultsTable t, int label) {
        t.incrementCounter();
        int r = t.size() - 1;
        t.setValue("Region", r, "SCN");
        t.setValue("Hemisphere", r, "LH");
        t.setValue("SCN", r, 1);
        t.setValue("ROI", r, "SCN1");
        t.setValue("Animal Name", r, "AnimalA");
        t.setValue("Label", r, label);
        t.setValue("Volume (micron^3)", r, 100);
    }

    @SuppressWarnings("unchecked")
    private static void setBBThreshold(ThreeDObjectAnalysis analysis, Map<String, Double> values) throws Exception {
        Field f = ThreeDObjectAnalysis.class.getDeclaredField("bbThresholds");
        f.setAccessible(true);
        Map<String, Double> map = (Map<String, Double>) f.get(analysis);
        map.clear();
        map.putAll(values);
    }

    private static void registerImage(ThreeDObjectAnalysis analysis, String title, ImagePlus image) throws Exception {
        Field reg = ThreeDObjectAnalysis.class.getDeclaredField("imageRegistry");
        reg.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, ImagePlus> registry = (Map<String, ImagePlus>) reg.get(analysis);
        registry.put(title, image);
    }

    private static void invoke(ThreeDObjectAnalysis analysis, String method, BinConfig cfg,
                               boolean[] has, Map<String, ResultsTable> tables) throws Exception {
        Method m = ThreeDObjectAnalysis.class.getDeclaredMethod(method,
                BinConfig.class, boolean[].class, Map.class, int.class,
                String.class, String.class, String.class, String.class);
        m.setAccessible(true);
        m.invoke(analysis, cfg, has, tables, 1, "AnimalA", "LH", "SCN", "SCN1");
    }

    @Test
    public void allThreeFamiliesProduceValuesWithNonDefaultThresholdAndZeroFill() throws Exception {
        BinConfig cfg = new BinConfig();
        cfg.channelNames.add("CH1");
        cfg.channelNames.add("CH2");
        cfg.channelNames.add("CH3");   // empty channel

        Map<String, ResultsTable> tables = new LinkedHashMap<String, ResultsTable>();
        tables.put("CH1", new ResultsTable());
        tables.put("CH2", new ResultsTable());
        tables.put("CH3", new ResultsTable());
        addRow(tables.get("CH1"), 1);
        addRow(tables.get("CH2"), 1);

        int w = 8, h = 8;
        // CH1: 4x4 block at [0..3]x[0..3] -> box vol 16, centroid (1.5,1.5).
        int[] ch1 = new int[w * h];
        fillRect(ch1, w, 0, 3, 0, 3, 1);
        // CH2: 2x2 block at [1..2]x[1..2] -> box vol 4, fully inside CH1's box and contains CH1 centroid.
        int[] ch2 = new int[w * h];
        fillRect(ch2, w, 1, 2, 1, 2, 1);

        ThreeDObjectAnalysis analysis = new ThreeDObjectAnalysis();
        registerImage(analysis, "CH1_objects", makeLabels(w, h, ch1));
        registerImage(analysis, "CH2_objects", makeLabels(w, h, ch2));
        registerImage(analysis, "CH3_objects", makeLabels(w, h, new int[w * h]));

        Map<String, Double> thresholds = new LinkedHashMap<String, Double>();
        thresholds.put("CH1", 10.0);
        thresholds.put("CH2", 10.0);
        thresholds.put("CH3", 10.0);
        setBBThreshold(analysis, thresholds);

        boolean[] has = new boolean[] { true, true, false };
        invoke(analysis, "appendBBColocColumns", cfg, has, tables);
        invoke(analysis, "appendBBCpcColocColumns", cfg, has, tables);
        invoke(analysis, "appendBBVolColocColumns", cfg, has, tables);

        ResultsTable ch1Table = tables.get("CH1");

        // Family C — overlap: CH1 box[0..3] ∩ CH2 box[1..2] = 4 voxels / 16 box = 25%.
        assertEquals(25.0, ch1Table.getValue("CH1_BBColoc_CH2", 0), 1e-6);
        assertEquals(1.0, ch1Table.getValue("CH1_BBColoc10_CH2", 0), 0.0);   // non-default threshold 10

        // Family A — BB-CPC: CH1 centroid (1.5,1.5) is inside CH2's box.
        assertEquals(1.0, ch1Table.getValue("CH1_BBCPCColoc_CH2", 0), 0.0);
        assertEquals(1.0, ch1Table.getValue("CH1_BBCPCContains_CH2", 0), 0.0);  // CH2 centroid in CH1 box

        // Family B — volume fill: CH2's 4 voxels fill 4 of CH1's 16-voxel box = 25%.
        assertEquals(25.0, ch1Table.getValue("CH1_BBVolColoc_CH2", 0), 1e-6);
        assertEquals(25.0, ch1Table.getValue("CH1_BBVolColocTotal_CH2", 0), 1e-6);
        assertEquals(1.0, ch1Table.getValue("CH1_BBVolColoc10_CH2", 0), 0.0);

        // Empty channel CH3 zero-fills every family in both producer directions.
        assertEquals(0.0, ch1Table.getValue("CH1_BBColoc_CH3", 0), 0.0);
        assertEquals(0.0, ch1Table.getValue("CH1_BBCPCColoc_CH3", 0), 0.0);
        assertEquals(0.0, ch1Table.getValue("CH1_BBVolColoc_CH3", 0), 0.0);
        assertEquals(0.0, ch1Table.getValue("CH1_BBVolColocTotal_CH3", 0), 0.0);
    }
}
