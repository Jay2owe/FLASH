package flash.pipeline.deconv.psf;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class EpflPsfGeneratorAdapterTest {

    /*
     * The historical "unavailablePluginReturnsNull..." test was removed: PSF synthesis no
     * longer depends on the EPFL PSF Generator plugin (its run(String) is GUI-only and
     * ignored macro options). Synthesis is now performed natively by
     * {@link ScalarPsfSynthesizer}, so an "unavailable plugin" code path no longer exists.
     */

    @Test
    public void macroOptionStringBuilderIsPureAndStable() {
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
                ScopeModality.CONFOCAL,
                Double.valueOf(1.0));

        String options = EpflPsfGeneratorAdapter.buildMacroOptions(spec, PsfModel.GIBSON_LANNI);

        assertEquals(
                "optical-model=[Gibson & Lanni 3D Optical Model] "
                        + "na=1.400000 ri-immersion=1.515000 ri-sample=1.450000 "
                        + "wavelength=520.000000 nx=64 ny=64 nz=32 "
                        + "resxy=65.000000 resz=250.000000 pinhole=1.000000",
                options);
        assertEquals(options, EpflPsfGeneratorAdapter.buildMacroOptions(spec, PsfModel.GIBSON_LANNI));
    }

    @Test
    public void normalizationAndCenteringHelpersProduceUnitCenteredPsf() {
        ImagePlus psf = offCenterPsf();
        try {
            EpflPsfGeneratorAdapter.normalizeInPlace(psf);
            EpflPsfGeneratorAdapter.centerBrightestVoxelInPlace(psf);

            assertEquals(1.0, EpflPsfGeneratorAdapter.sum(psf), 1e-6);
            int[] brightest = EpflPsfGeneratorAdapter.brightestVoxel(psf);
            assertEquals(psf.getWidth() / 2, brightest[0]);
            assertEquals(psf.getHeight() / 2, brightest[1]);
            assertEquals(psf.getStackSize() / 2, brightest[2]);
        } finally {
            close(psf);
        }
    }

    private static ImagePlus offCenterPsf() {
        ImageStack stack = new ImageStack(4, 4);
        for (int z = 0; z < 3; z++) {
            stack.addSlice(new FloatProcessor(4, 4, new float[16], null));
        }
        stack.getProcessor(1).setf(0, 2.0f);
        stack.getProcessor(2).setf(5, 3.0f);
        stack.getProcessor(3).setf(15, 10.0f);
        return new ImagePlus("off-center", stack);
    }

    private static void close(ImagePlus image) {
        if (image == null) return;
        image.changes = false;
        image.close();
        image.flush();
    }
}
