package flash.pipeline.ui.variations;

import flash.pipeline.deconv.engine.Algorithm;
import flash.pipeline.deconv.engine.DeconvSettings;
import flash.pipeline.deconv.psf.PsfModel;

/**
 * Maps a {@link ParameterCombo} of {@link DeconvParameterId} axes onto a base
 * {@link DeconvSettings}. Axes that are not present in the combo (i.e. not swept)
 * keep their base value, so a sweep over only {@code ITERATIONS} varies iterations
 * while engine/algorithm/regularization/PSF stay at the channel's chosen values.
 */
public final class DeconvComboSettings {

    private DeconvComboSettings() {
    }

    public static DeconvSettings resolve(ParameterCombo combo, DeconvSettings base) {
        if (base == null) {
            throw new IllegalArgumentException("base must not be null");
        }
        if (combo == null) {
            return base;
        }
        DeconvSettings out = base;

        Object engine = combo.get(DeconvParameterId.ENGINE);
        if (engine instanceof String && !((String) engine).trim().isEmpty()) {
            out = out.withEngineKey(((String) engine).trim());
        }

        Algorithm algorithm = parseAlgorithm(combo.get(DeconvParameterId.ALGORITHM));
        if (algorithm != null) {
            out = out.withAlgorithm(algorithm);
        }

        PsfModel psf = parsePsf(combo.get(DeconvParameterId.PSF_MODEL));
        if (psf != null) {
            out = out.withPsfModel(psf);
        }

        Object iterations = combo.get(DeconvParameterId.ITERATIONS);
        if (iterations instanceof Number) {
            out = out.withIterations(((Number) iterations).intValue());
        }

        Object regularization = combo.get(DeconvParameterId.REGULARIZATION);
        if (regularization instanceof Number) {
            out = out.withRegularization(((Number) regularization).doubleValue());
        }
        return out;
    }

    static Algorithm parseAlgorithm(Object token) {
        if (!(token instanceof String)) {
            return null;
        }
        String name = ((String) token).trim();
        if (name.isEmpty()) {
            return null;
        }
        for (Algorithm a : Algorithm.values()) {
            if (a.name().equalsIgnoreCase(name) || a.displayName().equalsIgnoreCase(name)) {
                return a;
            }
        }
        return null;
    }

    static PsfModel parsePsf(Object token) {
        if (!(token instanceof String)) {
            return null;
        }
        String name = ((String) token).trim();
        if (name.isEmpty()) {
            return null;
        }
        for (PsfModel p : PsfModel.values()) {
            if (p.name().equalsIgnoreCase(name) || p.displayName().equalsIgnoreCase(name)) {
                return p;
            }
        }
        return null;
    }
}
