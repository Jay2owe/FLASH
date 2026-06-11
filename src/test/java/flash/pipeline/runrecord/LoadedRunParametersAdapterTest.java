package flash.pipeline.runrecord;

import flash.pipeline.analyses.CreateBinFileAnalysis;
import flash.pipeline.analyses.DeconvolutionAnalysis;
import flash.pipeline.analyses.IntensityAnalysisV2;
import flash.pipeline.analyses.LineDistanceAnalysis;
import flash.pipeline.analyses.SpatialAnalysis;
import flash.pipeline.analyses.SplitAndMergeImageChannelsAnalysis;
import flash.pipeline.analyses.ThreeDObjectAnalysis;
import flash.pipeline.analyses.wizard.IntensityPreset;
import flash.pipeline.analyses.wizard.SpatialPreset;
import flash.pipeline.analyses.wizard.ThreeDObjectPreset;
import flash.pipeline.bin.BinConfig;
import flash.pipeline.decontamination.SpectralDecontaminationConfig;
import flash.pipeline.decontamination.wizard.SpectralDecontamPreset;
import flash.pipeline.decontamination.wizard.SpectralDecontaminationSetup;
import flash.pipeline.deconv.wizard.DeconvPreset;
import flash.pipeline.ui.config.ParticleSizeStage;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LoadedRunParametersAdapterTest {

    @Test
    public void presetAdaptersApplyKnownKeysAndReportUnknownKeys() {
        Map<String, Object> threeD = map(
                "doVolumetric", Boolean.TRUE,
                "colocThresholdPercent", Double.valueOf(42.0),
                "unknown", "ignored");
        LoadedRunParameters.PresetLoad<ThreeDObjectPreset> objectLoad =
                LoadedRunParameters.threeDObjectPreset(threeD);
        assertTrue(objectLoad.payload.isDoVolumetric());
        assertEquals(42.0, objectLoad.payload.getColocThresholdPercent(), 0.0001);
        assertAppliedIgnored(objectLoad.result, "doVolumetric", "unknown");

        Map<String, Object> spatial = map(
                "doDistances", Boolean.TRUE,
                "textureClassK", Integer.valueOf(5),
                "unknown", "ignored");
        LoadedRunParameters.PresetLoad<SpatialPreset> spatialLoad =
                LoadedRunParameters.spatialPreset(spatial);
        assertTrue(spatialLoad.payload.isDoDistances());
        assertEquals(5, spatialLoad.payload.getTextureClassK());
        assertAppliedIgnored(spatialLoad.result, "doDistances", "unknown");

        Map<String, Object> intensity = map(
                "defaultMode", "Only bright pixels above threshold (puncta / cells)",
                "thresholds", Collections.singletonMap("1", "123"),
                "unknown", "ignored");
        LoadedRunParameters.PresetLoad<IntensityPreset> intensityLoad =
                LoadedRunParameters.intensityPreset(intensity);
        assertEquals("Only bright pixels above threshold (puncta / cells)",
                intensityLoad.payload.getDefaultMode());
        assertEquals("123", intensityLoad.payload.getThresholds().get("1"));
        assertAppliedIgnored(intensityLoad.result, "defaultMode", "unknown");

        Map<String, Object> deconv = map(
                "engineKey", "clij2-fft",
                "algorithm", "RL",
                "psfModel", "GIBSON_LANNI",
                "iterations", Integer.valueOf(12),
                "regularization", Double.valueOf(0.02),
                "scopeModality", "WIDEFIELD",
                "unknown", "ignored");
        LoadedRunParameters.PresetLoad<DeconvPreset> deconvLoad =
                LoadedRunParameters.deconvPreset(deconv);
        assertEquals(12, deconvLoad.payload.getIterations());
        assertEquals(0.02, deconvLoad.payload.getRegularization(), 0.0001);
        assertAppliedIgnored(deconvLoad.result, "iterations", "unknown");

        Map<String, Object> spectral = map(
                "goal", "create_cleaned_mask",
                "targetChannelIndex", Integer.valueOf(1),
                "bleedThroughChannelIndexes", Arrays.asList(Integer.valueOf(2)),
                "unknown", "ignored");
        LoadedRunParameters.PresetLoad<SpectralDecontamPreset> spectralLoad =
                LoadedRunParameters.spectralPreset(spectral);
        assertEquals(SpectralDecontaminationConfig.Goal.CREATE_CLEANED_MASK,
                spectralLoad.payload.getPayload().getGoal());
        assertEquals(1, spectralLoad.payload.getPayload().getTargetChannelIndex());
        assertAppliedIgnored(spectralLoad.result, "goal", "unknown");
    }

    @Test
    public void spatialAdapterRestoresBoundingBoxTogglesFromRunRecord() {
        Map<String, Object> spatial = map(
                "doBBOverlap", Boolean.TRUE,
                "doBBCpc", Boolean.TRUE,
                "doBBVol", Boolean.FALSE,
                "bbColocThresholdPercent", Double.valueOf(45.0),
                "unknown", "ignored");

        LoadedRunParameters.PresetLoad<SpatialPreset> load =
                LoadedRunParameters.spatialPreset(spatial);

        assertTrue(load.payload.isDoBBOverlap());
        assertTrue(load.payload.isDoBBCpc());
        assertFalse(load.payload.isDoBBVol());
        assertEquals(45.0, load.payload.getBBColocThresholdPercent(), 0.0001);
        // The BB keys must be recognised (applied), not reported as ignored.
        assertTrue(load.result.getAppliedKeys().contains("doBBOverlap"));
        assertTrue(load.result.getAppliedKeys().contains("bbColocThresholdPercent"));
        assertTrue(load.result.getIgnoredKeys().contains("unknown"));

        flash.pipeline.analyses.wizard.SpatialSetupConfig.DerivedConfig derived =
                flash.pipeline.analyses.wizard.SpatialSetupConfig.fromPreset(load.payload);
        assertTrue(derived.doBBOverlap);
        assertTrue(derived.doBBCpc);
        assertFalse(derived.doBBVol);
        assertEquals(45.0, derived.bbColocThresholdPercent, 0.0001);
    }

    @Test
    public void binAndStageAdaptersApplySupportedChannelKeys() {
        Map<String, Object> parameters = map(
                "channel_names", Arrays.asList("DAPI", "Iba1"),
                "channel_colors", Arrays.asList("Blue", "Green"),
                "object_thresholds", Arrays.asList("default", "123"),
                "particle_sizes", Arrays.asList("100-Infinity", "25-400"),
                "segmentation_methods", Arrays.asList("classical", "stardist:model=demo"),
                "filter_presets", Arrays.asList("Default", "Median"),
                "unknown", "ignored");

        LoadedRunParameters.PresetLoad<BinConfig> binLoad = LoadedRunParameters.binConfig(parameters);
        assertEquals("Iba1", binLoad.payload.channelNames.get(1));
        assertEquals("123", binLoad.payload.channelThresholds.get(1));
        assertAppliedIgnored(binLoad.result, "channel_names", "unknown");

        LoadedRunParameters.ValueLoad<String> threshold =
                LoadedRunParameters.objectThreshold(parameters, 1);
        assertEquals("123", threshold.value);

        LoadedRunParameters.ValueLoad<ParticleSizeStage.SizeToken> size =
                LoadedRunParameters.particleSize(parameters, 1);
        assertEquals("25-400", size.value.toToken());

        assertEquals("stardist:model=demo",
                LoadedRunParameters.segmentationMethod(parameters, 1).value);
        assertEquals("Median", LoadedRunParameters.filterPreset(parameters, 1).value);
    }

    @Test
    public void analysisAdaptersIgnoreUnknownKeysWithoutThrowing() {
        Map<String, Object> parameters = map(
                "doVolumetric", Boolean.TRUE,
                "doDistances", Boolean.TRUE,
                "defaultMode", "Whole ROI mean (all pixels, including background)",
                "engineKey", "clij2-fft",
                "algorithm", "RL",
                "psfModel", "GIBSON_LANNI",
                "iterations", Integer.valueOf(8),
                "regularization", Double.valueOf(0.01),
                "scopeModality", "WIDEFIELD",
                "split_merge_use_deconv", Boolean.TRUE,
                "unknown", "ignored");

        assertFalse(new CreateBinFileAnalysis().applyLoadedParameters(parameters)
                .getIgnoredKeys().isEmpty());
        assertFalse(new ThreeDObjectAnalysis().applyLoadedParameters(parameters)
                .getIgnoredKeys().isEmpty());
        assertFalse(new SpatialAnalysis().applyLoadedParameters(parameters)
                .getIgnoredKeys().isEmpty());
        assertFalse(new IntensityAnalysisV2().applyLoadedParameters(parameters)
                .getIgnoredKeys().isEmpty());
        assertFalse(new DeconvolutionAnalysis().applyLoadedParameters(parameters)
                .getIgnoredKeys().isEmpty());
        assertFalse(new SplitAndMergeImageChannelsAnalysis().applyLoadedParameters(parameters)
                .getIgnoredKeys().isEmpty());
        assertFalse(new LineDistanceAnalysis().applyLoadedParameters(parameters)
                .getIgnoredKeys().isEmpty());
        assertFalse(new SpectralDecontaminationSetup(
                new File("."), new BinConfig(), null).applyLoadedParameters(parameters)
                .getIgnoredKeys().isEmpty());
    }

    private static void assertAppliedIgnored(LoadedRunParameters.Result result,
                                             String applied,
                                             String ignored) {
        assertTrue("Expected applied key " + applied, result.getAppliedKeys().contains(applied));
        assertTrue("Expected ignored key " + ignored, result.getIgnoredKeys().contains(ignored));
    }

    private static Map<String, Object> map(Object... entries) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < entries.length; i += 2) {
            out.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return out;
    }
}
