package flash.pipeline.naming;

import ij.IJ;

import java.io.File;
import java.util.regex.Pattern;

/**
 * Filename parser supporting Jamie's naming convention with a graceful
 * fallback for arbitrary filenames.
 */
public final class ImageNameParser {

    private static final Pattern IGNORED_SERIES_NAME = Pattern.compile(
            "(?i).*(preview|thumbnail|thumb).*");

    /** Bio-Formats container/series separator (always wrapped in spaces). */
    private static final String BF_SERIES_SEPARATOR = " - ";

    private ImageNameParser() {}

    public static String stripExtension(String name) {
        if (name == null) return null;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /**
     * Sanitise a string for use as a filesystem path component.
     * Replaces characters that are illegal on Windows/macOS/Linux
     * with underscores and collapses runs of whitespace.
     */
    static String sanitiseForFilesystem(String s) {
        if (s == null || s.isEmpty()) return s;
        // Replace illegal/problematic chars: \ / : * ? " < > |
        return s.replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Returns the Bio-Formats internal series name from a title like
     * {@code file.lif - Mouse3_LH_CA1}. If the title does not look like a
     * Bio-Formats multi-series title, returns the trimmed input unchanged.
     */
    public static String extractBioFormatsSeriesName(String imageTitleOrFilename) {
        if (imageTitleOrFilename == null) return "";
        String trimmed = imageTitleOrFilename.trim();
        if (trimmed.isEmpty()) return "";

        int titledSep = trimmed.lastIndexOf(BF_SERIES_SEPARATOR);
        if (titledSep >= 0 && titledSep + BF_SERIES_SEPARATOR.length() < trimmed.length()) {
            return trimmed.substring(titledSep + BF_SERIES_SEPARATOR.length()).trim();
        }
        return trimmed;
    }

    /** True when the supplied series name looks like a Leica preview/thumbnail entry. */
    public static boolean isPreviewSeriesName(String seriesName) {
        if (seriesName == null) return false;
        String normalized = extractBioFormatsSeriesName(seriesName);
        return !normalized.isEmpty() && IGNORED_SERIES_NAME.matcher(normalized).matches();
    }

    /**
     * Builds a user-facing multi-series label such as
     * {@code experiment.lif :: Mouse3_LH_CA1}.
     */
    public static String buildMultiSeriesDisplayLabel(String containerName, String seriesName) {
        String container = containerName == null ? "" : containerName.trim();
        String series = extractBioFormatsSeriesName(seriesName);
        if (series.isEmpty()) return container;
        if (container.isEmpty() || container.equals(series)) return series;
        return container + " :: " + series;
    }

    private static NameParts parseStructured(String imageTitleOrFilename) {
        if (imageTitleOrFilename == null) {
            return new NameParts("", "", "", "", false, null);
        }

        // Bio-Formats container titles look like "file.lif - Mouse3_LH_CA1".
        // Anchor on " - " (always wrapped in spaces) so a stray hyphen in the
        // experiment name (e.g. "my-cool-exp.lif") does not throw the parse.
        // We must split on " - " BEFORE stripping any extension, otherwise
        // stripExtension on the whole title would consume the series suffix
        // (the last '.' lives in the container's ".lif" extension).
        String containerLhs;
        String seriesPart = null;
        int sepIdx = imageTitleOrFilename.lastIndexOf(BF_SERIES_SEPARATOR);
        if (sepIdx >= 0) {
            containerLhs = imageTitleOrFilename.substring(0, sepIdx);
            seriesPart = imageTitleOrFilename.substring(sepIdx + BF_SERIES_SEPARATOR.length()).trim();
            seriesPart = stripExtension(seriesPart);
        } else {
            containerLhs = imageTitleOrFilename;
        }
        String lhs = stripExtension(containerLhs);
        if (lhs == null) lhs = "";

        boolean hasBfSeries = seriesPart != null && !seriesPart.isEmpty();
        String exp;
        String rhs;
        int hyphen = lhs.lastIndexOf('-');
        if (hyphen >= 0) {
            exp = lhs.substring(0, hyphen).trim();
            rhs = lhs.substring(hyphen + 1).trim();
        } else if (hasBfSeries) {
            // Bio-Formats container without an experiment hyphen — treat the
            // whole container as the experiment, defer animal/hemi/region to
            // the series part below.
            exp = lhs.trim();
            rhs = "";
        } else {
            // No experiment hyphen and no Bio-Formats series — the convention
            // (Experiment-Animal_Hemisphere_Region) is not satisfied.
            return new NameParts("", "", "", "", false, imageTitleOrFilename);
        }

        // Bio-Formats series name takes precedence as the animal/region token.
        // The LHS animal slot may still contain useful info (the on-disk filename
        // animal id) when the series name is generic, but the convention places
        // the animal/hemisphere/region in the series name.
        String tokenSource = hasBfSeries ? seriesPart : rhs;

        String[] toks = tokenSource.split("_");
        String animal = "";
        String hemi = "";
        String region = "";
        String condition = "";

        if (toks.length >= 2 && isKnownHemisphere(toks[toks.length - 1])) {
            animal = joinTokens(toks, 0, toks.length - 1);
            hemi = toks[toks.length - 1].trim();
        } else if (toks.length >= 3 && isKnownHemisphere(toks[toks.length - 2])) {
            animal = joinTokens(toks, 0, toks.length - 2);
            hemi = toks[toks.length - 2].trim();
            region = toks[toks.length - 1].trim();
        } else if (toks.length >= 4 && isKnownHemisphere(toks[toks.length - 3])) {
            animal = joinTokens(toks, 0, toks.length - 3);
            hemi = toks[toks.length - 3].trim();
            region = toks[toks.length - 2].trim();
            condition = toks[toks.length - 1].trim();
        }

        return new NameParts(exp, animal, hemi, region, condition, true, imageTitleOrFilename);
    }

    /**
     * Best-effort condition guess from a source file's immediate parent folder.
     * Returns the parent folder name trimmed; empty string when the file is null,
     * has no parent, or sits directly at a drive/root.
     *
     * <p>Common lab pattern this catches: {@code .../WT/animal-03.lif},
     * {@code .../KO/animal-04.lif}. Used by the project builder dialog when the
     * filename convention itself does not embed a condition token.
     */
    public static String guessConditionFromParentFolder(File source) {
        if (source == null) return "";
        File parent = source.getParentFile();
        if (parent == null) return "";
        String name = parent.getName();
        return name == null ? "" : name.trim();
    }

    /**
     * Try strict convention parsing first; fall back to using the whole
     * filename (minus extension) as the animal identifier when the
     * convention is not matched.
     * <p>
     * A strict match is recognised when the result has a non-empty
     * {@code animal} <b>and</b> a known hemisphere value (LH / RH).
     */
    public static NameParts parse(String imageTitleOrFilename) {
        if (imageTitleOrFilename == null) {
            return new NameParts("", "", "", "", false, null);
        }

        NameParts strict = parseStructured(imageTitleOrFilename);

        // Accept strict result when we got at least an animal name and a
        // recognised hemisphere — that's the minimum for the convention.
        if (!strict.animal.isEmpty() && strict.hasKnownHemisphere()) {
            return strict;
        }

        // Fallback: use the internal series name when Bio-Formats exposed one,
        // otherwise use the full title. This keeps multi-series LIF entries
        // distinct instead of lumping everything under the outer container name.
        String fallbackSeed = extractBioFormatsSeriesName(imageTitleOrFilename);
        if (fallbackSeed.isEmpty()) {
            fallbackSeed = imageTitleOrFilename;
        }
        // Strip extension before sanitising so filenames like "Image.tif" do
        // not leak ".tif" into the sample identifier (related LOW finding).
        fallbackSeed = stripExtension(fallbackSeed);

        String fallbackName = sanitiseForFilesystem(fallbackSeed);
        if (fallbackName == null || fallbackName.isEmpty()) {
            String stripped = stripExtension(imageTitleOrFilename);
            fallbackName = stripped == null ? "" : stripped.trim();
        }

        IJ.log("  [INFO] Filename does not match expected convention "
                + "(Experiment-Animal_Hemisphere_Region). "
                + "Using \"" + fallbackName + "\" as the sample identifier.");

        return new NameParts("", fallbackName, "", "", false, imageTitleOrFilename);
    }

    private static boolean isKnownHemisphere(String value) {
        return "LH".equalsIgnoreCase(value) || "RH".equalsIgnoreCase(value);
    }

    private static String joinTokens(String[] tokens, int startInclusive, int endExclusive) {
        if (tokens == null || startInclusive >= endExclusive) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = startInclusive; i < endExclusive; i++) {
            String token = tokens[i] == null ? "" : tokens[i].trim();
            if (token.isEmpty()) continue;
            if (sb.length() > 0) sb.append('_');
            sb.append(token);
        }
        return sb.toString();
    }
}
