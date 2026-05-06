package flash.pipeline.stats;

/**
 * Immutable data carrier for one row of statistical output.
 * Maps directly to the columns in {@code Project_Statistics.csv}.
 */
public class StatisticRow {

    public final String metric;
    public final String test;
    public final double statistic;
    public final double pValue;
    public final String significant;
    public final String normalityResult;
    public final String group1;
    public final String group2;
    public final String pairwiseTest;
    public final double pairwiseStatistic;
    public final double pairwisePValue;
    public final double correctedPValue;
    public final String significance;
    public final String notes;
    public final boolean paired;
    public final String postHocMethod;

    private StatisticRow(Builder b) {
        this.metric = b.metric;
        this.test = b.test;
        this.statistic = b.statistic;
        this.pValue = b.pValue;
        this.significant = b.significant;
        this.normalityResult = b.normalityResult;
        this.group1 = b.group1;
        this.group2 = b.group2;
        this.pairwiseTest = b.pairwiseTest;
        this.pairwiseStatistic = b.pairwiseStatistic;
        this.pairwisePValue = b.pairwisePValue;
        this.correctedPValue = b.correctedPValue;
        this.significance = b.significance;
        this.notes = b.notes;
        this.paired = b.paired;
        this.postHocMethod = b.postHocMethod;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        String metric = "";
        String test = "";
        double statistic = Double.NaN;
        double pValue = Double.NaN;
        String significant = "";
        String normalityResult = "";
        String group1 = "";
        String group2 = "";
        String pairwiseTest = "";
        double pairwiseStatistic = Double.NaN;
        double pairwisePValue = Double.NaN;
        double correctedPValue = Double.NaN;
        String significance = "";
        String notes = "";
        boolean paired = false;
        String postHocMethod = "";

        public Builder metric(String v) { this.metric = v; return this; }
        public Builder test(String v) { this.test = v; return this; }
        public Builder statistic(double v) { this.statistic = v; return this; }
        public Builder pValue(double v) { this.pValue = v; return this; }
        public Builder significant(String v) { this.significant = v; return this; }
        public Builder normalityResult(String v) { this.normalityResult = v; return this; }
        public Builder group1(String v) { this.group1 = v; return this; }
        public Builder group2(String v) { this.group2 = v; return this; }
        public Builder pairwiseTest(String v) { this.pairwiseTest = v; return this; }
        public Builder pairwiseStatistic(double v) { this.pairwiseStatistic = v; return this; }
        public Builder pairwisePValue(double v) { this.pairwisePValue = v; return this; }
        public Builder correctedPValue(double v) { this.correctedPValue = v; return this; }
        public Builder significance(String v) { this.significance = v; return this; }
        public Builder notes(String v) { this.notes = v; return this; }
        public Builder paired(boolean v) { this.paired = v; return this; }
        public Builder postHocMethod(String v) { this.postHocMethod = v == null ? "" : v; return this; }

        public StatisticRow build() { return new StatisticRow(this); }
    }
}
