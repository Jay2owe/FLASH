package flash.pipeline.naming;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class NamePartsAnalysisRegionLabelTest {

    private static NameParts withRegion(String region) {
        return new NameParts("Exp", "Animal", "LH", region);
    }

    @Test
    public void appendsSectionIndexWhenBaseHasNoTrailingDigits() {
        NameParts np = withRegion("");
        assertEquals("SCN1", np.analysisRegionLabel("SCN", 1));
    }

    @Test
    public void leavesBaseUnchangedWhenTrailingDigitsMatchSectionIndex() {
        NameParts np = withRegion("");
        assertEquals("SCN1", np.analysisRegionLabel("SCN1", 1));
    }

    @Test
    public void appendsSectionIndexForLetterOnlyBase() {
        NameParts np = withRegion("");
        assertEquals("PVN2", np.analysisRegionLabel("PVN", 2));
    }

    @Test
    public void disambiguatesWhenTrailingDigitsDifferFromSectionIndex() {
        NameParts np = withRegion("");
        assertEquals("PVN1_2", np.analysisRegionLabel("PVN1", 2));
    }

    @Test
    public void fallsBackToSectionLabelWhenBothBaseAndRegionAreBlank() {
        NameParts np = withRegion("");
        assertEquals("Section1", np.analysisRegionLabel("", 1));
    }
}
