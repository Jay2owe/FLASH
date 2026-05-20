package flash.pipeline.image;

import ij.ImagePlus;
import ij.ImageStack;
import ij.plugin.ImageCalculator;
import ij.process.ImageProcessor;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;
import java.util.List;

/**
 * Image calculator operations (AND, subtract).
 *
 * Provides both legacy IJ-based methods and thread-safe direct pixel operations.
 */
public final class ImageCalcOps {

    /** Minimum number of slices to use parallel processing. */
    private static final int PARALLEL_THRESHOLD = 4;

    private ImageCalcOps() {}

    // ── Legacy methods (NOT thread-safe — ImageCalculator may touch global state) ──

    public static ImagePlus andStack(ImagePlus a, ImagePlus b) {
        if (a == null || b == null) return null;
        ImageCalculator ic = new ImageCalculator();
        return ic.run("AND create stack", a, b);
    }

    public static ImagePlus subtractStack(ImagePlus a, ImagePlus b) {
        if (a == null || b == null) return null;
        ImageCalculator ic = new ImageCalculator();
        return ic.run("Subtract create stack", a, b);
    }

    // ── Thread-safe methods using direct pixel operations ──

    /**
     * Applies stack {@code a} as a binary mask to stack {@code b}. Creates a new ImagePlus.
     * Thread-safe: operates directly on pixel arrays.
     * Parallelized for stacks with >= PARALLEL_THRESHOLD slices.
     */
    public static ImagePlus andStackThreadSafe(ImagePlus a, ImagePlus b) {
        if (a == null || b == null) return null;
        int nSlices = Math.min(a.getStackSize(), b.getStackSize());
        final ImageProcessor[] sliceResults = new ImageProcessor[nSlices];

        if (nSlices < PARALLEL_THRESHOLD) {
            for (int s = 1; s <= nSlices; s++) {
                sliceResults[s - 1] = andSlice(a, b, s);
            }
        } else {
            parallelProcessSlices(nSlices, new SliceTask() {
                @Override
                public void process(int slice) {
                    sliceResults[slice - 1] = andSlice(a, b, slice);
                }
            });
        }

        ImageStack result = new ImageStack(a.getWidth(), a.getHeight());
        for (int i = 0; i < nSlices; i++) {
            result.addSlice(sliceResults[i]);
        }
        ImagePlus out = new ImagePlus("AND_result", result);
        out.setCalibration(b.getCalibration().copy());
        return out;
    }

    private static ImageProcessor andSlice(ImagePlus a, ImagePlus b, int slice) {
        ImageProcessor mask = a.getStack().getProcessor(slice);
        ImageProcessor signal = b.getStack().getProcessor(slice);
        ImageProcessor ipR = signal.duplicate();
        int size = ipR.getWidth() * ipR.getHeight();
        if (ipR instanceof ij.process.FloatProcessor || signal instanceof ij.process.FloatProcessor) {
            for (int i = 0; i < size; i++) {
                ipR.setf(i, mask.getf(i) != 0f ? signal.getf(i) : 0f);
            }
        } else {
            for (int i = 0; i < size; i++) {
                ipR.set(i, mask.getf(i) != 0f ? signal.get(i) : 0);
            }
        }
        return ipR;
    }

    /**
     * Subtract stack b from stack a. Creates a new ImagePlus.
     * Thread-safe: operates directly on pixel arrays.
     * Parallelized for stacks with >= PARALLEL_THRESHOLD slices.
     */
    public static ImagePlus subtractStackThreadSafe(ImagePlus a, ImagePlus b) {
        if (a == null || b == null) return null;
        int nSlices = Math.min(a.getStackSize(), b.getStackSize());
        final ImageProcessor[] sliceResults = new ImageProcessor[nSlices];

        if (nSlices < PARALLEL_THRESHOLD) {
            for (int s = 1; s <= nSlices; s++) {
                sliceResults[s - 1] = subtractSlice(a, b, s);
            }
        } else {
            parallelProcessSlices(nSlices, new SliceTask() {
                @Override
                public void process(int slice) {
                    sliceResults[slice - 1] = subtractSlice(a, b, slice);
                }
            });
        }

        ImageStack result = new ImageStack(a.getWidth(), a.getHeight());
        for (int i = 0; i < nSlices; i++) {
            result.addSlice(sliceResults[i]);
        }
        ImagePlus out = new ImagePlus("Subtract_result", result);
        out.setCalibration(a.getCalibration().copy());
        return out;
    }

    private static ImageProcessor subtractSlice(ImagePlus a, ImagePlus b, int slice) {
        ImageProcessor ipA = a.getStack().getProcessor(slice);
        ImageProcessor ipB = b.getStack().getProcessor(slice);
        ImageProcessor ipR = ipA.duplicate();
        int size = ipR.getWidth() * ipR.getHeight();
        if (ipR instanceof ij.process.FloatProcessor || ipA instanceof ij.process.FloatProcessor
                || ipB instanceof ij.process.FloatProcessor) {
            for (int i = 0; i < size; i++) {
                float val = ipA.getf(i) - ipB.getf(i);
                ipR.setf(i, Math.max(0f, val));
            }
        } else {
            for (int i = 0; i < size; i++) {
                int val = ipA.get(i) - ipB.get(i);
                ipR.set(i, Math.max(0, val));
            }
        }
        return ipR;
    }

    // ── Parallel helpers ──

    private interface SliceTask {
        void process(int slice);
    }

    private static void parallelProcessSlices(int nSlices, final SliceTask task) {
        int nThreads = Math.min(nSlices, Runtime.getRuntime().availableProcessors());
        ExecutorService exec = Executors.newFixedThreadPool(nThreads);
        try {
            List<Future<?>> futures = new ArrayList<Future<?>>();
            for (int s = 1; s <= nSlices; s++) {
                final int slice = s;
                futures.add(exec.submit(new Runnable() {
                    @Override
                    public void run() {
                        task.process(slice);
                    }
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception e) {
                    throw new RuntimeException("Parallel image calc failed", e);
                }
            }
        } finally {
            exec.shutdown();
        }
    }
}
