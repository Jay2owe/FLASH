package flash.pipeline.deconv.psf;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.FloatProcessor;
import ij.process.ImageConverter;
import ij.process.ImageProcessor;
import ij.process.StackConverter;

import java.util.Locale;

/**
 * Thin wrapper that produces 3D point-spread functions for the deconvolution pipeline.
 *
 * <p>Historically this class tried to drive the EPFL PSF Generator Fiji plugin via
 * {@code IJ.run("PSF Generator", options)}, but that plugin's {@code run(String)} method
 * only displays a Swing dialog and ignores macro options. The pipeline therefore got back
 * no image, skipped every channel, and reported "finished" without doing any work.
 *
 * <p>PSF synthesis now runs natively in {@link ScalarPsfSynthesizer}; this class is kept
 * only because {@link PsfCache} and existing call sites already reference it. The
 * {@link #buildMacroOptions} helper is preserved as a record of the macro keys the old
 * adapter attempted; it is no longer invoked at runtime.
 */
public final class EpflPsfGeneratorAdapter {

    private EpflPsfGeneratorAdapter() {}

    public static ImagePlus synthesize(PsfSpec spec, PsfModel model) {
        if (spec == null) throw new IllegalArgumentException("spec is required.");
        if (model == null) throw new IllegalArgumentException("model is required.");
        ImagePlus psf = ScalarPsfSynthesizer.synthesize(spec, model);
        if (psf == null) {
            return null;
        }
        convertTo32BitInPlace(psf);
        centerBrightestVoxelInPlace(psf);
        return psf;
    }

    /**
     * Pure option-string builder for the EPFL PSF Generator macro API.
     *
     * <p>Retained for historical reference and for the existing unit test; the returned
     * string is no longer passed to {@code IJ.run} because the upstream plugin's
     * {@code run(String)} method does not parse macro options.
     */
    static String buildMacroOptions(PsfSpec spec, PsfModel model) {
        StringBuilder sb = new StringBuilder();
        addBracketedOption(sb, "optical-model", model.epflMacroModelKey());
        addNumericOption(sb, "na", spec.getNumericalAperture());
        addNumericOption(sb, "ri-immersion", spec.getImmersionRI());
        addNumericOption(sb, "ri-sample", spec.getSampleRI());
        addNumericOption(sb, "wavelength", spec.getEmissionWavelengthNm());
        addIntegerOption(sb, "nx", spec.getSizeX());
        addIntegerOption(sb, "ny", spec.getSizeY());
        addIntegerOption(sb, "nz", spec.getSizeZ());
        addNumericOption(sb, "resxy", spec.getPixelSizeXyNm());
        addNumericOption(sb, "resz", spec.getPixelSizeZNm());
        if (spec.getScopeModality() == ScopeModality.CONFOCAL && spec.getPinholeAiryUnits() != null) {
            addNumericOption(sb, "pinhole", spec.getPinholeAiryUnits().doubleValue());
        }
        return sb.toString().trim();
    }

    static double sum(ImagePlus image) {
        double total = 0.0;
        ImageStack stack = image.getStack();
        for (int z = 1; z <= stack.getSize(); z++) {
            ImageProcessor ip = stack.getProcessor(z);
            int pixelCount = ip.getPixelCount();
            for (int i = 0; i < pixelCount; i++) {
                total += ip.getf(i);
            }
        }
        return total;
    }

    static int[] brightestVoxel(ImagePlus image) {
        int bestX = 0;
        int bestY = 0;
        int bestZ = 0;
        float bestValue = -Float.MAX_VALUE;
        ImageStack stack = image.getStack();
        int width = image.getWidth();
        int height = image.getHeight();
        for (int z = 0; z < stack.getSize(); z++) {
            ImageProcessor ip = stack.getProcessor(z + 1);
            int pixelCount = ip.getPixelCount();
            for (int i = 0; i < pixelCount; i++) {
                float value = ip.getf(i);
                if (value > bestValue) {
                    bestValue = value;
                    bestX = i % width;
                    bestY = i / width;
                    bestZ = z;
                }
            }
        }
        return new int[]{bestX, bestY, bestZ, width / 2, height / 2, stack.getSize() / 2};
    }

    static void normalizeInPlace(ImagePlus image) {
        double total = sum(image);
        if (Double.isNaN(total) || Double.isInfinite(total) || total <= 0.0) {
            throw new IllegalStateException("Generated PSF has non-positive or non-finite sum.");
        }
        ImageStack stack = image.getStack();
        float scale = (float) (1.0 / total);
        for (int z = 1; z <= stack.getSize(); z++) {
            ImageProcessor ip = stack.getProcessor(z);
            int pixelCount = ip.getPixelCount();
            for (int i = 0; i < pixelCount; i++) {
                ip.setf(i, ip.getf(i) * scale);
            }
        }
    }

    static void centerBrightestVoxelInPlace(ImagePlus image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int depth = image.getStackSize();
        if (width <= 0 || height <= 0 || depth <= 0) return;

        int[] brightest = brightestVoxel(image);
        int shiftX = brightest[3] - brightest[0];
        int shiftY = brightest[4] - brightest[1];
        int shiftZ = brightest[5] - brightest[2];
        if (shiftX == 0 && shiftY == 0 && shiftZ == 0) return;

        int channels = Math.max(1, image.getNChannels());
        int slices = Math.max(1, image.getNSlices());
        int frames = Math.max(1, image.getNFrames());
        boolean hyperstack = image.isHyperStack();
        Calibration calibration = image.getCalibration() == null ? null : image.getCalibration().copy();
        ImageStack source = image.getStack();
        ImageStack shifted = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            int sourceZ = floorMod(z - shiftZ, depth);
            ImageProcessor sourceIp = source.getProcessor(sourceZ + 1);
            float[] plane = new float[width * height];
            for (int y = 0; y < height; y++) {
                int sourceY = floorMod(y - shiftY, height);
                int row = y * width;
                for (int x = 0; x < width; x++) {
                    int sourceX = floorMod(x - shiftX, width);
                    plane[row + x] = sourceIp.getf(sourceY * width + sourceX);
                }
            }
            shifted.addSlice(source.getSliceLabel(sourceZ + 1), new FloatProcessor(width, height, plane, null));
        }
        image.setStack(image.getTitle(), shifted);
        if (channels * slices * frames == depth) {
            image.setDimensions(channels, slices, frames);
            image.setOpenAsHyperStack(hyperstack);
        } else {
            image.setDimensions(1, depth, 1);
        }
        if (calibration != null) {
            image.setCalibration(calibration);
        }
    }

    private static void convertTo32BitInPlace(ImagePlus image) {
        if (image.getBitDepth() == 32) return;
        if (image.getStackSize() > 1) new StackConverter(image).convertToGray32();
        else new ImageConverter(image).convertToGray32();
    }

    private static void addBracketedOption(StringBuilder sb, String key, String value) {
        if (sb.length() > 0) sb.append(' ');
        sb.append(key).append("=[").append(value == null ? "" : value).append(']');
    }

    private static void addNumericOption(StringBuilder sb, String key, double value) {
        if (sb.length() > 0) sb.append(' ');
        sb.append(key).append('=').append(formatDouble(value));
    }

    private static void addIntegerOption(StringBuilder sb, String key, int value) {
        if (sb.length() > 0) sb.append(' ');
        sb.append(key).append('=').append(value);
    }

    private static String formatDouble(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private static int floorMod(int value, int modulo) {
        int result = value % modulo;
        return result < 0 ? result + modulo : result;
    }
}
