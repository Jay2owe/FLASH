package flash.pipeline.ui.variations;

import flash.pipeline.deconv.engine.Algorithm;
import flash.pipeline.deconv.engine.DeconvSettings;
import flash.pipeline.deconv.psf.PsfModel;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class DeconvComboSettingsTest {

    private static DeconvSettings base() {
        return new DeconvSettings("DL2", Algorithm.RL, PsfModel.GIBSON_LANNI, 15, 0.01d);
    }

    @Test
    public void unsweptAxesKeepBaseValues() {
        ParameterCombo combo = ParameterCombo.builder()
                .put(DeconvParameterId.ITERATIONS, Integer.valueOf(30))
                .build();
        DeconvSettings resolved = DeconvComboSettings.resolve(combo, base());
        assertEquals("DL2", resolved.engineKey());
        assertEquals(Algorithm.RL, resolved.algorithm());
        assertEquals(PsfModel.GIBSON_LANNI, resolved.psfModel());
        assertEquals(30, resolved.iterations());
        assertEquals(0.01d, resolved.regularization(), 1e-9);
    }

    @Test
    public void allAxesApplied() {
        ParameterCombo combo = ParameterCombo.builder()
                .put(DeconvParameterId.ENGINE, "CLIJ2")
                .put(DeconvParameterId.ALGORITHM, "RL_TV")
                .put(DeconvParameterId.PSF_MODEL, "BORN_WOLF")
                .put(DeconvParameterId.ITERATIONS, Integer.valueOf(40))
                .put(DeconvParameterId.REGULARIZATION, Double.valueOf(0.05d))
                .build();
        DeconvSettings resolved = DeconvComboSettings.resolve(combo, base());
        assertEquals("CLIJ2", resolved.engineKey());
        assertEquals(Algorithm.RL_TV, resolved.algorithm());
        assertEquals(PsfModel.BORN_WOLF, resolved.psfModel());
        assertEquals(40, resolved.iterations());
        assertEquals(0.05d, resolved.regularization(), 1e-9);
    }

    @Test
    public void categoricalAxesResolveByDisplayName() {
        ParameterCombo combo = ParameterCombo.builder()
                .put(DeconvParameterId.ALGORITHM, "Richardson-Lucy + TV")
                .put(DeconvParameterId.PSF_MODEL, "Born & Wolf")
                .build();
        DeconvSettings resolved = DeconvComboSettings.resolve(combo, base());
        assertEquals(Algorithm.RL_TV, resolved.algorithm());
        assertEquals(PsfModel.BORN_WOLF, resolved.psfModel());
    }

    @Test
    public void unknownCategoricalTokenKeepsBase() {
        ParameterCombo combo = ParameterCombo.builder()
                .put(DeconvParameterId.ALGORITHM, "not-an-algorithm")
                .build();
        DeconvSettings resolved = DeconvComboSettings.resolve(combo, base());
        assertEquals(Algorithm.RL, resolved.algorithm());
    }

    @Test
    public void nullComboReturnsBaseInstance() {
        DeconvSettings b = base();
        assertSame(b, DeconvComboSettings.resolve(null, b));
    }

    @Test
    public void parameterIdMetadata() {
        assertEquals(ParameterKey.ValueKind.STRING, DeconvParameterId.ENGINE.valueKind());
        assertEquals(ParameterKey.ValueKind.STRING, DeconvParameterId.ALGORITHM.valueKind());
        assertEquals(ParameterKey.ValueKind.STRING, DeconvParameterId.PSF_MODEL.valueKind());
        assertEquals(ParameterKey.ValueKind.NUMBER, DeconvParameterId.ITERATIONS.valueKind());
        assertEquals(ParameterKey.ValueKind.NUMBER, DeconvParameterId.REGULARIZATION.valueKind());
        assertEquals(DeconvParameterId.ENGINE, DeconvParameterId.fromStableKey("engine"));
        assertEquals(DeconvParameterId.PSF_MODEL, DeconvParameterId.fromStableKey("psf_model"));
        assertEquals(DeconvParameterId.PSF_MODEL, DeconvParameterId.fromStableKey("PSF_MODEL"));
    }
}
