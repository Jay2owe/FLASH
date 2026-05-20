package flash.pipeline.deconv.engine;

import flash.pipeline.deconv.DeconvolutionAvailability;
import flash.pipeline.image.GpuConcurrency;
import flash.pipeline.image.GpuProbe;
import ij.ImagePlus;
import ij.measure.Calibration;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class Clij2FftEngine implements DeconvolutionEngine {

    private static final List<Algorithm> SUPPORTED_ALGORITHMS = Collections.unmodifiableList(
            Arrays.asList(Algorithm.RL, Algorithm.RL_TV)
    );

    private final AvailabilityProbe availabilityProbe;
    private final Clij2RuntimeFactory runtimeFactory;

    public Clij2FftEngine() {
        this(
                new AvailabilityProbe() {
                    @Override
                    public boolean isAvailable() {
                        return DeconvolutionAvailability.isClij2Available();
                    }
                },
                new Clij2RuntimeFactory() {
                    @Override
                    public Clij2Runtime open() throws Exception {
                        return new ReflectiveClij2Runtime();
                    }
                }
        );
    }

    Clij2FftEngine(AvailabilityProbe availabilityProbe, Clij2RuntimeFactory runtimeFactory) {
        if (availabilityProbe == null) {
            throw new IllegalArgumentException("availabilityProbe is required.");
        }
        if (runtimeFactory == null) {
            throw new IllegalArgumentException("runtimeFactory is required.");
        }
        this.availabilityProbe = availabilityProbe;
        this.runtimeFactory = runtimeFactory;
    }

    @Override
    public String key() {
        return "CLIJ2";
    }

    @Override
    public String displayName() {
        return "CLIJ2 (GPU)";
    }

    @Override
    public String description() {
        return "Fast GPU deconvolution via clij2-fft. Best for large 3D datasets and modern graphics cards.";
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

        boolean permitAcquired = false;
        Clij2Runtime runtime = null;
        Object stackBuffer = null;
        Object psfBuffer = null;
        Object outputBuffer = null;

        try {
            GpuConcurrency.gpuSemaphore().acquireUninterruptibly();
            permitAcquired = true;

            runtime = runtimeFactory.open();
            long requiredBytes = estimateGpuBytes(stack, psf);
            long availableBytes = runtime.getGpuMemoryInBytes();
            if (availableBytes > 0L && requiredBytes > availableBytes) {
                throw new InsufficientGpuMemoryException(
                        "CLIJ2 requires approximately " + formatMiB(requiredBytes)
                                + " MiB of GPU memory but only " + formatMiB(availableBytes)
                                + " MiB is available."
                );
            }

            stackBuffer = runtime.push(stack);
            psfBuffer = runtime.push(psf);
            outputBuffer = runtime.createFloatLike(stackBuffer);
            runtime.runRichardsonLucy(
                    stackBuffer,
                    psfBuffer,
                    outputBuffer,
                    params.getIterations(),
                    params.getAlgorithm() == Algorithm.RL_TV ? (float) params.getRegularization() : 0.0f,
                    params.getEdgeHandling() == EdgeHandling.ZERO_PAD
            );

            ImagePlus result = runtime.pull(outputBuffer);
            if (result == null) {
                throw new DeconvolutionException("CLIJ2 returned no deconvolved image.");
            }
            preserveMetadata(stack, result, stack.getShortTitle() + "-deconv-clij2");
            validateResultDimensions(stack, result);
            return result;
        } catch (InsufficientGpuMemoryException e) {
            throw e;
        } catch (Throwable t) {
            if (isMissingDependencyFailure(t)) {
                throw new EnginePluginMissingException(
                        "CLIJ2 runtime classes are missing or unusable for " + displayName() + ".",
                        t
                );
            }
            if (t instanceof DeconvolutionException) {
                throw (DeconvolutionException) t;
            }
            throw new DeconvolutionException("CLIJ2 deconvolution failed.", t);
        } finally {
            releaseQuietly(runtime, outputBuffer);
            releaseQuietly(runtime, psfBuffer);
            releaseQuietly(runtime, stackBuffer);
            if (permitAcquired) {
                GpuConcurrency.gpuSemaphore().release();
            }
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

    private static long estimateGpuBytes(ImagePlus stack, ImagePlus psf) {
        long stackBytes = stackBytes(stack);
        long psfBytes = stackBytes(psf);
        long workingSet = Math.max(stackBytes, psfBytes);
        return workingSet * 4L;
    }

    private static long stackBytes(ImagePlus image) {
        long voxels = (long) image.getWidth() * (long) image.getHeight() * (long) image.getStackSize();
        int bytesPerPixel = Math.max(1, image.getBitDepth() / 8);
        if (image.getBitDepth() == 24) bytesPerPixel = 4;
        return voxels * bytesPerPixel;
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
            throw new DeconvolutionException("CLIJ2 returned an image with unexpected dimensions.");
        }
    }

    private static void releaseQuietly(Clij2Runtime runtime, Object buffer) {
        if (runtime == null || buffer == null) return;
        try {
            runtime.release(buffer);
        } catch (Throwable ignored) {}
    }

    private static String formatMiB(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / 1048576.0);
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

    interface Clij2RuntimeFactory {
        Clij2Runtime open() throws Exception;
    }

    interface Clij2Runtime {
        long getGpuMemoryInBytes() throws Exception;
        Object push(ImagePlus image) throws Exception;
        Object createFloatLike(Object sourceBuffer) throws Exception;
        void runRichardsonLucy(Object stackBuffer,
                               Object psfBuffer,
                               Object outputBuffer,
                               int iterations,
                               float regularization,
                               boolean zeroPad) throws Exception;
        ImagePlus pull(Object outputBuffer) throws Exception;
        void release(Object buffer) throws Exception;
    }

    private static final class ReflectiveClij2Runtime implements Clij2Runtime {
        private final ClassLoader loader;
        private final Object clij2;
        private final Method deconvolutionMethod;

        private ReflectiveClij2Runtime() throws Exception {
            this.loader = lookupClassLoader();
            Class<?> clij2Class = Class.forName("net.haesleinhuepf.clij2.CLIJ2", false, loader);
            this.clij2 = invokeStaticZeroArg(clij2Class, "getInstance");
            if (clij2 == null) {
                throw new ClassNotFoundException("CLIJ2.getInstance() returned null.");
            }
            Class<?> deconvolutionClass = Class.forName(
                    "net.haesleinhuepf.clijx.plugins.DeconvolveRichardsonLucyFFT",
                    false,
                    loader
            );
            this.deconvolutionMethod = findCompatibleMethod(
                    deconvolutionClass,
                    "deconvolveRichardsonLucyFFT",
                    true,
                    new Object[]{clij2, null, null, null, Integer.valueOf(1), Float.valueOf(0.0f), Boolean.FALSE}
            );
        }

        @Override
        public long getGpuMemoryInBytes() throws Exception {
            try {
                Object value = invokeInstanceMethod(clij2, "getGPUMemoryInBytes");
                if (value instanceof Number) {
                    return ((Number) value).longValue();
                }
            } catch (NoSuchMethodException ignored) {}

            int vramMiB = GpuProbe.probeNvidiaVramMiB();
            return vramMiB <= 0 ? 0L : vramMiB * 1024L * 1024L;
        }

        @Override
        public Object push(ImagePlus image) throws Exception {
            return invokeInstanceMethod(clij2, "push", image);
        }

        @Override
        public Object createFloatLike(Object sourceBuffer) throws Exception {
            Object dimensions = invokeInstanceMethod(sourceBuffer, "getDimensions");
            Class<?> nativeTypeClass = Class.forName(
                    "net.haesleinhuepf.clij.coremem.enums.NativeTypeEnum",
                    false,
                    loader
            );
            @SuppressWarnings("unchecked")
            Class<? extends Enum> enumClass = (Class<? extends Enum>) nativeTypeClass.asSubclass(Enum.class);
            Object floatType = Enum.valueOf(enumClass, "Float");
            return invokeInstanceMethod(clij2, "create", dimensions, floatType);
        }

        @Override
        public void runRichardsonLucy(Object stackBuffer,
                                      Object psfBuffer,
                                      Object outputBuffer,
                                      int iterations,
                                      float regularization,
                                      boolean zeroPad) throws Exception {
            Object ok = deconvolutionMethod.invoke(
                    null,
                    clij2,
                    stackBuffer,
                    psfBuffer,
                    outputBuffer,
                    Integer.valueOf(iterations),
                    Float.valueOf(regularization),
                    Boolean.valueOf(zeroPad)
            );
            if (ok instanceof Boolean && !((Boolean) ok).booleanValue()) {
                throw new DeconvolutionException("CLIJ2 Richardson-Lucy reported failure.");
            }
        }

        @Override
        public ImagePlus pull(Object outputBuffer) throws Exception {
            Object pulled = invokeInstanceMethod(clij2, "pull", outputBuffer);
            if (pulled instanceof ImagePlus) {
                return (ImagePlus) pulled;
            }
            throw new DeconvolutionException("CLIJ2 pull() did not return an ImagePlus.");
        }

        @Override
        public void release(Object buffer) throws Exception {
            try {
                invokeInstanceMethod(clij2, "release", buffer);
            } catch (NoSuchMethodException e) {
                invokeInstanceMethod(buffer, "close");
            }
        }
    }

    private static ClassLoader lookupClassLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader != null ? loader : Clij2FftEngine.class.getClassLoader();
    }

    private static Object invokeStaticZeroArg(Class<?> type, String name) throws Exception {
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name)) continue;
            if (!Modifier.isStatic(method.getModifiers())) continue;
            if (method.getParameterTypes().length != 0) continue;
            method.setAccessible(true);
            return method.invoke(null);
        }
        throw new NoSuchMethodException(type.getName() + '#' + name);
    }

    private static Object invokeInstanceMethod(Object target, String name, Object... args) throws Exception {
        Method method = findCompatibleMethod(target.getClass(), name, false, args);
        return method.invoke(target, args);
    }

    private static Method findCompatibleMethod(Class<?> type,
                                               String name,
                                               boolean requireStatic,
                                               Object[] args) throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name)) continue;
            if (Modifier.isStatic(method.getModifiers()) != requireStatic) continue;
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes.length != args.length) continue;
            if (!parametersAccept(parameterTypes, args)) continue;
            method.setAccessible(true);
            return method;
        }
        for (Method method : type.getDeclaredMethods()) {
            if (!method.getName().equals(name)) continue;
            if (Modifier.isStatic(method.getModifiers()) != requireStatic) continue;
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes.length != args.length) continue;
            if (!parametersAccept(parameterTypes, args)) continue;
            method.setAccessible(true);
            return method;
        }
        throw new NoSuchMethodException(type.getName() + '#' + name);
    }

    private static boolean parametersAccept(Class<?>[] parameterTypes, Object[] args) {
        for (int i = 0; i < parameterTypes.length; i++) {
            Object arg = args[i];
            if (arg == null) continue;
            Class<?> parameterType = wrap(parameterTypes[i]);
            if (!parameterType.isInstance(arg) && parameterType != Object.class) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == Integer.TYPE) return Integer.class;
        if (type == Long.TYPE) return Long.class;
        if (type == Float.TYPE) return Float.class;
        if (type == Double.TYPE) return Double.class;
        if (type == Boolean.TYPE) return Boolean.class;
        if (type == Short.TYPE) return Short.class;
        if (type == Byte.TYPE) return Byte.class;
        if (type == Character.TYPE) return Character.class;
        return type;
    }
}
