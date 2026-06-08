package flash.pipeline.intelligence.identity;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Order-independent token scanner for the lab's closed vocabulary. Detects:
 * <ul>
 *   <li><b>ZT/CT timepoint</b> ({@code ZT06}, {@code CT_12}, {@code zt8}) — the
 *       circadian grouping variable that the positional parser never captured —
 *       emitted on the {@code Timepoint} condition axis, canonicalised to
 *       {@code ZT6}/{@code CT12} (uppercase prefix, leading zeros stripped) so
 *       {@code ZT06}/{@code ZT_6}/{@code zt6} group together.</li>
 *   <li><b>Hemisphere</b> {@code LH}/{@code RH}.</li>
 *   <li><b>Region</b> from a lexicon ({@code SCN}, {@code PVN}, …).</li>
 *   <li><b>Genotype/condition</b> from a lexicon ({@code WT}, {@code KO},
 *       {@code NLGF}, {@code Syn}, {@code hAPP}, …) on the {@code Genotype} axis.</li>
 * </ul>
 * Date-like tokens ({@code 20231104}, {@code 2024-03-14}) are treated as batch
 * markers, never conditions. Does not guess the animal (left to grammar /
 * frequency / legacy strategies). Lexicons are injectable for user extension.
 */
public final class VocabularyStrategy implements IdentityStrategy {

    public static final String AXIS_TIMEPOINT = "Timepoint";
    public static final String AXIS_GENOTYPE = "Genotype";

    // Separator-aware boundaries: underscore counts as a separator (it is a
    // \w char, so plain \b would miss "x_ZT06"), so we exclude only alphanumerics.
    private static final Pattern ZT_CT = Pattern.compile(
            "(?i)(?<![A-Za-z0-9])([CZ]T)[\\s_-]?(\\d{1,2})(?![A-Za-z0-9])");
    private static final Pattern HEMI = Pattern.compile(
            "(?i)(?<![A-Za-z0-9])(LH|RH)(?![A-Za-z0-9])");
    private static final Pattern DATE_TOKEN = Pattern.compile(
            "\\d{8}|\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[\\s_.\\-]+");

    private static final Set<String> DEFAULT_REGIONS = set(
            "SCN", "PVN", "OC", "OX", "DMH", "VMH", "ARC", "Cortex", "Hippocampus");
    private static final Set<String> DEFAULT_GENOTYPES = set(
            "WT", "KO", "HET", "Cre", "flox", "NLGF", "Bmal1", "Per2", "Cry1", "Cry2",
            "Control", "Veh", "Vehicle", "Sham", "5xFAD", "Syn", "hAPP");

    private final Map<String, String> regionCanon;     // lower-case -> canonical
    private final Map<String, String> genotypeCanon;

    public VocabularyStrategy() {
        this(DEFAULT_REGIONS, DEFAULT_GENOTYPES);
    }

    public VocabularyStrategy(Collection<String> regions, Collection<String> genotypes) {
        this.regionCanon = canonMap(regions);
        this.genotypeCanon = canonMap(genotypes);
    }

    @Override
    public Map<SourceRecord, PartialIdentity> detect(List<SourceRecord> batch) {
        Map<SourceRecord, PartialIdentity> out = new LinkedHashMap<SourceRecord, PartialIdentity>();
        if (batch == null) return out;
        for (SourceRecord r : batch) {
            out.put(r, detectOne(r.seed));
        }
        return out;
    }

    /** Visible for testing. */
    PartialIdentity detectOne(String seed) {
        PartialIdentity p = new PartialIdentity();
        String s = seed == null ? "" : seed;

        Matcher zt = ZT_CT.matcher(s);
        if (zt.find()) {
            String canon = zt.group(1).toUpperCase(Locale.ROOT) + Integer.parseInt(zt.group(2));
            p.condition(AXIS_TIMEPOINT, canon, Confidence.HIGH, "vocabulary (ZT/CT timepoint)");
        }

        Matcher hemi = HEMI.matcher(s);
        if (hemi.find()) {
            p.hemisphere(hemi.group(1).toUpperCase(Locale.ROOT), Confidence.HIGH, "vocabulary (hemisphere)");
        }

        for (String tok : TOKEN_SPLIT.split(s)) {
            if (tok.isEmpty() || DATE_TOKEN.matcher(tok).matches()) continue;
            String lower = tok.toLowerCase(Locale.ROOT);
            if (p.region() == null && regionCanon.containsKey(lower)) {
                p.region(regionCanon.get(lower), Confidence.MEDIUM, "vocabulary (region)");
            }
            if (!p.conditions().containsKey("genotype") && genotypeCanon.containsKey(lower)) {
                p.condition(AXIS_GENOTYPE, genotypeCanon.get(lower), Confidence.MEDIUM, "vocabulary (genotype)");
            }
        }
        return p;
    }

    private static Set<String> set(String... values) {
        return new LinkedHashSet<String>(Arrays.asList(values));
    }

    private static Map<String, String> canonMap(Collection<String> values) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        if (values != null) {
            for (String v : values) {
                if (v != null && !v.trim().isEmpty()) {
                    m.put(v.toLowerCase(Locale.ROOT), v);
                }
            }
        }
        return m;
    }
}
