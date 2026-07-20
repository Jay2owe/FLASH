package flash.pipeline.image;

import ij.CompositeImage;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Overlay;
import ij.measure.Calibration;
import ij.process.ImageProcessor;
import ij.process.LUT;

import java.util.Properties;

/**
 * Thread-safe image-plane operations.
 *
 * <p>{@code ij.plugin.Duplicator} touches shared ImageJ state and is not safe
 * to call from worker threads. This helper performs an equivalent
 * channel/slice/frame copy using {@link ImageProcessor#crop()} per slice — the
 * documented per-slice safe path that returns a detached processor without any
 * WindowManager interaction.</p>
 */
public final class ImageOps {
    private static final OutputImageFactory DEFAULT_OUTPUT_IMAGE_FACTORY =
            new OutputImageFactory() {
                @Override
                public ImagePlus create(String title, ImageStack stack) {
                    return new ImagePlus(title, stack);
                }
            };

    private ImageOps() {}

    /** Thread-safe full-stack duplicate. */
    public static ImagePlus duplicateThreadSafe(ImagePlus src) {
        if (src == null) return null;
        return duplicateThreadSafe(src,
                1, Math.max(1, src.getNChannels()),
                1, Math.max(1, src.getNSlices()),
                1, Math.max(1, src.getNFrames()));
    }

    /**
     * Thread-safe duplicate of a sub-range. Mirrors
     * {@code Duplicator.run(imp, firstC, lastC, firstZ, lastZ, firstT, lastT)}
     * but performs a per-slice {@code ip.crop()} so it can run concurrently
     * from multiple worker threads.
     */
    public static ImagePlus duplicateThreadSafe(ImagePlus src,
            int firstC, int lastC, int firstZ, int lastZ,
            int firstT, int lastT) {
        if (src == null) return null;
        return duplicateThreadSafe(src,
                firstC, lastC, firstZ, lastZ, firstT, lastT,
                DEFAULT_OUTPUT_IMAGE_FACTORY);
    }

    /**
     * Package-private overload exposing only output construction for failure
     * lifecycle tests. Processor duplication and cropping always run through
     * the real processor subclass supplied by the source image.
     */
    static ImagePlus duplicateThreadSafe(ImagePlus src,
            int firstC, int lastC, int firstZ, int lastZ,
            int firstT, int lastT, OutputImageFactory outputFactory) {
        if (src == null) return null;
        if (outputFactory == null) {
            throw new IllegalArgumentException("outputFactory must not be null");
        }

        int sourceChannels = Math.max(1, src.getNChannels());
        int sourceSlices = Math.max(1, src.getNSlices());
        int sourceFrames = Math.max(1, src.getNFrames());
        validateRange("channel", firstC, lastC, sourceChannels);
        validateRange("slice", firstZ, lastZ, sourceSlices);
        validateRange("frame", firstT, lastT, sourceFrames);

        int nC = lastC - firstC + 1;
        int nZ = lastZ - firstZ + 1;
        int nT = lastT - firstT + 1;
        ImageStack inStack = src.getImageStack();
        ImageStack out = new ImageStack(src.getWidth(), src.getHeight());
        ImagePlus ownedOutput = null;
        try {
            for (int t = firstT; t <= lastT; t++) {
                for (int z = firstZ; z <= lastZ; z++) {
                    for (int c = firstC; c <= lastC; c++) {
                        int idx = src.getStackIndex(c, z, t);
                        ImageProcessor sourceProcessor = inStack.getProcessor(idx);
                        ImageProcessor cropped = copyFullProcessor(
                                sourceProcessor, src.getWidth(), src.getHeight(), idx);
                        out.addSlice(inStack.getSliceLabel(idx), cropped);
                        if (ownedOutput == null) {
                            ownedOutput = outputFactory.create(src.getTitle(), out);
                            if (ownedOutput == null) {
                                throw new IllegalStateException(
                                        "Output image factory returned null");
                            }
                        }
                    }
                }
            }

            if (ownedOutput == null) {
                ownedOutput = outputFactory.create(src.getTitle(), out);
                if (ownedOutput == null) {
                    throw new IllegalStateException("Output image factory returned null");
                }
            }

            // Re-attach the now-complete stack because the ImagePlus was created
            // after the first plane solely so a failed multi-plane copy has one
            // explicit owned object to release.
            ownedOutput.setStack(src.getTitle(), out);
            ownedOutput.setDimensions(nC, nZ, nT);

            if (src.isComposite() && nC > 1) {
                // CompositeImage adopts the same stack and metadata container.
                // Once construction succeeds, ownership transfers to the
                // composite wrapper; closing the base wrapper would flush the
                // returned composite's shared stack. If construction throws,
                // ownedOutput still names the base wrapper and is cleaned below.
                CompositeImage composite = new CompositeImage(
                        ownedOutput, src.getCompositeMode());
                ownedOutput = composite;
            }

            // Apply all ImagePlus-level metadata to the final owned wrapper.
            // CompositeImage construction does not retain arbitrary object
            // properties from the base wrapper.
            copyImageMetadata(src, ownedOutput,
                    firstC, lastC, firstZ, lastZ, firstT, lastT);

            if (src.isComposite()) {
                if (nC > 1) {
                    CompositeImage composite = (CompositeImage) ownedOutput;
                    if (firstC == 1 && lastC == sourceChannels) {
                        composite.copyLuts(src);
                    } else {
                        composite.setLuts(selectedLuts(src, firstC, lastC));
                        composite.setMode(src.getCompositeMode());
                    }
                } else {
                    LUT selected = selectedLuts(src, firstC, lastC)[0];
                    ownedOutput.setLut(selected);
                    ownedOutput.setDisplayRange(selected.min, selected.max);
                }
            }

            // CompositeImage construction can replace the base wrapper, so set
            // wrapper-local hyperstack state only after that ownership transfer.
            ownedOutput.setOpenAsHyperStack(src.getOpenAsHyperStack());
            int outputC = positionWithinRange(src.getC(), firstC, lastC);
            int outputZ = positionWithinRange(src.getZ(), firstZ, lastZ);
            int outputT = positionWithinRange(src.getT(), firstT, lastT);
            ownedOutput.setPositionWithoutUpdate(outputC, outputZ, outputT);
            return ownedOutput;
        } catch (RuntimeException failure) {
            closeOwnedAfterFailure(ownedOutput, failure);
            throw failure;
        } catch (Error failure) {
            closeOwnedAfterFailure(ownedOutput, failure);
            throw failure;
        }
    }

    private static ImageProcessor copyFullProcessor(
            ImageProcessor source, int width, int height, int stackIndex) {
        if (source == null) {
            throw new IllegalStateException(
                    "Source stack has no processor at index " + stackIndex);
        }
        ImageProcessor privateProcessor = source.duplicate();
        if (privateProcessor == null) {
            throw new IllegalStateException(
                    "Processor duplicate returned null at stack index " + stackIndex);
        }
        privateProcessor.setRoi(0, 0, width, height);
        ImageProcessor cropped = privateProcessor.crop();
        if (cropped == null
                || cropped.getWidth() != width
                || cropped.getHeight() != height) {
            throw new IllegalStateException(
                    "Processor copy has wrong dimensions at stack index " + stackIndex);
        }
        cropped.resetRoi();
        return cropped;
    }

    private static void copyImageMetadata(ImagePlus src, ImagePlus duplicate,
            int firstC, int lastC, int firstZ, int lastZ,
            int firstT, int lastT) {
        Calibration cal = src.getCalibration();
        if (cal != null) duplicate.setCalibration(cal.copy());

        Properties objectProperties = src.getProperties();
        if (objectProperties != null) {
            for (Object key : objectProperties.keySet()) {
                if (key instanceof String) {
                    duplicate.setProperty((String) key, objectProperties.get(key));
                }
            }
        }
        String[] properties = src.getPropertiesAsArray();
        if (properties != null) duplicate.setProperties(properties.clone());

        duplicate.setDisplayRange(src.getDisplayRangeMin(), src.getDisplayRangeMax());

        Overlay overlay = src.getOverlay();
        if (overlay != null) {
            Overlay overlayCopy = overlay.duplicate();
            overlayCopy.crop(firstC, lastC, firstZ, lastZ, firstT, lastT);
            duplicate.setOverlay(overlayCopy);
            duplicate.setHideOverlay(src.getHideOverlay());
        }
    }

    private static int positionWithinRange(int sourcePosition, int first, int last) {
        if (sourcePosition < first) return 1;
        if (sourcePosition > last) return last - first + 1;
        return sourcePosition - first + 1;
    }

    private static LUT[] selectedLuts(ImagePlus source, int firstC, int lastC) {
        LUT[] sourceLuts = source.getLuts();
        LUT[] selected = new LUT[lastC - firstC + 1];
        for (int c = firstC; c <= lastC; c++) {
            selected[c - firstC] = sourceLuts[c - 1];
        }
        return selected;
    }

    private static void validateRange(String name, int first, int last, int limit) {
        if (first < 1 || last < first || last > limit) {
            throw new IllegalArgumentException("Invalid " + name + " range "
                    + first + "-" + last + " for 1-" + limit);
        }
    }

    private static void closeOwnedAfterFailure(ImagePlus owned, Throwable primaryFailure) {
        if (owned == null) return;
        Throwable cleanupFailure = null;
        try {
            owned.changes = false;
            owned.close();
        } catch (RuntimeException failure) {
            cleanupFailure = failure;
        } catch (Error failure) {
            cleanupFailure = failure;
        }
        try {
            owned.flush();
        } catch (RuntimeException failure) {
            cleanupFailure = mergeCleanupFailures(cleanupFailure, failure);
        } catch (Error failure) {
            cleanupFailure = mergeCleanupFailures(cleanupFailure, failure);
        }
        if (cleanupFailure == null || cleanupFailure == primaryFailure) return;
        if (isVmFatal(cleanupFailure) && !isVmFatal(primaryFailure)) {
            addSuppressedIfDistinct(cleanupFailure, primaryFailure);
            rethrowUnchecked(cleanupFailure);
        }
        addSuppressedIfDistinct(primaryFailure, cleanupFailure);
    }

    private static Throwable mergeCleanupFailures(
            Throwable existing, Throwable additional) {
        if (existing == null) return additional;
        if (additional == existing) return existing;
        if (isVmFatal(additional) && !isVmFatal(existing)) {
            addSuppressedIfDistinct(additional, existing);
            return additional;
        }
        addSuppressedIfDistinct(existing, additional);
        return existing;
    }

    private static void addSuppressedIfDistinct(Throwable primary, Throwable additional) {
        if (primary != null && additional != null && primary != additional) {
            primary.addSuppressed(additional);
        }
    }

    private static boolean isVmFatal(Throwable failure) {
        return failure instanceof VirtualMachineError || failure instanceof ThreadDeath;
    }

    private static void rethrowUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        throw (Error) failure;
    }

    interface OutputImageFactory {
        ImagePlus create(String title, ImageStack stack);
    }
}
