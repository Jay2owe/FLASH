package flash.pipeline.ui.variations.analysis;

import flash.pipeline.ui.variations.ParameterCombo;
import flash.pipeline.ui.variations.ParameterId;

import ij.ImagePlus;
import ij.process.ByteProcessor;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IouStabilityTest {

    @Test
    public void oneDimensionalSweepReturnsMiddleOfStableRun() {
        List<ParameterCombo> combos = oneAxisCombos(5);
        List<ImagePlus> labels = Arrays.asList(
                mask(8, 1, 0),
                mask(8, 1, 1, 2, 3),
                mask(8, 1, 1, 2, 3, 4),
                mask(8, 1, 1, 2, 3),
                mask(8, 1, 7));

        OptionalInt stable = IouStability.findMostStable(combos, labels);

        assertTrue(stable.isPresent());
        assertEquals(2, stable.getAsInt());
    }

    @Test
    public void twoDimensionalSweepReturnsCentreOfStablePlateau() {
        List<ParameterCombo> combos = twoAxisCombos(3, 3);
        List<ImagePlus> labels = Arrays.asList(
                mask(5, 5, 10),
                mask(5, 5, 11),
                mask(5, 5, 12),
                mask(5, 5, 13),
                stableMask(),
                stableMask(),
                mask(5, 5, 14),
                stableMask(),
                stableMask());

        OptionalInt stable = IouStability.findMostStable(combos, labels);

        assertTrue(stable.isPresent());
        assertEquals(8, stable.getAsInt());
        assertEquals(1.0d, IouStability.meanNeighbourIou(combos, labels, 8),
                0.000001d);
    }

    @Test
    public void allEmptySweepReturnsEmpty() {
        List<ParameterCombo> combos = oneAxisCombos(3);
        List<ImagePlus> labels = Arrays.asList(
                mask(4, 4),
                mask(4, 4),
                mask(4, 4));

        OptionalInt stable = IouStability.findMostStable(combos, labels);

        assertFalse(stable.isPresent());
    }

    private static List<ParameterCombo> oneAxisCombos(int count) {
        List<ParameterCombo> combos = new ArrayList<ParameterCombo>();
        for (int i = 0; i < count; i++) {
            combos.add(ParameterCombo.builder()
                    .put(ParameterId.THRESHOLD, Integer.valueOf(i))
                    .build());
        }
        return combos;
    }

    private static List<ParameterCombo> twoAxisCombos(int rows, int cols) {
        List<ParameterCombo> combos = new ArrayList<ParameterCombo>();
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                combos.add(ParameterCombo.builder()
                        .put(ParameterId.PROB_THRESH, Double.valueOf(row))
                        .put(ParameterId.NMS_THRESH, Double.valueOf(col))
                        .build());
            }
        }
        return combos;
    }

    private static ImagePlus stableMask() {
        return mask(5, 5, 0, 1, 5, 6);
    }

    private static ImagePlus mask(int width, int height, int... activeIndexes) {
        byte[] pixels = new byte[width * height];
        for (int i = 0; i < activeIndexes.length; i++) {
            pixels[activeIndexes[i]] = (byte) 255;
        }
        return new ImagePlus("mask", new ByteProcessor(width, height, pixels, null));
    }
}
