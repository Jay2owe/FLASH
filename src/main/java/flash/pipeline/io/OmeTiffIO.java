package flash.pipeline.io;

import ij.IJ;
import ij.ImagePlus;
import loci.common.services.ServiceFactory;
import loci.formats.meta.IMetadata;
import loci.formats.services.OMEXMLService;
import loci.formats.out.OMETiffWriter;
import ome.xml.model.primitives.Color;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

/**
 * Minimal OME-TIFF writer using Bio-Formats.
 *
 * Writes a single ImagePlus as one-series OME-TIFF.
 * Intended for saving composite/channel stacks for downstream viewing.
 */
public class OmeTiffIO {

    /**
     * Default OME-TIFF output directory: {@code FLASH/Results/Presentation Images/OME-TIFF/}.
     */
    public static File defaultOutputDir(FlashProjectLayout layout) {
        return layout.presentationOmeTiffDir();
    }

    /**
     * Save an ImagePlus as a single-series OME-TIFF.
     *
     * Requirements/assumptions:
     * - imp is a hyperstack or stack with dimensions (C,Z,T)
     * - pixel type is 8-bit or 16-bit
     *
     * @param imp           image to save
     * @param outFile       destination .ome.tif/.ome.tiff
     * @param channelNames  optional channel names (length >= C)
     * @param channelColors optional display colors ("red","green","blue","cyan","magenta","yellow","gray") length >= C
     */
    public static void saveOmeTiff(ImagePlus imp, File outFile, String[] channelNames, String[] channelColors) throws Exception {
        if (imp == null) throw new IllegalArgumentException("imp is null");
        if (outFile == null) throw new IllegalArgumentException("outFile is null");

        int w = imp.getWidth();
        int h = imp.getHeight();
        int cSize = Math.max(1, imp.getNChannels());
        int zSize = Math.max(1, imp.getNSlices());
        int tSize = Math.max(1, imp.getNFrames());
        int bitDepth = imp.getBitDepth();

        if (bitDepth != 8 && bitDepth != 16) {
            throw new IllegalArgumentException("Only 8-bit and 16-bit supported; got bitDepth=" + bitDepth);
        }

        // Build OME-XML metadata
        ServiceFactory sf = new ServiceFactory();
        OMEXMLService omeService = sf.getInstance(OMEXMLService.class);
        // Bio-Formats writers expect loci.formats.meta.IMetadata (which OMEXMLMetadata implements)
        loci.formats.meta.IMetadata meta = omeService.createOMEXMLMetadata();

        String imageId = "Image:0";
        String pixelsId = "Pixels:0";
        meta.setImageID(imageId, 0);
        meta.setPixelsID(pixelsId, 0);
        meta.setPixelsDimensionOrder(ome.xml.model.enums.DimensionOrder.XYCZT, 0);
        meta.setPixelsSizeX(new ome.xml.model.primitives.PositiveInteger(w), 0);
        meta.setPixelsSizeY(new ome.xml.model.primitives.PositiveInteger(h), 0);
        meta.setPixelsSizeC(new ome.xml.model.primitives.PositiveInteger(cSize), 0);
        meta.setPixelsSizeZ(new ome.xml.model.primitives.PositiveInteger(zSize), 0);
        meta.setPixelsSizeT(new ome.xml.model.primitives.PositiveInteger(tSize), 0);
        meta.setPixelsType(bitDepth == 8 ? ome.xml.model.enums.PixelType.UINT8 : ome.xml.model.enums.PixelType.UINT16, 0);
        meta.setPixelsBigEndian(Boolean.FALSE, 0);

        // Channels
        for (int c = 0; c < cSize; c++) {
            meta.setChannelID("Channel:0:" + c, 0, c);
            if (channelNames != null && c < channelNames.length && channelNames[c] != null) {
                meta.setChannelName(channelNames[c], 0, c);
            }
            if (channelColors != null && c < channelColors.length && channelColors[c] != null) {
                Color col = toOmeColor(channelColors[c]);
                if (col != null) {
                    meta.setChannelColor(col, 0, c);
                }
            }
            meta.setChannelSamplesPerPixel(new ome.xml.model.primitives.PositiveInteger(1), 0, c);
        }

        // TiffData + Plane entries (some Bio-Formats versions/readers require these)
        int planeCount = zSize * cSize * tSize;
        for (int p = 0; p < planeCount; p++) {
            int theZ = p / (cSize * tSize);
            int theC = (p / tSize) % cSize;
            int theT = p % tSize;

            meta.setPlaneTheZ(new ome.xml.model.primitives.NonNegativeInteger(theZ), 0, p);
            meta.setPlaneTheC(new ome.xml.model.primitives.NonNegativeInteger(theC), 0, p);
            meta.setPlaneTheT(new ome.xml.model.primitives.NonNegativeInteger(theT), 0, p);

            // Map plane -> IFD
            meta.setTiffDataFirstZ(new ome.xml.model.primitives.NonNegativeInteger(theZ), 0, p);
            meta.setTiffDataFirstC(new ome.xml.model.primitives.NonNegativeInteger(theC), 0, p);
            meta.setTiffDataFirstT(new ome.xml.model.primitives.NonNegativeInteger(theT), 0, p);
            meta.setTiffDataIFD(new ome.xml.model.primitives.NonNegativeInteger(p), 0, p);
            meta.setTiffDataPlaneCount(new ome.xml.model.primitives.NonNegativeInteger(1), 0, p);
        }

        // Write
        OMETiffWriter writer = new OMETiffWriter();
        try {
            writer.setMetadataRetrieve(meta);
            writer.setCompression("LZW");
            writer.setId(outFile.getAbsolutePath());

            int bytesPerPixel = bitDepth == 8 ? 1 : 2;
            int planeBytes = w * h * bytesPerPixel;

            for (int t = 0; t < tSize; t++) {
                for (int z = 0; z < zSize; z++) {
                    for (int c = 0; c < cSize; c++) {
                        int stackIndex = imp.getStackIndex(c + 1, z + 1, t + 1); // 1-based
                        Object pixels = imp.getStack().getPixels(stackIndex);
                        byte[] plane = toBytes(pixels, bitDepth, planeBytes);

                        int planeIndex = z * cSize * tSize + c * tSize + t;
                        writer.saveBytes(planeIndex, plane);
                    }
                }
            }
        } finally { 
            try {
                writer.close();
            } catch (Exception ignored) {
            }
        }

        IJ.log("Saved OME-TIFF: " + outFile.getAbsolutePath());
    }

    private static byte[] toBytes(Object pixels, int bitDepth, int planeBytes) {
        if (bitDepth == 8) {
            // ImageJ uses byte[]
            if (pixels instanceof byte[]) return (byte[]) pixels;
            // fallback copy
            byte[] out = new byte[planeBytes];
            if (pixels instanceof short[]) {
                short[] s = (short[]) pixels;
                for (int i = 0; i < s.length && i < out.length; i++) out[i] = (byte) (s[i] & 0xff);
            }
            return out;
        } else {
            // 16-bit: ImageJ uses short[] little-endian in memory; OME expects bytes
            if (pixels instanceof short[]) {
                short[] s = (short[]) pixels;
                ByteBuffer bb = ByteBuffer.allocate(planeBytes).order(ByteOrder.LITTLE_ENDIAN);
                for (short value : s) bb.putShort(value);
                return bb.array();
            }
            // fallback
            byte[] out = new byte[planeBytes];
            if (pixels instanceof byte[]) {
                System.arraycopy(pixels, 0, out, 0, Math.min(((byte[]) pixels).length, out.length));
            }
            return out;
        }
    }

    private static Color toOmeColor(String name) {
        if (name == null) return null;
        String c = name.trim().toLowerCase(Locale.ROOT);
        // OME Color is ARGB (0..255)
        switch (c) {
            case "red":
                return new Color(255, 255, 0, 0);
            case "green":
                return new Color(255, 0, 255, 0);
            case "blue":
                return new Color(255, 0, 0, 255);
            case "cyan":
                return new Color(255, 0, 255, 255);
            case "magenta":
                return new Color(255, 255, 0, 255);
            case "yellow":
                return new Color(255, 255, 255, 0);
            case "grey":
            case "gray":
            case "grays":
                return new Color(255, 255, 255, 255);
            default:
                return null;
        }
    }
}
