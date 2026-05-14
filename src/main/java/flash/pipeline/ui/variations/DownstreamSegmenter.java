package flash.pipeline.ui.variations;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.objects.ObjectsCounter3DWrapper;
import flash.pipeline.ui.config.CellposeParameterStage;
import flash.pipeline.ui.config.ClassicalSegmentationStage;
import flash.pipeline.ui.config.ConfigQcContext;
import flash.pipeline.ui.config.StarDistParameterStage;
import flash.pipeline.ui.variations.strategy.CellposeOneShot;
import flash.pipeline.ui.variations.strategy.ClassicalSweep;
import flash.pipeline.ui.variations.strategy.StarDistPerCell;

import ij.ImagePlus;
import ij.process.ImageProcessor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

public class DownstreamSegmenter {

    static final String BASELINE_SOURCE_KEY = "baseline:no-filter";

    public interface Counter {
        int count(ImagePlus crop,
                  ParameterCombo upstreamCombo,
                  String filteredSourceKey,
                  VariationCache cache,
                  BooleanSupplier cancelCheck) throws Exception;
    }

    private final ParameterSweep.Method method;
    private final String methodToken;
    private final String strategyCacheTag;
    private final String channelName;
    private final CropSpec cropSpec;
    private final ConfigQcContext configContext;
    private final String upstreamSweepToken;
    private final ParameterSweep upstreamSweep;
    private final ClassicalSegmentationStage.PreviewAdapter classicalPreviewAdapter;
    private final StarDistParameterStage.PreviewAdapter starDistPreviewAdapter;
    private final CellposeParameterStage.PreviewAdapter cellposePreviewAdapter;
    private final String classicalThresholdToken;
    private final String classicalSizeToken;
    private final StarDistParameterStage.Parameters starDistParameters;
    private final CellposeParameterStage.Parameters cellposeParameters;
    private final Counter counter;

    private DownstreamSegmenter(ParameterSweep.Method method,
                                String methodToken,
                                String strategyCacheTag,
                                String channelName,
                                CropSpec cropSpec,
                                ConfigQcContext configContext,
                                String upstreamSweepToken,
                                ParameterSweep upstreamSweep,
                                ClassicalSegmentationStage.PreviewAdapter classicalPreviewAdapter,
                                StarDistParameterStage.PreviewAdapter starDistPreviewAdapter,
                                CellposeParameterStage.PreviewAdapter cellposePreviewAdapter,
                                String classicalThresholdToken,
                                String classicalSizeToken,
                                StarDistParameterStage.Parameters starDistParameters,
                                CellposeParameterStage.Parameters cellposeParameters,
                                Counter counter) {
        this.method = method == null ? ParameterSweep.Method.CLASSICAL : method;
        this.methodToken = safe(methodToken);
        this.strategyCacheTag = safe(strategyCacheTag);
        this.channelName = channelName == null ? "" : channelName;
        this.cropSpec = cropSpec == null ? CropSpec.full() : cropSpec;
        this.configContext = configContext;
        this.upstreamSweepToken = safe(upstreamSweepToken);
        this.upstreamSweep = upstreamSweep;
        this.classicalPreviewAdapter = classicalPreviewAdapter;
        this.starDistPreviewAdapter = starDistPreviewAdapter;
        this.cellposePreviewAdapter = cellposePreviewAdapter;
        this.classicalThresholdToken = safe(classicalThresholdToken);
        this.classicalSizeToken = safe(classicalSizeToken);
        this.starDistParameters = starDistParameters;
        this.cellposeParameters = cellposeParameters;
        this.counter = counter;
    }

    public static DownstreamSegmenter fixedCounter(String methodToken,
                                                   Counter counter) {
        if (counter == null) {
            throw new IllegalArgumentException("counter must not be null");
        }
        return new DownstreamSegmenter(ParameterSweep.Method.CLASSICAL,
                methodToken,
                "FixedCounter",
                "",
                CropSpec.full(),
                null,
                "",
                null,
                null,
                null,
                null,
                "",
                "",
                null,
                null,
                counter);
    }

    public static Resolution resolve(FilterVariationEngineContext context) {
        if (context == null) {
            return Resolution.unavailable("No filter variation context is available.");
        }
        ConfigQcContext configContext = context.configContext();
        if (configContext == null) {
            return Resolution.unavailable("No config context is available.");
        }
        Object config = configContext.getConfig();
        if (!(config instanceof BinConfig)) {
            return Resolution.unavailable(
                    "Downstream verdict requires a BinConfig-backed setup.");
        }
        BinConfig binConfig = (BinConfig) config;
        int channel = configContext.getChannelIndex();
        if (channel < 0 || channel >= binConfig.segmentationMethods.size()) {
            return Resolution.unavailable(
                    "No segmentation method is saved for channel "
                            + (channel + 1) + ".");
        }
        String methodToken = safe(binConfig.segmentationMethods.get(channel));
        if (methodToken.length() == 0) {
            return Resolution.unavailable(
                    "No segmentation method is saved for channel "
                            + (channel + 1) + ".");
        }
        if (binConfig.isStarDist(channel)) {
            if (context.starDistPreviewAdapter() == null) {
                return Resolution.unavailable(
                        "StarDist preview adapter is not available.");
            }
            return Resolution.available(new DownstreamSegmenter(
                    ParameterSweep.Method.STARDIST,
                    methodToken,
                    "StarDistPerCell",
                    context.channelName(),
                    context.initialCropSpec(),
                    configContext,
                    context.cacheNamespace(),
                    null,
                    null,
                    context.starDistPreviewAdapter(),
                    null,
                    "",
                    "",
                    StarDistParameterStage.parseMethod(methodToken),
                    null,
                    null));
        }
        if (binConfig.isCellpose(channel)) {
            if (context.cellposePreviewAdapter() == null) {
                return Resolution.unavailable(
                        "Cellpose preview adapter is not available.");
            }
            int channelCount = Math.max(binConfig.numChannels(),
                    configContext.getChannelNames().size());
            return Resolution.available(new DownstreamSegmenter(
                    ParameterSweep.Method.CELLPOSE,
                    methodToken,
                    "CellposeOneShot",
                    context.channelName(),
                    context.initialCropSpec(),
                    configContext,
                    context.cacheNamespace(),
                    null,
                    null,
                    null,
                    context.cellposePreviewAdapter(),
                    "",
                    "",
                    null,
                    CellposeParameterStage.parseMethod(methodToken,
                            BinConfig.DEFAULT_CELLPOSE_USE_GPU,
                            channelCount,
                            channel),
                    null));
        }
        if (context.classicalPreviewAdapter() == null) {
            return Resolution.unavailable(
                    "Classical preview adapter is not available.");
        }
        return Resolution.available(new DownstreamSegmenter(
                ParameterSweep.Method.CLASSICAL,
                methodToken,
                "ClassicalSweep",
                context.channelName(),
                context.initialCropSpec(),
                configContext,
                context.cacheNamespace(),
                null,
                context.classicalPreviewAdapter(),
                null,
                null,
                tokenAt(binConfig.channelThresholds, channel, "default"),
                tokenAt(binConfig.channelSizes, channel, "100-Infinity"),
                null,
                null,
                null));
    }

    public DownstreamSegmenter forFilterSweep(ParameterSweep sweep) {
        if (sweep == null) {
            return this;
        }
        return new DownstreamSegmenter(method,
                methodToken,
                strategyCacheTag,
                channelName,
                sweep.cropSpec(),
                configContext,
                safe(sweep.cacheNamespace())
                        + ":source=" + safe(sweep.sourceImageHash())
                        + ":crop=" + sweep.cropSpec().toCanonicalJson(),
                sweep,
                classicalPreviewAdapter,
                starDistPreviewAdapter,
                cellposePreviewAdapter,
                classicalThresholdToken,
                classicalSizeToken,
                starDistParameters,
                cellposeParameters,
                counter);
    }

    public int count(ImagePlus crop,
                     ParameterCombo upstreamCombo,
                     String filteredSourceKey,
                     VariationCache cache,
                     BooleanSupplier cancelCheck) throws Exception {
        if (crop == null) {
            throw new IllegalArgumentException("crop must not be null");
        }
        if (isCancelled(cancelCheck)) {
            return 0;
        }
        if (counter != null) {
            return counter.count(crop, upstreamCombo, filteredSourceKey, cache,
                    cancelCheck);
        }

        ParameterCombo downstreamCombo = comboFor(crop);
        ParameterSweep sweep = sweepFor(crop, downstreamCombo, filteredSourceKey);
        final AtomicReference<VariationResult> resultRef =
                new AtomicReference<VariationResult>();
        VariationStrategyForImage strategy = strategyFor(crop, cache);
        strategy.dispatch(sweep, resultRef, cancelCheck);
        VariationResult result = resultRef.get();
        if (result == null) {
            return 0;
        }
        if (result.hasError()) {
            Throwable error = result.error();
            if (error instanceof Exception) {
                throw (Exception) error;
            }
            throw new RuntimeException(error);
        }
        return Math.max(0, result.nObjects());
    }

    String filteredSourceKey(ParameterCombo upstreamCombo) {
        if (upstreamSweep != null && upstreamCombo != null) {
            return VariationCache.keyFor(upstreamSweep, upstreamCombo);
        }
        String base = upstreamSweepToken.length() == 0
                ? "filter"
                : upstreamSweepToken;
        String combo = upstreamCombo == null
                ? "baseline"
                : upstreamCombo.toCanonicalJson();
        return base + ":combo=" + combo;
    }

    String baselineSourceKey() {
        return upstreamSweepToken.length() == 0
                ? BASELINE_SOURCE_KEY
                : upstreamSweepToken + ":baseline";
    }

    ParameterCombo comboForTest(ImagePlus crop) {
        return comboFor(crop);
    }

    public ParameterSweep.Method method() {
        return method;
    }

    public String methodToken() {
        return methodToken;
    }

    public String strategyCacheTag() {
        return strategyCacheTag;
    }

    StarDistParameterStage.Parameters starDistParametersForTest() {
        return starDistParameters;
    }

    CellposeParameterStage.Parameters cellposeParametersForTest() {
        return cellposeParameters;
    }

    private ParameterCombo comboFor(ImagePlus crop) {
        if (method == ParameterSweep.Method.CLASSICAL) {
            SizeToken size = parseSizeToken(classicalSizeToken);
            return ParameterCombo.builder()
                    .put(ParameterId.THRESHOLD, Integer.valueOf(
                            thresholdFor(classicalThresholdToken, crop)))
                    .put(ParameterId.MIN_SIZE, Integer.valueOf(
                            ObjectsCounter3DWrapper.parseMinSizeVoxels(
                                    size.minText, 100)))
                    .put(ParameterId.MAX_SIZE, Integer.valueOf(
                            ObjectsCounter3DWrapper.parseMaxSizeVoxels(
                                    size.maxText, crop)))
                    .build();
        }
        return ParameterCombo.builder().build();
    }

    private ParameterSweep sweepFor(ImagePlus crop,
                                    ParameterCombo combo,
                                    String filteredSourceKey) {
        Map<ParameterKey, ParameterValueList> values =
                new LinkedHashMap<ParameterKey, ParameterValueList>();
        for (Map.Entry<ParameterKey, Object> entry : combo.values().entrySet()) {
            values.put(entry.getKey(),
                    new ParameterValueList(singleton(entry.getValue())));
        }
        String namespace = VariationCache.downstreamCacheNamespace(
                filteredSourceKey,
                methodToken,
                strategyCacheTag,
                cropSpec,
                combo);
        return new ParameterSweep(method,
                values,
                CropSpec.full(),
                channelName,
                FilterVariationEngineContext.sourceImageHash(crop),
                namespace);
    }

    private VariationStrategyForImage strategyFor(ImagePlus crop,
                                                  VariationCache cache) {
        if (method == ParameterSweep.Method.CLASSICAL) {
            return new VariationStrategyForImage(new ClassicalSweep(crop,
                    CropSpec.full(),
                    cache,
                    classicalPreviewAdapter,
                    1));
        }
        if (method == ParameterSweep.Method.STARDIST) {
            return new VariationStrategyForImage(new StarDistPerCell(crop,
                    CropSpec.full(),
                    cache,
                    starDistPreviewAdapter,
                    starDistParameters));
        }
        if (method == ParameterSweep.Method.CELLPOSE) {
            return new VariationStrategyForImage(new CellposeOneShot(crop,
                    CropSpec.full(),
                    cache,
                    cellposePreviewAdapter,
                    cellposeParameters,
                    configContext));
        }
        throw new IllegalStateException("Unsupported downstream method: " + method);
    }

    private static int thresholdFor(String token, ImagePlus crop) {
        String value = safe(token);
        if (value.length() == 0 || "default".equalsIgnoreCase(value)) {
            double auto = defaultDarkThreshold(crop);
            return Double.isFinite(auto) ? nonNegativeInt(auto) : 0;
        }
        try {
            return nonNegativeInt(Double.parseDouble(value));
        } catch (NumberFormatException e) {
            double auto = defaultDarkThreshold(crop);
            return Double.isFinite(auto) ? nonNegativeInt(auto) : 0;
        }
    }

    private static double defaultDarkThreshold(ImagePlus image) {
        if (image == null || image.getProcessor() == null) {
            return Double.NaN;
        }
        ImageProcessor processor = image.getProcessor().duplicate();
        if (processor == null) {
            return Double.NaN;
        }
        try {
            processor.setAutoThreshold("Default dark");
            double threshold = processor.getMinThreshold();
            return Double.isFinite(threshold)
                    && threshold != ImageProcessor.NO_THRESHOLD
                    ? threshold
                    : Double.NaN;
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    private static int nonNegativeInt(double value) {
        if (!Double.isFinite(value)) {
            return 0;
        }
        return Math.max(0, (int) Math.round(value));
    }

    private static SizeToken parseSizeToken(String token) {
        String safeToken = safe(token);
        if (safeToken.length() == 0) {
            safeToken = "100-Infinity";
        }
        int dash = safeToken.indexOf('-');
        if (dash < 0) {
            return new SizeToken(safeToken, "Infinity");
        }
        String min = safeToken.substring(0, dash).trim();
        String max = safeToken.substring(dash + 1).trim();
        return new SizeToken(min.length() == 0 ? "100" : min,
                max.length() == 0 ? "Infinity" : max);
    }

    private static List<Object> singleton(Object value) {
        List<Object> out = new ArrayList<Object>(1);
        out.add(value);
        return out;
    }

    private static String tokenAt(List<String> values, int index, String fallback) {
        if (values == null || index < 0 || index >= values.size()) {
            return fallback;
        }
        String value = values.get(index);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static boolean isCancelled(BooleanSupplier cancelCheck) {
        return cancelCheck != null && cancelCheck.getAsBoolean();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class SizeToken {
        final String minText;
        final String maxText;

        SizeToken(String minText, String maxText) {
            this.minText = minText;
            this.maxText = maxText;
        }
    }

    public static final class Resolution {
        private final DownstreamSegmenter segmenter;
        private final String unavailableReason;

        private Resolution(DownstreamSegmenter segmenter,
                           String unavailableReason) {
            this.segmenter = segmenter;
            this.unavailableReason = unavailableReason == null
                    ? ""
                    : unavailableReason;
        }

        public static Resolution available(DownstreamSegmenter segmenter) {
            if (segmenter == null) {
                throw new IllegalArgumentException("segmenter must not be null");
            }
            return new Resolution(segmenter, "");
        }

        public static Resolution unavailable(String reason) {
            return new Resolution(null, reason);
        }

        public boolean isAvailable() {
            return segmenter != null;
        }

        public DownstreamSegmenter segmenter() {
            return segmenter;
        }

        public String unavailableReason() {
            return unavailableReason;
        }
    }

    private static final class VariationStrategyForImage {
        private final flash.pipeline.ui.variations.VariationStrategy strategy;

        VariationStrategyForImage(
                flash.pipeline.ui.variations.VariationStrategy strategy) {
            this.strategy = strategy;
        }

        void dispatch(ParameterSweep sweep,
                      final AtomicReference<VariationResult> resultRef,
                      BooleanSupplier cancelCheck) throws Exception {
            strategy.dispatch(sweep, new java.util.function.Consumer<VariationResult>() {
                @Override public void accept(VariationResult result) {
                    resultRef.set(result);
                }
            }, cancelCheck);
        }
    }
}
