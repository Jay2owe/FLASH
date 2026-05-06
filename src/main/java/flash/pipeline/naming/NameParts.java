package flash.pipeline.naming;

import java.util.Locale;
import java.util.Objects;

/**
 * Parsed components of Jamie macro naming convention:
 * "Experiment - Animal_Hemisphere_Region"
 * <p>
 * When the filename does not follow the convention, {@link #strictMatch}
 * is {@code false} and {@link #animal} holds the full filename (minus
 * extension) so that downstream code always has a usable identifier.
 */
public final class NameParts {
    public final String experiment;
    public final String animal;
    /** Expected "LH" or "RH"; empty string when naming convention was not matched. */
    public final String hemisphere;
    public final String region;
    /** {@code true} when the filename matched the expected naming convention. */
    public final boolean strictMatch;
    /** The raw input passed to the parser; used as a deterministic seed for empty-suffix fallback. */
    private final String originalInput;

    public NameParts(String experiment, String animal, String hemisphere, String region) {
        this(experiment, animal, hemisphere, region, true, null);
    }

    public NameParts(String experiment, String animal, String hemisphere, String region, boolean strictMatch) {
        this(experiment, animal, hemisphere, region, strictMatch, null);
    }

    public NameParts(String experiment, String animal, String hemisphere, String region,
                     boolean strictMatch, String originalInput) {
        this.experiment = experiment;
        this.animal = animal;
        this.hemisphere = hemisphere;
        this.region = region;
        this.strictMatch = strictMatch;
        this.originalInput = originalInput;
    }

    /** Whether the hemisphere value is a known orientation tag. */
    public boolean hasKnownHemisphere() {
        return "LH".equalsIgnoreCase(hemisphere) || "RH".equalsIgnoreCase(hemisphere);
    }

    /**
     * Returns a compact display label built from the non-empty parts,
     * e.g. "animal_LH_Cortex" or just "my_image" for free-form names.
     */
    public String displayLabel() {
        if (!strictMatch) return animal;
        StringBuilder sb = new StringBuilder();
        if (animal != null && !animal.isEmpty()) sb.append(animal);
        if (hemisphere != null && !hemisphere.isEmpty()) {
            if (sb.length() > 0) sb.append('_');
            sb.append(hemisphere);
        }
        if (region != null && !region.isEmpty()) {
            if (sb.length() > 0) sb.append('_');
            sb.append(region);
        }
        return sb.length() > 0 ? sb.toString() : animal;
    }

    /**
     * Combined hemisphere + region suffix for file naming, e.g. "LH_Cortex".
     * Returns empty string when both are blank, avoiding dangling underscores.
     */
    public String hemiRegionSuffix() {
        boolean hasH = hemisphere != null && !hemisphere.isEmpty();
        boolean hasR = region != null && !region.isEmpty();
        if (!hasH && !hasR) return "";
        if (hasH && hasR) return hemisphere + "_" + region;
        return hasH ? hemisphere : region;
    }

    /**
     * Raw region token for CSV exports.
     * Returns the parsed image-name suffix only when the filename matched the
     * expected naming convention; otherwise returns blank.
     */
    public String csvRegion() {
        if (!strictMatch) return "";
        return trimToEmpty(region);
    }

    /**
     * Descriptive region label for CSV output.
     * Uses the supplied region base when present, otherwise falls back to the parsed
     * image region, then disambiguates by section index.
     *
     * <p>If the base already ends in trailing digits matching {@code sectionIndex} the
     * base is returned unchanged; if it ends in trailing digits that differ, an
     * underscore-separated index is appended so the existing number is not extended;
     * otherwise the index is appended directly.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code SCN + 1 -> SCN1}</li>
     *   <li>{@code SCN1 + 1 -> SCN1}</li>
     *   <li>{@code PVN + 2 -> PVN2}</li>
     *   <li>{@code PVN1 + 2 -> PVN1_2}</li>
     * </ul>
     */
    public String analysisRegionLabel(String regionBase, int sectionIndex) {
        String base = trimToEmpty(regionBase);
        if (base.isEmpty()) {
            base = trimToEmpty(region);
        }

        if (!base.isEmpty() && sectionIndex > 0) {
            int i = base.length();
            while (i > 0 && Character.isDigit(base.charAt(i - 1))) i--;
            String trailingDigits = base.substring(i);
            if (trailingDigits.isEmpty()) {
                return base + sectionIndex;
            }
            try {
                int parsed = Integer.parseInt(trailingDigits);
                if (parsed == sectionIndex) return base;
                return base + "_" + sectionIndex;
            } catch (NumberFormatException nfe) {
                return base + sectionIndex;
            }
        }

        if (!base.isEmpty()) return base;
        return sectionIndex > 0 ? "Section" + sectionIndex : "";
    }

    /**
     * Suffix guaranteed to be non-empty and unique per image, suitable for
     * output filenames so that files from different images within the same
     * folder never collide.
     * <ul>
     *   <li>Strict mode: returns {@link #hemiRegionSuffix()} (e.g. "LH_Cortex")</li>
     *   <li>Fallback mode: returns the {@link #animal} identifier
     *       (which is the sanitised image title)</li>
     *   <li>Last-resort fallback: a deterministic {@code image_<hex>} hash
     *       derived from the original parser input (or {@code image_unknown}
     *       when no input was supplied).</li>
     * </ul>
     */
    public String fileSuffix() {
        String hr = hemiRegionSuffix();
        if (!hr.isEmpty()) return hr;
        if (animal != null && !animal.isEmpty()) return animal;
        // Deterministic fallback so two parses of the same input agree.
        if (originalInput == null) return "image_unknown";
        int hash = Math.abs(Objects.hashCode(originalInput));
        return "image_" + Integer.toHexString(hash).toLowerCase(Locale.ROOT);
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
