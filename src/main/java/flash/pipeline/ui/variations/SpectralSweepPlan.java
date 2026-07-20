package flash.pipeline.ui.variations;

import flash.pipeline.decontamination.CorrectionFeatureRegistry;
import flash.pipeline.decontamination.CorrectionPipeline;
import flash.pipeline.decontamination.SpectralDecontaminationConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Plans a spectral-decontamination sweep: resolves every raw combination to an executable
 * {@link SpectralDecontaminationConfig} and keeps only those that can run in the single-crop
 * preview grid. Mirrors {@code DeconvSweepPlan}.
 *
 * <p>A combination is dropped when it (a) fails to resolve, (b) resolves to a stack that still
 * contains the ROC threshold search (which needs control/experimental images + calibration and
 * cannot run on one crop), (c) fails validation against the registry, or (d) is a duplicate of an
 * already-kept combination (e.g. the AF window radius varies while the mode is global, so the
 * radius is inert). Deduplication is by the resolved config's feature stack + settings signature,
 * which handles every inert-axis case uniformly.</p>
 */
final class SpectralSweepPlan {

    private static final String ROC_FEATURE_ID = "roc_threshold_search";

    private final List<ParameterCombo> executableCombos;
    private final int skippedCount;

    private SpectralSweepPlan(List<ParameterCombo> executableCombos, int skippedCount) {
        this.executableCombos = Collections.unmodifiableList(
                new ArrayList<ParameterCombo>(executableCombos));
        this.skippedCount = Math.max(0, skippedCount);
    }

    static SpectralSweepPlan forSweep(ParameterSweep sweep, SpectralDecontaminationConfig base) {
        return forSweep(sweep, base, CorrectionFeatureRegistry.getDefault(), Long.MAX_VALUE);
    }

    static SpectralSweepPlan forSweep(ParameterSweep sweep,
                                      SpectralDecontaminationConfig base,
                                      CorrectionFeatureRegistry registry,
                                      long maxRawCombos) {
        if (sweep == null) {
            throw new IllegalArgumentException("sweep must not be null");
        }
        if (base == null) {
            throw new IllegalArgumentException("base must not be null");
        }
        if (sweep.method() != ParameterSweep.Method.SPECTRAL) {
            throw new IllegalArgumentException("Only spectral sweeps can be planned");
        }
        long rawCount = sweep.cellCount();
        if (rawCount > maxRawCombos) {
            throw new TooManyCombinationsException(rawCount, maxRawCombos);
        }
        List<ParameterCombo> rawCombos = sweep.combos();
        List<ParameterCombo> executable = new ArrayList<ParameterCombo>();
        java.util.Set<String> seenSignatures = new java.util.HashSet<String>();
        for (int i = 0; i < rawCombos.size(); i++) {
            ParameterCombo combo = rawCombos.get(i);
            SpectralDecontaminationConfig resolved = resolve(combo, base);
            if (resolved == null) {
                continue;
            }
            if (resolved.getCorrectionPipeline().getFeatureIds().contains(ROC_FEATURE_ID)) {
                continue;
            }
            if (registry != null && !isValid(resolved, registry)) {
                continue;
            }
            String signature = signature(resolved);
            if (!seenSignatures.add(signature)) {
                continue;
            }
            executable.add(combo);
        }
        return new SpectralSweepPlan(executable, rawCombos.size() - executable.size());
    }

    List<ParameterCombo> executableCombos() {
        return executableCombos;
    }

    int executableCount() {
        return executableCombos.size();
    }

    int skippedCount() {
        return skippedCount;
    }

    static final class TooManyCombinationsException extends RuntimeException {
        private final long rawCount;
        private final long maxRawCombos;

        TooManyCombinationsException(long rawCount, long maxRawCombos) {
            super("too many parameter combinations: " + rawCount);
            this.rawCount = rawCount;
            this.maxRawCombos = maxRawCombos;
        }

        long rawCount() {
            return rawCount;
        }

        long maxRawCombos() {
            return maxRawCombos;
        }
    }

    private static SpectralDecontaminationConfig resolve(ParameterCombo combo,
                                                         SpectralDecontaminationConfig base) {
        try {
            return SpectralComboSettings.resolve(combo, base);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean isValid(SpectralDecontaminationConfig resolved,
                                   CorrectionFeatureRegistry registry) {
        try {
            return resolved.getCorrectionPipeline().validate(registry, resolved, false).isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Canonical signature of a resolved config: feature stack order + sorted feature settings. */
    private static String signature(SpectralDecontaminationConfig resolved) {
        StringBuilder sb = new StringBuilder();
        sb.append(resolved.getCorrectionPipeline().getFeatureIds());
        sb.append('|');
        Map<String, CorrectionPipeline.Settings> byId =
                new TreeMap<String, CorrectionPipeline.Settings>(resolved.getFeatureSettingsById());
        for (Map.Entry<String, CorrectionPipeline.Settings> entry : byId.entrySet()) {
            sb.append(entry.getKey()).append('{');
            Map<String, String> values = new TreeMap<String, String>(entry.getValue().getValues());
            for (Map.Entry<String, String> v : values.entrySet()) {
                sb.append(v.getKey()).append('=').append(v.getValue()).append(',');
            }
            sb.append('}');
        }
        return sb.toString();
    }
}
