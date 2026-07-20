package flash.pipeline.deconv.psf;

import ij.ImagePlus;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Smoke test for the public {@link EpflPsfGeneratorAdapter#synthesize} entry point used by
 * {@link PsfCache}. Synthesis now runs natively in {@link ScalarPsfSynthesizer}, so there is
 * no longer an {@code Assume.assumeTrue} guard on an installed external plugin.
 */
public class EpflPsfGeneratorAdapterIntegrationTest {

    @Test
    public void synthesizeProducesNormalizedCenteredBornWolfPsf() {
        PsfSpec spec = new PsfSpec(
                1.40,
                1.515,
                1.450,
                520.0,
                65.0,
                250.0,
                64,
                64,
                32,
                ScopeModality.WIDEFIELD,
                null);

        ImagePlus psf = EpflPsfGeneratorAdapter.synthesize(spec, PsfModel.BORN_WOLF);
        try {
            assertNotNull(psf);
            assertEquals(64, psf.getWidth());
            assertEquals(64, psf.getHeight());
            assertEquals(32, psf.getStackSize());
            assertEquals(1.0, EpflPsfGeneratorAdapter.sum(psf), 1e-6);

            int[] brightest = EpflPsfGeneratorAdapter.brightestVoxel(psf);
            assertEquals(psf.getWidth() / 2, brightest[0]);
            assertEquals(psf.getHeight() / 2, brightest[1]);
            assertEquals(psf.getStackSize() / 2, brightest[2]);
        } finally {
            close(psf);
        }
    }

    private static void close(ImagePlus image) {
        if (image == null) return;
        image.changes = false;
        image.close();
        image.flush();
    }
}
