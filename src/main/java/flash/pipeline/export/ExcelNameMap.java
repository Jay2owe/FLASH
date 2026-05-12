package flash.pipeline.export;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps raw CSV column names (e.g. {@code GFAP_Count_permm3}) to
 * human-readable Excel labels and method descriptions.
 * <p>
 * Port of the Python {@code IF_NAME_MAP} in {@code export.py}.
 */
public final class ExcelNameMap {

    static final int EXCEL_MAX_SHEET = 31;
    private static final double COLOC_THRESHOLD = 30.0;

    private static final String OBJ_COUNTER =
            "Using 3D Object Counter, confocal image stacks were segmented "
            + "into individual 3D objects based on a threshold.\n";
    private static final String PER_VOL = "normalized per volume of tissue";
    private static final String QUANTIFIED = "was then quantified";

    /** A single mapping rule: pattern text, label template, description template. */
    private static final class Rule {
        final Pattern regex;
        final String label;
        final String desc;

        Rule(String patternText, String label, String desc) {
            // Split on <ab> / <ab2> placeholders, escape literal parts,
            // then reassemble with named regex groups
            String AB2_TOKEN = "<ab2>";
            String AB_TOKEN = "<ab>";
            String GROUP_AB2 = "(?<ab2>[A-Za-z0-9_-]+)";
            String GROUP_AB  = "(?<ab>[A-Za-z0-9_-]+)";

            // Replace <ab2> first (longer token) to avoid partial match
            String re = patternText;
            re = re.replace(AB2_TOKEN, "\u0000AB2\u0000");
            re = re.replace(AB_TOKEN,  "\u0000AB\u0000");

            // Escape each literal segment between placeholders
            String[] parts = re.split("\u0000");
            StringBuilder sb = new StringBuilder();
            for (String part : parts) {
                if (part.equals("AB2")) {
                    sb.append(GROUP_AB2);
                } else if (part.equals("AB")) {
                    sb.append(GROUP_AB);
                } else if (!part.isEmpty()) {
                    sb.append(Pattern.quote(part));
                }
            }

            this.regex = Pattern.compile("^" + sb.toString() + "$");
            this.label = label;
            this.desc = desc;
        }
    }

    private static final List<Rule> RULES = new ArrayList<Rule>();

    static {
        // Ordered longest-pattern-first so more specific patterns match first.
        // Patterns with <ab2> are longer and more specific.
        addRule("<ab>_Pearson_t_<ab2>Mean",
                "<ab>-<ab2> Costes Pearson",
                "For each <ab>/<ab2> channel pair, Pearson correlation was recomputed only on voxels above the Costes auto-thresholds.");
        addRule("<ab>_Pearson_<ab2>Mean",
                "<ab>-<ab2> Pearson r",
                "For each <ab>/<ab2> channel pair, raw Pearson correlation was computed across the analysis volume.");
        addRule("<ab>_Manders_M1_<ab2>Mean",
                "<ab> Manders M1 vs <ab2>",
                "For each <ab>/<ab2> channel pair, Manders M1 measured the fraction of <ab> signal colocated with Costes-thresholded <ab2> signal.");
        addRule("<ab>_Manders_M2_<ab2>Mean",
                "<ab2> Manders M2 vs <ab>",
                "For each <ab>/<ab2> channel pair, Manders M2 measured the fraction of <ab2> signal colocated with Costes-thresholded <ab> signal.");
        addRule("<ab>_Costes_Ta_<ab2>Mean",
                "<ab>-<ab2> Costes Ta",
                "The Costes auto-threshold for the <ab> channel in each <ab>/<ab2> channel pair.");
        addRule("<ab>_Costes_Tb_<ab2>Mean",
                "<ab>-<ab2> Costes Tb",
                "The Costes auto-threshold for the <ab2> channel in each <ab>/<ab2> channel pair.");
        addRule("<ab>_Costes_p_<ab2>Mean",
                "<ab>-<ab2> Costes p",
                "The Costes randomization p-value for the <ab>/<ab2> channel pair, using seeded 5x5x1 block shuffling.");

        addRule("<ab>_VolColocCount<ab2>%",
                "% <ab2>+<ab> per <ab>",
                OBJ_COUNTER + multicoloc() + "The % of segmented <ab> objects with a greater than "
                + fmt(COLOC_THRESHOLD) + "% overlap by <ab2> objects " + QUANTIFIED + ".");
        addRule("<ab>_VolColocCount<ab2>",
                "<ab2>+<ab> Count",
                OBJ_COUNTER + multicoloc() + "The number of segmented <ab> objects with a greater than "
                + fmt(COLOC_THRESHOLD) + "% overlap by <ab2> objects " + QUANTIFIED + ".");
        addRule("<ab>_VolColoc<ab2>Mean",
                "<ab2> Overlap per <ab>",
                OBJ_COUNTER + multicoloc() + "The mean % voxel overlap of each segmented <ab> object by <ab2> " + QUANTIFIED + ".");
        addRule("<ab>_DistToClosest_<ab2>Mean",
                "<ab> Mean Nearest <ab2>",
                OBJ_COUNTER + "Using euclidean distance calculations and the objects' centre of masses, the closest <ab2> object was identified and the distance calculated.\n"
                + "The mean distance for each <ab> object " + QUANTIFIED + ".");
        addRule("<ab>_VolContains_<ab2>Mean",
                "<ab> Mean # Internal <ab2>",
                OBJ_COUNTER + multicoloc() + "The mean number of colocalising <ab2> objects contained per <ab> object was then quantified.");
        addRule("<ab>_VolNonColocCount<ab2>",
                "<ab2>-<ab> Count",
                OBJ_COUNTER + multicoloc() + "The number of segmented <ab> objects with less than "
                + fmt(COLOC_THRESHOLD) + "% overlap by <ab2> objects " + QUANTIFIED + ".");

        // Single-marker rules
        addRule("<ab>_Count",
                "<ab> Count",
                OBJ_COUNTER + "The number of segmented <ab> objects was summed.");
        addRule("<ab>_IntDenTotal",
                "<ab> IntDen Total (A.U.)",
                OBJ_COUNTER + "The integrated density across all segmented <ab> objects was summed.");
        addRule("<ab>_VolumeTotal",
                "<ab> Volume Total (\u00B5m\u00B3)",
                OBJ_COUNTER + "The volume of all segmented <ab> objects was summed.");
        addRule("<ab>_SurfaceTotal",
                "<ab> SA Total (\u00B5m\u00B2)",
                OBJ_COUNTER + "The surface area of all segmented <ab> objects was summed.");
        addRule("<ab>_IntDenMean",
                "<ab> Mean IntDen (A.U.)",
                OBJ_COUNTER + "The mean integrated density per segmented <ab> object " + QUANTIFIED + ".");
        addRule("<ab>_VolumeMean",
                "<ab> Mean Volume (\u00B5m\u00B3)",
                OBJ_COUNTER + "The mean volume per segmented <ab> object " + QUANTIFIED + ".");
        addRule("<ab>_SurfaceMean",
                "<ab> Mean SA (\u00B5m\u00B2)",
                OBJ_COUNTER + "The mean surface area per segmented <ab> object " + QUANTIFIED + ".");
        addRule("<ab>_SAtoVolumeRatioMean",
                "<ab> Mean SA-Vol",
                OBJ_COUNTER + "The mean surface-area-to-volume ratio per segmented <ab> object " + QUANTIFIED + ".");
        addRule("<ab>_MeanIntDenMean",
                "<ab> Mean Pixel IntDen",
                OBJ_COUNTER + "The mean integrated density of each segmented <ab> object was then normalized by the number of <ab> objects.");
        addRule("<ab>_RawYMMean",
                "<ab> Mean YM (\u00B5ms)",
                OBJ_COUNTER + "The mean Y-coordinate of <ab> objects was then quantified in physical units.");
        addRule("<ab>_RawXMMean",
                "<ab> Mean XM",
                OBJ_COUNTER + "The mean X-coordinate of <ab> objects was then quantified in physical units.");
        addRule("<ab>_ROI_IntDenMean",
                "<ab> ROI IntDen (A.U.)",
                "The total integrated density of <ab> signal within the ROI was quantified and then adjusted by the volume of the ROI.");
        addRule("<ab>_ROI_IntDen_binarizedMean",
                "<ab> ROI Binarized IntDen (A.U.)",
                "The total integrated density of thresholded <ab>-positive signal within the ROI was quantified and then averaged per section.");
        addRule("<ab>_ROI_IntDen_UnfilteredMean",
                "<ab> ROI Unfiltered IntDen (A.U.)",
                "The total integrated density of unfiltered <ab> signal within the ROI was quantified and then averaged per section.");
        addRule("<ab>_ROI_%AreaMean",
                "<ab> %Area",
                "The percentage of ROI area occupied by thresholded <ab>-positive signal.");
        addRule("<ab>_ROI_%Area_binarizedMean",
                "<ab> Binarized %Area",
                "The percentage of ROI area occupied by thresholded <ab>-positive signal was quantified from the binarized mask.");
        addRule("<ab>_DistToVentricle",
                "<ab> Mean Ventricle Distance",
                OBJ_COUNTER + "Using euclidean distance calculations and the centre of mass of <ab> objects, the distance to the ventricular boundary was calculated.\n"
                + "The mean distance of each <ab> object to the ventricle " + QUANTIFIED + ".");

        // 3D Morphometric raw features
        addRule("<ab>_" + "Morph_" + "Sphericity" + "Mean",
                "<ab> Mean Sphericity",
                OBJ_COUNTER + "The mean 3D sphericity (corrected) per <ab> object. Range 0-1, 1 = sphere.");
        addRule("<ab>_Morph_CompactnessMean",
                "<ab> Mean Compactness",
                OBJ_COUNTER + "The mean 3D compactness (corrected) per <ab> object. Range 0-1. Compactness = Sphericity\u00B3.");
        addRule("<ab>_Morph_ElongationMean",
                "<ab> Mean Elongation",
                OBJ_COUNTER + "The mean elongation (R1/R2 ellipsoid radii) per <ab> object. \u22651, 1 = equiaxial.");
        addRule("<ab>_Morph_FlatnessMean",
                "<ab> Mean Flatness",
                OBJ_COUNTER + "The mean flatness (R2/R3 ellipsoid radii) per <ab> object. \u22651, 1 = equiaxial.");
        addRule("<ab>_Morph_SparenessMean",
                "<ab> Mean Spareness",
                OBJ_COUNTER + "Mean fraction of fitted ellipsoid envelope filled by <ab> object. Range 0-1.");
        addRule("<ab>_Morph_Feret3D_umMean",
                "<ab> Mean 3D Feret (\u00B5m)",
                OBJ_COUNTER + "The mean 3D maximum caliper distance per <ab> object.");
        addRule("<ab>_Morph_MajorRadius_umMean",
                "<ab> Mean Major Radius (\u00B5m)",
                OBJ_COUNTER + "The mean major ellipsoid semi-axis radius per <ab> object.");
        addRule("<ab>_Morph_DistCenter_Mean_umMean",
                "<ab> Mean Radial Dist (\u00B5m)",
                OBJ_COUNTER + "The mean of centroid-to-surface distance means per <ab> object.");
        addRule("<ab>_Morph_DistCenter_SD_umMean",
                "<ab> Mean Radial SD (\u00B5m)",
                OBJ_COUNTER + "The mean of centroid-to-surface distance SDs per <ab> object.");

        // Tier 1 composite shape indices
        addRule("<ab>_Morph_RIMean",
                "<ab> Mean Ramification Idx",
                OBJ_COUNTER + "Mean Ramification Index per <ab> object. RI = 1/Sphericity. \u22651, 1 = sphere. "
                + "Higher values indicate more complex surface morphology.");
        addRule("<ab>_Morph_SRIMean",
                "<ab> Mean Surface Roughness",
                OBJ_COUNTER + "Mean Surface Roughness Index (CV of centroid-to-surface distances) per <ab> object. "
                + "Dimensionless, scale-independent.");
        addRule("<ab>_Morph_PBMean",
                "<ab> Mean Process Burden",
                OBJ_COUNTER + "Mean Process Burden per <ab> object. PB = 1 - Spareness. "
                + "Range 0-1. 0 = compact, 1 = mostly empty envelope.");
        addRule("<ab>_Morph_MPMean",
                "<ab> Mean Morph. Polarity",
                OBJ_COUNTER + "Mean Morphological Polarity per <ab> object. "
                + "Range 0-1: 0 = disc/oblate, 0.5 = isotropic, 1 = rod/prolate.");
        addRule("<ab>_Morph_VSDMean",
                "<ab> Mean Vol-Span Discr.",
                OBJ_COUNTER + "Mean Volume-Span Discrepancy per <ab> object. "
                + "VSD = log10(Feret\u00B3 / Volume). Higher = more extended.");

        // Tier 2 population composite indices
        addRule("<ab>_Morph_CMSMean",
                "<ab> Mean Composite Score",
                OBJ_COUNTER + "Mean Composite Morphological Score per <ab> object. "
                + "Population-normalised weighted sum of shape, roughness, burden, volume. Range 0-1.");
        addRule("<ab>_Morph_IMDIMean",
                "<ab> Mean Dissociation Idx",
                OBJ_COUNTER + "Mean Intensity-Morphology Dissociation Index per <ab> object. "
                + "Higher values = intensity and morphological complexity disagree.");
        addRule("<ab>_Morph_SMSDMean",
                "<ab> Mean Shape Distance",
                OBJ_COUNTER + "Mean Shape Moment Signature Distance per <ab> object. "
                + "Morphological deviation from population mean shape in 3D moment space.");

        // Tier 3 spatial-morphometric indices
        addRule("<ab>_Morph_TDRMean",
                "<ab> Mean Territorial Dom.",
                OBJ_COUNTER + "Mean Territorial Dominance Ratio per <ab> object. "
                + "1 = fair share, >1 = sparse region, <1 = crowded cluster.");
        addRule("<ab>_Morph_FEV_MagMean",
                "<ab> Mean Feret Eccentric.",
                OBJ_COUNTER + "Mean Feret Eccentricity Vector magnitude per <ab> object. "
                + "Feret/(2\u00D7MajorRadius). ~1 = smooth elongation, >>1 = dominant extension.");

        // Sort rules so longer regex patterns match first (more specific)
        RULES.sort((a, b) -> Integer.compare(b.regex.pattern().length(), a.regex.pattern().length()));
    }

    private static String multicoloc() {
        return "Using 3D MultiColoc, the co-occurence colocalization between "
                + "segmented <ab> and <ab2> objects was then quantified.\n";
    }

    private static void addRule(String pattern, String label, String desc) {
        RULES.add(new Rule(pattern, label, desc));
    }

    private static String fmt(double v) {
        if (v == Math.floor(v)) return String.valueOf((int) v);
        return String.valueOf(v);
    }

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Convert a raw column name to a (label, description) pair.
     * The raw column may end with {@code _permm3} which is stripped before matching
     * and noted in the description.
     *
     * @return String[2]: {label, description}, or null if no rule matches
     */
    public static String[] convert(String colName) {
        // Strip _permm3 suffix for matching, then re-apply to label
        boolean perMm3 = colName.endsWith("_permm3");
        String matchName = perMm3 ? colName.substring(0, colName.length() - "_permm3".length()) : colName;

        for (Rule rule : RULES) {
            Matcher m = rule.regex.matcher(matchName);
            if (m.matches()) {
                String label = rule.label;
                String desc = rule.desc;

                // Replace <ab> and <ab2> placeholders with captured values
                try {
                    String ab = m.group("ab");
                    label = label.replace("<ab>", ab);
                    desc = desc.replace("<ab>", ab);
                } catch (IllegalArgumentException ignored) {}
                try {
                    String ab2 = m.group("ab2");
                    label = label.replace("<ab2>", ab2);
                    desc = desc.replace("<ab2>", ab2);
                } catch (IllegalArgumentException ignored) {}

                if (perMm3) {
                    label = label + " per mm\u00B3";
                    desc = desc + " Values are normalized per mm\u00B3 of tissue volume.";
                }

                // Truncate label for Excel sheet name compatibility
                if (label.length() > EXCEL_MAX_SHEET) {
                    label = label.substring(0, EXCEL_MAX_SHEET);
                }

                return new String[]{label, desc};
            }
        }
        String[] dynamicIntensity = convertDynamicIntensity(matchName);
        if (dynamicIntensity != null) {
            if (perMm3) {
                dynamicIntensity[0] = dynamicIntensity[0] + " per mm\u00B3";
                dynamicIntensity[1] = dynamicIntensity[1] + " Values are normalized per mm\u00B3 of tissue volume.";
            }
            if (dynamicIntensity[0].length() > EXCEL_MAX_SHEET) {
                dynamicIntensity[0] = dynamicIntensity[0].substring(0, EXCEL_MAX_SHEET);
            }
            return dynamicIntensity;
        }
        return null;
    }

    /**
     * Extract the primary marker name from a column name.
     * E.g. "GFAP_Count_permm3" → "GFAP"
     */
    public static String extractMarker(String colName) {
        String matchName = colName.endsWith("_permm3")
                ? colName.substring(0, colName.length() - "_permm3".length())
                : colName;
        for (Rule rule : RULES) {
            Matcher m = rule.regex.matcher(matchName);
            if (m.matches()) {
                try {
                    return m.group("ab");
                } catch (IllegalArgumentException ignored) {}
            }
        }
        Matcher intensity = Pattern.compile("^(.+)_ROI_(Intensity_.+)Mean$").matcher(matchName);
        if (intensity.matches()) {
            return intensity.group(1);
        }
        Matcher pair = Pattern.compile("^(.+)_ROI_(.+_.+_.+)Mean$").matcher(matchName);
        if (pair.matches()) {
            return pair.group(1);
        }
        return null;
    }

    private static String[] convertDynamicIntensity(String colName) {
        Matcher sameChannel = Pattern.compile("^(.+)_ROI_(Intensity_.+)Mean$").matcher(colName);
        if (sameChannel.matches()) {
            String marker = sameChannel.group(1);
            String metric = humanizeIntensityMetric(sameChannel.group(2));
            return new String[] {
                    marker + " ROI " + metric,
                    "Pixel-level spatial-intensity metric " + sameChannel.group(2)
                            + " was averaged for " + marker + " within each ROI."
            };
        }

        Matcher pair = Pattern.compile("^(.+)_ROI_(.+_.+_.+)Mean$").matcher(colName);
        if (pair.matches()) {
            String marker = pair.group(1);
            String metric = pair.group(2);
            return new String[] {
                    marker + " ROI Pair " + humanizePairMetric(metric),
                    "Cross-channel pixel-level spatial-intensity metric " + metric
                            + " was averaged within each ROI."
            };
        }
        return null;
    }

    private static String humanizeIntensityMetric(String metric) {
        String text = metric == null ? "" : metric;
        if (text.startsWith("Intensity_")) {
            text = text.substring("Intensity_".length());
        }
        if (text.endsWith("_binarized")) {
            text = text.substring(0, text.length() - "_binarized".length()) + " Binarized";
        }
        return splitCamelAndUnderscore(text);
    }

    private static String humanizePairMetric(String metric) {
        String text = metric == null ? "" : metric;
        if (text.endsWith("_binarized")) {
            text = text.substring(0, text.length() - "_binarized".length()) + " Binarized";
        }
        return splitCamelAndUnderscore(text);
    }

    private static String splitCamelAndUnderscore(String text) {
        String spaced = text.replace('_', ' ')
                .replaceAll("([a-z])([A-Z])", "$1 $2")
                .replaceAll("([A-Za-z])([0-9])", "$1 $2")
                .replaceAll("([0-9])([A-Za-z])", "$1 $2")
                .trim();
        return spaced.isEmpty() ? "Intensity Metric" : spaced;
    }

    /**
     * Determine whether a column represents an ROI-based analysis.
     */
    public static boolean isRoiColumn(String colName) {
        return colName.contains("_ROI_");
    }

    /**
     * Generate a safe Excel sheet name: replace forbidden characters,
     * truncate to 31 chars, and ensure uniqueness.
     */
    public static String safeSheetName(String name, java.util.Set<String> used) {
        String base = name.replaceAll("[\\[\\]:*?/\\\\]", "-").trim();
        if (base.length() > EXCEL_MAX_SHEET) {
            base = base.substring(0, EXCEL_MAX_SHEET);
        }
        String out = base;
        int n = 1;
        while (used.contains(out) || out.isEmpty()) {
            String suffix = "_" + n;
            int maxBase = EXCEL_MAX_SHEET - suffix.length();
            out = (base.length() > maxBase ? base.substring(0, maxBase) : base) + suffix;
            n++;
        }
        used.add(out);
        return out;
    }

    private ExcelNameMap() {}
}
