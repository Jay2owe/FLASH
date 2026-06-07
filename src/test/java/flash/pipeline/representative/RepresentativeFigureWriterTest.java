package flash.pipeline.representative;

import flash.pipeline.presentation.PresentationTileConfig;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RepresentativeFigureWriterTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void writesPngToRepresentativeFiguresDirUsingChosenLayoutAndOrder() throws Exception {
        File project = temp.newFolder("project");
        RepresentativeFigureConfig config = configFor(
                Arrays.asList("Control", "Treatment"),
                PresentationTileConfig.builder()
                        .createOverviewTile(true)
                        .annotateOverviewTile(false)
                        .scaleBarEnabled(false)
                        .labelMode(PresentationTileConfig.LabelMode.NONE)
                        .channelOrder(Arrays.asList("Merge", "DAPI"))
                        .cellSizePx(50)
                        .build());

        File output = RepresentativeFigureWriter.writeRenderedFigureForTests(
                project.getAbsolutePath(),
                config,
                Arrays.asList(
                        renderedSeries(0, "Control", Color.RED, Color.CYAN),
                        renderedSeries(1, "Treatment", Color.GREEN, Color.YELLOW)));

        assertTrue(output.isFile());
        assertEquals(new File(project,
                        "FLASH/Results/Presentation Images/Representative Figures").getAbsolutePath(),
                output.getParentFile().getAbsolutePath());
        assertTrue(output.getName().startsWith(
                "Representative_Figure_Control_Treatment"));

        BufferedImage figure = ImageIO.read(output);
        int cyanX = minDominantX(figure, Color.CYAN);
        int redX = minDominantX(figure, Color.RED);
        assertTrue("requested Merge column should be drawn before DAPI",
                cyanX >= 0 && redX >= 0 && cyanX < redX);
    }

    @Test
    public void renderFigureImageDrawsConfiguredScaleBar() {
        RepresentativeFigureConfig config = configFor(
                Collections.singletonList("Control"),
                PresentationTileConfig.builder()
                        .createOverviewTile(true)
                        .annotateOverviewTile(true)
                        .scaleBarEnabled(true)
                        .scaleBarLengthUm(20.0)
                        .scaleBarThicknessPx(5)
                        .scaleBarPosition(PresentationTileConfig.Position.BOTTOM_RIGHT)
                        .labelMode(PresentationTileConfig.LabelMode.NONE)
                        .channelOrder(Collections.singletonList("DAPI"))
                        .cellSizePx(80)
                        .annotationColor(Color.WHITE)
                        .build());

        BufferedImage figure = RepresentativeFigureWriter.renderFigureImage(
                config,
                Collections.singletonList(renderedSeries(0, "Control", Color.BLACK, Color.BLACK)));

        assertTrue("scale bar should draw in white inside the black tile",
                containsBrightPixel(figure,
                        figure.getWidth() - 40,
                        figure.getHeight() - 24,
                        figure.getWidth() - 8,
                        figure.getHeight() - 8));
    }

    @Test
    public void writesIndividualImagesAndOriginalTifPerCondition() throws Exception {
        File project = temp.newFolder("project-with-individuals");
        File source = temp.newFile("Exp-Mouse1_LH_SCN.tif");
        byte[] tifBytes = new byte[]{1, 2, 3, 4, 5};
        Files.write(source.toPath(), tifBytes);
        RepresentativeFigureConfig config = configFor(
                Collections.singletonList("Control"),
                PresentationTileConfig.builder()
                        .createOverviewTile(true)
                        .annotateOverviewTile(false)
                        .scaleBarEnabled(false)
                        .labelMode(PresentationTileConfig.LabelMode.NONE)
                        .channelOrder(Arrays.asList("DAPI", "Merge"))
                        .cellSizePx(50)
                        .build());

        RepresentativeFigureWriter.writeRenderedFigureForTests(
                project.getAbsolutePath(),
                config,
                Collections.singletonList(
                        renderedSeries(0, "Control", Color.RED, Color.CYAN, source)));

        File conditionDir = new File(project,
                "FLASH/Results/Presentation Images/Representative Figures/"
                        + "Individual Images/Control");
        File channel = new File(conditionDir, "C1_DAPI.png");
        File merge = new File(conditionDir, "Merge.png");
        File original = new File(conditionDir, "Original_Exp-Mouse1_LH_SCN.tif");

        assertTrue(channel.isFile());
        assertTrue(merge.isFile());
        assertTrue(original.isFile());
        assertArrayEquals(tifBytes, Files.readAllBytes(original.toPath()));
    }

    @Test
    public void writesOriginalTifFromSeriesCacheWhenSourceIsContainer() throws Exception {
        File project = temp.newFolder("project-with-cache");
        File cacheDir = new File(project, "FLASH/Cache/TIF");
        assertTrue(cacheDir.mkdirs());
        File cachedTif = new File(cacheDir, "0000_Exp-Mouse1_LH_SCN.tif");
        byte[] tifBytes = new byte[]{9, 8, 7, 6};
        Files.write(cachedTif.toPath(), tifBytes);
        File container = temp.newFile("source.lif");
        RepresentativeFigureConfig config = configFor(
                Collections.singletonList("Control"),
                PresentationTileConfig.builder()
                        .createOverviewTile(true)
                        .annotateOverviewTile(false)
                        .scaleBarEnabled(false)
                        .labelMode(PresentationTileConfig.LabelMode.NONE)
                        .channelOrder(Arrays.asList("DAPI", "Merge"))
                        .cellSizePx(50)
                        .build());

        RepresentativeFigureWriter.writeRenderedFigureForTests(
                project.getAbsolutePath(),
                config,
                Collections.singletonList(
                        renderedSeries(0, "Control", Color.RED, Color.CYAN, container)));

        File original = new File(project,
                "FLASH/Results/Presentation Images/Representative Figures/"
                        + "Individual Images/Control/Original_Exp-Mouse1_LH_SCN.tif");

        assertTrue(original.isFile());
        assertArrayEquals(tifBytes, Files.readAllBytes(original.toPath()));
    }

    @Test
    public void prefersDirectSourceTifOverSeriesCacheForOriginalCopy() throws Exception {
        File project = temp.newFolder("project-with-direct-source-and-cache");
        File source = temp.newFile("Exp-Mouse1_LH_SCN.tif");
        byte[] sourceBytes = new byte[]{1, 2, 3, 4};
        Files.write(source.toPath(), sourceBytes);
        File cacheDir = new File(project, "FLASH/Cache/TIF");
        assertTrue(cacheDir.mkdirs());
        File cachedTif = new File(cacheDir, "0000_stale.tif");
        Files.write(cachedTif.toPath(), new byte[]{9, 9, 9, 9});
        RepresentativeFigureConfig config = configFor(
                Collections.singletonList("Control"),
                PresentationTileConfig.builder()
                        .createOverviewTile(true)
                        .annotateOverviewTile(false)
                        .scaleBarEnabled(false)
                        .labelMode(PresentationTileConfig.LabelMode.NONE)
                        .channelOrder(Arrays.asList("DAPI", "Merge"))
                        .cellSizePx(50)
                        .build());

        RepresentativeFigureWriter.writeRenderedFigureForTests(
                project.getAbsolutePath(),
                config,
                Collections.singletonList(
                        renderedSeries(0, "Control", Color.RED, Color.CYAN, source)));

        File original = new File(project,
                "FLASH/Results/Presentation Images/Representative Figures/"
                        + "Individual Images/Control/Original_Exp-Mouse1_LH_SCN.tif");

        assertTrue(original.isFile());
        assertArrayEquals(sourceBytes, Files.readAllBytes(original.toPath()));
    }

    @Test
    public void clearsStaleIndividualImagesBeforeWritingCurrentRun() throws Exception {
        File project = temp.newFolder("project-with-repeated-individuals");
        PresentationTileConfig tileConfig = PresentationTileConfig.builder()
                .createOverviewTile(true)
                .annotateOverviewTile(false)
                .scaleBarEnabled(false)
                .labelMode(PresentationTileConfig.LabelMode.NONE)
                .channelOrder(Arrays.asList("DAPI", "Merge"))
                .cellSizePx(50)
                .build();

        RepresentativeFigureWriter.writeRenderedFigureForTests(
                project.getAbsolutePath(),
                configFor(Collections.singletonList("Control"), tileConfig),
                Collections.singletonList(
                        renderedSeries(0, "Control", Color.RED, Color.CYAN)));
        File individualRoot = new File(project,
                "FLASH/Results/Presentation Images/Representative Figures/"
                        + "Individual Images");
        assertTrue(new File(individualRoot, "Control").isDirectory());

        RepresentativeFigureWriter.writeRenderedFigureForTests(
                project.getAbsolutePath(),
                configFor(Collections.singletonList("Treatment"), tileConfig),
                Collections.singletonList(
                        renderedSeries(0, "Treatment", Color.GREEN, Color.YELLOW)));

        assertFalse("old condition folder should be removed before the next run",
                new File(individualRoot, "Control").exists());
        assertTrue(new File(individualRoot, "Treatment").isDirectory());
    }

    private static RepresentativeFigureConfig configFor(Iterable<String> conditions,
                                                        PresentationTileConfig tileConfig) {
        RepresentativeFigureConfig config = new RepresentativeFigureConfig();
        Map<String, RepresentativeSeries> selected =
                new LinkedHashMap<String, RepresentativeSeries>();
        java.util.List<String> ordered = new java.util.ArrayList<String>();
        int index = 0;
        for (String condition : conditions) {
            ordered.add(condition);
            selected.put(condition, representativeSeries(index, condition));
            index++;
        }
        config.selection = new RepresentativeSelection(ordered, selected);
        config.layout = RepresentativeLayout.allInOneRow(ordered);
        config.tileConfig = tileConfig;
        return config;
    }

    private static RepresentativeSeries representativeSeries(int index, String condition) {
        return new RepresentativeSeries(
                RepresentativeStatTable.seriesIdForIndex(index),
                index,
                index + 1,
                "Exp-Mouse" + (index + 1) + "_LH_SCN",
                "Mouse" + (index + 1),
                condition,
                "LH",
                "SCN",
                null,
                Collections.singletonList(
                        new RepresentativeSeries.ChannelThumbnail(0, "DAPI", null, null)),
                null,
                null,
                RepresentativeSeries.PreviewSource.GENERATED,
                false);
    }

    private static RepresentativePreviewRenderer.RenderedFinalSeries renderedSeries(
            int index,
            String condition,
            Color dapi,
            Color merge) {
        return renderedSeries(index, condition, dapi, merge, null);
    }

    private static RepresentativePreviewRenderer.RenderedFinalSeries renderedSeries(
            int index,
            String condition,
            Color dapi,
            Color merge,
            File sourcePath) {
        return new RepresentativePreviewRenderer.RenderedFinalSeries(
                RepresentativeStatTable.seriesIdForIndex(index),
                index,
                index + 1,
                "Exp-Mouse" + (index + 1) + "_LH_SCN",
                "Mouse" + (index + 1),
                condition,
                "LH",
                "SCN",
                sourcePath,
                Collections.singletonList(
                        new RepresentativePreviewRenderer.RenderedFinalChannel(
                                0, "DAPI", "Red", solid(dapi))),
                solid(merge),
                1.0,
                1.0);
    }

    private static BufferedImage solid(Color color) {
        BufferedImage image = new BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB);
        int rgb = color.getRGB();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, rgb);
            }
        }
        return image;
    }

    private static int minDominantX(BufferedImage image, Color color) {
        int min = Integer.MAX_VALUE;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (isDominantColor(image.getRGB(x, y), color)) {
                    min = Math.min(min, x);
                }
            }
        }
        return min == Integer.MAX_VALUE ? -1 : min;
    }

    private static boolean containsBrightPixel(BufferedImage image,
                                               int x0,
                                               int y0,
                                               int x1,
                                               int y1) {
        int minX = Math.max(0, x0);
        int maxX = Math.min(image.getWidth() - 1, x1);
        int minY = Math.max(0, y0);
        int maxY = Math.min(image.getHeight() - 1, y1);
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                Color c = new Color(image.getRGB(x, y), true);
                if (c.getRed() > 220 && c.getGreen() > 220 && c.getBlue() > 220) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isDominantColor(int rgb, Color color) {
        Color c = new Color(rgb, true);
        if (Color.CYAN.equals(color)) {
            return c.getGreen() > 180 && c.getBlue() > 180 && c.getRed() < 80;
        }
        if (Color.RED.equals(color)) {
            return c.getRed() > 180 && c.getGreen() < 80 && c.getBlue() < 80;
        }
        return Math.abs(c.getRed() - color.getRed()) < 20
                && Math.abs(c.getGreen() - color.getGreen()) < 20
                && Math.abs(c.getBlue() - color.getBlue()) < 20;
    }
}
