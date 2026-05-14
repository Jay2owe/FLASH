package flash.pipeline.ui.variations;

import flash.pipeline.ui.variations.state.VariationStateStore;

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
    private final VariationStateStore stateStore;
    private int deliveredCount;

    public VariationExecutor(ParameterSweep sweep,
                             VariationStrategy strategy,
                             VariationCache cache,
                             BiConsumer<VariationResult, Integer> onResult,
                             Consumer<String> onStatus) {
        this(sweep, strategy, cache, onResult, onStatus, null);
    }

    public VariationExecutor(ParameterSweep sweep,
                             VariationStrategy strategy,
                             VariationCache cache,
                             BiConsumer<VariationResult, Integer> onResult,
                             Consumer<String> onStatus,
                             VariationStateStore stateStore) {
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
        this.stateStore = stateStore;
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
        if (chunks == null) {
            return;
        }
        for (int i = 0; i < chunks.size(); i++) {
            VariationResult result = chunks.get(i);
            if (result != null) {
                if (onResult != null) {
                    onResult.accept(result, Integer.valueOf(deliveredCount));
                }
                recordCompletion(result);
                deliveredCount++;
            }
        }
    }

    private void recordCompletion(VariationResult result) {
        if (stateStore == null
                || result == null
                || result.hasError()
                || result.label() == null) {
            return;
        }
        String cacheKey = VariationCache.keyFor(sweep, result.combo());
        stateStore.recordCompletion(sweep, result.combo(), cacheKey,
                result.nObjects(), result.durationMs());
    }
}
