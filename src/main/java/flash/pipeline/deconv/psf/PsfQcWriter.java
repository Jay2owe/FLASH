package flash.pipeline.deconv.psf;

import flash.pipeline.io.AsyncImageSaver;
import ij.ImagePlus;
import ij.process.ImageConverter;
import ij.process.StackConverter;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class PsfQcWriter {

    private PsfQcWriter() {}

    public static void writePsfPreview(ImagePlus psf, PsfSpec spec, PsfModel model, File outDir) {
        if (psf == null) throw new IllegalArgumentException("psf is required.");
        if (spec == null) throw new IllegalArgumentException("spec is required.");
        if (model == null) throw new IllegalArgumentException("model is required.");
        if (outDir == null) throw new IllegalArgumentException("outDir is required.");

        File psfDir = new File(outDir, "PSF");
        if (!psfDir.isDirectory() && !psfDir.mkdirs() && !psfDir.isDirectory()) {
            throw new IllegalStateException("Could not create PSF QC directory: " + psfDir.getAbsolutePath());
        }

        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        String baseName = safeModelName(model)
                + "_NA" + formatNa(spec.getNumericalAperture())
                + "_" + formatWavelength(spec.getEmissionWavelengthNm())
                + "nm_" + timestamp;
        File tif = new File(psfDir, baseName + ".tif");
        File sidecar = new File(psfDir, baseName + ".txt");

        ImagePlus toSave = ensure32BitCopy(psf, baseName);
        try {
            AsyncImageSaver.saveAsTiffAsync(toSave, tif.getAbsolutePath());
        } finally {
            toSave.changes = false;
            toSave.close();
            toSave.flush();
        }

        try {
            Files.write(sidecar.toPath(), buildSidecarLines(spec, model, tif).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Could not write PSF sidecar: " + sidecar.getAbsolutePath(), e);
        }
    }

    static String buildSidecarLines(PsfSpec spec, PsfModel model, File tif) {
        List<String> lines = new ArrayList<String>();
        lines.add("model=" + model.name());
        lines.add("modelDisplayName=" + model.displayName());
        lines.add("scopeModality=" + spec.getScopeModality().name());
        lines.add("numericalAperture=" + formatDouble(spec.getNumericalAperture()));
        lines.add("immersionRI=" + formatDouble(spec.getImmersionRI()));
        lines.add("sampleRI=" + formatDouble(spec.getSampleRI()));
        lines.add("emissionWavelengthNm=" + formatDouble(spec.getEmissionWavelengthNm()));
        lines.add("pixelSizeXyNm=" + formatDouble(spec.getPixelSizeXyNm()));
        lines.add("pixelSizeZNm=" + formatDouble(spec.getPixelSizeZNm()));
        lines.add("sizeX=" + spec.getSizeX());
        lines.add("sizeY=" + spec.getSizeY());
        lines.add("sizeZ=" + spec.getSizeZ());
        lines.add("pinholeAiryUnits="
                + (spec.getPinholeAiryUnits() == null ? "" : formatDouble(spec.getPinholeAiryUnits().doubleValue())));
        if (tif != null) {
            lines.add("tiff=" + tif.getName());
        }
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append(System.lineSeparator());
        }
        return sb.toString();
    }

    static ImagePlus ensure32BitCopy(ImagePlus source, String title) {
        ImagePlus copy = source.duplicate();
        copy.setTitle(title == null ? copy.getTitle() : title);
        if (copy.getBitDepth() == 32) return copy;
        if (copy.getStackSize() > 1) new StackConverter(copy).convertToGray32();
        else new ImageConverter(copy).convertToGray32();
        return copy;
    }

    private static String safeModelName(PsfModel model) {
        return model.name().toLowerCase(Locale.ROOT);
    }

    private static String formatNa(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String formatWavelength(double value) {
        double rounded = Math.rint(value);
        if (Math.abs(rounded - value) < 0.05) {
            return String.format(Locale.ROOT, "%.0f", rounded);
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String formatDouble(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }
}
