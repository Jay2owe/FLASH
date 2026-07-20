package flash.pipeline.deconv;

import flash.pipeline.deconv.engine.Algorithm;
import flash.pipeline.deconv.engine.DeconvSettings;
import flash.pipeline.deconv.psf.PsfModel;
import flash.pipeline.deconv.psf.ScopeModality;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * The shared params-hash field-set producer. Guards that the config-derived subset is exactly the
 * config portion of the full 20-key map (so overlaying it onto a mirror's recorded geometry
 * reproduces the writer's hash), and that a change to any parameter — config or geometry — changes
 * the hash while an irrelevant no-op does not.
 */
public class DeconvParamsHashTest {

    private static DeconvSettings settings() {
        return new DeconvSettings("CLIJ2", Algorithm.RL_TV, PsfModel.GIBSON_LANNI, 20, 0.002);
    }

    private static Map<String, String> fullParams() {
        return DeconvParamsHash.buildParams(
                settings(), ScopeModality.WIDEFIELD,
                /*na*/ 1.40, /*immersionRi*/ 1.515, /*sampleRi*/ 1.44, /*pinhole*/ null,
                /*wavelengthNm*/ 520.0, /*pxX*/ 0.09, /*pxZ*/ 0.30,
                /*sizeX*/ 512, /*sizeY*/ 512, /*sizeZ*/ 40);
    }

    @Test
    public void fullMapHasTheTwentyKeyFieldSet() {
        Map<String, String> params = fullParams();
        assertEquals("field set must be exactly 20 keys", 20, params.size());
        // spot-check the non-geometry keys
        assertEquals("CLIJ2", params.get("engine"));
        assertEquals("RL_TV", params.get("algorithm"));
        assertEquals("20", params.get("iterations"));
        assertEquals("GIBSON_LANNI", params.get("psfModel"));
        assertEquals("WIDEFIELD", params.get("scopeModality"));
        assertEquals("", params.get("pinhole"));          // not confocal
        assertEquals("trim-trailing-blank-v1", params.get("trailingBlankSlicePolicy"));
    }

    @Test
    public void configSubsetMatchesConfigPortionOfFullMap() {
        Map<String, String> full = fullParams();
        Map<String, String> config = DeconvParamsHash.buildConfigParams(
                settings(), ScopeModality.WIDEFIELD,
                1.40, 1.515, 1.44, null, 520.0);
        // Every config key must be present in the full map with an IDENTICAL value: this is the
        // invariant that lets the freshness check overlay the config subset onto recorded geometry
        // and reproduce the writer's hash byte-for-byte.
        for (Map.Entry<String, String> e : config.entrySet()) {
            assertEquals("config key " + e.getKey() + " must match the full map",
                    full.get(e.getKey()), e.getValue());
        }
        assertEquals("config subset carries the 11 config-derived keys", 11, config.size());
    }

    @Test
    public void confocalPinholeDefaultsToOneAiryUnit() {
        Map<String, String> nullPinhole = DeconvParamsHash.buildConfigParams(
                settings(), ScopeModality.CONFOCAL, 1.40, 1.515, 1.44, null, 520.0);
        Map<String, String> onePinhole = DeconvParamsHash.buildConfigParams(
                settings(), ScopeModality.CONFOCAL, 1.40, 1.515, 1.44, Double.valueOf(1.0), 520.0);
        assertEquals("1.000000", nullPinhole.get("pinhole"));
        assertEquals("confocal null pinhole == explicit 1.0",
                onePinhole.get("pinhole"), nullPinhole.get("pinhole"));
    }

    @Test
    public void changingAnyParameterChangesTheHash() {
        String base = DeconvolutionIO.paramsHash(fullParams());

        // config change: iterations
        assertNotEquals(base, DeconvolutionIO.paramsHash(DeconvParamsHash.buildParams(
                new DeconvSettings("CLIJ2", Algorithm.RL_TV, PsfModel.GIBSON_LANNI, 30, 0.002),
                ScopeModality.WIDEFIELD, 1.40, 1.515, 1.44, null, 520.0, 0.09, 0.30, 512, 512, 40)));

        // config change: NA
        assertNotEquals(base, DeconvolutionIO.paramsHash(DeconvParamsHash.buildParams(
                settings(), ScopeModality.WIDEFIELD, 1.45, 1.515, 1.44, null, 520.0,
                0.09, 0.30, 512, 512, 40)));

        // config change: wavelength
        assertNotEquals(base, DeconvolutionIO.paramsHash(DeconvParamsHash.buildParams(
                settings(), ScopeModality.WIDEFIELD, 1.40, 1.515, 1.44, null, 488.0,
                0.09, 0.30, 512, 512, 40)));

        // geometry change: depth
        assertNotEquals(base, DeconvolutionIO.paramsHash(DeconvParamsHash.buildParams(
                settings(), ScopeModality.WIDEFIELD, 1.40, 1.515, 1.44, null, 520.0,
                0.09, 0.30, 512, 512, 41)));

        // geometry change: z-step
        assertNotEquals(base, DeconvolutionIO.paramsHash(DeconvParamsHash.buildParams(
                settings(), ScopeModality.WIDEFIELD, 1.40, 1.515, 1.44, null, 520.0,
                0.09, 0.31, 512, 512, 40)));
    }

    @Test
    public void identicalInputsHashIdentically() {
        assertEquals(DeconvolutionIO.paramsHash(fullParams()),
                DeconvolutionIO.paramsHash(fullParams()));
    }

    @Test
    public void unusedRegularizationFieldIsBlankNotZero() {
        // clij2fft RL consumes iterations but NOT regularization; the unused key must be blanked so a
        // stray numeric default cannot perturb the hash (matches the writers' historical behaviour).
        Map<String, String> rl = DeconvParamsHash.buildConfigParams(
                new DeconvSettings("CLIJ2", Algorithm.RL, PsfModel.GIBSON_LANNI, 20, 0.002),
                ScopeModality.WIDEFIELD, 1.40, 1.515, 1.44, null, 520.0);
        assertTrue("RL consumes iterations", !rl.get("iterations").isEmpty());
        assertEquals("RL does not consume regularization -> blank", "", rl.get("regularization"));
    }

    @Test
    public void paramsHashIncludesStrongSourceAndSourceLocalSeriesIdentity() {
        String content = repeat("12", 32);
        DeconvolutionIO.ArtifactIdentity series0 = new DeconvolutionIO.ArtifactIdentity(
                DeconvolutionIO.ArtifactIdentity.VERSION, 1_000L, content, 0, "Region");
        DeconvolutionIO.ArtifactIdentity series1 = new DeconvolutionIO.ArtifactIdentity(
                DeconvolutionIO.ArtifactIdentity.VERSION, 1_000L, content, 1, "Region");
        DeconvolutionIO.ArtifactIdentity changedContent = new DeconvolutionIO.ArtifactIdentity(
                DeconvolutionIO.ArtifactIdentity.VERSION, 1_000L, repeat("34", 32), 0, "Region");

        Map<String, String> qualified = DeconvParamsHash.withArtifactIdentity(fullParams(), series0);
        assertEquals("source-aware writer adds exactly four immutable identity fields",
                24, qualified.size());
        assertEquals("0", qualified.get("sourceSeriesIndex"));
        assertEquals(content, qualified.get("sourceContentHash"));
        assertNotEquals(DeconvolutionIO.paramsHash(qualified),
                DeconvolutionIO.paramsHash(
                        DeconvParamsHash.withArtifactIdentity(fullParams(), series1)));
        assertNotEquals(DeconvolutionIO.paramsHash(qualified),
                DeconvolutionIO.paramsHash(
                        DeconvParamsHash.withArtifactIdentity(fullParams(), changedContent)));
    }

    @Test
    public void displayRenameDoesNotChangeQualifiedParameterHash() {
        String content = repeat("56", 32);
        DeconvolutionIO.ArtifactIdentity before = new DeconvolutionIO.ArtifactIdentity(
                DeconvolutionIO.ArtifactIdentity.VERSION, 50L, content, 4, "Before");
        DeconvolutionIO.ArtifactIdentity after = new DeconvolutionIO.ArtifactIdentity(
                DeconvolutionIO.ArtifactIdentity.VERSION, 50L, content, 4, "After");
        assertEquals(DeconvolutionIO.paramsHash(
                        DeconvParamsHash.withArtifactIdentity(fullParams(), before)),
                DeconvolutionIO.paramsHash(
                        DeconvParamsHash.withArtifactIdentity(fullParams(), after)));
    }

    private static String repeat(String value, int count) {
        StringBuilder out = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }
}
