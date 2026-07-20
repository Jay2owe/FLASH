package flash.pipeline.deconv.engine;

import ij.ImagePlus;
import org.junit.After;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class EngineRegistryTest {

    @After
    public void tearDown() {
        EngineRegistry.resetForTest();
    }

    @Test
    public void allReturnsThreeDefaultEnginesInSpecifiedOrder() {
        List<DeconvolutionEngine> engines = EngineRegistry.all();

        assertEquals(3, engines.size());
        assertEquals("CLIJ2", engines.get(0).key());
        assertEquals("DL2", engines.get(1).key());
        assertEquals("IterativeDeconvolve3D", engines.get(2).key());
    }

    @Test
    public void availableFiltersUsingEachEngineAvailabilityFlag() {
        FakeEngine availableA = new FakeEngine("A", true);
        FakeEngine unavailable = new FakeEngine("B", false);
        FakeEngine availableC = new FakeEngine("C", true);
        EngineRegistry.setEnginesForTest(Arrays.<DeconvolutionEngine>asList(availableA, unavailable, availableC));

        List<DeconvolutionEngine> available = EngineRegistry.available();

        assertEquals(2, available.size());
        assertSame(availableA, available.get(0));
        assertSame(availableC, available.get(1));
    }

    @Test
    public void byKeyResolvesKnownKeysAndRejectsUnknownOnes() {
        FakeEngine known = new FakeEngine("Known", true);
        EngineRegistry.setEnginesForTest(Collections.<DeconvolutionEngine>singletonList(known));

        assertSame(known, EngineRegistry.byKey("Known"));

        try {
            EngineRegistry.byKey("Missing");
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Unknown keys must throw IllegalArgumentException.");
    }

    private static final class FakeEngine implements DeconvolutionEngine {
        private final String key;
        private final boolean available;

        private FakeEngine(String key, boolean available) {
            this.key = key;
            this.available = available;
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public String displayName() {
            return key;
        }

        @Override
        public String description() {
            return key;
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public List<Algorithm> supportedAlgorithms() {
            return Collections.<Algorithm>emptyList();
        }

        @Override
        public ImagePlus deconvolve(ImagePlus stack, ImagePlus psf, DeconvParams params) {
            return null;
        }
    }
}
