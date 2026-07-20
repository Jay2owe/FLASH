package flash.pipeline.stats;

/**
 * Immutable data carrier for one row of statistical output.
 * Maps directly to the columns in {@code Statistics.csv}.
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
    public final String inferentialUnit;
    public final int group1NAnimals;
    public final int group2NAnimals;
    public final int totalNAnimals;
    public final double group1Mean;
    public final double group2Mean;
    public final String effectSizeType;
    public final double effectSize;
    public final double effectCI95Low;
    public final double effectCI95High;

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
        this.inferentialUnit = b.inferentialUnit;
        this.group1NAnimals = b.group1NAnimals;
        this.group2NAnimals = b.group2NAnimals;
        this.totalNAnimals = b.totalNAnimals;
        this.group1Mean = b.group1Mean;
        this.group2Mean = b.group2Mean;
        this.effectSizeType = b.effectSizeType;
        this.effectSize = b.effectSize;
        this.effectCI95Low = b.effectCI95Low;
        this.effectCI95High = b.effectCI95High;
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
        String inferentialUnit = "";
        int group1NAnimals = 0;
        int group2NAnimals = 0;
        int totalNAnimals = 0;
        double group1Mean = Double.NaN;
        double group2Mean = Double.NaN;
        String effectSizeType = "";
        double effectSize = Double.NaN;
        double effectCI95Low = Double.NaN;
        double effectCI95High = Double.NaN;

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
        public Builder inferentialUnit(String v) { this.inferentialUnit = v == null ? "" : v; return this; }
        public Builder group1NAnimals(int v) { this.group1NAnimals = Math.max(0, v); return this; }
        public Builder group2NAnimals(int v) { this.group2NAnimals = Math.max(0, v); return this; }
        public Builder totalNAnimals(int v) { this.totalNAnimals = Math.max(0, v); return this; }
        public Builder group1Mean(double v) { this.group1Mean = v; return this; }
        public Builder group2Mean(double v) { this.group2Mean = v; return this; }
        public Builder effectSizeType(String v) { this.effectSizeType = v == null ? "" : v; return this; }
        public Builder effectSize(double v) { this.effectSize = v; return this; }
        public Builder effectCI95Low(double v) { this.effectCI95Low = v; return this; }
        public Builder effectCI95High(double v) { this.effectCI95High = v; return this; }

        public StatisticRow build() { return new StatisticRow(this); }
    }
}
