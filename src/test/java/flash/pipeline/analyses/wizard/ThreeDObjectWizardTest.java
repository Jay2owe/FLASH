package flash.pipeline.analyses.wizard;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.ChannelIdentities;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ThreeDObjectWizardTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void mapsIntentCombinationsAcrossStrictnessOptions() {
        BinConfig cfg = dapiIba1AbetaConfig();
        ChannelIdentities identities = dapiIba1AbetaIdentities();

        boolean[][] intents = new boolean[][]{
                {false, false, false},
                {true, false, false},
                {false, true, false},
                {false, false, true}
        };
        String[] strictness = new String[]{
                ThreeDObjectWizard.STRICTNESS_LOOSE,
                ThreeDObjectWizard.STRICTNESS_STANDARD,
                ThreeDObjectWizard.STRICTNESS_STRICT
        };
        double[] thresholds = new double[]{10.0, 30.0, 60.0};

        for (boolean[] intent : intents) {
            for (int i = 0; i < strictness.length; i++) {
                Map<String, Object> answers = new LinkedHashMap<String, Object>();
                answers.put("intent.coloc", Boolean.valueOf(intent[0]));
                answers.put("intent.process", Boolean.valueOf(intent[1]));
                answers.put("intent.spatial", Boolean.valueOf(intent[2]));
                answers.put("coloc.strictness", strictness[i]);

                ThreeDObjectWizard.DerivedConfig derived = ThreeDObjectWizard.deriveConfig(
                        cfg, identities, answers, null);

                assertEquals(intent[0], derived.doVolumetric);
                assertEquals(intent[0], derived.doCpc);
                assertFalse(derived.doIntensityColoc);
                assertEquals(intent[1], derived.extractProcessLength);
                assertEquals(intent[2], derived.runSpatial);
                assertEquals(thresholds[i], derived.thresholdPercent, 0.0001);
            }
        }
    }

    @Test
    public void looseStrictnessIsDefaultWhenAmyloidIsPresent() {
        ThreeDObjectWizard.DerivedConfig derived = ThreeDObjectWizard.deriveConfig(
                dapiIba1AbetaConfig(), dapiIba1AbetaIdentities(), colocAnswers(), null);

        assertTrue(derived.doVolumetric);
        assertTrue(derived.doCpc);
        assertFalse(derived.doIntensityColoc);
        assertEquals(10.0, derived.thresholdPercent, 0.0001);
        assertEquals(Double.valueOf(10.0), derived.markerThresholds.get("DAPI"));
        assertEquals(Double.valueOf(10.0), derived.markerThresholds.get("IBA1"));
        assertFalse(derived.extractProcessLength);
        assertFalse(derived.runSpatial);
    }

    @Test
    public void intensityColocalizationIntentCanBeEnabledWithoutVolumetricColoc() {
        Map<String, Object> answers = new LinkedHashMap<String, Object>();
        answers.put("intent.intensityColoc", Boolean.TRUE);

        ThreeDObjectWizard.DerivedConfig derived = ThreeDObjectWizard.deriveConfig(
                dapiIba1AbetaConfig(), dapiIba1AbetaIdentities(), answers, null);

        assertFalse(derived.doVolumetric);
        assertFalse(derived.doCpc);
        assertTrue(derived.doIntensityColoc);
    }

    @Test
    public void processChannelsInheritFromMarkerIdentities() {
        Map<String, Object> answers = new LinkedHashMap<String, Object>();
        answers.put("intent.process", Boolean.TRUE);

        ThreeDObjectWizard.DerivedConfig derived = ThreeDObjectWizard.deriveConfig(
                dapiIba1AbetaConfig(), dapiIba1AbetaIdentities(), answers, null);

        assertEquals(0, derived.nuclearMarkerIndex);
        assertFalse(derived.processChannels[0]);
        assertTrue(derived.processChannels[1]);
        assertFalse(derived.processChannels[2]);
    }

    @Test
    public void centroidRoiFilteringDefaultsOn() {
        ThreeDObjectWizard.DerivedConfig derived = ThreeDObjectWizard.deriveConfig(
                dapiIba1AbetaConfig(), dapiIba1AbetaIdentities(),
                new LinkedHashMap<String, Object>(), null);

        assertTrue(derived.classicalCentroidFiltering);
    }

    @Test
    public void fallbackPresetLoadDefaultsCentroidRoiFilteringOn() {
        ThreeDObjectWizard.DerivedConfig derived = ThreeDObjectWizard.fromPreset(
                dapiIba1AbetaConfig(), dapiIba1AbetaIdentities(), null);

        assertTrue(derived.classicalCentroidFiltering);
    }

    @Test
    public void stockPresetsKeepCentroidRoiFilteringOnAfterLoad() throws Exception {
        ThreeDObjectPresetIO io = new ThreeDObjectPresetIO(temp.newFolder("stock-presets"));
        List<ThreeDObjectPreset> presets = io.listAll();

        assertEquals(6, presets.size());
        assertEquals(Arrays.asList(
                "Full workflow",
                "Count Only",
                "Count + Coloc Standard",
                "Count + Coloc Strict",
                "Count + Coloc Loose",
                "Count + Process Length"), presetNames(presets));
        for (ThreeDObjectPreset preset : presets) {
            ThreeDObjectWizard.DerivedConfig derived = ThreeDObjectWizard.fromPreset(
                    dapiIba1AbetaConfig(), dapiIba1AbetaIdentities(), preset);

            assertTrue(preset.getName(), derived.classicalCentroidFiltering);
        }
    }

    @Test
    public void explicitPresetWithCentroidRoiFilteringOffIsRespected() {
        ThreeDObjectPreset preset = new ThreeDObjectPreset(
                "User preset",
                "Explicitly disables centroid ROI filtering",
                ThreeDObjectPreset.CURRENT_LIBRARY_VERSION,
                false,
                false,
                false,
                false,
                false,
                30.0,
                null,
                null);

        ThreeDObjectWizard.DerivedConfig derived = ThreeDObjectWizard.fromPreset(
                dapiIba1AbetaConfig(), dapiIba1AbetaIdentities(), preset);

        assertFalse(derived.classicalCentroidFiltering);
    }

    @Test
    public void twoChannelColocalizationDoesNotRequirePairSelection() {
        BinConfig cfg = new BinConfig();
        cfg.channelNames.add("DAPI");
        cfg.channelNames.add("IBA1");
        ChannelIdentities identities = new ChannelIdentities(Arrays.asList(
                new ChannelIdentities.Entry(0, "nuclei_dapi", "round", false),
                new ChannelIdentities.Entry(1, "microglia_iba1", "complex", true)));

        ThreeDObjectWizard.DerivedConfig derived = ThreeDObjectWizard.deriveConfig(
                cfg, identities, colocAnswers(), null);

        assertTrue(derived.primaryPairs.contains("1-2"));
    }

    private static Map<String, Object> colocAnswers() {
        Map<String, Object> answers = new LinkedHashMap<String, Object>();
        answers.put("intent.coloc", Boolean.TRUE);
        return answers;
    }

    private static List<String> presetNames(List<ThreeDObjectPreset> presets) {
        List<String> names = new ArrayList<String>();
        for (ThreeDObjectPreset preset : presets) {
            names.add(preset.getName());
        }
        return names;
    }

    static BinConfig dapiIba1AbetaConfig() {
        BinConfig cfg = new BinConfig();
        cfg.channelNames.add("DAPI");
        cfg.channelNames.add("IBA1");
        cfg.channelNames.add("Abeta");
        return cfg;
    }

    static ChannelIdentities dapiIba1AbetaIdentities() {
        return new ChannelIdentities(Arrays.asList(
                new ChannelIdentities.Entry(0, "nuclei_dapi", "round", false),
                new ChannelIdentities.Entry(1, "microglia_iba1", "complex", true),
                new ChannelIdentities.Entry(2, "amyloid_abeta_pan", "puncta_like", false)));
    }
}
