package flash.pipeline.representative;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.io.OrientationManifestIO;
import flash.pipeline.io.SeriesMeta;
import flash.pipeline.io.TifCache;
import flash.pipeline.naming.OrientationManifestRow;
import flash.pipeline.presentation.PresentationTileRecord;
import flash.pipeline.qc.QcSelectionCandidate;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
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
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
    public void presentationManifestMatchesSavedOrientationImageKey() throws Exception {
        File project = temp.newFolder("orientation-project");
        File cacheDir = temp.newFolder("orientation-cache");
        File source = new File(project, "source.lif");
        String originalName = "source.lif - Exp-Mouse1_LH_SCN";
        String sourceId = OrientationManifestRow.buildImageKey(
                "container", source.getName(), 1, originalName);
        OrientationManifestIO.saveRows(project.getAbsolutePath(),
                Collections.singletonList(orientationRow(sourceId, originalName)));

        File dapi = temp.newFile("Orientation_DAPI.png");
        File gfap = temp.newFile("Orientation_GFAP.png");
        File merge = temp.newFile("Orientation_Merge.png");
        writeSolidPng(dapi, 440, 120, Color.BLUE);
        writeSolidPng(gfap, 440, 120, Color.GREEN);
        writeSolidPng(merge, 440, 120, Color.CYAN);

        List<PresentationTileRecord> records = Arrays.asList(
                new PresentationTileRecord(dapi, "Mouse1", "LH", "SCN",
                        sourceId, "DAPI", "DAPI", 0, 440, 120, 0.5, 0.5),
                new PresentationTileRecord(gfap, "Mouse1", "LH", "SCN",
                        sourceId, "GFAP", "GFAP", 1, 440, 120, 0.5, 0.5),
                new PresentationTileRecord(merge, "Mouse1", "LH", "SCN",
                        sourceId, "Merge", "Merge", -1, 440, 120, 0.5, 0.5));

        RepresentativeSeries series = RepresentativePreviewRenderer.renderPresentationSeriesForTests(
                project.getAbsolutePath(), cacheDir,
                meta(0, "Exp-Mouse1_LH_SCN", 440, 120, 1, 2),
                candidate(0, "Exp-Mouse1_LH_SCN", "Mouse1", "Control"),
                configuredTwoChannelBinConfig("None", "None"),
                new RepresentativeFigureConfig(),
                records,
                source);

        assertEquals(RepresentativeSeries.PreviewSource.PRESENTATION, series.previewSource());
        assertFalse(series.cacheHit());
        assertEquals(2, series.channelThumbnails().size());
    }

    @Test
    public void existingPresentationPngsAreUsedWhenManifestIsMissing() throws Exception {
        File project = temp.newFolder("legacy-presentation-project");
        File cacheDir = temp.newFolder("legacy-presentation-cache");
        File animalDir = new File(project,
                "FLASH/Results/Presentation Images/Images/Mouse1");
        assertTrue(animalDir.mkdirs());
        writeSolidPng(new File(animalDir, "DAPI_LH_SCN.png"), 440, 120, Color.BLUE);
        writeSolidPng(new File(animalDir, "GFAP_LH_SCN.png"), 440, 120, Color.GREEN);
        writeSolidPng(new File(animalDir, "Merge_LH_SCN.png"), 440, 120, Color.CYAN);

        RepresentativeSeries series = RepresentativePreviewRenderer.renderPresentationSeriesForTests(
                project.getAbsolutePath(), cacheDir,
                meta(0, "Exp-Mouse1_LH_SCN", 440, 120, 1, 2),
                candidate(0, "Exp-Mouse1_LH_SCN", "Mouse1", "Control"),
                configuredTwoChannelBinConfig("None", "None"),
                new RepresentativeFigureConfig(),
                Collections.<PresentationTileRecord>emptyList());

        assertEquals(RepresentativeSeries.PreviewSource.PRESENTATION, series.previewSource());
        assertFalse(series.cacheHit());
        assertEquals(220, series.mergeThumbnail().getWidth());
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
    public void generatedPreviewAppliesSavedOrientationBeforeSelection() throws Exception {
        File project = temp.newFolder("generated-orientation-project");
        File cacheDir = temp.newFolder("generated-orientation-cache");
        File sourcePath = new File(project, "source.lif");
        saveOrientation(project, sourcePath, OrientationManifestRow.RotationDegrees.DEG_90);

        RepresentativeSeries series = RepresentativePreviewRenderer.renderGeneratedSeriesForTests(
                project.getAbsolutePath(), cacheDir,
                meta(0, "Exp-Mouse1_LH_SCN", 4, 2, 1, 1),
                candidate(0, "Exp-Mouse1_LH_SCN", "Mouse1", "Control"),
                configuredOneChannelBinConfig("0-255"),
                new RepresentativeFigureConfig(),
                oneChannelGradientImage(4, 2),
                sourcePath);

        assertEquals(RepresentativeSeries.PreviewSource.GENERATED, series.previewSource());
        assertEquals(1, series.channelThumbnails().size());
        assertEquals(2, series.channelThumbnails().get(0).image().getWidth());
        assertEquals(4, series.channelThumbnails().get(0).image().getHeight());
        assertEquals(2, series.mergeThumbnail().getWidth());
        assertEquals(4, series.mergeThumbnail().getHeight());
    }

    @Test
    public void generatedPreviewUsesRepresentativeCustomRangeBeforeSetupRange() throws Exception {
        File cacheDir = temp.newFolder("custom-range-cache");
        BinConfig cfg = configuredTwoChannelBinConfig("0-100", "0-200");
        RepresentativeFigureConfig config = new RepresentativeFigureConfig();
        config.setCustomDisplayRangeForChannel(0, "0-200");
        ImagePlus source = twoChannelImage(300, 100, 100, 200);

        RepresentativeSeries series = RepresentativePreviewRenderer.renderGeneratedSeriesForTests(
                cacheDir, meta(0, "Exp-Mouse1_LH_SCN", 300, 100, 1, 2),
                candidate(0, "Exp-Mouse1_LH_SCN", "Mouse1", "Control"),
                cfg, config, source);

        int redPixel = centerRgb(series.channelThumbnails().get(0).image());
        assertTrue("custom range should avoid setup saturation",
                red(redPixel) >= 120 && red(redPixel) <= 135);
        assertEquals(0, green(redPixel));
        assertEquals(0, blue(redPixel));
    }

    @Test
    public void finalRenderAppliesSavedOrientation() throws Exception {
        File project = temp.newFolder("final-orientation-project");
        File sourcePath = new File(project, "source.lif");
        saveOrientation(project, sourcePath, OrientationManifestRow.RotationDegrees.DEG_90);
        BinConfig cfg = configuredOneChannelBinConfig("0-255");
        RepresentativeFigureConfig config = new RepresentativeFigureConfig();
        config.setCustomDisplayRangeForChannel(0, "0-255");

        RepresentativePreviewRenderer.RenderedFinalSeries rendered =
                RepresentativePreviewRenderer.renderFinalSeriesForTests(
                        project.getAbsolutePath(),
                        cfg,
                        config,
                        oneChannelRepresentativeSeries(0, "Control", sourcePath),
                        oneChannelGradientImage(4, 2),
                        sourcePath);

        assertEquals(1, rendered.channels().size());
        assertEquals(2, rendered.channels().get(0).image().getWidth());
        assertEquals(4, rendered.channels().get(0).image().getHeight());
        assertEquals(2, rendered.mergeImage().getWidth());
        assertEquals(4, rendered.mergeImage().getHeight());
    }

    @Test
    public void finalRenderCachesMaterializedContainerOriginalForExport() throws Exception {
        File project = temp.newFolder("final-container-cache-project");
        File sourcePath = new File(project, "source.lif");
        BinConfig cfg = configuredOneChannelBinConfig("0-255");
        RepresentativeFigureConfig config = new RepresentativeFigureConfig();
        config.setCustomDisplayRangeForChannel(0, "0-255");

        RepresentativePreviewRenderer.RenderedFinalSeries rendered =
                RepresentativePreviewRenderer.renderFinalSeriesForTests(
                        project.getAbsolutePath(),
                        cfg,
                        config,
                        oneChannelRepresentativeSeries(0, "Control", sourcePath),
                        oneChannelGradientImage(4, 2),
                        sourcePath);

        File cached = TifCache.cachedFileForSeries(project.getAbsolutePath(), 0);
        assertTrue("materialized container source should be cached as a TIFF",
                cached != null && cached.isFile());
        assertEquals(sourcePath, rendered.sourcePath());
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

    @Test
    public void finalRenderUsesFullResolutionAndRepresentativeCustomRange() throws Exception {
        BinConfig cfg = configuredTwoChannelBinConfig("0-100", "0-200");
        RepresentativeFigureConfig config = new RepresentativeFigureConfig();
        config.setCustomDisplayRangeForChannel(0, "0-200");
        config.setCustomDisplayRangeForChannel(1, "0-200");
        ImagePlus source = twoChannelImage(300, 100, 100, 200);
        Calibration calibration = new Calibration();
        calibration.pixelWidth = 0.5;
        calibration.pixelHeight = 0.75;
        calibration.setUnit("micron");
        source.setCalibration(calibration);

        RepresentativePreviewRenderer.RenderedFinalSeries rendered =
                RepresentativePreviewRenderer.renderFinalSeriesForTests(
                        cfg, config, representativeSeries(0, "Control"), source);

        assertEquals(2, rendered.channels().size());
        assertEquals(300, rendered.channels().get(0).image().getWidth());
        assertEquals(100, rendered.channels().get(0).image().getHeight());
        assertEquals(0.5, rendered.pixelWidthUm(), 0.0001);
        assertEquals(0.75, rendered.pixelHeightUm(), 0.0001);

        int redPixel = centerRgb(rendered.channels().get(0).image());
        assertTrue("custom final range should avoid setup-range saturation",
                red(redPixel) >= 120 && red(redPixel) <= 135);
        assertEquals(0, green(redPixel));
        assertEquals(0, blue(redPixel));
    }

    @Test
    public void finalRenderRequiresLockedRangeInsteadOfAutoEnhance() throws Exception {
        BinConfig cfg = new BinConfig();
        cfg.channelNames.add("DAPI");
        cfg.channelColors.add("Red");

        try {
            RepresentativePreviewRenderer.renderFinalSeriesForTests(
                    cfg, new RepresentativeFigureConfig(),
                    representativeSeries(0, "Control"), oneChannelGradientImage(260, 80));
            fail("Final rendering should require custom or setup display ranges.");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("No locked display range"));
        }
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

    private static BinConfig configuredOneChannelBinConfig(String range) {
        BinConfig cfg = new BinConfig();
        cfg.channelNames.add("DAPI");
        cfg.channelColors.add("Red");
        cfg.channelMinMax.add(range);
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

    private static void saveOrientation(File project,
                                        File source,
                                        OrientationManifestRow.RotationDegrees rotation)
            throws Exception {
        String originalName = source.getName() + " - Exp-Mouse1_LH_SCN";
        String sourceId = OrientationManifestRow.buildImageKey(
                "container", source.getName(), 1, originalName);
        OrientationManifestIO.saveRows(project.getAbsolutePath(),
                Collections.singletonList(orientationRow(sourceId, originalName, rotation)));
    }

    private static OrientationManifestRow orientationRow(String imageKey,
                                                         String originalName) {
        return orientationRow(imageKey, originalName,
                OrientationManifestRow.RotationDegrees.DEG_0);
    }

    private static OrientationManifestRow orientationRow(
            String imageKey,
            String originalName,
            OrientationManifestRow.RotationDegrees rotation) {
        return new OrientationManifestRow(
                imageKey,
                "source.lif",
                1,
                originalName,
                originalName,
                "Mouse1",
                OrientationManifestRow.Hemisphere.LH,
                "SCN",
                rotation,
                false,
                false,
                OrientationManifestRow.ViewPolicy.MANUAL_ONLY,
                OrientationManifestRow.DecisionSource.MANUAL,
                OrientationManifestRow.ConfirmationState.YES,
                "");
    }

    private static RepresentativeSeries representativeSeries(int index, String condition) {
        return new RepresentativeSeries(
                RepresentativeStatTable.seriesIdForIndex(index),
                index,
                index + 1,
                "Exp-Mouse1_LH_SCN",
                "Mouse1",
                condition,
                "LH",
                "SCN",
                null,
                Arrays.asList(
                        new RepresentativeSeries.ChannelThumbnail(0, "DAPI", null, null),
                        new RepresentativeSeries.ChannelThumbnail(1, "GFAP", null, null)),
                null,
                null,
                RepresentativeSeries.PreviewSource.GENERATED,
                false);
    }

    private static RepresentativeSeries oneChannelRepresentativeSeries(
            int index,
            String condition,
            File sourcePath) {
        return new RepresentativeSeries(
                RepresentativeStatTable.seriesIdForIndex(index),
                index,
                index + 1,
                "Exp-Mouse1_LH_SCN",
                "Mouse1",
                condition,
                "LH",
                "SCN",
                sourcePath,
                Collections.singletonList(
                        new RepresentativeSeries.ChannelThumbnail(0, "DAPI", null, null)),
                null,
                null,
                RepresentativeSeries.PreviewSource.GENERATED,
                false);
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
