package flash.pipeline.ui.variations.strategy;

import flash.pipeline.deconv.engine.Algorithm;
import flash.pipeline.deconv.engine.DeconvSettings;
import flash.pipeline.deconv.psf.PsfModel;
import flash.pipeline.ui.variations.DeconvParameterId;
import flash.pipeline.ui.variations.DeconvolutionPreviewAdapter;
import flash.pipeline.ui.variations.ParameterCombo;
import flash.pipeline.ui.variations.ParameterKey;
import flash.pipeline.ui.variations.ParameterSweep;
import flash.pipeline.ui.variations.ParameterValueList;
import flash.pipeline.ui.variations.VariationCache;
import flash.pipeline.ui.variations.VariationCleanupCoordinatorTestAccess;
import flash.pipeline.ui.variations.VariationResult;

import ij.ImagePlus;
import ij.process.ByteProcessor;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DeconvolutionSweepTest {

    private static final BooleanSupplier NEVER_CANCEL = new BooleanSupplier() {
        @Override public boolean getAsBoolean() {
            return false;
        }
    };

    @Before
    public void resetCoordinatorBeforeTest() {
        VariationCleanupCoordinatorTestAccess.reset();
    }

    @After
    public void resetCoordinatorAfterTest() {
        VariationCleanupCoordinatorTestAccess.reset();
    }

    private static DeconvSettings base() {
        return new DeconvSettings("DL2", Algorithm.RL, PsfModel.GIBSON_LANNI, 15, 0.01d);
    }

    private static ImagePlus rawCrop() {
        ByteProcessor bp = new ByteProcessor(4, 4);
        for (int i = 0; i < 16; i++) {
            bp.set(i, i * 8);
        }
        return new ImagePlus("raw", bp);
    }

    private static ParameterSweep iterationRegularizationSweep() {
        Map<ParameterKey, ParameterValueList> values =
                new LinkedHashMap<ParameterKey, ParameterValueList>();
        values.put(DeconvParameterId.ITERATIONS, ParameterValueList.ofInts(10, 20));
        values.put(DeconvParameterId.REGULARIZATION, ParameterValueList.ofDoubles(0.01d, 0.02d));
        return new ParameterSweep(ParameterSweep.Method.DECONVOLUTION, values,
                null, "DAPI", "image-a");
    }

    @Test
    public void publishesOneFilterResultPerCombinationInDispatchOrder() {
        ParameterSweep sweep = iterationRegularizationSweep();
        RecordingAdapter adapter = new RecordingAdapter();
        DeconvolutionSweep strategy =
                new DeconvolutionSweep(rawCrop(), null, adapter, base());
        final List<VariationResult> published = new ArrayList<VariationResult>();

        strategy.dispatch(sweep, new Consumer<VariationResult>() {
            @Override public void accept(VariationResult result) {
                published.add(result);
            }
        }, NEVER_CANCEL);

        List<ParameterCombo> ordered = SweepDispatchOrder.order(sweep);
        assertEquals(4, published.size());
        for (int i = 0; i < published.size(); i++) {
            VariationResult result = published.get(i);
            assertEquals(ordered.get(i), result.combo());
            assertEquals(VariationResult.Kind.FILTER, result.kind());
            assertNotNull(result.previewImage());
        }
    }

    @Test
    public void failedCombinationPublishesErrorAndContinuesRemainingPreviews() {
        ParameterSweep sweep = iterationRegularizationSweep();
        FailingOnceAdapter adapter = new FailingOnceAdapter();
        DeconvolutionSweep strategy =
                new DeconvolutionSweep(rawCrop(), null, adapter, base());
        final List<VariationResult> published = new ArrayList<VariationResult>();

        strategy.dispatch(sweep, new Consumer<VariationResult>() {
            @Override public void accept(VariationResult result) {
                published.add(result);
            }
        }, NEVER_CANCEL);

        assertEquals(4, adapter.settings.size());
        assertEquals(4, published.size());
        int failures = 0;
        int successes = 0;
        for (int i = 0; i < published.size(); i++) {
            VariationResult result = published.get(i);
            if (result.hasError()) {
                failures++;
                assertTrue(result.error().getMessage().contains("simulated DL2 failure"));
            } else {
                successes++;
                assertEquals(VariationResult.Kind.FILTER, result.kind());
                assertNotNull(result.previewImage());
            }
        }
        assertEquals(1, failures);
        assertEquals(3, successes);
    }

    @Test
    public void cachedResultPublishFailureDoesNotStopRemainingPreviews() {
        ParameterSweep sweep = iterationRegularizationSweep();
        List<ParameterCombo> ordered = SweepDispatchOrder.order(sweep);
        final ParameterCombo cachedCombo = ordered.get(0);
        VariationCache cache = new VariationCache((java.io.File) null);
        TrackingImage cachedImage = new TrackingImage("cached");
        cache.put(VariationCache.keyFor(sweep, cachedCombo), cachedImage);
        RecordingAdapter adapter = new RecordingAdapter();
        DeconvolutionSweep strategy =
                new DeconvolutionSweep(rawCrop(), cache, adapter, base());
        final List<VariationResult> published = new ArrayList<VariationResult>();

        strategy.dispatch(sweep, new Consumer<VariationResult>() {
            @Override public void accept(VariationResult result) {
                if (cachedCombo.equals(result.combo())) {
                    throw new RuntimeException("cached publish failure");
                }
                published.add(result);
            }
        }, NEVER_CANCEL);

        assertEquals("cached combo should not call the adapter", 3, adapter.settings.size());
        assertEquals(3, published.size());
        for (int i = 0; i < published.size(); i++) {
            assertEquals(VariationResult.Kind.FILTER, published.get(i).kind());
        }
        assertEquals("cached image is borrowed by the result", 0, cachedImage.closeCalls);
    }

    @Test
    public void resolvesPerComboSettingsAgainstBase() {
        ParameterSweep sweep = iterationRegularizationSweep();
        RecordingAdapter adapter = new RecordingAdapter();
        new DeconvolutionSweep(rawCrop(), null, adapter, base())
                .dispatch(sweep, sink(), NEVER_CANCEL);

        assertEquals(4, adapter.settings.size());
        for (DeconvSettings s : adapter.settings) {
            // engine / algorithm / psf are not swept, so stay at base.
            assertEquals("DL2", s.engineKey());
            assertEquals(Algorithm.RL, s.algorithm());
            assertEquals(PsfModel.GIBSON_LANNI, s.psfModel());
            assertTrue(s.iterations() == 10 || s.iterations() == 20);
            assertTrue(Math.abs(s.regularization() - 0.01d) < 1e-9
                    || Math.abs(s.regularization() - 0.02d) < 1e-9);
        }
    }

    @Test
    public void dispatchesOnlyPlannedCombosWhenProvided() {
        ParameterSweep sweep = iterationRegularizationSweep();
        List<ParameterCombo> planned = new ArrayList<ParameterCombo>();
        planned.add(sweep.combos().get(0));
        planned.add(sweep.combos().get(2));
        RecordingAdapter adapter = new RecordingAdapter();
        final List<VariationResult> published = new ArrayList<VariationResult>();

        new DeconvolutionSweep(rawCrop(), null, adapter, base(), planned)
                .dispatch(sweep, new Consumer<VariationResult>() {
                    @Override public void accept(VariationResult result) {
                        published.add(result);
                    }
                }, NEVER_CANCEL);

        assertEquals(2, adapter.settings.size());
        assertEquals(2, published.size());
    }

    @Test
    public void cancelledSweepPublishesNothing() {
        RecordingAdapter adapter = new RecordingAdapter();
        final List<VariationResult> published = new ArrayList<VariationResult>();
        new DeconvolutionSweep(rawCrop(), null, adapter, base())
                .dispatch(iterationRegularizationSweep(), new Consumer<VariationResult>() {
                    @Override public void accept(VariationResult result) {
                        published.add(result);
                    }
                }, new BooleanSupplier() {
                    @Override public boolean getAsBoolean() {
                        return true;
                    }
                });
        assertEquals(0, published.size());
        assertEquals(0, adapter.settings.size());
    }

    @Test
    public void fatalPublisherDisposesProducedImageBeforeRethrow() {
        final ThreadDeath fatal = new ThreadDeath();
        CountingAdapter adapter = new CountingAdapter(null, false);

        try {
            new DeconvolutionSweep(rawCrop(), null, adapter, base())
                    .dispatch(singleComboSweep(), new Consumer<VariationResult>() {
                        @Override public void accept(VariationResult result) {
                            throw fatal;
                        }
                    }, NEVER_CANCEL);
            fail("Expected fatal publisher failure.");
        } catch (ThreadDeath expected) {
            assertSame(fatal, expected);
        }

        assertEquals(1, adapter.closeCalls);
    }

    @Test
    public void fatalPublisherKeepsPrecedenceOverOrdinaryCloseFailure() {
        final ThreadDeath fatal = new ThreadDeath();
        RuntimeException closeFailure = new RuntimeException("close failed");
        CountingAdapter adapter = new CountingAdapter(closeFailure, false);

        try {
            new DeconvolutionSweep(rawCrop(), null, adapter, base())
                    .dispatch(singleComboSweep(), new Consumer<VariationResult>() {
                        @Override public void accept(VariationResult result) {
                            throw fatal;
                        }
                    }, NEVER_CANCEL);
            fail("Expected fatal publisher failure.");
        } catch (ThreadDeath expected) {
            assertSame(fatal, expected);
            assertEquals(1, expected.getSuppressed().length);
            assertSame(closeFailure, expected.getSuppressed()[0]);
        }

        assertEquals("rejected cleanup exhausts its bounded retry budget",
                8, adapter.closeCalls);
        assertEquals(1, VariationCleanupCoordinatorTestAccess.pendingCount());

        adapter.allowClose();
        assertNull(VariationCleanupCoordinatorTestAccess.drain());
        assertEquals(9, adapter.closeCalls);
        assertEquals(0, VariationCleanupCoordinatorTestAccess.pendingCount());
    }

    @Test
    public void borrowedRawSourceIsNeverClosedWhenAdapterReturnsIt() {
        ImagePlus raw = rawCrop();
        CountingAdapter adapter = new CountingAdapter(null, true);

        new DeconvolutionSweep(raw, null, adapter, base())
                .dispatch(singleComboSweep(), sink(), NEVER_CANCEL);

        assertEquals(0, adapter.closeCalls);
    }

    @Test
    public void cancellationAfterPreviewClosesProducedImage() {
        final AtomicInteger checks = new AtomicInteger();
        CountingAdapter adapter = new CountingAdapter(null, false);
        final List<VariationResult> published = new ArrayList<VariationResult>();

        new DeconvolutionSweep(rawCrop(), null, adapter, base())
                .dispatch(singleComboSweep(), new Consumer<VariationResult>() {
                    @Override public void accept(VariationResult result) {
                        published.add(result);
                    }
                }, new BooleanSupplier() {
                    @Override public boolean getAsBoolean() {
                        return checks.incrementAndGet() >= 3;
                    }
                });

        assertEquals(1, adapter.closeCalls);
        assertEquals(0, published.size());
    }

    private static ParameterSweep singleComboSweep() {
        Map<ParameterKey, ParameterValueList> values =
                new LinkedHashMap<ParameterKey, ParameterValueList>();
        values.put(DeconvParameterId.ITERATIONS, ParameterValueList.ofInts(10));
        return new ParameterSweep(ParameterSweep.Method.DECONVOLUTION, values,
                null, "DAPI", "image-single");
    }

    private static Consumer<VariationResult> sink() {
        return new Consumer<VariationResult>() {
            @Override public void accept(VariationResult result) {
            }
        };
    }

    private static final class RecordingAdapter implements DeconvolutionPreviewAdapter {
        final List<DeconvSettings> settings = new ArrayList<DeconvSettings>();

        @Override
        public ImagePlus deconvolvePreview(ImagePlus rawCrop, DeconvSettings s) {
            settings.add(s);
            return new ImagePlus("deconv", rawCrop.getProcessor().duplicate());
        }

        @Override
        public void close(ImagePlus image) {
        }
    }

    private static final class FailingOnceAdapter implements DeconvolutionPreviewAdapter {
        final List<DeconvSettings> settings = new ArrayList<DeconvSettings>();

        @Override
        public ImagePlus deconvolvePreview(ImagePlus rawCrop, DeconvSettings s) {
            settings.add(s);
            if (s.iterations() == 20 && Math.abs(s.regularization() - 0.02d) < 1e-9) {
                throw new RuntimeException("simulated DL2 failure");
            }
            return new ImagePlus("deconv", rawCrop.getProcessor().duplicate());
        }

        @Override
        public void close(ImagePlus image) {
        }
    }

    private static final class CountingAdapter implements DeconvolutionPreviewAdapter {
        private RuntimeException closeFailure;
        private final boolean returnSource;
        int closeCalls;

        CountingAdapter(RuntimeException closeFailure, boolean returnSource) {
            this.closeFailure = closeFailure;
            this.returnSource = returnSource;
        }

        void allowClose() {
            closeFailure = null;
        }

        @Override
        public ImagePlus deconvolvePreview(ImagePlus rawCrop, DeconvSettings settings) {
            return returnSource
                    ? rawCrop
                    : new ImagePlus("produced", rawCrop.getProcessor().duplicate());
        }

        @Override
        public void close(ImagePlus image) {
            closeCalls++;
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }

    private static final class TrackingImage extends ImagePlus {
        int closeCalls;

        TrackingImage(String title) {
            super(title, new ByteProcessor(4, 4));
        }

        @Override public void close() {
            closeCalls++;
            super.close();
        }
    }
}
