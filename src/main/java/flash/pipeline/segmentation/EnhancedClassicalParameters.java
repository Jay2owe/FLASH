package flash.pipeline.segmentation;

import ij.ImagePlus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class EnhancedClassicalParameters {
    public interface WarningSink {
        void warn(String message);
    }

    public static final WarningSink NO_OP_WARNING_SINK = new WarningSink() {
        @Override public void warn(String message) {
        }
    };

    public final int threshold;
    public final int minSize;
    public final int maxSize;
    public final List<MorphPredicate> morphPredicates;
    public final ImagePlus intensityImage;
    public final WarningSink warningSink;

    public EnhancedClassicalParameters(int threshold,
                                       int minSize,
                                       int maxSize,
                                       List<MorphPredicate> morphPredicates,
                                       ImagePlus intensityImage,
                                       WarningSink warningSink) {
        this.threshold = threshold;
        this.minSize = Math.max(0, minSize);
        this.maxSize = Math.max(this.minSize, maxSize);
        this.morphPredicates = immutableCopy(morphPredicates);
        this.intensityImage = intensityImage;
        this.warningSink = warningSink == null ? NO_OP_WARNING_SINK : warningSink;
    }

    public static EnhancedClassicalParameters fromMethod(SegmentationMethod method,
                                                         ImagePlus intensityImage,
                                                         WarningSink warningSink) {
        SegmentationMethod safe = method == null
                ? SegmentationMethod.classical("classical")
                : method;
        return new EnhancedClassicalParameters(
                (int) Math.round(SegmentationMethod.threshold(safe)),
                SegmentationMethod.minSize(safe),
                SegmentationMethod.maxSize(safe),
                SegmentationMethod.morphPredicates(safe),
                intensityImage,
                warningSink);
    }

    private static List<MorphPredicate> immutableCopy(List<MorphPredicate> source) {
        if (source == null || source.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<MorphPredicate>(source));
    }
}
