package flash.pipeline.ui.variations.strategy;

import flash.pipeline.image.FilterMacroEditorModel;
import flash.pipeline.image.FilterMacroParser.OpType;
import flash.pipeline.image.dag.DagToIjmEmitter;
import flash.pipeline.ui.config.FilterParameterStage;
import flash.pipeline.ui.variations.CanonicalScale;
import flash.pipeline.ui.variations.FilterParameterId;
import flash.pipeline.ui.variations.FilterVariationEngineContext;
import flash.pipeline.ui.variations.ParameterCombo;
import flash.pipeline.ui.variations.ParameterKey;
import flash.pipeline.ui.variations.ParameterSweep;
import flash.pipeline.ui.variations.PresetSweepCombo;
import flash.pipeline.ui.variations.SlotSubstitutionCombo;
import flash.pipeline.ui.variations.VariationCache;
import flash.pipeline.ui.variations.VariationResult;
import flash.pipeline.ui.variations.VariationStrategy;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.AutoThresholder;
import ij.process.ImageProcessor;

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class FilterSweepStrategy implements VariationStrategy {

    private static final int HISTOGRAM_BINS = 256;

    public interface MacroPostProcessor {
        String apply(String macroContent);
    }

    private final FilterMacroEditorModel.MacroDefinition baseMacro;
    private final FilterParameterStage.PreviewAdapter previewAdapter;
    private final ImagePlus croppedSource;
    private final VariationCache cache;
    private final MacroPostProcessor macroPostProcessor;
    private final FilterVariationEngineContext.PresetMacroLoader presetMacroLoader;

    public FilterSweepStrategy(FilterMacroEditorModel.MacroDefinition baseMacro,
                               FilterParameterStage.PreviewAdapter previewAdapter,
                               ImagePlus croppedSource,
                               VariationCache cache) {
        this(baseMacro, previewAdapter, croppedSource, cache, null);
    }

    public FilterSweepStrategy(FilterMacroEditorModel.MacroDefinition baseMacro,
                               FilterParameterStage.PreviewAdapter previewAdapter,
                               ImagePlus croppedSource,
                               VariationCache cache,
                               MacroPostProcessor macroPostProcessor) {
        this(baseMacro, previewAdapter, croppedSource, cache, macroPostProcessor,
                null);
    }

    public FilterSweepStrategy(FilterMacroEditorModel.MacroDefinition baseMacro,
                               FilterParameterStage.PreviewAdapter previewAdapter,
                               ImagePlus croppedSource,
                               VariationCache cache,
                               MacroPostProcessor macroPostProcessor,
                               FilterVariationEngineContext.PresetMacroLoader presetMacroLoader) {
        if (baseMacro == null) {
            throw new IllegalArgumentException("baseMacro must not be null");
        }
        if (previewAdapter == null) {
            throw new IllegalArgumentException("previewAdapter must not be null");
        }
        if (croppedSource == null) {
            throw new IllegalArgumentException("croppedSource must not be null");
        }
        this.baseMacro = baseMacro;
        this.previewAdapter = previewAdapter;
        this.croppedSource = croppedSource;
        this.cache = cache;
        this.macroPostProcessor = macroPostProcessor;
        this.presetMacroLoader = presetMacroLoader;
    }

    @Override
    public void dispatch(ParameterSweep sweep,
                         Consumer<VariationResult> publisher,
                         BooleanSupplier cancelCheck) {
        if (sweep == null) {
            throw new IllegalArgumentException("sweep must not be null");
        }
        if (sweep.method() != ParameterSweep.Method.FILTER) {
            throw new IllegalArgumentException("FilterSweepStrategy only accepts Filter sweeps");
        }
        if (publisher == null) {
            throw new IllegalArgumentException("publisher must not be null");
        }

        List<ParameterCombo> ordered = SweepDispatchOrder.order(sweep);
        for (int i = 0; i < ordered.size(); i++) {
            if (isCancelled(cancelCheck)) {
                return;
            }
            ParameterCombo combo = ordered.get(i);
            String cacheKey = VariationCache.keyFor(sweep, combo);
            ImagePlus cached = cache == null ? null : cache.get(cacheKey);
            if (cached != null) {
                publishCached(combo, cached, publisher, cancelCheck);
                continue;
            }
            runOne(sweep, combo, cacheKey, publisher, cancelCheck);
            if (isCancelled(cancelCheck)) {
                return;
            }
        }
    }

    public String renderMacroForCombo(ParameterCombo combo) {
        PresetSweepCombo presetCombo = PresetSweepCombo.from(combo);
        if (presetCombo != null) {
            return renderMacroForPresetCombo(presetCombo);
        }
        SlotSubstitutionCombo substitution = SlotSubstitutionCombo.from(combo);
        if (substitution != null) {
            return renderMacroForSubstitution(substitution);
        }
        FilterMacroEditorModel.MacroDefinition macro =
                FilterMacroEditorModel.parse(baseMacro.render());
        for (Map.Entry<ParameterKey, Object> entry : combo.values().entrySet()) {
            if (!(entry.getKey() instanceof FilterParameterId)) {
                throw new IllegalArgumentException(
                        "Filter sweeps require FilterParameterId keys: "
                                + entry.getKey().stableKey());
            }
            FilterParameterId id = (FilterParameterId) entry.getKey();
            setParameterValue(macro, id, entry.getValue());
        }
        String rendered = macro.render();
        return macroPostProcessor == null
                ? rendered
                : macroPostProcessor.apply(rendered);
    }

    private String renderMacroForPresetCombo(PresetSweepCombo combo) {
        if (presetMacroLoader == null) {
            throw new IllegalStateException("No preset loader available.");
        }
        String presetMacro;
        try {
            presetMacro = presetMacroLoader.loadPresetMacro(combo.presetName());
        } catch (Exception e) {
            throw new IllegalStateException("Could not load preset "
                    + combo.presetName() + ": " + e.getMessage(), e);
        }
        if (presetMacro == null || presetMacro.trim().isEmpty()) {
            throw new PresetSweepCombo.IncompatiblePresetException(
                    "Preset " + combo.presetName() + " has no macro content.");
        }
        FilterMacroEditorModel.MacroDefinition macro =
                FilterMacroEditorModel.parse(presetMacro);
        if (!setFirstMatchingParameterValue(macro, combo.xParamKey(),
                combo.xValue())) {
            throw new PresetSweepCombo.IncompatiblePresetException(
                    "Preset " + combo.presetName() + " has no "
                            + combo.xParamKey() + " parameter.");
        }
        String rendered = macro.render();
        return macroPostProcessor == null
                ? rendered
                : macroPostProcessor.apply(rendered);
    }

    private String renderMacroForSubstitution(SlotSubstitutionCombo combo) {
        String rendered = replaceStep(baseMacro.render(),
                combo.stepIndex(),
                combo.candidateType(),
                combo.scaleValue());
        return macroPostProcessor == null
                ? rendered
                : macroPostProcessor.apply(rendered);
    }

    private void publishCached(ParameterCombo combo,
                               ImagePlus cached,
                               Consumer<VariationResult> publisher,
                               BooleanSupplier cancelCheck) {
        if (isCancelled(cancelCheck)) {
            return;
        }
        Metrics metrics = metricsFor(cached);
        if (!isCancelled(cancelCheck)) {
            publisher.accept(VariationResult.filterSuccess(combo, cached, 0L,
                    metrics.histogram, metrics.snr, metrics.bgSigma));
        }
    }

    private void runOne(ParameterSweep sweep,
                        ParameterCombo combo,
                        String cacheKey,
                        Consumer<VariationResult> publisher,
                        BooleanSupplier cancelCheck) {
        ImagePlus perComboSource = null;
        ImagePlus filtered = null;
        long started = System.currentTimeMillis();
        try {
            if (isCancelled(cancelCheck)) {
                return;
            }
            perComboSource = copyImage(croppedSource);
            String macro = renderMacroForCombo(combo);
            filtered = previewAdapter.createFilteredPreview(perComboSource, macro);
            if (filtered == null) {
                throw new IllegalStateException("Filter preview returned no image.");
            }
            if (isCancelled(cancelCheck)) {
                closeIfOwned(filtered, perComboSource);
                return;
            }
            Metrics metrics = metricsFor(filtered);
            long durationMs = Math.max(1L, System.currentTimeMillis() - started);
            if (cache != null) {
                cache.put(cacheKey, filtered);
            }
            if (!isCancelled(cancelCheck)) {
                publisher.accept(VariationResult.filterSuccess(combo, filtered,
                        durationMs, metrics.histogram, metrics.snr, metrics.bgSigma));
            }
        } catch (Throwable t) {
            if (!isCancelled(cancelCheck)) {
                publisher.accept(VariationResult.failure(combo, t));
            }
        } finally {
            if (perComboSource != null && perComboSource != filtered) {
                closeQuietly(perComboSource);
            }
        }
    }

    private static void setParameterValue(FilterMacroEditorModel.MacroDefinition macro,
                                          FilterParameterId id,
                                          Object value) {
        List<FilterMacroEditorModel.Section> sections = macro.getSections();
        if (id.sectionIndex() >= sections.size()) {
            throw new IllegalArgumentException("Filter section index is out of bounds: "
                    + id.sectionIndex());
        }
        FilterMacroEditorModel.Section section = sections.get(id.sectionIndex());
        if (id.entryIndex() >= section.entries.size()) {
            throw new IllegalArgumentException("Filter entry index is out of bounds: "
                    + id.entryIndex());
        }
        FilterMacroEditorModel.Entry entry = section.entries.get(id.entryIndex());
        if (id.parameterIndex() >= entry.parameters.size()) {
            throw new IllegalArgumentException("Filter parameter index is out of bounds: "
                    + id.parameterIndex());
        }
        FilterMacroEditorModel.Parameter parameter =
                entry.parameters.get(id.parameterIndex());
        parameter.setValue(String.valueOf(value));
    }

    private static boolean setFirstMatchingParameterValue(
            FilterMacroEditorModel.MacroDefinition macro,
            String paramKey,
            Object value) {
        String target = paramKey == null ? "" : paramKey.trim();
        if (macro == null || target.isEmpty()) {
            return false;
        }
        List<FilterMacroEditorModel.Section> sections = macro.getSections();
        for (int i = 0; i < sections.size(); i++) {
            FilterMacroEditorModel.Section section = sections.get(i);
            for (int j = 0; j < section.entries.size(); j++) {
                FilterMacroEditorModel.Entry entry = section.entries.get(j);
                for (int k = 0; k < entry.parameters.size(); k++) {
                    FilterMacroEditorModel.Parameter parameter =
                            entry.parameters.get(k);
                    if (parameter != null
                            && target.equalsIgnoreCase(parameter.key)
                            && isFiniteDouble(parameter.getValue())) {
                        parameter.setValue(String.valueOf(value));
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isFiniteDouble(String value) {
        try {
            double parsed = Double.parseDouble(value == null ? "" : value.trim());
            return !Double.isNaN(parsed) && !Double.isInfinite(parsed);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private String replaceStep(String macroContent,
                               int stepIndex,
                               OpType candidateType,
                               CanonicalScale.ScaleValue scaleValue) {
        int lineIndex = lineIndexForStep(baseMacro, stepIndex);
        String normalized = macroContent == null ? "" : macroContent
                .replace("\r\n", "\n")
                .replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        if (lineIndex < 0 || lineIndex >= lines.length) {
            throw new IllegalArgumentException("Filter step line is out of bounds: "
                    + stepIndex);
        }
        lines[lineIndex] = renderRunLine(candidateType, scaleValue);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            out.append(lines[i]);
        }
        return out.toString();
    }

    private static int lineIndexForStep(FilterMacroEditorModel.MacroDefinition macro,
                                        int stepIndex) {
        if (macro == null) {
            throw new IllegalArgumentException("baseMacro must not be null");
        }
        int current = 0;
        List<FilterMacroEditorModel.Section> sections = macro.getSections();
        for (int i = 0; i < sections.size(); i++) {
            FilterMacroEditorModel.Section section = sections.get(i);
            for (int j = 0; j < section.entries.size(); j++) {
                FilterMacroEditorModel.Entry entry = section.entries.get(j);
                if (current == stepIndex) {
                    return entry.lineIndex;
                }
                current++;
            }
        }
        throw new IllegalArgumentException("Filter step index is out of bounds: "
                + stepIndex);
    }

    private static String renderRunLine(OpType type,
                                        CanonicalScale.ScaleValue scaleValue) {
        String command = DagToIjmEmitter.commandFor(type);
        if (command == null || command.trim().isEmpty()) {
            throw new IllegalArgumentException("Unsupported substitution filter: "
                    + type);
        }
        String args = argsFor(type, scaleValue);
        if (args.trim().isEmpty()) {
            return "run(\"" + escape(command) + "\");";
        }
        return "run(\"" + escape(command) + "...\", \"" + escape(args) + "\");";
    }

    private static String argsFor(OpType type,
                                  CanonicalScale.ScaleValue scaleValue) {
        CanonicalScale.ScaleValue safeValue = scaleValue == null
                ? CanonicalScale.ScaleValue.none()
                : scaleValue;
        if (safeValue.isNone()) {
            return "";
        }
        if (type == OpType.AUTO_LOCAL_THRESHOLD) {
            return "method=Bernsen radius="
                    + CanonicalScale.format(safeValue.value())
                    + " parameter_1=0 parameter_2=0 white stack";
        }
        return safeValue.paramKey()
                + "="
                + CanonicalScale.format(safeValue.value())
                + " stack";
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private ImagePlus copyImage(ImagePlus source) {
        int width = Math.max(1, source.getWidth());
        int height = Math.max(1, source.getHeight());
        ImageStack stack = source.getStack();
        int size = stack == null ? 1 : Math.max(1, stack.getSize());
        ImageStack copyStack = new ImageStack(width, height);
        for (int slice = 1; slice <= size; slice++) {
            ImageProcessor processor = stack == null
                    ? source.getProcessor()
                    : stack.getProcessor(slice);
            processor.setRoi(0, 0, width, height);
            ImageProcessor copy = processor.crop();
            processor.resetRoi();
            String label = stack == null ? null : stack.getSliceLabel(slice);
            copyStack.addSlice(label, copy);
        }
        ImagePlus copyImage = new ImagePlus(source.getTitle(), copyStack);
        if (source.getCalibration() != null) {
            copyImage.setCalibration(source.getCalibration().copy());
        }
        int channels = Math.max(1, source.getNChannels());
        int slices = Math.max(1, source.getNSlices());
        int frames = Math.max(1, source.getNFrames());
        if (channels * slices * frames == copyStack.getSize()) {
            copyImage.setDimensions(channels, slices, frames);
            copyImage.setOpenAsHyperStack(source.isHyperStack());
        }
        return copyImage;
    }

    private void closeIfOwned(ImagePlus image, ImagePlus perComboSource) {
        if (image != null && image != perComboSource) {
            closeQuietly(image);
        }
    }

    private void closeQuietly(ImagePlus image) {
        try {
            previewAdapter.close(image);
        } catch (RuntimeException ignored) {
            // Disposable preview images should not turn cancellation into a failure.
        }
    }

    private static Metrics metricsFor(ImagePlus image) {
        PixelRange range = pixelRange(image);
        int[] histogram = new int[HISTOGRAM_BINS];
        if (!range.hasValues) {
            return new Metrics(histogram, 0.0d, 0.0d);
        }
        fillHistogram(image, range, histogram);
        int thresholdBin = new AutoThresholder().getThreshold(
                AutoThresholder.Method.Otsu, histogram);
        RunningStats foreground = new RunningStats();
        RunningStats background = new RunningStats();
        accumulateStats(image, range, thresholdBin, foreground, background);
        double bgSigma = background.standardDeviation();
        double snr = bgSigma <= 0.0d ? 0.0d : foreground.mean() / bgSigma;
        return new Metrics(histogram, snr, bgSigma);
    }

    private static PixelRange pixelRange(ImagePlus image) {
        if (image == null) {
            return new PixelRange(false, 0.0d, 0.0d);
        }
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        ImageStack stack = image.getStack();
        int size = stack == null ? 1 : Math.max(1, stack.getSize());
        for (int slice = 1; slice <= size; slice++) {
            ImageProcessor processor = stack == null
                    ? image.getProcessor()
                    : stack.getProcessor(slice);
            if (processor == null) {
                continue;
            }
            for (int i = 0; i < processor.getPixelCount(); i++) {
                double value = processor.getf(i);
                if (!Double.isFinite(value)) {
                    continue;
                }
                if (value < min) min = value;
                if (value > max) max = value;
            }
        }
        if (!Double.isFinite(min) || !Double.isFinite(max)) {
            return new PixelRange(false, 0.0d, 0.0d);
        }
        return new PixelRange(true, min, max);
    }

    private static void fillHistogram(ImagePlus image,
                                      PixelRange range,
                                      int[] histogram) {
        ImageStack stack = image.getStack();
        int size = stack == null ? 1 : Math.max(1, stack.getSize());
        for (int slice = 1; slice <= size; slice++) {
            ImageProcessor processor = stack == null
                    ? image.getProcessor()
                    : stack.getProcessor(slice);
            if (processor == null) {
                continue;
            }
            for (int i = 0; i < processor.getPixelCount(); i++) {
                double value = processor.getf(i);
                if (Double.isFinite(value)) {
                    histogram[binFor(value, range)]++;
                }
            }
        }
    }

    private static void accumulateStats(ImagePlus image,
                                        PixelRange range,
                                        int thresholdBin,
                                        RunningStats foreground,
                                        RunningStats background) {
        ImageStack stack = image.getStack();
        int size = stack == null ? 1 : Math.max(1, stack.getSize());
        for (int slice = 1; slice <= size; slice++) {
            ImageProcessor processor = stack == null
                    ? image.getProcessor()
                    : stack.getProcessor(slice);
            if (processor == null) {
                continue;
            }
            for (int i = 0; i < processor.getPixelCount(); i++) {
                double value = processor.getf(i);
                if (!Double.isFinite(value)) {
                    continue;
                }
                if (binFor(value, range) > thresholdBin) {
                    foreground.add(value);
                } else {
                    background.add(value);
                }
            }
        }
    }

    private static int binFor(double value, PixelRange range) {
        if (range.max <= range.min) {
            return 0;
        }
        int bin = (int) Math.floor(((value - range.min) / (range.max - range.min))
                * (HISTOGRAM_BINS - 1));
        if (bin < 0) return 0;
        if (bin >= HISTOGRAM_BINS) return HISTOGRAM_BINS - 1;
        return bin;
    }

    private static boolean isCancelled(BooleanSupplier cancelCheck) {
        return cancelCheck != null && cancelCheck.getAsBoolean();
    }

    private static final class Metrics {
        final int[] histogram;
        final double snr;
        final double bgSigma;

        Metrics(int[] histogram, double snr, double bgSigma) {
            this.histogram = histogram;
            this.snr = snr;
            this.bgSigma = bgSigma;
        }
    }

    private static final class PixelRange {
        final boolean hasValues;
        final double min;
        final double max;

        PixelRange(boolean hasValues, double min, double max) {
            this.hasValues = hasValues;
            this.min = min;
            this.max = max;
        }
    }

    private static final class RunningStats {
        private int count;
        private double mean;
        private double m2;

        void add(double value) {
            count++;
            double delta = value - mean;
            mean += delta / count;
            double delta2 = value - mean;
            m2 += delta * delta2;
        }

        double mean() {
            return count == 0 ? 0.0d : mean;
        }

        double standardDeviation() {
            return count == 0 ? 0.0d : Math.sqrt(m2 / count);
        }
    }
}
