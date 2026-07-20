package flash.pipeline.image;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;
import flash.pipeline.naming.OrientationManifestRow;
import flash.pipeline.naming.ResolvedImageMetadata;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;
import java.util.List;

/** Image stack orientation utilities. */
public final class OrientationOps {

    /** Minimum number of slices to use parallel processing. */
    private static final int PARALLEL_THRESHOLD = 4;

    private OrientationOps() {}

    /** Returns true when the hemisphere string is a recognised orientation tag. */
    private static boolean isKnownHemisphere(String hemisphere) {
        return "LH".equalsIgnoreCase(hemisphere) || "RH".equalsIgnoreCase(hemisphere);
    }

    /**
     * Legacy macro-based orientation (NOT thread-safe).
     * Rotate 90 Degrees Left; if hemisphere == "RH" then Flip Horizontally (stack).
     * Skipped entirely when hemisphere is not a recognised value.
     */
    public static void orientateToLeftHemisphere(ImagePlus imp, String hemisphere) {
        if (imp == null || !isKnownHemisphere(hemisphere)) return;
        IJ.run(imp, "Rotate 90 Degrees Left", "");
        if ("RH".equalsIgnoreCase(hemisphere)) {
            IJ.run(imp, "Flip Horizontally", "stack");
        }
    }

    /**
     * Thread-safe orientation using direct ImageProcessor operations.
     * Rotates 90 degrees left and optionally flips horizontally for RH hemisphere.
     * Skipped entirely when hemisphere is not a recognised value.
     */
    public static void orientateThreadSafe(ImagePlus imp, String hemisphere) {
        if (imp == null || !isKnownHemisphere(hemisphere)) return;
        rotateLeft90(imp);
        if ("RH".equalsIgnoreCase(hemisphere)) {
            flipHorizontallyStack(imp);
        }
    }

    /**
     * Applies saved manual transforms, then optional left/right view
     * standardisation. Positive rotation degrees are clockwise.
     */
    public static void applyTransform(ImagePlus imp, ResolvedImageMetadata metadata) {
        if (metadata == null) return;
        applyTransform(
                imp,
                metadata.rotateDegrees.degrees(),
                metadata.flipHorizontal,
                metadata.flipVertical,
                metadata.hemisphere,
                metadata.viewPolicy);
    }

    /**
     * Applies saved manual transforms, then optional left/right view
     * standardisation. Positive rotation degrees are clockwise.
     */
    public static void applyTransform(ImagePlus imp,
                                      int rotateDegrees,
                                      boolean flipHorizontal,
                                      boolean flipVertical,
                                      String hemisphere,
                                      OrientationManifestRow.ViewPolicy viewPolicy) {
        if (imp == null) return;

        rotateClockwise(imp, normalizeRotationDegrees(rotateDegrees));
        if (flipHorizontal) {
            flipHorizontallyStack(imp);
        }
        if (flipVertical) {
            flipVerticallyStack(imp);
        }

        OrientationManifestRow.ViewPolicy policy = viewPolicy == null
                ? OrientationManifestRow.ViewPolicy.MANUAL_ONLY : viewPolicy;
        if (policy == OrientationManifestRow.ViewPolicy.STANDARDIZE_TO_LEFT
                && "RH".equalsIgnoreCase(hemisphere)) {
            flipHorizontallyStack(imp);
        } else if (policy == OrientationManifestRow.ViewPolicy.STANDARDIZE_TO_RIGHT
                && "LH".equalsIgnoreCase(hemisphere)) {
            flipHorizontallyStack(imp);
        }
    }

    public static int normalizeRotationDegrees(int rotateDegrees) {
        int normalized = rotateDegrees % 360;
        if (normalized < 0) normalized += 360;
        if (normalized == 90 || normalized == 180 || normalized == 270) {
            return normalized;
        }
        return 0;
    }

    /**
     * Rotate all slices 90 degrees counter-clockwise. Thread-safe.
     * Parallelized for stacks with >= PARALLEL_THRESHOLD slices.
     */
    private static void rotateLeft90(ImagePlus imp) {
        final ImageStack oldStack = imp.getStack();
        int nSlices = oldStack.getSize();
        int oldH = imp.getHeight();
        int oldW = imp.getWidth();
        // After rotating left 90: new width = oldH, new height = oldW
        final ImageProcessor[] rotatedSlices = new ImageProcessor[nSlices];
        final String[] labels = new String[nSlices];

        if (nSlices < PARALLEL_THRESHOLD || ParallelContext.isNested()) {
            for (int s = 1; s <= nSlices; s++) {
                rotatedSlices[s - 1] = oldStack.getProcessor(s).rotateLeft();
                labels[s - 1] = oldStack.getSliceLabel(s);
            }
        } else {
            // Pre-read labels and processors sequentially (virtual stacks are NOT thread-safe)
            final ImageProcessor[] rawSlices = new ImageProcessor[nSlices];
            for (int s = 1; s <= nSlices; s++) {
                labels[s - 1] = oldStack.getSliceLabel(s);
                rawSlices[s - 1] = oldStack.getProcessor(s);
            }
            // Rotate in parallel (CPU-only, no IO)
            int nThreads = Math.min(nSlices, Runtime.getRuntime().availableProcessors());
            ExecutorService exec = Executors.newFixedThreadPool(nThreads);
            try {
                List<Future<?>> futures = new ArrayList<Future<?>>();
                for (int s = 0; s < nSlices; s++) {
                    final int idx = s;
                    futures.add(exec.submit(new Runnable() {
                        @Override
                        public void run() {
                            rotatedSlices[idx] = rawSlices[idx].rotateLeft();
                        }
                    }));
                }
                for (Future<?> f : futures) {
                    try {
                        f.get();
                    } catch (Exception e) {
                        throw new RuntimeException("Parallel rotation failed", e);
                    }
                }
            } finally {
                exec.shutdown();
            }
        }

        ImageStack newStack = new ImageStack(oldH, oldW);
        for (int i = 0; i < nSlices; i++) {
            newStack.addSlice(labels[i], rotatedSlices[i]);
        }
        setStackPreservingDimensions(imp, newStack, oldStack.getSize());
    }

    /** Flip all slices horizontally. Thread-safe. */
    private static void flipHorizontallyStack(ImagePlus imp) {
        ImageStack stack = imp.getStack();
        int nSlices = stack.getSize();
        for (int s = 1; s <= nSlices; s++) {
            ImageProcessor ip = stack.getProcessor(s);
            ip.flipHorizontal();
        }
    }

    /** Flip all slices vertically. Thread-safe. */
    private static void flipVerticallyStack(ImagePlus imp) {
        ImageStack stack = imp.getStack();
        int nSlices = stack.getSize();
        for (int s = 1; s <= nSlices; s++) {
            ImageProcessor ip = stack.getProcessor(s);
            ip.flipVertical();
        }
    }

    private static void rotateClockwise(ImagePlus imp, int normalizedDegrees) {
        if (normalizedDegrees == 0) return;
        if (normalizedDegrees == 90) {
            rotateRight90(imp);
        } else if (normalizedDegrees == 180) {
            flipHorizontallyStack(imp);
            flipVerticallyStack(imp);
        } else if (normalizedDegrees == 270) {
            rotateLeft90(imp);
        }
    }

    /**
     * Rotate all slices 90 degrees clockwise. Thread-safe.
     * Parallelized for stacks with >= PARALLEL_THRESHOLD slices.
     */
    private static void rotateRight90(ImagePlus imp) {
        final ImageStack oldStack = imp.getStack();
        int nSlices = oldStack.getSize();
        int oldH = imp.getHeight();
        int oldW = imp.getWidth();
        final ImageProcessor[] rotatedSlices = new ImageProcessor[nSlices];
        final String[] labels = new String[nSlices];

        if (nSlices < PARALLEL_THRESHOLD || ParallelContext.isNested()) {
            for (int s = 1; s <= nSlices; s++) {
                rotatedSlices[s - 1] = oldStack.getProcessor(s).rotateRight();
                labels[s - 1] = oldStack.getSliceLabel(s);
            }
        } else {
            final ImageProcessor[] rawSlices = new ImageProcessor[nSlices];
            for (int s = 1; s <= nSlices; s++) {
                labels[s - 1] = oldStack.getSliceLabel(s);
                rawSlices[s - 1] = oldStack.getProcessor(s);
            }
            int nThreads = Math.min(nSlices, Runtime.getRuntime().availableProcessors());
            ExecutorService exec = Executors.newFixedThreadPool(nThreads);
            try {
                List<Future<?>> futures = new ArrayList<Future<?>>();
                for (int s = 0; s < nSlices; s++) {
                    final int idx = s;
                    futures.add(exec.submit(new Runnable() {
                        @Override
                        public void run() {
                            rotatedSlices[idx] = rawSlices[idx].rotateRight();
                        }
                    }));
                }
                for (Future<?> f : futures) {
                    try {
                        f.get();
                    } catch (Exception e) {
                        throw new RuntimeException("Parallel rotation failed", e);
                    }
                }
            } finally {
                exec.shutdown();
            }
        }

        ImageStack newStack = new ImageStack(oldH, oldW);
        for (int i = 0; i < nSlices; i++) {
            newStack.addSlice(labels[i], rotatedSlices[i]);
        }
        setStackPreservingDimensions(imp, newStack, oldStack.getSize());
    }

    private static void setStackPreservingDimensions(ImagePlus imp,
                                                     ImageStack newStack,
                                                     int oldStackSize) {
        int channels = imp.getNChannels();
        int slices = imp.getNSlices();
        int frames = imp.getNFrames();
        boolean hyperStack = imp.isHyperStack();
        imp.setStack(newStack);
        if (channels * slices * frames == oldStackSize) {
            imp.setDimensions(channels, slices, frames);
            imp.setOpenAsHyperStack(hyperStack);
        }
    }
}
