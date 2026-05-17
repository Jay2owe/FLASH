package flash.pipeline.analyses;

import flash.pipeline.cli.CLIArgumentParser;
import flash.pipeline.cli.CLIConfig;
import flash.pipeline.deconv.DeconvolutionIO;
import flash.pipeline.deconv.engine.Algorithm;
import flash.pipeline.deconv.engine.DeconvParams;
import flash.pipeline.deconv.engine.DeconvolutionEngine;
import flash.pipeline.deconv.engine.DeconvolutionException;
import flash.pipeline.deconv.psf.PsfModel;
import flash.pipeline.deconv.psf.PsfSpec;
import flash.pipeline.intelligence.MetadataDiagnostics;
import flash.pipeline.io.SeriesMeta;
import ij.ImagePlus;
import ij.ImageStack;
import ij.io.Opener;
import ij.process.FloatProcessor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class DeconvolutionAnalysisTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void declaresHeadedModeForInteractiveGuiSetup() {
        assertTrue(new DeconvolutionAnalysis().requiresHeadedMode());
    }

    @Test
    public void headlessRunWritesDeconvolvedOutputWithExpectedDimensionsAndSharperPeak() throws Exception {
        File root = temp.newFolder("deconvolution-analysis");
        File source = new File(root, "synthetic.lif");
        Files.write(source.toPath(), "lif".getBytes(StandardCharsets.UTF_8));

        final ImagePlus blurred = gaussianPointSource("blurred", 64, 64, 16, 2.6, 1.6, 100.0);
        ((FloatProcessor) blurred.getStack().getProcessor(1)).setf(0, -5.0f);
        ((FloatProcessor) blurred.getStack().getProcessor(1)).setf(1, Float.NaN);
        final ImagePlus psf = gaussianPointSource("psf", 15, 15, 9, 1.2, 1.0, 1.0);
        final MetadataDiagnostics.SeriesInfo info = syntheticSeriesInfo();
        final DeconvolutionEngine engine = mockEngine();
        String cliDir = root.getAbsolutePath().replace('\\', '/');

        TestDeconvolutionAnalysis analysis = new TestDeconvolutionAnalysis(source, blurred, psf, info, engine);
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.setCliConfig(CLIArgumentParser.parse(
                "dir=[" + cliDir + "] "
                        + "analysisIndex=2 "
                        + "deconv.enabled=true "
                        + "deconv.engine=DL2 "
                        + "deconv.algorithm=RL "
                        + "deconv.psf=GibsonLanni "
                        + "deconv.iterations=15 "
                        + "deconv.regularization=0.01 "
                        + "deconv.scopeModality=widefield "
                        + "deconv.sampleRI=1.33 "
                        + "deconv.channels=0 "
                        + "deconv.useCache=true"));

        double inputPeak = peak(blurred);
        analysis.execute(root.getAbsolutePath());

        File output = DeconvolutionIO.deconvFile(root, "synthetic", 0);
        assertTrue(output.isFile());
        File mergedOutput = DeconvolutionIO.mergedDeconvFile(root, "synthetic");
        assertTrue(mergedOutput.isFile());
        assertTrue(containsFileNamed(DeconvolutionIO.cacheDir(root), "synthetic_C0.tif"));
        File summaryReport = new File(DeconvolutionIO.deconvOutDir(root), "deconv_summary.txt");
        assertTrue(summaryReport.isFile());
        assertEquals(1, analysis.requestedSpecs.size());
        assertEquals(63, analysis.requestedSpecs.get(0).getSizeX());
        assertEquals(63, analysis.requestedSpecs.get(0).getSizeY());
        assertEquals(15, analysis.requestedSpecs.get(0).getSizeZ());

        ImagePlus written = new Opener().openImage(output.getAbsolutePath());
        ImagePlus merged = new Opener().openImage(mergedOutput.getAbsolutePath());
        assertNotNull(written);
        assertNotNull(merged);
        try {
            assertEquals(blurred.getWidth(), written.getWidth());
            assertEquals(blurred.getHeight(), written.getHeight());
            assertEquals(blurred.getStackSize(), written.getStackSize());
            assertTrue(peak(written) > inputPeak);
            assertEquals(blurred.getWidth(), merged.getWidth());
            assertEquals(blurred.getHeight(), merged.getHeight());
        } finally {
            close(merged);
            close(written);
            close(blurred);
            close(psf);
        }

        List<String> summaryLines = Files.readAllLines(summaryReport.toPath(), StandardCharsets.UTF_8);
        assertEquals("image\tchannel\tengine\talgorithm\titerations\tregularization\tpsfModel\tsizeXYZ\telapsedMs\tpeakRamMB\tcacheHit\twarnings",
                summaryLines.get(0));
        assertTrue(summaryLines.get(1).contains("synthetic"));
        assertTrue(summaryLines.get(2).startsWith("# Batch totals:"));
    }

    @Test
    public void listSeriesJobsFallsBackToPartialMetadataWhenDetailedReadFails() throws Exception {
        File root = temp.newFolder("deconvolution-fallback");
        File source = new File(root, "partial.lif");
        Files.write(source.toPath(), "lif".getBytes(StandardCharsets.UTF_8));

        MetadataFallbackAnalysis analysis = new MetadataFallbackAnalysis();
        List<DeconvolutionAnalysis.SeriesJob> jobs = analysis.listSeriesJobs(root.getAbsolutePath());

        assertEquals(1, jobs.size());
        DeconvolutionAnalysis.SeriesJob job = jobs.get(0);
        MetadataDiagnostics.SeriesInfo info = job.seriesInfo;
        assertEquals("partial.lif", info.file);
        assertEquals(2, info.seriesIndex);
        assertEquals("Sample_A", info.imageName);
        assertEquals(64, info.sizeX);
        assertEquals(48, info.sizeY);
        assertEquals(12, info.sizeZ);
        assertEquals(3, info.sizeC);
        assertEquals(0.20, info.pixelSizeXUm.doubleValue(), 1e-12);
        assertEquals(0.60, info.pixelSizeZUm.doubleValue(), 1e-12);
        assertEquals(3, info.emissionWavelengthNm.length);
    }

    private static MetadataDiagnostics.SeriesInfo syntheticSeriesInfo() {
        MetadataDiagnostics.SeriesInfo info = new MetadataDiagnostics.SeriesInfo();
        info.file = "synthetic.lif";
        info.seriesIndex = 0;
        info.imageName = "synthetic";
        info.sizeX = 64;
        info.sizeY = 64;
        info.sizeZ = 16;
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
                assertNonNegativeFinite(stack);
                ImagePlus output = stack.duplicate();
                int centerX = output.getWidth() / 2;
                int centerY = output.getHeight() / 2;
                int centerZ = (output.getStackSize() / 2) + 1;
                FloatProcessor processor = (FloatProcessor) output.getStack().getProcessor(centerZ);
                processor.setf(centerX, centerY, (float) (peak(output) * 1.5));
                return output;
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

    private static double peak(ImagePlus image) {
        double max = Double.NEGATIVE_INFINITY;
        for (int z = 1; z <= image.getStackSize(); z++) {
            FloatProcessor processor = (FloatProcessor) image.getStack().getProcessor(z);
            float[] pixels = (float[]) processor.getPixels();
            for (float pixel : pixels) {
                if (pixel > max) {
                    max = pixel;
                }
            }
        }
        return max;
    }

    private static void assertNonNegativeFinite(ImagePlus image) {
        for (int z = 1; z <= image.getStackSize(); z++) {
            FloatProcessor processor = (FloatProcessor) image.getStack().getProcessor(z);
            float[] pixels = (float[]) processor.getPixels();
            for (float pixel : pixels) {
                assertTrue("deconvolution input must be finite",
                        !Float.isNaN(pixel) && !Float.isInfinite(pixel));
                assertTrue("deconvolution input must be non-negative", pixel >= 0.0f);
            }
        }
    }

    private static void close(ImagePlus image) {
        if (image == null) return;
        image.changes = false;
        image.close();
        image.flush();
    }

    private static boolean containsFileNamed(File dir, String name) {
        if (dir == null || name == null || !dir.isDirectory()) return false;
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File file : files) {
            if (file.isFile() && name.equals(file.getName())) {
                return true;
            }
            if (file.isDirectory() && containsFileNamed(file, name)) {
                return true;
            }
        }
        return false;
    }

    private static final class TestDeconvolutionAnalysis extends DeconvolutionAnalysis {
        private final File sourceFile;
        private final ImagePlus blurred;
        private final ImagePlus psf;
        private final MetadataDiagnostics.SeriesInfo info;
        private final DeconvolutionEngine engine;
        private final List<PsfSpec> requestedSpecs = new ArrayList<PsfSpec>();

        private TestDeconvolutionAnalysis(File sourceFile,
                                          ImagePlus blurred,
                                          ImagePlus psf,
                                          MetadataDiagnostics.SeriesInfo info,
                                          DeconvolutionEngine engine) {
            this.sourceFile = sourceFile;
            this.blurred = blurred;
            this.psf = psf;
            this.info = info;
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
        protected List<SeriesJob> listSeriesJobs(String directory) {
            return Collections.singletonList(new SeriesJob(sourceFile, 0, "synthetic", info));
        }

        @Override
        protected ImagePlus openSeriesChannel(String directory, int seriesIndex, int channelIndex) {
            return blurred.duplicate();
        }

        @Override
        protected List<DeconvolutionEngine> allEngines() {
            return Collections.singletonList(engine);
        }

        @Override
        protected List<DeconvolutionEngine> availableEngines() {
            return Collections.singletonList(engine);
        }

        @Override
        protected DeconvolutionEngine resolveEngine(String key) {
            return engine;
        }

        @Override
        protected ImagePlus getOrCreatePsf(PsfSpec spec, PsfModel model) {
            requestedSpecs.add(spec);
            return psf.duplicate();
        }

        @Override
        protected void writePsfPreview(ImagePlus psf, PsfSpec spec, PsfModel model, File outputDir) {
            // No-op: the test only asserts the deconvolved image output.
        }

        @Override
        protected long requiredFor3DDeconv(ImagePlus stack) {
            return 1L;
        }

        @Override
        protected long estimatedAvailableMemory() {
            return Long.MAX_VALUE;
        }
    }

    private static final class MetadataFallbackAnalysis extends DeconvolutionAnalysis {
        @Override
        protected List<SeriesMeta> readAllSeriesMetadata(File lifFile) {
            return Collections.singletonList(new SeriesMeta(
                    2,
                    "Sample_A",
                    64,
                    48,
                    12,
                    3,
                    0.20,
                    0.20,
                    0.60,
                    "micron"));
        }

        @Override
        protected MetadataDiagnostics.SeriesInfo readSeriesInfo(File lifFile, int seriesIndex) throws Exception {
            throw new IOException("OME objective metadata unavailable");
        }
    }
}
