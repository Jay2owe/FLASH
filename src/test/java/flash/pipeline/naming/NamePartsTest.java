package flash.pipeline.naming;

import org.junit.Test;
import static org.junit.Assert.*;

public class NamePartsTest {

    // ── hasKnownHemisphere ──

    @Test
    public void hasKnownHemisphere_LH() {
        assertTrue(new NameParts("", "", "LH", "").hasKnownHemisphere());
    }

    @Test
    public void hasKnownHemisphere_RH() {
        assertTrue(new NameParts("", "", "RH", "").hasKnownHemisphere());
    }

    @Test
    public void hasKnownHemisphere_lowercase() {
        assertTrue(new NameParts("", "", "lh", "").hasKnownHemisphere());
        assertTrue(new NameParts("", "", "rh", "").hasKnownHemisphere());
    }

    @Test
    public void hasKnownHemisphere_empty() {
        assertFalse(new NameParts("", "", "", "").hasKnownHemisphere());
    }

    @Test
    public void hasKnownHemisphere_invalid() {
        assertFalse(new NameParts("", "", "XX", "").hasKnownHemisphere());
    }

    // ── displayLabel ──

    @Test
    public void displayLabel_strictAllParts() {
        NameParts np = new NameParts("Exp", "Mouse5", "LH", "Cortex", true);
        assertEquals("Mouse5_LH_Cortex", np.displayLabel());
    }

    @Test
    public void displayLabel_strictNoRegion() {
        NameParts np = new NameParts("Exp", "Mouse5", "LH", "", true);
        assertEquals("Mouse5_LH", np.displayLabel());
    }

    @Test
    public void displayLabel_strictNoHemisphere() {
        NameParts np = new NameParts("Exp", "Mouse5", "", "Cortex", true);
        assertEquals("Mouse5_Cortex", np.displayLabel());
    }

    @Test
    public void displayLabel_nonStrict_returnsAnimal() {
        NameParts np = new NameParts("Exp", "my_image_title", "LH", "Cortex", false);
        assertEquals("my_image_title", np.displayLabel());
    }

    // ── hemiRegionSuffix ──

    @Test
    public void hemiRegionSuffix_bothPresent() {
        NameParts np = new NameParts("", "", "LH", "Cortex", true);
        assertEquals("LH_Cortex", np.hemiRegionSuffix());
    }

    @Test
    public void hemiRegionSuffix_hemiOnly() {
        NameParts np = new NameParts("", "", "LH", "", true);
        assertEquals("LH", np.hemiRegionSuffix());
    }

    @Test
    public void hemiRegionSuffix_regionOnly() {
        NameParts np = new NameParts("", "", "", "Cortex", true);
        assertEquals("Cortex", np.hemiRegionSuffix());
    }

    @Test
    public void hemiRegionSuffix_neitherPresent() {
        NameParts np = new NameParts("", "", "", "", true);
        assertEquals("", np.hemiRegionSuffix());
    }

    // ── fileSuffix ──

    @Test
    public void csvRegion_strict_returnsParsedRegionOnly() {
        NameParts np = new NameParts("Exp", "Mouse5", "LH", "SCN", true);
        assertEquals("SCN", np.csvRegion());
    }

    @Test
    public void csvRegion_nonStrict_returnsBlank() {
        NameParts np = new NameParts("Exp", "fallback_name", "", "SCN", false);
        assertEquals("", np.csvRegion());
    }

    @Test
    public void analysisRegionLabel_withHemisphereAndRoiSet() {
        NameParts np = new NameParts("Exp", "Mouse5", "LH", "SCN", true);
        assertEquals("SCN1", np.analysisRegionLabel("SCN", 1));
    }

    @Test
    public void analysisRegionLabel_withoutHemisphere() {
        NameParts np = new NameParts("Exp", "Mouse5", "", "SCN", true);
        assertEquals("SCN2", np.analysisRegionLabel("SCN", 2));
    }

    @Test
    public void analysisRegionLabel_fallsBackToParsedRegion() {
        NameParts np = new NameParts("Exp", "Mouse5", "RH", "PVN", true);
        assertEquals("PVN3", np.analysisRegionLabel("", 3));
    }

    @Test
    public void analysisRegionLabel_doesNotDuplicateExistingNumericSuffix() {
        NameParts np = new NameParts("Exp", "Mouse5", "LH", "SCN1", true);
        assertEquals("SCN1", np.analysisRegionLabel("", 1));
    }

    @Test
    public void analysisRegionLabel_sectionFallback() {
        NameParts np = new NameParts("Exp", "Mouse5", "LH", "", true);
        assertEquals("Section4", np.analysisRegionLabel("", 4));
    }

    @Test
    public void fileSuffix_withHemiRegion() {
        NameParts np = new NameParts("Exp", "Mouse5", "LH", "Cortex", true);
        assertEquals("LH_Cortex", np.fileSuffix());
    }

    @Test
    public void fileSuffix_emptyHemiRegion_fallsBackToAnimal() {
        NameParts np = new NameParts("Exp", "my_image", "", "", false);
        assertEquals("my_image", np.fileSuffix());
    }

    @Test
    public void fileSuffix_neverEmpty_withAnimal() {
        NameParts np = new NameParts("", "fallback_name", "", "", false);
        assertFalse(np.fileSuffix().isEmpty());
        assertEquals("fallback_name", np.fileSuffix());
    }

    @Test
    public void fileSuffix_hemiOnly() {
        NameParts np = new NameParts("Exp", "Mouse5", "RH", "", true);
        assertEquals("RH", np.fileSuffix());
    }

    // ── 4-arg constructor defaults strictMatch to true ──

    @Test
    public void fourArgConstructor_defaultsToStrictMatch() {
        NameParts np = new NameParts("Exp", "Mouse5", "LH", "Cortex");
        assertTrue(np.strictMatch);
    }
}
