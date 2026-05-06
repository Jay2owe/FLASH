package flash.pipeline.decontamination;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;

public class MaskCombinerTest {

    @Test
    public void combinesMasksDeterministically() {
        boolean[] left = new boolean[]{true, true, false, false};
        boolean[] right = new boolean[]{true, false, true, false};

        assertArrayEquals(new boolean[]{true, false, false, false}, MaskCombiner.and(left, right));
        assertArrayEquals(new boolean[]{true, true, true, false}, MaskCombiner.or(left, right));
        assertArrayEquals(new boolean[]{false, true, false, false}, MaskCombiner.andNot(left, right));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsLengthMismatch() {
        MaskCombiner.and(new boolean[]{true}, new boolean[]{true, false});
    }
}
