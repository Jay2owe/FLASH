package flash.pipeline.presentation;

import flash.pipeline.io.CsvSupport;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.w3c.dom.Node;

import static org.junit.Assert.*;

public class PresentationTileWriterTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void overviewTileUsesRequestedColumnOrder() throws Exception {
        File root = temp.newFolder("presentation");
        File images = new File(root, "Images/Animal1");
        assertTrue(images.mkdirs());

        File dapi = new File(images, "DAPI_LH_Cortex.png");
        File gfap = new File(images, "GFAP_LH_Cortex.png");
        writeSolid(dapi, Color.RED);
        writeSolid(gfap, Color.GREEN);

        List<PresentationTileRecord> records = Arrays.asList(
                record(dapi, "Animal1", "DAPI", 0),
                record(gfap, "Animal1", "GFAP", 1));
        Map<String, String> conditions = new LinkedHashMap<String, String>();
        conditions.put("Animal1", "Control");

        PresentationTileConfig config = PresentationTileConfig.builder()
                .createOverviewTile(true)
                .annotateOverviewTile(false)
                .scaleBarEnabled(false)
                .labelMode(PresentationTileConfig.LabelMode.NONE)
                .groupRowsBy(PresentationTileConfig.GroupRowsBy.CONDITION)
                .channelOrder(Arrays.asList("GFAP", "DAPI"))
                .cellSizePx(60)
                .build();

        File out = new File(root, "Tiles/overview.png");
        PresentationTileWriter.writeOverviewTile(out, records, conditions, config);

        assertTrue(out.isFile());
        BufferedImage tile = ImageIO.read(out);
        int greenX = minDominantX(tile, Color.GREEN);
        int redX = minDominantX(tile, Color.RED);
        assertTrue("first requested column should be GFAP/green",
                greenX >= 0 && redX >= 0 && greenX < redX);
        assertTrue("overview tile should use tight horizontal layout", tile.getWidth() < 300);
        assertTrue("overview tile should use tight vertical layout", tile.getHeight() < 150);
        assertFalse("column headers should not draw a filled text background",
                containsExactColor(tile, new Color(238, 241, 243)));
        assertFalse("group labels should not draw a filled text background",
                containsExactColor(tile, new Color(225, 230, 233)));
    }

    @Test
    public void duplicateLabelRecordsWithDifferentImageIdsSurviveManifestAndOverview() throws Exception {
        File root = temp.newFolder("duplicate-labels");
        File images = new File(root, "Images/Animal1");
        assertTrue(images.mkdirs());

        File first = new File(images, "DAPI_first.png");
        File second = new File(images, "DAPI_second.png");
        writeSolid(first, Color.RED);
        writeSolid(second, Color.GREEN);

        PresentationTileRecord firstRecord = new PresentationTileRecord(
                first, "Animal1", "LH", "Cortex", "source-series-001",
                "DAPI", "DAPI", 0, 40, 40, 1.0, 1.0);
        PresentationTileRecord secondRecord = new PresentationTileRecord(
                second, "Animal1", "LH", "Cortex", "source-series-002",
                "DAPI", "DAPI", 0, 40, 40, 1.0, 1.0);
        assertNotEquals(firstRecord.imageKey(), secondRecord.imageKey());
        assertEquals(firstRecord.imageLabel(), secondRecord.imageLabel());

        List<PresentationTileRecord> records = Arrays.asList(firstRecord, secondRecord);
        Map<String, String> conditions = new LinkedHashMap<String, String>();
        conditions.put("Animal1", "Control");

        File manifest = new File(root, "Presentation_Image_Manifest.csv");
        PresentationTileWriter.writeManifest(manifest, records, conditions);
        List<PresentationTileRecord> readBack = PresentationTileWriter.readManifest(manifest);
        assertEquals(2, readBack.size());
        assertEquals("source-series-001", readBack.get(0).imageId());
        assertEquals("source-series-002", readBack.get(1).imageId());

        PresentationTileConfig config = PresentationTileConfig.builder()
                .createOverviewTile(true)
                .annotateOverviewTile(false)
                .scaleBarEnabled(false)
                .labelMode(PresentationTileConfig.LabelMode.NONE)
                .cellSizePx(80)
                .build();

        File out = new File(root, "Tiles/overview.png");
        PresentationTileWriter.writeOverviewTile(out, records, conditions, config);

        BufferedImage tile = ImageIO.read(out);
        int redY = minDominantY(tile, Color.RED);
        int greenY = minDominantY(tile, Color.GREEN);
        assertTrue("first row should use the first source image",
                redY >= 0 && greenY >= 0 && redY < greenY);
        assertTrue("second row should use the second source image",
                greenY > redY);
        assertTrue("overview tile should be tighter than the old fixed layout",
                tile.getHeight() < 282);
    }

    @Test
    public void readManifestAcceptsRowsWithoutSourceImageId() throws Exception {
        File root = temp.newFolder("legacy-manifest");
        File image = new File(root, "DAPI_LH_Cortex.png");
        writeSolid(image, Color.BLUE);

        File manifest = new File(root, "Presentation_Image_Manifest.csv");
        PrintWriter pw = CsvSupport.newWriter(manifest);
        try {
            pw.println(CsvSupport.joinRow(Arrays.asList(
                    "Animal", "Condition", "Hemisphere", "Region",
                    "OutputName", "StainName", "ChannelIndex",
                    "ImagePath", "AnnotatedImagePath",
                    "WidthPx", "HeightPx", "PixelWidthUm", "PixelHeightUm")));
            pw.println(CsvSupport.joinRow(Arrays.asList(
                    "Animal1", "Control", "LH", "Cortex",
                    "DAPI", "DAPI", "0",
                    image.getAbsolutePath(), "",
                    "40", "40", "1.0", "1.0")));
        } finally {
            pw.close();
        }

        List<PresentationTileRecord> records = PresentationTileWriter.readManifest(manifest);

        assertEquals(1, records.size());
        assertEquals("", records.get(0).imageId());
        assertEquals("Animal1|LH|Cortex", records.get(0).imageKey());
    }

    @Test
    public void individualAnnotationsWriteStainLabelAndScaleBarCopies() throws Exception {
        File root = temp.newFolder("annotated");
        File images = new File(root, "Images/Animal1");
        assertTrue(images.mkdirs());

        File source = new File(images, "DAPI_LH_Cortex.png");
        writeSolid(source, Color.BLACK, 100, 50);
        PresentationTileRecord record = new PresentationTileRecord(
                source, "Animal1", "LH", "Cortex", "DAPI", "DAPI",
                0, 100, 50, 1.0, 1.0);

        PresentationTileConfig config = PresentationTileConfig.builder()
                .createOverviewTile(false)
                .annotateIndividualImages(true)
                .labelMode(PresentationTileConfig.LabelMode.STAIN_NAME)
                .labelFontSizePx(18)
                .labelPosition(PresentationTileConfig.Position.TOP_LEFT)
                .scaleBarEnabled(true)
                .scaleBarLengthUm(20.0)
                .scaleBarThicknessPx(6)
                .scaleBarPosition(PresentationTileConfig.Position.BOTTOM_RIGHT)
                .build();

        File annotatedDir = new File(root, "Annotated Images");
        File tilesDir = new File(root, "Tiles");
        File manifest = new File(root, "Presentation_Image_Manifest.csv");
        PresentationTileWriter.writeRequestedOutputs(annotatedDir, tilesDir, manifest,
                Arrays.asList(record), new LinkedHashMap<String, String>(), config);

        File annotated = new File(annotatedDir, "Animal1/DAPI_LH_Cortex.png");
        assertTrue(annotated.isFile());
        BufferedImage image = ImageIO.read(annotated);
        assertTrue("scale bar should draw in white",
                containsBrightPixel(image, 65, 30, 99, 49));
        assertNotNull("record should point to annotated derivative", record.annotatedImageFile());
        assertTrue(new File(tilesDir, "Presentation_Overview_ByAnimal.png").isFile());
    }

    @Test
    public void individualAnnotationsDrawTextWithoutBackgroundBox() throws Exception {
        File root = temp.newFolder("transparent-label");
        File images = new File(root, "Images/Animal1");
        assertTrue(images.mkdirs());

        File source = new File(images, "DAPI_LH_Cortex.png");
        writeSolid(source, Color.YELLOW, 180, 80);
        PresentationTileRecord record = new PresentationTileRecord(
                source, "Animal1", "LH", "Cortex", "DAPI", "DAPI",
                0, 180, 80, 1.0, 1.0);

        PresentationTileConfig config = PresentationTileConfig.builder()
                .createOverviewTile(false)
                .annotateIndividualImages(true)
                .labelMode(PresentationTileConfig.LabelMode.STAIN_NAME)
                .labelFontSizePx(24)
                .labelPosition(PresentationTileConfig.Position.TOP_LEFT)
                .scaleBarEnabled(false)
                .annotationColor(Color.WHITE)
                .build();

        File annotatedDir = new File(root, "Annotated Images");
        File tilesDir = new File(root, "Tiles");
        File manifest = new File(root, "Presentation_Image_Manifest.csv");
        PresentationTileWriter.writeRequestedOutputs(annotatedDir, tilesDir, manifest,
                Arrays.asList(record), new LinkedHashMap<String, String>(), config);

        BufferedImage image = ImageIO.read(new File(annotatedDir, "Animal1/DAPI_LH_Cortex.png"));
        assertFalse("annotation labels should not draw a dark backing box",
                containsDarkPixel(image, 0, 0, 120, 50));
    }

    @Test
    public void manifestRewriteReplacesExistingFile() throws Exception {
        File root = temp.newFolder("manifest-rewrite");
        File image = new File(root, "DAPI.png");
        writeSolid(image, Color.BLUE);
        File manifest = new File(root, "Presentation_Image_Manifest.csv");

        PresentationTileWriter.writeManifest(manifest,
                Arrays.asList(record(image, "Animal1", "DAPI", 0)),
                new LinkedHashMap<String, String>());
        PresentationTileWriter.writeManifest(manifest,
                Arrays.asList(record(image, "Animal2", "DAPI", 0)),
                new LinkedHashMap<String, String>());

        List<PresentationTileRecord> readBack = PresentationTileWriter.readManifest(manifest);
        assertEquals(1, readBack.size());
        assertEquals("Animal2", readBack.get(0).animal());
    }

    @Test
    public void configForcesTileAnnotationsWhenIndividualAnnotationsAreEnabled() {
        PresentationTileConfig config = PresentationTileConfig.builder()
                .createOverviewTile(false)
                .annotateOverviewTile(false)
                .annotateIndividualImages(true)
                .build();

        assertTrue(config.createOverviewTile());
        assertTrue(config.annotateOverviewTile());
    }

    @Test
    public void annotationPreviewUsesRepresentativeImageDimensions() {
        PresentationTileRecord representative = new PresentationTileRecord(
                null, "Animal1", "LH", "Cortex", "DAPI", "DAPI",
                0, 640, 320, 0.5, 0.5);
        PresentationTileConfig config = PresentationTileConfig.builder()
                .scaleBarEnabled(true)
                .labelMode(PresentationTileConfig.LabelMode.STAIN_NAME)
                .build();

        BufferedImage preview = PresentationTileWriter.renderAnnotationPreview(config, representative);

        assertEquals(640, preview.getWidth());
        assertEquals(320, preview.getHeight());
    }

    @Test
    public void annotationPreviewCapsLargeImagesAndPreservesAspectRatio() {
        PresentationTileRecord representative = new PresentationTileRecord(
                null, "Animal1", "LH", "Cortex", "DAPI", "DAPI",
                0, 4000, 2000, 0.5, 0.5);
        PresentationTileConfig config = PresentationTileConfig.builder()
                .scaleBarEnabled(true)
                .labelMode(PresentationTileConfig.LabelMode.STAIN_NAME)
                .build();

        BufferedImage preview = PresentationTileWriter.renderAnnotationPreview(config, representative);

        assertEquals(640, preview.getWidth());
        assertEquals(320, preview.getHeight());
    }

    @Test
    public void annotationPreviewScalesLabelTextForLargeRepresentativeImages() {
        PresentationTileRecord representative = new PresentationTileRecord(
                null, "Animal1", "LH", "Cortex", "DAPI", "DAPI",
                0, 4000, 2000, 0.5, 0.5);
        PresentationTileConfig config = PresentationTileConfig.builder()
                .labelMode(PresentationTileConfig.LabelMode.STAIN_NAME)
                .labelFontSizePx(40)
                .scaleBarEnabled(false)
                .build();

        BufferedImage preview = PresentationTileWriter.renderAnnotationPreview(config, representative);

        assertTrue("large-image preview should not draw full-size label text",
                maxBrightVerticalRun(preview, 0, 120, 0, 80) <= 12);
    }

    @Test
    public void annotationPreviewScalesScaleBarThicknessForLargeRepresentativeImages() {
        PresentationTileRecord representative = new PresentationTileRecord(
                null, "Animal1", "LH", "Cortex", "DAPI", "DAPI",
                0, 4000, 2000, 1.0, 1.0);
        PresentationTileConfig config = PresentationTileConfig.builder()
                .labelMode(PresentationTileConfig.LabelMode.NONE)
                .labelFontSizePx(40)
                .scaleBarEnabled(true)
                .scaleBarLengthUm(1000.0)
                .scaleBarThicknessPx(20)
                .scaleBarPosition(PresentationTileConfig.Position.BOTTOM_RIGHT)
                .build();

        BufferedImage preview = PresentationTileWriter.renderAnnotationPreview(config, representative);
        int run = maxBrightVerticalRun(preview,
                preview.getWidth() - 80, preview.getWidth() - 4,
                preview.getHeight() - 80, preview.getHeight() - 1);

        assertTrue("scaled preview should still show a scale bar", run >= 1);
        assertTrue("large-image preview should not draw full-size scale-bar thickness", run <= 6);
    }

    @Test
    public void fractionalScaleBarPlacementCanReachImageEdge() {
        BufferedImage image = new BufferedImage(120, 80, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            PresentationTileWriter.applyQualityHints(g);
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            PresentationTileRecord record = new PresentationTileRecord(
                    null, "Animal1", "LH", "Cortex", "DAPI", "DAPI",
                    0, 120, 80, 1.0, 1.0);
            PresentationTileConfig config = PresentationTileConfig.builder()
                    .labelMode(PresentationTileConfig.LabelMode.NONE)
                    .scaleBarEnabled(true)
                    .scaleBarLengthUm(20.0)
                    .scaleBarThicknessPx(4)
                    .scaleBarFracX(1.0)
                    .scaleBarFracY(1.0)
                    .annotationColor(Color.WHITE)
                    .build();

            PresentationTileWriter.drawAnnotations(g, record,
                    new LinkedHashMap<String, String>(), config,
                    new java.awt.Rectangle(0, 0, image.getWidth(), image.getHeight()), 1.0);
        } finally {
            g.dispose();
        }

        assertTrue("manual bottom-right placement should reach the image edge",
                containsBrightPixel(image, 118, 78, 119, 79));
    }

    @Test
    public void writePngAtomicallyEmbedsRequestedDpi() throws Exception {
        File output = temp.newFile("dpi.png");
        BufferedImage image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);

        PresentationTileWriter.writePngAtomically(image, output, 600);

        assertEquals(600, pngDpi(output));
    }

    private static PresentationTileRecord record(File file, String animal, String stain, int channelIndex) {
        return new PresentationTileRecord(file, animal, "LH", "Cortex", stain, stain,
                channelIndex, 40, 40, 1.0, 1.0);
    }

    private static void writeSolid(File file, Color color) throws Exception {
        writeSolid(file, color, 40, 40);
    }

    private static void writeSolid(File file, Color color, int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, color.getRGB());
            }
        }
        ImageIO.write(image, "png", file);
    }

    private static boolean containsBrightPixel(BufferedImage image,
                                               int x0, int y0, int x1, int y1) {
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                Color c = new Color(image.getRGB(x, y), true);
                if (c.getRed() > 200 && c.getGreen() > 200 && c.getBlue() > 200) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsDarkPixel(BufferedImage image,
                                             int x0, int y0, int x1, int y1) {
        for (int y = Math.max(0, y0); y <= Math.min(image.getHeight() - 1, y1); y++) {
            for (int x = Math.max(0, x0); x <= Math.min(image.getWidth() - 1, x1); x++) {
                Color c = new Color(image.getRGB(x, y), true);
                double luminance = (0.2126 * c.getRed())
                        + (0.7152 * c.getGreen())
                        + (0.0722 * c.getBlue());
                if (c.getAlpha() > 0 && luminance < 140.0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsExactColor(BufferedImage image, Color color) {
        int expected = color.getRGB();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (image.getRGB(x, y) == expected) return true;
            }
        }
        return false;
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

    private static int minDominantY(BufferedImage image, Color color) {
        int min = Integer.MAX_VALUE;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (isDominantColor(image.getRGB(x, y), color)) {
                    min = Math.min(min, y);
                }
            }
        }
        return min == Integer.MAX_VALUE ? -1 : min;
    }

    private static boolean isDominantColor(int rgb, Color color) {
        Color c = new Color(rgb, true);
        if (c.getAlpha() == 0) return false;
        if (color.equals(Color.RED)) {
            return c.getRed() > 180 && c.getGreen() < 80 && c.getBlue() < 80;
        }
        if (color.equals(Color.GREEN)) {
            return c.getGreen() > 180 && c.getRed() < 80 && c.getBlue() < 80;
        }
        return Math.abs(c.getRed() - color.getRed()) < 20
                && Math.abs(c.getGreen() - color.getGreen()) < 20
                && Math.abs(c.getBlue() - color.getBlue()) < 20;
    }

    private static int maxBrightVerticalRun(BufferedImage image,
                                            int x0,
                                            int x1,
                                            int y0,
                                            int y1) {
        int best = 0;
        int minX = Math.max(0, x0);
        int maxX = Math.min(image.getWidth() - 1, x1);
        int minY = Math.max(0, y0);
        int maxY = Math.min(image.getHeight() - 1, y1);
        for (int x = minX; x <= maxX; x++) {
            int current = 0;
            for (int y = minY; y <= maxY; y++) {
                if (isBrightWhite(image.getRGB(x, y))) {
                    current++;
                    best = Math.max(best, current);
                } else {
                    current = 0;
                }
            }
        }
        return best;
    }

    private static boolean isBrightWhite(int rgb) {
        int a = (rgb >>> 24) & 0xff;
        int r = (rgb >> 16) & 0xff;
        int g = (rgb >> 8) & 0xff;
        int b = rgb & 0xff;
        return a > 0 && r > 240 && g > 240 && b > 240;
    }

    private static int pngDpi(File file) throws Exception {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("png");
        assertTrue("PNG reader available", readers.hasNext());
        ImageReader reader = readers.next();
        ImageInputStream input = null;
        try {
            input = ImageIO.createImageInputStream(file);
            reader.setInput(input);
            IIOMetadata metadata = reader.getImageMetadata(0);
            Node root = metadata.getAsTree("javax_imageio_png_1.0");
            Node phys = childNamed(root, "pHYs");
            assertNotNull("PNG pHYs metadata", phys);
            int pixelsPerMeter = Integer.parseInt(
                    phys.getAttributes().getNamedItem("pixelsPerUnitXAxis")
                            .getNodeValue());
            assertEquals("meter", phys.getAttributes().getNamedItem("unitSpecifier")
                    .getNodeValue());
            return (int) Math.round(pixelsPerMeter * 0.0254d);
        } finally {
            reader.dispose();
            if (input != null) {
                input.close();
            }
        }
    }

    private static Node childNamed(Node root, String name) {
        if (root == null) {
            return null;
        }
        for (Node child = root.getFirstChild(); child != null;
             child = child.getNextSibling()) {
            if (name.equals(child.getNodeName())) {
                return child;
            }
        }
        return null;
    }
}
