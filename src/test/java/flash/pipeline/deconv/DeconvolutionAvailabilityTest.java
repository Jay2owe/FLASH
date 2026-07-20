package flash.pipeline.deconv;

import ij.Menus;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Hashtable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class DeconvolutionAvailabilityTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void detectionMethodsReturnFalseWithAnEmptyContextClassLoader() throws Exception {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        URLClassLoader emptyLoader = new URLClassLoader(new URL[0], null);
        try {
            Thread.currentThread().setContextClassLoader(emptyLoader);
            DeconvolutionAvailability.clearCache();

            assertFalse(DeconvolutionAvailability.isClij2Available());
            assertFalse(DeconvolutionAvailability.isDL2Available());
            assertFalse(DeconvolutionAvailability.isIterativeDeconvolve3DAvailable());
            assertFalse(DeconvolutionAvailability.isPsfGeneratorAvailable());
        } finally {
            Thread.currentThread().setContextClassLoader(original);
            DeconvolutionAvailability.clearCache();
            emptyLoader.close();
        }
    }

    @Test
    public void installInstructionUrlReturnsKnownUrls() {
        assertNotNull(DeconvolutionAvailability.installInstructionUrl("CLIJ2"));
        assertNotNull(DeconvolutionAvailability.installInstructionUrl("DL2"));
        assertNotNull(DeconvolutionAvailability.installInstructionUrl("IterativeDeconvolve3D"));
        assertNotNull(DeconvolutionAvailability.installInstructionUrl("PsfGenerator"));
    }

    @Test
    public void clearCacheForcesAReprobeAfterTheContextClassLoaderChanges() throws Exception {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        URLClassLoader emptyLoader = new URLClassLoader(new URL[0], null);
        URLClassLoader populatedLoader = compileClass("deconvolutionlab", "Lab");
        try {
            Thread.currentThread().setContextClassLoader(emptyLoader);
            DeconvolutionAvailability.clearCache();
            assertFalse(DeconvolutionAvailability.isDL2Available());

            Thread.currentThread().setContextClassLoader(populatedLoader);
            assertFalse(DeconvolutionAvailability.isDL2Available());

            DeconvolutionAvailability.clearCache();
            assertTrue(DeconvolutionAvailability.isDL2Available());
        } finally {
            Thread.currentThread().setContextClassLoader(original);
            DeconvolutionAvailability.clearCache();
            populatedLoader.close();
            emptyLoader.close();
        }
    }

    @Test
    public void detectsRootPluginClassNamesUsedByInstalledFijiPlugins() throws Exception {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        URLClassLoader populatedLoader = compileClasses("", "PSF_Generator", "Iterative_Deconvolve_3D");
        try {
            Thread.currentThread().setContextClassLoader(populatedLoader);
            DeconvolutionAvailability.clearCache();

            assertTrue(DeconvolutionAvailability.isPsfGeneratorAvailable());
            assertTrue(DeconvolutionAvailability.isIterativeDeconvolve3DAvailable());
        } finally {
            Thread.currentThread().setContextClassLoader(original);
            DeconvolutionAvailability.clearCache();
            populatedLoader.close();
        }
    }

    /*
     * Regression: in real Fiji the FLASH plugin's class loader cannot resolve
     * default-package, standalone .class files in plugins/ (only IJ.PluginClassLoader can),
     * so Class.forName("Iterative_Deconvolve_3D") returns ClassNotFoundException even when
     * the file is correctly installed. The fix routes through ImageJ's command table -
     * the same table IJ.run() uses - which IS authoritative after Fiji has scanned
     * plugins/. This test simulates that situation: empty class loader + populated
     * Menus command table should report available.
     */
    @Test
    public void detectsIterativeDeconvolve3DViaImageJCommandTableWhenClassLoaderCannotResolveIt()
            throws Exception {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        URLClassLoader emptyLoader = new URLClassLoader(new URL[0], null);
        Hashtable<String, String> previousCommands = setPluginsTable(
                "Iterative Deconvolve 3D", "Iterative_Deconvolve_3D");
        try {
            Thread.currentThread().setContextClassLoader(emptyLoader);
            DeconvolutionAvailability.clearCache();

            assertTrue(DeconvolutionAvailability.isIterativeDeconvolve3DAvailable());
        } finally {
            restorePluginsTable(previousCommands);
            Thread.currentThread().setContextClassLoader(original);
            DeconvolutionAvailability.clearCache();
            emptyLoader.close();
        }
    }

    private static Hashtable<String, String> setPluginsTable(String command, String className) throws Exception {
        Field field = Menus.class.getDeclaredField("pluginsTable");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Hashtable<String, String> previous = (Hashtable<String, String>) field.get(null);
        Hashtable<String, String> next = new Hashtable<String, String>();
        if (previous != null) {
            next.putAll(previous);
        }
        next.put(command, className);
        field.set(null, next);
        return previous;
    }

    private static void restorePluginsTable(Hashtable<String, String> previous) throws Exception {
        Field field = Menus.class.getDeclaredField("pluginsTable");
        field.setAccessible(true);
        field.set(null, previous);
    }

    private URLClassLoader compileClass(String packageName, String simpleName) throws Exception {
        return compileClasses(packageName, simpleName);
    }

    private URLClassLoader compileClasses(String packageName, String... simpleNames) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull("Tests require a JDK compiler", compiler);

        File root = temp.newFolder(simpleNames[0].toLowerCase());
        File sourceDir = packageName == null || packageName.trim().isEmpty()
                ? root
                : new File(root, packageName.replace('.', File.separatorChar));
        assertTrue(sourceDir.mkdirs() || sourceDir.isDirectory());

        java.util.List<String> args = new java.util.ArrayList<String>();
        args.add("-d");
        args.add(root.getAbsolutePath());
        for (String simpleName : simpleNames) {
            File sourceFile = new File(sourceDir, simpleName + ".java");
            String source = (packageName == null || packageName.trim().isEmpty() ? "" : "package " + packageName + ";\n")
                    + "public class " + simpleName + " {}\n";
            Files.write(sourceFile.toPath(), source.getBytes(StandardCharsets.UTF_8));
            args.add(sourceFile.getAbsolutePath());
        }

        int exitCode = compiler.run(null, null, null, args.toArray(new String[args.size()]));
        assertEquals(0, exitCode);

        return new URLClassLoader(new URL[]{root.toURI().toURL()}, null);
    }
}
