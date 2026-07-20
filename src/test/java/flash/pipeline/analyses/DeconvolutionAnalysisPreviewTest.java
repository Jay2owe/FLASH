package flash.pipeline.analyses;

import flash.pipeline.deconv.engine.Algorithm;
import flash.pipeline.deconv.engine.DeconvParams;
import flash.pipeline.deconv.engine.DeconvolutionEngine;
import flash.pipeline.deconv.engine.DeconvolutionException;
import flash.pipeline.deconv.psf.PsfModel;
import flash.pipeline.deconv.psf.PsfSpec;
import flash.pipeline.deconv.psf.ScopeModality;
import flash.pipeline.deconv.qc.DeconvPreviewDialog;
import flash.pipeline.intelligence.MetadataDiagnostics;
import flash.pipeline.ui.variations.CropSpec;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import org.junit.Test;

import java.awt.Rectangle;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Stage 01 coverage: the extracted {@code renderPreviewContent} must produce raw/deconvolved
 * stacks from the configured crop without closing the returned images, while releasing all
 * other temporary stacks.
 */
public class DeconvolutionAnalysisPreviewTest {

    @Test
    public void renderPreviewContentProducesOpenStacksAtRequestedCrop() throws Exception {
        ImagePlus blurred = gaussianPointSource("blurred", 64, 64, 8, 2.6, 1.6, 100.0);
        ImagePlus psf = gaussianPointSource("psf", 9, 9, 5, 1.2, 1.0, 1.0);
        DeconvolutionEngine engine = mockEngine();
        PreviewAnalysis analysis = new PreviewAnalysis(blurred, psf, engine);

        DeconvolutionAnalysis.RunSettings settings = previewSettings(8, 0.02);
        DeconvolutionAnalysis.SeriesJob job =
                new DeconvolutionAnalysis.SeriesJob(new File("synthetic.lif"), 0, "synthetic", syntheticSeriesInfo());

        DeconvPreviewDialog.PreviewContent content =
                analysis.renderPreviewContent("ignored", job, settings.channelNames, settings, 0, 32, 32);

        try {
            assertNotNull("preview content must be produced for valid inputs", content);

            // Both stacks must remain open (not flushed) so the modal dialog can show them.
            assertNotNull("raw stack must stay open", content.rawStack.getProcessor());
            assertNotNull("raw stack pixels must stay valid", content.rawStack.getProcessor().getPixels());
            assertNotNull("deconvolved stack must stay open", content.deconvolvedStack.getProcessor());
            assertNotNull("deconvolved stack pixels must stay valid",
                    content.deconvolvedStack.getProcessor().getPixels());

            // Center crop is capped at the requested 32x32, while the Z stack remains navigable.
            assertEquals(32, content.rawStack.getWidth());
            assertEquals(32, content.rawStack.getHeight());
            assertEquals(8, content.rawStack.getStackSize());
            assertEquals(32, content.deconvolvedStack.getWidth());
            assertEquals(32, content.deconvolvedStack.getHeight());
            assertEquals(8, content.deconvolvedStack.getStackSize());

            assertEquals("Raw", content.rawLabel);
            assertTrue("deconvolved label should name the engine",
                    content.deconvolvedLabel.contains(engine.displayName()));
            assertTrue("deconvolved label should name the iteration count",
                    content.deconvolvedLabel.contains("8 iter"));
            assertTrue("deconvolved label should name the PSF model",
                    content.deconvolvedLabel.contains(PsfModel.GIBSON_LANNI.displayName()));
        } finally {
            close(content == null ? null : content.rawStack);
            close(content == null ? null : content.deconvolvedStack);
            close(blurred);
            close(psf);
        }
    }

    @Test
    public void renderPreviewContentClampsCropToImageBounds() throws Exception {
        ImagePlus blurred = gaussianPointSource("blurred", 48, 40, 6, 2.0, 1.4, 80.0);
        ImagePlus psf = gaussianPointSource("psf", 9, 9, 5, 1.2, 1.0, 1.0);
        PreviewAnalysis analysis = new PreviewAnalysis(blurred, psf, mockEngine());

        DeconvolutionAnalysis.RunSettings settings = previewSettings(5, 0.01);
        DeconvolutionAnalysis.SeriesJob job =
                new DeconvolutionAnalysis.SeriesJob(new File("synthetic.lif"), 0, "synthetic", syntheticSeriesInfo());

        // Request a crop larger than the image: it must clamp to the image dimensions.
        DeconvPreviewDialog.PreviewContent content =
                analysis.renderPreviewContent("ignored", job, settings.channelNames, settings, 0, 256, 256);

        try {
            assertNotNull(content);
            assertEquals(48, content.rawStack.getWidth());
            assertEquals(40, content.rawStack.getHeight());
            assertEquals(6, content.rawStack.getStackSize());
        } finally {
            close(content == null ? null : content.rawStack);
            close(content == null ? null : content.deconvolvedStack);
            close(blurred);
            close(psf);
        }
    }

    @Test
    public void renderPreviewContentCanUseFullImageCropSpec() throws Exception {
        ImagePlus blurred = gaussianPointSource("blurred", 52, 44, 5, 2.0, 1.4, 80.0);
        ImagePlus psf = gaussianPointSource("psf", 9, 9, 5, 1.2, 1.0, 1.0);
        PreviewAnalysis analysis = new PreviewAnalysis(blurred, psf, mockEngine());
        DeconvolutionAnalysis.RunSettings settings = previewSettings(5, 0.01);
        DeconvolutionAnalysis.SeriesJob job =
                new DeconvolutionAnalysis.SeriesJob(new File("synthetic.lif"), 0, "synthetic", syntheticSeriesInfo());

        DeconvPreviewDialog.PreviewContent content =
                analysis.renderPreviewContent("ignored", job, settings.channelNames, settings, 0, CropSpec.full());

        try {
            assertNotNull(content);
            assertEquals(52, content.rawStack.getWidth());
            assertEquals(44, content.rawStack.getHeight());
            assertEquals(5, content.rawStack.getStackSize());
        } finally {
            close(content == null ? null : content.rawStack);
            close(content == null ? null : content.deconvolvedStack);
            close(blurred);
            close(psf);
        }
    }

    @Test
    public void renderPreviewContentCanUseCustomRoiCropSpec() throws Exception {
        ImagePlus blurred = gaussianPointSource("blurred", 80, 72, 4, 2.0, 1.4, 80.0);
        ImagePlus psf = gaussianPointSource("psf", 9, 9, 5, 1.2, 1.0, 1.0);
        PreviewAnalysis analysis = new PreviewAnalysis(blurred, psf, mockEngine());
        DeconvolutionAnalysis.RunSettings settings = previewSettings(5, 0.01);
        DeconvolutionAnalysis.SeriesJob job =
                new DeconvolutionAnalysis.SeriesJob(new File("synthetic.lif"), 0, "synthetic", syntheticSeriesInfo());

        DeconvPreviewDialog.PreviewContent content =
                analysis.renderPreviewContent("ignored", job, settings.channelNames, settings,
                        0, CropSpec.custom(new Rectangle(7, 9, 31, 29)));

        try {
            assertNotNull(content);
            assertEquals(31, content.rawStack.getWidth());
            assertEquals(29, content.rawStack.getHeight());
            assertEquals(4, content.rawStack.getStackSize());
        } finally {
            close(content == null ? null : content.rawStack);
            close(content == null ? null : content.deconvolvedStack);
            close(blurred);
            close(psf);
        }
    }

    private static DeconvolutionAnalysis.RunSettings previewSettings(int iterations, double regularization) {
        DeconvolutionAnalysis.RunSettings settings = new DeconvolutionAnalysis.RunSettings();
        settings.enabled = true;
        settings.engineKey = "DL2";
        settings.algorithm = Algorithm.RL;
        settings.psfModel = PsfModel.GIBSON_LANNI;
        settings.scopeModality = ScopeModality.WIDEFIELD;
        settings.iterations = iterations;
        settings.regularization = regularization;
        settings.strictNyquist = false;
        settings.useCache = false;
        settings.skipPreview = false;
        settings.channelNames = new String[]{"C0"};
        settings.selectedChannels = new boolean[]{true};
        settings.sampleRiOverride = Double.valueOf(1.33);
        settings.emissionOverridesNm = new double[]{568.0};
        return settings;
    }

    private static MetadataDiagnostics.SeriesInfo syntheticSeriesInfo() {
        MetadataDiagnostics.SeriesInfo info = new MetadataDiagnostics.SeriesInfo();
        info.file = "synthetic.lif";
        info.seriesIndex = 0;
        info.imageName = "synthetic";
        info.sizeX = 64;
        info.sizeY = 64;
        info.sizeZ = 8;
        info.sizeC = 1;
        info.pixelSizeXUm = Double.valueOf(0.10);
        info.pixelSizeZUm = Double.valueOf(0.30);
        info.objectiveNA = Double.valueOf(1.30);
        info.objectiveImmersion = "oil";
        info.sampleRefractiveIndex = Double.valueOf(1.33);
        info.emissionWavelengthNm = new double[]{568.0};
        return info;
    }

    private static DeconvolutionEngine mockEngine() {
        return new DeconvolutionEngine() {
            @Override
            public String key() {
                return "DL2";
            }

            @Override
            public String displayName() {
                return "Mock DL2";
            }

            @Override
            public String description() {
                return "Test double";
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public List<Algorithm> supportedAlgorithms() {
                return Arrays.asList(Algorithm.RL, Algorithm.RL_TV);
            }

            @Override
            public ImagePlus deconvolve(ImagePlus stack, ImagePlus psf, DeconvParams params) throws DeconvolutionException {
                return stack.duplicate();
            }
        };
    }

    private static ImagePlus gaussianPointSource(String title,
                                                 int width,
                                                 int height,
                                                 int depth,
                                                 double sigmaXy,
                                                 double sigmaZ,
                                                 double peak) {
        ImageStack stack = new ImageStack(width, height);
        double cx = (width - 1) / 2.0;
        double cy = (height - 1) / 2.0;
        double cz = (depth - 1) / 2.0;
        for (int z = 0; z < depth; z++) {
            float[] pixels = new float[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    double dx = x - cx;
                    double dy = y - cy;
                    double dz = z - cz;
                    double exponent = -((dx * dx + dy * dy) / (2.0 * sigmaXy * sigmaXy)
                            + (dz * dz) / (2.0 * sigmaZ * sigmaZ));
                    pixels[(y * width) + x] = (float) (peak * Math.exp(exponent));
                }
            }
            stack.addSlice(new FloatProcessor(width, height, pixels, null));
        }
        return new ImagePlus(title, stack);
    }

    private static void close(ImagePlus image) {
        if (image == null) return;
        image.changes = false;
        image.close();
        image.flush();
    }

    private static final class PreviewAnalysis extends DeconvolutionAnalysis {
        private final ImagePlus channel;
        private final ImagePlus psf;
        private final DeconvolutionEngine engine;

        private PreviewAnalysis(ImagePlus channel, ImagePlus psf, DeconvolutionEngine engine) {
            this.channel = channel;
            this.psf = psf;
            this.engine = engine;
        }

        @Override
        protected boolean isBioFormatsAvailable() {
            return true;
        }

        @Override
        protected boolean isPsfGeneratorAvailable() {
            return true;
        }

        @Override
        protected ImagePlus openSeriesChannel(String directory, int seriesIndex, int channelIndex) {
            return channel.duplicate();
        }

        @Override
        protected DeconvolutionEngine resolveEngine(String key) {
            return engine;
        }

        @Override
        protected ImagePlus getOrCreatePsf(PsfSpec spec, PsfModel model) {
            return psf.duplicate();
        }

        @Override
        protected void writePsfPreview(ImagePlus psf, PsfSpec spec, PsfModel model, File outputDir) {
            // No-op: this test only inspects the returned preview stacks.
        }
    }
}
