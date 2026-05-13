package flash.pipeline.image.variation;

import flash.pipeline.image.FilterMacroParser.OpType;
import flash.pipeline.image.dag.DagNode;
import flash.pipeline.image.variation.VariantAxis.AlternativeValue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Heuristic mapping from an operation type to reasonable filter-swap siblings.
 */
public final class FilterCompatibility {

    private FilterCompatibility() {}

    public static List<OpType> alternativesFor(OpType current) {
        if (current == null) return Collections.emptyList();
        switch (current) {
            case GAUSSIAN_BLUR:
            case MEDIAN:
            case MEAN:
            case UNSHARP_MASK:
                return Arrays.asList(
                        OpType.GAUSSIAN_BLUR,
                        OpType.MEDIAN,
                        OpType.MEAN,
                        OpType.UNSHARP_MASK);

            case MINIMUM:
            case MAXIMUM:
            case VARIANCE:
                return Arrays.asList(
                        OpType.MINIMUM,
                        OpType.MAXIMUM,
                        OpType.VARIANCE);

            case GAUSSIAN_BLUR_3D:
            case MEDIAN_3D:
            case MINIMUM_3D:
                return Arrays.asList(
                        OpType.GAUSSIAN_BLUR_3D,
                        OpType.MEDIAN_3D,
                        OpType.MINIMUM_3D);

            case DILATE:
            case ERODE:
            case OPEN:
            case CLOSE_:
                return Arrays.asList(
                        OpType.DILATE,
                        OpType.ERODE,
                        OpType.OPEN,
                        OpType.CLOSE_);

            case CONVERT_8BIT:
            case CONVERT_16BIT:
            case CONVERT_32BIT:
                return Arrays.asList(
                        OpType.CONVERT_8BIT,
                        OpType.CONVERT_16BIT,
                        OpType.CONVERT_32BIT);

            case SUBTRACT_BACKGROUND:
                return Collections.singletonList(OpType.SUBTRACT_BACKGROUND);
            case AUTO_LOCAL_THRESHOLD:
                return Collections.singletonList(OpType.AUTO_LOCAL_THRESHOLD);
            case ENHANCE_CONTRAST:
                return Collections.singletonList(OpType.ENHANCE_CONTRAST);
            case INVERT:
                return Collections.singletonList(OpType.INVERT);
            case FILL_HOLES:
                return Collections.singletonList(OpType.FILL_HOLES);
            case SKELETONIZE:
                return Collections.singletonList(OpType.SKELETONIZE);

            default:
                return Collections.emptyList();
        }
    }

    public static List<OpType> alternativesExcludingBaseline(OpType current) {
        List<OpType> all = alternativesFor(current);
        if (all.isEmpty()) return all;
        Set<OpType> out = new LinkedHashSet<OpType>(all);
        out.remove(current);
        return Collections.unmodifiableList(new ArrayList<OpType>(out));
    }

    public static boolean isSwappable(DagNode node) {
        return node != null
                && !node.disabled
                && !alternativesExcludingBaseline(node.type).isEmpty();
    }

    public static List<AlternativeValue> alternativeValuesExcludingBaseline(OpType current) {
        List<OpType> types = alternativesExcludingBaseline(current);
        if (types.isEmpty()) return Collections.emptyList();
        List<AlternativeValue> out = new ArrayList<AlternativeValue>(types.size());
        for (OpType type : types) {
            out.add(new AlternativeValue(displayName(type), type,
                    OpTypeParamRegistry.argsForDefaults(type)));
        }
        return Collections.unmodifiableList(out);
    }

    public static String displayName(OpType type) {
        if (type == null) return "";
        switch (type) {
            case GAUSSIAN_BLUR: return "Gaussian Blur";
            case SUBTRACT_BACKGROUND: return "Subtract Background";
            case MEDIAN: return "Median";
            case MEAN: return "Mean";
            case UNSHARP_MASK: return "Unsharp Mask";
            case MINIMUM: return "Minimum";
            case MAXIMUM: return "Maximum";
            case VARIANCE: return "Variance";
            case DILATE: return "Dilate";
            case ERODE: return "Erode";
            case OPEN: return "Open";
            case CLOSE_: return "Close";
            case FILL_HOLES: return "Fill Holes";
            case SKELETONIZE: return "Skeletonize";
            case INVERT: return "Invert";
            case ADD: return "Add";
            case SUBTRACT: return "Subtract";
            case MULTIPLY: return "Multiply";
            case DIVIDE: return "Divide";
            case AUTO_LOCAL_THRESHOLD: return "Auto Local Threshold";
            case CONVERT_8BIT: return "8-bit";
            case CONVERT_16BIT: return "16-bit";
            case CONVERT_32BIT: return "32-bit";
            case ENHANCE_CONTRAST: return "Enhance Contrast";
            case GAUSSIAN_BLUR_3D: return "Gaussian Blur 3D";
            case MEDIAN_3D: return "Median 3D";
            case MINIMUM_3D: return "Minimum 3D";
            default: return "";
        }
    }
}
