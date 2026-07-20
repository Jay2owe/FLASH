package flash.pipeline.zslice;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Global Z-slice mode plus the final per-series resolved selections.
 */
public final class ZSliceConfig {
    public final ZSliceMode mode;
    public final LinkedHashMap<Integer, ZSliceSelection> selections = new LinkedHashMap<Integer, ZSliceSelection>();

    public ZSliceConfig(ZSliceMode mode, Map<Integer, ZSliceSelection> selections) {
        this.mode = mode == null ? ZSliceMode.FULL : mode;
        if (selections != null) {
            this.selections.putAll(selections);
        }
    }

    public static ZSliceConfig fullStack() {
        return new ZSliceConfig(ZSliceMode.FULL, null);
    }

    public boolean usesSubset() {
        return mode.usesSubset() && !selections.isEmpty();
    }

    public boolean isEffectivelyFullStack() {
        if (mode == ZSliceMode.FULL || selections.isEmpty()) return true;
        for (ZSliceSelection selection : selections.values()) {
            if (selection != null && !selection.isFullStack()) {
                return false;
            }
        }
        return true;
    }

    public ZSliceSelection getSelection(int seriesIndex) {
        return selections.get(seriesIndex);
    }

    public ZSliceRange getRange(int seriesIndex) {
        ZSliceSelection selection = getSelection(seriesIndex);
        return selection == null ? null : selection.range;
    }

    public List<ZSliceSelection> orderedSelections() {
        return new ArrayList<ZSliceSelection>(selections.values());
    }

    public String summary() {
        if (mode == ZSliceMode.FULL || selections.isEmpty()) return "Full stack";
        return mode.displayName + " (" + selections.size() + " series)";
    }
}
