package flash.pipeline.ui.config;

import flash.pipeline.deconv.engine.Algorithm;
import flash.pipeline.deconv.engine.DeconvParams;
import flash.pipeline.deconv.engine.DeconvSettings;
import flash.pipeline.deconv.psf.PsfModel;
import flash.pipeline.ui.variations.DeconvolutionPreviewAdapter;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DeconvolutionStageTest {

    @After
    public void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    public void modalReturnClosesOnlyOwnedDuplicateForAcceptAndCancelPaths() throws Throwable {
        for (int path = 0; path < 2; path++) {
            ImagePlus shared = image("shared-" + path);
            final ImagePlus published = image("published-" + path);
            final AtomicReference<ImagePlus> borrowed = new AtomicReference<ImagePlus>();
            RecordingPreviewSource source = new RecordingPreviewSource();

            DeconvolutionStage.withOwnedVariationsCrop(
                    shared, source, new DeconvolutionStage.VariationsDialogAction() {
                        @Override public void open(ImagePlus ownedCrop) {
                            borrowed.set(ownedCrop);
                            // Models an accepted/published dialog result. It is not the baseline
                            // duplicate and therefore remains outside this ownership boundary.
                            assertNotSame(published, ownedCrop);
                        }
                    });

            assertNotSame(shared, borrowed.get());
            assertEquals(shared.getTitle(), borrowed.get().getTitle());
            assertClosedExactlyOnce(source, borrowed.get());
            assertFalse(source.closed.contains(shared));
            assertFalse(source.closed.contains(published));
        }
    }

    @Test
    public void setupShowAndReadbackFailuresCloseDuplicateAndPreservePrimary() throws Throwable {
        RuntimeException[] failures = new RuntimeException[]{
                new IllegalStateException("setup failed"),
                new IllegalArgumentException("show failed"),
                new RuntimeException("readback failed")
        };
        for (final RuntimeException primary : failures) {
            ImagePlus shared = image("shared");
            RecordingPreviewSource source = new RecordingPreviewSource();
            final AtomicReference<ImagePlus> borrowed = new AtomicReference<ImagePlus>();
            try {
                DeconvolutionStage.withOwnedVariationsCrop(
                        shared, source, new DeconvolutionStage.VariationsDialogAction() {
                            @Override public void open(ImagePlus ownedCrop) {
                                borrowed.set(ownedCrop);
                                throw primary;
                            }
                        });
                fail("Expected modal failure");
            } catch (Throwable actual) {
                assertSame(primary, actual);
            }
            assertClosedExactlyOnce(source, borrowed.get());
            assertFalse(source.closed.contains(shared));
        }
    }

    @Test
    public void ordinaryCleanupFailureIsReportedWithoutDoubleClose() throws Throwable {
        final RuntimeException cleanup = new RuntimeException("close failed");
        RecordingPreviewSource source = new RecordingPreviewSource();
        source.closeFailure = cleanup;
        final AtomicReference<ImagePlus> borrowed = new AtomicReference<ImagePlus>();
        try {
            DeconvolutionStage.withOwnedVariationsCrop(
                    image("shared"), source, new DeconvolutionStage.VariationsDialogAction() {
                        @Override public void open(ImagePlus ownedCrop) {
                            borrowed.set(ownedCrop);
                        }
                    });
            fail("Expected cleanup failure");
        } catch (Throwable actual) {
            assertSame(cleanup, actual);
        }
        assertClosedExactlyOnce(source, borrowed.get());
    }

    @Test
    public void primaryDiagnosticKeepsOrdinaryCleanupFailureSuppressed() throws Throwable {
        final RuntimeException primary = new RuntimeException("dialog failed");
        final RuntimeException cleanup = new RuntimeException("close failed");
        RecordingPreviewSource source = new RecordingPreviewSource();
        source.closeFailure = cleanup;
        final AtomicReference<ImagePlus> borrowed = new AtomicReference<ImagePlus>();
        try {
            DeconvolutionStage.withOwnedVariationsCrop(
                    image("shared"), source, new DeconvolutionStage.VariationsDialogAction() {
                        @Override public void open(ImagePlus ownedCrop) {
                            borrowed.set(ownedCrop);
                            throw primary;
                        }
                    });
            fail("Expected dialog failure");
        } catch (Throwable actual) {
            assertSame(primary, actual);
            assertEquals(1, actual.getSuppressed().length);
            assertSame(cleanup, actual.getSuppressed()[0]);
        }
        assertClosedExactlyOnce(source, borrowed.get());
    }

    @Test
    public void cleanupVmFatalTakesPrecedenceOverOrdinaryPrimary() throws Throwable {
        final RuntimeException primary = new RuntimeException("dialog failed");
        final OutOfMemoryError fatalCleanup = new OutOfMemoryError("close fatal");
        RecordingPreviewSource source = new RecordingPreviewSource();
        source.closeFailure = fatalCleanup;
        final AtomicReference<ImagePlus> borrowed = new AtomicReference<ImagePlus>();
        try {
            DeconvolutionStage.withOwnedVariationsCrop(
                    image("shared"), source, new DeconvolutionStage.VariationsDialogAction() {
                        @Override public void open(ImagePlus ownedCrop) {
                            borrowed.set(ownedCrop);
                            throw primary;
                        }
                    });
            fail("Expected fatal cleanup failure");
        } catch (Throwable actual) {
            assertSame(fatalCleanup, actual);
            assertEquals(1, actual.getSuppressed().length);
            assertSame(primary, actual.getSuppressed()[0]);
        }
        assertClosedExactlyOnce(source, borrowed.get());
    }

    @Test
    public void primaryVmFatalRetainsPrecedenceAndCleanupDiagnostic() throws Throwable {
        final OutOfMemoryError fatalPrimary = new OutOfMemoryError("dialog fatal");
        final RuntimeException cleanup = new RuntimeException("close failed");
        RecordingPreviewSource source = new RecordingPreviewSource();
        source.closeFailure = cleanup;
        final AtomicReference<ImagePlus> borrowed = new AtomicReference<ImagePlus>();
        try {
            DeconvolutionStage.withOwnedVariationsCrop(
                    image("shared"), source, new DeconvolutionStage.VariationsDialogAction() {
                        @Override public void open(ImagePlus ownedCrop) {
                            borrowed.set(ownedCrop);
                            throw fatalPrimary;
                        }
                    });
            fail("Expected fatal dialog failure");
        } catch (Throwable actual) {
            assertSame(fatalPrimary, actual);
            assertEquals(1, actual.getSuppressed().length);
            assertSame(cleanup, actual.getSuppressed()[0]);
        }
        assertClosedExactlyOnce(source, borrowed.get());
    }

    @Test
    public void interruptionClosesDuplicatePreservesFailureAndRestoresFlag() throws Throwable {
        assertFalse(Thread.currentThread().isInterrupted());
        final InterruptedException interruption = new InterruptedException("dialog interrupted");
        RecordingPreviewSource source = new RecordingPreviewSource();
        final AtomicReference<ImagePlus> borrowed = new AtomicReference<ImagePlus>();
        try {
            DeconvolutionStage.withOwnedVariationsCrop(
                    image("shared"), source, new DeconvolutionStage.VariationsDialogAction() {
                        @Override public void open(ImagePlus ownedCrop) throws Throwable {
                            borrowed.set(ownedCrop);
                            Thread.currentThread().interrupt();
                            throw interruption;
                        }
                    });
            fail("Expected interruption");
        } catch (Throwable actual) {
            assertSame(interruption, actual);
        }
        assertTrue(Thread.currentThread().isInterrupted());
        assertFalse(source.closeSawInterrupted);
        assertClosedExactlyOnce(source, borrowed.get());
    }

    @Test
    public void variationsAdapterUsesOnlyItsSuppliedOwnedCrop() throws Exception {
        RecordingPreviewSource source = new RecordingPreviewSource();
        ImagePlus cachedStageCrop = image("cached-stage");
        ImagePlus suppliedCallCrop = image("supplied-call");
        ImagePlus produced = image("produced");
        source.cachedCrop = cachedStageCrop;
        source.suppliedResult = produced;
        DeconvSettings base = new DeconvSettings("DL2", Algorithm.RL,
                PsfModel.GIBSON_LANNI, 15, 0.01d);
        DeconvSettings swept = new DeconvSettings("RLTV", Algorithm.RL_TV,
                PsfModel.BORN_WOLF, 31, 0.2d);
        DeconvolutionStage.Value live = new DeconvolutionStage.Value(
                base, Double.valueOf(488.0d), true, false,
                DeconvParams.DEFAULT_EDGE_HANDLING, true, false);

        DeconvolutionPreviewAdapter adapter =
                DeconvolutionStage.variationsAdapter(source, live);
        ImagePlus actual = adapter.deconvolvePreview(suppliedCallCrop, swept);

        assertSame(produced, actual);
        assertEquals(0, source.legacyDeconvolveCalls);
        assertEquals(1, source.suppliedDeconvolveCalls);
        assertSame(suppliedCallCrop, source.suppliedCrop);
        assertSame(swept, source.suppliedValue.settings);
        assertEquals(Double.valueOf(488.0d), source.suppliedValue.emissionWavelengthNm);
        assertFalse(source.closed.contains(cachedStageCrop));
        assertFalse(source.closed.contains(suppliedCallCrop));
        assertFalse(source.closed.contains(produced));
    }

    private static ImagePlus image(String title) {
        return new ImagePlus(title, new ByteProcessor(4, 4));
    }

    private static void assertClosedExactlyOnce(RecordingPreviewSource source,
                                                ImagePlus expected) {
        assertEquals(1, source.closeCalls);
        assertEquals(1, source.closed.size());
        assertSame(expected, source.closed.get(0));
    }

    private static final class RecordingPreviewSource
            implements DeconvolutionStage.DeconvPreviewSource {
        final List<ImagePlus> closed = new ArrayList<ImagePlus>();
        int closeCalls;
        boolean closeSawInterrupted;
        Throwable closeFailure;
        ImagePlus cachedCrop;
        ImagePlus suppliedCrop;
        ImagePlus suppliedResult;
        DeconvolutionStage.Value suppliedValue;
        int legacyDeconvolveCalls;
        int suppliedDeconvolveCalls;

        @Override public ImagePlus openRawCrop() {
            return null;
        }

        @Override public ImagePlus deconvolveCrop(DeconvolutionStage.Value liveValue) {
            legacyDeconvolveCalls++;
            return cachedCrop;
        }

        @Override public ImagePlus deconvolveCrop(ImagePlus rawCrop,
                                                  DeconvolutionStage.Value liveValue) {
            suppliedDeconvolveCalls++;
            suppliedCrop = rawCrop;
            suppliedValue = liveValue;
            return suppliedResult;
        }

        @Override public void close(ImagePlus image) {
            closeCalls++;
            closeSawInterrupted = Thread.currentThread().isInterrupted();
            closed.add(image);
            if (closeFailure instanceof RuntimeException) {
                throw (RuntimeException) closeFailure;
            }
            if (closeFailure instanceof Error) {
                throw (Error) closeFailure;
            }
        }
    }
}
