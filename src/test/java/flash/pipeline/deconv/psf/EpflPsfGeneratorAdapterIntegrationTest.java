package flash.pipeline.deconv.psf;

import flash.pipeline.deconv.DeconvolutionAvailability;
import ij.ImagePlus;
import org.junit.Assume;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class EpflPsfGeneratorAdapterIntegrationTest {

    @Test
    public void synthesizeProducesNormalizedCenteredBornWolfPsfWhenPluginIsAvailable() {
        Assume.assumeTrue("EPFL PSF Generator is not available in this runtime.",
                DeconvolutionAvailability.isPsfGeneratorAvailable());

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
