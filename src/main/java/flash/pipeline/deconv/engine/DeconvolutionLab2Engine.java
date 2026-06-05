package flash.pipeline.deconv.engine;

import flash.pipeline.deconv.DeconvolutionAvailability;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.FloatProcessor;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Drives DeconvolutionLab2 through its synchronous Java API
 * ({@code deconvolution.Deconvolution.deconvolve(RealSignal, RealSignal)}).
 *
 * <p>The earlier implementation invoked the {@code "DeconvolutionLab2 Run"} macro command via
 * {@link ij.IJ#run(String, String)}. That command launches the deconvolution on a background
 * thread and opens DeconvolutionLab2's own "Monitor of run" table window. Because the macro call
 * returned before the worker finished, the engine checked the ImageJ window list too early and
 * reported "did not produce an output image", while the real output appeared later as an orphaned
 * window and the monitor dialogs were left open. Calling the Java API directly is synchronous,
 * needs no temporary files, opens no windows, and — with {@code -monitor no} — never creates the
 * monitor dialogs.</p>
 */
public final class DeconvolutionLab2Engine implements DeconvolutionEngine {

    private static final List<Algorithm> SUPPORTED_ALGORITHMS = Collections.unmodifiableList(
            Arrays.asList(
                    Algorithm.RL,
                    Algorithm.RL_TV,
                    Algorithm.TIKHONOV,
                    Algorithm.WIENER,
                    Algorithm.LANDWEBER
            )
    );
    private static final LabApiStrategy DETECTED_LAB_API = detectLabApiStrategy();

    private final AvailabilityProbe availabilityProbe;
    private final LabApiStrategy labApiStrategy;

    public DeconvolutionLab2Engine() {
        this(
                new AvailabilityProbe() {
                    @Override
                    public boolean isAvailable() {
                        return DeconvolutionAvailability.isDL2Available();
                    }
                },
                DETECTED_LAB_API
        );
    }

    DeconvolutionLab2Engine(AvailabilityProbe availabilityProbe, LabApiStrategy labApiStrategy) {
        if (availabilityProbe == null) {
            throw new IllegalArgumentException("availabilityProbe is required.");
        }
        this.availabilityProbe = availabilityProbe;
        this.labApiStrategy = labApiStrategy;
    }

    @Override
    public String key() {
        return "DL2";
    }

    @Override
    public String displayName() {
        return "DeconvolutionLab2 (CPU, accurate)";
    }

    @Override
    public String description() {
        return "Academic gold-standard deconvolution. Supports RL, RL-TV, Tikhonov, Wiener, Landweber. Slower than GPU but more accurate.";
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
        if (labApiStrategy == null) {
            throw new EnginePluginMissingException(
                    "DeconvolutionLab2 is installed but its synchronous Java API "
                            + "(deconvolution.Deconvolution / imagej.IJImager) could not be initialised in this runtime.");
        }

        ImagePlus result;
        try {
            result = labApiStrategy.run(stack, psf, params);
        } catch (DeconvolutionException e) {
            throw e;
        } catch (Throwable t) {
            if (isMissingDependencyFailure(t)) {
                throw new EnginePluginMissingException(
                        "DeconvolutionLab2 runtime classes are missing or unusable for " + displayName() + '.',
                        t
                );
            }
            throw new DeconvolutionException("DeconvolutionLab2 deconvolution failed.", t);
        }

        if (result == null) {
            throw new DeconvolutionException("DeconvolutionLab2 did not produce an output image.");
        }

        preserveMetadata(stack, result, stack.getShortTitle() + "-deconv-dl2");
        validateResultDimensions(stack, result);
        return result;
    }

    /**
     * Builds the DeconvolutionLab2 command string for an API run. No {@code -image}/{@code -psf}
     * (the signals are passed directly) and no {@code -out} (the result is returned in memory), so
     * nothing is read from or written to disk and no image window is shown. {@code -monitor no}
     * suppresses the "Monitor of run" table window; {@code -verbose mute} silences console logging.
     */
    static String buildApiCommand(DeconvParams params) {
        return "-algorithm " + buildAlgorithmSpec(params) + " -monitor no -verbose mute";
    }

    private static String buildAlgorithmSpec(DeconvParams params) {
        switch (params.getAlgorithm()) {
            case RL:
                return "RL " + params.getIterations();
            case RL_TV:
                return "RLTV " + params.getIterations() + " " + formatDouble(params.getRegularization());
            case TIKHONOV:
                return "TM " + formatDouble(params.getRegularization());
            case WIENER:
                return "WIF " + formatDouble(params.getRegularization());
            case LANDWEBER:
                return "LW " + params.getIterations() + " 1.0";
            default:
                throw new IllegalArgumentException("Unsupported algorithm for DL2: " + params.getAlgorithm());
        }
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
            throw new DeconvolutionException("DeconvolutionLab2 returned an image with unexpected dimensions.");
        }
    }

    private static String formatDouble(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
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

    interface AvailabilityProbe {
        boolean isAvailable();
    }

    interface LabApiStrategy {
        ImagePlus run(ImagePlus stack, ImagePlus psf, DeconvParams params) throws Exception;
    }

    private static LabApiStrategy detectLabApiStrategy() {
        return DeconvolutionRunnerStrategy.tryCreate();
    }

    /**
     * Reflective bridge to DeconvolutionLab2's synchronous Java API. DeconvolutionLab2 ships as a
     * Fiji runtime plugin and is not on the Maven compile classpath, so every call is made through
     * reflection. All referenced classes ({@code imagej.IJImager}, {@code deconvolution.Deconvolution},
     * {@code signal.RealSignal}) live in the same jar as {@code deconvolutionlab.Lab}, which the
     * availability probe already requires, so they resolve together or not at all.
     */
    private static final class DeconvolutionRunnerStrategy implements LabApiStrategy {
        private final Method createSignal;      // static signal.RealSignal imagej.IJImager.create(ImagePlus)
        private final Constructor<?> deconCtor; // deconvolution.Deconvolution(String name, String command)
        private final Method deconvolveSignals; // signal.RealSignal deconvolve(RealSignal image, RealSignal psf)
        private final Method getXY;             // float[] signal.RealSignal.getXY(int z)
        private final Field nxField;
        private final Field nyField;
        private final Field nzField;

        private DeconvolutionRunnerStrategy(Method createSignal,
                                            Constructor<?> deconCtor,
                                            Method deconvolveSignals,
                                            Method getXY,
                                            Field nxField,
                                            Field nyField,
                                            Field nzField) {
            this.createSignal = createSignal;
            this.deconCtor = deconCtor;
            this.deconvolveSignals = deconvolveSignals;
            this.getXY = getXY;
            this.nxField = nxField;
            this.nyField = nyField;
            this.nzField = nzField;
        }

        static DeconvolutionRunnerStrategy tryCreate() {
            try {
                ClassLoader loader = lookupClassLoader();
                Class<?> imagerClass = Class.forName("imagej.IJImager", false, loader);
                Class<?> deconClass = Class.forName("deconvolution.Deconvolution", false, loader);
                Class<?> signalClass = Class.forName("signal.RealSignal", false, loader);

                Method create = imagerClass.getMethod("create", ImagePlus.class);
                if (!Modifier.isStatic(create.getModifiers())
                        || !signalClass.isAssignableFrom(create.getReturnType())) {
                    return null;
                }
                Constructor<?> ctor = deconClass.getConstructor(String.class, String.class);
                Method deconvolve = deconClass.getMethod("deconvolve", signalClass, signalClass);
                if (!signalClass.isAssignableFrom(deconvolve.getReturnType())) {
                    return null;
                }
                Method getXY = signalClass.getMethod("getXY", int.class);
                Field nx = signalClass.getField("nx");
                Field ny = signalClass.getField("ny");
                Field nz = signalClass.getField("nz");

                create.setAccessible(true);
                deconvolve.setAccessible(true);
                getXY.setAccessible(true);
                return new DeconvolutionRunnerStrategy(create, ctor, deconvolve, getXY, nx, ny, nz);
            } catch (Throwable ignored) {
                return null;
            }
        }

        @Override
        public ImagePlus run(ImagePlus stack, ImagePlus psf, DeconvParams params) throws Exception {
            String title = stack.getShortTitle() + "-deconv-dl2";
            Object imageSignal = createSignal.invoke(null, stack);
            Object psfSignal = createSignal.invoke(null, psf);
            Object decon = deconCtor.newInstance(title, buildApiCommand(params));
            Object output = deconvolveSignals.invoke(decon, imageSignal, psfSignal);
            if (output == null) {
                return null;
            }
            return signalToImagePlus(output, title);
        }

        /**
         * Converts a DeconvolutionLab2 {@code RealSignal} back into an in-memory float ImagePlus.
         * {@code IJImager.create} populates the signal via {@code setXY(z, processor.getPixels())},
         * so each {@code getXY(z)} slice is already in ImageJ's row-major float layout and maps
         * directly onto a {@link FloatProcessor}. No window is shown.
         */
        private ImagePlus signalToImagePlus(Object signal, String title) throws Exception {
            int nx = nxField.getInt(signal);
            int ny = nyField.getInt(signal);
            int nz = nzField.getInt(signal);
            if (nx <= 0 || ny <= 0 || nz <= 0) {
                return null;
            }
            int planeSize = nx * ny;
            ImageStack stack = new ImageStack(nx, ny);
            for (int z = 0; z < nz; z++) {
                Object raw = getXY.invoke(signal, Integer.valueOf(z));
                float[] pixels = raw instanceof float[] ? (float[]) raw : null;
                if (pixels == null || pixels.length != planeSize) {
                    float[] resized = new float[planeSize];
                    if (pixels != null) {
                        System.arraycopy(pixels, 0, resized, 0, Math.min(pixels.length, planeSize));
                    }
                    pixels = resized;
                }
                stack.addSlice(new FloatProcessor(nx, ny, pixels, null));
            }
            return new ImagePlus(title, stack);
        }
    }

    private static ClassLoader lookupClassLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader != null ? loader : DeconvolutionLab2Engine.class.getClassLoader();
    }
}
