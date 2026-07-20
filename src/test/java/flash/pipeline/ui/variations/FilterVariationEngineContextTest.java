package flash.pipeline.ui.variations;

import flash.pipeline.image.FilterMacroEditorModel;
import flash.pipeline.ui.config.ConfigQcContext;
import flash.pipeline.ui.config.FilterParameterStage;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import org.junit.Test;

import java.io.File;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class FilterVariationEngineContextTest {

    @Test
    public void accessorsExposeFilterInputsAndStableImageHash() {
        ImagePlus source = image("source");
        FilterMacroEditorModel.MacroDefinition macro =
                FilterMacroEditorModel.parse(macroText());
        File bin = new File("target/filter-variation-context-test-bin");
        ConfigQcContext config = ConfigQcContext.fromImages(new File("."), bin,
                null, Collections.singletonList(source),
                Collections.singletonList("DAPI"), 0);
        StubPreviewAdapter adapter = new StubPreviewAdapter();

        FilterVariationEngineContext context =
                new FilterVariationEngineContext(macro, source,
                        CropSpec.centre256(), "DAPI", config, adapter);

        assertSame(macro, context.baseMacro());
        assertSame(source, context.sourceImage());
        assertEquals(CropSpec.Mode.CENTRE_256, context.initialCropSpec().mode());
        assertEquals("DAPI", context.channelName());
        assertSame(config, context.configContext());
        assertEquals(bin, context.binFolder());
        assertSame(adapter, context.previewAdapter());
        assertEquals(FilterVariationEngineContext.sourceImageHash(source),
                context.sourceImageHash());
        assertEquals(64, context.sourceImageHash().length());
        assertTrue(context.cacheNamespace().startsWith("filter:"));
    }

    private static String macroText() {
        return "run(\"Gaussian Blur...\", \"sigma=2 stack\");";
    }

    private static ImagePlus image(String title) {
        ByteProcessor processor = new ByteProcessor(8, 8);
        processor.setValue(7);
        processor.fill();
        return new ImagePlus(title, processor);
    }

    private static final class StubPreviewAdapter
            implements FilterParameterStage.PreviewAdapter {
        @Override public ImagePlus createSource(ConfigQcContext context) {
            return image("source");
        }

        @Override public ImagePlus createFilteredPreview(ImagePlus source,
                                                         String macroContent) {
            return source == null ? null : source.duplicate();
        }

        @Override public void close(ImagePlus image) {
        }
    }
}
