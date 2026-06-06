package flash.pipeline.representative;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.io.SeriesMeta;
import flash.pipeline.presentation.PresentationTileRecord;
import flash.pipeline.qc.QcSelectionCandidate;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RepresentativePreviewRendererTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void presentationManifestImagesAreDownscaledAndCached() throws Exception {
        File cacheDir = temp.newFolder("preview-cache");
        File dapi = temp.newFile("DAPI.png");
        File gfap = temp.newFile("GFAP.png");
        File merge = temp.newFile("Merge.png");
        writeSolidPng(dapi, 440, 120, Color.BLUE);
        writeSolidPng(gfap, 440, 120, Color.GREEN);
        writeSolidPng(merge, 440, 120, Color.CYAN);

        List<PresentationTileRecord> records = Arrays.asList(
                new PresentationTileRecord(dapi, "Mouse1", "LH", "SCN",
                        "series:1|Exp-Mouse1_LH_SCN", "DAPI", "DAPI",
                        0, 440, 120, 0.5, 0.5),
                new PresentationTileRecord(gfap, "Mouse1", "LH", "SCN",
                        "series:1|Exp-Mouse1_LH_SCN", "GFAP", "GFAP",
                        1, 440, 120, 0.5, 0.5),
                new PresentationTileRecord(merge, "Mouse1", "LH", "SCN",
                        "series:1|Exp-Mouse1_LH_SCN", "Merge", "Merge",
                        -1, 440, 120, 0.5, 0.5));

        BinConfig cfg = configuredTwoChannelBinConfig("None", "None");
        RepresentativeSeries first = RepresentativePreviewRenderer.renderPresentationSeriesForTests(
                cacheDir, meta(0, "Exp-Mouse1_LH_SCN", 440, 120, 1, 2),
                candidate(0, "Exp-Mouse1_LH_SCN", "Mouse1", "Control"),
                cfg, new RepresentativeFigureConfig(), records);

        assertEquals(RepresentativeSeries.PreviewSource.PRESENTATION, first.previewSource());
        assertFalse(first.cacheHit());
        assertEquals(2, first.channelThumbnails().size());
        assertEquals(220, first.mergeThumbnail().getWidth());
        assertTrue(first.mergeCacheFile().isFile());
        assertTrue(first.channelThumbnails().get(0).cacheFile().isFile());

        RepresentativeSeries second = RepresentativePreviewRenderer.renderPresentationSeriesForTests(
                cacheDir, meta(0, "Exp-Mouse1_LH_SCN", 440, 120, 1, 2),
                candidate(0, "Exp-Mouse1_LH_SCN", "Mouse1", "Control"),
                cfg, new RepresentativeFigureConfig(), records);

        assertEquals(RepresentativeSeries.PreviewSource.CACHE, second.previewSource());
        assertTrue(second.cacheHit());
        assertEquals(220, second.channelThumbnails().get(0).image().getWidth());
    }

    @Test
    public void generatedPreviewUsesConfiguredRangesAndPseudoColors() throws Exception {
        File cacheDir = temp.newFolder("generated-cache");
        BinConfig cfg = configuredTwoChannelBinConfig("0-100", "0-200");
        ImagePlus source = twoChannelImage(300, 100, 50, 200);

        RepresentativeSeries series = RepresentativePreviewRenderer.renderGeneratedSeriesForTests(
                cacheDir, meta(0, "Exp-Mouse1_LH_SCN", 300, 100, 1, 2),
                candidate(0, "Exp-Mouse1_LH_SCN", "Mouse1", "Control"),
                cfg, new RepresentativeFigureConfig(), source);

        assertEquals(RepresentativeSeries.PreviewSource.GENERATED, series.previewSource());
        assertEquals(2, series.channelThumbnails().size());
        assertEquals(220, series.mergeThumbnail().getWidth());

        int redPixel = centerRgb(series.channelThumbnails().get(0).image());
        assertTrue("red channel should contribute red", red(redPixel) > 120);
        assertEquals(0, green(redPixel));
        assertEquals(0, blue(redPixel));

        int greenPixel = centerRgb(series.channelThumbnails().get(1).image());
        assertEquals(0, red(greenPixel));
        assertTrue("green channel should contribute green", green(greenPixel) > 240);
        assertEquals(0, blue(greenPixel));

        int mergePixel = centerRgb(series.mergeThumbnail());
        assertTrue(red(mergePixel) > 120);
        assertTrue(green(mergePixel) > 240);
    }

    @Test
    public void generatedPreviewWithoutSetupFallsBackToAutoEnhance() throws Exception {
        File cacheDir = temp.newFolder("auto-cache");
        BinConfig cfg = new BinConfig();
        ImagePlus source = oneChannelGradientImage(260, 80);

        RepresentativeSeries first = RepresentativePreviewRenderer.renderGeneratedSeriesForTests(
                cacheDir, meta(0, "Exp-Mouse1_LH_SCN", 260, 80, 1, 1),
                candidate(0, "Exp-Mouse1_LH_SCN", "Mouse1", "Control"),
                cfg, new RepresentativeFigureConfig(), source);

        assertEquals(RepresentativeSeries.PreviewSource.GENERATED, first.previewSource());
        assertEquals(1, first.channelThumbnails().size());
        assertEquals(220, first.channelThumbnails().get(0).image().getWidth());
        assertTrue("auto-enhanced preview should contain dark and bright pixels",
                hasDarkAndBrightPixels(first.channelThumbnails().get(0).image()));

        RepresentativeSeries second = RepresentativePreviewRenderer.renderGeneratedSeriesForTests(
                cacheDir, meta(0, "Exp-Mouse1_LH_SCN", 260, 80, 1, 1),
                candidate(0, "Exp-Mouse1_LH_SCN", "Mouse1", "Control"),
                cfg, new RepresentativeFigureConfig(), source);

        assertEquals(RepresentativeSeries.PreviewSource.CACHE, second.previewSource());
        assertTrue(second.cacheHit());
    }

    private static BinConfig configuredTwoChannelBinConfig(String c1Range, String c2Range) {
        BinConfig cfg = new BinConfig();
        cfg.channelNames.add("DAPI");
        cfg.channelNames.add("GFAP");
        cfg.channelColors.add("Red");
        cfg.channelColors.add("Green");
        cfg.channelMinMax.add(c1Range);
        cfg.channelMinMax.add(c2Range);
        return cfg;
    }

    private static SeriesMeta meta(int index, String name, int width, int height,
                                   int slices, int channels) {
        return new SeriesMeta(index, name, width, height, slices, channels,
                0.5, 0.5, 1.0, "micron");
    }

    private static QcSelectionCandidate candidate(int index, String name,
                                                  String animal, String condition) {
        return new QcSelectionCandidate(index, name, animal, condition);
    }

    private static ImagePlus twoChannelImage(int width, int height, int c1, int c2) {
        ImageStack stack = new ImageStack(width, height);
        stack.addSlice("C1", constantProcessor(width, height, c1));
        stack.addSlice("C2", constantProcessor(width, height, c2));
        ImagePlus image = new ImagePlus("Exp-Mouse1_LH_SCN", stack);
        image.setDimensions(2, 1, 1);
        image.setOpenAsHyperStack(true);
        return image;
    }

    private static ImagePlus oneChannelGradientImage(int width, int height) {
        byte[] pixels = new byte[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pixels[y * width + x] = (byte) Math.min(255, x);
            }
        }
        ImageStack stack = new ImageStack(width, height);
        stack.addSlice("C1", new ByteProcessor(width, height, pixels));
        ImagePlus image = new ImagePlus("Exp-Mouse1_LH_SCN", stack);
        image.setDimensions(1, 1, 1);
        return image;
    }

    private static ByteProcessor constantProcessor(int width, int height, int value) {
        byte[] pixels = new byte[width * height];
        Arrays.fill(pixels, (byte) value);
        return new ByteProcessor(width, height, pixels);
    }

    private static void writeSolidPng(File file, int width, int height, Color color)
            throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(color);
            g.fillRect(0, 0, width, height);
        } finally {
            g.dispose();
        }
        assertTrue(ImageIO.write(image, "png", file));
    }

    private static int centerRgb(BufferedImage image) {
        return image.getRGB(image.getWidth() / 2, image.getHeight() / 2);
    }

    private static int red(int rgb) {
        return (rgb >> 16) & 0xff;
    }

    private static int green(int rgb) {
        return (rgb >> 8) & 0xff;
    }

    private static int blue(int rgb) {
        return rgb & 0xff;
    }

    private static boolean hasDarkAndBrightPixels(BufferedImage image) {
        boolean dark = false;
        boolean bright = false;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int grey = red(image.getRGB(x, y));
                if (grey < 20) dark = true;
                if (grey > 220) bright = true;
            }
        }
        return dark && bright;
    }
}
