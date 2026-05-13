package flash.pipeline.image.variation;

import ij.ImagePlus;

/**
 * Outcome of executing one variant plan.
 */
public final class VariantResult {

    public final VariantPlan plan;
    public final ImagePlus output;
    public final Throwable error;
    public final long elapsedMillis;

    public VariantResult(VariantPlan plan, ImagePlus output, Throwable error, long elapsedMillis) {
        if (plan == null) throw new IllegalArgumentException("plan must not be null");
        this.plan = plan;
        this.output = output;
        this.error = error;
        this.elapsedMillis = elapsedMillis;
    }

    public boolean isSuccess() {
        return error == null && output != null;
    }
}
