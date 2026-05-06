package flash.pipeline.deconv;

import flash.pipeline.image.GpuProbe;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

public final class DeconvolutionAvailability {

    private static final Map<String, Boolean> CACHE = new HashMap<String, Boolean>();

    private DeconvolutionAvailability() {}

    public static boolean isClij2Available() {
        synchronized (CACHE) {
            if (CACHE.containsKey("CLIJ2")) {
                return CACHE.get("CLIJ2").booleanValue();
            }
        }

        boolean available = classExists("net.haesleinhuepf.clij2.CLIJ2") && hasUsableGpu();
        synchronized (CACHE) {
            CACHE.put("CLIJ2", Boolean.valueOf(available));
        }
        return available;
    }

    public static boolean isDL2Available() {
        return cachedClassAvailability("DL2", "deconvolutionlab.Lab");
    }

    public static boolean isIterativeDeconvolve3DAvailable() {
        return cachedClassAvailability("IterativeDeconvolve3D", "OptiNavLib.Iterative_Deconvolve_3D");
    }

    public static boolean isPsfGeneratorAvailable() {
        return cachedClassAvailability("PsfGenerator", "psfgenerator.PSFGenerator");
    }

    public static String installInstructionUrl(String engineKey) {
        if ("DL2".equals(engineKey)) return "https://sites.imagej.net/DeconvolutionLab2/";
        if ("IterativeDeconvolve3D".equals(engineKey)) {
            return "http://www.optinav.info/Iterative-Deconvolve-3D.htm";
        }
        if ("PsfGenerator".equals(engineKey)) return "http://bigwww.epfl.ch/algorithms/psfgenerator/";
        if ("CLIJ2".equals(engineKey)) return "https://clij.github.io/clij2-docs/installationInFiji";
        return null;
    }

    public static void clearCache() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    private static boolean cachedClassAvailability(String cacheKey, String className) {
        synchronized (CACHE) {
            if (CACHE.containsKey(cacheKey)) {
                return CACHE.get(cacheKey).booleanValue();
            }
        }

        boolean available = classExists(className);
        synchronized (CACHE) {
            CACHE.put(cacheKey, Boolean.valueOf(available));
        }
        return available;
    }

    private static boolean classExists(String className) {
        try {
            return Class.forName(className, false, lookupClassLoader()) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static ClassLoader lookupClassLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader != null ? loader : DeconvolutionAvailability.class.getClassLoader();
    }

    private static boolean hasUsableGpu() {
        try {
            Method method = GpuProbe.class.getMethod("hasUsableGpu");
            Object value = method.invoke(null);
            if (value instanceof Boolean) {
                return ((Boolean) value).booleanValue();
            }
        } catch (Throwable ignored) {}

        try {
            return GpuProbe.probeNvidiaVramMiB() > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
