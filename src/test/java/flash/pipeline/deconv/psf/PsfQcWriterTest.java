package flash.pipeline.deconv.psf;

import flash.pipeline.io.AsyncImageSaver;
import ij.ImagePlus;
import ij.ImageStack;
import ij.io.Opener;
import ij.process.ShortProcessor;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class PsfQcWriterTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Before
    public void setUp() throws Exception {
        resetAsyncImageSaver();
    }

    @After
    public void tearDown() throws Exception {
        resetAsyncImageSaver();
    }

    @Test
    public void writesTiffAndParseableSidecar() throws Exception {
        File outDir = temp.newFolder("psf-qc");
        ImagePlus psf = shortStack();
        try {
            PsfQcWriter.writePsfPreview(psf, spec(), PsfModel.BORN_WOLF, outDir);
            AsyncImageSaver.waitForAll();

            File psfDir = new File(outDir, "PSF");
            File[] tiffs = psfDir.listFiles((dir, name) -> name.endsWith(".tif"));
            File[] sidecars = psfDir.listFiles((dir, name) -> name.endsWith(".txt"));

            assertNotNull(tiffs);
            assertNotNull(sidecars);
            assertEquals(1, tiffs.length);
            assertEquals(1, sidecars.length);

            ImagePlus reopened = new Opener().openImage(tiffs[0].getAbsolutePath());
            try {
                assertNotNull(reopened);
                assertEquals(32, reopened.getBitDepth());
                assertEquals(2, reopened.getStackSize());
            } finally {
                close(reopened);
            }

            String sidecar = new String(Files.readAllBytes(sidecars[0].toPath()), StandardCharsets.UTF_8);
            Map<String, String> parsed = parse(sidecar);
            assertEquals("BORN_WOLF", parsed.get("model"));
            assertEquals("64", parsed.get("sizeX"));
            assertEquals("32", parsed.get("sizeZ"));
            assertTrue(parsed.containsKey("tiff"));
        } finally {
            close(psf);
        }
    }

    private static PsfSpec spec() {
        return new PsfSpec(
                1.40,
                1.515,
                1.450,
                520.0,
                65.0,
                250.0,
                64,
                64,
                32,
                ScopeModality.WIDEFIELD,
                null);
    }

    private static ImagePlus shortStack() {
        ImageStack stack = new ImageStack(4, 4);
        short[] first = new short[16];
        short[] second = new short[16];
        first[0] = 10;
        second[15] = 20;
        stack.addSlice(new ShortProcessor(4, 4, first, null));
        stack.addSlice(new ShortProcessor(4, 4, second, null));
        return new ImagePlus("psf", stack);
    }

    private static Map<String, String> parse(String text) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        for (String line : text.split("\\R")) {
            if (line.trim().isEmpty()) continue;
            int equals = line.indexOf('=');
            if (equals <= 0) continue;
            values.put(line.substring(0, equals), line.substring(equals + 1));
        }
        return values;
    }

    private static void close(ImagePlus image) {
        if (image == null) return;
        image.changes = false;
        image.close();
        image.flush();
    }

    private static void resetAsyncImageSaver() throws Exception {
        java.lang.reflect.Method reset = AsyncImageSaver.class.getDeclaredMethod("resetForTest");
        reset.setAccessible(true);
        reset.invoke(null);
    }
}
