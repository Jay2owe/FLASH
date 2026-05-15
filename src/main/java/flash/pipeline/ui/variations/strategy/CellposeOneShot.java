package flash.pipeline.ui.variations.strategy;

import flash.pipeline.cellpose.Cellpose3DRunner;
import flash.pipeline.segmentation.SegmentationMethod;
import flash.pipeline.ui.config.CellposeParameterStage;
import flash.pipeline.ui.config.ConfigQcContext;
import flash.pipeline.ui.preview.ObjectSizeFilterPreview;
import flash.pipeline.ui.variations.CropSpec;
import flash.pipeline.ui.variations.MacroPreprocessor;
import flash.pipeline.ui.variations.ParameterCombo;
import flash.pipeline.ui.variations.ParameterId;
import flash.pipeline.ui.variations.ParameterKey;
import flash.pipeline.ui.variations.ParameterSweep;
import flash.pipeline.ui.variations.ParameterValueList;
import flash.pipeline.ui.variations.VariationCache;
import flash.pipeline.ui.variations.VariationResult;
import flash.pipeline.ui.variations.VariationStrategy;

import ij.ImagePlus;
import ij.measure.ResultsTable;

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class CellposeOneShot implements VariationStrategy {

    private final ImagePlus filteredSource;
    private final CropSpec crop;
    private final VariationCache cache;
    private final CellposeParameterStage.PreviewAdapter previewAdapter;
    private final CellposeParameterStage.Parameters baseParams;
    private final ConfigQcContext configContext;
    private final MacroPreprocessor macroPreprocessor = new MacroPreprocessor();

    public CellposeOneShot(ImagePlus filteredSource,
                           CropSpec crop,
                           VariationCache cache,
                           CellposeParameterStage.PreviewAdapter previewAdapter,
                           CellposeParameterStage.Parameters baseParams,
                           ConfigQcContext configContext) {
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
        this.configContext = configContext;
    }

    @Override
    public void dispatch(ParameterSweep sweep,
                         Consumer<VariationResult> publisher,
                         BooleanSupplier cancelCheck) {
        dispatchCombos(sweep, SweepDispatchOrder.order(sweep), publisher, cancelCheck);
    }

    void dispatchCombos(ParameterSweep sweep,
                        List<ParameterCombo> ordered,
                        Consumer<VariationResult> publisher,
                        BooleanSupplier cancelCheck) {
        validate(sweep, publisher);
        CropSpec activeCrop = sweep.cropSpec() == null ? crop : sweep.cropSpec();
        ImagePlus cropped = activeCrop.apply(filteredSource);
        ImagePlus companion = null;
        try {
            companion = createCroppedCompanion(activeCrop);
            for (int i = 0; i < ordered.size(); i++) {
                if (isCancelled(cancelCheck)) {
                    return;
                }
                ParameterCombo combo = ordered.get(i);
                CellposeParameterStage.Parameters parameters = overlay(baseParams, combo);
                String cacheKey = VariationCache.keyFor(sweep, combo);
                ImagePlus cached = cache == null ? null : cache.get(cacheKey);
                if (cached != null) {
                    publisher.accept(resultFor(combo, cached, cropped, 0L));
                    continue;
                }
                runOne(cropped, sweep, companionFor(parameters, companion), combo, cacheKey,
                        parameters, publisher, cancelCheck);
            }
        } finally {
            closeIfOwned(companion, filteredSource, cropped);
            closeIfOwned(cropped, filteredSource, null);
        }
    }

    private void validate(ParameterSweep sweep, Consumer<VariationResult> publisher) {
        if (sweep == null) {
            throw new IllegalArgumentException("sweep must not be null");
        }
        if (sweep.method() != ParameterSweep.Method.CELLPOSE) {
            throw new IllegalArgumentException("CellposeOneShot only accepts Cellpose sweeps");
        }
        if (publisher == null) {
            throw new IllegalArgumentException("publisher must not be null");
        }
    }

    private void runOne(ImagePlus cropped,
                        ParameterSweep sweep,
                        ImagePlus companion,
                        ParameterCombo combo,
                        String cacheKey,
                        CellposeParameterStage.Parameters parameters,
                        Consumer<VariationResult> publisher,
                        BooleanSupplier cancelCheck) {
        if (isCancelled(cancelCheck)) {
            return;
        }
        long started = System.currentTimeMillis();
        ImagePlus input = null;
        try {
            input = macroPreprocessor.prepare(cropped, sweep, combo);
            ImagePlus label = previewAdapter.runPreview(input, companion, parameters);
            if (label == null) {
                throw new IllegalStateException("Cellpose returned no label map.");
            }
            long durationMs = Math.max(1L, System.currentTimeMillis() - started);
            VariationResult result = resultFor(combo, label, input, durationMs);
            if (cache != null) {
                cache.put(cacheKey, result.label());
            }
            if (!isCancelled(cancelCheck)) {
                publisher.accept(result);
            }
        } catch (Throwable t) {
            if (!isCancelled(cancelCheck)) {
                publisher.accept(VariationResult.failure(combo, t));
            }
        } finally {
            macroPreprocessor.closeIfOwned(input, cropped);
        }
    }

    private VariationResult resultFor(ParameterCombo combo,
                                      ImagePlus label,
                                      ImagePlus reference,
                                      long durationMs) {
        ResultsTable stats = ObjectSizeFilterPreview.statisticsFromLabelMap(label, reference);
        int count = label == null ? 0 : previewAdapter.countLabels(label);
        return VariationResult.success(combo, label, count, durationMs, stats);
    }

    private ImagePlus createCroppedCompanion(CropSpec activeCrop) {
        if (baseParams.secondChannelIndex < 0) {
            return null;
        }
        ImagePlus full = null;
        try {
            full = previewAdapter.createFilteredCompanionSource(
                    configContext, baseParams.secondChannelIndex);
            if (full == null) {
                return null;
            }
            ImagePlus cropped = activeCrop.apply(full);
            if (cropped != full) {
                previewAdapter.close(full);
            }
            return cropped;
        } catch (Exception e) {
            if (full != null) {
                previewAdapter.close(full);
            }
            return null;
        }
    }

    private static ImagePlus companionFor(CellposeParameterStage.Parameters parameters,
                                          ImagePlus companion) {
        return parameters != null && parameters.secondChannelIndex >= 0
                ? companion
                : null;
    }

    static boolean sweepsModel(ParameterSweep sweep) {
        if (sweep == null) {
            return false;
        }
        Map<ParameterKey, ParameterValueList> values = sweep.valueLists();
        ParameterValueList modelValues = values.get(ParameterId.MODEL);
        return modelValues != null && modelValues.size() > 1;
    }

    static CellposeParameterStage.Parameters overlay(
            CellposeParameterStage.Parameters base,
            ParameterCombo combo) {
        CellposeParameterStage.Parameters p = base == null
                ? CellposeParameterStage.parseMethod(null)
                : base;
        String model = stringParameter(combo, ParameterId.MODEL, p.modelToken);
        return new CellposeParameterStage.Parameters(
                SegmentationMethod.canonicalCellposeModelKey(model),
                p.secondChannelIndex,
                doubleParameter(combo, ParameterId.DIAMETER, p.diameter),
                doubleParameter(combo, ParameterId.FLOW_THRESHOLD, p.flowThreshold),
                doubleParameter(combo, ParameterId.CELLPROB_THRESHOLD,
                        p.cellprobThreshold),
                p.useGpu);
    }

    static double doubleParameter(ParameterCombo combo,
                                  ParameterId id,
                                  double fallback) {
        Object value = combo == null ? null : combo.get(id);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            double parsed = Double.parseDouble(String.valueOf(value));
            return Double.isFinite(parsed) ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String stringParameter(ParameterCombo combo,
                                          ParameterId id,
                                          String fallback) {
        Object value = combo == null ? null : combo.get(id);
        String text = value == null ? fallback : String.valueOf(value);
        return text == null || text.trim().isEmpty() ? fallback : text.trim();
    }

    private void closeIfOwned(ImagePlus image,
                              ImagePlus firstOwner,
                              ImagePlus secondOwner) {
        if (image == null || image == firstOwner || image == secondOwner) {
            return;
        }
        previewAdapter.close(image);
    }

    private static boolean isCancelled(BooleanSupplier cancelCheck) {
        return Thread.currentThread().isInterrupted()
                || (cancelCheck != null && cancelCheck.getAsBoolean());
    }
}
