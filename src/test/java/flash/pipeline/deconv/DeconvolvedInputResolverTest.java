package flash.pipeline.deconv;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DeconvolvedInputResolverTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private final List<String> logs = new ArrayList<String>();

    @After
    public void tearDown() {
        DeconvolvedInputResolver.resetForTest();
    }

    @Test
    public void mirrorFreshReturnsMirror() throws Exception {
        File root = temp.newFolder("resolver-fresh");
        File original = new File(root, "sample.tif");
        Files.write(original.toPath(), "raw".getBytes(StandardCharsets.UTF_8));

        File mirror = DeconvolvedInputResolver.mirrorPathFor(original);
        Files.createDirectories(mirror.getParentFile().toPath());
        Files.write(mirror.toPath(), "deconv".getBytes(StandardCharsets.UTF_8));
        assertTrue(original.setLastModified(1_000L));
        assertTrue(mirror.setLastModified(2_000L));

        captureLogs();
        assertEquals(mirror, DeconvolvedInputResolver.resolveInput(original, true));
        assertTrue(logs.get(0).contains("using deconvolved stack"));
    }

    @Test
    public void mirrorStaleFallsBackToRaw() throws Exception {
        File root = temp.newFolder("resolver-stale");
        File original = new File(root, "sample.tif");
        Files.write(original.toPath(), "raw".getBytes(StandardCharsets.UTF_8));

        File mirror = DeconvolvedInputResolver.mirrorPathFor(original);
        Files.createDirectories(mirror.getParentFile().toPath());
        Files.write(mirror.toPath(), "deconv".getBytes(StandardCharsets.UTF_8));
        assertTrue(original.setLastModified(2_000L));
        assertTrue(mirror.setLastModified(1_000L));

        captureLogs();
        assertEquals(original, DeconvolvedInputResolver.resolveInput(original, true));
        assertTrue(logs.get(0).contains("stale"));
        assertTrue(logs.get(0).contains("using raw"));
    }

    @Test
    public void mirrorMissingFallsBackToRaw() throws Exception {
        File root = temp.newFolder("resolver-missing");
        File original = new File(root, "sample.tif");
        Files.write(original.toPath(), "raw".getBytes(StandardCharsets.UTF_8));

        captureLogs();
        assertEquals(original, DeconvolvedInputResolver.resolveInput(original, true));
        assertTrue(logs.get(0).contains("no deconvolved mirror"));
        assertTrue(logs.get(0).contains("using raw"));
    }

    @Test
    public void toggleOffAlwaysReturnsRaw() throws Exception {
        File root = temp.newFolder("resolver-off");
        File original = new File(root, "sample.tif");
        Files.write(original.toPath(), "raw".getBytes(StandardCharsets.UTF_8));

        File mirror = DeconvolvedInputResolver.mirrorPathFor(original);
        Files.createDirectories(mirror.getParentFile().toPath());
        Files.write(mirror.toPath(), "deconv".getBytes(StandardCharsets.UTF_8));
        assertTrue(mirror.setLastModified(2_000L));

        captureLogs();
        assertEquals(original, DeconvolvedInputResolver.resolveInput(original, false));
        assertTrue(logs.get(0).contains("useDeconv disabled"));
        assertTrue(logs.get(0).contains("using raw"));
    }

    private void captureLogs() {
        logs.clear();
        DeconvolvedInputResolver.setLogSinkForTest(new DeconvolvedInputResolver.LogSink() {
            @Override
            public void log(String message) {
                logs.add(message);
            }
        });
    }
}
