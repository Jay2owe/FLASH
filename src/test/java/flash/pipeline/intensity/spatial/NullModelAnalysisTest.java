package flash.pipeline.intensity.spatial;

import flash.pipeline.analyses.wizard.IntensitySpatialConfig;
import flash.pipeline.runtime.DependencyId;
import ij.ImagePlus;
import ij.measure.Calibration;
import ij.process.FloatProcessor;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NullModelAnalysisTest {
    @Test
    public void uniformRoiProducesFiniteRawOnlyStats() {
        IntensitySpatialResult result = new NullModelAnalysis().measure(context(
                uniformImage(8, 8, 25.0f), null, config(), null));

        assertTrue(Double.isFinite(result.value("Intensity_NullModelP")));
        assertTrue(Double.isFinite(result.value("Intensity_NullModelZ")));
        assertTrue(Double.isFinite(result.value("Intensity_NullModelPass")));
        assertFalse(result.values().containsKey("Intensity_NullModelP_binarized"));
    }

    @Test
    public void zeroImageReturnsNanAndWarns() {
        final List<String> warnings = new ArrayList<String>();
        IntensitySpatialResult result = new NullModelAnalysis().measure(context(
                uniformImage(8, 8, 0.0f), null, config(), warnings));

        assertTrue(Double.isNaN(result.value("Intensity_NullModelP")));
        assertFalse(warnings.isEmpty());
    }

    @Test
    public void runnerFailurePolicyFillsSelectedFamilyWithNan() {
        IntensitySpatialRunner runner = new IntensitySpatialRunner(
                Collections.<IntensitySpatialAnalysis>singletonList(new FailingNullModelAnalysis()));

        IntensitySpatialResult result = runner.measure(context(
                uniformImage(8, 8, 25.0f), null, config(), null));

        assertTrue(Double.isNaN(result.value("Intensity_NullModelP")));
        assertTrue(Double.isNaN(result.value("Intensity_NullModelZ")));
        assertTrue(Double.isNaN(result.value("Intensity_NullModelPass")));
    }

    @Test
    public void runnerLinkageFailureFillsSelectedFamilyWithNan() {
        IntensitySpatialRunner runner = new IntensitySpatialRunner(
                Collections.<IntensitySpatialAnalysis>singletonList(new LinkageFailingNullModelAnalysis()));

        IntensitySpatialResult result = runner.measure(context(
                uniformImage(8, 8, 25.0f), null, config(), null));

        assertTrue(Double.isNaN(result.value("Intensity_NullModelP")));
        assertTrue(Double.isNaN(result.value("Intensity_NullModelZ")));
        assertTrue(Double.isNaN(result.value("Intensity_NullModelPass")));
    }


    private static IntensitySpatialContext context(ImagePlus raw,
                                                   ImagePlus binarized,
                                                   IntensitySpatialConfig config,
                                                   final List<String> warnings) {
        IntensitySpatialContext.WarningSink sink = warnings == null
                ? null
                : new IntensitySpatialContext.WarningSink() {
            @Override
            public void warn(String message) {
                warnings.add(message);
            }
        };
        return new IntensitySpatialContext(config, raw, binarized, 1, null,
                IntensitySpatialOutputMode.BASE, "synthetic", "DAPI", "", sink);
    }

    private static IntensitySpatialConfig config() {
        return IntensitySpatialConfig.builder()
                .enabled(true)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.NULLMODEL)
                .build();
    }

    private static ImagePlus uniformImage(int width, int height, float value) {
        float[] pixels = new float[width * height];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = value;
        }
        ImagePlus image = new ImagePlus("synthetic",
                new FloatProcessor(width, height, pixels, null));
        Calibration calibration = new Calibration();
        calibration.pixelWidth = 1.0;
        calibration.pixelHeight = 1.0;
        calibration.setUnit("um");
        image.setCalibration(calibration);
        return image;
    }

    private static final class FailingNullModelAnalysis implements IntensitySpatialAnalysis {
        @Override
        public IntensitySpatialConfig.AnalysisKey key() {
            return IntensitySpatialConfig.AnalysisKey.NULLMODEL;
        }

        @Override
        public AnalysisValidity validity() {
            return AnalysisValidity.RAW_ONLY;
        }

        @Override
        public EnumSet<IntensitySpatialOutputMode> outputModes() {
            return EnumSet.of(IntensitySpatialOutputMode.BASE);
        }

        @Override
        public Set<DependencyId> dependencyIds() {
            return Collections.emptySet();
        }

        @Override
        public List<String> columns(IntensitySpatialConfig config, boolean binarizedPartner) {
            return new NullModelAnalysis().columns(config, binarizedPartner);
        }

        @Override
        public int estimatedCost() {
            return 1;
        }

        @Override
        public IntensitySpatialResult measure(IntensitySpatialContext context) throws Exception {
            throw new IllegalStateException("synthetic failure");
        }
    }

    private static final class LinkageFailingNullModelAnalysis implements IntensitySpatialAnalysis {
        @Override
        public IntensitySpatialConfig.AnalysisKey key() {
            return IntensitySpatialConfig.AnalysisKey.NULLMODEL;
        }

        @Override
        public AnalysisValidity validity() {
            return AnalysisValidity.RAW_ONLY;
        }

        @Override
        public EnumSet<IntensitySpatialOutputMode> outputModes() {
            return EnumSet.of(IntensitySpatialOutputMode.BASE);
        }

        @Override
        public Set<DependencyId> dependencyIds() {
            return Collections.emptySet();
        }

        @Override
        public List<String> columns(IntensitySpatialConfig config, boolean binarizedPartner) {
            return new NullModelAnalysis().columns(config, binarizedPartner);
        }

        @Override
        public int estimatedCost() {
            return 1;
        }

        @Override
        public IntensitySpatialResult measure(IntensitySpatialContext context) {
            throw new NoClassDefFoundError("synthetic/missing/InnerClass");
        }
    }
}
