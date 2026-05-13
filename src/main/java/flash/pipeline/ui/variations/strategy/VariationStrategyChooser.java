package flash.pipeline.ui.variations.strategy;

import flash.pipeline.ui.variations.ParameterSweep;
import flash.pipeline.ui.variations.VariationCache;
import flash.pipeline.ui.variations.VariationEngineContext;
import flash.pipeline.ui.variations.VariationStrategy;

public final class VariationStrategyChooser {

    private VariationStrategyChooser() {
    }

    public static VariationStrategy choose(ParameterSweep sweep,
                                           VariationEngineContext context,
                                           VariationCache cache) {
        if (sweep == null) {
            throw new IllegalArgumentException("sweep must not be null");
        }
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        if (sweep.method() == ParameterSweep.Method.CLASSICAL) {
            if (context.classicalPreviewAdapter() == null) {
                throw new IllegalStateException(
                        "Classical variations require a Classical preview adapter.");
            }
            return new ClassicalSweep(context.filteredSource(),
                    sweep.cropSpec(),
                    cache,
                    context.classicalPreviewAdapter(),
                    Runtime.getRuntime().availableProcessors() - 1);
        }
        throw new UnsupportedOperationException(
                sweep.method().label() + " variations are not implemented yet.");
    }
}
