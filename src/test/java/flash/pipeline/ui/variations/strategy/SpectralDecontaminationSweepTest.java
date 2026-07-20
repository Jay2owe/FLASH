package flash.pipeline.ui.variations.strategy;

import flash.pipeline.decontamination.SpectralDecontaminationConfig;
import flash.pipeline.ui.variations.CropSpec;
import flash.pipeline.ui.variations.ParameterKey;
import flash.pipeline.ui.variations.ParameterSweep;
import flash.pipeline.ui.variations.ParameterValueList;
import flash.pipeline.ui.variations.SpectralParameterId;
import flash.pipeline.ui.variations.SpectralPreviewAdapter;
import flash.pipeline.ui.variations.VariationResult;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;

import org.junit.Test;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SpectralDecontaminationSweepTest {

    private static final BooleanSupplier NEVER_CANCEL = new BooleanSupplier() {
        @Override public boolean getAsBoolean() {
            return false;
        }
    };

    @Test
    public void fatalPublisherClosesMergeAndAncillariesExactlyOnce() {
        ImagePlus raw = image("raw");
        ImagePlus merge = image("merge");
        ImagePlus corrected = image("corrected");
        ImagePlus mask = image("mask");
        RecordingAdapter adapter = new RecordingAdapter(
                new SpectralPreviewAdapter.Result(merge, corrected, mask));
        final ThreadDeath fatal = new ThreadDeath();

        try {
            strategy(raw, adapter).dispatch(singleComboSweep(),
                    new Consumer<VariationResult>() {
                        @Override public void accept(VariationResult result) {
                            throw fatal;
                        }
                    }, NEVER_CANCEL);
            fail("Expected fatal publisher failure.");
        } catch (ThreadDeath expected) {
            assertSame(fatal, expected);
        }

        assertEquals(1, adapter.closeCount(merge));
        assertEquals(1, adapter.closeCount(corrected));
        assertEquals(1, adapter.closeCount(mask));
        assertEquals(0, adapter.closeCount(raw));
    }

    @Test
    public void fatalMetricFailureStillClosesEveryProducedImage() {
        ImagePlus raw = image("raw");
        ImagePlus merge = image("merge");
        ThreadDeath fatal = new ThreadDeath();
        ImagePlus corrected = new FatalStackImage("corrected", fatal);
        ImagePlus mask = image("mask");
        RecordingAdapter adapter = new RecordingAdapter(
                new SpectralPreviewAdapter.Result(merge, corrected, mask));

        try {
            strategy(raw, adapter).dispatch(singleComboSweep(), sink(), NEVER_CANCEL);
            fail("Expected fatal metrics failure.");
        } catch (ThreadDeath expected) {
            assertSame(fatal, expected);
        }

        assertEquals(1, adapter.closeCount(merge));
        assertEquals(1, adapter.closeCount(corrected));
        assertEquals(1, adapter.closeCount(mask));
    }

    @Test
    public void mergeAliasesAreNotClosedAsAncillariesOrAfterAdoption() {
        ImagePlus raw = image("raw");
        final ImagePlus merge = image("merge");
        RecordingAdapter adapter = new RecordingAdapter(
                new SpectralPreviewAdapter.Result(merge, merge, merge));
        final List<VariationResult> published = new ArrayList<VariationResult>();

        strategy(raw, adapter).dispatch(singleComboSweep(),
                new Consumer<VariationResult>() {
                    @Override public void accept(VariationResult result) {
                        published.add(result);
                        result.transferOwnership();
                    }
                }, NEVER_CANCEL);

        assertEquals(1, published.size());
        assertSame(merge, published.get(0).previewImage());
        published.get(0).dispose();
        assertEquals(0, adapter.closeCount(merge));
    }

    @Test
    public void ordinaryAncillaryCloseFailureDoesNotSkipOtherCleanup() {
        ImagePlus raw = image("raw");
        ImagePlus merge = image("merge");
        ImagePlus corrected = image("corrected");
        ImagePlus mask = image("mask");
        RuntimeException closeFailure = new RuntimeException("corrected close failed");
        RecordingAdapter adapter = new RecordingAdapter(
                new SpectralPreviewAdapter.Result(merge, corrected, mask));
        adapter.failOn(corrected, closeFailure);
        final List<VariationResult> published = new ArrayList<VariationResult>();

        strategy(raw, adapter).dispatch(singleComboSweep(),
                new Consumer<VariationResult>() {
                    @Override public void accept(VariationResult result) {
                        published.add(result);
                    }
                }, NEVER_CANCEL);

        assertEquals(1, adapter.closeCount(corrected));
        assertEquals(1, adapter.closeCount(mask));
        assertEquals(1, adapter.closeCount(merge));
        assertEquals(1, published.size());
        assertTrue(published.get(0).hasError());
        assertSame(closeFailure, published.get(0).error());
    }

    @Test
    public void borrowedSourceReturnedAsMergeIsNeverClosed() {
        ImagePlus raw = image("raw");
        RecordingAdapter adapter = new RecordingAdapter(
                new SpectralPreviewAdapter.Result(raw, null, null));
        final List<VariationResult> published = new ArrayList<VariationResult>();

        strategy(raw, adapter).dispatch(singleComboSweep(),
                new Consumer<VariationResult>() {
                    @Override public void accept(VariationResult result) {
                        published.add(result);
                    }
                }, NEVER_CANCEL);

        assertEquals(0, adapter.closeCount(raw));
        assertEquals(1, published.size());
        assertTrue(published.get(0).hasError());
    }

    @Test
    public void cancellationAfterPreviewClosesIdentityAliasesOnce() {
        ImagePlus raw = image("raw");
        ImagePlus merge = image("merge");
        RecordingAdapter adapter = new RecordingAdapter(
                new SpectralPreviewAdapter.Result(merge, merge, merge));
        final AtomicInteger checks = new AtomicInteger();
        final List<VariationResult> published = new ArrayList<VariationResult>();

        strategy(raw, adapter).dispatch(singleComboSweep(),
                new Consumer<VariationResult>() {
                    @Override public void accept(VariationResult result) {
                        published.add(result);
                    }
                }, new BooleanSupplier() {
                    @Override public boolean getAsBoolean() {
                        return checks.incrementAndGet() >= 3;
                    }
                });

        assertEquals(1, adapter.closeCount(merge));
        assertEquals(0, published.size());
    }

    private static SpectralDecontaminationSweep strategy(ImagePlus raw,
                                                         RecordingAdapter adapter) {
        return new SpectralDecontaminationSweep(raw, adapter,
                new SpectralDecontaminationConfig());
    }

    private static ParameterSweep singleComboSweep() {
        Map<ParameterKey, ParameterValueList> values =
                new LinkedHashMap<ParameterKey, ParameterValueList>();
        values.put(SpectralParameterId.STRENGTH,
                ParameterValueList.ofDoubles(1.0d));
        return new ParameterSweep(ParameterSweep.Method.SPECTRAL, values,
                CropSpec.full(), "channels", "spectral-source");
    }

    private static Consumer<VariationResult> sink() {
        return new Consumer<VariationResult>() {
            @Override public void accept(VariationResult result) {
            }
        };
    }

    private static ImagePlus image(String title) {
        ByteProcessor processor = new ByteProcessor(2, 2);
        processor.set(0, 1);
        processor.set(1, 2);
        processor.set(2, 3);
        processor.set(3, 4);
        return new ImagePlus(title, processor);
    }

    private static final class RecordingAdapter implements SpectralPreviewAdapter {
        private final Result result;
        private final IdentityHashMap<ImagePlus, Integer> closeCounts =
                new IdentityHashMap<ImagePlus, Integer>();
        private final IdentityHashMap<ImagePlus, RuntimeException> failures =
                new IdentityHashMap<ImagePlus, RuntimeException>();

        RecordingAdapter(Result result) {
            this.result = result;
        }

        @Override
        public Result decontaminatePreview(ImagePlus rawCropMultiChannel,
                                           SpectralDecontaminationConfig resolvedConfig) {
            return result;
        }

        @Override
        public void close(Result value) {
            if (value == null) {
                return;
            }
            record(value.mergeRgb());
            record(value.correctedGray());
            record(value.maskOrNull());
        }

        void failOn(ImagePlus image, RuntimeException failure) {
            failures.put(image, failure);
        }

        int closeCount(ImagePlus image) {
            Integer count = closeCounts.get(image);
            return count == null ? 0 : count.intValue();
        }

        private void record(ImagePlus image) {
            if (image == null) {
                return;
            }
            closeCounts.put(image, Integer.valueOf(closeCount(image) + 1));
            RuntimeException failure = failures.get(image);
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static final class FatalStackImage extends ImagePlus {
        private final ThreadDeath fatal;

        FatalStackImage(String title, ThreadDeath fatal) {
            super(title, new ByteProcessor(1, 1));
            this.fatal = fatal;
        }

        @Override public ImageStack getStack() {
            throw fatal;
        }
    }
}
