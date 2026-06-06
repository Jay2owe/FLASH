package flash.pipeline.naming;

import org.junit.Test;
import static org.junit.Assert.*;

public class ImageNameParserTest {

    // ── parse() strict matches ──

    @Test
    public void parse_standardFormat() {
        NameParts np = ImageNameParser.parse("MyExp-Mouse5_LH_Cortex");
        assertTrue(np.strictMatch);
        assertEquals("MyExp", np.experiment);
        assertEquals("Mouse5", np.animal);
        assertEquals("LH", np.hemisphere);
        assertEquals("Cortex", np.region);
    }

    @Test
    public void parse_rightHemisphere() {
        NameParts np = ImageNameParser.parse("Exp-Animal1_RH_Hippo");
        assertTrue(np.strictMatch);
        assertEquals("RH", np.hemisphere);
        assertEquals("Hippo", np.region);
    }

    @Test
    public void parse_animalWithUnderscore_regionComesFromLastToken() {
        NameParts np = ImageNameParser.parse("Exp-Mouse_5_LH_SCN");
        assertTrue(np.strictMatch);
        assertEquals("Mouse_5", np.animal);
        assertEquals("LH", np.hemisphere);
        assertEquals("SCN", np.region);
    }

    @Test
    public void parse_missingRegion() {
        NameParts np = ImageNameParser.parse("MyExp-Mouse5_LH");
        assertTrue(np.strictMatch);
        assertEquals("MyExp", np.experiment);
        assertEquals("Mouse5", np.animal);
        assertEquals("LH", np.hemisphere);
        assertEquals("", np.region);
    }

    @Test
    public void parse_noDash_withValidHemisphere() {
        NameParts np = ImageNameParser.parse("Mouse5_LH_Cortex");
        assertTrue(np.strictMatch);
        assertEquals("Mouse5", np.animal);
        assertEquals("LH", np.hemisphere);
        assertEquals("Cortex", np.region);
    }

    @Test
    public void parse_caseInsensitiveHemisphere() {
        NameParts np = ImageNameParser.parse("Exp-Mouse5_lh_Cortex");
        assertTrue(np.strictMatch);
        assertEquals("lh", np.hemisphere);
        assertTrue(np.hasKnownHemisphere());
    }

    @Test
    public void parse_invalidHemisphere_fallback() {
        NameParts np = ImageNameParser.parse("MyExp-Mouse5_XX_Cortex");
        assertFalse(np.strictMatch);
        assertNotNull(np.animal);
        assertFalse(np.animal.isEmpty());
    }

    @Test
    public void parse_fifthTokenBecomesCondition() {
        // The convention is Experiment-Animal_Hemisphere_Region[_Condition].
        // A 5th underscore-separated token is parsed as the condition, not
        // treated as noise that breaks the strict match.
        NameParts np = ImageNameParser.parse("MyExp-Mouse5_LH_Cortex_Extra");
        assertTrue(np.strictMatch);
        assertEquals("Mouse5", np.animal);
        assertEquals("LH", np.hemisphere);
        assertEquals("Cortex", np.region);
        assertEquals("Extra", np.condition);
    }

    @Test
    public void parse_multipleDashes_splitsOnLastHyphen() {
        // Multiple plain hyphens (no Bio-Formats " - " separator): split on
        // the LAST hyphen so a hyphenated experiment name is preserved.
        // "MyExp-Sub-Mouse5_LH_Cortex" -> exp="MyExp-Sub", animal="Mouse5".
        NameParts np = ImageNameParser.parse("MyExp-Sub-Mouse5_LH_Cortex");
        assertTrue(np.strictMatch);
        assertEquals("MyExp-Sub", np.experiment);
        assertEquals("Mouse5", np.animal);
        assertEquals("LH", np.hemisphere);
        assertEquals("Cortex", np.region);
    }

    @Test
    public void parse_emptyString_noException() {
        NameParts np = ImageNameParser.parse("");
        assertNotNull(np);
        assertFalse(np.strictMatch);
    }

    @Test
    public void parse_null_noException() {
        NameParts np = ImageNameParser.parse(null);
        assertNotNull(np);
        assertFalse(np.strictMatch);
        assertEquals("", np.animal);
    }

    @Test
    public void parse_bioFormatsTitle_fallback() {
        NameParts np = ImageNameParser.parse("experiment.lif - Series 003");
        assertFalse(np.strictMatch);
        assertEquals("Series 003", np.animal);
    }

    @Test
    public void extractBioFormatsSeriesName_returnsInternalSeriesName() {
        assertEquals("Mouse3_LH_CA1",
                ImageNameParser.extractBioFormatsSeriesName("Experiment_Mouse3.lif - Mouse3_LH_CA1"));
    }

    @Test
    public void buildMultiSeriesDisplayLabel_includesContainerAndSeries() {
        assertEquals("Experiment_Mouse3.lif :: Mouse3_LH_CA1",
                ImageNameParser.buildMultiSeriesDisplayLabel("Experiment_Mouse3.lif", "Mouse3_LH_CA1"));
    }

    @Test
    public void isPreviewSeriesName_detectsPreviewTokens() {
        assertTrue(ImageNameParser.isPreviewSeriesName("Preview"));
        assertTrue(ImageNameParser.isPreviewSeriesName("experiment.lif - Thumbnail"));
        assertFalse(ImageNameParser.isPreviewSeriesName("Mouse3_LH_CA1"));
    }

    @Test
    public void parse_specialCharacters_sanitised() {
        NameParts np = ImageNameParser.parse("Exp-Mouse:5_LH_Cortex");
        // parse: animal="Mouse:5", hemi="LH" -> known hemi -> strict match
        assertTrue(np.strictMatch);
        assertEquals("Mouse:5", np.animal);
    }

    // ── stripExtension ──

    @Test
    public void stripExtension_standard() {
        assertEquals("file", ImageNameParser.stripExtension("file.lif"));
    }

    @Test
    public void stripExtension_omeTif() {
        assertEquals("file.ome", ImageNameParser.stripExtension("file.ome.tif"));
    }

    @Test
    public void stripExtension_noExtension() {
        assertEquals("filename", ImageNameParser.stripExtension("filename"));
    }

    @Test
    public void stripExtension_null() {
        assertNull(ImageNameParser.stripExtension(null));
    }

    @Test
    public void stripExtension_dotAtStart() {
        // ".hidden" has dot at index 0, which is not > 0, so no stripping
        assertEquals(".hidden", ImageNameParser.stripExtension(".hidden"));
    }
}
