package flash.pipeline.cellpose;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ShortProcessor;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

public class CellposePersistentWorkerTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void helperRunsThreeRequestsWhenCellposeRuntimeIsConfigured()
            throws Exception {
        CellposeRuntime.Status status = CellposeRuntime.probeConfigured();
        assumeTrue("Cellpose runtime is not configured in this test environment.",
                status.ready);

        ImagePlus input = createStack(16, 16, 1);
        long normalStarted = System.currentTimeMillis();
        ImagePlus normal = Cellpose3DRunner.run(input.duplicate(),
                "cyto3",
                30.0d,
                0.4d,
                0.0d,
                false,
                "Test");
        long normalMs = Math.max(1L, System.currentTimeMillis() - normalStarted);
        assertNotNull(normal);

        Path tempDir = temp.newFolder("persistent-cellpose").toPath();
        Path outDir = temp.newFolder("persistent-cellpose-out").toPath();
        Path inputPath = Cellpose3DRunner.writeInputStack(input, tempDir);
        long persistentStarted = System.currentTimeMillis();
        CellposePersistentWorker worker = new CellposePersistentWorker(inputPath,
                outDir,
                input,
                input,
                "cyto3",
                false,
                "Test");
        try {
            Future<CellposeWorkerResult> r1 = worker.submit(
                    new CellposeWorkerRequest("v01", 20.0d, 0.4d, 0.0d));
            Future<CellposeWorkerResult> r2 = worker.submit(
                    new CellposeWorkerRequest("v02", 30.0d, 0.4d, 0.0d));
            Future<CellposeWorkerResult> r3 = worker.submit(
                    new CellposeWorkerRequest("v03", 40.0d, 0.4d, 0.0d));

            assertResult(r1.get(5L, TimeUnit.MINUTES));
            assertResult(r2.get(5L, TimeUnit.MINUTES));
            assertResult(r3.get(5L, TimeUnit.MINUTES));
        } finally {
            worker.close();
        }
        long persistentMs = Math.max(1L, System.currentTimeMillis() - persistentStarted);

        assertTrue("Persistent helper should finish three requests within 3x "
                        + "one normal Cellpose run. normalMs=" + normalMs
                        + ", persistentMs=" + persistentMs,
                persistentMs <= normalMs * 3L);
    }

    private static void assertResult(CellposeWorkerResult result) {
        assertNotNull(result);
        assertTrue(result.errorText(), !result.hasError());
        assertNotNull(result.labelImage());
    }

    private static ImagePlus createStack(int width, int height, int slices) {
        ImageStack stack = new ImageStack(width, height);
        for (int s = 0; s < slices; s++) {
            ShortProcessor processor = new ShortProcessor(width, height);
            processor.set(width / 2, height / 2, 255);
            stack.addSlice(processor);
        }
        return new ImagePlus("stack", stack);
    }
}
