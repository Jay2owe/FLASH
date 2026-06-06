package flash.pipeline.representative;

/**
 * Mutable configuration holder for the representative image figure workflow.
 * Later representative-image-figure stages add concrete fields here.
 */
public class RepresentativeFigureConfig {
    public RepresentativeStatistic statistic = RepresentativeStatistic.QUICK;
    public RepresentativeStatLoader.ExistingResultOption existingResult = null;
    public RepresentativeStatTable statTable = new RepresentativeStatTable();
}
