package flash.pipeline.analyses;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.cli.CLIArgumentParser;
import flash.pipeline.cli.CLIConfig;
import flash.pipeline.objects.BoundingBoxColoc;
import flash.pipeline.objects.CpcUtils;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.ResultsTable;
import ij.process.ShortProcessor;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Stage 04 — Family B bounding-box volume fill (doBBVol). Counts partner voxels inside a source
 * box: single-best partner object (BBVolColoc) and all partners (BBVolColocTotal), each a percent
 * of the box volume; the flag derives from single-best. Invariant: best ≤ total ≤ 100.
 */
public class BBVolTest {

    private static ImagePlus makeLabels(int w, int h, int[] slice) {
        ImageStack stack = new ImageStack(w, h);
        ShortProcessor sp = new ShortProcessor(w, h);
        for (int i = 0; i < slice.length; i++) sp.set(i, slice[i]);
        stack.addSlice(sp);
        return new ImagePlus("labels", stack);
    }

    private static void fillRect(int[] slice, int w, int x0, int x1, int y0, int y1, int label) {
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                slice[y * w + x] = label;
            }
        }
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

    @Test
    public void fillPercentSplitsSingleBestFromTotal() {
        int w = 8, h = 8;
        // Source A: 4x4 block at x[0..3] y[0..3] -> box volume 16.
        int[] aSlice = new int[w * h];
        fillRect(aSlice, w, 0, 3, 0, 3, 1);
        // Partner B: object 1 = 4 voxels, object 2 = 6 voxels, both inside A's box.
        int[] bSlice = new int[w * h];
        fillRect(bSlice, w, 0, 1, 0, 1, 1);   // 4 voxels
        fillRect(bSlice, w, 2, 3, 0, 2, 2);   // 6 voxels

        List<CpcUtils.ObjectInfo> aObjs = CpcUtils.extractObjects(makeLabels(w, h, aSlice));
        CpcUtils.ObjectInfo srcBox = aObjs.get(0);
        assertEquals(16L, srcBox.bbVolume());

        float[] fill = BoundingBoxColoc.fillPercent(makeLabels(w, h, bSlice), srcBox);
        float best = fill[0];
        float total = fill[1];
        assertEquals(6.0f / 16f * 100f, best, 1e-4f);    // best single partner object = 6 voxels
        assertEquals(10.0f / 16f * 100f, total, 1e-4f);  // all partner voxels = 10
        assertTrue("best <= total <= 100", best <= total && total <= 100f);
    }

    @Test
    public void appendBBVolWritesBestTotalAndFlag() throws Exception {
        BinConfig cfg = new BinConfig();
        cfg.channelNames.add("CH1");
        cfg.channelNames.add("CH2");

        Map<String, ResultsTable> tables = new LinkedHashMap<String, ResultsTable>();
        tables.put("CH1", new ResultsTable());
        tables.put("CH2", new ResultsTable());
        addObjectRow(tables.get("CH1"), 1);
        addObjectRow(tables.get("CH2"), 1);
        addObjectRow(tables.get("CH2"), 2);

        int w = 8, h = 8;
        int[] aSlice = new int[w * h];
        fillRect(aSlice, w, 0, 3, 0, 3, 1);
        int[] bSlice = new int[w * h];
        fillRect(bSlice, w, 0, 1, 0, 1, 1);
        fillRect(bSlice, w, 2, 3, 0, 2, 2);

        ThreeDObjectAnalysis analysis = new ThreeDObjectAnalysis();
        Field reg = ThreeDObjectAnalysis.class.getDeclaredField("imageRegistry");
        reg.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, ImagePlus> registry = (Map<String, ImagePlus>) reg.get(analysis);
        registry.put("CH1_objects", makeLabels(w, h, aSlice));
        registry.put("CH2_objects", makeLabels(w, h, bSlice));

        Method m = ThreeDObjectAnalysis.class.getDeclaredMethod("appendBBVolColocColumns",
                BinConfig.class, boolean[].class, Map.class, int.class,
                String.class, String.class, String.class, String.class);
        m.setAccessible(true);
        m.invoke(analysis, cfg, new boolean[] { true, true }, tables, 1, "AnimalA", "LH", "SCN", "SCN1");

        ResultsTable ch1 = tables.get("CH1");
        assertEquals(6.0 / 16 * 100, ch1.getValue("CH1_BBVolColoc_CH2", 0), 1e-4);
        assertEquals(10.0 / 16 * 100, ch1.getValue("CH1_BBVolColocTotal_CH2", 0), 1e-4);
        // Default BB threshold is 30; best 37.5 >= 30 -> flag 1.
        assertEquals(1.0, ch1.getValue("CH1_BBVolColoc30_CH2", 0), 0.0);

        // Reverse: each B box is fully filled by the A block -> 100%.
        ResultsTable ch2 = tables.get("CH2");
        assertEquals(100.0, ch2.getValue("CH2_BBVolColoc_CH1", 0), 1e-4);
    }

    @Test
    public void doBBVolSurvivesCliRoundTrip() {
        CLIConfig parsed = CLIArgumentParser.parse("dir=[/tmp] object.doBBVol=true");
        assertEquals(Boolean.TRUE, parsed.getObject().getDoBBVol());
        CLIConfig reparsed = CLIArgumentParser.parse(parsed.toMacroOptions());
        assertEquals(Boolean.TRUE, reparsed.getObject().getDoBBVol());
    }
}
