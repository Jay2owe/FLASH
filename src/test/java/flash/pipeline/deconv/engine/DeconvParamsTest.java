package flash.pipeline.deconv.engine;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DeconvParamsTest {

    @Test
    public void builderAppliesDefaultsWhenOnlyAlgorithmIsProvided() {
        DeconvParams params = DeconvParams.builder(Algorithm.RL).build();

        assertEquals(Algorithm.RL, params.getAlgorithm());
        assertEquals(DeconvParams.DEFAULT_ITERATIONS, params.getIterations());
        assertEquals(DeconvParams.DEFAULT_REGULARIZATION, params.getRegularization(), 0.0);
        assertEquals(DeconvParams.DEFAULT_EDGE_HANDLING, params.getEdgeHandling());
    }

    @Test(expected = IllegalArgumentException.class)
    public void builderRejectsMissingAlgorithm() {
        DeconvParams.builder().build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void builderRejectsIterationCountsBelowRange() {
        DeconvParams.builder(Algorithm.RL).iterations(0).build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void builderRejectsIterationCountsAboveRange() {
        DeconvParams.builder(Algorithm.RL).iterations(101).build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void builderRejectsRegularizationOutsideRange() {
        DeconvParams.builder(Algorithm.RL_TV).regularization(0.2).build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void builderRejectsNullEdgeHandling() {
        DeconvParams.builder(Algorithm.RL).edgeHandling(null).build();
    }
}
