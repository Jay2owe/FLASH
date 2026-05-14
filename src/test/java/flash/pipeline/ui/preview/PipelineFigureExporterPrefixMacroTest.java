package flash.pipeline.ui.preview;

import flash.pipeline.image.FilterMacroEditorModel;
import flash.pipeline.ui.config.ConfigQcContext;
import flash.pipeline.ui.config.FilterParameterStage;
import flash.pipeline.ui.variations.VariationCache;

import ij.ImagePlus;
import ij.process.ByteProcessor;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class PipelineFigureExporterPrefixMacroTest {

    @Test
    public void prefixMacrosCoverRawAndEveryStepWithoutMutatingFinalMacro()
            throws Exception {
        String selected = macroText();
        List<String> prefixes = PipelineFigureExporter.prefixMacros(selected);
        List<Integer> prefixLengths = new ArrayList<Integer>();
        for (int i = 0; i < prefixes.size(); i++) {
            prefixLengths.add(Integer.valueOf(countRuns(prefixes.get(i))));
        }
        assertEquals(Arrays.asList(Integer.valueOf(0),
                        Integer.valueOf(1),
                        Integer.valueOf(2),
                        Integer.valueOf(3)),
                prefixLengths);

        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        PipelineFigureExporter.render(selected,
                image(),
                adapter,
                new VariationCache((File) null));

        assertEquals(Arrays.asList(Integer.valueOf(1),
                        Integer.valueOf(2),
                        Integer.valueOf(3)),
                adapter.filteredPrefixLengths);
        assertEquals(macroText(), selected);
        assertEquals(3,
                countRuns(FilterMacroEditorModel.parse(selected).render()));
    }

    private static String macroText() {
        return "run(\"Gaussian Blur...\", \"sigma=1 stack\");\n"
                + "run(\"Subtract Background...\", \"rolling=25 stack\");\n"
                + "run(\"Median...\", \"radius=2 stack\");";
    }

    private static int countRuns(String macroContent) {
        int count = 0;
        String safe = macroContent == null ? "" : macroContent;
        int index = -1;
        while ((index = safe.indexOf("run(", index + 1)) >= 0) {
            count++;
        }
        return count;
    }

    private static ImagePlus image() {
        ByteProcessor processor = new ByteProcessor(8, 8);
        processor.set(7, 7, 255);
        processor.set(4, 4, 80);
        return new ImagePlus("source", processor);
    }

    private static final class RecordingPreviewAdapter
            implements FilterParameterStage.PreviewAdapter {
        final List<Integer> filteredPrefixLengths = new ArrayList<Integer>();

        @Override public ImagePlus createSource(ConfigQcContext context) {
            return image();
        }

        @Override public ImagePlus createFilteredPreview(ImagePlus source,
                                                         String macroContent) {
            filteredPrefixLengths.add(Integer.valueOf(countRuns(macroContent)));
            return image();
        }

        @Override public void close(ImagePlus image) {
            if (image != null) {
                image.flush();
            }
        }
    }
}
