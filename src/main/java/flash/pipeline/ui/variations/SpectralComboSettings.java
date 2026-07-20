package flash.pipeline.ui.variations;

import flash.pipeline.decontamination.CorrectionPipeline;
import flash.pipeline.decontamination.SpectralDecontaminationConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Maps a {@link ParameterCombo} of {@link SpectralParameterId} axes onto a base
 * {@link SpectralDecontaminationConfig}, producing an executable config that the existing
 * {@link CorrectionPipeline} can run. Mirrors {@code DeconvComboSettings} but, because the
 * correction-stack engine is entirely config-driven, the resolved unit is a whole config.
 *
 * <p>Axes not present in the combo (i.e. not swept) keep their base value. Because
 * {@code ParameterSweepEditor.forSpectral} seeds each un-ticked row with the base config's
 * effective value, overlaying an un-ticked axis reproduces the base behaviour (the
 * fall-through guarantee).</p>
 *
 * <p>{@link SpectralParameterId#AF_MODE} is <em>structural</em>: it rewrites which
 * autofluorescence feature is in {@code featureIds} rather than editing a numeric setting.
 * All other axes overlay stringly-typed feature {@code Settings} keys, and only for features
 * actually present in the stack.</p>
 */
public final class SpectralComboSettings {

    // Feature ids (kept in sync with the *.ID constants in flash.pipeline.decontamination.features).
    static final String FEATURE_LINEAR_UNMIXING = "linear_unmixing";
    static final String FEATURE_GLOBAL_RATIO = "global_ratio_correction";
    static final String FEATURE_LOCAL_K = "local_k_correction";
    static final String FEATURE_THRESHOLD = "threshold_corrected_target";
    static final String FEATURE_ROC = "roc_threshold_search";
    static final String FEATURE_SIZE_FILTER = "size_filter";

    // Feature Settings keys.
    private static final String KEY_WEIGHT_SCALE = "weight_scale";
    private static final String KEY_FIT_PERCENTILE = "fit_percentile";
    private static final String KEY_QUIET_TARGET_PERCENTILE = "quiet_target_percentile";
    private static final String KEY_WINDOW_RADIUS = "window_radius";
    private static final String KEY_THRESHOLD_MODE = "threshold_mode";
    private static final String KEY_THRESHOLD_PERCENTILE = "threshold_percentile";
    private static final String THRESHOLD_MODE_PERCENTILE = "percentile";
    private static final String KEY_ALLOWED_FPR = "allowed_false_positive_rate";
    private static final String KEY_MIN_SIZE = "min_size_voxels";
    private static final String KEY_MAX_SIZE = "max_size_voxels";

    private SpectralComboSettings() {
    }

    public static SpectralDecontaminationConfig resolve(ParameterCombo combo,
                                                        SpectralDecontaminationConfig base) {
        if (base == null) {
            throw new IllegalArgumentException("base must not be null");
        }
        SpectralDecontaminationConfig out = base.copy();
        if (combo == null) {
            return out;
        }

        CorrectionPipeline pipeline = out.getCorrectionPipeline();
        List<String> originalIds = new ArrayList<String>(pipeline.getFeatureIds());
        List<String> featureIds = new ArrayList<String>(originalIds);

        // Structural autofluorescence-mode swap: replace whichever AF feature is present with
        // the requested one. If the requested feature is already present, or neither AF feature
        // is in the stack, leave the stack untouched.
        String afFeatureId = parseAfModeFeatureId(combo.get(SpectralParameterId.AF_MODE));
        if (afFeatureId != null && !featureIds.contains(afFeatureId)) {
            String other = FEATURE_LOCAL_K.equals(afFeatureId) ? FEATURE_GLOBAL_RATIO : FEATURE_LOCAL_K;
            int idx = featureIds.indexOf(other);
            if (idx >= 0) {
                featureIds.set(idx, afFeatureId);
            }
        }
        if (!featureIds.equals(originalIds)) {
            pipeline.setFeatureIds(featureIds);
            // The stack no longer matches its named preset after a structural swap; mark it custom
            // so persistence/reporting and any dialog re-derivation reflect the actual feature set.
            pipeline.setPresetId(CorrectionPipeline.CUSTOM_PRESET_ID);
            out.setCorrectionPipeline(pipeline);
        }

        Double strength = asDouble(combo.get(SpectralParameterId.STRENGTH));
        Double fitPercentile = asDouble(combo.get(SpectralParameterId.FIT_PERCENTILE));
        Double afQuietPercentile = asDouble(combo.get(SpectralParameterId.AF_QUIET_PERCENTILE));
        Integer afWindow = asInt(combo.get(SpectralParameterId.AF_WINDOW_RADIUS));
        Double maskPercentile = asDouble(combo.get(SpectralParameterId.MASK_PERCENTILE));
        Double rocFpr = asDouble(combo.get(SpectralParameterId.ROC_FPR));
        Integer sizeMin = asInt(combo.get(SpectralParameterId.SIZE_MIN));
        Integer sizeMax = asInt(combo.get(SpectralParameterId.SIZE_MAX));

        // FIT_PERCENTILE is the linear-unmixing quiet-pixel percentile only; the AF features have a
        // separate AF_QUIET_PERCENTILE axis so the two (which have different defaults, 50 vs 85)
        // never collide on one value — preserving the fall-through guarantee even in a custom stack
        // that contains both linear unmixing and an AF feature.
        if (featureIds.contains(FEATURE_LINEAR_UNMIXING) && (strength != null || fitPercentile != null)) {
            CorrectionPipeline.Settings s = out.getFeatureSettings(FEATURE_LINEAR_UNMIXING);
            if (strength != null) {
                s.putDouble(KEY_WEIGHT_SCALE, strength.doubleValue());
            }
            if (fitPercentile != null) {
                s.putDouble(KEY_FIT_PERCENTILE, fitPercentile.doubleValue());
            }
            out.setFeatureSettings(FEATURE_LINEAR_UNMIXING, s);
        }

        // AF quiet percentile: apply to the SINGLE primary AF feature (local-k if present, else
        // global), matching baseValueFor(). If a custom stack somehow contains both AF features, the
        // secondary one keeps its base value, preserving the fall-through guarantee.
        String primaryAf = featureIds.contains(FEATURE_LOCAL_K)
                ? FEATURE_LOCAL_K
                : (featureIds.contains(FEATURE_GLOBAL_RATIO) ? FEATURE_GLOBAL_RATIO : null);
        if (primaryAf != null && afQuietPercentile != null) {
            CorrectionPipeline.Settings s = out.getFeatureSettings(primaryAf);
            s.putDouble(KEY_QUIET_TARGET_PERCENTILE, afQuietPercentile.doubleValue());
            out.setFeatureSettings(primaryAf, s);
        }

        if (featureIds.contains(FEATURE_LOCAL_K) && afWindow != null) {
            CorrectionPipeline.Settings s = out.getFeatureSettings(FEATURE_LOCAL_K);
            s.putInt(KEY_WINDOW_RADIUS, afWindow.intValue());
            out.setFeatureSettings(FEATURE_LOCAL_K, s);
        }

        // Mask percentile: only meaningful when the threshold feature is in percentile mode.
        // If the base stack uses a fixed/median threshold, leave it alone rather than silently
        // switching it to percentile (which would break the fall-through guarantee for an
        // un-swept, seeded MASK_PERCENTILE row). The threshold feature defaults to percentile.
        if (featureIds.contains(FEATURE_THRESHOLD) && maskPercentile != null) {
            CorrectionPipeline.Settings s = out.getFeatureSettings(FEATURE_THRESHOLD);
            String baseMode = s.get(KEY_THRESHOLD_MODE, THRESHOLD_MODE_PERCENTILE);
            if (THRESHOLD_MODE_PERCENTILE.equalsIgnoreCase(baseMode)) {
                s.put(KEY_THRESHOLD_MODE, THRESHOLD_MODE_PERCENTILE);
                s.putDouble(KEY_THRESHOLD_PERCENTILE, maskPercentile.doubleValue());
                out.setFeatureSettings(FEATURE_THRESHOLD, s);
            }
        }

        if (featureIds.contains(FEATURE_ROC) && rocFpr != null) {
            CorrectionPipeline.Settings s = out.getFeatureSettings(FEATURE_ROC);
            s.putDouble(KEY_ALLOWED_FPR, rocFpr.doubleValue());
            out.setFeatureSettings(FEATURE_ROC, s);
        }

        if (featureIds.contains(FEATURE_SIZE_FILTER) && (sizeMin != null || sizeMax != null)) {
            CorrectionPipeline.Settings s = out.getFeatureSettings(FEATURE_SIZE_FILTER);
            if (sizeMin != null) {
                s.putInt(KEY_MIN_SIZE, sizeMin.intValue());
            }
            if (sizeMax != null) {
                s.putInt(KEY_MAX_SIZE, sizeMax.intValue());
            }
            out.setFeatureSettings(FEATURE_SIZE_FILTER, s);
        }

        return out;
    }

    /**
     * The effective base value for an axis, read from the base config's feature settings and
     * falling back to the feature's code default when the setting is absent. Used by
     * {@code ParameterSweepEditor.forSpectral} to seed each row so that overlaying an un-ticked
     * axis reproduces the base behaviour (the fall-through guarantee).
     *
     * <p>{@link SpectralParameterId#FIT_PERCENTILE} is the linear-unmixing quiet-pixel percentile
     * (default 50); {@link SpectralParameterId#AF_QUIET_PERCENTILE} is the AF quiet-target
     * percentile (default 85). They are separate axes so their differing defaults never collide,
     * keeping the fall-through guarantee even in a custom stack containing both.</p>
     */
    public static Object baseValueFor(SpectralDecontaminationConfig base, SpectralParameterId id) {
        if (id == null) {
            return null;
        }
        switch (id) {
            case STRENGTH:
                return Double.valueOf(settings(base, FEATURE_LINEAR_UNMIXING).getDouble(KEY_WEIGHT_SCALE, 1.0));
            case FIT_PERCENTILE:
                return Double.valueOf(settings(base, FEATURE_LINEAR_UNMIXING).getDouble(KEY_FIT_PERCENTILE, 50.0));
            case AF_QUIET_PERCENTILE:
                if (hasFeature(base, FEATURE_LOCAL_K)) {
                    return Double.valueOf(settings(base, FEATURE_LOCAL_K).getDouble(KEY_QUIET_TARGET_PERCENTILE, 85.0));
                }
                return Double.valueOf(settings(base, FEATURE_GLOBAL_RATIO).getDouble(KEY_QUIET_TARGET_PERCENTILE, 85.0));
            case AF_MODE:
                return hasFeature(base, FEATURE_LOCAL_K)
                        ? SpectralParameterId.AF_MODE_LOCAL
                        : SpectralParameterId.AF_MODE_GLOBAL;
            case AF_WINDOW_RADIUS:
                return Integer.valueOf(settings(base, FEATURE_LOCAL_K).getInt(KEY_WINDOW_RADIUS, 2));
            case MASK_PERCENTILE:
                return Double.valueOf(settings(base, FEATURE_THRESHOLD).getDouble(KEY_THRESHOLD_PERCENTILE, 90.0));
            case ROC_FPR:
                return Double.valueOf(settings(base, FEATURE_ROC).getDouble(KEY_ALLOWED_FPR, 0.05));
            case SIZE_MIN:
                return Integer.valueOf(settings(base, FEATURE_SIZE_FILTER).getInt(KEY_MIN_SIZE, 1));
            case SIZE_MAX:
                return Integer.valueOf(settings(base, FEATURE_SIZE_FILTER).getInt(KEY_MAX_SIZE, 0));
            default:
                return null;
        }
    }

    private static CorrectionPipeline.Settings settings(SpectralDecontaminationConfig base, String featureId) {
        return base == null ? new CorrectionPipeline.Settings() : base.getFeatureSettings(featureId);
    }

    private static boolean hasFeature(SpectralDecontaminationConfig base, String featureId) {
        return base != null && base.getCorrectionPipeline().getFeatureIds().contains(featureId);
    }

    /** Normalises an {@link SpectralParameterId#AF_MODE} token to {@code global}/{@code local}, or null. */
    static String parseAfMode(Object token) {
        if (!(token instanceof String)) {
            return null;
        }
        String name = ((String) token).trim().toLowerCase(Locale.US);
        if (name.isEmpty()) {
            return null;
        }
        if (name.equals(SpectralParameterId.AF_MODE_LOCAL)
                || name.equals("local_k")
                || name.equals(FEATURE_LOCAL_K)) {
            return SpectralParameterId.AF_MODE_LOCAL;
        }
        if (name.equals(SpectralParameterId.AF_MODE_GLOBAL)
                || name.equals("global_ratio")
                || name.equals(FEATURE_GLOBAL_RATIO)) {
            return SpectralParameterId.AF_MODE_GLOBAL;
        }
        return null;
    }

    /** Resolves an {@link SpectralParameterId#AF_MODE} token to its feature id, or null if unrecognised. */
    static String parseAfModeFeatureId(Object token) {
        String mode = parseAfMode(token);
        if (mode == null) {
            return null;
        }
        return SpectralParameterId.AF_MODE_LOCAL.equals(mode) ? FEATURE_LOCAL_K : FEATURE_GLOBAL_RATIO;
    }

    private static Double asDouble(Object value) {
        return (value instanceof Number) ? Double.valueOf(((Number) value).doubleValue()) : null;
    }

    private static Integer asInt(Object value) {
        return (value instanceof Number) ? Integer.valueOf(((Number) value).intValue()) : null;
    }
}
