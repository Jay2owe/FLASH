package flash.pipeline.deconv.engine;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import org.junit.Assume;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class Clij2FftEngineTest {

    @Test
    public void deconvolveSharpensABlurredPointAndPreservesDimensions() throws Exception {
        Clij2FftEngine engine = new Clij2FftEngine();
        Assume.assumeTrue("CLIJ2 is not available in this runtime.", engine.isAvailable());

        ImagePlus psf = gaussianPsf("psf", 9, 9, 5, 1.2, 1.2, 0.9);
        ImagePlus blurred = blurredPoint("blurred", 32, 32, 8, psf, 100.0f);
        ImagePlus result = null;
        try {
            result = engine.deconvolve(
                    blurred,
                    psf,
                    DeconvParams.builder(Algorithm.RL)
                            .iterations(15)
                            .edgeHandling(EdgeHandling.REFLECT)
                            .build()
            );

            assertEquals(blurred.getWidth(), result.getWidth());
            assertEquals(blurred.getHeight(), result.getHeight());
            assertEquals(blurred.getStackSize(), result.getStackSize());
            assertTrue(peakValue(result) >= peakValue(blurred) * 1.5);
        } finally {
            close(result);
            close(blurred);
            close(psf);
        }
    }

    private static ImagePlus gaussianPsf(String title,
                                         int width,
                                         int height,
                                         int depth,
                                         double sigmaX,
                                         double sigmaY,
                                         double sigmaZ) {
        ImageStack stack = new ImageStack(width, height);
        int centerX = width / 2;
        int centerY = height / 2;
        int centerZ = depth / 2;
        double sum = 0.0;
        float[][] planes = new float[depth][width * height];
        for (int z = 0; z < depth; z++) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    double dx = x - centerX;
                    double dy = y - centerY;
                    double dz = z - centerZ;
                    double value = Math.exp(
                            -((dx * dx) / (2.0 * sigmaX * sigmaX))
                                    - ((dy * dy) / (2.0 * sigmaY * sigmaY))
                                    - ((dz * dz) / (2.0 * sigmaZ * sigmaZ))
                    );
                    planes[z][y * width + x] = (float) value;
                    sum += value;
                }
            }
        }
        float scale = (float) (1.0 / sum);
        for (int z = 0; z < depth; z++) {
            for (int i = 0; i < planes[z].length; i++) {
                planes[z][i] *= scale;
            }
            stack.addSlice(new FloatProcessor(width, height, planes[z], null));
        }
        return new ImagePlus(title, stack);
    }

    private static ImagePlus blurredPoint(String title,
                                          int width,
                                          int height,
                                          int depth,
                                          ImagePlus psf,
                                          float amplitude) {
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            stack.addSlice(new FloatProcessor(width, height, new float[width * height], null));
        }

        int centerX = width / 2;
        int centerY = height / 2;
        int centerZ = depth / 2;
        int psfCenterX = psf.getWidth() / 2;
        int psfCenterY = psf.getHeight() / 2;
        int psfCenterZ = psf.getStackSize() / 2;
        ImageStack psfStack = psf.getStack();
        for (int z = 0; z < psf.getStackSize(); z++) {
            int outZ = centerZ + (z - psfCenterZ);
            if (outZ < 0 || outZ >= depth) continue;
            FloatProcessor out = (FloatProcessor) stack.getProcessor(outZ + 1);
            for (int y = 0; y < psf.getHeight(); y++) {
                int outY = centerY + (y - psfCenterY);
                if (outY < 0 || outY >= height) continue;
                for (int x = 0; x < psf.getWidth(); x++) {
                    int outX = centerX + (x - psfCenterX);
                    if (outX < 0 || outX >= width) continue;
                    float psfValue = psfStack.getProcessor(z + 1).getf(x, y);
                    out.setf(outX, outY, out.getf(outX, outY) + amplitude * psfValue);
                }
            }
        }
        return new ImagePlus(title, stack);
    }

    private static double peakValue(ImagePlus image) {
        double peak = Double.NEGATIVE_INFINITY;
        ImageStack stack = image.getStack();
        for (int z = 1; z <= stack.getSize(); z++) {
            FloatProcessor ip = (FloatProcessor) stack.getProcessor(z);
            for (int i = 0; i < ip.getPixelCount(); i++) {
                peak = Math.max(peak, ip.getf(i));
            }
        }
        return peak;
    }

    private static void close(ImagePlus image) {
        if (image == null) return;
        image.changes = false;
        image.close();
        image.flush();
    }
}
