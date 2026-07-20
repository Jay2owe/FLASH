package flash.pipeline.deconv.engine;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import org.junit.Test;

public class EngineExceptionTest {

    @Test(expected = InsufficientGpuMemoryException.class)
    public void clij2ThrowsInsufficientGpuMemoryWhenPreflightEstimateExceedsReportedBudget()
            throws Exception {
        Clij2FftEngine engine = new Clij2FftEngine(
                new Clij2FftEngine.AvailabilityProbe() {
                    @Override
                    public boolean isAvailable() {
                        return true;
                    }
                },
                new Clij2FftEngine.Clij2RuntimeFactory() {
                    @Override
                    public Clij2FftEngine.Clij2Runtime open() {
                        return new Clij2FftEngine.Clij2Runtime() {
                            @Override
                            public long getGpuMemoryInBytes() {
                                return 1L;
                            }

                            @Override
                            public Object push(ImagePlus image) {
                                throw new AssertionError("push must not be called after a failed memory preflight.");
                            }

                            @Override
                            public Object createFloatLike(Object sourceBuffer) {
                                throw new AssertionError("createFloatLike must not be called after a failed memory preflight.");
                            }

                            @Override
                            public void runRichardsonLucy(Object stackBuffer,
                                                           Object psfBuffer,
                                                           Object outputBuffer,
                                                           int iterations,
                                                           float regularization,
                                                           boolean zeroPad) {
                                throw new AssertionError("runRichardsonLucy must not be called after a failed memory preflight.");
                            }

                            @Override
                            public ImagePlus pull(Object outputBuffer) {
                                throw new AssertionError("pull must not be called after a failed memory preflight.");
                            }

                            @Override
                            public void release(Object buffer) {}
                        };
                    }
                }
        );

        ImagePlus stack = syntheticVolume("stack", 32, 32, 8, 1.0f);
        ImagePlus psf = syntheticVolume("psf", 9, 9, 5, 1.0f);
        try {
            engine.deconvolve(stack, psf, DeconvParams.builder(Algorithm.RL).build());
        } finally {
            close(stack);
            close(psf);
        }
    }

    private static ImagePlus syntheticVolume(String title, int width, int height, int depth, float value) {
        ImageStack stack = new ImageStack(width, height);
        float[] plane = new float[width * height];
        for (int i = 0; i < plane.length; i++) {
            plane[i] = value;
        }
        for (int z = 0; z < depth; z++) {
            stack.addSlice(new FloatProcessor(width, height, plane.clone(), null));
        }
        return new ImagePlus(title, stack);
    }

    private static void close(ImagePlus image) {
        if (image == null) return;
        image.changes = false;
        image.close();
        image.flush();
    }
}
