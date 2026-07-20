package flash.pipeline.analyses;

import flash.pipeline.cli.CLIArgumentParser;
import flash.pipeline.cli.CLIConfig;
import flash.pipeline.deconv.DeconvolutionIO;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.io.DeferredImageSupplier;
import flash.pipeline.io.ImageSourceDispatcher;
import flash.pipeline.deconv.engine.Algorithm;
import flash.pipeline.deconv.engine.DeconvParams;
import flash.pipeline.deconv.engine.DeconvolutionEngine;
import flash.pipeline.deconv.engine.DeconvolutionException;
import flash.pipeline.deconv.psf.PsfModel;
import flash.pipeline.deconv.psf.PsfSpec;
import flash.pipeline.execution.AnalysisRunCoordinator;
import flash.pipeline.intelligence.MetadataDiagnostics;
import flash.pipeline.io.SeriesMeta;
import flash.pipeline.project.ProjectFile;
import flash.pipeline.project.ProjectFileIO;
import flash.pipeline.runrecord.RunRecord;
import flash.pipeline.runrecord.RunRecordIO;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.io.Opener;
import ij.process.FloatProcessor;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
        final CLIConfig cliConfig = CLIArgumentParser.parse(
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
                        + "deconv.useCache=true");
        analysis.setCliConfig(cliConfig);

        double inputPeak = peak(blurred);
        new AnalysisRunCoordinator().run(analysis, 2, "3D Deconvolution",
                root.getAbsolutePath(), cliConfig, null, "", new Callable<Void>() {
                    @Override public Void call() {
                        analysis.execute(root.getAbsolutePath());
                        return null;
                    }
                });

        File output = DeconvolutionIO.deconvFile(root, "synthetic", 0);
        assertTrue(output.isFile());
        File mergedOutput = DeconvolutionIO.mergedDeconvFile(root, "synthetic");
        assertTrue(mergedOutput.isFile());
        assertTrue(containsFileNamed(DeconvolutionIO.cacheDir(root), "synthetic_C0.tif"));
        File summaryReport = new File(FlashProjectLayout.forDirectory(root.getAbsolutePath()).qcRoot(),
                "deconv_summary.txt");
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

        RunRecord record = latestRecord(root);
        assertTrue(record.extras.containsKey("progressLatest"));
        Object progressObject = record.extras.get("progressLatest");
        assertTrue(progressObject instanceof Map);
        Map<?, ?> progress = (Map<?, ?>) progressObject;
        assertEquals(Boolean.TRUE, progress.get("finished"));
        assertTrue(String.valueOf(progress.get("status")).contains("finished"));
    }

    @Test
    public void headlessRunDetailsRecordPerChannelSettings() throws Exception {
        File root = temp.newFolder("deconvolution-per-channel-details");
        File source = new File(root, "synthetic.lif");
        Files.write(source.toPath(), "lif".getBytes(StandardCharsets.UTF_8));

        final ImagePlus blurred = gaussianPointSource("blurred", 32, 32, 5, 2.0, 1.0, 50.0);
        final ImagePlus psf = gaussianPointSource("psf", 7, 7, 3, 1.0, 1.0, 1.0);
        final MetadataDiagnostics.SeriesInfo info = syntheticSeriesInfo();
        info.sizeC = 2;
        info.emissionWavelengthNm = new double[]{568.0, 647.0};
        TestDeconvolutionAnalysis analysis = new TestDeconvolutionAnalysis(
                source,
                blurred,
                psf,
                info,
                mockEngine());
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.setCliConfig(CLIArgumentParser.parse(
                "dir=[" + root.getAbsolutePath().replace('\\', '/') + "] "
                        + "analysisIndex=2 "
                        + "deconv.enabled=true "
                        + "deconv.engine=DL2 "
                        + "deconv.algorithm=RL "
                        + "deconv.psf=GibsonLanni "
                        + "deconv.iterations=15 "
                        + "deconv.regularization=0.01 "
                        + "deconv.scopeModality=widefield "
                        + "deconv.channels=0,1 "
                        + "deconv.ch1.algorithm=RL_TV "
                        + "deconv.ch1.psf=BornWolf "
                        + "deconv.ch1.iterations=27 "
                        + "deconv.ch1.regularization=0.04 "
                        + "deconv.useCache=false"));

        try {
            analysis.execute(root.getAbsolutePath());

            File details = DeconvolutionIO.detailsFile(root, "synthetic");
            String detailsText = new String(Files.readAllBytes(details.toPath()), StandardCharsets.UTF_8);
            assertTrue(detailsText.contains("Engine: per-channel"));
            assertTrue(detailsText.contains("Algorithm: per-channel"));
            assertTrue(detailsText.contains("PSF Model: per-channel"));
            assertTrue(detailsText.contains("Per-Channel Settings:"));
            assertTrue(detailsText.contains("Channel 1: Engine=Mock DL2, Algorithm=Richardson-Lucy, "
                    + "Iterations=15, Regularization=0.010000, PSF=Gibson & Lanni"));
            assertTrue(detailsText.contains("Channel 2: Engine=Mock DL2, Algorithm=Richardson-Lucy + TV, "
                    + "Iterations=27, Regularization=0.040000, PSF=Born & Wolf"));
        } finally {
            close(blurred);
            close(psf);
        }
    }

    @Test
    public void headlessRunBlocksWhenSelectedPerChannelEngineIsUnavailable() throws Exception {
        File root = temp.newFolder("deconvolution-unavailable-channel-engine");
        File source = new File(root, "synthetic.lif");
        Files.write(source.toPath(), "lif".getBytes(StandardCharsets.UTF_8));

        final ImagePlus blurred = gaussianPointSource("blurred", 32, 32, 5, 2.0, 1.0, 50.0);
        final ImagePlus psf = gaussianPointSource("psf", 7, 7, 3, 1.0, 1.0, 1.0);
        final MetadataDiagnostics.SeriesInfo info = syntheticSeriesInfo();
        info.sizeC = 2;
        info.emissionWavelengthNm = new double[]{568.0, 647.0};
        TestDeconvolutionAnalysis analysis = new TestDeconvolutionAnalysis(
                source,
                blurred,
                psf,
                info,
                mockEngine());
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.setCliConfig(CLIArgumentParser.parse(
                "dir=[" + root.getAbsolutePath().replace('\\', '/') + "] "
                        + "analysisIndex=2 "
                        + "deconv.enabled=true "
                        + "deconv.engine=DL2 "
                        + "deconv.algorithm=RL "
                        + "deconv.scopeModality=widefield "
                        + "deconv.channels=0,1 "
                        + "deconv.ch1.engine=CLIJ2 "
                        + "deconv.useCache=false"));

        try {
            analysis.execute(root.getAbsolutePath());

            assertFalse("batch should stop before writing channel 0",
                    DeconvolutionIO.deconvFile(root, "synthetic", 0).exists());
            assertFalse("batch should stop before writing channel 1",
                    DeconvolutionIO.deconvFile(root, "synthetic", 1).exists());
            assertFalse("batch should stop before writing merged output",
                    DeconvolutionIO.mergedDeconvFile(root, "synthetic").exists());
        } finally {
            close(blurred);
            close(psf);
        }
    }

    @Test
    public void headlessRunBlocksUnsupportedEngineAlgorithmPairBeforeBatchWrites() throws Exception {
        File root = temp.newFolder("deconvolution-unsupported-algorithm");
        File source = new File(root, "synthetic.lif");
        Files.write(source.toPath(), "lif".getBytes(StandardCharsets.UTF_8));

        final ImagePlus blurred = gaussianPointSource("blurred", 32, 32, 5, 2.0, 1.0, 50.0);
        final ImagePlus psf = gaussianPointSource("psf", 7, 7, 3, 1.0, 1.0, 1.0);
        TestDeconvolutionAnalysis analysis = new TestDeconvolutionAnalysis(
                source,
                blurred,
                psf,
                syntheticSeriesInfo(),
                algorithmLimitedEngine("CLIJ2", Arrays.asList(Algorithm.RL)));
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.setCliConfig(CLIArgumentParser.parse(
                "dir=[" + root.getAbsolutePath().replace('\\', '/') + "] "
                        + "analysisIndex=2 "
                        + "deconv.enabled=true "
                        + "deconv.engine=CLIJ2 "
                        + "deconv.algorithm=TIKHONOV "
                        + "deconv.scopeModality=widefield "
                        + "deconv.channels=0 "
                        + "deconv.useCache=false"));

        try {
            analysis.execute(root.getAbsolutePath());

            assertEquals("PSF synthesis should not start for unsupported engine/algorithm pairs",
                    0, analysis.requestedSpecs.size());
            assertFalse("batch should stop before writing a channel output",
                    DeconvolutionIO.deconvFile(root, "synthetic", 0).exists());
            assertFalse("batch should stop before writing merged output",
                    DeconvolutionIO.mergedDeconvFile(root, "synthetic").exists());
        } finally {
            close(blurred);
            close(psf);
        }
    }

    @Test
    public void headlessRunClosesEngineFinalDisplayWindows() throws Exception {
        Assume.assumeFalse("ImageJ windows require a headed runtime.",
                GraphicsEnvironment.isHeadless());
        File root = temp.newFolder("deconvolution-final-display");
        File source = new File(root, "synthetic.lif");
        Files.write(source.toPath(), "lif".getBytes(StandardCharsets.UTF_8));

        final String finalDisplayTitle = "Final Display of synthetic";
        close(WindowManager.getImage(finalDisplayTitle));
        final ImagePlus blurred = gaussianPointSource("blurred", 32, 32, 5, 2.0, 1.0, 50.0);
        final ImagePlus psf = gaussianPointSource("psf", 7, 7, 3, 1.0, 1.0, 1.0);
        TestDeconvolutionAnalysis analysis = new TestDeconvolutionAnalysis(
                source,
                blurred,
                psf,
                syntheticSeriesInfo(),
                finalDisplayLeakingEngine(finalDisplayTitle));
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.setCliConfig(CLIArgumentParser.parse(
                "dir=[" + root.getAbsolutePath().replace('\\', '/') + "] "
                        + "analysisIndex=2 "
                        + "deconv.enabled=true "
                        + "deconv.engine=DL2 "
                        + "deconv.algorithm=RL "
                        + "deconv.scopeModality=widefield "
                        + "deconv.channels=0 "
                        + "deconv.useCache=false"));

        try {
            analysis.execute(root.getAbsolutePath());

            assertTrue(DeconvolutionIO.deconvFile(root, "synthetic", 0).isFile());
            assertFalse("engine-created Final Display window should be closed",
                    WindowManager.getImage(finalDisplayTitle) != null);
        } finally {
            close(WindowManager.getImage(finalDisplayTitle));
            close(blurred);
            close(psf);
        }
    }

    @Test
    public void headlessRunTrimsTrailingBlankDeconvolutionSlice() throws Exception {
        File root = temp.newFolder("deconvolution-trailing-blank");
        File source = new File(root, "synthetic.lif");
        Files.write(source.toPath(), "lif".getBytes(StandardCharsets.UTF_8));

        final ImagePlus blurred = gaussianPointSource("blurred", 32, 32, 5, 2.0, 1.0, 50.0);
        final ImagePlus psf = gaussianPointSource("psf", 7, 7, 3, 1.0, 1.0, 1.0);
        TestDeconvolutionAnalysis analysis = new TestDeconvolutionAnalysis(
                source,
                blurred,
                psf,
                syntheticSeriesInfo(),
                trailingBlankSliceEngine());
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.setCliConfig(CLIArgumentParser.parse(
                "dir=[" + root.getAbsolutePath().replace('\\', '/') + "] "
                        + "analysisIndex=2 "
                        + "deconv.enabled=true "
                        + "deconv.engine=DL2 "
                        + "deconv.algorithm=RL "
                        + "deconv.scopeModality=widefield "
                        + "deconv.channels=0 "
                        + "deconv.useCache=true"));

        ImagePlus written = null;
        ImagePlus merged = null;
        try {
            analysis.execute(root.getAbsolutePath());

            written = new Opener().openImage(DeconvolutionIO.deconvFile(root, "synthetic", 0).getAbsolutePath());
            merged = new Opener().openImage(DeconvolutionIO.mergedDeconvFile(root, "synthetic").getAbsolutePath());
            assertNotNull(written);
            assertNotNull(merged);
            assertEquals("per-channel deconvolved output should drop the blank final slice",
                    blurred.getStackSize() - 1, written.getStackSize());
            assertEquals("merged deconvolved mirror used by quantification should also be n-1 slices",
                    blurred.getStackSize() - 1, merged.getStackSize());
            assertTrue(containsFileNamed(DeconvolutionIO.cacheDir(root), "synthetic_C0.tif"));
        } finally {
            close(merged);
            close(written);
            close(blurred);
            close(psf);
        }
    }

    @Test
    public void headlessRunTrimsRawChannelsInMergedMirrorToDeconvolvedDepth() throws Exception {
        File root = temp.newFolder("deconvolution-trailing-blank-multichannel");
        File source = new File(root, "synthetic.lif");
        Files.write(source.toPath(), "lif".getBytes(StandardCharsets.UTF_8));

        final ImagePlus blurred = gaussianPointSource("blurred", 32, 32, 5, 2.0, 1.0, 50.0);
        final ImagePlus psf = gaussianPointSource("psf", 7, 7, 3, 1.0, 1.0, 1.0);
        final MetadataDiagnostics.SeriesInfo info = syntheticSeriesInfo();
        info.sizeC = 2;
        info.emissionWavelengthNm = new double[]{568.0, 647.0};
        TestDeconvolutionAnalysis analysis = new TestDeconvolutionAnalysis(
                source,
                blurred,
                psf,
                info,
                trailingBlankSliceEngine());
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.setCliConfig(CLIArgumentParser.parse(
                "dir=[" + root.getAbsolutePath().replace('\\', '/') + "] "
                        + "analysisIndex=2 "
                        + "deconv.enabled=true "
                        + "deconv.engine=DL2 "
                        + "deconv.algorithm=RL "
                        + "deconv.scopeModality=widefield "
                        + "deconv.channels=0 "
                        + "deconv.useCache=false"));

        ImagePlus written = null;
        ImagePlus merged = null;
        try {
            analysis.execute(root.getAbsolutePath());

            written = new Opener().openImage(DeconvolutionIO.deconvFile(root, "synthetic", 0).getAbsolutePath());
            merged = new Opener().openImage(DeconvolutionIO.mergedDeconvFile(root, "synthetic").getAbsolutePath());
            assertNotNull(written);
            assertNotNull(merged);
            assertEquals(blurred.getStackSize() - 1, written.getStackSize());
            assertEquals("merged raw channels should be cropped to deconvolved depth",
                    written.getStackSize(), merged.getNSlices());
            assertFalse("unselected raw channel should not get a per-channel deconvolved file",
                    DeconvolutionIO.deconvFile(root, "synthetic", 1).exists());
        } finally {
            close(merged);
            close(written);
            close(blurred);
            close(psf);
        }
    }

    @Test
    public void headlessRunKeepsLegitimateBlankSourceFinalSlice() throws Exception {
        File root = temp.newFolder("deconvolution-source-trailing-blank");
        File source = new File(root, "synthetic.lif");
        Files.write(source.toPath(), "lif".getBytes(StandardCharsets.UTF_8));

        final ImagePlus blurred = gaussianPointSource("blurred", 32, 32, 5, 2.0, 1.0, 50.0);
        zeroFinalSlice(blurred);
        final ImagePlus psf = gaussianPointSource("psf", 7, 7, 3, 1.0, 1.0, 1.0);
        TestDeconvolutionAnalysis analysis = new TestDeconvolutionAnalysis(
                source,
                blurred,
                psf,
                syntheticSeriesInfo(),
                trailingBlankSliceEngine());
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.setCliConfig(CLIArgumentParser.parse(
                "dir=[" + root.getAbsolutePath().replace('\\', '/') + "] "
                        + "analysisIndex=2 "
                        + "deconv.enabled=true "
                        + "deconv.engine=DL2 "
                        + "deconv.algorithm=RL "
                        + "deconv.scopeModality=widefield "
                        + "deconv.channels=0 "
                        + "deconv.useCache=false"));

        ImagePlus written = null;
        ImagePlus merged = null;
        try {
            analysis.execute(root.getAbsolutePath());

            written = new Opener().openImage(DeconvolutionIO.deconvFile(root, "synthetic", 0).getAbsolutePath());
            merged = new Opener().openImage(DeconvolutionIO.mergedDeconvFile(root, "synthetic").getAbsolutePath());
            assertNotNull(written);
            assertNotNull(merged);
            assertEquals("source-blank final slices should not be treated as engine artifacts",
                    blurred.getStackSize(), written.getStackSize());
            assertEquals("merged mirror should preserve legitimate source depth",
                    blurred.getStackSize(), merged.getStackSize());
        } finally {
            close(merged);
            close(written);
            close(blurred);
            close(psf);
        }
    }

    @Test
    public void listSeriesJobsFallsBackToPartialMetadataWhenDetailedReadFails() throws Exception {
        File root = temp.newFolder("deconvolution-fallback");
        File source = new File(root, "partial.lif");
        Files.write(source.toPath(), "lif".getBytes(StandardCharsets.UTF_8));

        MetadataFallbackAnalysis analysis = new MetadataFallbackAnalysis(source);
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
        assertEquals(DeconvolutionIO.ArtifactIdentity.VERSION, job.artifactIdentity.version);
        assertTrue(job.artifactIdentity.isPublishable());
    }

    @Test
    public void listSeriesJobsReadsProjectManifestSourceWhenOutputRootHasNoLif() throws Exception {
        File outputRoot = temp.newFolder("deconvolution-project-output");
        File sourceRoot = temp.newFolder("deconvolution-project-source");
        File source = new File(sourceRoot, "slide.lif");
        Files.write(source.toPath(), "lif".getBytes(StandardCharsets.UTF_8));
        ProjectFile project = new ProjectFile();
        project.outputRoot = outputRoot.getAbsolutePath();
        project.items.add(projectItem(source));
        writeProject(outputRoot, project);

        ProjectManifestAnalysis analysis = new ProjectManifestAnalysis(0);
        List<DeconvolutionAnalysis.SeriesJob> jobs =
                analysis.listSeriesJobs(outputRoot.getAbsolutePath());

        assertEquals(1, jobs.size());
        assertEquals(source.getAbsolutePath(), jobs.get(0).sourceFile.getAbsolutePath());
        assertEquals(source.getAbsolutePath(), analysis.readInfoFile.getAbsolutePath());
        assertEquals(0, jobs.get(0).seriesIndex);
        assertEquals(0, jobs.get(0).sourceSeriesIndex);
        assertEquals("Mouse1_LH_CA1", jobs.get(0).baseName);
    }

    @Test
    public void listSeriesJobsReadsDetailedMetadataWithSourceLocalSeriesIndex() throws Exception {
        File outputRoot = temp.newFolder("deconvolution-project-selected-output");
        File sourceRoot = temp.newFolder("deconvolution-project-selected-source");
        File source = new File(sourceRoot, "slide.lif");
        Files.write(source.toPath(), "lif".getBytes(StandardCharsets.UTF_8));
        ProjectFile project = new ProjectFile();
        project.outputRoot = outputRoot.getAbsolutePath();
        ProjectFile.Item item = projectItem(source);
        item.series.add(Integer.valueOf(5));
        project.items.add(item);
        writeProject(outputRoot, project);

        ProjectManifestAnalysis analysis = new ProjectManifestAnalysis(5);
        List<DeconvolutionAnalysis.SeriesJob> jobs =
                analysis.listSeriesJobs(outputRoot.getAbsolutePath());

        assertEquals(1, jobs.size());
        assertEquals(0, jobs.get(0).seriesIndex);
        assertEquals(5, jobs.get(0).sourceSeriesIndex);
        assertEquals(5, analysis.readInfoSeriesIndex);
        assertEquals(5, jobs.get(0).seriesInfo.seriesIndex);
    }

    @Test
    public void listSeriesJobsLooseTiffUsesSupplierTitleForMirrorBasename() throws Exception {
        File root = temp.newFolder("deconvolution-loose-tiff");
        File source = new File(root, "MouseA_LH_SCN.tif");
        Files.write(source.toPath(), "tif".getBytes(StandardCharsets.UTF_8));

        LooseTiffBasenameAnalysis analysis = new LooseTiffBasenameAnalysis(
                source, root.getName());
        List<DeconvolutionAnalysis.SeriesJob> jobs =
                analysis.listSeriesJobs(root.getAbsolutePath());

        assertEquals(1, jobs.size());
        assertEquals(source.getAbsolutePath(), jobs.get(0).sourceFile.getAbsolutePath());
        assertEquals(0, jobs.get(0).sourceSeriesIndex);
        assertEquals("MouseA_LH_SCN", jobs.get(0).baseName);
        assertEquals(root.getName() + " - MouseA_LH_SCN", jobs.get(0).seriesInfo.imageName);
        assertEquals(DeconvolutionIO.ArtifactIdentity.VERSION,
                jobs.get(0).artifactIdentity.version);
    }

    @Test
    public void missingUnboundOrLegacyJobIdentityIsRejectedBeforePublication() throws Exception {
        File root = temp.newFolder("deconvolution-invalid-identity");
        File source = new File(root, "source.lif");
        Files.write(source.toPath(), "source".getBytes(StandardCharsets.UTF_8));
        MetadataDiagnostics.SeriesInfo info = syntheticSeriesInfo();
        File outputDir = DeconvolutionIO.deconvOutDir(root);

        DeconvolutionAnalysis.SeriesJob previewOnly = new DeconvolutionAnalysis.SeriesJob(
                new File(root, "missing-preview-fixture.lif"), 0, "Region", info);
        assertNull("preview-only job must not invent an artifact identity",
                previewOnly.artifactIdentity);
        assertNull("preview-only job must not fall back to a basename artifact key",
                previewOnly.artifactKey);
        try {
            DeconvolutionAnalysis.requireArtifactIdentitiesForPublication(
                    Collections.singletonList(previewOnly));
            fail("expected missing identity rejection for publication");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("current source/container identity"));
        }

        try {
            new DeconvolutionAnalysis.SeriesJob(
                    source, 0, 0, "Region", info, null);
            fail("expected null identity rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("source/container identity"));
        }

        DeconvolutionIO.ArtifactIdentity unbound = new DeconvolutionIO.ArtifactIdentity(
                DeconvolutionIO.ArtifactIdentity.VERSION, source.length(),
                repeat("ab", 32), 0, "Region");
        try {
            new DeconvolutionAnalysis.SeriesJob(
                    source, 0, 0, "Region", info, unbound);
            fail("expected unbound identity rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("source/container identity"));
        }

        DeconvolutionIO.ArtifactIdentity legacy = DeconvolutionIO.ArtifactIdentity.of(
                flash.pipeline.deconv.DeconvManifest.SourceFingerprint.of(source),
                0, "Region");
        DeconvolutionAnalysis.SeriesJob legacyJob = new DeconvolutionAnalysis.SeriesJob(
                source, 0, 0, "Region", info, legacy);
        try {
            DeconvolutionAnalysis.requireArtifactIdentitiesForPublication(
                    Collections.singletonList(legacyJob));
            fail("expected legacy identity rejection for new publication");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("current source/container identity"));
        }

        DeconvolutionAnalysis.SeriesJob current = new DeconvolutionAnalysis.SeriesJob(
                source, 0, "Region", info);
        DeconvolutionAnalysis.requireArtifactIdentitiesForPublication(
                Collections.singletonList(current));
        assertFalse("identity rejection must happen before output directory publication",
                outputDir.exists());
    }

    @Test
    public void failedRecomputeRemovesStaleOutputsAndSkipsMergedMirror() throws Exception {
        File root = temp.newFolder("deconvolution-stale");
        File source = new File(root, "synthetic.lif");
        Files.write(source.toPath(), "lif".getBytes(StandardCharsets.UTF_8));

        final ImagePlus blurred = gaussianPointSource("blurred", 32, 32, 5, 2.0, 1.0, 50.0);
        final ImagePlus psf = gaussianPointSource("psf", 7, 7, 3, 1.0, 1.0, 1.0);
        TestDeconvolutionAnalysis analysis = new TestDeconvolutionAnalysis(
                source,
                blurred,
                psf,
                syntheticSeriesInfo(),
                failingEngine());
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.setCliConfig(CLIArgumentParser.parse(
                "dir=[" + root.getAbsolutePath().replace('\\', '/') + "] "
                        + "analysisIndex=2 "
                        + "deconv.enabled=true "
                        + "deconv.engine=DL2 "
                        + "deconv.algorithm=RL "
                        + "deconv.scopeModality=widefield "
                        + "deconv.channels=0 "
                        + "deconv.useCache=false"));

        File staleChannel = DeconvolutionIO.deconvFile(root, "synthetic", 0);
        File staleMerged = DeconvolutionIO.mergedDeconvFile(root, "synthetic");
        Files.createDirectories(staleChannel.getParentFile().toPath());
        Files.write(staleChannel.toPath(), "old-channel".getBytes(StandardCharsets.UTF_8));
        Files.write(staleMerged.toPath(), "old-merged".getBytes(StandardCharsets.UTF_8));

        try {
            analysis.execute(root.getAbsolutePath());

            assertFalse("failed recompute must not leave stale per-channel output", staleChannel.exists());
            assertFalse("failed recompute must not leave stale merged deconvolved mirror", staleMerged.exists());
            File details = DeconvolutionIO.detailsFile(root, "synthetic");
            String detailsText = new String(Files.readAllBytes(details.toPath()), StandardCharsets.UTF_8);
            assertTrue(detailsText.contains("synthetic failure"));
            assertTrue(detailsText.contains("Merged deconvolved output skipped"));
        } finally {
            close(blurred);
            close(psf);
        }
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

    private static DeconvolutionEngine algorithmLimitedEngine(final String key,
                                                              final List<Algorithm> algorithms) {
        return new DeconvolutionEngine() {
            @Override
            public String key() {
                return key;
            }

            @Override
            public String displayName() {
                return key + " limited";
            }

            @Override
            public String description() {
                return "Supports only a narrow algorithm set.";
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public List<Algorithm> supportedAlgorithms() {
                return algorithms;
            }

            @Override
            public ImagePlus deconvolve(ImagePlus stack, ImagePlus psf, DeconvParams params) {
                return stack.duplicate();
            }
        };
    }

    private static DeconvolutionEngine failingEngine() {
        return new DeconvolutionEngine() {
            @Override
            public String key() {
                return "DL2";
            }

            @Override
            public String displayName() {
                return "Failing DL2";
            }

            @Override
            public String description() {
                return "Throws for stale-output regression coverage";
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
                throw new DeconvolutionException("synthetic failure");
            }
        };
    }

    private static DeconvolutionEngine trailingBlankSliceEngine() {
        return new DeconvolutionEngine() {
            @Override
            public String key() {
                return "DL2";
            }

            @Override
            public String displayName() {
                return "Trailing-blank DL2";
            }

            @Override
            public String description() {
                return "Returns a valid deconvolved stack with a blank final Z plane.";
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
            public ImagePlus deconvolve(ImagePlus stack, ImagePlus psf, DeconvParams params) {
                ImagePlus output = stack.duplicate();
                FloatProcessor last = (FloatProcessor) output.getStack()
                        .getProcessor(output.getStackSize());
                for (int i = 0; i < last.getPixelCount(); i++) {
                    last.setf(i, 0.0f);
                }
                return output;
            }
        };
    }

    private static DeconvolutionEngine finalDisplayLeakingEngine(final String finalDisplayTitle) {
        return new DeconvolutionEngine() {
            @Override
            public String key() {
                return "DL2";
            }

            @Override
            public String displayName() {
                return "Window-leaking DL2";
            }

            @Override
            public String description() {
                return "Opens a display window like some third-party deconvolution backends.";
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
            public ImagePlus deconvolve(ImagePlus stack, ImagePlus psf, DeconvParams params) {
                ImagePlus finalDisplay = stack.duplicate();
                finalDisplay.setTitle(finalDisplayTitle);
                finalDisplay.show();
                return stack.duplicate();
            }
        };
    }

    private static void zeroFinalSlice(ImagePlus image) {
        FloatProcessor last = (FloatProcessor) image.getStack().getProcessor(image.getStackSize());
        for (int i = 0; i < last.getPixelCount(); i++) {
            last.setf(i, 0.0f);
        }
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

    private static String repeat(String value, int count) {
        StringBuilder out = new StringBuilder(value.length() * count);
        for (int index = 0; index < count; index++) {
            out.append(value);
        }
        return out.toString();
    }

    private static ProjectFile.Item projectItem(File source) {
        ProjectFile.Item item = new ProjectFile.Item();
        item.path = source.getAbsolutePath();
        item.include = true;
        return item;
    }

    private static void writeProject(File outputRoot, ProjectFile project) throws Exception {
        ProjectFileIO.write(
                FlashProjectLayout.forDirectory(outputRoot.getAbsolutePath()).configurationWriteDir(),
                project);
    }

    private static RunRecord latestRecord(File project) {
        File runsDir = FlashProjectLayout.forDirectory(project.getAbsolutePath()).runJsonlWriteDir();
        File[] files = runsDir.listFiles();
        assertNotNull("runs dir should exist", files);
        File latest = null;
        for (File f : files) {
            if (f.getName().endsWith(RunRecordIO.EXTENSION)) {
                latest = f;
            }
        }
        assertNotNull("run record should exist", latest);
        return RunRecordIO.readLatest(latest);
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
        private final File sourceFile;

        private MetadataFallbackAnalysis(File sourceFile) {
            this.sourceFile = sourceFile;
        }

        @Override
        protected DeferredImageSupplier createImageSupplier(String directory) {
            return null;
        }

        @Override
        protected List<SeriesMeta> readAllInputMetadata(String directory) {
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
        protected File sourceFileForSeries(DeferredImageSupplier supplier, int seriesIndex) {
            return sourceFile;
        }

        @Override
        protected int sourceSeriesIndexForSeries(DeferredImageSupplier supplier, int seriesIndex) {
            return seriesIndex;
        }

        @Override
        protected MetadataDiagnostics.SeriesInfo readSeriesInfo(File lifFile, int seriesIndex) throws Exception {
            throw new IOException("OME objective metadata unavailable");
        }
    }

    private static final class LooseTiffBasenameAnalysis extends DeconvolutionAnalysis {
        private final File sourceFile;
        private final String displayName;

        private LooseTiffBasenameAnalysis(File sourceFile, String displayName) {
            this.sourceFile = sourceFile;
            this.displayName = displayName;
        }

        @Override
        protected DeferredImageSupplier createImageSupplier(String directory) {
            return new DeferredImageSupplier(Collections.singletonList(sourceFile), displayName);
        }

        @Override
        protected List<SeriesMeta> readAllInputMetadata(String directory) {
            return Collections.singletonList(new SeriesMeta(
                    0,
                    displayName + " - MouseA_LH_SCN",
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
        protected MetadataDiagnostics.SeriesInfo readSeriesInfo(File imageFile, int seriesIndex) {
            MetadataDiagnostics.SeriesInfo info = new MetadataDiagnostics.SeriesInfo();
            info.file = imageFile == null ? "" : imageFile.getName();
            info.extension = "tif";
            info.seriesIndex = seriesIndex;
            info.imageName = "MouseA_LH_SCN.tif";
            info.sizeX = 64;
            info.sizeY = 48;
            info.sizeZ = 12;
            info.sizeC = 3;
            info.pixelSizeXUm = Double.valueOf(0.20);
            info.pixelSizeZUm = Double.valueOf(0.60);
            info.emissionWavelengthNm = new double[]{488.0, 568.0, 647.0};
            return info;
        }
    }

    private static final class ProjectManifestAnalysis extends DeconvolutionAnalysis {
        private final int sourceSeriesIndex;
        private List<File> manifestSources = Collections.emptyList();
        private File readInfoFile;
        private int readInfoSeriesIndex = -1;

        private ProjectManifestAnalysis(int sourceSeriesIndex) {
            this.sourceSeriesIndex = sourceSeriesIndex;
        }

        @Override
        protected DeferredImageSupplier createImageSupplier(String directory) {
            return null;
        }

        @Override
        protected List<SeriesMeta> readAllInputMetadata(String directory) {
            manifestSources = ImageSourceDispatcher.projectContainerFiles(directory);
            return Collections.singletonList(new SeriesMeta(
                    0,
                    "slide.lif - Mouse1_LH_CA1",
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
        protected File sourceFileForSeries(DeferredImageSupplier supplier, int seriesIndex) {
            return manifestSources.isEmpty() ? null : manifestSources.get(0);
        }

        @Override
        protected int sourceSeriesIndexForSeries(DeferredImageSupplier supplier, int seriesIndex) {
            return sourceSeriesIndex;
        }

        @Override
        protected MetadataDiagnostics.SeriesInfo readSeriesInfo(File imageFile, int seriesIndex) {
            readInfoFile = imageFile;
            readInfoSeriesIndex = seriesIndex;
            MetadataDiagnostics.SeriesInfo info = new MetadataDiagnostics.SeriesInfo();
            info.file = imageFile == null ? "" : imageFile.getName();
            info.extension = "lif";
            info.seriesIndex = seriesIndex;
            info.imageName = "slide.lif - Mouse1_LH_CA1";
            info.sizeX = 64;
            info.sizeY = 48;
            info.sizeZ = 12;
            info.sizeC = 3;
            info.pixelSizeXUm = Double.valueOf(0.20);
            info.pixelSizeZUm = Double.valueOf(0.60);
            info.objectiveNA = Double.valueOf(1.30);
            info.objectiveImmersion = "oil";
            info.sampleRefractiveIndex = Double.valueOf(1.33);
            info.emissionWavelengthNm = new double[]{488.0, 568.0, 647.0};
            return info;
        }
    }
}
