package flash.pipeline.click.training.cellpose;

import flash.pipeline.TestConfigFiles;
import flash.pipeline.bin.BinConfig;
import flash.pipeline.click.ClickStore;
import flash.pipeline.click.training.ImagePlusProvider;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.Prefs;
import ij.process.ShortProcessor;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class CellposeDatasetPackagerIntegrationTest {
    private static final String PYTHON_PREF = "flash.pipeline.cellpose.pythonPath";

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private String previousPythonPref;

    @Before
    public void rememberPrefs() {
        previousPythonPref = Prefs.get(PYTHON_PREF, "");
        Prefs.set(PYTHON_PREF, "");
    }

    @After
    public void restorePrefs() {
        Prefs.set(PYTHON_PREF, previousPythonPref == null ? "" : previousPythonPref);
    }

    @Test
    public void tinySyntheticTwoImageDatasetLoadsMasks() throws Exception {
        Path root = temp.newFolder("project").toPath();
        writeChannelConfig(root);

        ClickStore store = new ClickStore();
        store.add(click("ImageA", 2, 1, ClickStore.Verdict.POSITIVE));
        store.add(click("ImageA", 2, 2, ClickStore.Verdict.NEGATIVE));
        store.add(click("ImageB", 2, 1, ClickStore.Verdict.POSITIVE));

        CellposeDatasetPackager.PackagingResult result = new CellposeDatasetPackager().packageDataset(
                root, "integration", 2, store,
                new ImagePlusProvider() {
                    @Override
                    public ImagePlus get(String imageName) {
                        return rawStack(4, 3, 1);
                    }
                },
                new ImagePlusProvider() {
                    @Override
                    public ImagePlus get(String imageName) {
                        if ("ImageA".equals(imageName)) {
                            return labelStack(4, 3, new int[][][] {
                                    { {0, 0, 1}, {1, 0, 2}, {2, 0, 2} }
                            });
                        }
                        return labelStack(4, 3, new int[][][] {
                                { {0, 0, 1}, {1, 1, 1} }
                        });
                    }
                },
                "cellpose_cyto3");

        assertEquals(2, result.imagesWritten);
        assertEquals(2, result.slicesWritten);
        assertEquals(2, result.positiveLabelsRetained);
        assertEquals(1, result.negativeLabelsRemoved);

        ImagePlus imageAMask = IJ.openImage(result.outputDir.resolve("ImageA_C2_z001_masks.tif").toString());
        ImagePlus imageBMask = IJ.openImage(result.outputDir.resolve("ImageB_C2_z001_masks.tif").toString());
        try {
            assertNotNull(imageAMask);
            assertNotNull(imageBMask);
            assertEquals(1, imageAMask.getProcessor().get(0, 0));
            assertEquals(0, imageAMask.getProcessor().get(1, 0));
            assertEquals(1, imageBMask.getProcessor().get(1, 1));
            assertTrue(Files.isRegularFile(result.outputDir.resolve("metadata.json")));
            assertTrue(Files.isRegularFile(result.trainCommandFile));
        } finally {
            close(imageAMask);
            close(imageBMask);
        }
    }

    private static ClickStore.Click click(String image,
                                          int channel,
                                          int label,
                                          ClickStore.Verdict verdict) {
        return new ClickStore.Click(image, channel, label, 1,
                0.0, 0.0, verdict, System.currentTimeMillis());
    }

    private static void writeChannelConfig(Path root) throws IOException {
        BinConfig cfg = TestConfigFiles.basicBinConfig("DAPI", "Iba1");
        cfg.segmentationMethods.clear();
        cfg.addSegmentationMethodToken("classical");
        cfg.addSegmentationMethodToken("cellpose:30:0.4:0.0:gpu=false:model=cellpose_cyto3");
        cfg.clickConfigPresent = true;
        TestConfigFiles.writeChannelConfig(root, cfg);
    }

    private static ImagePlus rawStack(int width, int height, int slices) {
        ImageStack stack = new ImageStack(width, height);
        for (int s = 1; s <= slices; s++) {
            ShortProcessor processor = new ShortProcessor(width, height);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    processor.set(x, y, s * 10 + y * width + x);
                }
            }
            stack.addSlice(processor);
        }
        ImagePlus image = new ImagePlus("raw", stack);
        image.setDimensions(1, slices, 1);
        return image;
    }

    private static ImagePlus labelStack(int width, int height, int[][][] labeledPixelsPerSlice) {
        ImageStack stack = new ImageStack(width, height);
        for (int[][] slicePixels : labeledPixelsPerSlice) {
            ShortProcessor processor = new ShortProcessor(width, height);
            for (int[] pixel : slicePixels) {
                processor.set(pixel[0], pixel[1], pixel[2]);
            }
            stack.addSlice(processor);
        }
        ImagePlus image = new ImagePlus("labels", stack);
        image.setDimensions(1, labeledPixelsPerSlice.length, 1);
        return image;
    }

    private static void close(ImagePlus image) {
        if (image != null) {
            image.changes = false;
            image.close();
            image.flush();
        }
    }
}
