package flash.pipeline.deconv.engine;

import flash.pipeline.deconv.psf.PsfModel;

/**
 * Immutable per-channel deconvolution settings: which engine and algorithm to run,
 * how many iterations, the regularization strength, and the PSF model. Optical
 * acquisition parameters (NA, refractive indices, pixel sizes, wavelength, modality)
 * are shared across channels and live elsewhere.
 */
public final class DeconvSettings {

    private final String engineKey;
    private final Algorithm algorithm;
    private final PsfModel psfModel;
    private final int iterations;
    private final double regularization;

    public DeconvSettings(String engineKey,
                          Algorithm algorithm,
                          PsfModel psfModel,
                          int iterations,
                          double regularization) {
        this.engineKey = engineKey;
        this.algorithm = algorithm;
        this.psfModel = psfModel;
        this.iterations = iterations;
        this.regularization = regularization;
    }

    public String engineKey() {
        return engineKey;
    }

    public Algorithm algorithm() {
        return algorithm;
    }

    public PsfModel psfModel() {
        return psfModel;
    }

    public int iterations() {
        return iterations;
    }

    public double regularization() {
        return regularization;
    }

    public DeconvSettings withEngineKey(String value) {
        return new DeconvSettings(value, algorithm, psfModel, iterations, regularization);
    }

    public DeconvSettings withAlgorithm(Algorithm value) {
        return new DeconvSettings(engineKey, value, psfModel, iterations, regularization);
    }

    public DeconvSettings withPsfModel(PsfModel value) {
        return new DeconvSettings(engineKey, algorithm, value, iterations, regularization);
    }

    public DeconvSettings withIterations(int value) {
        return new DeconvSettings(engineKey, algorithm, psfModel, value, regularization);
    }

    public DeconvSettings withRegularization(double value) {
        return new DeconvSettings(engineKey, algorithm, psfModel, iterations, value);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof DeconvSettings)) {
            return false;
        }
        DeconvSettings other = (DeconvSettings) obj;
        return iterations == other.iterations
                && Double.compare(regularization, other.regularization) == 0
                && (engineKey == null ? other.engineKey == null : engineKey.equals(other.engineKey))
                && algorithm == other.algorithm
                && psfModel == other.psfModel;
    }

    @Override
    public int hashCode() {
        int result = engineKey == null ? 0 : engineKey.hashCode();
        result = 31 * result + (algorithm == null ? 0 : algorithm.hashCode());
        result = 31 * result + (psfModel == null ? 0 : psfModel.hashCode());
        result = 31 * result + iterations;
        long bits = Double.doubleToLongBits(regularization);
        result = 31 * result + (int) (bits ^ (bits >>> 32));
        return result;
    }

    @Override
    public String toString() {
        return "DeconvSettings{engine=" + engineKey
                + ", algorithm=" + algorithm
                + ", psf=" + psfModel
                + ", iterations=" + iterations
                + ", regularization=" + regularization
                + '}';
    }
}
