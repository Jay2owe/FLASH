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
 * Marker-centroid object counting ("resolve fused objects"). For a chosen marker channel,
 * {@code appendOverlapCountColumns} writes per target object the count of marker centroids that land
 * on the object's voxels, plus HasMarker (&ge;1) and IsCluster (&ge;2) flags. The marker channel is
 * never its own target; objects with no marker inside keep a true 0 (the object is not dropped).
 */
public class OverlapCountTest {

    private static ImagePlus makeLabels(int w, int h, int[] pix) {
        ImageStack stack = new ImageStack(w, h);
        ShortProcessor sp = new ShortProcessor(w, h);
        for (int i = 0; i < pix.length; i++) sp.set(i, pix[i]);
        stack.addSlice(sp);
        return new ImagePlus("labels", stack);
    }

    private static void fill(int[] pix, int w, int x0, int x1, int y0, int y1, int label) {
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) pix[y * w + x] = label;
    }

    private static void addObjectRow(ResultsTable t, int label) {
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

    private static void registerImage(ThreeDObjectAnalysis analysis, String title, ImagePlus image) throws Exception {
        Field registryField = ThreeDObjectAnalysis.class.getDeclaredField("imageRegistry");
        registryField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, ImagePlus> registry = (Map<String, ImagePlus>) registryField.get(analysis);
        registry.put(title, image);
    }

    private static void setMarker(ThreeDObjectAnalysis analysis, String marker, String... targets) throws Exception {
        Field mf = ThreeDObjectAnalysis.class.getDeclaredField("clusterMarkerChannel");
        mf.setAccessible(true);
        mf.set(analysis, marker);
        Field tf = ThreeDObjectAnalysis.class.getDeclaredField("clusterTargets");
        tf.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Boolean> ct = (Map<String, Boolean>) tf.get(analysis);
        for (String tgt : targets) ct.put(tgt, Boolean.TRUE);
    }

    private static void invoke(ThreeDObjectAnalysis analysis, BinConfig cfg, boolean[] has,
                               Map<String, ResultsTable> tables) throws Exception {
        Method m = ThreeDObjectAnalysis.class.getDeclaredMethod("appendOverlapCountColumns",
                BinConfig.class, boolean[].class, Map.class, int.class,
                String.class, String.class, String.class, String.class);
        m.setAccessible(true);
        m.invoke(analysis, cfg, has, tables, 1, "AnimalA", "LH", "SCN", "SCN1");
    }

    @Test
    public void countsMarkerCentroidsPerTargetObjectWithFlags() throws Exception {
        BinConfig cfg = new BinConfig();
        cfg.channelNames.add("CH1");   // target
        cfg.channelNames.add("CH2");   // marker

        int w = 8, h = 8;
        // CH1 target: object 1 = big block x[1..5]y[1..5] (will be a fused cluster of 2 markers);
        //             object 2 = small block x[6..7]y[0..1] (no marker inside).
        int[] ch1 = new int[w * h];
        fill(ch1, w, 1, 5, 1, 5, 1);
        fill(ch1, w, 6, 7, 0, 1, 2);

        // CH2 marker: three single-voxel objects; centroids at (2,2) and (4,4) inside CH1 obj1,
        // and (7,7) on CH1 background.
        int[] ch2 = new int[w * h];
        ch2[2 * w + 2] = 1;
        ch2[4 * w + 4] = 2;
        ch2[7 * w + 7] = 3;

        Map<String, ResultsTable> tables = new LinkedHashMap<String, ResultsTable>();
        tables.put("CH1", new ResultsTable());
        tables.put("CH2", new ResultsTable());
        addObjectRow(tables.get("CH1"), 1);
        addObjectRow(tables.get("CH1"), 2);
        addObjectRow(tables.get("CH2"), 1);

        ThreeDObjectAnalysis analysis = new ThreeDObjectAnalysis();
        registerImage(analysis, "CH1_objects", makeLabels(w, h, ch1));
        registerImage(analysis, "CH2_objects", makeLabels(w, h, ch2));
        setMarker(analysis, "CH2", "CH1");

        invoke(analysis, cfg, new boolean[] { true, true }, tables);

        ResultsTable ch1t = tables.get("CH1");
        // Object 1 (row 0): a fused cluster of 2 markers.
        assertEquals(2.0, ch1t.getValue("CH1_OverlapCount_CH2", 0), 0.0);
        assertEquals(1.0, ch1t.getValue("CH1_HasMarker_CH2", 0), 0.0);
        assertEquals(1.0, ch1t.getValue("CH1_IsCluster_CH2", 0), 0.0);
        // Object 2 (row 1): no marker inside -> true 0, flags 0, row preserved.
        assertEquals(0.0, ch1t.getValue("CH1_OverlapCount_CH2", 1), 0.0);
        assertEquals(0.0, ch1t.getValue("CH1_HasMarker_CH2", 1), 0.0);
        assertEquals(0.0, ch1t.getValue("CH1_IsCluster_CH2", 1), 0.0);

        // The marker channel is never its own target: no overlap columns written to CH2.
        ResultsTable ch2t = tables.get("CH2");
        for (String col : ch2t.getHeadings()) {
            org.junit.Assert.assertFalse("marker channel must not get overlap columns: " + col,
                    col.contains("_OverlapCount_") || col.contains("_HasMarker_") || col.contains("_IsCluster_"));
        }
    }

    @Test
    public void singleMarkerObjectIsNotFlaggedAsCluster() throws Exception {
        BinConfig cfg = new BinConfig();
        cfg.channelNames.add("CH1");
        cfg.channelNames.add("CH2");

        int w = 8, h = 8;
        int[] ch1 = new int[w * h];
        fill(ch1, w, 1, 5, 1, 5, 1);   // one target object
        int[] ch2 = new int[w * h];
        ch2[3 * w + 3] = 1;            // exactly one marker centroid inside

        Map<String, ResultsTable> tables = new LinkedHashMap<String, ResultsTable>();
        tables.put("CH1", new ResultsTable());
        tables.put("CH2", new ResultsTable());
        addObjectRow(tables.get("CH1"), 1);

        ThreeDObjectAnalysis analysis = new ThreeDObjectAnalysis();
        registerImage(analysis, "CH1_objects", makeLabels(w, h, ch1));
        registerImage(analysis, "CH2_objects", makeLabels(w, h, ch2));
        setMarker(analysis, "CH2", "CH1");

        invoke(analysis, cfg, new boolean[] { true, true }, tables);

        ResultsTable ch1t = tables.get("CH1");
        assertEquals(1.0, ch1t.getValue("CH1_OverlapCount_CH2", 0), 0.0);
        assertEquals(1.0, ch1t.getValue("CH1_HasMarker_CH2", 0), 0.0);
        assertEquals(0.0, ch1t.getValue("CH1_IsCluster_CH2", 0), 0.0);
    }
}
