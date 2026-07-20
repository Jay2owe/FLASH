package flash.pipeline.ui.variations;

import flash.pipeline.deconv.engine.Algorithm;
import flash.pipeline.deconv.engine.DeconvSettings;
import flash.pipeline.deconv.engine.DeconvolutionEngine;
import flash.pipeline.deconv.engine.EngineRegistry;
import flash.pipeline.deconv.psf.PsfModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class DeconvSweepPlan {

    interface EngineLookup {
        DeconvolutionEngine byKey(String key);
    }

    private final List<ParameterCombo> executableCombos;
    private final int skippedCount;

    private DeconvSweepPlan(List<ParameterCombo> executableCombos,
                            int skippedCount) {
        this.executableCombos = Collections.unmodifiableList(
                new ArrayList<ParameterCombo>(executableCombos));
        this.skippedCount = Math.max(0, skippedCount);
    }

    static DeconvSweepPlan forSweep(ParameterSweep sweep, DeconvSettings base) {
        return forSweep(sweep, base, Long.MAX_VALUE);
    }

    static DeconvSweepPlan forSweep(ParameterSweep sweep, DeconvSettings base, long maxRawCombos) {
        return forSweep(sweep, base, new EngineLookup() {
            @Override public DeconvolutionEngine byKey(String key) {
                return EngineRegistry.byKey(key);
            }
        }, maxRawCombos);
    }

    static DeconvSweepPlan forSweep(ParameterSweep sweep,
                                     DeconvSettings base,
                                     EngineLookup engineLookup) {
        return forSweep(sweep, base, engineLookup, Long.MAX_VALUE);
    }

    static DeconvSweepPlan forSweep(ParameterSweep sweep,
                                    DeconvSettings base,
                                    EngineLookup engineLookup,
                                    long maxRawCombos) {
        if (sweep == null) {
            throw new IllegalArgumentException("sweep must not be null");
        }
        if (base == null) {
            throw new IllegalArgumentException("base must not be null");
        }
        if (sweep.method() != ParameterSweep.Method.DECONVOLUTION) {
            throw new IllegalArgumentException("Only deconvolution sweeps can be planned");
        }
        long rawCount = sweep.cellCount();
        if (rawCount > maxRawCombos) {
            throw new TooManyCombinationsException(rawCount, maxRawCombos);
        }
        List<ParameterCombo> rawCombos = sweep.combos();
        List<ParameterCombo> executable = new ArrayList<ParameterCombo>();
        EngineLookup lookup = engineLookup == null ? new EngineLookup() {
            @Override public DeconvolutionEngine byKey(String key) {
                return EngineRegistry.byKey(key);
            }
        } : engineLookup;
        for (int i = 0; i < rawCombos.size(); i++) {
            ParameterCombo combo = rawCombos.get(i);
            if (canGenerate(combo, base, lookup)) {
                executable.add(combo);
            }
        }
        return new DeconvSweepPlan(executable,
                rawCombos.size() - executable.size());
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

    List<ParameterCombo> executableCombos() {
        return executableCombos;
    }

    int executableCount() {
        return executableCombos.size();
    }

    int skippedCount() {
        return skippedCount;
    }

    private static boolean canGenerate(ParameterCombo combo,
                                        DeconvSettings base,
                                        EngineLookup lookup) {
        if (hasUnresolvedCategoricalAxis(combo)) {
            return false;
        }
        DeconvSettings settings;
        try {
            settings = DeconvComboSettings.resolve(combo, base);
        } catch (RuntimeException e) {
            return false;
        }
        if (settings == null) {
            return false;
        }
        DeconvolutionEngine engine = resolveEngine(lookup, settings.engineKey());
        if (engine == null) {
            return false;
        }
        Algorithm algorithm = settings.algorithm();
        List<Algorithm> supportedAlgorithms = engine.supportedAlgorithms();
        if (algorithm == null
                || supportedAlgorithms == null
                || !supportedAlgorithms.contains(algorithm)) {
            return false;
        }
        PsfModel psfModel = settings.psfModel();
        try {
            return engine.supportsPsfModel(psfModel);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean hasUnresolvedCategoricalAxis(ParameterCombo combo) {
        if (combo == null) {
            return false;
        }
        return hasUnresolvedAlgorithm(combo) || hasUnresolvedPsfModel(combo);
    }

    private static boolean hasUnresolvedAlgorithm(ParameterCombo combo) {
        if (!combo.contains(DeconvParameterId.ALGORITHM)) {
            return false;
        }
        return DeconvComboSettings.parseAlgorithm(combo.get(DeconvParameterId.ALGORITHM)) == null;
    }

    private static boolean hasUnresolvedPsfModel(ParameterCombo combo) {
        if (!combo.contains(DeconvParameterId.PSF_MODEL)) {
            return false;
        }
        return DeconvComboSettings.parsePsf(combo.get(DeconvParameterId.PSF_MODEL)) == null;
    }

    private static DeconvolutionEngine resolveEngine(EngineLookup lookup, String key) {
        if (lookup == null || key == null || key.trim().isEmpty()) {
            return null;
        }
        try {
            return lookup.byKey(key.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
