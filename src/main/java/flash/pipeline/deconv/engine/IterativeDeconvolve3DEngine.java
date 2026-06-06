package flash.pipeline.deconv.engine;

import flash.pipeline.deconv.DeconvolutionAvailability;
import flash.pipeline.image.WindowManagerLock;
import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.macro.Interpreter;
import ij.measure.Calibration;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class IterativeDeconvolve3DEngine implements DeconvolutionEngine {

    private static final String COMMAND_NAME = "Iterative Deconvolve 3D";
    private static final List<Algorithm> SUPPORTED_ALGORITHMS =
            Collections.unmodifiableList(Arrays.asList(Algorithm.RL));

    private final AvailabilityProbe availabilityProbe;
    private final ImageJRunner imageJRunner;

    public IterativeDeconvolve3DEngine() {
        this(
                new AvailabilityProbe() {
                    @Override
                    public boolean isAvailable() {
                        return DeconvolutionAvailability.isIterativeDeconvolve3DAvailable();
                    }
                },
                new ImageJRunner() {
                    @Override
                    public void run(ImagePlus image, String command, String options) {
                        IJ.run(image, command, options);
                    }

                    @Override
                    public int[] getWindowIds() {
                        return WindowManager.getIDList();
                    }

                    @Override
                    public ImagePlus getImage(int id) {
                        return WindowManager.getImage(id);
                    }

                    @Override
                    public ImagePlus getImage(String title) {
                        return WindowManager.getImage(title);
                    }
                }
        );
    }

    IterativeDeconvolve3DEngine(AvailabilityProbe availabilityProbe, ImageJRunner imageJRunner) {
        if (availabilityProbe == null) {
            throw new IllegalArgumentException("availabilityProbe is required.");
        }
        if (imageJRunner == null) {
            throw new IllegalArgumentException("imageJRunner is required.");
        }
        this.availabilityProbe = availabilityProbe;
        this.imageJRunner = imageJRunner;
    }

    @Override
    public String key() {
        return "IterativeDeconvolve3D";
    }

    @Override
    public String displayName() {
        return "Iterative Deconvolve 3D (CPU, lightweight)";
    }

    @Override
    public String description() {
        return "Classic noise-resistant Richardson-Lucy. Lightweight CPU, works without GPU.";
    }

    @Override
    public boolean isAvailable() {
        return availabilityProbe.isAvailable();
    }

    @Override
    public List<Algorithm> supportedAlgorithms() {
        return SUPPORTED_ALGORITHMS;
    }

    @Override
    public ImagePlus deconvolve(ImagePlus stack, ImagePlus psf, DeconvParams params)
            throws DeconvolutionException {
        validateInputs(stack, psf, params);
        requireSupported(params.getAlgorithm());

        if (!isAvailable()) {
            throw new EnginePluginMissingException(displayName() + " is not available in this runtime.");
        }

        String token = UUID.randomUUID().toString().replace("-", "");
        String stackTitle = "ihf_iterative_stack_" + token;
        String psfTitle = "ihf_iterative_psf_" + token;
        String outputTitle = "ihf_iterative_out_" + token;

        ImagePlus stackCopy = null;
        ImagePlus psfCopy = null;
        ImagePlus rawResult = null;
        int[] beforeIds = null;
        boolean previousBatchMode = false;

        WindowManagerLock.LOCK.lock();
        try {
            previousBatchMode = Interpreter.batchMode;
            Interpreter.batchMode = true;
            stackCopy = stack.duplicate();
            psfCopy = psf.duplicate();
            stackCopy.setTitle(stackTitle);
            psfCopy.setTitle(psfTitle);
            stackCopy.show();
            psfCopy.show();

            beforeIds = imageJRunner.getWindowIds();
            imageJRunner.run(stackCopy, COMMAND_NAME, buildMacroOptions(stackTitle, psfTitle, outputTitle, params));

            rawResult = imageJRunner.getImage(outputTitle);
            if (rawResult == null) {
                rawResult = findGeneratedImage(beforeIds, imageJRunner.getWindowIds(), imageJRunner);
            }
            if (rawResult == null) {
                throw new DeconvolutionException("Iterative Deconvolve 3D did not produce an output image.");
            }

            ImagePlus detached = rawResult.duplicate();
            preserveMetadata(stack, detached, stack.getShortTitle() + "-deconv-iterative");
            validateResultDimensions(stack, detached);
            return detached;
        } catch (Throwable t) {
            if (isMissingDependencyFailure(t)) {
                throw new EnginePluginMissingException(
                        "Iterative Deconvolve 3D is no longer available during execution.",
                        t
                );
            }
            if (t instanceof DeconvolutionException) {
                throw (DeconvolutionException) t;
            }
            throw new DeconvolutionException("Iterative Deconvolve 3D deconvolution failed.", t);
        } finally {
            try {
                disposeDistinct(rawResult, stackCopy, psfCopy);
                ImagePlus leftover = imageJRunner.getImage(outputTitle);
                if (leftover != null && leftover != rawResult) {
                    disposeImage(leftover);
                }
                closeNewImages(beforeIds, imageJRunner);
            } finally {
                Interpreter.batchMode = previousBatchMode;
                WindowManagerLock.LOCK.unlock();
            }
        }
    }

    private static String buildMacroOptions(String stackTitle,
                                            String psfTitle,
                                            String outputTitle,
                                            DeconvParams params) {
        return "image=" + stackTitle
                + " point=" + psfTitle
                + " output=" + outputTitle
                + " iterations=" + params.getIterations()
                + " wiener=0.000 low=0 show=no";
    }

    private static void validateInputs(ImagePlus stack, ImagePlus psf, DeconvParams params) {
        if (stack == null) throw new IllegalArgumentException("stack is required.");
        if (psf == null) throw new IllegalArgumentException("psf is required.");
        if (params == null) throw new IllegalArgumentException("params is required.");
    }

    private void requireSupported(Algorithm algorithm) {
        if (!SUPPORTED_ALGORITHMS.contains(algorithm)) {
            throw new IllegalArgumentException(displayName() + " does not support algorithm " + algorithm + '.');
        }
    }

    private static void preserveMetadata(ImagePlus source, ImagePlus result, String title) {
        if (title != null) {
            result.setTitle(title);
        }
        Calibration calibration = source.getCalibration();
        if (calibration != null) {
            result.setCalibration(calibration.copy());
        }
        if (source.getNChannels() * source.getNSlices() * source.getNFrames() == source.getStackSize()
                && result.getStackSize() == source.getStackSize()) {
            result.setDimensions(source.getNChannels(), source.getNSlices(), source.getNFrames());
            result.setOpenAsHyperStack(source.isHyperStack());
        }
    }

    private static void validateResultDimensions(ImagePlus input, ImagePlus result)
            throws DeconvolutionException {
        if (input.getWidth() != result.getWidth()
                || input.getHeight() != result.getHeight()
                || input.getStackSize() != result.getStackSize()) {
            throw new DeconvolutionException("Iterative Deconvolve 3D returned an image with unexpected dimensions.");
        }
    }

    private static ImagePlus findGeneratedImage(int[] beforeIds, int[] afterIds, ImageJRunner imageJRunner) {
        if (afterIds == null || afterIds.length == 0) return null;
        Set<Integer> seen = new HashSet<Integer>();
        if (beforeIds != null) {
            for (int id : beforeIds) {
                seen.add(Integer.valueOf(id));
            }
        }
        for (int i = afterIds.length - 1; i >= 0; i--) {
            int id = afterIds[i];
            if (seen.contains(Integer.valueOf(id))) continue;
            ImagePlus image = imageJRunner.getImage(id);
            if (image != null) return image;
        }
        return null;
    }

    private static void closeNewImages(int[] beforeIds, ImageJRunner imageJRunner) {
        if (beforeIds == null || imageJRunner == null) return;
        Set<Integer> seen = new HashSet<Integer>();
        for (int id : beforeIds) {
            seen.add(Integer.valueOf(id));
        }
        int[] afterIds = imageJRunner.getWindowIds();
        if (afterIds == null) return;
        for (int id : afterIds) {
            if (seen.contains(Integer.valueOf(id))) continue;
            disposeImage(imageJRunner.getImage(id));
        }
    }

    private static void disposeDistinct(ImagePlus... images) {
        Set<ImagePlus> seen = new HashSet<ImagePlus>();
        for (ImagePlus image : images) {
            if (image == null || seen.contains(image)) continue;
            seen.add(image);
            disposeImage(image);
        }
    }

    private static void disposeImage(ImagePlus image) {
        if (image == null) return;
        image.changes = false;
        try {
            image.close();
        } finally {
            image.flush();
        }
    }

    private static boolean isMissingDependencyFailure(Throwable t) {
        Throwable cursor = t;
        while (cursor != null) {
            if (cursor instanceof ClassNotFoundException
                    || cursor instanceof NoClassDefFoundError
                    || cursor instanceof LinkageError) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private interface AvailabilityProbe {
        boolean isAvailable();
    }

    interface ImageJRunner {
        void run(ImagePlus image, String command, String options);
        int[] getWindowIds();
        ImagePlus getImage(int id);
        ImagePlus getImage(String title);
    }
}
