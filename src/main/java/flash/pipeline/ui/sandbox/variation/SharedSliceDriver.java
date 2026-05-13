package flash.pipeline.ui.sandbox.variation;

import ij.ImagePlus;

import java.util.ArrayList;
import java.util.List;

/**
 * Drives the current Z slice across registered image tiles in lockstep.
 */
public final class SharedSliceDriver {

    private final List<ImagePlus> slaves = new ArrayList<ImagePlus>();
    private final List<Runnable> repainters = new ArrayList<Runnable>();
    private int currentSlice = 1;

    public void register(ImagePlus imp, Runnable onRepaint) {
        if (imp == null) throw new IllegalArgumentException("imp must not be null");
        slaves.add(imp);
        repainters.add(onRepaint);
    }

    public void unregister(ImagePlus imp) {
        if (imp == null) return;
        for (int i = slaves.size() - 1; i >= 0; i--) {
            if (slaves.get(i) == imp) {
                slaves.remove(i);
                repainters.remove(i);
            }
        }
        currentSlice = Math.max(1, Math.min(currentSlice, maxSlice()));
    }

    public void setSlice(int slice) {
        int clamped = Math.max(1, Math.min(slice, maxSlice()));
        currentSlice = clamped;
        for (int i = 0; i < slaves.size(); i++) {
            slaves.get(i).setSliceWithoutUpdate(clamped);
            Runnable repaint = repainters.get(i);
            if (repaint != null) repaint.run();
        }
    }

    public int maxSlice() {
        if (slaves.isEmpty()) return 1;
        int min = Integer.MAX_VALUE;
        for (int i = 0; i < slaves.size(); i++) {
            int slices = Math.max(1, slaves.get(i).getNSlices());
            if (slices < min) min = slices;
        }
        return min == Integer.MAX_VALUE ? 1 : min;
    }

    public int currentSlice() {
        return currentSlice;
    }

    public int size() {
        return slaves.size();
    }
}
