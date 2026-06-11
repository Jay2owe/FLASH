package flash.pipeline.analyses;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.cli.CLIArgumentParser;
import flash.pipeline.cli.CLIConfig;
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
 * Stage 03 — Family A bounding-box centroid coincidence (doBBCpc). A box contains its object, so a
 * centroid that lies inside a partner box but outside the partner voxels is BBCPCColoc==1 while the
 * voxel-level CPCColoc==0. Also checks the Contains counts and the TargetsHit/Pattern roll-ups.
 */
public class BBCpcTest {

    private static ImagePlus makeLabels(int w, int h, int[] slice) {
        ImageStack stack = new ImageStack(w, h);
        ShortProcessor sp = new ShortProcessor(w, h);
        for (int i = 0; i < slice.length; i++) sp.set(i, slice[i]);
        stack.addSlice(sp);
        return new ImagePlus("labels", stack);
    }

    private static void addObjectRow(ResultsTable t, int label, int scnIndex, String animalName,
                                     String hemisphere, String region, String roiLabel) {
        t.incrementCounter();
        int r = t.size() - 1;
        t.setValue("Region", r, region);
        t.setValue("Hemisphere", r, hemisphere);
        t.setValue("SCN", r, scnIndex);
        t.setValue("ROI", r, roiLabel);
        t.setValue("Animal Name", r, animalName);
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

    private static void invoke(ThreeDObjectAnalysis analysis, String method, BinConfig cfg,
                               boolean[] has, Map<String, ResultsTable> tables) throws Exception {
        Method m = ThreeDObjectAnalysis.class.getDeclaredMethod(method,
                BinConfig.class, boolean[].class, Map.class, int.class,
                String.class, String.class, String.class, String.class);
        m.setAccessible(true);
        m.invoke(analysis, cfg, has, tables, 1, "AnimalA", "LH", "SCN", "SCN1");
    }

    @Test
    public void boundingBoxCoincidenceIsSupersetOfVoxelCoincidence() throws Exception {
        BinConfig cfg = new BinConfig();
        cfg.channelNames.add("CH1");
        cfg.channelNames.add("CH2");

        Map<String, ResultsTable> tables = new LinkedHashMap<String, ResultsTable>();
        tables.put("CH1", new ResultsTable());
        tables.put("CH2", new ResultsTable());
        addObjectRow(tables.get("CH1"), 1, 1, "AnimalA", "LH", "SCN", "SCN1");
        addObjectRow(tables.get("CH2"), 1, 1, "AnimalA", "LH", "SCN", "SCN1");

        int w = 8, h = 8;
        // CH1: a single object at (3,3). CH2: a sparse object whose box is [1..5]x[1..5] but whose
        // voxels are only the two opposite corners, so (3,3) is inside the box but not on a voxel.
        int[] ch1 = new int[w * h];
        ch1[3 * w + 3] = 1;
        int[] ch2 = new int[w * h];
        ch2[1 * w + 1] = 1; ch2[1 * w + 2] = 1; ch2[2 * w + 1] = 1;   // corner near (1,1)
        ch2[5 * w + 5] = 1; ch2[5 * w + 4] = 1; ch2[4 * w + 5] = 1;   // corner near (5,5)  -> centroid (3,3)

        ThreeDObjectAnalysis analysis = new ThreeDObjectAnalysis();
        registerImage(analysis, "CH1_objects", makeLabels(w, h, ch1));
        registerImage(analysis, "CH2_objects", makeLabels(w, h, ch2));

        boolean[] has = new boolean[] { true, true };
        invoke(analysis, "appendCpcColocColumns", cfg, has, tables);
        invoke(analysis, "appendBBCpcColocColumns", cfg, has, tables);

        ResultsTable ch1t = tables.get("CH1");
        ResultsTable ch2t = tables.get("CH2");

        // CH1 centroid (3,3) is inside CH2's box but not a CH2 voxel: BBCPC fires, plain CPC does not.
        assertEquals(0.0, ch1t.getValue("CH1_CPCColoc_CH2", 0), 0.0);
        assertEquals(1.0, ch1t.getValue("CH1_BBCPCColoc_CH2", 0), 0.0);

        // Contains both directions: each box holds exactly one partner centroid.
        assertEquals(1.0, ch1t.getValue("CH1_BBCPCContains_CH2", 0), 0.0);
        assertEquals(1.0, ch2t.getValue("CH2_BBCPCContains_CH1", 0), 0.0);

        // Multi-target roll-ups.
        assertEquals(1.0, ch1t.getValue("CH1_BBCPCTargetsHit", 0), 0.0);
        assertEquals("CH2", ch1t.getStringValue("CH1_BBCPCPattern", 0));
        assertEquals(1.0, ch2t.getValue("CH2_BBCPCTargetsHit", 0), 0.0);
        assertEquals("CH1", ch2t.getStringValue("CH2_BBCPCPattern", 0));
    }

    @Test
    public void doBBCpcSurvivesCliRoundTrip() {
        CLIConfig parsed = CLIArgumentParser.parse("dir=[/tmp] object.doBBCpc=true");
        assertEquals(Boolean.TRUE, parsed.getObject().getDoBBCpc());
        CLIConfig reparsed = CLIArgumentParser.parse(parsed.toMacroOptions());
        assertEquals(Boolean.TRUE, reparsed.getObject().getDoBBCpc());
    }
}
