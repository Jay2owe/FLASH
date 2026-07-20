package flash.pipeline.ui.variations.strategy;

import flash.pipeline.ui.config.CellposeParameterStage;
import flash.pipeline.ui.config.StarDistParameterStage;
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
        return choose(sweep, context, cache, null);
    }

    static VariationStrategy choose(ParameterSweep sweep,
                                    VariationEngineContext context,
                                    VariationCache cache,
                                    Boolean fastNmsParityVerified) {
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
        if (sweep.method() == ParameterSweep.Method.STARDIST) {
            if (context.starDistPreviewAdapter() == null) {
                throw new IllegalStateException(
                        "StarDist variations require a StarDist preview adapter.");
            }
            if (!(context.baseParameters() instanceof StarDistParameterStage.Parameters)) {
                throw new IllegalStateException(
                        "StarDist variations require StarDist base parameters.");
            }
            StarDistParameterStage.Parameters params =
                    (StarDistParameterStage.Parameters) context.baseParameters();
            boolean fastEligible = fastNmsParityVerified == null
                    ? StarDistFastNms.canHandle(sweep)
                    : StarDistFastNms.canHandle(sweep, fastNmsParityVerified.booleanValue());
            if (fastEligible) {
                return new StarDistFastNms(context.filteredSource(),
                        sweep.cropSpec(),
                        cache,
                        context.starDistPreviewAdapter(),
                        params);
            }
            return new StarDistPerCell(context.filteredSource(),
                    sweep.cropSpec(),
                    cache,
                    context.starDistPreviewAdapter(),
                    params);
        }
        if (sweep.method() == ParameterSweep.Method.CELLPOSE) {
            if (context.cellposePreviewAdapter() == null) {
                throw new IllegalStateException(
                        "Cellpose variations require a Cellpose preview adapter.");
            }
            if (!(context.baseParameters() instanceof CellposeParameterStage.Parameters)) {
                throw new IllegalStateException(
                        "Cellpose variations require Cellpose base parameters.");
            }
            CellposeParameterStage.Parameters params =
                    (CellposeParameterStage.Parameters) context.baseParameters();
            if (CellposeOneShot.sweepsModel(sweep)) {
                return new CellposeOneShot(context.filteredSource(),
                        sweep.cropSpec(),
                        cache,
                        context.cellposePreviewAdapter(),
                        params,
                        context.configContext());
            }
            return new CellposePersistent(context.filteredSource(),
                    sweep.cropSpec(),
                    cache,
                    context.cellposePreviewAdapter(),
                    params,
                    context.configContext(),
                    context.channelName());
        }
        throw new UnsupportedOperationException(
                sweep.method().label() + " variations are not implemented yet.");
    }
}
