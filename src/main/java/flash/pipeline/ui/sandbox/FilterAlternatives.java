package flash.pipeline.ui.sandbox;

import flash.pipeline.image.FilterMacroParser.OpType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Curated native substitution catalog for Steps mode.
 *
 * This is deliberately not heuristic. Extension is a manual code change and
 * must use real {@link OpType} values backed by the tier-one filter parser.
 */
public final class FilterAlternatives {

    public enum SlotRole {
        SMOOTHING,
        BG_REMOVAL,
        LOCAL_THRESHOLD,
        MORPHOLOGY
    }

    private static final Map<SlotRole, List<Alternative>> BY_ROLE =
            buildByRole();
    private static final Map<OpType, SlotRole> ROLE_BY_TYPE =
            buildRoleByType();

    private FilterAlternatives() {
    }

    public static SlotRole slotRoleFor(OpType opType) {
        return opType == null ? null : ROLE_BY_TYPE.get(opType);
    }

    public static List<Alternative> alternativesFor(SlotRole role) {
        List<Alternative> alternatives = role == null ? null : BY_ROLE.get(role);
        return alternatives == null
                ? Collections.<Alternative>emptyList()
                : alternatives;
    }

    public static Alternative alternativeFor(SlotRole role, OpType type) {
        if (type == null) {
            return null;
        }
        List<Alternative> alternatives = alternativesFor(role);
        for (int i = 0; i < alternatives.size(); i++) {
            Alternative alternative = alternatives.get(i);
            if (alternative.type() == type) {
                return alternative;
            }
        }
        return null;
    }

    public static Alternative alternativeForLabel(SlotRole role, String label) {
        String needle = label == null ? "" : label.trim();
        if (needle.isEmpty()) {
            return null;
        }
        List<Alternative> alternatives = alternativesFor(role);
        for (int i = 0; i < alternatives.size(); i++) {
            Alternative alternative = alternatives.get(i);
            if (needle.equalsIgnoreCase(alternative.label())) {
                return alternative;
            }
        }
        return null;
    }

    public static boolean hasUsefulAlternatives(SlotRole role) {
        return alternativesFor(role).size() >= 2;
    }

    private static Map<SlotRole, List<Alternative>> buildByRole() {
        EnumMap<SlotRole, List<Alternative>> map =
                new EnumMap<SlotRole, List<Alternative>>(SlotRole.class);

        List<Alternative> smoothing = new ArrayList<Alternative>();
        smoothing.add(new Alternative("Smoothing", "Gaussian Blur",
                OpType.GAUSSIAN_BLUR));
        smoothing.add(new Alternative("Smoothing", "Median", OpType.MEDIAN));
        smoothing.add(new Alternative("Smoothing", "Mean", OpType.MEAN));
        smoothing.add(new Alternative("Smoothing", "Minimum", OpType.MINIMUM));
        smoothing.add(new Alternative("Smoothing", "Maximum", OpType.MAXIMUM));
        smoothing.add(new Alternative("Smoothing", "Variance", OpType.VARIANCE));
        map.put(SlotRole.SMOOTHING, immutable(smoothing));

        List<Alternative> background = new ArrayList<Alternative>();
        background.add(new Alternative("Background", "Subtract Background",
                OpType.SUBTRACT_BACKGROUND));
        map.put(SlotRole.BG_REMOVAL, immutable(background));

        List<Alternative> threshold = new ArrayList<Alternative>();
        threshold.add(new Alternative("Thresholding", "Auto Local Threshold",
                OpType.AUTO_LOCAL_THRESHOLD));
        map.put(SlotRole.LOCAL_THRESHOLD, immutable(threshold));

        List<Alternative> morphology = new ArrayList<Alternative>();
        morphology.add(new Alternative("Morphology", "Dilate", OpType.DILATE));
        morphology.add(new Alternative("Morphology", "Erode", OpType.ERODE));
        morphology.add(new Alternative("Morphology", "Open", OpType.OPEN));
        morphology.add(new Alternative("Morphology", "Close-", OpType.CLOSE_));
        morphology.add(new Alternative("Morphology", "Fill Holes",
                OpType.FILL_HOLES));
        morphology.add(new Alternative("Morphology", "Skeletonize",
                OpType.SKELETONIZE));
        map.put(SlotRole.MORPHOLOGY, immutable(morphology));

        return Collections.unmodifiableMap(map);
    }

    private static Map<OpType, SlotRole> buildRoleByType() {
        EnumMap<OpType, SlotRole> map =
                new EnumMap<OpType, SlotRole>(OpType.class);
        for (Map.Entry<SlotRole, List<Alternative>> entry : BY_ROLE.entrySet()) {
            SlotRole role = entry.getKey();
            List<Alternative> alternatives = entry.getValue();
            for (int i = 0; i < alternatives.size(); i++) {
                map.put(alternatives.get(i).type(), role);
            }
        }
        return Collections.unmodifiableMap(map);
    }

    private static List<Alternative> immutable(List<Alternative> alternatives) {
        return Collections.unmodifiableList(new ArrayList<Alternative>(alternatives));
    }

    public static final class Alternative {
        private final String category;
        private final String label;
        private final OpType type;

        private Alternative(String category, String label, OpType type) {
            this.category = category == null ? "" : category;
            this.label = label == null ? "" : label;
            this.type = type;
        }

        public String category() {
            return category;
        }

        public String label() {
            return label;
        }

        public OpType type() {
            return type;
        }

        @Override public String toString() {
            return label;
        }
    }
}
