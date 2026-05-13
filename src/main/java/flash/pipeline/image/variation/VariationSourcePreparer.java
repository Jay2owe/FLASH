package flash.pipeline.image.variation;

import ij.ImagePlus;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.plugin.Duplicator;

import java.util.List;

/**
 * Prepares FLASH's already channel-isolated preview source for variant runs.
 */
public final class VariationSourcePreparer {

    private VariationSourcePreparer() {}

    public static PreparedSource prepare(ImagePlus channelSource, Roi roi) {
        if (channelSource == null) {
            throw new IllegalArgumentException("channelSource must not be null");
        }
        ImagePlus executionBase = duplicateCurrentTimepoint(channelSource);
        ImagePlus executionSource = null;
        try {
            executionSource = RoiCropper.cropToRoi(executionBase, roi);
            if (executionSource != executionBase) {
                executionBase.flush();
                executionBase = null;
            }
            ImagePlus displaySource = duplicateWholeImage(executionSource);
            return new PreparedSource(executionSource, displaySource);
        } catch (RuntimeException ex) {
            if (executionSource != null && executionSource != executionBase) {
                executionSource.flush();
            }
            throw ex;
        } finally {
            if (executionBase != null && executionSource != executionBase) {
                executionBase.flush();
            }
        }
    }

    public static VariationRunResult run(ImagePlus channelSource, Roi roi,
                                         List<VariantPlan> plans,
                                         ProgressCallback progress) {
        PreparedSource prepared = prepare(channelSource, roi);
        try {
            List<VariantResult> results =
                    VariantExecutor.runAll(prepared.executionSource, plans, progress);
            return new VariationRunResult(prepared.displaySource, results);
        } catch (RuntimeException ex) {
            prepared.displaySource.flush();
            throw ex;
        } finally {
            prepared.executionSource.flush();
        }
    }

    static ImagePlus duplicateCurrentTimepoint(ImagePlus source) {
        int frames = Math.max(1, source.getNFrames());
        int frame = frames > 1 ? source.getFrame() : 1;
        if (frame < 1 || frame > frames) frame = 1;
        return duplicateRange(source, frame, frame);
    }

    static ImagePlus duplicateWholeImage(ImagePlus source) {
        return duplicateRange(source, 1, Math.max(1, source.getNFrames()));
    }

    private static ImagePlus duplicateRange(ImagePlus source, int firstFrame, int lastFrame) {
        Roi previousRoi = source.getRoi();
        ImagePlus copy;
        try {
            source.deleteRoi();
            int channels = Math.max(1, source.getNChannels());
            int slices = Math.max(1, source.getNSlices());
            copy = new Duplicator().run(source,
                    1, channels,
                    1, slices,
                    firstFrame, lastFrame);
        } finally {
            if (previousRoi != null) source.setRoi(previousRoi);
            else source.deleteRoi();
        }

        if (source.getTitle() != null) {
            copy.setTitle(source.getTitle());
        }
        if (source.getCalibration() != null) {
            Calibration cal = source.getCalibration().copy();
            copy.setCalibration(cal);
        }
        if (source.isHyperStack()) {
            copy.setOpenAsHyperStack(true);
        }
        RoiCropper.copyDisplayState(source, copy);
        return copy;
    }

    public static final class PreparedSource {
        public final ImagePlus executionSource;
        public final ImagePlus displaySource;

        private PreparedSource(ImagePlus executionSource, ImagePlus displaySource) {
            this.executionSource = executionSource;
            this.displaySource = displaySource;
        }
    }
}
