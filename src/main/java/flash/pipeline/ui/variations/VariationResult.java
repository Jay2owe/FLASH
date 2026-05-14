package flash.pipeline.ui.variations;

import ij.ImagePlus;
import ij.measure.ResultsTable;

public final class VariationResult {

    public enum Kind {
        SEGMENTATION,
        FILTER
    }

    private final Kind kind;
    private final ParameterCombo combo;
    private final ImagePlus label;
    private final ImagePlus previewImage;
    private final int nObjects;
    private final long durationMs;
    private final ResultsTable stats;
    private final Throwable error;
    private final double meanNeighbourIou;
    private final int[] histogram;
    private final double snr;
    private final double bgSigma;

    public VariationResult(ParameterCombo combo,
                           ImagePlus label,
                           int nObjects,
                           long durationMs,
                           ResultsTable stats,
                           Throwable error) {
        this(combo, label, nObjects, durationMs, stats, error, Double.NaN);
    }

    public VariationResult(ParameterCombo combo,
                           ImagePlus label,
                           int nObjects,
                           long durationMs,
                           ResultsTable stats,
                           Throwable error,
                           double meanNeighbourIou) {
        this(Kind.SEGMENTATION, combo, label, null, nObjects, durationMs, stats,
                error, meanNeighbourIou, null, Double.NaN, Double.NaN);
    }

    private VariationResult(Kind kind,
                            ParameterCombo combo,
                            ImagePlus label,
                            ImagePlus previewImage,
                            int nObjects,
                            long durationMs,
                            ResultsTable stats,
                            Throwable error,
                            double meanNeighbourIou,
                            int[] histogram,
                            double snr,
                            double bgSigma) {
        if (combo == null) {
            throw new IllegalArgumentException("combo must not be null");
        }
        this.kind = kind == null ? Kind.SEGMENTATION : kind;
        this.combo = combo;
        this.label = label;
        this.previewImage = previewImage;
        this.nObjects = nObjects;
        this.durationMs = durationMs;
        this.stats = stats;
        this.error = error;
        this.meanNeighbourIou = meanNeighbourIou;
        this.histogram = histogram == null ? null : histogram.clone();
        this.snr = snr;
        this.bgSigma = bgSigma;
    }

    public static VariationResult success(ParameterCombo combo,
                                          ImagePlus label,
                                          int nObjects,
                                          long durationMs,
                                          ResultsTable stats) {
        return new VariationResult(combo, label, nObjects, durationMs, stats, null);
    }

    public static VariationResult failure(ParameterCombo combo, Throwable error) {
        return new VariationResult(combo, null, 0, 0L, null, error);
    }

    public static VariationResult filterSuccess(ParameterCombo combo,
                                                ImagePlus filteredImage,
                                                long durationMs,
                                                int[] histogram,
                                                double snr,
                                                double bgSigma) {
        return new VariationResult(Kind.FILTER, combo, filteredImage, filteredImage,
                0, durationMs, null, null, Double.NaN, histogram, snr, bgSigma);
    }

    public Kind kind() {
        return kind;
    }

    public Kind getKind() {
        return kind;
    }

    public ParameterCombo combo() {
        return combo;
    }

    public ParameterCombo getCombo() {
        return combo;
    }

    public ImagePlus label() {
        return label;
    }

    public ImagePlus getLabel() {
        return label;
    }

    public ImagePlus previewImage() {
        return previewImage;
    }

    public ImagePlus getPreviewImage() {
        return previewImage;
    }

    public int nObjects() {
        return nObjects;
    }

    public int getNObjects() {
        return nObjects;
    }

    public long durationMs() {
        return durationMs;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public ResultsTable stats() {
        return stats;
    }

    public ResultsTable getStats() {
        return stats;
    }

    public Throwable error() {
        return error;
    }

    public Throwable getError() {
        return error;
    }

    public boolean hasError() {
        return error != null;
    }

    public double meanNeighbourIou() {
        return meanNeighbourIou;
    }

    public double getMeanNeighbourIou() {
        return meanNeighbourIou;
    }

    public VariationResult withMeanNeighbourIou(double meanNeighbourIou) {
        return new VariationResult(kind, combo, label, previewImage, nObjects,
                durationMs, stats, error, meanNeighbourIou, histogram, snr, bgSigma);
    }

    public int[] histogram() {
        return histogram == null ? null : histogram.clone();
    }

    public int[] getHistogram() {
        return histogram();
    }

    public double snr() {
        return snr;
    }

    public double getSnr() {
        return snr;
    }

    public double bgSigma() {
        return bgSigma;
    }

    public double getBgSigma() {
        return bgSigma;
    }
}
