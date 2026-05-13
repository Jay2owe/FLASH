package flash.pipeline.ui.sandbox.variation;

import ij.CompositeImage;
import ij.ImagePlus;
import ij.measure.Calibration;
import ij.process.LUT;

/**
 * Copies display settings from the raw source onto a duplicated variant image.
 */
public final class DisplaySettingsCloner {

    private DisplaySettingsCloner() {}

    public static ImagePlus cloneFrom(ImagePlus source, ImagePlus variant) {
        if (source == null) throw new IllegalArgumentException("source must not be null");
        if (variant == null) throw new IllegalArgumentException("variant must not be null");
        ImagePlus copy = variant.duplicate();
        copy.setTitle(variant.getTitle());

        Calibration calibration = source.getCalibration();
        if (calibration != null) copy.setCalibration(calibration.copy());

        if (source instanceof CompositeImage && copy instanceof CompositeImage) {
            copyComposite((CompositeImage) source, (CompositeImage) copy);
        } else {
            copySingleChannel(source, copy);
        }
        return copy;
    }

    private static void copySingleChannel(ImagePlus source, ImagePlus copy) {
        LUT[] luts = source.getLuts();
        if (luts != null && luts.length > 0 && luts[0] != null) {
            copy.setLut((LUT) luts[0].clone());
        }
        copy.setDisplayRange(source.getDisplayRangeMin(), source.getDisplayRangeMax());
    }

    private static void copyComposite(CompositeImage source, CompositeImage copy) {
        int channels = Math.min(source.getNChannels(), copy.getNChannels());
        if (channels <= 0) return;

        LUT[] sourceLuts = source.getLuts();
        if (sourceLuts != null && sourceLuts.length >= channels) {
            LUT[] copyLuts = new LUT[copy.getNChannels()];
            LUT[] existing = copy.getLuts();
            for (int i = 0; i < copyLuts.length; i++) {
                if (i < channels && sourceLuts[i] != null) {
                    copyLuts[i] = (LUT) sourceLuts[i].clone();
                } else if (existing != null && i < existing.length && existing[i] != null) {
                    copyLuts[i] = existing[i];
                } else {
                    copyLuts[i] = LUT.createLutFromColor(java.awt.Color.WHITE);
                }
            }
            copy.setLuts(copyLuts);
        }

        int sourceChannel = source.getChannel();
        int copyChannel = copy.getChannel();
        try {
            for (int ch = 1; ch <= channels; ch++) {
                source.setPositionWithoutUpdate(ch, source.getSlice(), source.getFrame());
                copy.setPositionWithoutUpdate(ch, copy.getSlice(), copy.getFrame());
                copy.setDisplayRange(source.getDisplayRangeMin(), source.getDisplayRangeMax());
            }
        } finally {
            source.setPositionWithoutUpdate(sourceChannel, source.getSlice(), source.getFrame());
            copy.setPositionWithoutUpdate(copyChannel, copy.getSlice(), copy.getFrame());
        }

        copy.setMode(source.getMode());
        boolean[] active = source.getActiveChannels();
        boolean[] copyActive = copy.getActiveChannels();
        if (active != null && copyActive != null) {
            int n = Math.min(active.length, copyActive.length);
            for (int i = 0; i < n; i++) {
                copyActive[i] = active[i];
            }
        }
        copy.updateAndDraw();
    }
}
