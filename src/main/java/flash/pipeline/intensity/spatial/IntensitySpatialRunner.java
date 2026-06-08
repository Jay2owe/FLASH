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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs selected same-channel intensity-spatial analysis families.
 */
public final class IntensitySpatialRunner {
    private final List<IntensitySpatialAnalysis> analyses;
    private final List<IntensitySpatialPairAnalysis> pairAnalyses;
    private final boolean progressLogging;

    public IntensitySpatialRunner(List<IntensitySpatialAnalysis> analyses) {
        this(analyses, Collections.<IntensitySpatialPairAnalysis>emptyList(), false);
    }

    public IntensitySpatialRunner(List<IntensitySpatialAnalysis> analyses,
                                  List<IntensitySpatialPairAnalysis> pairAnalyses) {
        this(analyses, pairAnalyses, false);
    }

    public IntensitySpatialRunner(List<IntensitySpatialAnalysis> analyses,
                                  List<IntensitySpatialPairAnalysis> pairAnalyses,
                                  boolean progressLogging) {
        this.analyses = analyses == null
                ? Collections.<IntensitySpatialAnalysis>emptyList()
                : Collections.unmodifiableList(new ArrayList<IntensitySpatialAnalysis>(analyses));
        this.pairAnalyses = pairAnalyses == null
                ? Collections.<IntensitySpatialPairAnalysis>emptyList()
                : Collections.unmodifiableList(new ArrayList<IntensitySpatialPairAnalysis>(pairAnalyses));
        this.progressLogging = progressLogging;
    }

    public static IntensitySpatialRunner standard() {
        return standard(false);
    }

    public static IntensitySpatialRunner standardWithProgress() {
        return standard(true);
    }

    private static IntensitySpatialRunner standard(boolean progressLogging) {
        List<IntensitySpatialAnalysis> sameChannel =
                new ArrayList<IntensitySpatialAnalysis>();
        addSameChannelAnalysis(sameChannel, "patchiness", new SameChannelFactory() {
            @Override public IntensitySpatialAnalysis create() { return new PatchinessAnalysis(); }
        });
        addSameChannelAnalysis(sameChannel, "hotspot", new SameChannelFactory() {
            @Override public IntensitySpatialAnalysis create() { return new HotspotScanAnalysis(); }
        });
        addSameChannelAnalysis(sameChannel, "nullmodel", new SameChannelFactory() {
            @Override public IntensitySpatialAnalysis create() { return new NullModelAnalysis(); }
        });
        addSameChannelAnalysis(sameChannel, "granularity", new SameChannelFactory() {
            @Override public IntensitySpatialAnalysis create() { return new GranularityAnalysis(); }
        });
        addSameChannelAnalysis(sameChannel, "depth_profile", new SameChannelFactory() {
            @Override public IntensitySpatialAnalysis create() { return new DepthProfileAnalysis(); }
        });
        addSameChannelAnalysis(sameChannel, "anisotropy_2d", new SameChannelFactory() {
            @Override public IntensitySpatialAnalysis create() { return new Anisotropy2DAnalysis(); }
        });
        addSameChannelAnalysis(sameChannel, "periodicity", new SameChannelFactory() {
            @Override public IntensitySpatialAnalysis create() { return new PeriodicityAnalysis(); }
        });
        addSameChannelAnalysis(sameChannel, "glcm_texture", new SameChannelFactory() {
            @Override public IntensitySpatialAnalysis create() { return new GlcmTextureAnalysis(); }
        });
        addSameChannelAnalysis(sameChannel, "texture_class", new SameChannelFactory() {
            @Override public IntensitySpatialAnalysis create() { return new TextureClassAnalysis(); }
        });
        addSameChannelAnalysis(sameChannel, "scale_divergence", new SameChannelFactory() {
            @Override public IntensitySpatialAnalysis create() { return new ScaleDivergenceAnalysis(); }
        });
        addSameChannelAnalysis(sameChannel, "anisotropy_3d", new SameChannelFactory() {
            @Override public IntensitySpatialAnalysis create() { return new Anisotropy3DAnalysis(); }
        });

        List<IntensitySpatialPairAnalysis> pair =
                new ArrayList<IntensitySpatialPairAnalysis>();
        addPairAnalysis(pair, "crossmark_2d", new PairFactory() {
            @Override public IntensitySpatialPairAnalysis create() { return new CrossMark2DAnalysis(); }
        });
        addPairAnalysis(pair, "entropy_mi", new PairFactory() {
            @Override public IntensitySpatialPairAnalysis create() { return new EntropyMiAnalysis(); }
        });
        addPairAnalysis(pair, "distance_shell_2d", new PairFactory() {
            @Override public IntensitySpatialPairAnalysis create() { return new DistanceShell2DAnalysis(); }
        });
        addPairAnalysis(pair, "crossmark_3d", new PairFactory() {
            @Override public IntensitySpatialPairAnalysis create() { return new CrossMark3DAnalysis(); }
        });
        addPairAnalysis(pair, "distance_shell_3d", new PairFactory() {
            @Override public IntensitySpatialPairAnalysis create() { return new DistanceShell3DAnalysis(); }
        });
        return new IntensitySpatialRunner(sameChannel, pair, progressLogging);
    }

    private static void addSameChannelAnalysis(List<IntensitySpatialAnalysis> out,
                                               String token,
                                               SameChannelFactory factory) {
        try {
            IntensitySpatialAnalysis analysis = factory.create();
            if (analysis != null) out.add(analysis);
        } catch (LinkageError err) {
            IJ.log("[FLASH] Intensity-spatial " + token
                    + " disabled: runtime class/dependency problem: " + safeMessage(err));
        }
    }

    private static void addPairAnalysis(List<IntensitySpatialPairAnalysis> out,
                                        String token,
                                        PairFactory factory) {
        try {
            IntensitySpatialPairAnalysis analysis = factory.create();
            if (analysis != null) out.add(analysis);
        } catch (LinkageError err) {
            IJ.log("[FLASH] Intensity-spatial " + token
                    + " disabled: runtime class/dependency problem: " + safeMessage(err));
        }
    }

    private interface SameChannelFactory {
        IntensitySpatialAnalysis create();
    }

    private interface PairFactory {
        IntensitySpatialPairAnalysis create();
    }

    public IntensitySpatialResult measure(IntensitySpatialContext context) {
        if (context == null || !context.config().isEnabled()
                || context.config().getEnabledAnalyses().isEmpty()) {
            return IntensitySpatialResult.empty();
        }
        if (context.outputMode() == IntensitySpatialOutputMode.NATIVE_3D
                && !native3dAllowed(context.config(), stackDepth(context.image()))) {
            IJ.log("[FLASH] Intensity-spatial native 3D skipped for " + context.imageId()
                    + " channel " + context.channelName()
                    + ": native 3D output is not selected or stack has fewer than "
                    + IntensitySpatialConfig.MIN_NATIVE_3D_SLICES + " slices");
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
                long analysisStart = logSameChannelStart(analysis, effectiveContext);
                IntensitySpatialResult result = analysis.measure(effectiveContext);
                if (result != null) {
                    values.putAll(result.values());
                }
                logSameChannelComplete(analysis, effectiveContext, result, analysisStart);
            } catch (LinkageError err) {
                IJ.log("[FLASH] Intensity-spatial " + analysis.key().token()
                        + " skipped for " + context.imageId()
                        + " channel " + context.channelName()
                        + ": runtime class/dependency problem: " + safeMessage(err));
                values.putAll(IntensitySpatialResult
                        .nanFor(analysis.columns(context.config(), binarizedPartner))
                        .values());
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

    public IntensitySpatialResult measurePair(IntensitySpatialPairContext context) {
        if (context == null || !context.config().isEnabled()
                || context.config().getEnabledAnalyses().isEmpty()
                || context.sourceChannelName().equals(context.partnerChannelName())) {
            return IntensitySpatialResult.empty();
        }
        if (context.outputMode() == IntensitySpatialOutputMode.NATIVE_3D
                && !native3dAllowed(context.config(), pairStackDepth(context))) {
            IJ.log("[FLASH] Intensity-spatial native 3D skipped for " + context.imageId()
                    + " source " + context.sourceChannelName()
                    + " partner " + context.partnerChannelName()
                    + ": native 3D output is not selected or stack has fewer than "
                    + IntensitySpatialConfig.MIN_NATIVE_3D_SLICES + " slices");
            return IntensitySpatialResult.empty();
        }

        LinkedHashMap<String, Double> values = new LinkedHashMap<String, Double>();
        for (IntensitySpatialPairAnalysis analysis : pairAnalyses) {
            if (analysis == null
                    || !context.config().getEnabledAnalyses().contains(analysis.key())
                    || !analysis.outputModes().contains(context.outputMode())) {
                continue;
            }
            if ((analysis.key() == IntensitySpatialConfig.AnalysisKey.DISTANCE_SHELL
                    || analysis.key() == IntensitySpatialConfig.AnalysisKey.DISTANCE_SHELL_3D)
                    && !context.hasPartnerMaskImage()) {
                IJ.log("[FLASH] Intensity-spatial " + analysis.key().token()
                        + " skipped for " + context.imageId()
                        + " source " + context.sourceChannelName()
                        + " partner " + context.partnerChannelName()
                        + ": partner channel is not binarized");
                continue;
            }

            boolean sourceBinarized = context.hasSourceBinarizedImage();
            boolean partnerBinarized = context.hasPartnerBinarizedImage();
            try {
                long analysisStart = logPairStart(analysis, context);
                IntensitySpatialResult result = analysis.measure(context);
                if (result != null) {
                    values.putAll(result.values());
                }
                logPairComplete(analysis, context, result, analysisStart);
            } catch (LinkageError err) {
                IJ.log("[FLASH] Intensity-spatial " + analysis.key().token()
                        + " skipped for " + context.imageId()
                        + " source " + context.sourceChannelName()
                        + " partner " + context.partnerChannelName()
                        + ": runtime class/dependency problem: " + safeMessage(err));
                values.putAll(IntensitySpatialResult
                        .nanFor(analysis.columns(context.config(),
                                context.sourceChannelName(), context.partnerChannelName(),
                                sourceBinarized, partnerBinarized))
                        .values());
            } catch (Exception ex) {
                IJ.log("[FLASH] Intensity-spatial " + analysis.key().token()
                        + " skipped for " + context.imageId()
                        + " source " + context.sourceChannelName()
                        + " partner " + context.partnerChannelName()
                        + ": " + safeMessage(ex));
                values.putAll(IntensitySpatialResult
                        .nanFor(analysis.columns(context.config(),
                                context.sourceChannelName(), context.partnerChannelName(),
                                sourceBinarized, partnerBinarized))
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

    private long logSameChannelStart(IntensitySpatialAnalysis analysis,
                                     IntensitySpatialContext context) {
        if (!progressLogging) return 0L;
        IJ.showStatus("Intensity-spatial " + analysis.key().token()
                + ": " + sameChannelLabel(context));
        IJ.log("[FLASH] Intensity-spatial " + analysis.key().token()
                + " running: " + sameChannelLabel(context)
                + " (cost " + analysis.estimatedCost() + ")");
        return System.currentTimeMillis();
    }

    private void logSameChannelComplete(IntensitySpatialAnalysis analysis,
                                        IntensitySpatialContext context,
                                        IntensitySpatialResult result,
                                        long startMillis) {
        if (!progressLogging) return;
        IJ.log("[FLASH] Intensity-spatial " + analysis.key().token()
                + " complete: " + sameChannelLabel(context)
                + resultSummary(result, startMillis));
    }

    private long logPairStart(IntensitySpatialPairAnalysis analysis,
                              IntensitySpatialPairContext context) {
        if (!progressLogging) return 0L;
        IJ.showStatus("Intensity-spatial " + analysis.key().token()
                + ": " + pairLabel(context));
        IJ.log("[FLASH] Intensity-spatial " + analysis.key().token()
                + " running: " + pairLabel(context)
                + " (cost " + analysis.estimatedCost() + ")");
        return System.currentTimeMillis();
    }

    private void logPairComplete(IntensitySpatialPairAnalysis analysis,
                                 IntensitySpatialPairContext context,
                                 IntensitySpatialResult result,
                                 long startMillis) {
        if (!progressLogging) return;
        IJ.log("[FLASH] Intensity-spatial " + analysis.key().token()
                + " complete: " + pairLabel(context)
                + resultSummary(result, startMillis));
    }

    private static String sameChannelLabel(IntensitySpatialContext context) {
        return context.imageId()
                + " channel " + context.channelName()
                + roiLabel(context.roiLabel())
                + " " + modeLabel(context.outputMode(), context.sliceIndex());
    }

    private static String pairLabel(IntensitySpatialPairContext context) {
        if (context.outputMode() == IntensitySpatialOutputMode.MIP) {
            return context.imageId()
                    + " source " + context.sourceChannelName() + " MIP"
                    + " -> partner " + context.partnerChannelName() + " MIP"
                    + roiLabel(context.roiLabel());
        }
        return context.imageId()
                + " source " + context.sourceChannelName()
                + " -> partner " + context.partnerChannelName()
                + roiLabel(context.roiLabel())
                + " " + modeLabel(context.outputMode(), context.sliceIndex());
    }

    private static String roiLabel(String roiLabel) {
        return roiLabel == null || roiLabel.trim().isEmpty()
                ? ""
                : " ROI " + roiLabel.trim();
    }

    private static String modeLabel(IntensitySpatialOutputMode mode, int sliceIndex) {
        if (mode == IntensitySpatialOutputMode.MIP) return "MIP";
        if (mode == IntensitySpatialOutputMode.NATIVE_3D) return "native 3D";
        return "base slice " + Math.max(1, sliceIndex);
    }

    private static String resultSummary(IntensitySpatialResult result, long startMillis) {
        int values = result == null ? 0 : result.values().size();
        String elapsed = startMillis > 0L
                ? ", " + formatDurationCompact(System.currentTimeMillis() - startMillis)
                : "";
        return " (" + values + " columns" + elapsed + ")";
    }

    private static boolean native3dAllowed(IntensitySpatialConfig config, int stackDepth) {
        return config != null
                && config.isNative3dEnabled()
                && stackDepth >= IntensitySpatialConfig.MIN_NATIVE_3D_SLICES;
    }

    private static int stackDepth(ImagePlus image) {
        return image == null ? 0 : Math.max(1, image.getStackSize());
    }

    private static int pairStackDepth(IntensitySpatialPairContext context) {
        return Math.min(stackDepth(context.sourceImage()), stackDepth(context.partnerImage()));
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
                if (Float.isNaN(out[p]) || (!Float.isNaN(value) && value > out[p])) {
                    out[p] = value;
                }
            }
        }
        ImagePlus mip = new ImagePlus(title == null ? source.getTitle() + "_MIP" : title,
                new FloatProcessor(width, height, out, null));
        Calibration cal = source.getCalibration();
        if (cal != null) mip.setCalibration(cal.copy());
        return mip;
    }

    private static String safeMessage(Throwable ex) {
        String message = ex == null ? null : ex.getMessage();
        return message == null || message.trim().isEmpty()
                ? ex.getClass().getSimpleName()
                : message.trim();
    }

    private static String formatDurationCompact(long ms) {
        long safeMs = Math.max(0L, ms);
        if (safeMs < 1000L) {
            return safeMs + " ms";
        }
        long seconds = safeMs / 1000L;
        if (seconds < 60L) {
            return seconds + " s";
        }
        long minutes = seconds / 60L;
        long remSeconds = seconds % 60L;
        if (minutes < 60L) {
            return minutes + "m " + remSeconds + "s";
        }
        long hours = minutes / 60L;
        long remMinutes = minutes % 60L;
        return hours + "h " + remMinutes + "m";
    }
}
