package flash.pipeline.deconv.wizard;

import flash.pipeline.deconv.engine.Algorithm;
import flash.pipeline.deconv.psf.PsfModel;
import flash.pipeline.deconv.psf.ScopeModality;
import flash.pipeline.intelligence.MiniJson;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Immutable persisted deconvolution preset.
 */
public final class DeconvPreset {

    private final String name;
    private final String description;
    private final String engineKey;
    private final Algorithm algorithm;
    private final PsfModel psfModel;
    private final int iterations;
    private final double regularization;
    private final ScopeModality scopeModality;
    private final Double pinholeAU;
    private final Double sampleRI;

    public DeconvPreset(String name,
                        String description,
                        String engineKey,
                        Algorithm algorithm,
                        PsfModel psfModel,
                        int iterations,
                        double regularization,
                        ScopeModality scopeModality,
                        Double pinholeAU,
                        Double sampleRI) {
        this.name = requireText("name", name);
        this.description = emptyToNull(description);
        this.engineKey = requireText("engineKey", engineKey);
        if (algorithm == null) {
            throw new IllegalArgumentException("algorithm is required.");
        }
        if (psfModel == null) {
            throw new IllegalArgumentException("psfModel is required.");
        }
        if (scopeModality == null) {
            throw new IllegalArgumentException("scopeModality is required.");
        }
        if (iterations < 1 || iterations > 100) {
            throw new IllegalArgumentException("iterations must be in the range 1-100.");
        }
        if (Double.isNaN(regularization) || Double.isInfinite(regularization)
                || regularization < 0.0 || regularization > 0.1) {
            throw new IllegalArgumentException("regularization must be finite and in the range 0.0-0.1.");
        }
        this.algorithm = algorithm;
        this.psfModel = psfModel;
        this.iterations = iterations;
        this.regularization = regularization;
        this.scopeModality = scopeModality;
        this.pinholeAU = requirePositiveFiniteOrNull("pinholeAU", pinholeAU);
        this.sampleRI = requirePositiveFiniteOrNull("sampleRI", sampleRI);
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getEngineKey() {
        return engineKey;
    }

    public Algorithm getAlgorithm() {
        return algorithm;
    }

    public PsfModel getPsfModel() {
        return psfModel;
    }

    public int getIterations() {
        return iterations;
    }

    public double getRegularization() {
        return regularization;
    }

    public ScopeModality getScopeModality() {
        return scopeModality;
    }

    public Double getPinholeAU() {
        return pinholeAU;
    }

    public Double getSampleRI() {
        return sampleRI;
    }

    public String toJson() {
        return MiniJson.write(toJsonObject());
    }

    public Map<String, Object> toJsonObject() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("name", name);
        if (description != null) {
            out.put("description", description);
        }
        out.put("engineKey", engineKey);
        out.put("algorithm", algorithm.name());
        out.put("psfModel", psfModel.name());
        out.put("iterations", Integer.valueOf(iterations));
        out.put("regularization", Double.valueOf(regularization));
        out.put("scopeModality", scopeModality.name());
        if (pinholeAU != null) {
            out.put("pinholeAU", pinholeAU);
        }
        if (sampleRI != null) {
            out.put("sampleRI", sampleRI);
        }
        return out;
    }

    public static DeconvPreset fromJson(String json) throws IOException {
        Object parsed = MiniJson.parse(json);
        if (!(parsed instanceof Map)) {
            throw new IOException("Preset JSON must be an object.");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) parsed;
        return fromJsonObject(map);
    }

    public static DeconvPreset fromJsonObject(Map<String, Object> map) throws IOException {
        if (map == null) {
            throw new IOException("Preset JSON object is required.");
        }
        String name = stringValue(map.get("name"));
        String description = stringValue(map.get("description"));
        String engineKey = stringValue(map.get("engineKey"));
        Algorithm algorithm = parseAlgorithm(stringValue(map.get("algorithm")));
        PsfModel psfModel = parsePsfModel(stringValue(map.get("psfModel")));
        Integer iterations = intValue(map.get("iterations"));
        Double regularization = doubleValue(map.get("regularization"));
        ScopeModality modality = parseScopeModality(stringValue(map.get("scopeModality")));
        Double pinholeAU = doubleValue(map.get("pinholeAU"));
        Double sampleRI = doubleValue(map.get("sampleRI"));

        try {
            return new DeconvPreset(
                    name,
                    description,
                    engineKey,
                    algorithm,
                    psfModel,
                    iterations == null ? 15 : iterations.intValue(),
                    regularization == null ? 0.01 : regularization.doubleValue(),
                    modality,
                    pinholeAU,
                    sampleRI
            );
        } catch (IllegalArgumentException e) {
            throw new IOException("Malformed deconvolution preset '" + name + "': " + e.getMessage(), e);
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof DeconvPreset)) return false;
        DeconvPreset that = (DeconvPreset) other;
        return iterations == that.iterations
                && Double.doubleToLongBits(regularization) == Double.doubleToLongBits(that.regularization)
                && name.equals(that.name)
                && equalsNullable(description, that.description)
                && engineKey.equals(that.engineKey)
                && algorithm == that.algorithm
                && psfModel == that.psfModel
                && scopeModality == that.scopeModality
                && equalsNullable(pinholeAU, that.pinholeAU)
                && equalsNullable(sampleRI, that.sampleRI);
    }

    @Override
    public int hashCode() {
        int result = name.hashCode();
        result = 31 * result + (description == null ? 0 : description.hashCode());
        result = 31 * result + engineKey.hashCode();
        result = 31 * result + algorithm.hashCode();
        result = 31 * result + psfModel.hashCode();
        result = 31 * result + iterations;
        long regularizationBits = Double.doubleToLongBits(regularization);
        result = 31 * result + (int) (regularizationBits ^ (regularizationBits >>> 32));
        result = 31 * result + scopeModality.hashCode();
        result = 31 * result + (pinholeAU == null ? 0 : pinholeAU.hashCode());
        result = 31 * result + (sampleRI == null ? 0 : sampleRI.hashCode());
        return result;
    }

    @Override
    public String toString() {
        return "DeconvPreset{"
                + "name='" + name + '\''
                + ", engineKey='" + engineKey + '\''
                + ", algorithm=" + algorithm
                + ", psfModel=" + psfModel
                + ", iterations=" + iterations
                + ", regularization=" + regularization
                + ", scopeModality=" + scopeModality
                + '}';
    }

    private static String requireText(String label, String value) {
        String trimmed = emptyToNull(value);
        if (trimmed == null) {
            throw new IllegalArgumentException(label + " is required.");
        }
        return trimmed;
    }

    private static Double requirePositiveFiniteOrNull(String label, Double value) {
        if (value == null) return null;
        if (Double.isNaN(value.doubleValue())
                || Double.isInfinite(value.doubleValue())
                || value.doubleValue() <= 0.0) {
            throw new IllegalArgumentException(label + " must be null or > 0.");
        }
        return value;
    }

    private static String emptyToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer intValue(Object value) throws IOException {
        if (value == null) return null;
        if (value instanceof Number) {
            return Integer.valueOf(((Number) value).intValue());
        }
        try {
            return Integer.valueOf(Integer.parseInt(String.valueOf(value).trim()));
        } catch (NumberFormatException e) {
            throw new IOException("Expected integer but found '" + value + "'.", e);
        }
    }

    private static Double doubleValue(Object value) throws IOException {
        if (value == null) return null;
        if (value instanceof Number) {
            return Double.valueOf(((Number) value).doubleValue());
        }
        try {
            return Double.valueOf(Double.parseDouble(String.valueOf(value).trim()));
        } catch (NumberFormatException e) {
            throw new IOException("Expected number but found '" + value + "'.", e);
        }
    }

    private static Algorithm parseAlgorithm(String raw) throws IOException {
        String normalized = emptyToNull(raw);
        if (normalized == null) {
            throw new IOException("algorithm is required.");
        }
        normalized = normalized.toUpperCase(Locale.ROOT).replace('-', '_');
        if ("RLTV".equals(normalized)) normalized = "RL_TV";
        try {
            return Algorithm.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new IOException("Unknown algorithm '" + raw + "'.", e);
        }
    }

    private static PsfModel parsePsfModel(String raw) throws IOException {
        String normalized = emptyToNull(raw);
        if (normalized == null) {
            throw new IOException("psfModel is required.");
        }
        normalized = normalized.toUpperCase(Locale.ROOT)
                .replace('&', '_')
                .replace('-', '_')
                .replace(' ', '_');
        if ("GIBSONLANNI".equals(normalized)) return PsfModel.GIBSON_LANNI;
        if ("BORNWOLF".equals(normalized)) return PsfModel.BORN_WOLF;
        if ("DOUGHERTY".equals(normalized) || "DOUGHERTYSTHEORETICAL".equals(normalized)) {
            return PsfModel.DOUGHERTY_THEORETICAL;
        }
        try {
            return PsfModel.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new IOException("Unknown psfModel '" + raw + "'.", e);
        }
    }

    private static ScopeModality parseScopeModality(String raw) throws IOException {
        String normalized = emptyToNull(raw);
        if (normalized == null) {
            throw new IOException("scopeModality is required.");
        }
        normalized = normalized.toUpperCase(Locale.ROOT)
                .replace(' ', '_')
                .replace('-', '_');
        if ("SPINNINGDISK".equals(normalized)) normalized = "SPINNING_DISK";
        try {
            return ScopeModality.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new IOException("Unknown scopeModality '" + raw + "'.", e);
        }
    }

    private static boolean equalsNullable(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }
}
