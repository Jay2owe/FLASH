package flash.pipeline.deconv.engine;

import flash.pipeline.deconv.DeconvolutionAvailability;
import flash.pipeline.image.WindowManagerLock;
import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.io.FileSaver;
import ij.measure.Calibration;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class DeconvolutionLab2Engine implements DeconvolutionEngine {

    private static final String COMMAND_NAME = "DeconvolutionLab2 Run";
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
    private final ImageJRunner imageJRunner;
    private final LabApiStrategy labApiStrategy;

    public DeconvolutionLab2Engine() {
        this(
                new AvailabilityProbe() {
                    @Override
                    public boolean isAvailable() {
                        return DeconvolutionAvailability.isDL2Available();
                    }
                },
                new ImageJRunner() {
                    @Override
                    public void run(String command, String options) {
                        IJ.run(command, options);
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

                    @Override
                    public void saveTiffStack(ImagePlus image, File target) throws IOException {
                        FileSaver saver = new FileSaver(image);
                        boolean ok = image.getStackSize() > 1
                                ? saver.saveAsTiffStack(target.getAbsolutePath())
                                : saver.saveAsTiff(target.getAbsolutePath());
                        if (!ok) {
                            throw new IOException("Failed to write TIFF stack to " + target.getAbsolutePath());
                        }
                    }
                },
                DETECTED_LAB_API
        );
    }

    DeconvolutionLab2Engine(AvailabilityProbe availabilityProbe,
                            ImageJRunner imageJRunner,
                            LabApiStrategy labApiStrategy) {
        if (availabilityProbe == null) {
            throw new IllegalArgumentException("availabilityProbe is required.");
        }
        if (imageJRunner == null) {
            throw new IllegalArgumentException("imageJRunner is required.");
        }
        this.availabilityProbe = availabilityProbe;
        this.imageJRunner = imageJRunner;
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

        Throwable apiFailure = null;
        ImagePlus result = null;
        if (labApiStrategy != null) {
            try {
                result = labApiStrategy.run(stack, psf, params);
            } catch (Throwable t) {
                apiFailure = t;
                result = null;
            }
        }

        if (result == null) {
            try {
                result = runMacroFallback(stack, psf, params);
            } catch (Throwable t) {
                if (apiFailure != null) {
                    t.addSuppressed(apiFailure);
                }
                if (isMissingDependencyFailure(t)) {
                    throw new EnginePluginMissingException(
                            "DeconvolutionLab2 runtime classes are missing or unusable for " + displayName() + '.',
                            t
                    );
                }
                if (t instanceof DeconvolutionException) {
                    throw (DeconvolutionException) t;
                }
                throw new DeconvolutionException("DeconvolutionLab2 deconvolution failed.", t);
            }
        }

        preserveMetadata(stack, result, stack.getShortTitle() + "-deconv-dl2");
        validateResultDimensions(stack, result);
        return result;
    }

    private ImagePlus runMacroFallback(ImagePlus stack, ImagePlus psf, DeconvParams params)
            throws DeconvolutionException {
        File tempDir = new File(
                System.getProperty("java.io.tmpdir"),
                "ihf-deconv-" + UUID.randomUUID().toString()
        );
        File stackFile = new File(tempDir, "stack.tif");
        File psfFile = new File(tempDir, "psf.tif");

        try {
            if (!tempDir.mkdirs() && !tempDir.isDirectory()) {
                throw new IOException("Could not create temporary directory " + tempDir.getAbsolutePath());
            }
            imageJRunner.saveTiffStack(stack, stackFile);
            imageJRunner.saveTiffStack(psf, psfFile);

            WindowManagerLock.LOCK.lock();
            try {
                int[] beforeIds = imageJRunner.getWindowIds();
                imageJRunner.run(COMMAND_NAME, buildMacroOptions(stackFile, psfFile, params));
                ImagePlus generated = findGeneratedImage(beforeIds, imageJRunner.getWindowIds(), imageJRunner);
                if (generated == null) {
                    throw new DeconvolutionException("DeconvolutionLab2 did not produce an output image.");
                }
                ImagePlus detached = generated.duplicate();
                detached.setTitle(generated.getTitle());
                disposeImage(generated);
                return detached;
            } finally {
                WindowManagerLock.LOCK.unlock();
            }
        } catch (IOException e) {
            throw new DeconvolutionException("DeconvolutionLab2 temporary-file setup failed.", e);
        } catch (DeconvolutionException e) {
            throw e;
        } catch (Throwable t) {
            if (isMissingDependencyFailure(t)) {
                throw new EnginePluginMissingException(
                        "DeconvolutionLab2 is no longer available during macro execution.",
                        t
                );
            }
            throw new DeconvolutionException("DeconvolutionLab2 macro execution failed.", t);
        } finally {
            deleteRecursively(tempDir.toPath());
        }
    }

    private static String buildMacroOptions(File stackFile, File psfFile, DeconvParams params) {
        return "-image file " + macroPath(stackFile)
                + " -psf file " + macroPath(psfFile)
                + " -algorithm " + buildAlgorithmSpec(params);
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

    private static String macroPath(File file) {
        return file.getAbsolutePath().replace('\\', '/');
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

    private static void disposeImage(ImagePlus image) {
        if (image == null) return;
        image.changes = false;
        try {
            image.close();
        } finally {
            image.flush();
        }
    }

    private static String formatDouble(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) return;
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    Files.deleteIfExists(file);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException exc)
                        throws IOException {
                    Files.deleteIfExists(dir);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {}
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

    interface ImageJRunner {
        void run(String command, String options);
        int[] getWindowIds();
        ImagePlus getImage(int id);
        ImagePlus getImage(String title);
        void saveTiffStack(ImagePlus image, File target) throws IOException;
    }

    interface LabApiStrategy {
        ImagePlus run(ImagePlus stack, ImagePlus psf, DeconvParams params) throws Exception;
    }

    private static LabApiStrategy detectLabApiStrategy() {
        try {
            Class<?> labClass = Class.forName("deconvolutionlab.Lab", false, lookupClassLoader());
            for (Method method : labClass.getMethods()) {
                if (!"run".equals(method.getName())) continue;
                if (!Modifier.isStatic(method.getModifiers())) continue;
                Class<?>[] parameterTypes = method.getParameterTypes();
                if (parameterTypes.length == 3
                        && ImagePlus.class.isAssignableFrom(parameterTypes[0])
                        && ImagePlus.class.isAssignableFrom(parameterTypes[1])
                        && parameterTypes[2] == String.class) {
                    method.setAccessible(true);
                    return new ImagePlusLabApiStrategy(method);
                }
                if (parameterTypes.length == 3
                        && parameterTypes[0] == String.class
                        && parameterTypes[1] == String.class
                        && parameterTypes[2] == String.class) {
                    method.setAccessible(true);
                    return new PathLabApiStrategy(method);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static final class ImagePlusLabApiStrategy implements LabApiStrategy {
        private final Method method;

        private ImagePlusLabApiStrategy(Method method) {
            this.method = method;
        }

        @Override
        public ImagePlus run(ImagePlus stack, ImagePlus psf, DeconvParams params) throws Exception {
            ImagePlus stackCopy = stack.duplicate();
            ImagePlus psfCopy = psf.duplicate();
            Object raw = null;
            ImagePlus rawImage = null;
            try {
                raw = method.invoke(null, stackCopy, psfCopy, buildAlgorithmSpec(params));
                rawImage = coerceImagePlus(raw);
                if (rawImage == null) return null;
                ImagePlus detached = rawImage.duplicate();
                detached.setTitle(rawImage.getTitle());
                return detached;
            } finally {
                if (rawImage != null && rawImage != stackCopy && rawImage != psfCopy) {
                    disposeImage(rawImage);
                }
                if (stackCopy != rawImage) {
                    disposeImage(stackCopy);
                }
                if (psfCopy != rawImage) {
                    disposeImage(psfCopy);
                }
            }
        }
    }

    private static final class PathLabApiStrategy implements LabApiStrategy {
        private final Method method;

        private PathLabApiStrategy(Method method) {
            this.method = method;
        }

        @Override
        public ImagePlus run(ImagePlus stack, ImagePlus psf, DeconvParams params) throws Exception {
            File tempDir = new File(
                    System.getProperty("java.io.tmpdir"),
                    "ihf-deconv-" + UUID.randomUUID().toString()
            );
            File stackFile = new File(tempDir, "stack.tif");
            File psfFile = new File(tempDir, "psf.tif");
            try {
                if (!tempDir.mkdirs() && !tempDir.isDirectory()) {
                    throw new IOException("Could not create temporary directory " + tempDir.getAbsolutePath());
                }
                saveTiff(stack, stackFile, "stack");
                saveTiff(psf, psfFile, "PSF");
                Object raw = method.invoke(
                        null,
                        stackFile.getAbsolutePath(),
                        psfFile.getAbsolutePath(),
                        buildAlgorithmSpec(params)
                );
                ImagePlus image = coerceImagePlus(raw);
                if (image == null) return null;
                ImagePlus detached = image.duplicate();
                detached.setTitle(image.getTitle());
                if (detached != image) {
                    disposeImage(image);
                }
                return detached;
            } finally {
                deleteRecursively(tempDir.toPath());
            }
        }
    }

    private static void saveTiff(ImagePlus image, File target, String label) throws IOException {
        FileSaver saver = new FileSaver(image);
        boolean ok = image.getStackSize() > 1
                ? saver.saveAsTiffStack(target.getAbsolutePath())
                : saver.saveAsTiff(target.getAbsolutePath());
        if (!ok) {
            throw new IOException("Failed to write " + label + " TIFF for DeconvolutionLab2.");
        }
    }

    private static ImagePlus coerceImagePlus(Object raw) {
        if (raw == null) return null;
        if (raw instanceof ImagePlus) {
            return (ImagePlus) raw;
        }
        if (raw instanceof String) {
            ImagePlus byTitle = WindowManager.getImage((String) raw);
            if (byTitle != null) return byTitle;
            File maybeFile = new File((String) raw);
            if (maybeFile.isFile()) {
                return IJ.openImage(maybeFile.getAbsolutePath());
            }
            return null;
        }
        try {
            Method getImagePlus = raw.getClass().getMethod("getImagePlus");
            Object value = getImagePlus.invoke(raw);
            if (value instanceof ImagePlus) {
                return (ImagePlus) value;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static ClassLoader lookupClassLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader != null ? loader : DeconvolutionLab2Engine.class.getClassLoader();
    }
}
