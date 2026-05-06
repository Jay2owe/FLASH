package flash.pipeline.deconv.psf;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class PsfSpecTest {

    @Test
    public void equalsAndHashCodeUseStructuralValueSemantics() {
        PsfSpec first = spec(520.0, 200.0);
        PsfSpec second = spec(520.0, 200.0);

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    public void specsDifferingByOneFloatAreNotEqual() {
        PsfSpec first = spec(520.0, 200.0);
        PsfSpec second = spec(520.0, 200.0001);

        assertFalse(first.equals(second));
    }

    private static PsfSpec spec(double wavelengthNm, double pixelSizeZNm) {
        return new PsfSpec(
                1.40,
                1.515,
                1.450,
                wavelengthNm,
                65.0,
                pixelSizeZNm,
                64,
                64,
                32,
                ScopeModality.WIDEFIELD,
                null);
    }
}
