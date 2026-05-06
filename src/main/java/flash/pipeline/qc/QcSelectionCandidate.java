package flash.pipeline.qc;

/**
 * Metadata used by QC image selection for one .lif series.
 */
public final class QcSelectionCandidate {

    public final int seriesIndex;
    public final int seriesNumber;
    public final String seriesName;
    public final String animalName;
    public final String conditionName;

    public QcSelectionCandidate(int seriesIndex, String seriesName,
                                String animalName, String conditionName) {
        this.seriesIndex = seriesIndex;
        this.seriesNumber = seriesIndex + 1;
        this.seriesName = seriesName == null ? "" : seriesName;
        this.animalName = animalName == null ? "" : animalName;
        this.conditionName = conditionName == null ? "" : conditionName;
    }

    @Override
    public String toString() {
        return "Series " + seriesNumber + " [" + conditionName + "] " + seriesName;
    }
}
