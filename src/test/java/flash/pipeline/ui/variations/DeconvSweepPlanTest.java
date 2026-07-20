package flash.pipeline.ui.variations;

import flash.pipeline.deconv.engine.Algorithm;
import flash.pipeline.deconv.engine.DeconvParams;
import flash.pipeline.deconv.engine.DeconvSettings;
import flash.pipeline.deconv.engine.DeconvolutionEngine;
import flash.pipeline.deconv.engine.DeconvolutionException;
import flash.pipeline.deconv.psf.PsfModel;

import ij.ImagePlus;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

public class DeconvSweepPlanTest {

    @Test
    public void dropsCombosForEnginesThatDoNotSupportTheResolvedAlgorithm() {
        Map<ParameterKey, ParameterValueList> values =
                new LinkedHashMap<ParameterKey, ParameterValueList>();
        values.put(DeconvParameterId.ENGINE, ParameterValueList.ofStrings("DL2", "CLIJ2"));
        values.put(DeconvParameterId.ALGORITHM, ParameterValueList.ofStrings("RL", "TIKHONOV"));
        values.put(DeconvParameterId.PSF_MODEL, ParameterValueList.ofStrings("GIBSON_LANNI"));
        ParameterSweep sweep = new ParameterSweep(ParameterSweep.Method.DECONVOLUTION,
                values, null, "DAPI", "hash");

        DeconvSweepPlan plan = DeconvSweepPlan.forSweep(sweep, base(),
                lookup(
                        engine("DL2",
                                Arrays.asList(Algorithm.RL, Algorithm.TIKHONOV),
                                Arrays.asList(PsfModel.values())),
                        engine("CLIJ2",
                                Arrays.asList(Algorithm.RL),
                                Arrays.asList(PsfModel.values()))));

        assertEquals(3, plan.executableCount());
        assertEquals(1, plan.skippedCount());
        for (ParameterCombo combo : plan.executableCombos()) {
            DeconvSettings settings = DeconvComboSettings.resolve(combo, base());
            assertFalse("CLIJ2/TIKHONOV should be pre-filtered",
                    "CLIJ2".equals(settings.engineKey())
                            && settings.algorithm() == Algorithm.TIKHONOV);
        }
    }

    @Test
    public void dropsCombosForPsfModelsUnsupportedByTheResolvedEngine() {
        Map<ParameterKey, ParameterValueList> values =
                new LinkedHashMap<ParameterKey, ParameterValueList>();
        values.put(DeconvParameterId.ENGINE, ParameterValueList.ofStrings("MODEL_LIMITED"));
        values.put(DeconvParameterId.ALGORITHM, ParameterValueList.ofStrings("RL"));
        values.put(DeconvParameterId.PSF_MODEL, ParameterValueList.ofStrings(
                "GIBSON_LANNI", "BORN_WOLF", "DOUGHERTY_THEORETICAL"));
        ParameterSweep sweep = new ParameterSweep(ParameterSweep.Method.DECONVOLUTION,
                values, null, "DAPI", "hash");

        DeconvSweepPlan plan = DeconvSweepPlan.forSweep(sweep,
                base().withEngineKey("MODEL_LIMITED"),
                lookup(engine("MODEL_LIMITED",
                        Arrays.asList(Algorithm.RL),
                        Arrays.asList(PsfModel.GIBSON_LANNI))));

        assertEquals(1, plan.executableCount());
        assertEquals(2, plan.skippedCount());
        DeconvSettings settings = DeconvComboSettings.resolve(
                plan.executableCombos().get(0), base().withEngineKey("MODEL_LIMITED"));
        assertEquals(PsfModel.GIBSON_LANNI, settings.psfModel());
    }

    @Test
    public void dropsCombosWithUnresolvedCategoricalTokens() {
        Map<ParameterKey, ParameterValueList> values =
                new LinkedHashMap<ParameterKey, ParameterValueList>();
        values.put(DeconvParameterId.ENGINE, ParameterValueList.ofStrings("DL2"));
        values.put(DeconvParameterId.ALGORITHM, ParameterValueList.ofStrings("RL", "not-an-algorithm"));
        values.put(DeconvParameterId.PSF_MODEL, ParameterValueList.ofStrings(
                "GIBSON_LANNI", "not-a-psf"));
        ParameterSweep sweep = new ParameterSweep(ParameterSweep.Method.DECONVOLUTION,
                values, null, "DAPI", "hash");

        DeconvSweepPlan plan = DeconvSweepPlan.forSweep(sweep, base(),
                lookup(engine("DL2",
                        Arrays.asList(Algorithm.RL),
                        Arrays.asList(PsfModel.values()))));

        assertEquals(1, plan.executableCount());
        assertEquals(3, plan.skippedCount());
        DeconvSettings settings = DeconvComboSettings.resolve(plan.executableCombos().get(0), base());
        assertEquals(Algorithm.RL, settings.algorithm());
        assertEquals(PsfModel.GIBSON_LANNI, settings.psfModel());
    }

    @Test
    public void boundedPlanRejectsOversizedRawSweepBeforePlanningCombos() {
        Map<ParameterKey, ParameterValueList> values =
                new LinkedHashMap<ParameterKey, ParameterValueList>();
        values.put(DeconvParameterId.ENGINE, ParameterValueList.ofStrings("DL2"));
        values.put(DeconvParameterId.ALGORITHM, ParameterValueList.ofStrings("RL"));
        values.put(DeconvParameterId.PSF_MODEL, ParameterValueList.ofStrings("GIBSON_LANNI"));
        values.put(DeconvParameterId.ITERATIONS, new ParameterValueList(integerValues(401)));
        ParameterSweep sweep = new ParameterSweep(ParameterSweep.Method.DECONVOLUTION,
                values, null, "DAPI", "hash");

        try {
            DeconvSweepPlan.forSweep(sweep, base(),
                    lookup(engine("DL2",
                            Arrays.asList(Algorithm.RL),
                            Arrays.asList(PsfModel.values()))),
                    400L);
            fail("Expected oversized raw sweep to be rejected before planning");
        } catch (DeconvSweepPlan.TooManyCombinationsException expected) {
            assertEquals(401L, expected.rawCount());
            assertEquals(400L, expected.maxRawCombos());
        }
    }

    private static DeconvSettings base() {
        return new DeconvSettings("DL2", Algorithm.RL,
                PsfModel.GIBSON_LANNI, 15, 0.01d);
    }

    private static List<Object> integerValues(int count) {
        List<Object> out = new ArrayList<Object>();
        for (int i = 0; i < count; i++) {
            out.add(Integer.valueOf(i + 1));
        }
        return out;
    }

    private static DeconvSweepPlan.EngineLookup lookup(final DeconvolutionEngine... engines) {
        return new DeconvSweepPlan.EngineLookup() {
            @Override public DeconvolutionEngine byKey(String key) {
                for (int i = 0; i < engines.length; i++) {
                    if (engines[i].key().equals(key)) {
                        return engines[i];
                    }
                }
                throw new IllegalArgumentException("Unknown engine: " + key);
            }
        };
    }

    private static DeconvolutionEngine engine(String key,
                                              List<Algorithm> algorithms,
                                              List<PsfModel> psfModels) {
        return new FakeEngine(key, algorithms, psfModels);
    }

    private static final class FakeEngine implements DeconvolutionEngine {
        private final String key;
        private final List<Algorithm> algorithms;
        private final List<PsfModel> psfModels;

        FakeEngine(String key, List<Algorithm> algorithms, List<PsfModel> psfModels) {
            this.key = key;
            this.algorithms = algorithms;
            this.psfModels = psfModels;
        }

        @Override public String key() {
            return key;
        }

        @Override public String displayName() {
            return key;
        }

        @Override public String description() {
            return key;
        }

        @Override public boolean isAvailable() {
            return true;
        }

        @Override public List<Algorithm> supportedAlgorithms() {
            return algorithms;
        }

        @Override public List<PsfModel> supportedPsfModels() {
            return psfModels;
        }

        @Override
        public ImagePlus deconvolve(ImagePlus stack, ImagePlus psf, DeconvParams params)
                throws DeconvolutionException {
            throw new UnsupportedOperationException("not used");
        }
    }
}
