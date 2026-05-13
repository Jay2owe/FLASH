package flash.pipeline.ui.variations;

import javax.swing.SwingWorker;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class VariationExecutor extends SwingWorker<Void, VariationResult> {

    private final ParameterSweep sweep;
    private final VariationStrategy strategy;
    private final VariationCache cache;
    private final BiConsumer<VariationResult, Integer> onResult;
    private final Consumer<String> onStatus;
    private int deliveredCount;

    public VariationExecutor(ParameterSweep sweep,
                             VariationStrategy strategy,
                             VariationCache cache,
                             BiConsumer<VariationResult, Integer> onResult,
                             Consumer<String> onStatus) {
        if (sweep == null) {
            throw new IllegalArgumentException("sweep must not be null");
        }
        if (strategy == null) {
            throw new IllegalArgumentException("strategy must not be null");
        }
        this.sweep = sweep;
        this.strategy = strategy;
        this.cache = cache;
        this.onResult = onResult;
        this.onStatus = onStatus;
    }

    public VariationCache cache() {
        return cache;
    }

    @Override
    protected Void doInBackground() throws Exception {
        if (onStatus != null) {
            onStatus.accept("Running " + strategy.getClass().getSimpleName()
                    + " (" + sweep.cellCount() + " cells)...");
        }
        strategy.dispatch(sweep, this::publishResult, this::isCancelled);
        if (onStatus != null && !isCancelled()) {
            onStatus.accept("Parameter variations complete.");
        }
        return null;
    }

    private void publishResult(VariationResult result) {
        publish(result);
    }

    @Override
    protected void process(List<VariationResult> chunks) {
        if (onResult == null || chunks == null) {
            return;
        }
        for (int i = 0; i < chunks.size(); i++) {
            VariationResult result = chunks.get(i);
            if (result != null) {
                onResult.accept(result, Integer.valueOf(deliveredCount));
                deliveredCount++;
            }
        }
    }
}
