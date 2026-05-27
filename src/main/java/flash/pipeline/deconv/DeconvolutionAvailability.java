package flash.pipeline.deconv;

import flash.pipeline.image.GpuProbe;
import ij.Menus;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Hashtable;
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

        boolean available = classExists("net.haesleinhuepf.clij2.CLIJ2")
                && classExists("net.haesleinhuepf.clijx.plugins.DeconvolveRichardsonLucyFFT")
                && hasUsableGpu();
        synchronized (CACHE) {
            CACHE.put("CLIJ2", Boolean.valueOf(available));
        }
        return available;
    }

    public static boolean isDL2Available() {
        return cachedClassAvailability("DL2", "deconvolutionlab.Lab");
    }

    public static boolean isIterativeDeconvolve3DAvailable() {
        synchronized (CACHE) {
            if (CACHE.containsKey("IterativeDeconvolve3D")) {
                return CACHE.get("IterativeDeconvolve3D").booleanValue();
            }
        }

        /*
         * Iterative_Deconvolve_3D ships as a standalone default-package .class file in
         * Fiji's plugins/ folder. Fiji's IJ.PluginClassLoader is the only loader that
         * can resolve such files; the FLASH plugin's own class loader (and the thread
         * context loader in some startup paths) cannot, so Class.forName returns
         * ClassNotFoundException even when the file is correctly installed. ImageJ's
         * command table is the authoritative source for whether
         * IJ.run("Iterative Deconvolve 3D", ...) will succeed, so check it first.
         * Fall back to class lookup so unit tests with a populated URLClassLoader
         * continue to pass.
         */
        boolean available = hasImageJCommand("Iterative Deconvolve 3D")
                || classExists("Iterative_Deconvolve_3D")
                || classExists("OptiNavLib.Iterative_Deconvolve_3D");
        synchronized (CACHE) {
            CACHE.put("IterativeDeconvolve3D", Boolean.valueOf(available));
        }
        return available;
    }

    private static boolean hasImageJCommand(String commandName) {
        if (commandName == null || commandName.isEmpty()) {
            return false;
        }
        try {
            Hashtable commands = Menus.getCommands();
            return commands != null && commands.containsKey(commandName);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isPsfGeneratorAvailable() {
        return cachedAnyClassAvailability(
                "PsfGenerator",
                "PSF_Generator",
                "plugins.sage.psfgenerator.PSFGenerator",
                "psfgenerator.PSFGenerator");
    }

    public static String installInstructionUrl(String engineKey) {
        if ("DL2".equals(engineKey)) return "https://bigwww.epfl.ch/deconvolution/deconvolutionlab2/";
        if ("IterativeDeconvolve3D".equals(engineKey)) return "https://www.optinav.info/Iterative-Deconvolve-3D.htm";
        if ("PsfGenerator".equals(engineKey)) return "https://bigwww.epfl.ch/algorithms/psfgenerator/";
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

    private static boolean cachedAnyClassAvailability(String cacheKey, String... classNames) {
        synchronized (CACHE) {
            if (CACHE.containsKey(cacheKey)) {
                return CACHE.get(cacheKey).booleanValue();
            }
        }

        boolean available = false;
        if (classNames != null) {
            for (String className : classNames) {
                if (classExists(className)) {
                    available = true;
                    break;
                }
            }
        }
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
