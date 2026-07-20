package flash.pipeline.objects;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Stage 09 (engine slice): validates {@link ObjectIntensityProfiler} math on synthetic stacks —
 * ring vs core radial/shell shape, principal-axis elongation + orientation invariance, and
 * within-box correlation sign. No ImageJ runtime needed (pure ImagePlus/ImageStack in memory).
 */
public class ObjectIntensityProfilerTest {

    private interface Fn { float v(int x, int y, int z); }

    private static ImagePlus stack(int w, int h, int d, Fn fn, String title) {
        ImageStack st = new ImageStack(w, h);
        for (int z = 0; z < d; z++) {
            FloatProcessor fp = new FloatProcessor(w, h);
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++)
                    fp.setf(x, y, fn.v(x, y, z));
            st.addSlice(fp);
        }
        return new ImagePlus(title, st);
    }

    private static double dist(int x, int y, int z, double cx, double cy, double cz) {
        double dx = x - cx, dy = y - cy, dz = z - cz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Test
    public void ringAndCoreSeparateRadiallyAndByShell() {
        final int N = 21; final double c = 10;
        ImagePlus labels = stack(N, N, N, (x, y, z) -> dist(x, y, z, c, c, c) <= 9 ? 1f : 0f, "src_objects");
        ImagePlus core = stack(N, N, N, (x, y, z) -> dist(x, y, z, c, c, c) <= 3 ? 100f : 2f, "Core");
        ImagePlus ring = stack(N, N, N, (x, y, z) -> {
            double r = dist(x, y, z, c, c, c); return (r >= 6 && r <= 9) ? 100f : 2f;
        }, "Ring");

        Map<String, ImagePlus> raw = new LinkedHashMap<String, ImagePlus>();
        raw.put("Core", core); raw.put("Ring", ring);

        OipConfig cfg = new OipConfig();
        cfg.doRadial = true; cfg.doShell = true; cfg.doMarginal = false;
        cfg.doPrincipalAxis = false; cfg.doAngular = false; cfg.doWithinBox = false;
        cfg.radialBins = 10; cfg.shells = 3;

        List<ObjectProfileResult> results = ObjectIntensityProfiler.profile(
                labels, raw, CpcUtils.extractObjects(labels), "Core", null, cfg);
        assertEquals(1, results.size());
        ObjectProfileResult r = results.get(0);
        ObjectProfileResult.PartnerProfiles cp = r.byPartner.get("Core");
        ObjectProfileResult.PartnerProfiles rp = r.byPartner.get("Ring");

        // Radial: the ring peaks further out than the core; the core is enriched at its centre.
        assertTrue("ring peak should be outside core peak", rp.radialPeakR > cp.radialPeakR);
        assertTrue("core enriched at centre", cp.radialCoreEdge > 1.0);
        assertTrue("ring less core-enriched than core", cp.radialCoreEdge > rp.radialCoreEdge);

        // Shells: core brightest in the inner shell; ring brightest away from the inner shell.
        assertTrue(cp.shellInner > cp.shellMid);
        assertTrue(rp.shellMid > rp.shellInner);
    }

    @Test
    public void principalAxisElongationIsOrientationInvariant() {
        ObjectProfileResult alongX = ellipsoidResult(8, 4, 4);
        ObjectProfileResult alongY = ellipsoidResult(4, 8, 4);

        // 2:1 ellipsoid → elongation ≈ 2 regardless of orientation.
        assertEquals(2.0, alongX.elongation, 0.35);
        assertEquals(2.0, alongY.elongation, 0.35);
        assertEquals(1.0, alongX.flatness, 0.35);

        // Major eigenvector (column 0) points along the long axis in each case.
        assertTrue("major axis ~ x", Math.abs(alongX.eigenvectors[0][0]) > 0.9);
        assertTrue("major axis ~ y", Math.abs(alongY.eigenvectors[1][0]) > 0.9);
    }

    private static ObjectProfileResult ellipsoidResult(final double a, final double b, final double cc) {
        final int N = 25; final double c = 12;
        ImagePlus labels = stack(N, N, N, (x, y, z) -> {
            double dx = (x - c) / a, dy = (y - c) / b, dz = (z - c) / cc;
            return (dx * dx + dy * dy + dz * dz) <= 1.0 ? 1f : 0f;
        }, "src_objects");
        ImagePlus flat = stack(N, N, N, (x, y, z) -> 50f, "Sig");
        Map<String, ImagePlus> raw = new LinkedHashMap<String, ImagePlus>();
        raw.put("Sig", flat);
        OipConfig cfg = new OipConfig();
        cfg.doRadial = false; cfg.doMarginal = false; cfg.doAngular = false; cfg.doShell = false;
        cfg.doWithinBox = false; cfg.doPrincipalAxis = true;
        List<ObjectProfileResult> res = ObjectIntensityProfiler.profile(
                labels, raw, CpcUtils.extractObjects(labels), "Sig", null, cfg);
        assertEquals(1, res.size());
        return res.get(0);
    }

    @Test
    public void withinBoxCorrelationSignIsCorrect() {
        final int N = 12;
        ImagePlus labels = stack(N, N, N, (x, y, z) -> (x >= 2 && x <= 9) ? 1f : 0f, "src_objects");
        ImagePlus src = stack(N, N, N, (x, y, z) -> (float) x, "Src");
        ImagePlus pos = stack(N, N, N, (x, y, z) -> (float) x, "Pos");
        ImagePlus neg = stack(N, N, N, (x, y, z) -> (float) (N - x), "Neg");

        Map<String, ImagePlus> raw = new LinkedHashMap<String, ImagePlus>();
        raw.put("Src", src); raw.put("Pos", pos); raw.put("Neg", neg);

        OipConfig cfg = new OipConfig();
        cfg.doRadial = false; cfg.doMarginal = false; cfg.doAngular = false; cfg.doShell = false;
        cfg.doPrincipalAxis = false; cfg.doWithinBox = true;

        List<ObjectProfileResult> res = ObjectIntensityProfiler.profile(
                labels, raw, CpcUtils.extractObjects(labels), "Src", null, cfg);
        ObjectProfileResult r = res.get(0);
        assertTrue(r.byPartner.get("Pos").withinBoxPearson > 0.95);
        assertTrue(r.byPartner.get("Neg").withinBoxPearson < -0.95);
    }
}
