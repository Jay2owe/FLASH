package flash.pipeline.ui.variations;

import ij.ImagePlus;
import ij.measure.ResultsTable;

public final class VariationResult {

    private final ParameterCombo combo;
    private final ImagePlus label;
    private final int nObjects;
    private final long durationMs;
    private final ResultsTable stats;
    private final Throwable error;

    public VariationResult(ParameterCombo combo,
                           ImagePlus label,
                           int nObjects,
                           long durationMs,
                           ResultsTable stats,
                           Throwable error) {
        if (combo == null) {
            throw new IllegalArgumentException("combo must not be null");
        }
        this.combo = combo;
        this.label = label;
        this.nObjects = nObjects;
        this.durationMs = durationMs;
        this.stats = stats;
        this.error = error;
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
}
