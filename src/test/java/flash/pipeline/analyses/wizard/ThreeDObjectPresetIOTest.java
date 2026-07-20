package flash.pipeline.analyses.wizard;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.ChannelIdentities;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ThreeDObjectPresetIOTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void boundingBoxTogglesAndThresholdSurviveJsonRoundTrip() throws Exception {
        ThreeDObjectPreset preset = new ThreeDObjectPreset(
                "BB run", "desc", ThreeDObjectPreset.CURRENT_LIBRARY_VERSION,
                false, false, false, false, false, true, 30.0,
                true, false, true, 45.0,
                new ArrayList<String>(), new ArrayList<String>());

        ThreeDObjectPreset restored = ThreeDObjectPreset.fromJson(preset.toJson());

        assertTrue(restored.isDoBBOverlap());
        assertFalse(restored.isDoBBCpc());
        assertTrue(restored.isDoBBVol());
        assertEquals(45.0, restored.getBBColocThresholdPercent(), 0.0);
    }

    @Test
    public void objectIntensityProfilingOptionsSurviveJsonRoundTrip() throws Exception {
        ThreeDObjectPreset preset = new ThreeDObjectPreset(
                "OIP run", "desc", ThreeDObjectPreset.CURRENT_LIBRARY_VERSION,
                true, true, false, false, false, true, 30.0,
                false, false, false, 30.0,
                new ArrayList<String>(), new ArrayList<String>(),
                false, false, true, true, true, true, false,
                "object_voxels", "zscore", 16, 8, 4, 64, 10.0, 35.0);

        ThreeDObjectPreset restored = ThreeDObjectPreset.fromJson(preset.toJson());

        assertFalse(restored.isDoRadialProfile());
        assertFalse(restored.isDoMarginalProfile());
        assertTrue(restored.isDoPrincipalAxisProfile());
        assertTrue(restored.isDoAngularProfile());
        assertTrue(restored.isDoShellColoc());
        assertTrue(restored.isDoWithinBoxCorr());
        assertFalse(restored.isOipGenerateFigures());
        assertEquals("OBJECT_VOXELS", restored.getOipRegion());
        assertEquals("ZSCORE", restored.getOipIntensityNorm());
        assertEquals(16, restored.getOipRadialBins());
        assertEquals(8, restored.getOipAngularBins());
        assertEquals(4, restored.getOipShells());
        assertEquals(64, restored.getOipResampleN());
        assertEquals(10.0, restored.getOipBoxPadPct(), 0.0);
        assertEquals(35.0, restored.getOipRingThresholdPct(), 0.0);
    }

    @Test
    public void legacyPresetJsonWithoutBoundingBoxKeysDefaultsOff() throws Exception {
        // Backward compatibility: presets saved before the BB feature have no BB keys.
        String legacyJson = "{\"name\":\"old\",\"doVolumetric\":true,\"doCpc\":true,"
                + "\"colocThresholdPercent\":30.0}";
        ThreeDObjectPreset restored = ThreeDObjectPreset.fromJson(legacyJson);
        assertFalse(restored.isDoBBOverlap());
        assertFalse(restored.isDoBBCpc());
        assertFalse(restored.isDoBBVol());
        assertEquals(30.0, restored.getBBColocThresholdPercent(), 0.0);
        assertTrue(restored.isDoRadialProfile());
        assertTrue(restored.isDoMarginalProfile());
        assertTrue(restored.isDoPrincipalAxisProfile());
        assertFalse(restored.isDoAngularProfile());
        assertTrue(restored.isOipGenerateFigures());
    }

    @Test
    public void completeChannelStateRoundTripsCanonicallyWithoutLegacyScalars() throws Exception {
        ThreeDObjectPreset original = boundPreset();

        String first = original.toJson();
        ThreeDObjectPreset restored = ThreeDObjectPreset.fromJson(first);
        String second = restored.toJson();

        assertEquals(first, second);
        assertTrue(first.contains("\"schemaVersion\":2"));
        assertTrue(first.contains("\"channelSettings\""));
        assertFalse(restored.toJsonObject().containsKey("colocThresholdPercent"));
        assertFalse(restored.toJsonObject().containsKey("bbColocThresholdPercent"));
        assertFalse(restored.toJsonObject().containsKey("processMarkerHints"));
        assertEquals(2, restored.getChannelSettings().size());
        assertEquals(42.75, restored.getChannelSettings().get(
                "marker:microglia_iba1").getColocThresholdPercent(), 0.0);
    }

    @Test
    public void reorderedAndDisplayRenamedChannelsBindByDurableMarkerIdentity() {
        BinConfig reordered = new BinConfig();
        reordered.channelNames.add("Microglia renamed");
        reordered.channelNames.add("Nuclei renamed");
        ChannelIdentities identities = new ChannelIdentities(Arrays.asList(
                new ChannelIdentities.Entry(0, "microglia_iba1", "complex", true),
                new ChannelIdentities.Entry(1, "nuclei_dapi", "round", false)));

        ThreeDObjectSetupConfig.DerivedConfig derived =
                ThreeDObjectSetupConfig.fromPreset(reordered, identities, boundPreset());

        assertEquals(Double.valueOf(42.75), derived.markerThresholds.get("Microglia renamed"));
        assertEquals(Double.valueOf(11.25), derived.markerThresholds.get("Nuclei renamed"));
        assertEquals(Double.valueOf(52.125), derived.bbThresholds.get("Microglia renamed"));
        assertEquals(Double.valueOf(21.5), derived.bbThresholds.get("Nuclei renamed"));
        assertTrue(derived.processChannels[0]);
        assertFalse(derived.processChannels[1]);
        assertEquals(1, derived.nuclearMarkerIndex);
        assertEquals("Nuclei renamed", derived.clusterMarkerChannel);
        assertTrue(derived.clusterTargets.get("Microglia renamed").booleanValue());
        assertFalse(derived.clusterTargets.get("Nuclei renamed").booleanValue());
    }

    @Test
    public void captureUsesOneCompleteRecordPerStableChannelIdentity() {
        BinConfig cfg = new BinConfig();
        cfg.channelNames.add("DAPI");
        cfg.channelNames.add("IBA1");
        ChannelIdentities identities = new ChannelIdentities(Arrays.asList(
                new ChannelIdentities.Entry(0, "nuclei_dapi", "round", false),
                new ChannelIdentities.Entry(1, "microglia_iba1", "complex", true)));
        Map<String, Double> coloc = new LinkedHashMap<String, Double>();
        coloc.put("DAPI", Double.valueOf(11.25));
        coloc.put("IBA1", Double.valueOf(42.75));
        Map<String, Double> bbColoc = new LinkedHashMap<String, Double>();
        bbColoc.put("DAPI", Double.valueOf(21.5));
        bbColoc.put("IBA1", Double.valueOf(52.125));
        Map<String, Boolean> targets = new LinkedHashMap<String, Boolean>();
        targets.put("DAPI", Boolean.FALSE);
        targets.put("IBA1", Boolean.TRUE);

        Map<String, ThreeDObjectPreset.ChannelSetting> captured =
                ThreeDObjectSetupConfig.captureChannelSettings(
                        cfg, identities, coloc, bbColoc,
                        new boolean[]{false, true}, 0, "DAPI", targets);

        assertEquals(2, captured.size());
        assertTrue(captured.get("marker:nuclei_dapi").isNuclearMarker());
        assertTrue(captured.get("marker:nuclei_dapi").isOverlapMarker());
        assertTrue(captured.get("marker:microglia_iba1").isProcessChannel());
        assertTrue(captured.get("marker:microglia_iba1").isOverlapTarget());

        Map<String, ThreeDObjectPreset.ChannelSetting> markerDisabled =
                ThreeDObjectSetupConfig.captureChannelSettings(
                        cfg, identities, coloc, bbColoc,
                        new boolean[]{false, true}, 0, "None", targets);
        assertFalse(markerDisabled.get("marker:nuclei_dapi").isOverlapMarker());
        assertFalse(markerDisabled.get("marker:microglia_iba1").isOverlapTarget());
    }

    @Test
    public void renamedIdentityAndIdentityCollisionAreRejectedWithoutPositionalGuessing() {
        BinConfig renamed = new BinConfig();
        renamed.channelNames.add("DAPI");
        renamed.channelNames.add("IBA1");
        ChannelIdentities renamedIdentities = new ChannelIdentities(Arrays.asList(
                new ChannelIdentities.Entry(0, "renamed_nuclear_identity", "round", false),
                new ChannelIdentities.Entry(1, "microglia_iba1", "complex", true)));
        try {
            ThreeDObjectSetupConfig.fromPreset(renamed, renamedIdentities, boundPreset());
            fail("Expected an unmatched renamed identity to be rejected.");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("do not match"));
        }

        ChannelIdentities collidingIdentities = new ChannelIdentities(Arrays.asList(
                new ChannelIdentities.Entry(0, "same_marker", "round", false),
                new ChannelIdentities.Entry(1, "same_marker", "complex", true)));
        try {
            ThreeDObjectSetupConfig.fromPreset(renamed, collidingIdentities, boundPreset());
            fail("Expected duplicate durable identities to be rejected.");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("same durable identity"));
        }
    }

    @Test
    public void malformedMixedAndFutureSchemasAreRejected() throws Exception {
        String future = boundPreset().toJson().replace(
                "\"schemaVersion\":2", "\"schemaVersion\":3");
        try {
            ThreeDObjectPreset.fromJson(future);
            fail("Expected future schema rejection.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Unsupported"));
        }

        Map<String, Object> mixed = new LinkedHashMap<String, Object>(
                boundPreset().toJsonObject());
        mixed.put("colocThresholdPercent", Double.valueOf(30.0));
        try {
            ThreeDObjectPreset.fromJsonObject(mixed);
            fail("Expected mixed scalar/channel schema rejection.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("legacy field"));
        }

        Map<String, Object> malformed = new LinkedHashMap<String, Object>(
                boundPreset().toJsonObject());
        @SuppressWarnings("unchecked")
        Map<String, Object> settings =
                (Map<String, Object>) malformed.get("channelSettings");
        @SuppressWarnings("unchecked")
        Map<String, Object> dapi =
                (Map<String, Object>) settings.get("marker:nuclei_dapi");
        dapi.put("processChannel", "yes");
        try {
            ThreeDObjectPreset.fromJsonObject(malformed);
            fail("Expected malformed channel state rejection.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("processChannel"));
        }
    }

    @Test
    public void legacyScalarInputMigratesToExplicitSchemaTwoTemplate() throws Exception {
        ThreeDObjectPreset migrated = ThreeDObjectPreset.fromJson(
                "{\"name\":\"legacy\",\"colocThresholdPercent\":17,"
                        + "\"bbColocThresholdPercent\":29,"
                        + "\"processMarkerHints\":[\"iba1\"],"
                        + "\"nuclearMarkerHints\":[\"dapi\"]}");
        BinConfig cfg = new BinConfig();
        cfg.channelNames.add("DAPI");
        cfg.channelNames.add("IBA1");
        ThreeDObjectSetupConfig.DerivedConfig derived =
                ThreeDObjectSetupConfig.fromPreset(cfg, null, migrated);

        assertEquals(Double.valueOf(17.0), derived.markerThresholds.get("DAPI"));
        assertEquals(Double.valueOf(17.0), derived.markerThresholds.get("IBA1"));
        assertEquals(Double.valueOf(29.0), derived.bbThresholds.get("DAPI"));
        Map<String, Object> canonical = migrated.toJsonObject();
        assertEquals(Integer.valueOf(2), canonical.get("schemaVersion"));
        assertTrue(canonical.containsKey("channelDefaults"));
        assertFalse(canonical.containsKey("colocThresholdPercent"));
    }

    @Test
    public void stockPresetsBootstrapWhenDirectoryIsEmpty() throws Exception {
        ThreeDObjectPresetIO io = new ThreeDObjectPresetIO(temp.newFolder("stock"));

        List<ThreeDObjectPreset> presets = io.listAll();

        assertEquals(6, presets.size());
        assertEquals(Arrays.asList(
                "Full workflow",
                "Count Only",
                "Count + Coloc Standard",
                "Count + Coloc Strict",
                "Count + Coloc Loose",
                "Count + Process Length"), presetNames(presets));
        assertEquals(Arrays.asList(
                "full_workflow.json",
                "count_only.json",
                "count_coloc_standard.json",
                "count_coloc_strict.json",
                "count_coloc_loose.json",
                "count_process_length.json"), io.stockResourceFiles());
        for (ThreeDObjectPreset preset : presets) {
            assertTrue(preset.getName(), preset.isClassicalCentroidFiltering());
        }
        assertTrue(new File(io.presetDirectory(), "full_workflow.json").isFile());
        assertTrue(new File(io.presetDirectory(), "count_only.json").isFile());
        assertTrue(new File(io.presetDirectory(), "count_coloc_loose.json").isFile());
        assertTrue(new File(io.presetDirectory(), "count_process_length.json").isFile());
        assertFalse(new File(io.presetDirectory(), "amyloid_loose.json").isFile());
        assertFalse(new File(io.presetDirectory(), "microglia_processes.json").isFile());
    }

    @Test
    public void roundTripSaveLoadAndDelete() throws Exception {
        ThreeDObjectPresetIO io = new ThreeDObjectPresetIO(temp.newFolder("roundtrip"));
        ThreeDObjectPreset preset = preset("My Object Preset", 42.0);

        io.save(preset);

        assertTrue(new File(temp.getRoot(),
                "roundtrip/FLASH/.settings/Presets/3D Object Analysis/my_object_preset.json").isFile());
        ThreeDObjectPreset loaded = io.load("my_object_preset");
        assertEquals("My Object Preset", loaded.getName());
        assertEquals(42.0, loaded.getColocThresholdPercent(), 0.0001);
        assertFalse(loaded.isDoIntensityColoc());
        assertEquals("microglia", loaded.getProcessMarkerHints().get(0));

        io.delete("My Object Preset");
    }

    @Test
    public void roundTripPreservesIntensityColocalizationFlag() throws Exception {
        ThreeDObjectPresetIO io = new ThreeDObjectPresetIO(temp.newFolder("intensity-coloc"));
        ThreeDObjectPreset preset = new ThreeDObjectPreset(
                "Intensity Coloc",
                "test",
                "1",
                false,
                false,
                true,
                false,
                false,
                true,
                30.0,
                null,
                null);

        io.save(preset);

        ThreeDObjectPreset loaded = io.load("intensity_coloc");
        assertTrue(loaded.isDoIntensityColoc());
    }

    @Test
    public void legacyStockColocPresetJsonMigratesIntensityColocalization() throws Exception {
        String json = "{"
                + "\"name\":\"Count + Coloc Standard\","
                + "\"libraryVersion\":\"1\","
                + "\"doVolumetric\":true,"
                + "\"doCpc\":true,"
                + "\"doIntensityColoc\":false,"
                + "\"extractProcessLength\":false,"
                + "\"runSpatial\":false,"
                + "\"classicalCentroidFiltering\":true,"
                + "\"colocThresholdPercent\":30"
                + "}";

        ThreeDObjectPreset loaded = ThreeDObjectPreset.fromJson(json);

        assertTrue(loaded.isDoIntensityColoc());
        assertFalse(loaded.isRunSpatial());
    }

    @Test
    public void legacyFullWorkflowPresetJsonMigratesSpatialAnalysis() throws Exception {
        String json = "{"
                + "\"name\":\"Full workflow\","
                + "\"libraryVersion\":\"1\","
                + "\"doVolumetric\":true,"
                + "\"doCpc\":true,"
                + "\"doIntensityColoc\":false,"
                + "\"extractProcessLength\":true,"
                + "\"runSpatial\":false,"
                + "\"classicalCentroidFiltering\":true,"
                + "\"colocThresholdPercent\":30"
                + "}";

        ThreeDObjectPreset loaded = ThreeDObjectPreset.fromJson(json);

        assertTrue(loaded.isDoIntensityColoc());
        assertTrue(loaded.isRunSpatial());
    }

    @Test
    public void atomicWriteLeavesOriginalOnFailedMove() throws Exception {
        CrashyThreeDObjectPresetIO io = new CrashyThreeDObjectPresetIO(temp.newFolder("atomic"));
        io.save(preset("Crash Test", 10.0));

        io.crashOnMove = true;
        try {
            io.save(preset("Crash Test", 20.0));
        } catch (IOException expected) {
            // expected
        }

        assertEquals(10.0, io.load("Crash Test").getColocThresholdPercent(), 0.0001);
        File[] leftovers = io.presetDirectory().listFiles((dir, name) -> name.endsWith(".tmp"));
        assertTrue(leftovers == null || leftovers.length == 0);
        String persisted = new String(Files.readAllBytes(new File(io.presetDirectory(), "crash_test.json").toPath()),
                StandardCharsets.UTF_8);
        assertTrue(persisted.contains("\"colocThresholdPercent\":10.0"));
    }

    private static ThreeDObjectPreset preset(String name, double threshold) {
        return new ThreeDObjectPreset(name, "test", "1",
                true, true, true, false, false, threshold,
                Arrays.asList("microglia"), Arrays.asList("nuclei"));
    }

    private static ThreeDObjectPreset boundPreset() {
        Map<String, ThreeDObjectPreset.ChannelSetting> channels =
                new LinkedHashMap<String, ThreeDObjectPreset.ChannelSetting>();
        ThreeDObjectPreset.ChannelSetting dapi = new ThreeDObjectPreset.ChannelSetting(
                "DAPI", "nuclei_dapi", 11.25, 21.5,
                false, true, true, false);
        ThreeDObjectPreset.ChannelSetting iba1 = new ThreeDObjectPreset.ChannelSetting(
                "IBA1", "microglia_iba1", 42.75, 52.125,
                true, false, false, true);
        channels.put(dapi.getIdentityKey(), dapi);
        channels.put(iba1.getIdentityKey(), iba1);
        return new ThreeDObjectPreset(
                "Complete", "heterogeneous", ThreeDObjectPreset.CURRENT_LIBRARY_VERSION,
                true, true, true, true, true, true,
                true, true, true, channels,
                false, true, true, true, true, true, false,
                "object_voxels", "zscore", 16, 8, 4, 64, 10.0, 35.0);
    }

    private static List<String> presetNames(List<ThreeDObjectPreset> presets) {
        List<String> names = new ArrayList<String>();
        for (ThreeDObjectPreset preset : presets) {
            names.add(preset.getName());
        }
        return names;
    }

    private static final class CrashyThreeDObjectPresetIO extends ThreeDObjectPresetIO {
        boolean crashOnMove = false;

        private CrashyThreeDObjectPresetIO(File projectRoot) {
            super(projectRoot);
        }

        @Override
        protected void moveAtomically(File source, File target) throws IOException {
            if (crashOnMove) {
                throw new IOException("simulated crash");
            }
            super.moveAtomically(source, target);
        }
    }
}
