package flash.pipeline.decontamination;

import flash.pipeline.TestConfigFiles;
import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.BinField;
import flash.pipeline.bin.ChannelConfig;
import flash.pipeline.bin.ChannelConfigIO;
import flash.pipeline.decontamination.features.LinearUnmixingFeature;
import flash.pipeline.io.ImageCache;
import flash.pipeline.io.SeriesMeta;
import ij.ImagePlus;
import ij.ImageStack;
import ij.IJ;
import ij.process.ShortProcessor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.lang.reflect.Method;
import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SpectralDecontaminationAnalysisTest {

    @Test
    public void interruptedBatchWaitDoesNotFinalizeBeforeOwnedWorkerCompletes() throws Exception {
        final java.util.concurrent.CompletableFuture<String> worker =
                new java.util.concurrent.CompletableFuture<String>();
        final java.util.concurrent.atomic.AtomicBoolean interrupted =
                new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicReference<Object> result =
                new java.util.concurrent.atomic.AtomicReference<Object>();
        final java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<Throwable>();
        final Method await = SpectralDecontaminationAnalysis.class.getDeclaredMethod(
                "awaitOwnedWorker", java.util.concurrent.Future.class,
                java.util.concurrent.atomic.AtomicBoolean.class);
        await.setAccessible(true);
        Thread coordinator = new Thread(() -> {
            try { result.set(await.invoke(null, worker, interrupted)); }
            catch (Throwable problem) { failure.set(problem); }
        }, "test-spectral-worker-drain");
        try {
            coordinator.start();
            flash.pipeline.testutil.TestWait.await("spectral coordinator waiting", 5000L,
                    () -> coordinator.getState() == Thread.State.WAITING);
            coordinator.interrupt();
            flash.pipeline.testutil.TestWait.await("interruption recorded", 5000L, interrupted::get);
            assertTrue("Owned writer must finish before batch summaries finalize", coordinator.isAlive());
            assertEquals(null, result.get());
            worker.complete("finished output");
            coordinator.join(5000L);
            assertFalse(coordinator.isAlive());
            assertEquals(null, failure.get());
            assertEquals("finished output", result.get());
            assertTrue(interrupted.get());
        } finally {
            worker.complete("cleanup");
            coordinator.interrupt();
            coordinator.join(5000L);
        }
    }

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void spectralDecontaminationDeclaresHeadedModeForMainGui() {
        assertTrue(new SpectralDecontaminationAnalysis().requiresHeadedMode());
    }

    @Test
    public void spectralDecontaminationRequiresOnlyChannelNamesFromSetup() {
        assertEquals(EnumSet.of(BinField.CHANNEL_NAMES),
                new SpectralDecontaminationAnalysis().requiredBinFields());
    }

    @Test
    public void loadBinConfigAcceptsPartialSetupWithConfiguredChannelNames() throws Exception {
        File project = temp.newFolder("partial-spectral-config");
        ChannelConfigIO.write(TestConfigFiles.settingsDir(project), partialNamesConfig());

        BinConfig cfg = new SpectralDecontaminationAnalysis().loadBinConfig(project.getAbsolutePath());

        assertEquals(Arrays.asList("IgG", "IBA1", "mCherry", "Cleaved Caspase-3"), cfg.channelNames);
        assertTrue(cfg.hasChannelNames());
        assertFalse(cfg.hasChannelThresholds());
        assertFalse(cfg.hasChannelSizes());
        assertFalse(cfg.hasSegmentationMethods());
    }

    private static ChannelConfig partialNamesConfig() {
        ChannelConfig cfg = new ChannelConfig();
        cfg.complete = Boolean.FALSE;
        String[] names = {"IgG", "IBA1", "mCherry", "Cleaved Caspase-3"};
        String[] colors = {"Magenta", "Cyan", "Red", "Green"};
        for (int i = 0; i < names.length; i++) {
            ChannelConfig.Channel channel = new ChannelConfig.Channel();
            channel.index = i;
            channel.name = names[i];
            channel.color = colors[i];
            channel.threshold = "default";
            channel.size = "100-Infinity";
            channel.minmax = "None";
            channel.intensityThreshold = "default";
            channel.segmentationMethod = "classical";
            channel.filterPreset = "Default";
            channel.status.put(ChannelConfig.P_NAME, ChannelConfig.PropertyStatus.CONFIGURED);
            channel.status.put(ChannelConfig.P_COLOR, ChannelConfig.PropertyStatus.CONFIGURED);
            channel.status.put(ChannelConfig.P_MARKER, ChannelConfig.PropertyStatus.CONFIGURED);
            channel.status.put(ChannelConfig.P_THRESHOLD, ChannelConfig.PropertyStatus.PENDING);
            channel.status.put(ChannelConfig.P_SIZE, ChannelConfig.PropertyStatus.PENDING);
            channel.status.put(ChannelConfig.P_MINMAX, ChannelConfig.PropertyStatus.COMMITTED);
            channel.status.put(ChannelConfig.P_INTENSITY, ChannelConfig.PropertyStatus.CONFIGURED);
            channel.status.put(ChannelConfig.P_SEGMENTATION, ChannelConfig.PropertyStatus.PENDING);
            channel.status.put(ChannelConfig.P_FILTER, ChannelConfig.PropertyStatus.CONFIGURED);
            cfg.channels.add(channel);
        }
        return cfg;
    }

    @Test
    public void skipExistingRecomputesLegacyOutputWithMatchingConfigButUnverifiedSource() throws Exception {
        File project = temp.newFolder("unverified-spectral-output");
        SpectralDecontaminationConfig config = correctionConfig(false);
        SpectralOutputWriter.ExpectedOutputs outputs = SpectralOutputWriter.expectedOutputs(
                project.getAbsolutePath(), 0, "Mouse1_LH_SCN", "Target");
        SpectralOutputWriter.saveCorrectedImage(
                new ImagePlus("old", new ShortProcessor(1, 1, new short[]{999}, null)),
                outputs.correctedImageFile);
        Map<String, String> oldSummary = new LinkedHashMap<String, String>();
        oldSummary.put("SeriesIndex", "0");
        oldSummary.put("RunAction", "processed");
        oldSummary.put("ConfigId", SpectralOutputWriter.RunMetadata.fromConfig(
                config, CorrectionFeatureRegistry.getDefault()).configId);
        oldSummary.put("ZSliceRange", "Full stack");
        SpectralOutputWriter.writePerImageSummary(project.getAbsolutePath(),
                Collections.singletonList(oldSummary), "OLD-PRODUCER");

        Object result = runBatchWithCachedSource(project, config, false);

        assertTrue((Boolean) resultField(result, "success"));
        assertEquals(1, resultField(result, "processedCount"));
        assertEquals(0, resultField(result, "skippedCount"));
        ImagePlus corrected = IJ.openImage(outputs.correctedImageFile.getAbsolutePath());
        try {
            assertEquals(75, corrected.getProcessor().get(0));
        } finally {
            corrected.close();
            corrected.flush();
        }
        Map<String, String> summary = SpectralOutputWriter.readPerImageSummaryRows(
                project.getAbsolutePath()).get(0);
        assertEquals("processed", summary.get("RunAction"));
    }

    @Test
    public void singularCorrectionDoesNotPublishOrMarkBatchSuccessful() throws Exception {
        File project = temp.newFolder("singular-spectral-output");
        Object result = runBatchWithCachedSource(project, correctionConfig(true), true);
        assertFalse((Boolean) resultField(result, "success"));
        assertEquals(1, resultField(result, "failedCount"));
        assertEquals(0, resultField(result, "processedCount"));
        assertEquals("error", SpectralOutputWriter.readPerImageSummaryRows(
                project.getAbsolutePath()).get(0).get("RunAction"));
        assertFalse(SpectralOutputWriter.expectedOutputs(project.getAbsolutePath(),
                0, "Mouse1_LH_SCN", "Target").correctedImageFile.exists());
    }

    private Object runBatchWithCachedSource(File project, SpectralDecontaminationConfig config,
                                           boolean duplicateContaminants) throws Exception {
        final ImageStack stack = new ImageStack(1, 1);
        stack.addSlice(new ShortProcessor(1, 1, new short[]{100}, null));
        stack.addSlice(new ShortProcessor(1, 1, new short[]{50}, null));
        if (duplicateContaminants) stack.addSlice(new ShortProcessor(1, 1, new short[]{50}, null));
        final ImagePlus source = new ImagePlus("Mouse1_LH_SCN", stack);
        source.setDimensions(stack.size(), 1, 1);
        SpectralDecontaminationAnalysis analysis = new SpectralDecontaminationAnalysis();
        analysis.setSkipExisting(true);
        analysis.setImageCache(new ImageCache() {
            @Override public List<ImagePlus> getImages(String directory) {
                return Collections.singletonList(source);
            }
        });
        BinConfig bin = new BinConfig();
        bin.channelNames.addAll(duplicateContaminants
                ? Arrays.asList("Target", "Bleed1", "Bleed2") : Arrays.asList("Target", "Bleed1"));
        Method runBatch = SpectralDecontaminationAnalysis.class.getDeclaredMethod("runBatch",
                String.class, BinConfig.class, SpectralDecontaminationConfig.class,
                List.class, List.class, File.class, List.class);
        runBatch.setAccessible(true);
        try {
            return runBatch.invoke(analysis, project.getAbsolutePath(), bin, config,
                    Collections.singletonList(new SeriesMeta(0, source.getTitle(), 1, 1, 1, stack.size(),
                            1, 1, 1, "pixel")),
                    Collections.singletonList(new SpectralPreviewSelector.PreviewCandidate(
                            0, source.getTitle(), "Mouse1", "Control")),
                    null, Collections.emptyList());
        } finally {
            source.close();
            source.flush();
        }
    }

    private static SpectralDecontaminationConfig correctionConfig(boolean fitted) {
        SpectralDecontaminationConfig config = new SpectralDecontaminationConfig();
        config.setTargetChannelIndex(0);
        config.setBleedThroughChannelIndexes(fitted ? Arrays.asList(1, 2) : Collections.singletonList(1));
        CorrectionPipeline pipeline = new CorrectionPipeline();
        pipeline.setFeatureIds(Collections.singletonList(LinearUnmixingFeature.ID));
        config.setCorrectionPipeline(pipeline);
        if (!fitted) config.setFeatureSettings(LinearUnmixingFeature.ID,
                new LinearUnmixingFeature.Settings().setWeightMode(LinearUnmixingFeature.WeightMode.MANUAL)
                        .setManualWeight(1, 0.5).toPipelineSettings());
        return config;
    }

    private static Object resultField(Object result, String name) throws Exception {
        Field field = result.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(result);
    }
}
