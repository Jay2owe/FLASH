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
 * Drives DeconvolutionLab2 through its Java API, constructed with {@code Finish.ALIVE} and the
 * command flags {@code -monitor no -verbose mute}. The algorithm runs on the calling thread via
 * {@code Deconvolution.run()} + {@code getOutput()} so multithreading stays on for speed.
 *
 * <p>The earlier implementation invoked the {@code "DeconvolutionLab2 Run"} macro command via
 * {@link ij.IJ#run(String, String)}. That command launches the deconvolution on a background
 * thread and opens DeconvolutionLab2's own "Monitor of run" table window. Because the macro call
 * returned before the worker finished, the engine checked the ImageJ window list too early and
 * reported "did not produce an output image", while the real output appeared later as an orphaned
 * window and the monitor dialogs were left open.</p>
 *
 * <p>The Java API avoids the macro and temporary files, but DeconvolutionLab2 (verified against
 * {@code DeconvolutionLab_2.jar}, 2018) has three sharp edges that each reproduce one of the
 * reported symptoms. All three are neutralised below and carry a guard comment at their call site
 * so they are not "tidied" away:</p>
 * <ul>
 *   <li><b>{@code -monitor no}</b> — leaving it out attaches DeconvolutionLab2's default monitors,
 *   including a {@code TableMonitor}; {@code deconvolve(...)} then pops that monitor's panel up as a
 *   "Monitor of run" dialog. The {@code no} token leaves only a console logger (no window). This is
 *   intentional: removing it makes the monitor dialogs reappear. (DeconvolutionLab2 quirk: in
 *   {@code Command.decodeMonitors} the literal {@code no} does not clear monitors, it adds a
 *   {@code ConsoleMonitor} — hence the harmless {@code Image:}/{@code PSF:}/"Impossible to load the
 *   reference image" console lines. Only the {@code 0} token clears them, but {@code 0} also kills
 *   the console log we want for diagnostics.)</li>
 *   <li><b>Calling-thread execution</b> — the controller defaults multithreading to {@code true},
 *   and in that mode {@code deconvolve(image, psf)} runs the algorithm on a background
 *   {@code Thread} and returns the still-{@code null} {@code deconvolvedImage} <em>immediately</em>.
 *   The engine then sees a null result and throws "did not produce an output image" (so the channel
 *   is never written), while the background worker finishes later and shows an orphan window. Rather
 *   than disable threads ({@code -multithreading no}, which {@code deconvolution.algorithm.Algorithm}
 *   also reads to size its <em>internal</em> parallelism, making it slower), the strategy sets the
 *   image/psf signals and calls {@code run()} on the calling thread — exactly what DeconvolutionLab2
 *   does on its worker thread — then reads {@code getOutput()}. Result: synchronous, but the
 *   algorithm keeps full multithreading. A {@code deconvolve(image, psf)} + {@code -multithreading
 *   no} path is kept only as a fallback for builds lacking that API.</li>
 *   <li><b>{@code Finish.ALIVE}</b> — the {@code Deconvolution(name, command)} constructor defaults
 *   {@code Finish.DIE}, which makes {@code run()} call {@code die()} → {@code SignalCollector.free}
 *   the signals as it returns; {@code Finish.KILL} would even call {@code System.exit(0)} and tear
 *   down Fiji. {@code ALIVE} skips both so the returned signal stays valid while we copy it out.</li>
 * </ul>
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
     * nothing is read from or written to disk and no output window is shown.
     *
     * <p>Two flags are load-bearing — do not drop them when "simplifying" this string:</p>
     * <ul>
     *   <li>{@code -monitor no}: keeps DeconvolutionLab2 from opening its "Monitor of run" table
     *   dialog (leaves only a console logger). Intentional — removing it brings the dialogs back.</li>
     *   <li>{@code -verbose mute}: silences per-iteration console logging.</li>
     * </ul>
     *
     * <p>Deliberately <b>no</b> {@code -multithreading no}: multithreading is left at its default
     * ({@code true}) so the algorithm keeps its internal parallelism and stays fast. The async
     * "returns a null result immediately" problem is solved by running DeconvolutionLab2 on the
     * calling thread (see {@link DeconvolutionRunnerStrategy}), not by disabling threads. The
     * fallback path appends {@code -multithreading no} only when that calling-thread API is
     * unavailable.</p>
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
        private final Constructor<?> deconCtor; // (String,String) or (String,String,Finish) per finishAlive
        private final Object finishAlive;       // deconvolution.Deconvolution$Finish.ALIVE, or null if unavailable
        private final Method deconvolveSignals; // signal.RealSignal deconvolve(RealSignal image, RealSignal psf)
        // Calling-thread API: set image/psf then run() on this thread, so the algorithm keeps its
        // internal multithreading but the call is synchronous. All four are non-null together, or
        // all null (then we fall back to deconvolveSignals + "-multithreading no").
        private final Method runMethod;         // void deconvolution.Deconvolution.run()
        private final Method getOutputMethod;   // signal.RealSignal deconvolution.Deconvolution.getOutput()
        private final Field imageField;         // deconvolution.Deconvolution.image (RealSignal)
        private final Field psfField;           // deconvolution.Deconvolution.psf (RealSignal)
        private final Method getXY;             // float[] signal.RealSignal.getXY(int z)
        private final Field nxField;
        private final Field nyField;
        private final Field nzField;

        private DeconvolutionRunnerStrategy(Method createSignal,
                                            Constructor<?> deconCtor,
                                            Object finishAlive,
                                            Method deconvolveSignals,
                                            Method runMethod,
                                            Method getOutputMethod,
                                            Field imageField,
                                            Field psfField,
                                            Method getXY,
                                            Field nxField,
                                            Field nyField,
                                            Field nzField) {
            this.createSignal = createSignal;
            this.deconCtor = deconCtor;
            this.finishAlive = finishAlive;
            this.deconvolveSignals = deconvolveSignals;
            this.runMethod = runMethod;
            this.getOutputMethod = getOutputMethod;
            this.imageField = imageField;
            this.psfField = psfField;
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

                // Prefer Deconvolution(name, command, Finish.ALIVE) so run() neither frees the
                // signals (Finish.DIE) nor calls System.exit (Finish.KILL) before we read the
                // result. Fall back to the (name, command) form on builds that lack it; the default
                // Finish.DIE frees only the inputs, not the returned output signal.
                Constructor<?> ctor = null;
                Object alive = null;
                try {
                    Class<?> finishClass = Class.forName("deconvolution.Deconvolution$Finish", false, loader);
                    Object aliveConst = enumConstant(finishClass, "ALIVE");
                    if (aliveConst != null) {
                        ctor = deconClass.getConstructor(String.class, String.class, finishClass);
                        alive = aliveConst;
                    }
                } catch (Throwable ignored) {
                    ctor = null;
                    alive = null;
                }
                if (ctor == null) {
                    ctor = deconClass.getConstructor(String.class, String.class);
                    alive = null;
                }

                Method deconvolve = deconClass.getMethod("deconvolve", signalClass, signalClass);
                if (!signalClass.isAssignableFrom(deconvolve.getReturnType())) {
                    return null;
                }

                // Resolve the calling-thread worker API. deconvolve(image, psf) only sets these two
                // fields and then forks run() onto a background thread; doing the same setup here and
                // calling run() ourselves keeps the algorithm multithreaded yet returns synchronously.
                // Best-effort: if any piece is missing we leave them null and fall back to the
                // forced-synchronous deconvolve(...) + "-multithreading no" path.
                Method runMethod = null;
                Method getOutput = null;
                Field imageField = null;
                Field psfField = null;
                try {
                    Method r = deconClass.getMethod("run");
                    Method g = deconClass.getMethod("getOutput");
                    Field img = deconClass.getDeclaredField("image");
                    Field p = deconClass.getDeclaredField("psf");
                    if (signalClass.isAssignableFrom(g.getReturnType())
                            && signalClass.isAssignableFrom(img.getType())
                            && signalClass.isAssignableFrom(p.getType())) {
                        r.setAccessible(true);
                        g.setAccessible(true);
                        img.setAccessible(true);
                        p.setAccessible(true);
                        runMethod = r;
                        getOutput = g;
                        imageField = img;
                        psfField = p;
                    }
                } catch (Throwable ignored) {
                    runMethod = null;
                    getOutput = null;
                    imageField = null;
                    psfField = null;
                }

                Method getXY = signalClass.getMethod("getXY", int.class);
                Field nx = signalClass.getField("nx");
                Field ny = signalClass.getField("ny");
                Field nz = signalClass.getField("nz");

                create.setAccessible(true);
                deconvolve.setAccessible(true);
                getXY.setAccessible(true);
                return new DeconvolutionRunnerStrategy(create, ctor, alive, deconvolve,
                        runMethod, getOutput, imageField, psfField, getXY, nx, ny, nz);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private boolean hasCallingThreadApi() {
            return runMethod != null && getOutputMethod != null
                    && imageField != null && psfField != null;
        }

        @Override
        public ImagePlus run(ImagePlus stack, ImagePlus psf, DeconvParams params) throws Exception {
            String title = stack.getShortTitle() + "-deconv-dl2";
            Object imageSignal = createSignal.invoke(null, stack);
            Object psfSignal = createSignal.invoke(null, psf);

            boolean callingThread = hasCallingThreadApi();
            // Keep multithreading on for the calling-thread path (fast). Only the fallback forces it
            // off, because there the dispatcher's background-thread decision is the sole lever we have.
            String command = callingThread
                    ? buildApiCommand(params)
                    : buildApiCommand(params) + " -multithreading no";
            // Construct with Finish.ALIVE when available so the returned signal is not freed (DIE)
            // and Fiji is never System.exit-ed (KILL) before signalToImagePlus copies it out.
            Object decon = finishAlive != null
                    ? deconCtor.newInstance(title, command, finishAlive)
                    : deconCtor.newInstance(title, command);

            Object output;
            if (callingThread) {
                // Mirror what deconvolve(image, psf) does on its worker thread: set the signals,
                // then run() here. This runs synchronously on the calling thread while the algorithm
                // still parallelizes internally (multithreading stays on), so no orphan window
                // appears and the fully-computed result is available via getOutput().
                imageField.set(decon, imageSignal);
                psfField.set(decon, psfSignal);
                runMethod.invoke(decon);
                output = getOutputMethod.invoke(decon);
            } else {
                // Fallback: deconvolve(...) forks a background thread when multithreading is on and
                // returns null immediately, so we forced it off above to make this call synchronous.
                output = deconvolveSignals.invoke(decon, imageSignal, psfSignal);
            }
            if (output == null) {
                return null;
            }
            return signalToImagePlus(output, title);
        }

        /**
         * Returns the enum constant named {@code name} from {@code enumClass}, or {@code null} if
         * the class is not an enum or has no such constant. Used to resolve
         * {@code deconvolution.Deconvolution$Finish.ALIVE} reflectively without a compile-time
         * dependency on DeconvolutionLab2.
         */
        private static Object enumConstant(Class<?> enumClass, String name) {
            Object[] constants = enumClass.getEnumConstants();
            if (constants == null) {
                return null;
            }
            for (Object constant : constants) {
                if (constant instanceof Enum && name.equals(((Enum<?>) constant).name())) {
                    return constant;
                }
            }
            return null;
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
