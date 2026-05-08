package flash.pipeline.ui.config;

import flash.pipeline.io.SeriesMeta;
import flash.pipeline.ui.preview.PreviewPairPanel;
import flash.pipeline.zslice.ZSliceRange;
import flash.pipeline.zslice.ZSliceSelection;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ZSliceSelectionStageTest {

    @Test
    public void rejectsRangeOutsideCurrentStack() {
        RecordingStore store = new RecordingStore();
        ZSliceSelectionStage stage = stage(metas(10), store, null);
        ConfigQcContext context = contextFor(metas(10));
        PreviewPairPanel preview = new PreviewPairPanel("Original", "Adjusted");
        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, preview);

        stage.setRangeForTest("2", "12");

        assertFalse(stage.lockIn(context));
        assertTrue(stage.errorTextForTest().contains("1-10"));
        assertTrue(store.selections.isEmpty());
    }

    @Test
    public void feedbackTracksSharedPreviewZ() {
        RecordingStore store = new RecordingStore();
        ZSliceSelectionStage stage = stage(metas(10), store, null);
        ConfigQcContext context = contextFor(metas(10));
        PreviewPairPanel preview = new PreviewPairPanel("Original", "Adjusted");
        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, preview);

        stage.setRangeForTest("2", "4");
        preview.setCurrentZ(3);
        assertTrue(stage.feedbackTextForTest().contains("inside"));

        preview.setCurrentZ(5);
        assertTrue(stage.feedbackTextForTest().contains("outside"));
    }

    @Test
    public void applyToCompatibleWritesAllRemainingWhenRangeFits() {
        List<SeriesMeta> metas = metas(10, 12, 11);
        RecordingStore store = new RecordingStore();
        ZSliceSelectionStage stage = stage(metas, store, null);
        ConfigQcContext context = contextFor(metas);
        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));

        stage.setRangeForTest("3", "8");
        stage.setActionForTest(ZSliceSelectionStage.ACTION_APPLY_TO_COMPATIBLE);

        assertTrue(stage.lockIn(context));
        assertEquals("3-8", store.selections.get(Integer.valueOf(0)).range.toToken());
        assertEquals("3-8", store.selections.get(Integer.valueOf(1)).range.toToken());
        assertEquals("3-8", store.selections.get(Integer.valueOf(2)).range.toToken());
        assertEquals(Integer.valueOf(3), context.consumeRequestedNextImageIndex());
    }

    @Test
    public void partialApplyWritesCompatibleImagesAndJumpsToFirstOutlier() {
        List<SeriesMeta> metas = metas(10, 12, 5, 14);
        RecordingStore store = new RecordingStore();
        ZSliceSelectionStage.PartialApplyHandler handler = new ZSliceSelectionStage.PartialApplyHandler() {
            @Override public ZSliceSelectionStage.PartialApplyChoice choose(
                    ZSliceRange range,
                    List<SeriesMeta> compatibleMetas,
                    List<SeriesMeta> incompatibleMetas) {
                assertEquals(2, compatibleMetas.size());
                assertEquals(1, incompatibleMetas.size());
                return ZSliceSelectionStage.PartialApplyChoice.APPLY_TO_COMPATIBLE;
            }
        };
        ZSliceSelectionStage stage = stage(metas, store, handler);
        ConfigQcContext context = contextFor(metas);
        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));

        stage.setRangeForTest("3", "8");
        stage.setActionForTest(ZSliceSelectionStage.ACTION_APPLY_TO_COMPATIBLE);

        assertTrue(stage.lockIn(context));
        assertEquals("3-8", store.selections.get(Integer.valueOf(0)).range.toToken());
        assertEquals("3-8", store.selections.get(Integer.valueOf(1)).range.toToken());
        assertNull(store.selections.get(Integer.valueOf(2)));
        assertEquals("3-8", store.selections.get(Integer.valueOf(3)).range.toToken());
        assertEquals(Integer.valueOf(2), context.consumeRequestedNextImageIndex());
    }

    @Test
    public void computeCompatibilityClassifiesRemainingBySliceCount() {
        ZSliceSelectionStage.RangeCompatibility result =
                ZSliceSelectionStage.computeRangeCompatibility(
                        metas(10, 4, 8), 1, new ZSliceRange(2, 6));

        assertEquals(Collections.singletonList(Integer.valueOf(2)), result.compatiblePositions);
        assertEquals(1, result.incompatibleMetas.size());
        assertEquals(1, result.firstIncompatiblePosition);
    }

    private static ZSliceSelectionStage stage(List<SeriesMeta> metas,
                                             RecordingStore store,
                                             ZSliceSelectionStage.PartialApplyHandler handler) {
        return new ZSliceSelectionStage(
                metas,
                store,
                new ZSliceSelectionStage.ImageOpener() {
                    @Override public ImagePlus open(SeriesMeta meta) {
                        return stack(meta == null ? "Image" : meta.name, meta == null ? 1 : meta.nSlices);
                    }

                    @Override public void close(ImagePlus image) {
                        if (image != null) image.flush();
                    }
                },
                handler);
    }

    private static ConfigQcContext contextFor(List<SeriesMeta> metas) {
        List<ConfigQcContext.ConfigQcImage> images = new ArrayList<ConfigQcContext.ConfigQcImage>();
        for (int i = 0; i < metas.size(); i++) {
            SeriesMeta meta = metas.get(i);
            images.add(new ConfigQcContext.ConfigQcImage(meta.index, meta.name, null));
        }
        return new ConfigQcContext(null, null, null, images, Arrays.asList("DAPI"), 0);
    }

    private static List<SeriesMeta> metas(int... slices) {
        List<SeriesMeta> metas = new ArrayList<SeriesMeta>();
        for (int i = 0; i < slices.length; i++) {
            metas.add(new SeriesMeta(i, "Series " + (i + 1), slices[i], 1.0, 1.0, 1.0, "pixel"));
        }
        return metas;
    }

    private static ImagePlus stack(String title, int slices) {
        ImageStack stack = new ImageStack(3, 3);
        for (int i = 0; i < Math.max(1, slices); i++) {
            ByteProcessor processor = new ByteProcessor(3, 3);
            processor.set(1, 1, i + 1);
            stack.addSlice(processor);
        }
        return new ImagePlus(title, stack);
    }

    private static final class RecordingStore implements ZSliceSelectionStage.SelectionStore {
        final Map<Integer, ZSliceSelection> selections = new LinkedHashMap<Integer, ZSliceSelection>();

        @Override public ZSliceSelection get(int seriesIndex) {
            return selections.get(Integer.valueOf(seriesIndex));
        }

        @Override public void put(ZSliceSelection selection) {
            selections.put(Integer.valueOf(selection.seriesIndex), selection);
        }
    }

    private static final class RecordingActions implements ConfigQcActions {
        String status = "";

        @Override public void setStatus(String text) {
            status = text;
        }

        @Override public void markPreviewStale(String text) {
            status = text;
        }

        @Override public void setAdjustedPreview(ImagePlus image, String text) {
            status = text;
        }

        @Override public void nextImage() {
        }

        @Override public void skipCurrentImage() {
        }

        @Override public void restartStage() {
        }

        @Override public void cancel() {
        }
    }
}
