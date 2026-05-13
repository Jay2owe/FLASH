package flash.pipeline.image.variation;

import java.util.List;

/**
 * Listener for variant execution progress.
 *
 * <p>Callbacks are dispatched on the Swing event-dispatch thread by
 * {@link VariantExecutor}, so UI callers can update controls directly.</p>
 */
public interface ProgressCallback {

    void onStart(int total);

    void onVariantComplete(int completed, int total, VariantResult result);

    void onAllDone(List<VariantResult> results);
}
