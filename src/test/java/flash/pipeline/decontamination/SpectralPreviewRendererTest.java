package flash.pipeline.decontamination;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ByteProcessor;
import ij.process.ShortProcessor;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SpectralPreviewRendererTest {

    @Test
    public void rendersPreviewFromLoadedImageUsingCurrentPipeline() {
        BinConfigStub binConfig = new BinConfigStub("Target", "Bleed");
        SpectralDecontaminationConfig config = new SpectralDecontaminationConfig();
        config.setTargetChannelIndex(0);
        config.setBleedThroughChannelIndexes(Arrays.asList(Integer.valueOf(1)));

        CorrectionPipeline pipeline = new CorrectionPipeline();
        pipeline.setFeatureIds(Arrays.asList(
                "linear_unmixing",
                "threshold_corrected_target"));
        config.setCorrectionPipeline(pipeline);

        ImagePlus source = twoChannelImage(
                new int[]{0, 10, 20, 100},
                new int[]{0, 20, 40, 0});
        SpectralPreviewSelector.PreviewSelection selection =
                new SpectralPreviewSelector.PreviewSelection(
                        new SpectralPreviewSelector.PreviewCandidate(
                                0,
                                "Mouse1_Control",
                                "Mouse1",
                                "Control"),
                        new SpectralPreviewSelector.ImageScores(0.0, 0.0, 0.0, 0.0, -1),
                        "control",
                        "typical");

        SpectralPreviewRenderer.RenderedPreview preview =
                SpectralPreviewRenderer.renderLoadedImage(source, binConfig, config, selection);

        assertNotNull(preview);
        assertNotNull(preview.rawTarget);
        assertNotNull(preview.correctedTarget);
        assertNotNull(preview.finalOverlay);
        assertFalse(preview.correctedTarget.placeholder);
        assertFalse(preview.finalOverlay.placeholder);
        assertEquals(Long.valueOf(1L), preview.metrics.targetPositiveVoxels);
        assertEquals(0.0, preview.metrics.saturatedFraction, 0.0);
        assertEquals(1, preview.bleedThroughChannels.size());
        assertFalse(preview.metrics.coefficientLines.isEmpty());
        assertNotNull("renderLoadedImage must not close its caller-owned source", source.getProcessor());
    }

    @Test
    public void closesEveryExecutionImageAfterEachCreationAndRenderFault() {
        for (SpectralPreviewRenderer.PreviewBoundary boundary
                : SpectralPreviewRenderer.PreviewBoundary.values()) {
            final PreviewFixture fixture = previewFixture();
            final SpectralPreviewRenderer.PreviewBoundary injectedBoundary = boundary;
            try {
                SpectralPreviewRenderer.renderLoadedImage(
                        fixture.source,
                        fixture.binConfig,
                        fixture.config,
                        fixture.selection,
                        fixture.runner,
                        new SpectralPreviewRenderer.FaultInjector() {
                            @Override
                            public void after(SpectralPreviewRenderer.PreviewBoundary reached, int occurrence) {
                                if (reached == injectedBoundary && occurrence == 1) {
                                    throw new InjectedPreviewFault(injectedBoundary.name());
                                }
                            }
                        });
                fail("Expected injected failure after " + boundary);
            } catch (InjectedPreviewFault expected) {
                assertEquals(boundary.name(), expected.getMessage());
            }
            fixture.assertGeneratedImagesClosedOnce();
            assertEquals("caller-owned source must remain open after " + boundary,
                    0, fixture.source.closeCount);
            assertEquals("caller-owned source must not be flushed after " + boundary,
                    0, fixture.source.flushCount);
        }
    }

    @Test
    public void closesPartialExecutionStateWhenPipelineThrows() {
        for (int failAfter = 0; failAfter < 4; failAfter++) {
            final PreviewFixture fixture = previewFixture();
            final int failureIndex = failAfter;
            SpectralPreviewRenderer.ExecutionRunner failingRunner =
                    new SpectralPreviewRenderer.ExecutionRunner() {
                        @Override
                        public void execute(CorrectionFeatureRegistry registry,
                                            CorrectionPipeline.ExecutionState state) {
                            state.setCorrectedImage(fixture.corrected);
                            failAfterCreation(failureIndex, 0);
                            state.setMaskImage(fixture.mask);
                            failAfterCreation(failureIndex, 1);
                            state.setVetoMaskImage(fixture.veto);
                            failAfterCreation(failureIndex, 2);
                            state.putParameterMap("local_k", fixture.parameterMap);
                            failAfterCreation(failureIndex, 3);
                        }
                    };

            try {
                SpectralPreviewRenderer.renderLoadedImage(
                        fixture.source,
                        fixture.binConfig,
                        fixture.config,
                        fixture.selection,
                        failingRunner,
                        noFaults());
                fail("Expected injected pipeline failure after artifact " + failAfter);
            } catch (InjectedPreviewFault expected) {
                assertEquals("pipeline-" + failAfter, expected.getMessage());
            }

            assertCloseCount(fixture.corrected, failAfter >= 0 ? 1 : 0);
            assertCloseCount(fixture.mask, failAfter >= 1 ? 1 : 0);
            assertCloseCount(fixture.veto, failAfter >= 2 ? 1 : 0);
            assertCloseCount(fixture.parameterMap, failAfter >= 3 ? 1 : 0);
            assertEquals(0, fixture.source.closeCount);
            assertEquals(0, fixture.source.flushCount);
        }
    }

    @Test
    public void cancellationClosesExecutionImagesAndPreservesInterrupt() {
        final PreviewFixture fixture = previewFixture();
        try {
            SpectralPreviewRenderer.renderLoadedImage(
                    fixture.source,
                    fixture.binConfig,
                    fixture.config,
                    fixture.selection,
                    fixture.runner,
                    new SpectralPreviewRenderer.FaultInjector() {
                        @Override
                        public void after(SpectralPreviewRenderer.PreviewBoundary boundary, int occurrence) {
                            if (boundary == SpectralPreviewRenderer.PreviewBoundary.RAW_TARGET_THUMBNAIL) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    });
            fail("Expected cancellation");
        } catch (CancellationException expected) {
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }

        fixture.assertGeneratedImagesClosedOnce();
        assertEquals(0, fixture.source.closeCount);
        assertEquals(0, fixture.source.flushCount);
    }

    @Test
    public void successAndRetryCloseEachExecutionGenerationExactlyOnce() {
        PreviewFixture first = previewFixture();
        SpectralPreviewRenderer.RenderedPreview firstPreview =
                SpectralPreviewRenderer.renderLoadedImage(
                        first.source,
                        first.binConfig,
                        first.config,
                        first.selection,
                        first.runner,
                        noFaults());
        assertNotNull(firstPreview.finalOverlay.image);
        first.assertGeneratedImagesClosedOnce();
        assertEquals(0, first.source.closeCount);

        PreviewFixture retry = previewFixture();
        SpectralPreviewRenderer.RenderedPreview retryPreview =
                SpectralPreviewRenderer.renderLoadedImage(
                        retry.source,
                        retry.binConfig,
                        retry.config,
                        retry.selection,
                        retry.runner,
                        noFaults());
        assertNotNull(retryPreview.correctedTarget.image);
        retry.assertGeneratedImagesClosedOnce();
        assertEquals(0, retry.source.closeCount);
    }

    @Test
    public void lifecycleDeduplicatesAliasesIsIdempotentAndExcludesSource() {
        CountingImagePlus source = countingShortImage("source", new int[]{1, 2, 3, 4});
        CountingImagePlus generated = countingShortImage("generated", new int[]{4, 3, 2, 1});
        SpectralPreviewRenderer.PreviewImageLifecycle lifecycle =
                new SpectralPreviewRenderer.PreviewImageLifecycle(source);

        lifecycle.own(source);
        lifecycle.own(generated);
        lifecycle.own(generated);
        lifecycle.close();
        lifecycle.close();
        lifecycle.own(generated);

        assertEquals(0, source.closeCount);
        assertEquals(0, source.flushCount);
        assertEquals(1, generated.closeCount);
        assertEquals(1, generated.flushCount);
    }

    @Test
    public void lifecycleClosesAllImagesThenRethrowsFirstFatalCleanupError() {
        CountingImagePlus source = countingShortImage("source", new int[]{1, 2, 3, 4});
        FatalCloseImagePlus fatal = new FatalCloseImagePlus(
                "fatal", shortStack(new int[]{4, 3, 2, 1}), new LinkageError("fatal-close"));
        CountingImagePlus sibling = countingShortImage("sibling", new int[]{5, 6, 7, 8});
        SpectralPreviewRenderer.PreviewImageLifecycle lifecycle =
                new SpectralPreviewRenderer.PreviewImageLifecycle(source);
        lifecycle.own(fatal);
        lifecycle.own(sibling);

        try {
            lifecycle.close();
            fail("Expected fatal cleanup failure");
        } catch (LinkageError expected) {
            assertEquals("fatal-close", expected.getMessage());
        }

        assertCloseCount(fatal, 1);
        assertCloseCount(sibling, 1);
        lifecycle.close();
        assertCloseCount(fatal, 1);
        assertCloseCount(sibling, 1);
        assertEquals(0, source.closeCount);
    }

    @Test
    public void sourceAliasPublishedByExecutionIsNeverAdopted() {
        final PreviewFixture fixture = previewFixture();
        SpectralPreviewRenderer.ExecutionRunner aliasingRunner =
                new SpectralPreviewRenderer.ExecutionRunner() {
                    @Override
                    public void execute(CorrectionFeatureRegistry registry,
                                        CorrectionPipeline.ExecutionState state) {
                        state.setCorrectedImage(fixture.source);
                    }
                };
        try {
            SpectralPreviewRenderer.renderLoadedImage(
                    fixture.source,
                    fixture.binConfig,
                    fixture.config,
                    fixture.selection,
                    aliasingRunner,
                    new SpectralPreviewRenderer.FaultInjector() {
                        @Override
                        public void after(SpectralPreviewRenderer.PreviewBoundary boundary, int occurrence) {
                            if (boundary == SpectralPreviewRenderer.PreviewBoundary.CORRECTED_IMAGE) {
                                throw new InjectedPreviewFault("source-alias");
                            }
                        }
                    });
            fail("Expected injected source-alias failure");
        } catch (InjectedPreviewFault expected) {
            assertEquals("source-alias", expected.getMessage());
        }

        assertEquals(0, fixture.source.closeCount);
        assertEquals(0, fixture.source.flushCount);
        assertNotNull(fixture.source.getProcessor());
    }

    @Test
    public void extractsObjectCountsAndSaturationForMetricsPanel() {
        BinConfigStub binConfig = new BinConfigStub("Target", "Bleed");
        ImagePlus source = twoChannelImage(
                new int[]{1, 2, 3, 4},
                new int[]{5, 6, 7, 8});
        Calibration calibration = new Calibration();
        calibration.pixelWidth = 2.0;
        calibration.pixelHeight = 2.0;
        calibration.pixelDepth = 1.0;
        calibration.setUnit("micron");
        source.setCalibration(calibration);

        List<CorrectionPipeline.FeatureSummary> summaries =
                new ArrayList<CorrectionPipeline.FeatureSummary>();
        summaries.add(new CorrectionPipeline.FeatureSummary("linear_unmixing", "Linear unmixing")
                .putDouble("weight_channel_2", 0.25));
        summaries.add(new CorrectionPipeline.FeatureSummary("size_filter", "Size filter")
                .putInt("kept_voxels", 12)
                .putInt("kept_components", 3)
                .putInt("removed_components", 1));

        SpectralPreviewRenderer.PreviewMetrics metrics = SpectralPreviewRenderer.PreviewMetrics.from(
                source,
                binConfig,
                summaries,
                new SpectralPreviewSelector.ImageScores(0.0, 0.0, 0.0, 0.125, -1));

        assertEquals(Long.valueOf(12L), metrics.targetPositiveVoxels);
        assertEquals(Integer.valueOf(3), metrics.objectsKept);
        assertEquals(Integer.valueOf(1), metrics.objectsRemoved);
        assertEquals(0.125, metrics.saturatedFraction, 0.0);
        assertTrue(metrics.targetPositiveLabel.contains("48"));
        assertTrue(metrics.coefficientLines.get(0).contains("0.25"));
    }

    private static ImagePlus twoChannelImage(int[] targetPixels, int[] bleedPixels) {
        ImageStack stack = new ImageStack(2, 2);
        stack.addSlice(new ShortProcessor(2, 2, toShorts(targetPixels), null));
        stack.addSlice(new ShortProcessor(2, 2, toShorts(bleedPixels), null));
        ImagePlus image = new ImagePlus("source", stack);
        image.setDimensions(2, 1, 1);
        image.setOpenAsHyperStack(true);
        return image;
    }

    private static PreviewFixture previewFixture() {
        final CountingImagePlus source = countingThreeChannelImage(
                new int[]{0, 10, 20, 100},
                new int[]{0, 20, 40, 0},
                new int[]{2, 4, 8, 16});
        final CountingImagePlus corrected = countingShortImage(
                "corrected", new int[]{0, 5, 10, 50});
        final CountingImagePlus mask = countingMaskImage(
                "mask", new int[]{0, 0, 255, 255});
        final CountingImagePlus veto = countingMaskImage(
                "veto", new int[]{0, 255, 0, 255});
        final CountingImagePlus parameterMap = countingShortImage(
                "parameter-map", new int[]{1, 2, 3, 4});

        SpectralDecontaminationConfig config = new SpectralDecontaminationConfig();
        config.setTargetChannelIndex(0);
        config.setBleedThroughChannelIndexes(Arrays.asList(Integer.valueOf(1)));
        config.setAutofluorescenceChannelIndexes(Arrays.asList(Integer.valueOf(2)));

        SpectralPreviewRenderer.ExecutionRunner runner =
                new SpectralPreviewRenderer.ExecutionRunner() {
                    @Override
                    public void execute(CorrectionFeatureRegistry registry,
                                        CorrectionPipeline.ExecutionState state) {
                        state.setCorrectedImage(corrected);
                        state.setMaskImage(mask);
                        state.setVetoMaskImage(veto);
                        state.putParameterMap("local_k", parameterMap);
                        state.addSummary(new CorrectionPipeline.FeatureSummary("fixture", "Fixture")
                                .putInt("kept_voxels", 2));
                    }
                };

        return new PreviewFixture(
                source,
                corrected,
                mask,
                veto,
                parameterMap,
                new BinConfigStub("Target", "Bleed", "Autofluorescence"),
                config,
                selection(),
                runner);
    }

    private static SpectralPreviewSelector.PreviewSelection selection() {
        return new SpectralPreviewSelector.PreviewSelection(
                new SpectralPreviewSelector.PreviewCandidate(
                        0,
                        "Mouse1_Control",
                        "Mouse1",
                        "Control"),
                new SpectralPreviewSelector.ImageScores(0.0, 0.0, 0.0, 0.0, -1),
                "control",
                "typical");
    }

    private static SpectralPreviewRenderer.FaultInjector noFaults() {
        return new SpectralPreviewRenderer.FaultInjector() {
            @Override
            public void after(SpectralPreviewRenderer.PreviewBoundary boundary, int occurrence) {
                // Deliberately empty.
            }
        };
    }

    private static void failAfterCreation(int requestedIndex, int currentIndex) {
        if (requestedIndex == currentIndex) {
            throw new InjectedPreviewFault("pipeline-" + currentIndex);
        }
    }

    private static void assertCloseCount(CountingImagePlus image, int expected) {
        assertEquals(image.getTitle() + " close count", expected, image.closeCount);
        assertEquals(image.getTitle() + " flush count", expected, image.flushCount);
    }

    private static CountingImagePlus countingThreeChannelImage(int[] first,
                                                               int[] second,
                                                               int[] third) {
        ImageStack stack = new ImageStack(2, 2);
        stack.addSlice(new ShortProcessor(2, 2, toShorts(first), null));
        stack.addSlice(new ShortProcessor(2, 2, toShorts(second), null));
        stack.addSlice(new ShortProcessor(2, 2, toShorts(third), null));
        CountingImagePlus image = new CountingImagePlus("source", stack);
        image.setDimensions(3, 1, 1);
        image.setOpenAsHyperStack(true);
        return image;
    }

    private static CountingImagePlus countingShortImage(String title, int[] pixels) {
        return new CountingImagePlus(title, shortStack(pixels));
    }

    private static ImageStack shortStack(int[] pixels) {
        ImageStack stack = new ImageStack(2, 2);
        stack.addSlice(new ShortProcessor(2, 2, toShorts(pixels), null));
        return stack;
    }

    private static CountingImagePlus countingMaskImage(String title, int[] pixels) {
        byte[] bytes = new byte[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            bytes[i] = (byte) pixels[i];
        }
        ImageStack stack = new ImageStack(2, 2);
        stack.addSlice(new ByteProcessor(2, 2, bytes, null));
        return new CountingImagePlus(title, stack);
    }

    private static short[] toShorts(int[] values) {
        short[] out = new short[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (short) values[i];
        }
        return out;
    }

    private static final class BinConfigStub extends flash.pipeline.bin.BinConfig {
        BinConfigStub(String... channelNames) {
            this.channelNames.addAll(Arrays.asList(channelNames));
        }
    }

    private static final class PreviewFixture {
        private final CountingImagePlus source;
        private final CountingImagePlus corrected;
        private final CountingImagePlus mask;
        private final CountingImagePlus veto;
        private final CountingImagePlus parameterMap;
        private final BinConfigStub binConfig;
        private final SpectralDecontaminationConfig config;
        private final SpectralPreviewSelector.PreviewSelection selection;
        private final SpectralPreviewRenderer.ExecutionRunner runner;

        private PreviewFixture(CountingImagePlus source,
                               CountingImagePlus corrected,
                               CountingImagePlus mask,
                               CountingImagePlus veto,
                               CountingImagePlus parameterMap,
                               BinConfigStub binConfig,
                               SpectralDecontaminationConfig config,
                               SpectralPreviewSelector.PreviewSelection selection,
                               SpectralPreviewRenderer.ExecutionRunner runner) {
            this.source = source;
            this.corrected = corrected;
            this.mask = mask;
            this.veto = veto;
            this.parameterMap = parameterMap;
            this.binConfig = binConfig;
            this.config = config;
            this.selection = selection;
            this.runner = runner;
        }

        private void assertGeneratedImagesClosedOnce() {
            assertClosedOnce(corrected);
            assertClosedOnce(mask);
            assertClosedOnce(veto);
            assertClosedOnce(parameterMap);
        }

        private static void assertClosedOnce(CountingImagePlus image) {
            assertEquals(image.getTitle() + " close count", 1, image.closeCount);
            assertEquals(image.getTitle() + " flush count", 1, image.flushCount);
        }
    }

    private static class CountingImagePlus extends ImagePlus {
        private int closeCount;
        private int flushCount;

        private CountingImagePlus(String title, ImageStack stack) {
            super(title, stack);
        }

        @Override
        public void close() {
            closeCount++;
        }

        @Override
        public void flush() {
            flushCount++;
        }
    }

    private static final class FatalCloseImagePlus extends CountingImagePlus {
        private final Error failure;

        private FatalCloseImagePlus(String title, ImageStack stack, Error failure) {
            super(title, stack);
            this.failure = failure;
        }

        @Override
        public void close() {
            super.close();
            throw failure;
        }
    }

    private static final class InjectedPreviewFault extends RuntimeException {
        private InjectedPreviewFault(String message) {
            super(message);
        }
    }
}
