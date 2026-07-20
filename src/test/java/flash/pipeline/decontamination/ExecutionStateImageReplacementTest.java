package flash.pipeline.decontamination;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ShortProcessor;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

/**
 * Guards the close-on-replace behaviour of {@link CorrectionPipeline.ExecutionState}: replacing an
 * intermediate output image must return the NEW image and keep it usable (i.e. the fix must not
 * close the wrong one), and setting the same reference twice must be a no-op.
 */
public class ExecutionStateImageReplacementTest {

    private static ImagePlus img(String name) {
        ImageStack stack = new ImageStack(2, 2);
        stack.addSlice(new ShortProcessor(2, 2, new short[]{1, 2, 3, 4}, null));
        return new ImagePlus(name, stack);
    }

    private static CorrectionPipeline.ExecutionState state() {
        SpectralDecontaminationConfig config = new SpectralDecontaminationConfig();
        config.setTargetChannelIndex(0);
        return CorrectionPipeline.ExecutionState.create(img("source"), config);
    }

    @Test
    public void replacingMaskReturnsNewAndKeepsItUsable() {
        CorrectionPipeline.ExecutionState state = state();
        ImagePlus first = img("mask-1");
        ImagePlus second = img("mask-2");
        state.setMaskImage(first);
        state.setMaskImage(second);
        assertSame(second, state.getMaskImage());
        assertNotNull("replacement must not close the new image", second.getProcessor());
    }

    @Test
    public void replacingCorrectedReturnsNewAndKeepsItUsable() {
        CorrectionPipeline.ExecutionState state = state();
        ImagePlus first = img("corrected-1");
        ImagePlus second = img("corrected-2");
        state.setCorrectedImage(first);
        state.setCorrectedImage(second);
        assertSame(second, state.getCorrectedImage());
        assertNotNull(second.getProcessor());
    }

    @Test
    public void replacingVetoMaskReturnsNewAndKeepsItUsable() {
        CorrectionPipeline.ExecutionState state = state();
        ImagePlus first = img("veto-1");
        ImagePlus second = img("veto-2");
        state.setVetoMaskImage(first);
        state.setVetoMaskImage(second);
        assertSame(second, state.getVetoMaskImage());
        assertNotNull(second.getProcessor());
    }

    @Test
    public void settingSameImageTwiceDoesNotCloseIt() {
        CorrectionPipeline.ExecutionState state = state();
        ImagePlus mask = img("mask");
        state.setMaskImage(mask);
        state.setMaskImage(mask);
        assertSame(mask, state.getMaskImage());
        assertNotNull("same-reference set must not close the image", mask.getProcessor());
    }
}
