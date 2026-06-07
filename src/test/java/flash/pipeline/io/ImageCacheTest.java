package flash.pipeline.io;

import flash.pipeline.project.ProjectFile;
import flash.pipeline.project.ProjectFileIO;
import ij.IJ;
import ij.ImagePlus;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ImageCacheTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void getImagesUsesProjectManifestSourceOutsideOutputRoot() throws Exception {
        File outputRoot = temp.newFolder("cache-manifest-output");
        File sourceRoot = temp.newFolder("cache-manifest-source");
        File staleRootTiff = new File(outputRoot, "stale-root.tif");
        File manifestTiff = new File(sourceRoot, "manifest-source.tif");
        writeSyntheticTiff(staleRootTiff, "stale", 3, 2, 1);
        writeSyntheticTiff(manifestTiff, "manifest", 7, 5, 1);

        ProjectFile project = new ProjectFile();
        project.outputRoot = outputRoot.getAbsolutePath();
        project.items.add(projectItem(manifestTiff));
        ProjectFileIO.write(
                FlashProjectLayout.forDirectory(outputRoot.getAbsolutePath()).configurationWriteDir(),
                project);

        ImageCache cache = new ImageCache();
        List<ImagePlus> images = cache.getImages(outputRoot.getAbsolutePath());
        try {
            assertNotNull(images);
            assertEquals(1, images.size());
            assertTrue(images.get(0).getTitle().contains("manifest-source"));
            assertEquals(7, images.get(0).getWidth());
            assertEquals(5, images.get(0).getHeight());
        } finally {
            cache.release();
        }
    }

    private static ProjectFile.Item projectItem(File source) {
        ProjectFile.Item item = new ProjectFile.Item();
        item.path = source.getAbsolutePath();
        item.include = true;
        return item;
    }

    private static void writeSyntheticTiff(File target, String title,
                                           int width, int height, int slices) {
        ImagePlus image = IJ.createImage(title, "8-bit ramp", width, height, slices);
        try {
            IJ.saveAsTiff(image, target.getAbsolutePath());
        } finally {
            image.close();
            image.flush();
        }
    }
}
