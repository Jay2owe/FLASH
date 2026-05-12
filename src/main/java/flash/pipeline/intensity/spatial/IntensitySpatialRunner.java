package flash.pipeline.intensity.spatial;

import flash.pipeline.analyses.wizard.IntensitySpatialConfig;
import flash.pipeline.runtime.DependencyId;
import flash.pipeline.runtime.FeatureDependencyGate;
import ij.IJ;
import ij.ImagePlus;
import ij.measure.Calibration;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs selected same-channel intensity-spatial analysis families.
 */
public final class IntensitySpatialRunner {
    private final List<IntensitySpatialAnalysis> analyses;

    public IntensitySpatialRunner(List<IntensitySpatialAnalysis> analyses) {
        this.analyses = analyses == null
                ? Collections.<IntensitySpatialAnalysis>emptyList()
                : Collections.unmodifiableList(new ArrayList<IntensitySpatialAnalysis>(analyses));
    }

    public static IntensitySpatialRunner standard() {
        return new IntensitySpatialRunner(Arrays.<IntensitySpatialAnalysis>asList(
                new PatchinessAnalysis(),
                new HotspotScanAnalysis(),
                new NullModelAnalysis(),
                new GranularityAnalysis(),
                new DepthProfileAnalysis(),
                new Anisotropy2DAnalysis()));
    }

    public IntensitySpatialResult measure(IntensitySpatialContext context) {
        if (context == null || !context.config().isEnabled()
                || context.config().getEnabledAnalyses().isEmpty()) {
            return IntensitySpatialResult.empty();
        }

        LinkedHashMap<String, Double> values = new LinkedHashMap<String, Double>();
        for (IntensitySpatialAnalysis analysis : analyses) {
            if (analysis == null || !context.config().getEnabledAnalyses().contains(analysis.key())
                    || !analysis.outputModes().contains(context.outputMode())) {
                continue;
            }

            IntensitySpatialContext effectiveContext = analysis.validity()
                    == IntensitySpatialAnalysis.AnalysisValidity.EITHER_VALID
                    ? context
                    : context.withoutBinarizedImage();
            boolean binarizedPartner = effectiveContext.hasBinarizedImage();
            if (!dependenciesAvailable(analysis, effectiveContext, binarizedPartner, values)) {
                continue;
            }

            try {
                IntensitySpatialResult result = analysis.measure(effectiveContext);
                if (result != null) {
                    values.putAll(result.values());
                }
            } catch (Exception ex) {
                IJ.log("[FLASH] Intensity-spatial " + analysis.key().token()
                        + " skipped for " + context.imageId()
                        + " channel " + context.channelName()
                        + ": " + safeMessage(ex));
                values.putAll(IntensitySpatialResult
                        .nanFor(analysis.columns(context.config(), binarizedPartner))
                        .values());
            }
        }
        return new IntensitySpatialResult(values);
    }

    private boolean dependenciesAvailable(IntensitySpatialAnalysis analysis,
                                          IntensitySpatialContext context,
                                          boolean binarizedPartner,
                                          Map<String, Double> values) {
        for (DependencyId id : analysis.dependencyIds()) {
            if (!FeatureDependencyGate.isAvailable(id)) {
                IJ.log("[FLASH] Intensity-spatial " + analysis.key().token()
                        + " skipped for " + context.imageId()
                        + " channel " + context.channelName()
                        + ": missing dependency " + id.name());
                values.putAll(IntensitySpatialResult
                        .nanFor(analysis.columns(context.config(), binarizedPartner))
                        .values());
                return false;
            }
        }
        return true;
    }

    public static ImagePlus maxIntensityProjection(ImagePlus source, String title) {
        if (source == null) return null;
        int width = source.getWidth();
        int height = source.getHeight();
        int slices = Math.max(1, source.getStackSize());
        float[] out = new float[width * height];
        ImageProcessor first = source.getStack().getProcessor(1);
        for (int p = 0; p < out.length; p++) {
            out[p] = first.getf(p);
        }
        for (int s = 2; s <= slices; s++) {
            ImageProcessor ip = source.getStack().getProcessor(s);
            for (int p = 0; p < out.length; p++) {
                float value = ip.getf(p);
                if (value > out[p]) out[p] = value;
            }
        }
        ImagePlus mip = new ImagePlus(title == null ? source.getTitle() + "_MIP" : title,
                new FloatProcessor(width, height, out, null));
        Calibration cal = source.getCalibration();
        if (cal != null) mip.setCalibration(cal.copy());
        return mip;
    }

    private static String safeMessage(Exception ex) {
        String message = ex == null ? null : ex.getMessage();
        return message == null || message.trim().isEmpty()
                ? ex.getClass().getSimpleName()
                : message.trim();
    }
}
