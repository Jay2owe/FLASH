package flash.pipeline.deconv.psf;

import flash.pipeline.deconv.DeconvolutionAvailability;
import flash.pipeline.image.WindowManagerLock;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.measure.Calibration;
import ij.process.FloatProcessor;
import ij.process.ImageConverter;
import ij.process.ImageProcessor;
import ij.process.StackConverter;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public final class EpflPsfGeneratorAdapter {

    private static final String COMMAND_NAME = "PSF Generator";
    private static final AtomicBoolean INSTALL_PROMPT_LOGGED = new AtomicBoolean(false);

    private static volatile AvailabilityProbe availabilityProbe = new AvailabilityProbe() {
        @Override
        public boolean isPsfGeneratorAvailable() {
            return DeconvolutionAvailability.isPsfGeneratorAvailable();
        }

        @Override
        public String installInstructionUrl(String engineKey) {
            return DeconvolutionAvailability.installInstructionUrl(engineKey);
        }
    };

    private static volatile ImageJRunner imageJRunner = new ImageJRunner() {
        @Override
        public void run(String command, String options) {
            IJ.run(command, options);
        }

        @Override
        public int[] getWindowIds() {
            return WindowManager.getIDList();
        }

        @Override
        public ImagePlus getImage(int id) {
            return WindowManager.getImage(id);
        }
    };

    private static volatile LogSink logSink = new LogSink() {
        @Override
        public void log(String message) {
            IJ.log(message);
        }
    };

    private EpflPsfGeneratorAdapter() {}

    public static ImagePlus synthesize(PsfSpec spec, PsfModel model) {
        if (spec == null) throw new IllegalArgumentException("spec is required.");
        if (model == null) throw new IllegalArgumentException("model is required.");

        if (!availabilityProbe.isPsfGeneratorAvailable()) {
            logMissingPluginOnce();
            return null;
        }

        ImagePlus psf = runGenerator(buildMacroOptions(spec, model));
        if (psf == null) {
            return null;
        }

        convertTo32BitInPlace(psf);
        normalizeInPlace(psf);
        centerBrightestVoxelInPlace(psf);
        return psf;
    }

    /**
     * Pure option-string builder for the EPFL PSF Generator macro API.
     *
     * <p>No live {@code PSF_Generator} install was available on this machine on
     * 2026-04-23. The only local Fiji hit was the older {@code Diffraction_PSF_3D} plugin, which
     * is not the EPFL generator detected by {@link DeconvolutionAvailability#isPsfGeneratorAvailable()}.
     * These option keys are therefore best-effort guesses based on the phase spec:
     * {@code optical-model}, {@code na}, {@code ri-immersion}, {@code ri-sample},
     * {@code wavelength}, {@code nx}, {@code ny}, {@code nz}, {@code resxy}, {@code resz},
     * and confocal-only {@code pinhole}. Keep callers routing through this pure method so the
     * mapping can be corrected later without touching the rest of the pipeline.</p>
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

    static void setAvailabilityProbeForTest(AvailabilityProbe probe) {
        availabilityProbe = probe == null ? availabilityProbe : probe;
    }

    static void setImageJRunnerForTest(ImageJRunner runner) {
        imageJRunner = runner == null ? imageJRunner : runner;
    }

    static void setLogSinkForTest(LogSink sink) {
        logSink = sink == null ? logSink : sink;
    }

    static void resetForTest() {
        INSTALL_PROMPT_LOGGED.set(false);
        availabilityProbe = new AvailabilityProbe() {
            @Override
            public boolean isPsfGeneratorAvailable() {
                return DeconvolutionAvailability.isPsfGeneratorAvailable();
            }

            @Override
            public String installInstructionUrl(String engineKey) {
                return DeconvolutionAvailability.installInstructionUrl(engineKey);
            }
        };
        imageJRunner = new ImageJRunner() {
            @Override
            public void run(String command, String options) {
                IJ.run(command, options);
            }

            @Override
            public int[] getWindowIds() {
                return WindowManager.getIDList();
            }

            @Override
            public ImagePlus getImage(int id) {
                return WindowManager.getImage(id);
            }
        };
        logSink = new LogSink() {
            @Override
            public void log(String message) {
                IJ.log(message);
            }
        };
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

    interface AvailabilityProbe {
        boolean isPsfGeneratorAvailable();
        String installInstructionUrl(String engineKey);
    }

    interface ImageJRunner {
        void run(String command, String options);
        int[] getWindowIds();
        ImagePlus getImage(int id);
    }

    interface LogSink {
        void log(String message);
    }

    private static void logMissingPluginOnce() {
        if (!INSTALL_PROMPT_LOGGED.compareAndSet(false, true)) return;
        String url = availabilityProbe.installInstructionUrl("PsfGenerator");
        String message = "PSF Generator is not installed. Install it from Fiji Updater or see "
                + (url == null ? "<no install URL>" : url);
        logSink.log(message);
    }

    private static ImagePlus runGenerator(String options) {
        WindowManagerLock.LOCK.lock();
        try {
            int[] beforeIds = imageJRunner.getWindowIds();
            imageJRunner.run(COMMAND_NAME, options);
            ImagePlus generated = findGeneratedImage(beforeIds, imageJRunner.getWindowIds());
            if (generated == null) {
                return null;
            }
            ImagePlus duplicate = generated.duplicate();
            duplicate.setTitle(generated.getTitle());
            disposeImage(generated);
            return duplicate;
        } finally {
            WindowManagerLock.LOCK.unlock();
        }
    }

    private static ImagePlus findGeneratedImage(int[] beforeIds, int[] afterIds) {
        if (afterIds == null || afterIds.length == 0) return null;
        Set<Integer> seen = new HashSet<Integer>();
        if (beforeIds != null) {
            for (int id : beforeIds) {
                seen.add(Integer.valueOf(id));
            }
        }
        for (int i = afterIds.length - 1; i >= 0; i--) {
            int id = afterIds[i];
            if (seen.contains(Integer.valueOf(id))) continue;
            ImagePlus image = imageJRunner.getImage(id);
            if (image != null) return image;
        }
        return null;
    }

    private static void convertTo32BitInPlace(ImagePlus image) {
        if (image.getBitDepth() == 32) return;
        if (image.getStackSize() > 1) new StackConverter(image).convertToGray32();
        else new ImageConverter(image).convertToGray32();
    }

    private static void disposeImage(ImagePlus image) {
        if (image == null) return;
        image.changes = false;
        try {
            image.close();
        } finally {
            image.flush();
        }
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
