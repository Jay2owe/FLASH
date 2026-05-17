package flash.pipeline.ui.variations;

import flash.pipeline.image.FilterMacroEditorModel;
import flash.pipeline.ui.config.ConfigQcContext;
import flash.pipeline.ui.config.FilterParameterStage;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import org.junit.Test;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ChainRibbonParameterFilterTest {

    @Test
    public void sweptStepFiltersEditorRowsToThatStepsNumericParameters() {
        ParameterSweepEditor editor = ParameterSweepEditor.forFilter(context());
        Set<Integer> selected = new LinkedHashSet<Integer>();
        selected.add(Integer.valueOf(2));

        editor.setSelectedChainStepIndexes(selected);

        List<ParameterKey> keys = editor.parameterKeysForTest();
        assertEquals(1, keys.size());
        assertTrue(keys.get(0) instanceof FilterParameterId);
        FilterParameterId id = (FilterParameterId) keys.get(0);
        assertEquals(2, id.entryIndex());
        assertEquals("radius", id.paramKey());
        assertEquals(1, editor.currentSweep().valueLists().size());
    }

    private static FilterVariationEngineContext context() {
        ImagePlus source = image();
        FilterMacroEditorModel.MacroDefinition macro =
                FilterMacroEditorModel.parse(macroText());
        ConfigQcContext config = ConfigQcContext.fromImages(new File("."),
                new File("target/chain-ribbon-parameter-filter-test-bin"),
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
