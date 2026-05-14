package flash.pipeline.ui.variations;

import org.junit.Test;

import flash.pipeline.objects.ObjectsCounter3DWrapper;
import flash.pipeline.ui.config.ClassicalSegmentationStage;
import flash.pipeline.ui.config.ConfigQcContext;

import ij.ImagePlus;
import ij.process.ByteProcessor;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ParameterSweepEditorTest {

    @Test
    public void valueChipPanelAddsEditsAndRemovesValues() {
        ValueChipPanel panel = new ValueChipPanel(
                ParameterValueList.ofDoubles(1.0d, 2.0d),
                ValueChipPanel.doubleParser());

        panel.addValueForTest("3.5");
        panel.editValueForTest(0, "0.5");
        panel.removeValueForTest(1);

        List<Object> values = panel.currentValueList().values();
        assertEquals(2, values.size());
        assertEquals(Double.valueOf(0.5d), values.get(0));
        assertEquals(Double.valueOf(3.5d), values.get(1));
    }

    @Test
    public void currentSweepUsesSelectedListsAndBaseValuesForUncheckedRows() {
        ParameterCombo base = ParameterCombo.builder()
                .put(ParameterId.THRESHOLD, Integer.valueOf(100))
                .put(ParameterId.MIN_SIZE, Integer.valueOf(50))
                .put(ParameterId.MAX_SIZE, Integer.valueOf(500))
                .build();
        ParameterSweepEditor editor = new ParameterSweepEditor(
                ParameterSweep.Method.CLASSICAL, base, "DAPI", "hash");

        editor.setParameterValuesForTest(ParameterId.THRESHOLD,
                Arrays.<Object>asList(Integer.valueOf(80), Integer.valueOf(100), Integer.valueOf(120)));
        editor.setSweptForTest(ParameterId.THRESHOLD, true);
        editor.setParameterValuesForTest(ParameterId.MIN_SIZE,
                Arrays.<Object>asList(Integer.valueOf(20), Integer.valueOf(40)));
        editor.setSweptForTest(ParameterId.MIN_SIZE, true);
        editor.setParameterValuesForTest(ParameterId.MAX_SIZE,
                Arrays.<Object>asList(Integer.valueOf(500), Integer.valueOf(1000)));
        editor.setSweptForTest(ParameterId.MAX_SIZE, false);

        ParameterSweep sweep = editor.currentSweep();

        assertEquals(6L, sweep.cellCount());
        assertEquals(Integer.valueOf(500),
                sweep.combos().get(0).get(ParameterId.MAX_SIZE));
        assertEquals(Integer.valueOf(80),
                sweep.combos().get(0).get(ParameterId.THRESHOLD));
        assertEquals(Integer.valueOf(40),
                sweep.combos().get(1).get(ParameterId.MIN_SIZE));
    }

    @Test
    public void sweepRoundTripsBackIntoEditor() {
        ParameterSweepEditor editor = new ParameterSweepEditor(
                ParameterSweep.Method.CLASSICAL,
                ParameterCombo.builder()
                        .put(ParameterId.THRESHOLD, Integer.valueOf(100))
                        .put(ParameterId.MIN_SIZE, Integer.valueOf(50))
                        .put(ParameterId.MAX_SIZE, Integer.valueOf(500))
                        .build(),
                "DAPI",
                "hash");
        java.util.LinkedHashMap<ParameterId, ParameterValueList> values =
                new java.util.LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.THRESHOLD, ParameterValueList.ofInts(80, 100, 120));
        values.put(ParameterId.MIN_SIZE, ParameterValueList.ofInts(20));
        values.put(ParameterId.MAX_SIZE, ParameterValueList.ofInts(500));
        ParameterSweep sweep = new ParameterSweep(ParameterSweep.Method.CLASSICAL,
                values, CropSpec.centre256(), "DAPI", "hash");

        editor.setSweep(sweep);
        ParameterSweep roundTrip = editor.currentSweep();

        assertTrue(editor.isSweptForTest(ParameterId.THRESHOLD));
        assertFalse(editor.isSweptForTest(ParameterId.MIN_SIZE));
        assertEquals(3, editor.valueCountForTest(ParameterId.THRESHOLD));
        assertEquals(sweep.toCanonicalJson(), roundTrip.toCanonicalJson());
    }

    @Test
    public void sourceHashChangesWhenPixelsChange() {
        ParameterCombo base = ParameterCombo.builder()
                .put(ParameterId.THRESHOLD, Integer.valueOf(100))
                .put(ParameterId.MIN_SIZE, Integer.valueOf(50))
                .put(ParameterId.MAX_SIZE, Integer.valueOf(500))
                .build();
        ParameterSweepEditor first = new ParameterSweepEditor(
                VariationEngineContext.forClassical("DAPI",
                        imageWithPixel(10), imageWithPixel(10), null, base,
                        new NullPreviewAdapter()));
        ParameterSweepEditor second = new ParameterSweepEditor(
                VariationEngineContext.forClassical("DAPI",
                        imageWithPixel(20), imageWithPixel(20), null, base,
                        new NullPreviewAdapter()));

        assertFalse(first.currentSweep().sourceImageHash()
                .equals(second.currentSweep().sourceImageHash()));
    }

    private static ImagePlus imageWithPixel(int value) {
        ByteProcessor processor = new ByteProcessor(2, 2);
        processor.set(0, 0, value);
        return new ImagePlus("same-title", processor);
    }

    private static final class NullPreviewAdapter
            implements ClassicalSegmentationStage.PreviewAdapter {
        @Override public ImagePlus createRawSource(ConfigQcContext context) {
            return null;
        }

        @Override public ImagePlus createFilteredSource(ConfigQcContext context) {
            return null;
        }

        @Override public ObjectsCounter3DWrapper.Result runPreview(ImagePlus filteredSource,
                                                                   int threshold,
                                                                   int minSize,
                                                                   int maxSize) {
            return null;
        }

        @Override public int countObjects(ObjectsCounter3DWrapper.Result result) {
            return 0;
        }

        @Override public void close(ImagePlus image) {
        }
    }
}
