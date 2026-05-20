package flash.pipeline.ui.variations;

import java.util.ArrayList;
import java.util.List;

public final class SyncedSliceController {

    private final List<VariationCellPanel> cells =
            new ArrayList<VariationCellPanel>();
    private int currentSlice = 1;

    public void register(VariationCellPanel cell) {
        if (cell == null) {
            throw new IllegalArgumentException("cell must not be null");
        }
        if (!cells.contains(cell)) {
            cells.add(cell);
        }
        setSlice(currentSlice);
    }

    public void unregister(VariationCellPanel cell) {
        if (cell == null) {
            return;
        }
        for (int i = cells.size() - 1; i >= 0; i--) {
            if (cells.get(i) == cell) {
                cells.remove(i);
            }
        }
        currentSlice = clamp(currentSlice);
    }

    public void setSlice(int slice) {
        int clamped = clamp(slice);
        currentSlice = clamped;
        for (int i = 0; i < cells.size(); i++) {
            cells.get(i).setZ(clamped);
        }
    }

    public int currentSlice() {
        return currentSlice;
    }

    public int maxSlice() {
        if (cells.isEmpty()) {
            return 1;
        }
        int min = Integer.MAX_VALUE;
        for (int i = 0; i < cells.size(); i++) {
            int slices = 1;
            try {
                slices = Math.max(1, cells.get(i).preview().getSliceCount());
            } catch (RuntimeException ignored) {
                slices = 1;
            }
            if (slices < min) {
                min = slices;
            }
        }
        return min == Integer.MAX_VALUE ? 1 : min;
    }

    public int size() {
        return cells.size();
    }

    private int clamp(int slice) {
        return Math.max(1, Math.min(slice, maxSlice()));
    }
}
