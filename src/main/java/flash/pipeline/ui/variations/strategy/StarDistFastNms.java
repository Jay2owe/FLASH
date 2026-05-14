package flash.pipeline.ui.variations.strategy;

import flash.pipeline.stardist.StarDistVariationRunner;
import flash.pipeline.ui.config.StarDistParameterStage;
import flash.pipeline.ui.variations.CropSpec;
import flash.pipeline.ui.variations.ParameterId;
import flash.pipeline.ui.variations.ParameterKey;
import flash.pipeline.ui.variations.ParameterSweep;
import flash.pipeline.ui.variations.ParameterValueList;
import flash.pipeline.ui.variations.VariationCache;
import flash.pipeline.ui.variations.VariationResult;
import flash.pipeline.ui.variations.VariationStrategy;

import ij.ImagePlus;

import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class StarDistFastNms implements VariationStrategy {

    private final ImagePlus filteredSource;
    private final CropSpec crop;
    private final VariationCache cache;
    private final StarDistParameterStage.PreviewAdapter previewAdapter;
    private final StarDistParameterStage.Parameters baseParams;

    public StarDistFastNms(ImagePlus filteredSource,
                           CropSpec crop,
                           VariationCache cache,
                           StarDistParameterStage.PreviewAdapter previewAdapter,
                           StarDistParameterStage.Parameters baseParams) {
        if (filteredSource == null) {
            throw new IllegalArgumentException("filteredSource must not be null");
        }
        if (previewAdapter == null) {
            throw new IllegalArgumentException("previewAdapter must not be null");
        }
        if (baseParams == null) {
            throw new IllegalArgumentException("baseParams must not be null");
        }
        this.filteredSource = filteredSource;
        this.crop = crop == null ? CropSpec.full() : crop;
        this.cache = cache;
        this.previewAdapter = previewAdapter;
        this.baseParams = baseParams;
    }

    public static boolean canHandle(ParameterSweep sweep) {
        return canHandle(sweep, StarDistVariationRunner.isFastNmsParityVerified());
    }

    static boolean canHandle(ParameterSweep sweep, boolean parityVerified) {
        if (!parityVerified || sweep == null
                || sweep.method() != ParameterSweep.Method.STARDIST) {
            return false;
        }
        Map<ParameterKey, ParameterValueList> valueLists = sweep.valueLists();
        for (Map.Entry<ParameterKey, ParameterValueList> entry : valueLists.entrySet()) {
            ParameterValueList values = entry.getValue();
            if (values == null) {
                return false;
            }
            if (values.size() <= 1) {
                continue;
            }
            ParameterKey key = entry.getKey();
            if (!(key instanceof ParameterId)) {
                return false;
            }
            ParameterId id = (ParameterId) key;
            if (id != ParameterId.PROB_THRESH && id != ParameterId.NMS_THRESH) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void dispatch(ParameterSweep sweep,
                         Consumer<VariationResult> publisher,
                         BooleanSupplier cancelCheck) {
        if (!canHandle(sweep)) {
            throw new IllegalStateException(
                    "StarDist fast NMS is disabled until parity is verified, or this "
                            + "sweep varies parameters outside probability/NMS thresholds.");
        }
        throw new UnsupportedOperationException(
                "StarDist fast NMS dispatch is intentionally inactive until the "
                        + "StarDistVariationRunner parity gate is enabled.");
    }
}
