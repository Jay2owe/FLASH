package flash.pipeline.intensity.spatial;

import flash.pipeline.analyses.wizard.IntensitySpatialConfig;
import flash.pipeline.runtime.DependencyId;
import flash.pipeline.runtime.DependencyService;
import flash.pipeline.runtime.DependencyStatus;
import flash.pipeline.runtime.FeatureDependencyGate;
import ij.ImagePlus;
import ij.gui.Roi;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IntensitySpatialSyntheticValidationTest {
    @Before
    public void installPresentDependencySnapshot() throws Exception {
        installDependencyStatuses(null);
    }

    @After
    public void resetDependencyGate() {
        FeatureDependencyGate.configure(new DependencyService(), null);
        FeatureDependencyGate.setUiMode(false);
    }

    @Test
    public void plannedSameChannelFixturesProduceExpectedSpatialSignals() throws Exception {
        IntensitySpatialConfig compactConfig = IntensitySpatialConfig.builder()
                .enabled(true)
                .tileScalesUm(new double[]{4.0})
                .depthBinWidthUm(4.0)
                .rimDepthUm(4.0)
                .permutations(19)
                .seed(1L)
                .build();

        IntensitySpatialResult uniformPatchiness = new PatchinessAnalysis().measure(context(
                IntensitySpatialSyntheticFixtures.uniformImage(32, 32, 50.0f),
                null, compactConfig));
        IntensitySpatialResult checkerboardPatchiness = new PatchinessAnalysis().measure(context(
                IntensitySpatialSyntheticFixtures.checkerboardImage(32, 32, 4, 5.0f, 105.0f),
                null, compactConfig));
        assertEquals(0.0, uniformPatchiness.value("Intensity_PatchinessCV4"), 1e-9);
        assertEquals(0.0, uniformPatchiness.value("Intensity_Lacunarity4"), 1e-9);
        assertTrue(checkerboardPatchiness.value("Intensity_PatchinessCV4") > 0.7);
        assertTrue(checkerboardPatchiness.value("Intensity_Lacunarity4") > 0.5);

        IntensitySpatialResult uniformHotspot = new HotspotScanAnalysis().measure(context(
                IntensitySpatialSyntheticFixtures.uniformImage(64, 64, 40.0f),
                null, compactConfig));
        IntensitySpatialResult gaussianHotspot = new HotspotScanAnalysis().measure(context(
                IntensitySpatialSyntheticFixtures.gaussianHotspotImage(
                        64, 64, 32.0, 32.0, 5.0, 10.0, 200.0),
                null, compactConfig));
        assertEquals(0.0, uniformHotspot.value(HotspotScanAnalysis.COLUMN_FRACTION), 1e-9);
        assertTrue(gaussianHotspot.value(HotspotScanAnalysis.COLUMN_FRACTION) > 0.0);
        assertTrue(gaussianHotspot.value(HotspotScanAnalysis.COLUMN_MORANS_I)
                > uniformHotspot.value(HotspotScanAnalysis.COLUMN_MORANS_I));

        IntensitySpatialResult rim = new DepthProfileAnalysis().measure(context(
                IntensitySpatialSyntheticFixtures.boundaryRimImage(40, 40, 5, 150.0f, 25.0f),
                null, compactConfig));
        IntensitySpatialResult gradient = new DepthProfileAnalysis().measure(context(
                IntensitySpatialSyntheticFixtures.boundaryGradientImage(40, 40, 10.0f, 5.0f),
                null, compactConfig));
        assertTrue(rim.value("Intensity_RimCoreRatio") > 2.0);
        assertTrue(rim.value("Intensity_EdgeCouplingIdx") > 0.0);
        assertTrue(gradient.value("Intensity_DepthSlope") > 0.0);

        double expectedAngle = 30.0;
        IntensitySpatialResult anisotropy = new Anisotropy2DAnalysis().measure(context(
                IntensitySpatialSyntheticFixtures.stripeImage(96, 96, expectedAngle, 12.0),
                null, compactConfig));
        assertTrue(anisotropy.value(Anisotropy2DAnalysis.COLUMN_COHERENCY) > 0.8);
        assertTrue(angleDifference(anisotropy.value(Anisotropy2DAnalysis.COLUMN_ANGLE),
                expectedAngle) <= 10.0);

        IntensitySpatialResult periodicity = new PeriodicityAnalysis().measure(context(
                IntensitySpatialSyntheticFixtures.stripeImage(96, 96, 0.0, 12.0),
                null, compactConfig));
        assertEquals(12.0, periodicity.value(PeriodicityAnalysis.COLUMN_WAVELENGTH), 0.75);
        assertTrue(angleDifference(periodicity.value(PeriodicityAnalysis.COLUMN_ANGLE), 0.0) <= 8.0);
        assertTrue(periodicity.value(PeriodicityAnalysis.COLUMN_STRIPINESS) > 5.0);

        final List<String> warnings = new ArrayList<String>();
        IntensitySpatialResult empty = new PatchinessAnalysis().measure(new IntensitySpatialContext(
                compactConfig,
                IntensitySpatialSyntheticFixtures.uniformImage(16, 16, 10.0f),
                null, 1, IntensitySpatialSyntheticFixtures.outsideRoi(),
                IntensitySpatialOutputMode.BASE, "empty", "DAPI", "",
                new IntensitySpatialContext.WarningSink() {
                    @Override
                    public void warn(String message) {
                        warnings.add(message);
                    }
                }));
        assertTrue(Double.isNaN(empty.value("Intensity_PatchinessCV4")));
        assertFalse(warnings.isEmpty());
    }

    @Test
    public void plannedPairFixturesProduceExpectedCrossChannelSignals() throws Exception {
        IntensitySpatialConfig pairConfig = IntensitySpatialConfig.builder()
                .enabled(true)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.CROSSMARK)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.ENTROPY_MI)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.DISTANCE_SHELL)
                .shellWidthUm(4.0)
                .shellCount(4)
                .permutations(9)
                .seed(1L)
                .build();

        IntensitySpatialSyntheticFixtures.PairFixture colocated =
                IntensitySpatialSyntheticFixtures.colocatedPair(32, 32);
        IntensitySpatialSyntheticFixtures.PairFixture anticorrelated =
                IntensitySpatialSyntheticFixtures.antiCorrelatedPair(32, 32);
        CrossMark2DAnalysis crossmark = new CrossMark2DAnalysis();
        EntropyMiAnalysis entropy = new EntropyMiAnalysis();

        IntensitySpatialResult colocatedCrossmark = crossmark.measure(pairContext(pairConfig, colocated));
        IntensitySpatialResult antiCrossmark = crossmark.measure(pairContext(pairConfig, anticorrelated));
        assertTrue(colocatedCrossmark.value("DAPI_Pearson_mCherry")
                > antiCrossmark.value("DAPI_Pearson_mCherry"));
        assertTrue(colocatedCrossmark.value("DAPI_MandersM1_mCherry_binarized") > 0.9);
        assertTrue(colocatedCrossmark.value("DAPI_MandersM2_mCherry_binarized") > 0.9);

        IntensitySpatialResult colocatedMi = entropy.measure(pairContext(pairConfig, colocated));
        assertTrue(colocatedMi.value("DAPI_NMI_mCherry") > 0.8);
        assertTrue(Double.isFinite(colocatedMi.value("DAPI_MIPeakStrength_mCherry")));

        IntensitySpatialSyntheticFixtures.PairFixture shell =
                IntensitySpatialSyntheticFixtures.shellGradientPair(40, 40);
        IntensitySpatialResult shellResult = new DistanceShell2DAnalysis().measure(
                pairContext(pairConfig, shell));
        assertTrue(shellResult.value("DAPI_DistShellSlope_mCherry") > 0.0);
        assertTrue(shellResult.value("DAPI_DistShell0to4_mCherry")
                < shellResult.value("DAPI_DistShell12to16_mCherry"));
    }

    @Test
    public void plannedZStackFixturesSuppressSmallStacksAndMeasureNative3dStacks() {
        IntensitySpatialConfig nativeConfig = IntensitySpatialConfig.builder()
                .enabled(true)
                .native3dEnabled(true)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.ANISOTROPY_3D)
                .build();
        IntensitySpatialRunner runner = IntensitySpatialRunner.standard();

        ImagePlus smallStack = IntensitySpatialSyntheticFixtures.stackImage(
                "small-stack", 24, 24, 3, new IntensitySpatialSyntheticFixtures.SliceValue() {
                    @Override
                    public double at(int x, int y, int z) {
                        return 10.0 + x + z;
                    }
                });
        IntensitySpatialResult suppressed = runner.measure(new IntensitySpatialContext(
                nativeConfig, smallStack, null, 1, null,
                IntensitySpatialOutputMode.NATIVE_3D, "small-stack", "DAPI", "", null));
        assertTrue(suppressed.values().isEmpty());

        ImagePlus nativeStack = IntensitySpatialSyntheticFixtures.stackImage(
                "native-stack", 48, 48, 6, new IntensitySpatialSyntheticFixtures.SliceValue() {
                    @Override
                    public double at(int x, int y, int z) {
                        return 100.0 + 80.0 * Math.sin(2.0 * Math.PI * x / 8.0);
                    }
                });
        IntensitySpatialResult nativeResult = runner.measure(new IntensitySpatialContext(
                nativeConfig, nativeStack, null, 1, null,
                IntensitySpatialOutputMode.NATIVE_3D, "native-stack", "DAPI", "", null));
        assertTrue(nativeResult.values().containsKey(Anisotropy3DAnalysis.COLUMN_COHERENCY));
        assertTrue(nativeResult.value(Anisotropy3DAnalysis.COLUMN_COHERENCY) > 0.7);
    }

    private static IntensitySpatialContext context(ImagePlus raw,
                                                   ImagePlus binarized,
                                                   IntensitySpatialConfig config) {
        return new IntensitySpatialContext(config, raw, binarized, 1, null,
                IntensitySpatialOutputMode.BASE, "synthetic", "DAPI", "", null);
    }

    private static IntensitySpatialPairContext pairContext(
            IntensitySpatialConfig config,
            IntensitySpatialSyntheticFixtures.PairFixture fixture) {
        return new IntensitySpatialPairContext(config,
                fixture.source,
                fixture.sourceMask,
                fixture.sourceMask,
                fixture.partner,
                fixture.partnerMask,
                fixture.partnerMask,
                1,
                (Roi) null,
                IntensitySpatialOutputMode.BASE,
                "synthetic",
                "DAPI",
                "mCherry",
                "",
                null);
    }

    private static double angleDifference(double actual, double expected) {
        double diff = Math.abs(actual - expected) % 180.0;
        return diff > 90.0 ? 180.0 - diff : diff;
    }

    private static void installDependencyStatuses(final DependencyId missing) throws Exception {
        final EnumMap<DependencyId, DependencyStatus> statuses =
                new EnumMap<DependencyId, DependencyStatus>(DependencyId.class);
        for (DependencyId id : DependencyId.values()) {
            statuses.put(id, id == missing
                    ? DependencyStatus.missing(id.name() + " missing")
                    : DependencyStatus.present(id.name() + " present"));
        }

        Class<?> providerType = Class.forName(
                "flash.pipeline.runtime.DependencyService$StatusSnapshotProvider");
        Object provider = Proxy.newProxyInstance(
                providerType.getClassLoader(),
                new Class<?>[]{providerType},
                (proxyObject, method, args) -> {
                    if ("snapshot".equals(method.getName())) {
                        return new EnumMap<DependencyId, DependencyStatus>(statuses);
                    }
                    if ("toString".equals(method.getName())) {
                        return "synthetic dependency status provider";
                    }
                    if ("hashCode".equals(method.getName())) {
                        return Integer.valueOf(System.identityHashCode(proxyObject));
                    }
                    if ("equals".equals(method.getName())) {
                        return Boolean.valueOf(proxyObject == args[0]);
                    }
                    return null;
                });
        Constructor<DependencyService> ctor = DependencyService.class.getDeclaredConstructor(providerType);
        ctor.setAccessible(true);
        FeatureDependencyGate.configure(ctor.newInstance(provider), null);
        FeatureDependencyGate.setUiMode(false);
    }
}
