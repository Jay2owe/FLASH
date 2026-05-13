package flash.pipeline.image.variation;

import flash.pipeline.image.dag.DagIR;
import flash.pipeline.image.dag.DagMutations;
import flash.pipeline.image.variation.VariantAxis.AlternativeValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Enumerates variant plans from a baseline DAG and variation axes.
 */
public final class VariantSampler {

    public static final int CARTESIAN_HARD_CAP = 16;

    private VariantSampler() {}

    public static List<VariantPlan> ofat(DagIR baseline, List<VariantAxis> axes, int maxVariants) {
        requireBaseline(baseline);
        if (maxVariants < 1) {
            throw new IllegalArgumentException("maxVariants must be >= 1");
        }
        List<VariantPlan> out = new ArrayList<VariantPlan>();
        out.add(new VariantPlan("baseline", baseline, Collections.<String, String>emptyMap()));
        if (axes == null) return out;

        for (VariantAxis axis : axes) {
            for (AlternativeValue alt : axis.alternatives) {
                if (out.size() >= maxVariants) return out;
                DagIR mutated = mutate(baseline, axis, alt);
                Map<String, String> delta = new LinkedHashMap<String, String>();
                delta.put(axis.nodeId, alt.label);
                out.add(new VariantPlan(alt.label, mutated, delta));
            }
        }
        return out;
    }

    public static List<VariantPlan> cartesian(DagIR baseline, List<VariantAxis> axes,
                                              int maxVariants) {
        requireBaseline(baseline);
        if (maxVariants > CARTESIAN_HARD_CAP) {
            throw new IllegalArgumentException(
                    "maxVariants " + maxVariants + " exceeds hard cap "
                            + CARTESIAN_HARD_CAP);
        }
        if (maxVariants < 1) {
            throw new IllegalArgumentException("maxVariants must be >= 1");
        }
        if (axes == null || axes.isEmpty()) {
            return Collections.emptyList();
        }

        long total = 1L;
        for (VariantAxis axis : axes) {
            int n = axis.alternatives.size();
            if (n < 1) {
                throw new IllegalArgumentException(
                        "axis for nodeId=" + axis.nodeId + " has no alternatives");
            }
            total *= n;
            if (total > maxVariants) {
                throw new IllegalArgumentException(
                        "cartesian product " + total + " exceeds maxVariants "
                                + maxVariants);
            }
        }

        List<VariantPlan> out = new ArrayList<VariantPlan>((int) total);
        int[] indices = new int[axes.size()];
        for (long i = 0; i < total; i++) {
            DagIR current = baseline;
            Map<String, String> delta = new LinkedHashMap<String, String>();
            StringBuilder label = new StringBuilder();
            for (int a = 0; a < axes.size(); a++) {
                VariantAxis axis = axes.get(a);
                AlternativeValue alt = axis.alternatives.get(indices[a]);
                current = mutate(current, axis, alt);
                delta.put(axis.nodeId, alt.label);
                if (label.length() > 0) label.append(" / ");
                label.append(alt.label);
            }
            out.add(new VariantPlan(label.toString(), current, delta));

            for (int a = axes.size() - 1; a >= 0; a--) {
                indices[a]++;
                if (indices[a] < axes.get(a).alternatives.size()) break;
                indices[a] = 0;
            }
        }
        return out;
    }

    private static DagIR mutate(DagIR dag, VariantAxis axis, AlternativeValue alt) {
        if (axis.kind == VariantAxis.Kind.PARAM_SWEEP) {
            return DagMutations.withNodeArgs(dag, axis.nodeId, alt.args);
        }
        if (alt.type == null) {
            throw new IllegalArgumentException(
                    "FILTER_SWAP alternative for nodeId=" + axis.nodeId
                            + " has null type");
        }
        return DagMutations.withNodeSubstituted(dag, axis.nodeId, alt.type, alt.args);
    }

    private static void requireBaseline(DagIR baseline) {
        if (baseline == null) throw new IllegalArgumentException("baseline must not be null");
    }
}
