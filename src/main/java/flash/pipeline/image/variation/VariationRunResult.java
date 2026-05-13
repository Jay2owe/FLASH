package flash.pipeline.image.variation;

import ij.ImagePlus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Source image shown in the variation grid plus the executed variant outputs.
 */
public final class VariationRunResult {

    public final ImagePlus displaySource;
    public final List<VariantResult> results;

    public VariationRunResult(ImagePlus displaySource, List<VariantResult> results) {
        if (displaySource == null) {
            throw new IllegalArgumentException("displaySource must not be null");
        }
        this.displaySource = displaySource;
        if (results == null || results.isEmpty()) {
            this.results = Collections.emptyList();
        } else {
            this.results = Collections.unmodifiableList(new ArrayList<VariantResult>(results));
        }
    }
}
