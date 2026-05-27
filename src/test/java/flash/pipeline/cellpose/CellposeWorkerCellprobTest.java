package flash.pipeline.cellpose;

import flash.pipeline.ui.wizard.JsonIO;
import ij.ImagePlus;
import ij.process.ShortProcessor;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CellposeWorkerCellprobTest {

    @Test
    public void requestWithDumpCellprobSerializesFlag() throws Exception {
        CellposeWorkerRequest request = new CellposeWorkerRequest(
                "v01", 30.0d, 0.4d, 0.0d, true);

        String json = JsonIO.write(CellposePersistentWorker.requestPayloadForTest(
                request, new ImagePlus("input", new ShortProcessor(1, 1))));
        Map<String, Object> parsed = JsonIO.parseObject(json);

        assertEquals(Boolean.TRUE, parsed.get("dump_cellprob"));
    }

    @Test
    public void requestWithoutDumpCellprobOmitsFlagForBackwardCompatibility() {
        CellposeWorkerRequest request = new CellposeWorkerRequest(
                "v01", 30.0d, 0.4d, 0.0d);

        Map<String, Object> payload = CellposePersistentWorker.requestPayloadForTest(
                request, new ImagePlus("input", new ShortProcessor(1, 1)));

        assertFalse(payload.containsKey("dump_cellprob"));
    }

    @Test
    public void requestRejectsNonPositiveDiameter() {
        try {
            new CellposeWorkerRequest("v01", 0.0d, 0.4d, 0.0d);
            fail("Expected non-positive Cellpose diameter to be rejected.");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("greater than 0"));
        }
    }

    @Test
    public void responseWithCellprobPathDeserializesIntoOptionalPath() {
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("cellprob_path", "C:/tmp/v01_cellprob.tif");

        Optional<Path> path = CellposePersistentWorker.cellprobPathForTest(response);

        assertTrue(path.isPresent());
        assertEquals(Paths.get("C:/tmp/v01_cellprob.tif"), path.get());
    }

    @Test
    public void responseMissingCellprobPathDeserializesAsEmpty() {
        Optional<Path> path = CellposePersistentWorker.cellprobPathForTest(
                new LinkedHashMap<String, Object>());

        assertFalse(path.isPresent());
    }
}
