package flash.pipeline.ui.variations;

import flash.pipeline.image.FilterMacroParser.OpType;
import flash.pipeline.ui.sandbox.FilterAlternatives;
import flash.pipeline.ui.sandbox.FilterAlternatives.Alternative;
import flash.pipeline.ui.sandbox.FilterAlternatives.SlotRole;

import java.util.Map;

public final class SlotSubstitutionCombo {

    public static final String DEFAULT_SCALE_LABEL = "default";

    private final int stepIndex;
    private final SlotRole role;
    private final OpType candidateType;
    private final String candidateLabel;
    private final CanonicalScale scale;
    private final CanonicalScale.ScaleValue scaleValue;

    public SlotSubstitutionCombo(int stepIndex,
                                 SlotRole role,
                                 OpType candidateType,
                                 String candidateLabel,
                                 CanonicalScale scale,
                                 CanonicalScale.ScaleValue scaleValue) {
        if (stepIndex < 0) {
            throw new IllegalArgumentException("stepIndex must be >= 0");
        }
        if (role == null) {
            throw new IllegalArgumentException("role must not be null");
        }
        if (candidateType == null) {
            throw new IllegalArgumentException("candidateType must not be null");
        }
        this.stepIndex = stepIndex;
        this.role = role;
        this.candidateType = candidateType;
        this.candidateLabel = candidateLabel == null ? "" : candidateLabel.trim();
        this.scale = scale;
        this.scaleValue = scaleValue == null
                ? CanonicalScale.ScaleValue.none()
                : scaleValue;
    }

    public static ParameterCombo toParameterCombo(int stepIndex,
                                                  SlotRole role,
                                                  Alternative alternative,
                                                  String scaleLabel) {
        if (alternative == null) {
            throw new IllegalArgumentException("alternative must not be null");
        }
        return ParameterCombo.builder()
                .put(SlotSubstitutionKey.filterAxis(stepIndex, role.name()),
                        alternative.label())
                .put(SlotSubstitutionKey.scaleAxis(stepIndex, role.name()),
                        scaleLabel == null ? DEFAULT_SCALE_LABEL : scaleLabel)
                .build();
    }

    public static SlotSubstitutionCombo from(ParameterCombo combo) {
        if (combo == null) {
            return null;
        }
        SlotSubstitutionKey filterKey = null;
        Object filterValue = null;
        SlotSubstitutionKey scaleKey = null;
        Object scaleValue = null;
        for (Map.Entry<ParameterKey, Object> entry : combo.values().entrySet()) {
            if (!(entry.getKey() instanceof SlotSubstitutionKey)) {
                continue;
            }
            SlotSubstitutionKey key = (SlotSubstitutionKey) entry.getKey();
            if (key.axis() == SlotSubstitutionKey.Axis.FILTER) {
                filterKey = key;
                filterValue = entry.getValue();
            } else if (key.axis() == SlotSubstitutionKey.Axis.SCALE) {
                scaleKey = key;
                scaleValue = entry.getValue();
            }
        }
        if (filterKey == null || scaleKey == null
                || filterKey.stepIndex() != scaleKey.stepIndex()) {
            return null;
        }
        SlotRole role = parseRole(filterKey.roleName());
        if (role == null) {
            return null;
        }
        String label = filterValue == null ? "" : String.valueOf(filterValue);
        Alternative alternative = FilterAlternatives.alternativeForLabel(role, label);
        if (alternative == null) {
            return null;
        }
        String scaleText = scaleValue == null ? "" : String.valueOf(scaleValue);
        CanonicalScale scale = CanonicalScale.fromLabel(scaleText);
        CanonicalScale.ScaleValue nativeValue = scale == null
                ? CanonicalScale.ScaleValue.none()
                : CanonicalScale.valueFor(alternative.type(), scale);
        return new SlotSubstitutionCombo(filterKey.stepIndex(), role,
                alternative.type(), alternative.label(), scale, nativeValue);
    }

    public static boolean isSlotSubstitution(ParameterCombo combo) {
        return from(combo) != null;
    }

    public int stepIndex() {
        return stepIndex;
    }

    public SlotRole role() {
        return role;
    }

    public OpType candidateType() {
        return candidateType;
    }

    public String candidateLabel() {
        return candidateLabel;
    }

    public CanonicalScale scale() {
        return scale;
    }

    public CanonicalScale.ScaleValue scaleValue() {
        return scaleValue;
    }

    public String displayLabel() {
        return CanonicalScale.comboLabel(candidateLabel, candidateType, scale);
    }

    private static SlotRole parseRole(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        try {
            return SlotRole.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
