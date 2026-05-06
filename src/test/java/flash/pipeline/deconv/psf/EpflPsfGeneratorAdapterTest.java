package flash.pipeline.deconv.psf;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class EpflPsfGeneratorAdapterTest {

    @Before
    public void setUp() {
        EpflPsfGeneratorAdapter.resetForTest();
    }

    @After
    public void tearDown() {
        EpflPsfGeneratorAdapter.resetForTest();
    }

    @Test
    public void unavailablePluginReturnsNullAndLogsExactlyOnce() {
        final List<String> messages = new ArrayList<String>();
        EpflPsfGeneratorAdapter.setAvailabilityProbeForTest(new EpflPsfGeneratorAdapter.AvailabilityProbe() {
            @Override
            public boolean isPsfGeneratorAvailable() {
                return false;
            }

            @Override
            public String installInstructionUrl(String engineKey) {
                return "https://example.test/psf";
            }
        });
        EpflPsfGeneratorAdapter.setImageJRunnerForTest(new EpflPsfGeneratorAdapter.ImageJRunner() {
            @Override
            public void run(String command, String options) {
                throw new AssertionError("ImageJ should not be invoked when the plugin is unavailable.");
            }

            @Override
            public int[] getWindowIds() {
                return null;
            }

            @Override
            public ImagePlus getImage(int id) {
                return null;
            }
        });
        EpflPsfGeneratorAdapter.setLogSinkForTest(new EpflPsfGeneratorAdapter.LogSink() {
            @Override
            public void log(String message) {
                messages.add(message);
            }
        });

        assertNull(EpflPsfGeneratorAdapter.synthesize(spec(), PsfModel.BORN_WOLF));
        assertNull(EpflPsfGeneratorAdapter.synthesize(spec(), PsfModel.BORN_WOLF));

        assertEquals(1, messages.size());
        assertTrue(messages.get(0).contains("https://example.test/psf"));
    }

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

    private static PsfSpec spec() {
        return new PsfSpec(
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
