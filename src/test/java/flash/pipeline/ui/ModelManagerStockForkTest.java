package flash.pipeline.ui;

import flash.pipeline.segmentation.catalog.ModelCatalogIO;
import flash.pipeline.segmentation.catalog.ModelEntry;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ModelManagerStockForkTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void duplicateAsCustomCreatesEditableUserEntryThatRoundTrips() throws Exception {
        Path root = temp.newFolder("fork").toPath();
        SegmentationModelManagerController controller = new SegmentationModelManagerController(root);
        ModelEntry stock = controller.get("cellpose_cyto3").get();

        assertTrue(controller.canDuplicate(stock));
        assertFalse(controller.canEdit(stock));

        ModelEntry duplicate = controller.duplicateAsUser(stock.modelKey);
        ModelEntry loaded = ModelCatalogIO.read(root).get(duplicate.modelKey).get();

        assertEquals("Cellpose - cyto3 (copy)", loaded.name);
        assertEquals(ModelEntry.Engine.CELLPOSE, loaded.engine);
        assertEquals(ModelEntry.Source.STOCK_BUILTIN, loaded.source);
        assertEquals(stock.pretrainedModel.get(), loaded.pretrainedModel.get());
        assertEquals(stock.defaults, loaded.defaults);
        assertTrue(ModelCatalogIO.isProjectRegisteredBuiltin(loaded));
        assertTrue(new SegmentationModelManagerController(root).canEdit(loaded));
    }
}
