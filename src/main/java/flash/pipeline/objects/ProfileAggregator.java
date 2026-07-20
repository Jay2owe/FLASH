package flash.pipeline.objects;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Accumulates per-object normalized profile curves into per-bin mean ± SEM across objects, grouped
 * by (source channel, partner channel, profile type, grouping key e.g. image/condition). NaN bins
 * (objects whose box left a bin empty) are skipped per bin, so each bin's {@code n} is the number of
 * contributing objects. Feeds the aggregated CSVs (Stage 4) and figures (Stage 5).
 *
 * <p>Not thread-safe by itself; call {@link #add} from a single consumer thread, or one aggregator
 * per worker then {@link #merge}.
 */
public final class ProfileAggregator {

    public static final String RADIAL = "Radial";
    public static final String MARGINAL_X = "MarginalX";
    public static final String MARGINAL_Y = "MarginalY";
    public static final String MARGINAL_Z = "MarginalZ";
    public static final String PC_MAJOR = "PrincipalMajor";
    public static final String PC_MINOR = "PrincipalMinor";
    public static final String PC_THIRD = "PrincipalThird";
    public static final String ANGULAR = "Angular";

    /** Group-key delimiter (a token that cannot appear in channel names). */
    private static final String SEP = "<|>";

    private final Map<String, BinStats> groups = new LinkedHashMap<String, BinStats>();

    /** Add one object's normalized curve to its group; null/empty curves are ignored. */
    public void add(String source, String partner, String profileType, String groupKey, double[] curve) {
        if (curve == null || curve.length == 0) return;
        String key = source + SEP + partner + SEP + profileType + SEP + groupKey;
        BinStats bs = groups.get(key);
        if (bs == null) {
            bs = new BinStats(source, partner, profileType, groupKey, curve.length);
            groups.put(key, bs);
        }
        bs.add(curve);
    }

    /** Convenience: add every profile of one partner result for a group key. */
    public void addAll(ObjectProfileResult res, String groupKey) {
        if (res == null) return;
        for (ObjectProfileResult.PartnerProfiles pf : res.byPartner.values()) {
            String s = res.sourceChannel, p = pf.partnerChannel;
            add(s, p, RADIAL, groupKey, pf.radialNorm);
            add(s, p, MARGINAL_X, groupKey, pf.marginalXNorm);
            add(s, p, MARGINAL_Y, groupKey, pf.marginalYNorm);
            add(s, p, MARGINAL_Z, groupKey, pf.marginalZNorm);
            add(s, p, PC_MAJOR, groupKey, pf.pcMajorNorm);
            add(s, p, PC_MINOR, groupKey, pf.pcMinorNorm);
            add(s, p, PC_THIRD, groupKey, pf.pcThirdNorm);
            add(s, p, ANGULAR, groupKey, pf.angularNorm);
        }
    }

    /** Merge another aggregator's groups into this one (for per-worker accumulation). */
    public void merge(ProfileAggregator other) {
        if (other == null) return;
        for (Map.Entry<String, BinStats> e : other.groups.entrySet()) {
            BinStats mine = groups.get(e.getKey());
            if (mine == null) groups.put(e.getKey(), e.getValue());
            else mine.merge(e.getValue());
        }
    }

    public List<AggregatedProfile> results() {
        List<AggregatedProfile> out = new ArrayList<AggregatedProfile>(groups.size());
        for (BinStats bs : groups.values()) out.add(bs.finish());
        return out;
    }

    /** Normalized x-axis coordinate of bin {@code i} for a given profile type and bin count. */
    public static double axisAt(String profileType, int i, int length) {
        double t = (i + 0.5) / length;
        if (RADIAL.equals(profileType)) return t;                  // 0..1
        if (ANGULAR.equals(profileType)) return t * 360.0;         // degrees
        return -1.0 + 2.0 * t;                                     // marginal/principal: -1..1
    }

    /** Aggregated mean ± SEM curve for one (source, partner, profileType, group). */
    public static final class AggregatedProfile {
        public final String source, partner, profileType, groupKey;
        public final double[] x, mean, sem;
        public final int[] n;
        public AggregatedProfile(String source, String partner, String profileType, String groupKey,
                                 double[] x, double[] mean, double[] sem, int[] n) {
            this.source = source; this.partner = partner; this.profileType = profileType;
            this.groupKey = groupKey; this.x = x; this.mean = mean; this.sem = sem; this.n = n;
        }
    }

    private static final class BinStats {
        final String source, partner, profileType, groupKey;
        final double[] sum, sumsq;
        final int[] n;
        BinStats(String source, String partner, String profileType, String groupKey, int len) {
            this.source = source; this.partner = partner; this.profileType = profileType;
            this.groupKey = groupKey;
            sum = new double[len]; sumsq = new double[len]; n = new int[len];
        }
        void add(double[] curve) {
            int len = Math.min(curve.length, sum.length);
            for (int i = 0; i < len; i++) {
                double v = curve[i];
                if (Double.isNaN(v)) continue;
                sum[i] += v; sumsq[i] += v * v; n[i]++;
            }
        }
        void merge(BinStats o) {
            for (int i = 0; i < sum.length && i < o.sum.length; i++) {
                sum[i] += o.sum[i]; sumsq[i] += o.sumsq[i]; n[i] += o.n[i];
            }
        }
        AggregatedProfile finish() {
            int len = sum.length;
            double[] x = new double[len], mean = new double[len], sem = new double[len];
            for (int i = 0; i < len; i++) {
                x[i] = axisAt(profileType, i, len);
                if (n[i] > 0) {
                    mean[i] = sum[i] / n[i];
                    double var = Math.max(0, sumsq[i] / n[i] - mean[i] * mean[i]);
                    sem[i] = n[i] > 1 ? Math.sqrt(var) / Math.sqrt(n[i]) : 0;
                } else {
                    mean[i] = Double.NaN; sem[i] = Double.NaN;
                }
            }
            return new AggregatedProfile(source, partner, profileType, groupKey, x, mean, sem, n);
        }
    }
}
