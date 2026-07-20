package flash.pipeline.deconv.psf;

import ij.ImagePlus;
import ij.ImageStack;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ScalarPsfSynthesizerTest {

    @Test
    public void bornWolfWidefieldPsfIsCenteredNormalizedAndPeakedAtZero() {
        PsfSpec spec = new PsfSpec(
                1.40, 1.515, 1.515, 520.0, 65.0, 250.0,
                64, 64, 32, ScopeModality.WIDEFIELD, null);

        ImagePlus psf = ScalarPsfSynthesizer.synthesize(spec, PsfModel.BORN_WOLF);
        try {
            assertNotNull(psf);
            assertEquals(64, psf.getWidth());
            assertEquals(64, psf.getHeight());
            assertEquals(32, psf.getStackSize());

            assertEquals(1.0, sum(psf), 1e-5);

            int[] brightest = brightest(psf);
            assertEquals(32, brightest[0]);
            assertEquals(32, brightest[1]);
            assertEquals(16, brightest[2]);

            float center = psf.getStack().getProcessor(17).getf(32, 32);
            float corner = psf.getStack().getProcessor(17).getf(0, 0);
            assertTrue("PSF peak should exceed corner intensity", center > corner * 10.0f);
        } finally {
            close(psf);
        }
    }

    @Test
    public void gibsonLanniReducesToBornWolfWhenRefractiveIndicesMatch() {
        PsfSpec spec = new PsfSpec(
                1.20, 1.515, 1.515, 488.0, 80.0, 300.0,
                48, 48, 24, ScopeModality.WIDEFIELD, null);

        ImagePlus bornWolf = ScalarPsfSynthesizer.synthesize(spec, PsfModel.BORN_WOLF);
        ImagePlus gibsonLanni = ScalarPsfSynthesizer.synthesize(spec, PsfModel.GIBSON_LANNI);
        try {
            ImageStack a = bornWolf.getStack();
            ImageStack b = gibsonLanni.getStack();
            double maxDiff = 0.0;
            for (int z = 1; z <= a.getSize(); z++) {
                int n = a.getProcessor(z).getPixelCount();
                for (int i = 0; i < n; i++) {
                    double d = Math.abs(a.getProcessor(z).getf(i) - b.getProcessor(z).getf(i));
                    if (d > maxDiff) maxDiff = d;
                }
            }
            assertTrue("Gibson-Lanni with ns=ni should match Born-Wolf within 1e-5 (was " + maxDiff + ")",
                    maxDiff < 1e-5);
        } finally {
            close(bornWolf);
            close(gibsonLanni);
        }
    }

    @Test
    public void gibsonLanniMismatchIntroducesAxialAsymmetry() {
        PsfSpec spec = new PsfSpec(
                1.30, 1.515, 1.330, 520.0, 80.0, 250.0,
                48, 48, 33, ScopeModality.WIDEFIELD, null);

        ImagePlus gibsonLanni = ScalarPsfSynthesizer.synthesize(spec, PsfModel.GIBSON_LANNI);
        try {
            int cx = gibsonLanni.getWidth() / 2;
            int cy = gibsonLanni.getHeight() / 2;
            int cz = gibsonLanni.getStackSize() / 2;
            float belowFocus = gibsonLanni.getStack().getProcessor(cz - 4 + 1).getf(cx, cy);
            float aboveFocus = gibsonLanni.getStack().getProcessor(cz + 4 + 1).getf(cx, cy);
            float peak = gibsonLanni.getStack().getProcessor(cz + 1).getf(cx, cy);

            assertTrue("Gibson-Lanni RI mismatch should create axial asymmetry",
                    Math.abs(aboveFocus - belowFocus) > peak * 0.01f);
        } finally {
            close(gibsonLanni);
        }
    }

    @Test
    public void confocalPsfIsTighterThanWidefieldForSameSpec() {
        PsfSpec widefieldSpec = new PsfSpec(
                1.40, 1.515, 1.515, 520.0, 65.0, 250.0,
                48, 48, 24, ScopeModality.WIDEFIELD, null);
        PsfSpec confocalSpec = new PsfSpec(
                1.40, 1.515, 1.515, 520.0, 65.0, 250.0,
                48, 48, 24, ScopeModality.CONFOCAL, Double.valueOf(1.0));

        ImagePlus wf = ScalarPsfSynthesizer.synthesize(widefieldSpec, PsfModel.BORN_WOLF);
        ImagePlus cf = ScalarPsfSynthesizer.synthesize(confocalSpec, PsfModel.BORN_WOLF);
        try {
            double wfDecay = lateralIntensityRatio(wf, 3);
            double cfDecay = lateralIntensityRatio(cf, 3);
            assertTrue("Confocal PSF should fall off faster than widefield"
                            + " (wf intensity at 3px / peak = " + wfDecay
                            + ", cf = " + cfDecay + ")",
                    cfDecay < wfDecay);
        } finally {
            close(wf);
            close(cf);
        }
    }

    @Test
    public void spinningDiskUsesConfocalLikePsf() {
        PsfSpec widefieldSpec = new PsfSpec(
                1.40, 1.515, 1.515, 520.0, 65.0, 250.0,
                48, 48, 24, ScopeModality.WIDEFIELD, null);
        PsfSpec spinningDiskSpec = new PsfSpec(
                1.40, 1.515, 1.515, 520.0, 65.0, 250.0,
                48, 48, 24, ScopeModality.SPINNING_DISK, null);

        ImagePlus wf = ScalarPsfSynthesizer.synthesize(widefieldSpec, PsfModel.BORN_WOLF);
        ImagePlus sd = ScalarPsfSynthesizer.synthesize(spinningDiskSpec, PsfModel.BORN_WOLF);
        try {
            assertTrue("Spinning disk PSF should fall off faster than widefield",
                    lateralIntensityRatio(sd, 3) < lateralIntensityRatio(wf, 3));
        } finally {
            close(wf);
            close(sd);
        }
    }

    @Test
    public void gaussianPsfIsCenteredAndNormalized() {
        PsfSpec spec = new PsfSpec(
                1.40, 1.515, 1.515, 520.0, 65.0, 250.0,
                32, 32, 16, ScopeModality.WIDEFIELD, null);

        ImagePlus psf = ScalarPsfSynthesizer.synthesize(spec, PsfModel.DOUGHERTY_THEORETICAL);
        try {
            assertEquals(1.0, sum(psf), 1e-5);
            int[] brightest = brightest(psf);
            assertEquals(16, brightest[0]);
            assertEquals(16, brightest[1]);
            assertEquals(8, brightest[2]);
        } finally {
            close(psf);
        }
    }

    @Test
    public void besselJ0MatchesKnownReferenceValues() {
        assertEquals(1.0, ScalarPsfSynthesizer.j0(0.0), 1e-6);
        assertEquals(0.7651976865, ScalarPsfSynthesizer.j0(1.0), 1e-6);
        assertEquals(0.2238907791, ScalarPsfSynthesizer.j0(2.0), 1e-6);
        assertEquals(-0.2600519549, ScalarPsfSynthesizer.j0(3.0), 1e-6);
        assertEquals(-0.3971498099, ScalarPsfSynthesizer.j0(4.0), 1e-6);
        assertEquals(-0.1775967713, ScalarPsfSynthesizer.j0(5.0), 1e-6);
        assertEquals(0.1506452573, ScalarPsfSynthesizer.j0(6.0), 1e-6);
        assertEquals(ScalarPsfSynthesizer.j0(4.5), ScalarPsfSynthesizer.j0(-4.5), 1e-12);
    }

    @Test
    public void onAxisFocalPlaneIntensityIsApproximatelyUnity() {
        double intensity = ScalarPsfSynthesizer.scalarIntegralIntensity(
                0.0, 0.0, 0.0, 1.4, 1.515, 1.515,
                2.0 * Math.PI / 520.0, false);
        assertEquals(1.0, intensity, 1e-4);
    }

    @Test
    public void adaptiveSimpsonMatchesDenseReferenceForOscillatoryDefocus() {
        double k = 2.0 * Math.PI / 520.0;
        double adaptive = ScalarPsfSynthesizer.scalarIntegralIntensity(
                180.0, 260.0, 0.0, 1.4, 1.515, 1.515, k, false);
        double reference = ScalarPsfSynthesizer.scalarIntegralIntensity(
                180.0, 260.0, 0.0, 1.4, 1.515, 1.515, k, false, 2048);

        assertTrue(ScalarPsfSynthesizer.integrationSteps(180.0, 260.0, 1.0) > 128);
        assertEquals(reference, adaptive, Math.max(1e-6, Math.abs(reference) * 0.02));
    }

    private static double sum(ImagePlus image) {
        ImageStack stack = image.getStack();
        double total = 0.0;
        for (int z = 1; z <= stack.getSize(); z++) {
            int n = stack.getProcessor(z).getPixelCount();
            for (int i = 0; i < n; i++) total += stack.getProcessor(z).getf(i);
        }
        return total;
    }

    private static int[] brightest(ImagePlus image) {
        ImageStack stack = image.getStack();
        int w = image.getWidth();
        int bx = 0, by = 0, bz = 0;
        float bv = -Float.MAX_VALUE;
        for (int z = 0; z < stack.getSize(); z++) {
            int n = stack.getProcessor(z + 1).getPixelCount();
            for (int i = 0; i < n; i++) {
                float v = stack.getProcessor(z + 1).getf(i);
                if (v > bv) {
                    bv = v;
                    bx = i % w;
                    by = i / w;
                    bz = z;
                }
            }
        }
        return new int[]{bx, by, bz};
    }

    private static double lateralIntensityRatio(ImagePlus image, int offsetPx) {
        int cz = image.getStackSize() / 2;
        int cx = image.getWidth() / 2;
        int cy = image.getHeight() / 2;
        float peak = image.getStack().getProcessor(cz + 1).getf(cx, cy);
        float offset = image.getStack().getProcessor(cz + 1).getf(cx + offsetPx, cy);
        return offset / peak;
    }

    private static void close(ImagePlus image) {
        if (image == null) return;
        image.changes = false;
        image.close();
        image.flush();
    }
}
