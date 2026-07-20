package flash.pipeline.ui.variations;

import flash.pipeline.image.FilterExecutor;
import flash.pipeline.image.ImageOps;

import ij.ImagePlus;

public final class MacroPreprocessor {

    public ImagePlus prepare(ImagePlus cropped,
                             ParameterSweep sweep,
                             ParameterCombo combo) throws Exception {
        MacroVariation macro = resolveMacro(sweep, combo);
        if (isNoOp(macro)) {
            return cropped;
        }
        ImagePlus work = null;
        try {
            work = ImageOps.duplicateThreadSafe(cropped);
            if (work == null) {
                throw new IllegalStateException("could not duplicate source image");
            }
            FilterExecutor.runThreadSafe(work, macro.scriptText());
            validateResult(cropped, work);
            return work;
        } catch (Throwable t) {
            closeImage(work);
            throw macroFailure(macroIdentity(macro), t);
        }
    }

    public void closeIfOwned(ImagePlus image, ImagePlus shared) {
        if (image == null || image == shared) {
            return;
        }
        closeImage(image);
    }

    public static String macroToken(ParameterCombo combo) {
        Object value = combo == null ? null : combo.get(ParameterId.MACRO);
        if (value == null) {
            return MacroToken.NONE_VALUE;
        }
        String token = MacroToken.tokenString(value);
        return token == null || token.trim().isEmpty()
                ? MacroToken.NONE_VALUE
                : token.trim();
    }

    public static boolean hasActiveMacroValue(ParameterSweep sweep) {
        if (sweep == null || sweep.valueLists() == null) {
            return false;
        }
        ParameterValueList values = sweep.valueLists().get(ParameterId.MACRO);
        if (values == null) {
            return false;
        }
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            if (value != null) {
                String token = MacroToken.tokenString(value);
                if (token != null
                        && !token.trim().isEmpty()
                        && !MacroToken.NONE_VALUE.equals(token.trim())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static MacroVariation resolveMacro(ParameterSweep sweep,
                                               ParameterCombo combo) throws Exception {
        String token = macroToken(combo);
        if (MacroToken.NONE_VALUE.equals(token)) {
            return MacroVariation.none();
        }
        if (sweep == null) {
            throw new IllegalStateException(
                    "Macro preprocessing failed for " + token
                            + ": sweep metadata is unavailable");
        }
        MacroVariation macro = sweep.macroVariations().resolve(token);
        if (macro == null) {
            throw new IllegalStateException(
                    "Macro preprocessing failed for " + token
                            + ": token was not found in this sweep");
        }
        if (macro.scriptText() == null || macro.scriptText().trim().isEmpty()) {
            throw new IllegalStateException(
                    "Macro preprocessing failed for " + macroIdentity(macro)
                            + ": script text is empty");
        }
        return macro;
    }

    private static boolean isNoOp(MacroVariation macro) {
        return macro == null
                || MacroToken.NONE_VALUE.equals(macro.token())
                || macro.scriptText() == null
                || macro.scriptText().trim().isEmpty();
    }

    private static void validateResult(ImagePlus source, ImagePlus result) {
        if (result == null) {
            throw new IllegalStateException("macro returned no image");
        }
        if (result.getStack() == null || result.getStackSize() <= 0) {
            throw new IllegalStateException("macro returned an empty image");
        }
        if (source == null) {
            return;
        }
        if (result.getWidth() != source.getWidth()
                || result.getHeight() != source.getHeight()) {
            throw new IllegalStateException("macro changed image size from "
                    + source.getWidth() + "x" + source.getHeight()
                    + " to " + result.getWidth() + "x" + result.getHeight());
        }
        if (result.getStackSize() != source.getStackSize()
                || result.getNChannels() != source.getNChannels()
                || result.getNSlices() != source.getNSlices()
                || result.getNFrames() != source.getNFrames()) {
            throw new IllegalStateException("macro changed stack dimensions from "
                    + dimensions(source) + " to " + dimensions(result));
        }
        if (result.getCalibration() == null && source.getCalibration() != null) {
            result.setCalibration(source.getCalibration().copy());
        }
    }

    private static String dimensions(ImagePlus image) {
        if (image == null) {
            return "none";
        }
        return image.getNChannels() + "C/"
                + image.getNSlices() + "Z/"
                + image.getNFrames() + "T";
    }

    private static Exception macroFailure(String identity, Throwable cause) {
        String message = "Macro preprocessing failed for " + identity;
        String detail = cause == null ? "" : cause.getMessage();
        if (detail != null && !detail.trim().isEmpty()) {
            message += ": " + detail.trim();
        }
        return new Exception(message, cause);
    }

    private static String macroIdentity(MacroVariation macro) {
        if (macro == null) {
            return MacroToken.NONE_VALUE;
        }
        String display = macro.displayName() == null ? "" : macro.displayName().trim();
        String token = macro.token() == null ? MacroToken.NONE_VALUE : macro.token();
        return display.isEmpty() || display.equals(token)
                ? token
                : display + " (" + token + ")";
    }

    private static void closeImage(ImagePlus image) {
        if (image == null) {
            return;
        }
        image.changes = false;
        try {
            image.close();
        } catch (Throwable ignored) {
        }
        try {
            image.flush();
        } catch (Throwable ignored) {
        }
    }
}
