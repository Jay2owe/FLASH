package flash.pipeline.integration;

import ij.IJ;
import ij.ImagePlus;
import flash.pipeline.io.DeferredImageSupplier;
import flash.pipeline.io.ImageSourceDispatcher;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end TIFF-folder smoke test for the source-dispatch boundary.
 * <p>
 * SplitAndMerge is intentionally not invoked here: its public execute path
 * still owns channel-setup and processing dialogs when a full pipeline project
 * is not present. This test exercises the headless boundary that the TIFF
 * mode refactor introduced: project directory discovery, deterministic TIFF
 * supplier construction, and materialized Bio-Formats opening of each TIFF
 * as one pipeline series.
 */
public class TiffFolderEndToEndTest {

    private Path tmp;

    @Before
    public void setUp() throws Exception {
        tmp = Files.createTempDirectory("ihf-tiff-folder-e2e");
    }

    @After
    public void tearDown() throws Exception {
        if (tmp != null) {
            deleteRecursively(tmp);
        }
    }

    @Test
    public void tiffInputFolderRunsThroughDispatcherAndSupplier() throws Exception {
        Path input = Files.createDirectories(tmp.resolve("input"));
        createTwoChannelTiff(input.resolve("Sample-Mouse1_LH_CA1.tif"));
        createTwoChannelTiff(input.resolve("Sample-Mouse2_RH_CA1.tif"));
        createTwoChannelTiff(input.resolve("Sample-Mouse3_LH_DG.tif"));

        DeferredImageSupplier supplier =
                ImageSourceDispatcher.createSupplier(tmp.toString());

        assertEquals(3, supplier.getTotalSeries());
        assertEquals("input - Sample-Mouse1_LH_CA1", supplier.getSeriesName(0));

        ImagePlus opened = supplier.openSeriesMaterialized(0);
        try {
            assertNotNull("openSeriesMaterialized(0) must return an image", opened);
            assertEquals(16, opened.getWidth());
            assertEquals(12, opened.getHeight());
            assertEquals(2, opened.getNChannels());
        } finally {
            if (opened != null) opened.close();
            supplier.shutdownPrefetch();
        }
    }

    private static void createTwoChannelTiff(Path target) throws Exception {
        ImagePlus imp = IJ.createImage(target.getFileName().toString(),
                "16-bit", 16, 12, 2, 1, 1);
        try {
            IJ.saveAsTiff(imp, target.toString());
            assertTrue("synthesised TIFF must exist: " + target,
                    Files.isRegularFile(target) && Files.size(target) > 0);
        } finally {
            imp.close();
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.deleteIfExists(file);
                File memoSibling = new File(file.toString() + ".bfmemo");
                if (memoSibling.isFile()) {
                    Files.deleteIfExists(memoSibling.toPath());
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                    throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
