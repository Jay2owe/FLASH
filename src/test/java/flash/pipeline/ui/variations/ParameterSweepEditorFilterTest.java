package flash.pipeline.ui.variations;

import flash.pipeline.image.FilterMacroEditorModel;
import flash.pipeline.ui.config.ConfigQcContext;
import flash.pipeline.ui.config.FilterParameterStage;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import org.junit.Test;

import java.io.File;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ParameterSweepEditorFilterTest {

    @Test
    public void forFilterEnumeratesMacroParametersAsFilterIds() {
        FilterVariationEngineContext context = context();

        ParameterSweepEditor editor = ParameterSweepEditor.forFilter(context);

        List<ParameterKey> keys = editor.parameterKeysForTest();
        assertEquals(4, keys.size());
        assertFilterKey(keys.get(0), 0, 0, 0, "Gaussian Blur", "sigma",
                "filter.0.0.0.sigma", ParameterKey.ValueKind.NUMBER);
        assertFilterKey(keys.get(1), 0, 1, 0, "Subtract Background", "rolling",
                "filter.0.1.0.rolling", ParameterKey.ValueKind.NUMBER);
        assertFilterKey(keys.get(2), 0, 2, 0, "Auto Local Threshold (Bernsen)",
                "radius", "filter.0.2.0.radius", ParameterKey.ValueKind.NUMBER);
        assertFilterKey(keys.get(3), 0, 2, 1, "Auto Local Threshold (Bernsen)",
                "method", "filter.0.2.1.method", ParameterKey.ValueKind.STRING);

        ParameterSweep sweep = editor.currentSweep();
        assertEquals(ParameterSweep.Method.FILTER, sweep.method());
        assertEquals(context.sourceImageHash(), sweep.sourceImageHash());
        assertEquals(context.cacheNamespace(), sweep.cacheNamespace());
        assertEquals(Double.valueOf(2.0d), sweep.valueLists().get(keys.get(0)).get(0));
        assertEquals(Integer.valueOf(20), sweep.valueLists().get(keys.get(1)).get(0));
        assertEquals(Integer.valueOf(15), sweep.valueLists().get(keys.get(2)).get(0));
        assertEquals("Bernsen", sweep.valueLists().get(keys.get(3)).get(0));
    }

    private static void assertFilterKey(ParameterKey key,
                                        int sectionIndex,
                                        int entryIndex,
                                        int parameterIndex,
                                        String commandLabel,
                                        String paramKey,
                                        String stableKey,
                                        ParameterKey.ValueKind valueKind) {
        assertTrue(key instanceof FilterParameterId);
        FilterParameterId id = (FilterParameterId) key;
        assertEquals(sectionIndex, id.sectionIndex());
        assertEquals(entryIndex, id.entryIndex());
        assertEquals(parameterIndex, id.parameterIndex());
        assertEquals(commandLabel, id.commandLabel());
        assertEquals(paramKey, id.paramKey());
        assertEquals(stableKey, id.stableKey());
        assertEquals(commandLabel + " / " + paramKey, id.displayLabel());
        assertEquals(valueKind, id.valueKind());
    }

    private static FilterVariationEngineContext context() {
        ImagePlus source = image();
        FilterMacroEditorModel.MacroDefinition macro =
                FilterMacroEditorModel.parse(macroText());
        ConfigQcContext config = ConfigQcContext.fromImages(new File("."),
                new File("target/filter-parameter-editor-test-bin"),
                null, Collections.singletonList(source),
                Collections.singletonList("DAPI"), 0);
        return new FilterVariationEngineContext(macro, source, CropSpec.full(),
                "DAPI", config, new StubPreviewAdapter());
    }

    private static String macroText() {
        return "run(\"Gaussian Blur...\", \"sigma=2 stack\");\n"
                + "run(\"Subtract Background...\", \"rolling=20 stack\");\n"
                + "run(\"Auto Local Threshold\", \"radius=15 method=Bernsen white stack\");";
    }

    private static ImagePlus image() {
        ByteProcessor processor = new ByteProcessor(8, 8);
        processor.setValue(1);
        processor.fill();
        return new ImagePlus("source", processor);
    }

    private static final class StubPreviewAdapter
            implements FilterParameterStage.PreviewAdapter {
        @Override public ImagePlus createSource(ConfigQcContext context) {
            return image();
        }

        @Override public ImagePlus createFilteredPreview(ImagePlus source,
                                                         String macroContent) {
            return source == null ? null : source.duplicate();
        }

        @Override public void close(ImagePlus image) {
        }
    }
}
