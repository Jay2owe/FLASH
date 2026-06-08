package flash.pipeline.intelligence.identity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Learns the grammar from the batch itself. Tokenises every record's identity
 * core and, per positional column, measures cardinality across the whole batch:
 * the highest-cardinality column (one value per file) is the <b>animal</b>; a
 * low-cardinality column that splits the batch into a few groups is a
 * <b>condition</b>; constant columns (experiment / region) are left to other
 * strategies. This is the principled fix for "animal vs condition both look like
 * short codes" — cardinality is the disambiguator.
 *
 * <p>No-op below {@link #MIN_BATCH} records (cardinality is meaningless on a tiny
 * batch). Emits MEDIUM confidence — it is inference, so grammar/vocabulary
 * override it. Columns are aligned from the left up to the shortest token count;
 * messier ragged naming is left to the grammar.
 */
public final class FrequencyStrategy implements IdentityStrategy {

    public static final String AXIS_CONDITION = "Condition";
    static final int MIN_BATCH = 6;

    private static final Pattern SPLIT = Pattern.compile("[\\s_.\\-]+");

    @Override
    public Map<SourceRecord, PartialIdentity> detect(List<SourceRecord> batch) {
        Map<SourceRecord, PartialIdentity> out = new LinkedHashMap<SourceRecord, PartialIdentity>();
        if (batch == null) return out;
        for (SourceRecord r : batch) {
            out.put(r, new PartialIdentity());
        }
        if (batch.size() < MIN_BATCH) return out;

        List<String[]> toks = new ArrayList<String[]>();
        int minCount = Integer.MAX_VALUE;
        for (SourceRecord r : batch) {
            String[] t = tokenise(r.seed);
            toks.add(t);
            if (t.length < minCount) minCount = t.length;
        }
        if (minCount == Integer.MAX_VALUE || minCount == 0) return out;

        int n = batch.size();
        int[] distinct = new int[minCount];
        for (int p = 0; p < minCount; p++) {
            Set<String> vals = new HashSet<String>();
            for (String[] t : toks) vals.add(t[p]);
            distinct[p] = vals.size();
        }

        // Animal = the column that varies the most, and genuinely varies.
        int animalPos = -1;
        int animalCard = -1;
        for (int p = 0; p < minCount; p++) {
            if (distinct[p] > animalCard) {
                animalCard = distinct[p];
                animalPos = p;
            }
        }
        if (animalPos < 0 || animalCard < Math.max(2, (int) Math.ceil(n * 0.6))) {
            animalPos = -1;
        }

        // Condition = the smallest-cardinality grouping column (2..n/2), not the animal.
        int condMax = Math.max(2, n / 2);
        int condPos = -1;
        int condCard = Integer.MAX_VALUE;
        for (int p = 0; p < minCount; p++) {
            if (p == animalPos) continue;
            int d = distinct[p];
            if (d >= 2 && d <= condMax && d < condCard) {
                condCard = d;
                condPos = p;
            }
        }

        for (int i = 0; i < batch.size(); i++) {
            String[] t = toks.get(i);
            PartialIdentity p = out.get(batch.get(i));
            if (animalPos >= 0 && animalPos < t.length) {
                p.animal(t[animalPos], Confidence.MEDIUM, "cross-batch frequency (varying token)");
            }
            if (condPos >= 0 && condPos < t.length) {
                p.condition(AXIS_CONDITION, t[condPos], Confidence.MEDIUM,
                        "cross-batch frequency (repeated token)");
            }
        }
        return out;
    }

    /** Tokenise the identity core: the part after " - " (series name), minus extension. */
    private static String[] tokenise(String seed) {
        String s = seed == null ? "" : seed;
        int sep = s.lastIndexOf(" - ");
        if (sep >= 0) s = s.substring(sep + 3);
        int dot = s.lastIndexOf('.');
        if (dot > 0) s = s.substring(0, dot);
        s = s.trim();
        if (s.isEmpty()) return new String[0];
        List<String> keep = new ArrayList<String>();
        for (String x : SPLIT.split(s)) {
            if (!x.isEmpty()) keep.add(x);
        }
        return keep.toArray(new String[0]);
    }
}
