package flash.pipeline.ui.variations;

import javax.swing.SwingWorker;
import javax.swing.SwingUtilities;
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
        postStatus("Running " + strategy.getClass().getSimpleName()
                + " (" + sweep.cellCount() + " cells)...");
        strategy.dispatch(sweep, this::publishResult, this::isCancellationRequested);
        if (!isCancellationRequested()) {
            postStatus("Parameter variations complete.");
        }
        return null;
    }

    private void publishResult(VariationResult result) {
        if (isCancellationRequested() || result == null) {
            return;
        }
        publish(result);
    }

    @Override
    protected void process(List<VariationResult> chunks) {
        if (chunks == null || isCancellationRequested()) {
            return;
        }
        for (int i = 0; i < chunks.size(); i++) {
            if (isCancellationRequested()) {
                return;
            }
            VariationResult result = chunks.get(i);
            if (result != null) {
                if (onResult != null) {
                    onResult.accept(result, Integer.valueOf(deliveredCount));
                }
                deliveredCount++;
            }
        }
    }

    private boolean isCancellationRequested() {
        return isCancelled() || Thread.currentThread().isInterrupted();
    }

    private void postStatus(final String text) {
        if (onStatus == null) {
            return;
        }
        if (SwingUtilities.isEventDispatchThread()) {
            onStatus.accept(text);
            return;
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() {
                onStatus.accept(text);
            }
        });
    }
}
