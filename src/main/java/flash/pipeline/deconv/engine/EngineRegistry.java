package flash.pipeline.deconv.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class EngineRegistry {

    private static final List<DeconvolutionEngine> DEFAULT_ENGINES =
            Collections.unmodifiableList(Arrays.<DeconvolutionEngine>asList(
                    new Clij2FftEngine(),
                    new DeconvolutionLab2Engine(),
                    new IterativeDeconvolve3DEngine()
            ));

    private static volatile List<DeconvolutionEngine> testEngines;

    private EngineRegistry() {}

    public static List<DeconvolutionEngine> all() {
        List<DeconvolutionEngine> source = testEngines == null ? DEFAULT_ENGINES : testEngines;
        return Collections.unmodifiableList(new ArrayList<DeconvolutionEngine>(source));
    }

    public static List<DeconvolutionEngine> available() {
        List<DeconvolutionEngine> available = new ArrayList<DeconvolutionEngine>();
        for (DeconvolutionEngine engine : all()) {
            if (engine.isAvailable()) {
                available.add(engine);
            }
        }
        return Collections.unmodifiableList(available);
    }

    public static DeconvolutionEngine byKey(String key) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("key is required.");
        }
        for (DeconvolutionEngine engine : all()) {
            if (engine.key().equals(key)) {
                return engine;
            }
        }
        throw new IllegalArgumentException("Unknown deconvolution engine key: " + key);
    }

    static void setEnginesForTest(List<DeconvolutionEngine> engines) {
        if (engines == null) {
            testEngines = null;
            return;
        }
        testEngines = new ArrayList<DeconvolutionEngine>(engines);
    }

    static void resetForTest() {
        testEngines = null;
    }
}
