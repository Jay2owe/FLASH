package flash.pipeline.representative;

import flash.pipeline.bin.BinConfig;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ShortProcessor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * FLASH hands its prepared representative to the bundled Figure Panel Builder
 * engine, which writes the SVG/TIFF/PNG figure, panels and records.
 */
public class RepresentativeFigurePanelBuilderExportTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void representativeIsExportedWithFigurePanelBuilderOutputs() throws Exception {
        File project = temp.newFolder("project");
        File output = temp.newFolder("figures");
        BinConfig cfg = new BinConfig();
        cfg.channelNames.add("DAPI");
        cfg.channelNames.add("GFAP");
        cfg.channelColors.add("Blue");
        cfg.channelColors.add("Grays");
        cfg.channelMinMax.add("0-200");
        cfg.channelMinMax.add("0-400");

        File figure = RepresentativePreviewRenderer.exportWithFigurePanelBuilderForTests(
                project.getAbsolutePath(), cfg, new RepresentativeFigureConfig(),
                series(), twoChannelImage(), new File(project, "Exp.lif"), output,
                "Representative");

        assertTrue(new File(figure, "figure.svg").isFile());
        assertTrue(new File(figure, "figure.tif").isFile());
        BufferedImage png = ImageIO.read(new File(figure, "figure.png"));
        assertNotNull(png);
        File records = new File(figure, "Supporting files");
        assertTrue(new File(records, "manifest.csv").isFile());
        assertTrue(new File(records, "methods.txt").isFile());
        String manifest = new String(java.nio.file.Files.readAllBytes(
                new File(records, "manifest.csv").toPath()), "UTF-8");
        assertTrue(manifest, manifest.contains("DAPI") && manifest.contains("GFAP"));
    }

    @Test
    public void flashColourNamesMapToFigurePanelBuilderColours() {
        assertEquals("grey", RepresentativePreviewRenderer.figurePanelColour("Grays").name());
        assertEquals("magenta",
                RepresentativePreviewRenderer.figurePanelColour("Magenta").name());
        assertEquals("grey", RepresentativePreviewRenderer.figurePanelColour("Fire").name());
    }

    private static RepresentativeSeries series() {
        return new RepresentativeSeries(
                RepresentativeStatTable.seriesIdForIndex(0), 0, 1, "Exp-Mouse1_LH_SCN",
                "Mouse1", "Control", "LH", "SCN", null,
                Arrays.asList(
                        new RepresentativeSeries.ChannelThumbnail(0, "DAPI", null, null),
                        new RepresentativeSeries.ChannelThumbnail(1, "GFAP", null, null)),
                null, null, RepresentativeSeries.PreviewSource.GENERATED, false);
    }

    private static ImagePlus twoChannelImage() {
        ImageStack stack = new ImageStack(120, 80);
        for (int c = 0; c < 2; c++) {
            ShortProcessor processor = new ShortProcessor(120, 80);
            for (int i = 0; i < 120 * 80; i++) processor.set(i, (i % 120) * (c + 1));
            stack.addSlice("C" + (c + 1), processor);
        }
        ImagePlus image = new ImagePlus("Exp-Mouse1_LH_SCN", stack);
        image.setDimensions(2, 1, 1);
        image.setOpenAsHyperStack(true);
        Calibration calibration = new Calibration();
        calibration.pixelWidth = 0.5;
        calibration.pixelHeight = 0.5;
        calibration.setUnit("micron");
        image.setCalibration(calibration);
        return image;
    }
}
