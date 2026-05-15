package flash.pipeline.analyses;

import flash.pipeline.image.ImageOps;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

public class CreateBinFileAnalysisDuplicateThreadSafetyTest {

    @Test
    public void duplicateThreadSafeClonesSharedImageFromEightThreads()
            throws Exception {
        final ImagePlus source = syntheticStack();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        final CountDownLatch start = new CountDownLatch(1);
        List<Future<ImagePlus>> futures = new ArrayList<Future<ImagePlus>>();

        try {
            for (int i = 0; i < 8; i++) {
                futures.add(executor.submit(new Callable<ImagePlus>() {
                    @Override public ImagePlus call() throws Exception {
                        if (!start.await(5L, TimeUnit.SECONDS)) {
                            throw new AssertionError("duplicate workers did not start");
                        }
                        return ImageOps.duplicateThreadSafe(source);
                    }
                }));
            }

            start.countDown();

            for (int i = 0; i < futures.size(); i++) {
                ImagePlus duplicate = futures.get(i).get(5L, TimeUnit.SECONDS);
                assertDuplicateMatchesSource("worker " + i, source, duplicate);
                duplicate.flush();
            }
        } finally {
            executor.shutdownNow();
            assertTrue("duplicate executor did not terminate",
                    executor.awaitTermination(5L, TimeUnit.SECONDS));
            source.flush();
        }
    }

    private static ImagePlus syntheticStack() {
        int width = 17;
        int height = 13;
        int slices = 5;
        ImageStack stack = new ImageStack(width, height);
        for (int z = 1; z <= slices; z++) {
            ShortProcessor processor = new ShortProcessor(width, height);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    processor.set(x, y, z * 1000 + y * width + x);
                }
            }
            stack.addSlice("z" + z, processor);
        }
        ImagePlus image = new ImagePlus("shared synthetic stack", stack);
        image.setDimensions(1, slices, 1);
        Calibration calibration = new Calibration();
        calibration.pixelWidth = 0.4d;
        calibration.pixelHeight = 0.5d;
        calibration.pixelDepth = 1.25d;
        image.setCalibration(calibration);
        return image;
    }

    private static void assertDuplicateMatchesSource(String prefix,
                                                     ImagePlus source,
                                                     ImagePlus duplicate) {
        assertNotNull(prefix, duplicate);
        assertNotSame(prefix, source, duplicate);
        assertEquals(prefix, source.getWidth(), duplicate.getWidth());
        assertEquals(prefix, source.getHeight(), duplicate.getHeight());
        assertEquals(prefix, source.getStackSize(), duplicate.getStackSize());
        assertEquals(prefix, source.getNChannels(), duplicate.getNChannels());
        assertEquals(prefix, source.getNSlices(), duplicate.getNSlices());
        assertEquals(prefix, source.getNFrames(), duplicate.getNFrames());
        assertEquals(prefix, source.getCalibration().pixelWidth,
                duplicate.getCalibration().pixelWidth, 0.0d);
        assertNotSame(prefix, source.getStack().getProcessor(1),
                duplicate.getStack().getProcessor(1));

        int[][] samples = new int[][]{{0, 0}, {3, 4}, {16, 12}, {8, 6}};
        for (int slice = 1; slice <= source.getStackSize(); slice++) {
            ImageProcessor expected = source.getStack().getProcessor(slice);
            ImageProcessor actual = duplicate.getStack().getProcessor(slice);
            for (int i = 0; i < samples.length; i++) {
                int x = samples[i][0];
                int y = samples[i][1];
                assertEquals(prefix + " slice " + slice + " sample " + i,
                        expected.get(x, y), actual.get(x, y));
            }
        }
    }
}
