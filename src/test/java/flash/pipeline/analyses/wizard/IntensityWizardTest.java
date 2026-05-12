package flash.pipeline.analyses.wizard;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.ChannelIdentities;
import org.junit.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IntensityWizardTest {

    @Test
    public void markerIdentitiesDriveDefaultModes() {
        IntensityWizard.DerivedConfig derived = IntensityWizard.deriveConfig(
                config(), identities(), new LinkedHashMap<String, Object>(), Arrays.asList("LH ROIs"));

        assertEquals(IntensityWizard.MODE_WHOLE_ROI_MEAN, derived.measurementModes[1]);
        assertEquals(IntensityWizard.MODE_WHOLE_ROI_MEAN, derived.measurementModes[2]);
        assertEquals(IntensityWizard.MODE_THRESHOLD_MEAN, derived.measurementModes[3]);
        assertEquals("40", derived.thresholds[3]);
    }

    @Test
    public void modeAnswersDriveBinarizationAndThresholds() {
        Map<String, Object> answers = new LinkedHashMap<String, Object>();
        answers.put("mode.1", IntensityWizard.MODE_WHOLE_ROI_MEAN);
        answers.put("mode.2", IntensityWizard.MODE_THRESHOLD_MEAN);
        answers.put("mode.3", IntensityWizard.MODE_THRESHOLD_MEAN);
        answers.put("threshold.2", "45");
        answers.put("threshold.3", "55");

        IntensityWizard.DerivedConfig derived = IntensityWizard.deriveConfig(
                config(), identities(), answers, Arrays.asList("LH ROIs", "RH ROIs"));

        assertFalse(derived.binarization[0]);
        assertTrue(derived.binarization[1]);
        assertTrue(derived.binarization[2]);
        assertEquals("45", derived.thresholds[1]);
        assertEquals("55", derived.thresholds[2]);
    }

    @Test
    public void neunMaskChoiceMapsToChannelIndex() {
        Map<String, Object> answers = new LinkedHashMap<String, Object>();
        answers.put("mask.choice", "NeuN");

        IntensityWizard.DerivedConfig derived = IntensityWizard.deriveConfig(
                config(), identities(), answers, Arrays.asList("LH ROIs"));

        assertEquals(2, derived.maskChannelIndex);
        assertEquals("NeuN", derived.maskChannelChoice);
    }

    @Test
    public void spatialConfigAnswersAreValidatedAgainstIntensityBinarization() {
        Map<String, Object> answers = new LinkedHashMap<String, Object>();
        answers.put("mode.1", IntensityWizard.MODE_WHOLE_ROI_MEAN);
        answers.put("mode.2", IntensityWizard.MODE_WHOLE_ROI_MEAN);
        answers.put("mode.3", IntensityWizard.MODE_WHOLE_ROI_MEAN);
        answers.put("mode.4", IntensityWizard.MODE_WHOLE_ROI_MEAN);
        answers.put("intensity.spatial.config", IntensitySpatialConfig.builder()
                .enabled(true)
                .enabledAnalyses(EnumSet.of(
                        IntensitySpatialConfig.AnalysisKey.PATCHINESS,
                        IntensitySpatialConfig.AnalysisKey.DISTANCE_SHELL))
                .build());

        IntensityWizard.DerivedConfig derived = IntensityWizard.deriveConfig(
                config(), identities(), answers, Arrays.asList("LH ROIs"));

        assertTrue(derived.spatialConfig.getEnabledAnalyses()
                .contains(IntensitySpatialConfig.AnalysisKey.PATCHINESS));
        assertFalse(derived.spatialConfig.getEnabledAnalyses()
                .contains(IntensitySpatialConfig.AnalysisKey.DISTANCE_SHELL));
        assertFalse("Validation must not auto-enable binarisation", derived.binarization[0]);
    }

    private static BinConfig config() {
        BinConfig cfg = new BinConfig();
        cfg.channelNames.add("DAPI");
        cfg.channelNames.add("IBA1");
        cfg.channelNames.add("NeuN");
        cfg.channelNames.add("Synaptophysin");
        cfg.channelIntensityThresholds.add("10");
        cfg.channelIntensityThresholds.add("20");
        cfg.channelIntensityThresholds.add("30");
        cfg.channelIntensityThresholds.add("40");
        return cfg;
    }

    private static ChannelIdentities identities() {
        return new ChannelIdentities(Arrays.asList(
                new ChannelIdentities.Entry(0, "nuclei_dapi", "round", false),
                new ChannelIdentities.Entry(1, "microglia_iba1", "complex", true),
                new ChannelIdentities.Entry(2, "neurons_neun", "complex", true),
                new ChannelIdentities.Entry(3, "synapse_syp", "puncta_like", false)));
    }
}
