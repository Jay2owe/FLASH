package flash.pipeline.presentation;

import flash.pipeline.io.CsvSupport;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        int firstCellCenterX = 18 + 190 + 30;
        int firstCellCenterY = 18 + 46 + 30 + 30;
        Color firstCell = new Color(tile.getRGB(firstCellCenterX, firstCellCenterY), true);
        assertTrue("first requested column should be GFAP/green",
                firstCell.getGreen() > 180 && firstCell.getRed() < 80);
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
        assertEquals(282, tile.getHeight());
        int cellCenterX = 18 + 190 + 40;
        int firstRowCenterY = 18 + 46 + 30 + 40;
        int secondRowCenterY = firstRowCenterY + 80 + 10;
        Color firstCell = new Color(tile.getRGB(cellCenterX, firstRowCenterY), true);
        Color secondCell = new Color(tile.getRGB(cellCenterX, secondRowCenterY), true);
        assertTrue("first row should use the first source image",
                firstCell.getRed() > 180 && firstCell.getGreen() < 80);
        assertTrue("second row should use the second source image",
                secondCell.getGreen() > 180 && secondCell.getRed() < 80);
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

        PresentationTileWriter.writeRequestedOutputs(root, Arrays.asList(record),
                new LinkedHashMap<String, String>(), config);

        File annotated = new File(root, "Annotated Images/Animal1/DAPI_LH_Cortex.png");
        assertTrue(annotated.isFile());
        BufferedImage image = ImageIO.read(annotated);
        assertTrue("scale bar should draw in white",
                containsBrightPixel(image, 65, 30, 99, 49));
        assertNotNull("record should point to annotated derivative", record.annotatedImageFile());
        assertTrue(new File(root, "Tiles/Presentation_Overview_ByAnimal.png").isFile());
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
}
