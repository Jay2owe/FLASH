package flash.pipeline.ui.preview;

import flash.pipeline.ui.config.ConfigQcContext;
import flash.pipeline.ui.config.FilterParameterStage;
import flash.pipeline.ui.variations.VariationCache;

import ij.ImagePlus;
import ij.process.ByteProcessor;

import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class PipelineFigureExporterTest {

    @Test
    public void renderCreatesReadablePipelineStripWithChangingTiles()
            throws Exception {
        ImagePlus raw = image("raw", 30);
        BufferedImage figure = PipelineFigureExporter.render(macroText(),
                raw,
                new SyntheticPreviewAdapter(),
                new VariationCache((File) null));

        assertTrue(figure.getWidth() > 3 * PipelineFigureExporter.THUMBNAIL_SIZE);
        assertEquals(PipelineFigureExporter.PADDING * 2
                        + PipelineFigureExporter.LABEL_HEIGHT
                        + PipelineFigureExporter.THUMBNAIL_SIZE,
                figure.getHeight());

        int rawCentre = centreGrey(figure, 0);
        int step1Centre = centreGrey(figure, 1);
        int step2Centre = centreGrey(figure, 2);
        int step3Centre = centreGrey(figure, 3);
        assertNotEquals(rawCentre, step1Centre);
        assertNotEquals(step1Centre, step2Centre);
        assertNotEquals(step2Centre, step3Centre);

        File out = new File("target/pipeline-figure-exporter-test/figure.png");
        PipelineFigureExporter.exportPNG(figure, out);
        BufferedImage roundTrip = ImageIO.read(out);
        assertNotNull(roundTrip);
        assertEquals(figure.getWidth(), roundTrip.getWidth());
        assertEquals(figure.getHeight(), roundTrip.getHeight());
    }

    private static int centreGrey(BufferedImage figure, int tileIndex) {
        int x = PipelineFigureExporter.PADDING
                + tileIndex * (PipelineFigureExporter.THUMBNAIL_SIZE
                + PipelineFigureExporter.GAP)
                + PipelineFigureExporter.THUMBNAIL_SIZE / 2;
        int y = PipelineFigureExporter.PADDING
                + PipelineFigureExporter.LABEL_HEIGHT
                + PipelineFigureExporter.THUMBNAIL_SIZE / 2;
        return figure.getRGB(x, y) & 0xff;
    }

    private static String macroText() {
        return "run(\"Gaussian Blur...\", \"sigma=1 stack\");\n"
                + "run(\"Subtract Background...\", \"rolling=25 stack\");\n"
                + "run(\"Median...\", \"radius=2 stack\");";
    }

    private static ImagePlus image(String title, int centreValue) {
        ByteProcessor processor = new ByteProcessor(16, 16);
        processor.set(15, 15, 255);
        for (int y = 6; y <= 9; y++) {
            for (int x = 6; x <= 9; x++) {
                processor.set(x, y, centreValue);
            }
        }
        return new ImagePlus(title, processor);
    }

    private static int countRuns(String macroContent) {
        int count = 0;
        int index = -1;
        while ((index = macroContent.indexOf("run(", index + 1)) >= 0) {
            count++;
        }
        return count;
    }

    private static final class SyntheticPreviewAdapter
            implements FilterParameterStage.PreviewAdapter {
        @Override public ImagePlus createSource(ConfigQcContext context) {
            return image("raw", 30);
        }

        @Override public ImagePlus createFilteredPreview(ImagePlus source,
                                                         String macroContent) {
            int steps = countRuns(macroContent);
            return image("prefix-" + steps, 30 + steps * 50);
        }

        @Override public void close(ImagePlus image) {
            if (image != null) {
                image.flush();
            }
        }
    }
}
