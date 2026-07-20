package flash.pipeline.ui.variations.strategy;

import flash.pipeline.ui.config.CellposeParameterStage;
import flash.pipeline.ui.config.ConfigQcContext;
import flash.pipeline.ui.variations.CropSpec;
import flash.pipeline.ui.variations.ParameterCombo;
import flash.pipeline.ui.variations.ParameterId;
import flash.pipeline.ui.variations.ParameterSweep;
import flash.pipeline.ui.variations.ParameterValueList;
import flash.pipeline.ui.variations.VariationResult;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import ij.process.ShortProcessor;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CellposeOneShotTest {

    @Test
    public void dispatchRunsTwoCellsThroughPreviewAdapter() throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        CellposeOneShot strategy = new CellposeOneShot(sourceImage(),
                CropSpec.full(),
                null,
                adapter,
                baseParameters(),
                null);
        List<VariationResult> results = new ArrayList<VariationResult>();

        strategy.dispatch(sweep(), results::add, () -> false);

        assertEquals(2, results.size());
        assertEquals(2, adapter.parameters.size());
        assertEquals(20.0d, adapter.parameters.get(0).diameter, 0.001d);
        assertEquals(30.0d, adapter.parameters.get(1).diameter, 0.001d);
        assertEquals(2, results.get(0).nObjects());
        assertEquals(2, results.get(1).nObjects());
        assertFalse(results.get(0).hasError());
        assertFalse(results.get(1).hasError());
    }

    @Test
    public void resultConstructionFailureClosesLabelOnceAndPublishesOriginalFailure()
            throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        RuntimeException primary = new RuntimeException("synthetic count failure");
        adapter.countFailure = primary;
        CellposeOneShot strategy = strategy(adapter, baseParameters());
        List<VariationResult> results = new ArrayList<VariationResult>();

        strategy.dispatch(singleSweep(), results::add, () -> false);

        assertEquals(1, results.size());
        assertSame(primary, results.get(0).error());
        assertEquals(1, adapter.labelCloseCalls);
    }

    @Test
    public void cancellationCallbackFailureClosesUntransferredLabelOnceAndPropagates()
            throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        RuntimeException primary = new RuntimeException("synthetic cancellation failure");
        CellposeOneShot strategy = strategy(adapter, baseParameters());

        try {
            strategy.dispatch(singleSweep(), result -> { },
                    new ThrowOnCheckSupplier(3, primary));
            fail("Expected cancellation callback failure.");
        } catch (RuntimeException actual) {
            assertSame(primary, actual);
        }

        assertEquals(1, adapter.labelCloseCalls);
    }

    @Test
    public void cacheFailureClosesUntransferredLabelOnceAndPropagatesPrimary()
            throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        RuntimeException primary = new RuntimeException("synthetic cache failure");
        CellposeOneShot.CacheAccess cache = new CellposeOneShot.CacheAccess() {
            @Override public ImagePlus get(String key) {
                return null;
            }

            @Override public void put(String key, ImagePlus label) {
                throw primary;
            }
        };
        CellposeOneShot strategy = new CellposeOneShot(sourceImage(),
                CropSpec.full(), null, adapter, baseParameters(), null, cache);

        try {
            strategy.dispatch(singleSweep(), result -> { }, () -> false);
            fail("Expected cache failure.");
        } catch (RuntimeException actual) {
            assertSame(primary, actual);
        }

        assertEquals(1, adapter.labelCloseCalls);
    }

    @Test
    public void publisherFailureKeepsPrimaryAndClosesUnpublishedLabelExactlyOnce()
            throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        RuntimeException primary = new RuntimeException("synthetic publisher failure");
        RuntimeException cleanup = new RuntimeException("synthetic label close failure");
        adapter.closeFailure = cleanup;
        CellposeOneShot strategy = strategy(adapter, baseParameters());

        try {
            strategy.dispatch(singleSweep(), result -> {
                throw primary;
            }, () -> false);
            fail("Expected publisher failure.");
        } catch (RuntimeException actual) {
            assertSame(primary, actual);
            assertEquals(1, actual.getSuppressed().length);
            assertSame(cleanup, actual.getSuppressed()[0]);
        }

        assertEquals(1, adapter.labelCloseCalls);
    }

    @Test
    public void companionInterruptionIsRestoredAndRethrownWithoutPreviewRun()
            throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        InterruptedException primary =
                new InterruptedException("synthetic companion interruption");
        adapter.companionFailure = primary;
        CellposeOneShot strategy = strategy(adapter, baseParameters(1));

        try {
            strategy.dispatch(singleSweep(), result -> { }, () -> false);
            fail("Expected companion interruption.");
        } catch (InterruptedException actual) {
            assertSame(primary, actual);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }

        assertEquals(0, adapter.parameters.size());
        assertEquals(0, adapter.labelCloseCalls);
    }

    @Test
    public void inputAliasIsRejectedBeforeCacheOrSuccessPublicationWithoutClosingSource()
            throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        adapter.returnInputAsLabel = true;
        RecordingCacheAccess cache = new RecordingCacheAccess();
        CellposeOneShot strategy = strategy(adapter, baseParameters(), cache);
        List<VariationResult> results = new ArrayList<VariationResult>();

        strategy.dispatch(singleSweep(), results::add, () -> false);

        assertEquals(1, results.size());
        assertTrue(results.get(0).hasError());
        assertTrue(results.get(0).error().getMessage().contains("distinct owned label"));
        assertEquals(0, cache.putCalls);
        assertEquals(0, adapter.labelCloseCalls);
    }

    @Test
    public void inputAliasCancellationDoesNotCloseSharedSourceOrPopulateCache()
            throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        adapter.returnInputAsLabel = true;
        RecordingCacheAccess cache = new RecordingCacheAccess();
        CellposeOneShot strategy = strategy(adapter, baseParameters(), cache);
        List<VariationResult> results = new ArrayList<VariationResult>();

        strategy.dispatch(singleSweep(), results::add, new CancelOnCheckSupplier(3));

        assertTrue(results.isEmpty());
        assertEquals(0, cache.putCalls);
        assertEquals(0, adapter.labelCloseCalls);
    }

    @Test
    public void companionAliasPublisherFailureRetainsContractPrimaryAndClosesCompanionOnce()
            throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        adapter.fullCompanion = labelImage();
        adapter.returnCompanionAsLabel = true;
        RecordingCacheAccess cache = new RecordingCacheAccess();
        CellposeOneShot strategy = strategy(adapter, baseParameters(1), cache);
        RuntimeException publisherFailure =
                new RuntimeException("synthetic alias publisher failure");

        try {
            strategy.dispatch(singleSweep(), result -> {
                throw publisherFailure;
            }, () -> false);
            fail("Expected shared-label contract failure.");
        } catch (IllegalStateException actual) {
            assertTrue(actual.getMessage().contains("distinct owned label"));
            assertEquals(1, actual.getSuppressed().length);
            assertSame(publisherFailure, actual.getSuppressed()[0]);
        }

        assertEquals(0, cache.putCalls);
        assertEquals(1, adapter.labelCloseCalls);
    }

    private static CellposeOneShot strategy(RecordingPreviewAdapter adapter,
                                            CellposeParameterStage.Parameters parameters) {
        return new CellposeOneShot(sourceImage(), CropSpec.full(), null,
                adapter, parameters, null);
    }

    private static CellposeOneShot strategy(RecordingPreviewAdapter adapter,
                                            CellposeParameterStage.Parameters parameters,
                                            CellposeOneShot.CacheAccess cache) {
        return new CellposeOneShot(sourceImage(), CropSpec.full(), null,
                adapter, parameters, null, cache);
    }

    private static ParameterSweep sweep() {
        Map<ParameterId, ParameterValueList> values =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.DIAMETER, ParameterValueList.ofDoubles(20.0d, 30.0d));
        values.put(ParameterId.FLOW_THRESHOLD, ParameterValueList.ofDoubles(0.4d));
        values.put(ParameterId.CELLPROB_THRESHOLD, ParameterValueList.ofDoubles(0.0d));
        return new ParameterSweep(ParameterSweep.Method.CELLPOSE,
                values,
                CropSpec.full(),
                "DAPI",
                "synthetic");
    }

    private static ParameterSweep singleSweep() {
        Map<ParameterId, ParameterValueList> values =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.DIAMETER, ParameterValueList.ofDoubles(20.0d));
        values.put(ParameterId.FLOW_THRESHOLD, ParameterValueList.ofDoubles(0.4d));
        values.put(ParameterId.CELLPROB_THRESHOLD, ParameterValueList.ofDoubles(0.0d));
        return new ParameterSweep(ParameterSweep.Method.CELLPOSE,
                values, CropSpec.full(), "DAPI", "single-synthetic");
    }

    private static CellposeParameterStage.Parameters baseParameters() {
        return baseParameters(-1);
    }

    private static CellposeParameterStage.Parameters baseParameters(int companionIndex) {
        return new CellposeParameterStage.Parameters(
                "cyto3",
                companionIndex,
                30.0d,
                0.4d,
                0.0d,
                false);
    }

    private static ImagePlus sourceImage() {
        return new ImagePlus("source", new ByteProcessor(4, 1));
    }

    private static ImagePlus labelImage() {
        ShortProcessor processor = new ShortProcessor(4, 1);
        processor.set(0, 0, 1);
        processor.set(1, 0, 1);
        processor.set(2, 0, 2);
        processor.set(3, 0, 2);
        return new ImagePlus("labels", processor);
    }

    private static final class RecordingPreviewAdapter
            implements CellposeParameterStage.PreviewAdapter {
        final List<CellposeParameterStage.Parameters> parameters =
                new ArrayList<CellposeParameterStage.Parameters>();
        ImagePlus lastLabel;
        RuntimeException countFailure;
        RuntimeException closeFailure;
        Exception companionFailure;
        ImagePlus fullCompanion;
        boolean returnInputAsLabel;
        boolean returnCompanionAsLabel;
        int labelCloseCalls;

        @Override public ImagePlus createRawSource(ConfigQcContext context) {
            return null;
        }

        @Override public ImagePlus createFilteredSource(ConfigQcContext context) {
            return null;
        }

        @Override public ImagePlus createFilteredCompanionSource(ConfigQcContext context,
                                                                 int channelIndex) throws Exception {
            if (companionFailure != null) {
                throw companionFailure;
            }
            return fullCompanion;
        }

        @Override public ImagePlus runPreview(ImagePlus filteredSource,
                                              ImagePlus filteredCompanionSource,
                                              CellposeParameterStage.Parameters parameters) {
            this.parameters.add(parameters);
            if (returnInputAsLabel) {
                lastLabel = filteredSource;
                return lastLabel;
            }
            if (returnCompanionAsLabel) {
                lastLabel = filteredCompanionSource;
                return lastLabel;
            }
            lastLabel = labelImage();
            return lastLabel;
        }

        @Override public int countLabels(ImagePlus labelImage) {
            if (countFailure != null) {
                throw countFailure;
            }
            return labelImage == null ? 0
                    : (int) labelImage.getProcessor().getStats().max;
        }

        @Override public void close(ImagePlus image) {
            if (image != null) {
                if (image == lastLabel) {
                    labelCloseCalls++;
                }
                if (closeFailure != null && image == lastLabel) {
                    throw closeFailure;
                }
                image.flush();
            }
        }
    }

    private static final class ThrowOnCheckSupplier implements BooleanSupplier {
        private final int throwOn;
        private final RuntimeException failure;
        private int calls;

        ThrowOnCheckSupplier(int throwOn, RuntimeException failure) {
            this.throwOn = throwOn;
            this.failure = failure;
        }

        @Override public boolean getAsBoolean() {
            calls++;
            if (calls == throwOn) {
                throw failure;
            }
            return false;
        }
    }

    private static final class CancelOnCheckSupplier implements BooleanSupplier {
        private final int cancelOn;
        private int calls;

        CancelOnCheckSupplier(int cancelOn) {
            this.cancelOn = cancelOn;
        }

        @Override public boolean getAsBoolean() {
            calls++;
            return calls >= cancelOn;
        }
    }

    private static final class RecordingCacheAccess implements CellposeOneShot.CacheAccess {
        int putCalls;

        @Override public ImagePlus get(String key) {
            return null;
        }

        @Override public void put(String key, ImagePlus label) {
            putCalls++;
        }
    }
}
