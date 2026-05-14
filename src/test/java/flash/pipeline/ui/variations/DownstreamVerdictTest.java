package flash.pipeline.ui.variations;

import ij.ImagePlus;
import ij.process.ByteProcessor;

import org.junit.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DownstreamVerdictTest {

    @Test
    public void computesHelpHurtAndNeutralFromBaselineCount() {
        final ParameterCombo combo1 = combo(1);
        final ParameterCombo combo2 = combo(2);
        final ParameterCombo combo3 = combo(3);
        DownstreamSegmenter segmenter = DownstreamSegmenter.fixedCounter(
                "stub",
                new DownstreamSegmenter.Counter() {
                    @Override public int count(ImagePlus crop,
                                               ParameterCombo upstreamCombo,
                                               String filteredSourceKey,
                                               VariationCache cache,
                                               BooleanSupplier cancelCheck) {
                        if (upstreamCombo == null) {
                            return 18;
                        }
                        Object value = upstreamCombo.get(ParameterId.THRESHOLD);
                        int index = ((Number) value).intValue();
                        if (index == 1) return 20;
                        if (index == 2) return 30;
                        return 5;
                    }
                });

        Map<ParameterCombo, DownstreamVerdict.Verdict> verdicts =
                DownstreamVerdict.compute(Arrays.asList(
                                filterResult(combo1),
                                filterResult(combo2),
                                filterResult(combo3)),
                        segmenter,
                        image("baseline"),
                        null,
                        null,
                        null);

        assertFalse(verdicts.get(combo1).isHelp);
        assertFalse(verdicts.get(combo1).isHurt);
        assertTrue(verdicts.get(combo2).isHelp);
        assertTrue(verdicts.get(combo3).isHurt);
    }

    private static VariationResult filterResult(ParameterCombo combo) {
        return VariationResult.filterSuccess(combo, image("filtered"),
                1L, new int[256], 1.0d, 1.0d);
    }

    private static ParameterCombo combo(int index) {
        return ParameterCombo.builder()
                .put(ParameterId.THRESHOLD, Integer.valueOf(index))
                .build();
    }

    private static ImagePlus image(String title) {
        return new ImagePlus(title, new ByteProcessor(2, 2));
    }
}
