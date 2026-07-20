package flash.pipeline.click.training.stardist;

import flash.pipeline.click.ClickStore;
import ij.ImagePlus;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class StarDistDatasetPackagerIntegrationTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void syntheticTwoImageBinExportsFolderStructureAndReadableLabels() throws Exception {
        Path root = temp.newFolder("integration").toPath();
        Map<String, ImagePlus> raw = new HashMap<String, ImagePlus>();
        raw.put("Image1", StarDistDatasetPackagerTest.constantImage("raw1", 4, 3, 2, 100));
        raw.put("Image2", StarDistDatasetPackagerTest.constantImage("raw2", 4, 3, 2, 200));

        Map<String, ImagePlus> labels = new HashMap<String, ImagePlus>();
        labels.put("Image1", StarDistDatasetPackagerTest.labelImage("labels1", new int[][][] {
                {{1, 1, 0, 2}, {1, 0, 2, 2}, {0, 0, 0, 0}},
                {{3, 3, 0, 0}, {3, 0, 0, 0}, {0, 0, 0, 0}}
        }));
        labels.put("Image2", StarDistDatasetPackagerTest.labelImage("labels2", new int[][][] {
                {{4, 4, 0, 0}, {0, 0, 0, 0}, {0, 0, 0, 0}},
                {{5, 5, 0, 6}, {5, 0, 6, 6}, {0, 0, 0, 0}}
        }));

        ClickStore clicks = new ClickStore();
        clicks.add(StarDistDatasetPackagerTest.click("Image1", 2, 1, ClickStore.Verdict.POSITIVE));
        clicks.add(StarDistDatasetPackagerTest.click("Image1", 2, 2, ClickStore.Verdict.NEGATIVE));
        clicks.add(StarDistDatasetPackagerTest.click("Image2", 2, 4, ClickStore.Verdict.POSITIVE));
        clicks.add(StarDistDatasetPackagerTest.click("Image2", 2, 6, ClickStore.Verdict.NEGATIVE));

        StarDistDatasetPackager.PackagingResult result = new StarDistDatasetPackager()
                .packageDataset(root, "synthetic-bin", 2, clicks,
                        StarDistDatasetPackagerTest.provider(raw),
                        StarDistDatasetPackagerTest.provider(labels));

        assertEquals(4, result.imagesWritten);
        assertEquals(2, result.positiveLabelsRetained);
        assertEquals(2, result.negativeLabelsRemoved);
        assertTrue(Files.isDirectory(result.outputDir.resolve("raw")));
        assertTrue(Files.isDirectory(result.outputDir.resolve("labels")));
        assertTrue(Files.isRegularFile(result.outputDir.resolve("README.txt")));
        assertTrue(Files.isRegularFile(result.outputDir.resolve("metadata.json")));
        assertEquals(4, countTifs(result.outputDir.resolve("raw")));
        assertEquals(4, countTifs(result.outputDir.resolve("labels")));

        ImagePlus image1Slice1 = StarDistDatasetPackagerTest.open(result.outputDir
                .resolve("labels")
                .resolve("Image1_C2_z001.tif"));
        assertEquals(1, StarDistDatasetPackagerTest.pixel(image1Slice1, 0, 0));
        assertEquals(0, StarDistDatasetPackagerTest.pixel(image1Slice1, 3, 1));

        ImagePlus image2Slice2 = StarDistDatasetPackagerTest.open(result.outputDir
                .resolve("labels")
                .resolve("Image2_C2_z002.tif"));
        assertEquals(5, StarDistDatasetPackagerTest.pixel(image2Slice2, 0, 0));
        assertEquals(0, StarDistDatasetPackagerTest.pixel(image2Slice2, 3, 1));
    }

    private static int countTifs(Path dir) throws Exception {
        int count = 0;
        DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.tif");
        try {
            for (Path ignored : stream) {
                count++;
            }
        } finally {
            stream.close();
        }
        return count;
    }
}
