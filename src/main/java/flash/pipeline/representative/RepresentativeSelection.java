package flash.pipeline.representative;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Final Step-2 representative choices, keyed by condition.
 */
public final class RepresentativeSelection {

    public static final String UNASSIGNED_CONDITION = "Unassigned";

    private final List<String> conditionNames;
    private final LinkedHashMap<String, RepresentativeSeries> selectedByCondition;

    public RepresentativeSelection(List<String> conditionNames,
                                   Map<String, RepresentativeSeries> selectedByCondition) {
        this.conditionNames = normalizeConditionList(conditionNames);
        if (this.conditionNames.isEmpty()) {
            throw new IllegalArgumentException("At least one condition is required.");
        }
        this.selectedByCondition = normalizeSelections(selectedByCondition, this.conditionNames);
    }

    public static String conditionLabel(String conditionName) {
        String clean = conditionName == null ? "" : conditionName.trim();
        return clean.isEmpty() ? UNASSIGNED_CONDITION : clean;
    }

    public boolean isComplete() {
        return selectedByCondition.size() == conditionNames.size();
    }

    public int size() {
        return selectedByCondition.size();
    }

    public List<String> conditionNames() {
        return conditionNames;
    }

    public RepresentativeSeries seriesForCondition(String conditionName) {
        return selectedByCondition.get(conditionLabel(conditionName));
    }

    public List<RepresentativeSeries> series() {
        return Collections.unmodifiableList(
                new ArrayList<RepresentativeSeries>(selectedByCondition.values()));
    }

    public Map<String, RepresentativeSeries> asMap() {
        return Collections.unmodifiableMap(selectedByCondition);
    }

    private static List<String> normalizeConditionList(List<String> names) {
        LinkedHashSet<String> unique = new LinkedHashSet<String>();
        if (names != null) {
            for (String name : names) {
                unique.add(conditionLabel(name));
            }
        }
        return Collections.unmodifiableList(new ArrayList<String>(unique));
    }

    private static LinkedHashMap<String, RepresentativeSeries> normalizeSelections(
            Map<String, RepresentativeSeries> selections,
            List<String> requiredConditions) {
        LinkedHashSet<String> required = new LinkedHashSet<String>(requiredConditions);
        LinkedHashMap<String, RepresentativeSeries> normalizedInput =
                new LinkedHashMap<String, RepresentativeSeries>();
        if (selections != null) {
            for (Map.Entry<String, RepresentativeSeries> entry : selections.entrySet()) {
                String condition = conditionLabel(entry.getKey());
                if (!required.contains(condition)) {
                    throw new IllegalArgumentException(
                            "Selection includes unknown condition: " + condition);
                }
                RepresentativeSeries series = entry.getValue();
                if (series == null) {
                    throw new IllegalArgumentException(
                            "Selection is missing a series for condition: " + condition);
                }
                String seriesCondition = conditionLabel(series.condition());
                if (!condition.equals(seriesCondition)) {
                    throw new IllegalArgumentException(
                            "Series '" + series.seriesName()
                                    + "' belongs to condition '" + seriesCondition
                                    + "', not '" + condition + "'.");
                }
                normalizedInput.put(condition, series);
            }
        }

        LinkedHashMap<String, RepresentativeSeries> ordered =
                new LinkedHashMap<String, RepresentativeSeries>();
        for (String condition : requiredConditions) {
            RepresentativeSeries series = normalizedInput.get(condition);
            if (series == null) {
                throw new IllegalArgumentException(
                        "Selection is missing a series for condition: " + condition);
            }
            ordered.put(condition, series);
        }
        return ordered;
    }
}
