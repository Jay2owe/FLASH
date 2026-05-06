package flash.pipeline.analyses.wizard;

import flash.pipeline.bin.ChannelIdentities;
import flash.pipeline.intelligence.MetadataDiagnostics;
import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SpatialAnalysisWizardTest {

    @Test
    public void mapsAllSpatialAndMorphologyQuestionCombinations() {
        String[] spatialOptions = new String[]{
                SpatialAnalysisWizard.SPATIAL_NONE,
                SpatialAnalysisWizard.SPATIAL_DISTANCE,
                SpatialAnalysisWizard.SPATIAL_CLUSTERED,
                SpatialAnalysisWizard.SPATIAL_COLOC,
                SpatialAnalysisWizard.SPATIAL_TERRITORY,
                SpatialAnalysisWizard.SPATIAL_HOTSPOTS,
                SpatialAnalysisWizard.SPATIAL_PHENOTYPES,
                SpatialAnalysisWizard.SPATIAL_ALL
        };
        String[] morphologyOptions = new String[]{
                SpatialAnalysisWizard.MORPH_NONE,
                SpatialAnalysisWizard.MORPH_2D,
                SpatialAnalysisWizard.MORPH_3D,
                SpatialAnalysisWizard.MORPH_COMPLEX,
                SpatialAnalysisWizard.MORPH_POPULATION,
                SpatialAnalysisWizard.MORPH_TERRITORY,
                SpatialAnalysisWizard.MORPH_ALL
        };

        MetadataDiagnostics.SeriesInfo zStack = new MetadataDiagnostics.SeriesInfo();
        zStack.sizeZ = 12;

        for (String spatial : spatialOptions) {
            for (String morph : morphologyOptions) {
                Map<String, Object> answers = new LinkedHashMap<String, Object>();
                answers.put("spatial.question", spatial);
                answers.put("morph.question", morph);

                SpatialAnalysisWizard.DerivedConfig config = SpatialAnalysisWizard.deriveConfig(
                        identities(), zStack, true, answers);

                if (SpatialAnalysisWizard.SPATIAL_NONE.equals(spatial)
                        && SpatialAnalysisWizard.MORPH_NONE.equals(morph)) {
                    assertFalse(config.doDistances);
                    assertFalse(config.doSpatialStats);
                    assertFalse(config.doVolColoc);
                    assertFalse(config.doCpc);
                    assertFalse(config.doVoronoi);
                    assertFalse(config.doHeatmaps);
                    assertFalse(config.doPhenotyping);
                }
                if (SpatialAnalysisWizard.SPATIAL_COLOC.equals(spatial)) {
                    assertTrue(config.doDistances);
                    assertTrue(config.doVolColoc);
                    assertTrue(config.doCpc);
                }
                if (SpatialAnalysisWizard.MORPH_POPULATION.equals(morph)) {
                    assertTrue(config.do3DMorphology);
                    assertTrue(config.doCompositeIndices);
                    assertTrue(config.doPopMorphometrics);
                }
                if (SpatialAnalysisWizard.MORPH_TERRITORY.equals(morph)) {
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
        answers.put("spatial.question", SpatialAnalysisWizard.SPATIAL_NONE);
        answers.put("morph.question", SpatialAnalysisWizard.MORPH_COMPLEX);

        SpatialAnalysisWizard.DerivedConfig config = SpatialAnalysisWizard.deriveConfig(
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
    public void amyloidAndMicrogliaDefaultToContactAnd3dShape() {
        SpatialAnalysisWizard wizard = new SpatialAnalysisWizard(
                flash.pipeline.ui.wizard.WizardFlow.MainPanelBinding.NULL,
                identities(),
                null,
                true,
                false,
                true);

        Map<String, Object> answers = wizard.currentAnswers();
        assertTrue(SpatialAnalysisWizard.SPATIAL_COLOC.equals(answers.get("spatial.question")));
        assertTrue(SpatialAnalysisWizard.MORPH_3D.equals(answers.get("morph.question")));
    }

    private static ChannelIdentities identities() {
        return new ChannelIdentities(Arrays.asList(
                new ChannelIdentities.Entry(0, "microglia_iba1", "complex", true),
                new ChannelIdentities.Entry(1, "amyloid_abeta_pan", "puncta_like", false)));
    }
}
