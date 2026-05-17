package flash.pipeline.segmentation;

import ij.ImagePlus;
import ij.measure.ResultsTable;
import sc.fiji.oc3dplus.api.OC3DPlus;
import sc.fiji.oc3dplus.api.OC3DPlusParameters;
import sc.fiji.oc3dplus.api.OC3DPlusResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class EnhancedClassicalRunner {
    public static final String OBJECT_STATS_PROPERTY = "flash.enhanced3doc.objectStats";
    public static final String PREDICATE_COUNTS_PROPERTY = "flash.enhanced3doc.predicateCounts";
    public static final String PREDICATE_LABELS_PROPERTY = "flash.enhanced3doc.predicateLabels";

    public ImagePlus run(ImagePlus channelImage, EnhancedClassicalParameters params) {
        if (channelImage == null) {
            throw new IllegalArgumentException("channelImage must not be null");
        }
        EnhancedClassicalParameters safe = params == null
                ? new EnhancedClassicalParameters(0, 0, Integer.MAX_VALUE,
                Collections.<MorphPredicate>emptyList(), null, null)
                : params;

        OC3DPlusResult result = OC3DPlus.count(channelImage, toPlusParameters(safe));
        ImagePlus labelImage = result == null ? null : result.labelImage();
        if (labelImage == null) {
            return null;
        }

        labelImage.setTitle("Enhanced Classical label image");
        labelImage.setProperty(OBJECT_STATS_PROPERTY,
                result.statistics() == null ? new ResultsTable() : result.statistics());
        labelImage.setProperty(PREDICATE_COUNTS_PROPERTY,
                result.survivingPerFilter());
        labelImage.setProperty(PREDICATE_LABELS_PROPERTY,
                result.filterLabels());
        return labelImage;
    }

    public static ResultsTable statsProperty(ImagePlus labelImage) {
        Object property = labelImage == null ? null : labelImage.getProperty(OBJECT_STATS_PROPERTY);
        return property instanceof ResultsTable ? (ResultsTable) property : null;
    }

    public static ResultsTable appendReferencedMorphColumns(ImagePlus labelImage,
                                                            ImagePlus intensityImage,
                                                            ResultsTable stats,
                                                            List<MorphPredicate> predicates,
                                                            EnhancedClassicalParameters.WarningSink warningSink) {
        return sc.fiji.oc3dplus.engine.OC3DPlusRunner.appendReferencedMorphColumns(
                labelImage,
                intensityImage,
                stats,
                toPlusPredicates(predicates),
                toPlusWarningSink(warningSink));
    }

    private static OC3DPlusParameters toPlusParameters(EnhancedClassicalParameters params) {
        return new OC3DPlusParameters(
                params.threshold,
                params.minSize,
                params.maxSize,
                false,
                toPlusPredicates(params.morphPredicates),
                params.intensityImage,
                toPlusWarningSink(params.warningSink));
    }

    private static List<sc.fiji.oc3dplus.api.MorphPredicate> toPlusPredicates(
            List<MorphPredicate> predicates) {
        if (predicates == null || predicates.isEmpty()) {
            return Collections.emptyList();
        }
        List<sc.fiji.oc3dplus.api.MorphPredicate> out =
                new ArrayList<sc.fiji.oc3dplus.api.MorphPredicate>(predicates.size());
        for (int i = 0; i < predicates.size(); i++) {
            MorphPredicate predicate = predicates.get(i);
            if (predicate == null) continue;
            out.add(new sc.fiji.oc3dplus.api.MorphPredicate(
                    predicate.featureName,
                    toPlusOperator(predicate.op),
                    predicate.value));
        }
        return out;
    }

    private static sc.fiji.oc3dplus.api.MorphPredicate.Operator toPlusOperator(MorphPredicate.Operator op) {
        if (op == MorphPredicate.Operator.LE) return sc.fiji.oc3dplus.api.MorphPredicate.Operator.LE;
        if (op == MorphPredicate.Operator.GT) return sc.fiji.oc3dplus.api.MorphPredicate.Operator.GT;
        if (op == MorphPredicate.Operator.LT) return sc.fiji.oc3dplus.api.MorphPredicate.Operator.LT;
        return sc.fiji.oc3dplus.api.MorphPredicate.Operator.GE;
    }

    private static OC3DPlusParameters.WarningSink toPlusWarningSink(
            final EnhancedClassicalParameters.WarningSink warningSink) {
        return new OC3DPlusParameters.WarningSink() {
            @Override public void warn(String message) {
                if (warningSink != null) {
                    warningSink.warn(message);
                }
            }
        };
    }
}
