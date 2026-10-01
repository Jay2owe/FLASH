package flash.pipeline.io;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ShortProcessor;
import loci.common.services.ServiceFactory;
import loci.formats.in.OMETiffReader;
import loci.formats.meta.IMetadata;
import loci.formats.services.OMEXMLService;
import ome.units.UNITS;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.Assert.*;

public class OmeTiffIORoundTripTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void retainsDepthTimeChannelsPixelsPhysicalScaleAndDisplayColors() throws Exception {
        ImageStack stack = new ImageStack(2, 1);
        for (int t = 0; t < 2; t++) {
            for (int z = 0; z < 3; z++) {
                for (int c = 0; c < 2; c++) {
                    int value = sample(c, z, t);
                    stack.addSlice(new ShortProcessor(2, 1,
                            new short[]{(short) value, (short) (value + 1)}, null));
                }
            }
        }
        ImagePlus image = new ImagePlus("calibrated", stack);
        image.setDimensions(2, 3, 2);
        image.setOpenAsHyperStack(true);
        Calibration calibration = new Calibration();
        calibration.setUnit("nm");
        calibration.pixelWidth = 500;
        calibration.pixelHeight = 750;
        calibration.pixelDepth = 2500;
        image.setCalibration(calibration);

        File output = new File(temp.getRoot(), "calibrated.ome.tif");
        OmeTiffIO.saveOmeTiff(image, output, new String[]{"first", "second"},
                new String[]{"red", "green"});

        IMetadata metadata = new ServiceFactory().getInstance(OMEXMLService.class)
                .createOMEXMLMetadata();
        try (OMETiffReader reader = new OMETiffReader()) {
            reader.setMetadataStore(metadata);
            reader.setId(output.getAbsolutePath());
            assertEquals(2, reader.getSizeC());
            assertEquals(3, reader.getSizeZ());
            assertEquals(2, reader.getSizeT());
            assertEquals(12, reader.getImageCount());
            assertEquals(0.5, metadata.getPixelsPhysicalSizeX(0).value(UNITS.MICROMETER)
                    .doubleValue(), 1e-12);
            assertEquals(0.75, metadata.getPixelsPhysicalSizeY(0).value(UNITS.MICROMETER)
                    .doubleValue(), 1e-12);
            assertEquals(2.5, metadata.getPixelsPhysicalSizeZ(0).value(UNITS.MICROMETER)
                    .doubleValue(), 1e-12);
            assertEquals("first", metadata.getChannelName(0, 0));
            assertEquals(255, metadata.getChannelColor(0, 0).getRed());
            assertEquals(0, metadata.getChannelColor(0, 0).getGreen());
            assertEquals(255, metadata.getChannelColor(0, 0).getAlpha());
            assertEquals(255, metadata.getChannelColor(0, 1).getGreen());
            assertEquals(0, metadata.getChannelColor(0, 1).getRed());
            for (int t = 0; t < 2; t++) {
                for (int z = 0; z < 3; z++) {
                    for (int c = 0; c < 2; c++) {
                        ByteBuffer pixels = ByteBuffer.wrap(reader.openBytes(reader.getIndex(z, c, t)))
                                .order(reader.isLittleEndian() ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
                        assertEquals(sample(c, z, t), pixels.getShort() & 0xffff);
                        assertEquals(sample(c, z, t) + 1, pixels.getShort() & 0xffff);
                    }
                }
            }
        }
        image.close();
    }

    @Test
    public void pixelUnitsDoNotInventPhysicalCalibration() throws Exception {
        ImagePlus image = new ImagePlus("pixels", new ShortProcessor(1, 1));
        File output = new File(temp.getRoot(), "uncalibrated.ome.tif");
        OmeTiffIO.saveOmeTiff(image, output, null, null);
        IMetadata metadata = new ServiceFactory().getInstance(OMEXMLService.class)
                .createOMEXMLMetadata();
        try (OMETiffReader reader = new OMETiffReader()) {
            reader.setMetadataStore(metadata);
            reader.setId(output.getAbsolutePath());
            assertNull(metadata.getPixelsPhysicalSizeX(0));
            assertNull(metadata.getPixelsPhysicalSizeY(0));
            assertNull(metadata.getPixelsPhysicalSizeZ(0));
        }
        image.close();
    }

    private static int sample(int channel, int depth, int time) {
        return 1000 * time + 100 * depth + 10 * channel + 30000;
    }
}
