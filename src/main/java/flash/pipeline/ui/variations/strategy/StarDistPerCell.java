package flash.pipeline.ui.variations.strategy;

import flash.pipeline.stardist.StarDist3DRunner;
import flash.pipeline.ui.config.StarDistParameterStage;
import flash.pipeline.ui.preview.ObjectSizeFilterPreview;
import flash.pipeline.ui.variations.CropSpec;
import flash.pipeline.ui.variations.MacroPreprocessor;
import flash.pipeline.ui.variations.ParameterCombo;
import flash.pipeline.ui.variations.ParameterId;
import flash.pipeline.ui.variations.ParameterSweep;
import flash.pipeline.ui.variations.VariationCache;
import flash.pipeline.ui.variations.VariationResult;
import flash.pipeline.ui.variations.VariationStrategy;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.ResultsTable;
import ij.process.ShortProcessor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class StarDistPerCell implements VariationStrategy {

    private final ImagePlus filteredSource;
    private final CropSpec crop;
    private final VariationCache cache;
    private final StarDistParameterStage.PreviewAdapter previewAdapter;
    private final StarDistParameterStage.Parameters baseParams;
    private final MacroPreprocessor macroPreprocessor = new MacroPreprocessor();

    public StarDistPerCell(ImagePlus filteredSource,
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

    @Override
    public void dispatch(ParameterSweep sweep,
                         Consumer<VariationResult> publisher,
                         BooleanSupplier cancelCheck) {
        if (sweep == null) {
            throw new IllegalArgumentException("sweep must not be null");
        }
        if (sweep.method() != ParameterSweep.Method.STARDIST) {
            throw new IllegalArgumentException("StarDistPerCell only accepts StarDist sweeps");
        }
        if (publisher == null) {
            throw new IllegalArgumentException("publisher must not be null");
        }

        CropSpec activeCrop = sweep.cropSpec() == null ? crop : sweep.cropSpec();
        ImagePlus cropped = activeCrop.apply(filteredSource);
        try {
            List<ParameterCombo> ordered = SweepDispatchOrder.order(sweep);
            for (int i = 0; i < ordered.size(); i++) {
                if (isCancelled(cancelCheck)) {
                    return;
                }
                ParameterCombo combo = ordered.get(i);
                StarDistParameterStage.Parameters parameters = overlay(baseParams, combo);
                String cacheKey = VariationCache.keyFor(sweep, combo);
                ImagePlus cached = cache == null ? null : cache.get(cacheKey);
                if (cached != null) {
                    publisher.accept(resultFor(combo, cached, cropped, parameters, 0L));
                    continue;
                }
                runOne(cropped, sweep, combo, cacheKey, parameters, publisher, cancelCheck);
            }
        } finally {
            closeCroppedIfOwned(cropped);
        }
    }

    private void runOne(ImagePlus cropped,
                        ParameterSweep sweep,
                        ParameterCombo combo,
                        String cacheKey,
                        StarDistParameterStage.Parameters parameters,
                        Consumer<VariationResult> publisher,
                        BooleanSupplier cancelCheck) {
        if (isCancelled(cancelCheck)) {
            return;
        }
        long started = System.currentTimeMillis();
        ImagePlus input = null;
        try {
            input = macroPreprocessor.prepare(cropped, sweep, combo);
            ImagePlus label = previewAdapter.runPreview(input,
                    previewRunParameters(parameters));
            if (label == null) {
                label = emptyLabelMapLike(input);
            }
            long durationMs = Math.max(1L, System.currentTimeMillis() - started);
            VariationResult result = resultFor(combo, label, input, parameters, durationMs);
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
                                      StarDistParameterStage.Parameters parameters,
                                      long durationMs) {
        ResultsTable stats = objectStatsForLabelPreview(label, reference);
        if (label != null) {
            label.setProperty(StarDist3DRunner.OBJECT_STATS_PROPERTY, stats);
        }
        int count = applyPostFilters(label, stats, reference, parameters);
        return VariationResult.success(combo, label, count, durationMs, stats);
    }

    private int applyPostFilters(ImagePlus label,
                                 ResultsTable stats,
                                 ImagePlus reference,
                                 StarDistParameterStage.Parameters parameters) {
        int fallbackCount = label == null ? 0 : previewAdapter.countLabels(label);
        Map<Integer, ObjectSizeFilterPreview.Classification> classes =
                starDistFilterClassifications(stats, parameters);
        if (classes == null) {
            return fallbackCount;
        }
        ObjectSizeFilterPreview.Summary allObjectsSummary =
                ObjectSizeFilterPreview.summarize(stats, reference, 0, 0, false);
        ObjectSizeFilterPreview.applyClassifiedLut(label, allObjectsSummary, classes);
        int total = stats == null ? fallbackCount : stats.size();
        return Math.max(0, total - classes.size());
    }

    private void closeCroppedIfOwned(ImagePlus cropped) {
        if (cropped == null || cropped == filteredSource) {
            return;
        }
        previewAdapter.close(cropped);
    }

    private static StarDistParameterStage.Parameters overlay(
            StarDistParameterStage.Parameters base,
            ParameterCombo combo) {
        StarDistParameterStage.Parameters p =
                base == null ? StarDistParameterStage.parseMethod(null) : base;
        return new StarDistParameterStage.Parameters(
                doubleParameter(combo, ParameterId.PROB_THRESH, p.probabilityThreshold),
                doubleParameter(combo, ParameterId.NMS_THRESH, p.nmsThreshold),
                doubleParameter(combo, ParameterId.LINKING_MAX, p.linkingMaxDistance),
                doubleParameter(combo, ParameterId.GAP_CLOSING_MAX, p.gapClosingMaxDistance),
                intParameter(combo, ParameterId.FRAME_GAP, p.maxFrameGap),
                doubleParameter(combo, ParameterId.AREA_MIN, p.areaMin),
                doubleParameter(combo, ParameterId.AREA_MAX, p.areaMax),
                doubleParameter(combo, ParameterId.QUALITY_MIN, p.qualityMin),
                doubleParameter(combo, ParameterId.INTENSITY_MIN, p.intensityMin),
                p.modelKey);
    }

    private static StarDistParameterStage.Parameters previewRunParameters(
            StarDistParameterStage.Parameters parameters) {
        StarDistParameterStage.Parameters p =
                parameters == null ? StarDistParameterStage.parseMethod(null) : parameters;
        return new StarDistParameterStage.Parameters(
                p.probabilityThreshold,
                p.nmsThreshold,
                p.linkingMaxDistance,
                p.gapClosingMaxDistance,
                p.maxFrameGap,
                0,
                Double.POSITIVE_INFINITY,
                0,
                0,
                p.modelKey);
    }

    private static ResultsTable objectStatsForLabelPreview(ImagePlus labelImage,
                                                           ImagePlus reference) {
        ResultsTable stats = ObjectSizeFilterPreview.statisticsFromLabelMap(labelImage, reference);
        Object property = labelImage == null ? null
                : labelImage.getProperty(StarDist3DRunner.OBJECT_STATS_PROPERTY);
        if (!(property instanceof ResultsTable)) {
            return stats;
        }
        ResultsTable starDistStats = (ResultsTable) property;
        copyStarDistMetricColumn(starDistStats, stats, StarDist3DRunner.STATS_AREA_MEAN);
        copyStarDistMetricColumn(starDistStats, stats, StarDist3DRunner.STATS_QUALITY_MEAN);
        copyStarDistMetricColumn(starDistStats, stats, StarDist3DRunner.STATS_INTENSITY_MEAN);
        return stats;
    }

    private static void copyStarDistMetricColumn(ResultsTable source,
                                                 ResultsTable target,
                                                 String column) {
        if (!hasColumn(source, column) || source == null || target == null) {
            return;
        }
        Map<Integer, Integer> targetRows = rowsByLabel(target);
        for (int row = 0; row < source.size(); row++) {
            int label = labelForStatsRow(source, row);
            Integer targetRow = targetRows.get(Integer.valueOf(label));
            if (targetRow == null) {
                continue;
            }
            double value = metric(source, column, row);
            if (Double.isFinite(value)) {
                target.setValue(column, targetRow.intValue(), value);
            }
        }
    }

    private static Map<Integer, ObjectSizeFilterPreview.Classification>
    starDistFilterClassifications(ResultsTable stats,
                                  StarDistParameterStage.Parameters parameters) {
        Map<Integer, ObjectSizeFilterPreview.Classification> classes =
                new HashMap<Integer, ObjectSizeFilterPreview.Classification>();
        if (stats == null || stats.size() == 0 || parameters == null) {
            return classes;
        }
        boolean areaActive = parameters.areaMin > 0 || Double.isFinite(parameters.areaMax);
        boolean qualityActive = parameters.qualityMin > 0;
        boolean intensityActive = parameters.intensityMin > 0;
        if (!areaActive && !qualityActive && !intensityActive) {
            return classes;
        }
        if ((areaActive && !hasColumn(stats, StarDist3DRunner.STATS_AREA_MEAN))
                || (qualityActive && !hasColumn(stats, StarDist3DRunner.STATS_QUALITY_MEAN))
                || (intensityActive && !hasColumn(stats, StarDist3DRunner.STATS_INTENSITY_MEAN))) {
            return null;
        }
        for (int row = 0; row < stats.size(); row++) {
            int label = labelForStatsRow(stats, row);
            ObjectSizeFilterPreview.Classification classification =
                    ObjectSizeFilterPreview.Classification.KEPT;
            double area = metric(stats, StarDist3DRunner.STATS_AREA_MEAN, row);
            if (Double.isFinite(area)) {
                if (parameters.areaMin > 0 && area < parameters.areaMin) {
                    classification = ObjectSizeFilterPreview.Classification.BELOW_MIN;
                } else if (Double.isFinite(parameters.areaMax) && area > parameters.areaMax) {
                    classification = ObjectSizeFilterPreview.Classification.ABOVE_MAX;
                }
            }
            double quality = metric(stats, StarDist3DRunner.STATS_QUALITY_MEAN, row);
            if (classification == ObjectSizeFilterPreview.Classification.KEPT
                    && parameters.qualityMin > 0
                    && Double.isFinite(quality)
                    && quality < parameters.qualityMin) {
                classification = ObjectSizeFilterPreview.Classification.BELOW_MIN;
            }
            double intensity = metric(stats, StarDist3DRunner.STATS_INTENSITY_MEAN, row);
            if (classification == ObjectSizeFilterPreview.Classification.KEPT
                    && parameters.intensityMin > 0
                    && Double.isFinite(intensity)
                    && intensity < parameters.intensityMin) {
                classification = ObjectSizeFilterPreview.Classification.BELOW_MIN;
            }
            if (classification != ObjectSizeFilterPreview.Classification.KEPT) {
                classes.put(Integer.valueOf(label), classification);
            }
        }
        return classes;
    }

    private static Map<Integer, Integer> rowsByLabel(ResultsTable stats) {
        Map<Integer, Integer> rows = new HashMap<Integer, Integer>();
        if (stats == null) {
            return rows;
        }
        for (int row = 0; row < stats.size(); row++) {
            rows.put(Integer.valueOf(labelForStatsRow(stats, row)), Integer.valueOf(row));
        }
        return rows;
    }

    private static int labelForStatsRow(ResultsTable stats, int row) {
        try {
            double label = stats.getValue("Label", row);
            if (Double.isFinite(label) && label > 0) {
                return (int) Math.round(label);
            }
        } catch (RuntimeException ignored) {
            // Fall through to row order.
        }
        return row + 1;
    }

    private static boolean hasColumn(ResultsTable stats, String column) {
        if (stats == null || column == null) {
            return false;
        }
        String[] headings = stats.getHeadings();
        if (headings == null) {
            return false;
        }
        for (int i = 0; i < headings.length; i++) {
            if (column.equals(headings[i])) {
                return true;
            }
        }
        return false;
    }

    private static double metric(ResultsTable stats, String column, int row) {
        try {
            double value = stats.getValue(column, row);
            return Double.isFinite(value) ? value : Double.NaN;
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    private static double doubleParameter(ParameterCombo combo,
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

    private static int intParameter(ParameterCombo combo, ParameterId id, int fallback) {
        Object value = combo == null ? null : combo.get(id);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number) {
            return Math.max(0, (int) Math.round(((Number) value).doubleValue()));
        }
        try {
            return Math.max(0, (int) Math.round(Double.parseDouble(String.valueOf(value))));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean isCancelled(BooleanSupplier cancelCheck) {
        return Thread.currentThread().isInterrupted()
                || (cancelCheck != null && cancelCheck.getAsBoolean());
    }

    private static ImagePlus emptyLabelMapLike(ImagePlus reference) {
        int width = reference == null ? 1 : Math.max(1, reference.getWidth());
        int height = reference == null ? 1 : Math.max(1, reference.getHeight());
        int stackSize = reference == null ? 1 : Math.max(1, reference.getStackSize());
        ImageStack stack = new ImageStack(width, height);
        for (int i = 0; i < stackSize; i++) {
            stack.addSlice("z" + (i + 1), new ShortProcessor(width, height));
        }
        ImagePlus label = new ImagePlus("StarDist label preview (no objects)", stack);
        if (reference != null && reference.getCalibration() != null) {
            label.setCalibration(reference.getCalibration().copy());
        }
        if (reference != null) {
            int channels = Math.max(1, reference.getNChannels());
            int slices = Math.max(1, reference.getNSlices());
            int frames = Math.max(1, reference.getNFrames());
            if (channels * slices * frames == stackSize) {
                label.setDimensions(channels, slices, frames);
                label.setOpenAsHyperStack(reference.isHyperStack());
            }
        }
        return label;
    }
}
