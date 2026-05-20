package flash.pipeline.cellpose;

public final class CellposeWorkerRequest {

    private final String id;
    private final double diameter;
    private final double flowThreshold;
    private final double cellprobThreshold;
    private final boolean dumpCellprob;

    public CellposeWorkerRequest(String id,
                                 double diameter,
                                 double flowThreshold,
                                 double cellprobThreshold) {
        this(id, diameter, flowThreshold, cellprobThreshold, false);
    }

    public CellposeWorkerRequest(String id,
                                 double diameter,
                                 double flowThreshold,
                                 double cellprobThreshold,
                                 boolean dumpCellprob) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("id must not be empty");
        }
        this.id = id;
        this.diameter = sanitizeNonNegative(diameter);
        this.flowThreshold = flowThreshold;
        this.cellprobThreshold = cellprobThreshold;
        this.dumpCellprob = dumpCellprob;
    }

    public String id() {
        return id;
    }

    public String getId() {
        return id;
    }

    public double diameter() {
        return diameter;
    }

    public double getDiameter() {
        return diameter;
    }

    public double flowThreshold() {
        return flowThreshold;
    }

    public double getFlowThreshold() {
        return flowThreshold;
    }

    public double cellprobThreshold() {
        return cellprobThreshold;
    }

    public double getCellprobThreshold() {
        return cellprobThreshold;
    }

    public boolean dumpCellprob() {
        return dumpCellprob;
    }

    public boolean isDumpCellprob() {
        return dumpCellprob;
    }

    public boolean getDumpCellprob() {
        return dumpCellprob;
    }

    private static double sanitizeNonNegative(double value) {
        if (!Double.isFinite(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, value);
    }
}
