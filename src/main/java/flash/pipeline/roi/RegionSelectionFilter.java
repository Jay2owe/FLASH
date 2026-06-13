package flash.pipeline.roi;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Applies user-facing region include/exclude filters to ROI-set selections.
 *
 * <p>Region-scoped ROI zips are exposed by their base name (for example
 * {@code SCN ROIs.zip -> SCN}), so matching is intentionally tolerant of case,
 * spacing, underscores, dashes, and an optional {@code ROIs} suffix.
 */
public final class RegionSelectionFilter {

    private RegionSelectionFilter() {
    }

    public static boolean hasFilter(List<String> includeRegions, List<String> excludeRegions) {
        return !normalizedSet(includeRegions).isEmpty() || !normalizedSet(excludeRegions).isEmpty();
    }

    public static void apply(List<String> includeRegions,
                             List<String> excludeRegions,
                             String[] regionNames,
                             boolean[] selected) {
        if (regionNames == null || selected == null) {
            return;
        }

        Set<String> include = normalizedSet(includeRegions);
        Set<String> exclude = normalizedSet(excludeRegions);

        if (!include.isEmpty()) {
            for (int i = 0; i < regionNames.length && i < selected.length; i++) {
                selected[i] = include.contains(normalize(regionNames[i]));
            }
        }
        if (!exclude.isEmpty()) {
            for (int i = 0; i < regionNames.length && i < selected.length; i++) {
                if (exclude.contains(normalize(regionNames[i]))) {
                    selected[i] = false;
                }
            }
        }
    }

    public static String normalize(String value) {
        String s = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (s.endsWith(".zip")) {
            s = s.substring(0, s.length() - 4).trim();
        }
        if (s.endsWith(" rois")) {
            s = s.substring(0, s.length() - 5).trim();
        } else if (s.endsWith("_rois") || s.endsWith("-rois")) {
            s = s.substring(0, s.length() - 5).trim();
        } else if (s.endsWith("rois") && s.length() > 4) {
            s = s.substring(0, s.length() - 4).trim();
        }
        s = s.replace('_', ' ').replace('-', ' ');
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    private static Set<String> normalizedSet(List<String> values) {
        Set<String> out = new LinkedHashSet<String>();
        if (values == null) {
            return out;
        }
        for (String value : values) {
            String normalized = normalize(value);
            if (!normalized.isEmpty()) {
                out.add(normalized);
            }
        }
        return out;
    }
}
