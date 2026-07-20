package flash.pipeline.deconv.engine;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DeconvolutionEngineParameterUsageTest {

    @Test
    public void clij2UsesRegularizationOnlyForRlTv() {
        DeconvolutionEngine engine = new Clij2FftEngine();

        assertTrue(engine.usesIterations(Algorithm.RL));
        assertFalse(engine.usesRegularization(Algorithm.RL));
        assertTrue(engine.usesIterations(Algorithm.RL_TV));
        assertTrue(engine.usesRegularization(Algorithm.RL_TV));
    }

    @Test
    public void deconvolutionLab2ReportsAlgorithmSpecificParameters() {
        DeconvolutionEngine engine = new DeconvolutionLab2Engine();

        assertTrue(engine.usesIterations(Algorithm.RL));
        assertFalse(engine.usesRegularization(Algorithm.RL));
        assertTrue(engine.usesIterations(Algorithm.RL_TV));
        assertTrue(engine.usesRegularization(Algorithm.RL_TV));
        assertFalse(engine.usesIterations(Algorithm.TIKHONOV));
        assertTrue(engine.usesRegularization(Algorithm.TIKHONOV));
        assertFalse(engine.usesIterations(Algorithm.WIENER));
        assertTrue(engine.usesRegularization(Algorithm.WIENER));
        assertTrue(engine.usesIterations(Algorithm.LANDWEBER));
        assertFalse(engine.usesRegularization(Algorithm.LANDWEBER));
    }

    @Test
    public void iterativeDeconvolve3DUsesIterationsOnly() {
        DeconvolutionEngine engine = new IterativeDeconvolve3DEngine();

        assertTrue(engine.usesIterations(Algorithm.RL));
        assertFalse(engine.usesRegularization(Algorithm.RL));
        assertFalse(engine.usesIterations(Algorithm.RL_TV));
        assertFalse(engine.usesRegularization(Algorithm.RL_TV));
    }
}
