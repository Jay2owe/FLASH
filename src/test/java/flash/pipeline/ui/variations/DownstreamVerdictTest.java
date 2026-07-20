package flash.pipeline.ui.variations;

import ij.ImagePlus;
import ij.process.ByteProcessor;

import org.junit.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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

    @Test
    public void skipsTileWhenDownstreamSegmenterThrows() {
        final ParameterCombo combo1 = combo(1);
        final ParameterCombo combo2 = combo(2);
        final ParameterCombo combo3 = combo(3);
        final AtomicInteger progressCount = new AtomicInteger();
        DownstreamSegmenter segmenter = DownstreamSegmenter.fixedCounter(
                "stub",
                new DownstreamSegmenter.Counter() {
                    @Override public int count(ImagePlus crop,
                                               ParameterCombo upstreamCombo,
                                               String filteredSourceKey,
                                               VariationCache cache,
                                               BooleanSupplier cancelCheck) {
                        if (upstreamCombo == null) {
                            return 10;
                        }
                        int index = ((Number) upstreamCombo.get(
                                ParameterId.THRESHOLD)).intValue();
                        if (index == 2) {
                            throw new IllegalStateException("bad downstream tile");
                        }
                        return 20 + index;
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
                        progress -> progressCount.incrementAndGet());

        assertTrue(verdicts.containsKey(combo1));
        assertFalse(verdicts.containsKey(combo2));
        assertTrue(verdicts.containsKey(combo3));
        assertTrue(progressCount.get() >= 4);
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
