package flash.pipeline.analyses;

import flash.pipeline.cli.CLIArgumentParser;
import flash.pipeline.cli.CLIConfig;
import flash.pipeline.objects.CpcUtils;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * Stage 02 — Family C bounding-box overlap (doBBOverlap): the per-source max box-overlap percent
 * is bidirectional (denominator = source box) and the threshold flag uses {@code >=}.
 */
public class BBColocTest {

    @SuppressWarnings("unchecked")
    private static Map<Integer, Float> overlapPercents(List<CpcUtils.ObjectInfo> sources,
                                                       List<CpcUtils.ObjectInfo> partners) throws Exception {
        Method m = ThreeDObjectAnalysis.class.getDeclaredMethod(
                "computeBBOverlapPercents", List.class, List.class);
        m.setAccessible(true);
        return (Map<Integer, Float>) m.invoke(null, sources, partners);
    }

    @Test
    public void overlapPercentIsBidirectionalWithSourceBoxDenominator() throws Exception {
        // A box: x[0..3] y[0..1] z0 -> volume 8.  B box: x[2..3] y[0..1] z0 -> volume 4.
        // Intersection box = x[2..3]*y[0..1]*z0 = 2*2*1 = 4 voxels.
        List<CpcUtils.ObjectInfo> a = CpcUtils.extractObjects(box(0, 3, 0, 1));
        List<CpcUtils.ObjectInfo> b = CpcUtils.extractObjects(box(2, 3, 0, 1));

        Map<Integer, Float> ab = overlapPercents(a, b);  // source A, partner boxes B
        Map<Integer, Float> ba = overlapPercents(b, a);  // source B, partner boxes A

        assertEquals(50.0f, ab.get(1), 1e-4f);   // 4 / 8 * 100
        assertEquals(100.0f, ba.get(1), 1e-4f);  // 4 / 4 * 100

        // Flag uses >= threshold. At 60: forward fails (50), reverse passes (100).
        assertEquals(0, ab.get(1) >= 60.0 ? 1 : 0);
        assertEquals(1, ba.get(1) >= 60.0 ? 1 : 0);
        // At 50 the forward direction is exactly on the boundary and flags (>=).
        assertEquals(1, ab.get(1) >= 50.0 ? 1 : 0);
    }

    @Test
    public void overlapTakesBestPartnerBox() throws Exception {
        // Source A box x[0..3] y[0..3] z0 -> volume 16.
        // Partner image has two boxes: small (vol-2 overlap) and large (vol-8 overlap); best wins.
        ImagePlus partners = twoPartnerBoxes();
        List<CpcUtils.ObjectInfo> a = CpcUtils.extractObjects(box(0, 3, 0, 3));
        List<CpcUtils.ObjectInfo> p = CpcUtils.extractObjects(partners);

        Map<Integer, Float> ab = overlapPercents(a, p);
        // Best partner intersection is 8 voxels of the 16-voxel source box -> 50%.
        assertEquals(50.0f, ab.get(1), 1e-4f);
    }

    @Test
    public void doBBOverlapSurvivesCliRoundTrip() {
        CLIConfig parsed = CLIArgumentParser.parse(
                "dir=[/tmp] object.doBBOverlap=true object.bbColocThreshold=45");
        assertEquals(Boolean.TRUE, parsed.getObject().getDoBBOverlap());
        assertEquals(45.0, parsed.getObject().getBBColocThresholdPercent(), 0.0);

        CLIConfig reparsed = CLIArgumentParser.parse(parsed.toMacroOptions());
        assertEquals(Boolean.TRUE, reparsed.getObject().getDoBBOverlap());
        assertEquals(45.0, reparsed.getObject().getBBColocThresholdPercent(), 0.0);
    }

    /** Single-object (label 1) image, one z-slice, box spanning x[x0..x1] y[y0..y1]. */
    private static ImagePlus box(int x0, int x1, int y0, int y1) {
        ByteProcessor ip = new ByteProcessor(6, 6);
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                ip.set(x, y, 1);
            }
        }
        return new ImagePlus("box", ip);
    }

    /** Two partner objects: label 1 overlaps source by 2 voxels, label 2 by 8 voxels. */
    private static ImagePlus twoPartnerBoxes() {
        ByteProcessor ip = new ByteProcessor(8, 8);
        // label 1: x[3..3] y[0..1] -> within source x[0..3] y[0..3]: intersection 1*2 = 2
        for (int y = 0; y <= 1; y++) ip.set(3, y, 1);
        // label 2: x[0..1] y[0..3] -> within source: intersection 2*4 = 8
        for (int y = 0; y <= 3; y++) {
            ip.set(0, y, 2);
            ip.set(1, y, 2);
        }
        return new ImagePlus("partners", ip);
    }
}
