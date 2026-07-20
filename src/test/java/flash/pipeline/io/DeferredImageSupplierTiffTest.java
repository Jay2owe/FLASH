package flash.pipeline.io;

import ij.IJ;
import ij.ImagePlus;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Behaviour tests for {@link DeferredImageSupplier}'s TIFF-folder mode.
 * Container-mode behaviour is covered by analyses' integration tests
 * and by {@code LifIOTest}.
 */
public class DeferredImageSupplierTiffTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void constructor_emptyList_throws() {
        try {
            new DeferredImageSupplier(Collections.<File>emptyList(), "demo");
            fail("Expected IllegalArgumentException for empty list");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("empty"));
        }
    }

    @Test
    public void constructor_nullList_throws() {
        try {
            new DeferredImageSupplier((List<File>) null, "demo");
            fail("Expected IllegalArgumentException for null list");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("empty"));
        }
    }

    @Test
    public void totalSeries_matchesFileCount() throws Exception {
        File dir = temp.newFolder("tiff-folder-3");
        List<File> files = createTiffs(dir, "alpha", "beta", "gamma");

        DeferredImageSupplier supplier = new DeferredImageSupplier(files, "demo");

        assertEquals(3, supplier.getTotalSeries());
        assertTrue(supplier.isTiffFolderMode());
    }

    @Test
    public void getSeriesName_synthesisesContainerDashBasename() throws Exception {
        File dir = temp.newFolder("tiff-folder-name");
        List<File> files = createTiffs(dir, "alpha", "beta");

        DeferredImageSupplier supplier = new DeferredImageSupplier(files, "demo");

        assertEquals("demo - alpha", supplier.getSeriesName(0));
        assertEquals("demo - beta", supplier.getSeriesName(1));
    }

    @Test
    public void getAllSeriesNames_returnsAllInOrder() throws Exception {
        File dir = temp.newFolder("tiff-folder-names");
        List<File> files = createTiffs(dir, "alpha", "beta", "gamma");

        DeferredImageSupplier supplier = new DeferredImageSupplier(files, "demo");

        List<String> names = supplier.getAllSeriesNames();
        assertEquals(Arrays.asList("demo - alpha", "demo - beta", "demo - gamma"), names);
    }

    @Test
    public void getAllSeriesNames_returnsDispatcherSortedOrder() throws Exception {
        File dir = temp.newFolder("tiff-folder-sorted-names");
        createTiffs(dir, "Zebra", "alpha", "Beta");

        DeferredImageSupplier supplier =
                new DeferredImageSupplier(ImageSourceDispatcher.listTiffs(dir), "demo");

        List<String> names = supplier.getAllSeriesNames();
        assertEquals(Arrays.asList("demo - alpha", "demo - Beta", "demo - Zebra"), names);
    }

    @Test
    public void openSeriesMaterialized_returnsImagePlus() throws Exception {
        File dir = temp.newFolder("tiff-folder-open");
        List<File> files = createTiffs(dir, "first");

        DeferredImageSupplier supplier = new DeferredImageSupplier(files, "demo");
        ImagePlus imp = supplier.openSeriesMaterialized(0);

        try {
            assertNotNull("openSeriesMaterialized(0) must not return null", imp);
            assertEquals(8, imp.getWidth());
            assertEquals(6, imp.getHeight());
            assertEquals("demo - first", imp.getTitle());
        } finally {
            if (imp != null) imp.close();
        }
    }

    @Test
    public void openSeriesMaterialized_outOfRange_throws() throws Exception {
        File dir = temp.newFolder("tiff-folder-range");
        List<File> files = createTiffs(dir, "only");

        DeferredImageSupplier supplier = new DeferredImageSupplier(files, "demo");
        try {
            supplier.openSeriesMaterialized(1);
            fail("Expected IllegalArgumentException for out-of-range index");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("out of range"));
        }
    }

    @Test
    public void getContainerFile_returnsParentDirectoryInTiffMode() throws Exception {
        File dir = temp.newFolder("tiff-folder-container");
        List<File> files = createTiffs(dir, "alpha");

        DeferredImageSupplier supplier = new DeferredImageSupplier(files, "demo");

        assertEquals(dir.getAbsolutePath(), supplier.getContainerFile().getAbsolutePath());
    }

    @Test
    public void readTiffFolderMetadata_emptyList_returnsEmpty() throws Exception {
        List<SeriesMeta> metas = DeferredImageSupplier.readTiffFolderMetadata(
                Collections.<File>emptyList(), "demo");
        assertTrue(metas.isEmpty());
    }

    @Test
    public void readTiffFolderMetadata_returnsMetaPerFileInOrder() throws Exception {
        File dir = temp.newFolder("tiff-meta");
        List<File> files = createTiffs(dir, "alpha", "beta");

        List<SeriesMeta> metas = DeferredImageSupplier.readTiffFolderMetadata(files, "demo");

        assertEquals(2, metas.size());
        assertEquals(0, metas.get(0).index);
        assertEquals(1, metas.get(1).index);
        assertEquals("demo - alpha", metas.get(0).name);
        assertEquals("demo - beta", metas.get(1).name);
        // 2-D images: Z-slice count is 1.
        assertEquals(1, metas.get(0).nSlices);
        assertEquals(1, metas.get(1).nSlices);
    }

    private List<File> createTiffs(File dir, String... basenames) throws Exception {
        List<File> out = new ArrayList<File>();
        for (String base : basenames) {
            ImagePlus imp = IJ.createImage(base, "8-bit ramp", 8, 6, 1);
            File target = new File(dir, base + ".tif");
            IJ.saveAsTiff(imp, target.getAbsolutePath());
            imp.close();
            assertTrue("synthesised TIFF " + target + " must exist",
                    target.isFile() && target.length() > 0);
            out.add(target);
        }
        return out;
    }
}
