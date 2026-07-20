package flash.pipeline.deconv.engine;

import flash.pipeline.deconv.psf.PsfModel;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;

public class DeconvSettingsTest {

    private static DeconvSettings base() {
        return new DeconvSettings("DL2", Algorithm.RL, PsfModel.GIBSON_LANNI, 15, 0.01d);
    }

    @Test
    public void withersReturnNewInstancesAndChangeOnlyOneField() {
        DeconvSettings base = base();
        DeconvSettings changed = base.withIterations(40);
        assertNotSame(base, changed);
        assertEquals(40, changed.iterations());
        assertEquals("DL2", changed.engineKey());
        assertEquals(Algorithm.RL, changed.algorithm());
        assertEquals(PsfModel.GIBSON_LANNI, changed.psfModel());
        assertEquals(0.01d, changed.regularization(), 1e-9);
        // original untouched
        assertEquals(15, base.iterations());
    }

    @Test
    public void equalsAndHashCodeReflectAllFields() {
        assertEquals(base(), base());
        assertEquals(base().hashCode(), base().hashCode());
        assertNotEquals(base(), base().withEngineKey("CLIJ2"));
        assertNotEquals(base(), base().withAlgorithm(Algorithm.RL_TV));
        assertNotEquals(base(), base().withPsfModel(PsfModel.BORN_WOLF));
        assertNotEquals(base(), base().withRegularization(0.02d));
    }
}
