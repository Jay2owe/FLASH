package flash.pipeline.deconv;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

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

    private URLClassLoader compileClass(String packageName, String simpleName) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull("Tests require a JDK compiler", compiler);

        File root = temp.newFolder(simpleName.toLowerCase());
        File sourceDir = new File(root, packageName.replace('.', File.separatorChar));
        assertTrue(sourceDir.mkdirs() || sourceDir.isDirectory());

        File sourceFile = new File(sourceDir, simpleName + ".java");
        String source = "package " + packageName + ";\n"
                + "public class " + simpleName + " {}\n";
        Files.write(sourceFile.toPath(), source.getBytes(StandardCharsets.UTF_8));

        int exitCode = compiler.run(null, null, null,
                "-d", root.getAbsolutePath(),
                sourceFile.getAbsolutePath());
        assertEquals(0, exitCode);

        return new URLClassLoader(new URL[]{root.toURI().toURL()}, null);
    }
}
