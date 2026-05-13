package flash.pipeline.ui.variations;

import ij.ImagePlus;
import ij.process.ByteProcessor;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class NoopStrategy implements VariationStrategy {

    private final int resultCount;

    public NoopStrategy() {
        this(0);
    }

    public NoopStrategy(int resultCount) {
        this.resultCount = Math.max(0, resultCount);
    }

    @Override
    public void dispatch(ParameterSweep sweep,
                         Consumer<VariationResult> publisher,
                         BooleanSupplier cancelCheck) {
        for (int i = 0; i < resultCount; i++) {
            if (cancelCheck != null && cancelCheck.getAsBoolean()) {
                return;
            }
            ParameterCombo combo = comboForIndex(sweep, i);
            ImagePlus label = new ImagePlus("noop-" + i, new ByteProcessor(1, 1));
            publisher.accept(VariationResult.success(combo, label, i, 0L, null));
        }
    }

    private static ParameterCombo comboForIndex(ParameterSweep sweep, int index) {
        if (sweep != null) {
            java.util.List<ParameterCombo> combos = sweep.combos();
            if (!combos.isEmpty()) {
                return combos.get(index % combos.size());
            }
        }
        return ParameterCombo.builder().build();
    }
}
