package flash.pipeline.stats;

/**
 * Immutable carrier for one pairwise post-hoc result.
 * <p>
 * {@link #statistic} is {@code q} for {@link TukeyHSD} and {@code z} for
 * {@link DunnsTest}; {@link #pAdjusted} is the multiplicity-adjusted p-value
 * (Tukey HSD: studentised-range based; Dunn's: Bonferroni over k(k-1)/2).
 */
public final class PostHocRow {

    public final String groupA;
    public final String groupB;
    public final double statistic;
    public final double pAdjusted;

    public PostHocRow(String groupA, String groupB, double statistic, double pAdjusted) {
        this.groupA = groupA;
        this.groupB = groupB;
        this.statistic = statistic;
        this.pAdjusted = pAdjusted;
    }

    public String groupA() { return groupA; }
    public String groupB() { return groupB; }
    public double statistic() { return statistic; }
    public double pAdjusted() { return pAdjusted; }
}
