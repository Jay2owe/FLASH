package flash.pipeline.analyses.wizard;

import flash.pipeline.bin.ChannelIdentities;
import flash.pipeline.intelligence.MetadataDiagnostics;
import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SpatialSetupConfigTest {

    @Test
    public void mapsAllSpatialAndMorphologyQuestionCombinations() {
        String[] spatialOptions = new String[]{
                SpatialSetupConfig.SPATIAL_NONE,
                SpatialSetupConfig.SPATIAL_DISTANCE,
                SpatialSetupConfig.SPATIAL_CLUSTERED,
                SpatialSetupConfig.SPATIAL_COLOC,
                SpatialSetupConfig.SPATIAL_TERRITORY,
                SpatialSetupConfig.SPATIAL_HOTSPOTS,
                SpatialSetupConfig.SPATIAL_PHENOTYPES,
                SpatialSetupConfig.SPATIAL_ALL
        };
        String[] morphologyOptions = new String[]{
                SpatialSetupConfig.MORPH_NONE,
                SpatialSetupConfig.MORPH_2D,
                SpatialSetupConfig.MORPH_3D,
                SpatialSetupConfig.MORPH_COMPLEX,
                SpatialSetupConfig.MORPH_POPULATION,
                SpatialSetupConfig.MORPH_TERRITORY,
                SpatialSetupConfig.MORPH_ALL
        };

        MetadataDiagnostics.SeriesInfo zStack = new MetadataDiagnostics.SeriesInfo();
        zStack.sizeZ = 12;

        for (String spatial : spatialOptions) {
            for (String morph : morphologyOptions) {
                Map<String, Object> answers = new LinkedHashMap<String, Object>();
                answers.put("spatial.question", spatial);
                answers.put("morph.question", morph);

                SpatialSetupConfig.DerivedConfig config = SpatialSetupConfig.deriveConfig(
                        identities(), zStack, true, answers);

                if (SpatialSetupConfig.SPATIAL_NONE.equals(spatial)
                        && SpatialSetupConfig.MORPH_NONE.equals(morph)) {
                    assertFalse(config.doDistances);
                    assertFalse(config.doSpatialStats);
                    assertFalse(config.doVolColoc);
                    assertFalse(config.doCpc);
                    assertFalse(config.doVoronoi);
                    assertFalse(config.doHeatmaps);
                    assertFalse(config.doPhenotyping);
                }
                if (SpatialSetupConfig.SPATIAL_COLOC.equals(spatial)) {
                    assertTrue(config.doDistances);
                    assertTrue(config.doVolColoc);
                    assertTrue(config.doCpc);
                }
                if (SpatialSetupConfig.MORPH_POPULATION.equals(morph)) {
                    assertTrue(config.do3DMorphology);
                    assertTrue(config.doCompositeIndices);
                    assertTrue(config.doPopMorphometrics);
                }
                if (SpatialSetupConfig.MORPH_TERRITORY.equals(morph)) {
                    assertTrue(config.do3DMorphology);
                    assertTrue(config.doSpatialMorphometrics);
                    assertTrue(config.doVoronoi);
                }
            }
        }
    }

    @Test
    public void microgliaComplexityAutoChains3dWithNoSpatialToggles() {
        Map<String, Object> answers = new LinkedHashMap<String, Object>();
        answers.put("spatial.question", SpatialSetupConfig.SPATIAL_NONE);
        answers.put("morph.question", SpatialSetupConfig.MORPH_COMPLEX);

        SpatialSetupConfig.DerivedConfig config = SpatialSetupConfig.deriveConfig(
                identities(), null, true, answers);

        assertTrue(config.do3DMorphology);
        assertTrue(config.doCompositeIndices);
        assertFalse(config.doDistances);
        assertFalse(config.doSpatialStats);
        assertFalse(config.doVolColoc);
        assertFalse(config.doCpc);
        assertFalse(config.doVoronoi);
        assertFalse(config.doHeatmaps);
    }

    @Test
    public void exploratorySpatialQuestionDoesNotEnableRipleyByDefault() {
        Map<String, Object> answers = new LinkedHashMap<String, Object>();
        answers.put("spatial.question", SpatialSetupConfig.SPATIAL_ALL);
        answers.put("morph.question", SpatialSetupConfig.MORPH_NONE);

        SpatialSetupConfig.DerivedConfig config = SpatialSetupConfig.deriveConfig(
                identities(), null, true, answers);

        assertTrue(config.doDistances);
        assertFalse(config.doSpatialStats);
        assertTrue(config.doVolColoc);
        assertTrue(config.doCpc);
        assertTrue(config.doVoronoi);
        assertTrue(config.doHeatmaps);
        assertTrue(config.doPhenotyping);
    }

    @Test
    public void explicitRipleySpatialQuestionStillEnablesSpatialStats() {
        Map<String, Object> answers = new LinkedHashMap<String, Object>();
        answers.put("spatial.question", SpatialSetupConfig.SPATIAL_CLUSTERED);
        answers.put("morph.question", SpatialSetupConfig.MORPH_NONE);

        SpatialSetupConfig.DerivedConfig config = SpatialSetupConfig.deriveConfig(
                identities(), null, true, answers);

        assertTrue(config.doDistances);
        assertTrue(config.doSpatialStats);
    }

    private static ChannelIdentities identities() {
        return new ChannelIdentities(Arrays.asList(
                new ChannelIdentities.Entry(0, "microglia_iba1", "complex", true),
                new ChannelIdentities.Entry(1, "amyloid_abeta_pan", "puncta_like", false)));
    }
}
