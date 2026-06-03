package flash.pipeline.analyses;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CreateBinFileAnalysisValidationTest {

    @Test
    public void channelNamesRequireText() {
        assertTrue(CreateBinFileAnalysis.isValidChannelName("DAPI"));
        assertTrue(CreateBinFileAnalysis.isValidChannelName(" IBA1 "));

        assertFalse(CreateBinFileAnalysis.isValidChannelName(""));
        assertFalse(CreateBinFileAnalysis.isValidChannelName("   "));
        assertFalse(CreateBinFileAnalysis.isValidChannelName(null));
    }

    @Test
    public void channelCountRequiresPositiveFiniteNumber() {
        assertTrue(CreateBinFileAnalysis.isValidChannelCountToken("1"));
        assertTrue(CreateBinFileAnalysis.isValidChannelCountToken("3.0"));

        assertFalse(CreateBinFileAnalysis.isValidChannelCountToken("0"));
        assertFalse(CreateBinFileAnalysis.isValidChannelCountToken("-1"));
        assertFalse(CreateBinFileAnalysis.isValidChannelCountToken("Infinity"));
        assertFalse(CreateBinFileAnalysis.isValidChannelCountToken("abc"));
    }

    @Test
    public void displayRangeMatchesExistingParser() {
        assertTrue(CreateBinFileAnalysis.isValidDisplayRangeToken("None"));
        assertTrue(CreateBinFileAnalysis.isValidDisplayRangeToken("0-255"));
        assertTrue(CreateBinFileAnalysis.isValidDisplayRangeToken("10.5-200.25"));

        assertFalse(CreateBinFileAnalysis.isValidDisplayRangeToken(""));
        assertFalse(CreateBinFileAnalysis.isValidDisplayRangeToken("0"));
        assertFalse(CreateBinFileAnalysis.isValidDisplayRangeToken("low-high"));
    }

    @Test
    public void sizeRangeMatchesExistingParser() {
        assertTrue(CreateBinFileAnalysis.isValidSizeRangeToken("100-Infinity"));
        assertTrue(CreateBinFileAnalysis.isValidSizeRangeToken("0-500"));
        assertTrue(CreateBinFileAnalysis.isValidSizeRangeToken("25.5-100.5"));

        assertFalse(CreateBinFileAnalysis.isValidSizeRangeToken(""));
        assertFalse(CreateBinFileAnalysis.isValidSizeRangeToken("100"));
        assertFalse(CreateBinFileAnalysis.isValidSizeRangeToken("100-many"));
    }

    @Test
    public void thresholdTokenAllowsDefaultOrFiniteNumber() {
        assertTrue(CreateBinFileAnalysis.isValidThresholdToken("default"));
        assertTrue(CreateBinFileAnalysis.isValidThresholdToken("Default"));
        assertTrue(CreateBinFileAnalysis.isValidThresholdToken("123"));
        assertTrue(CreateBinFileAnalysis.isValidThresholdToken("12.5"));

        assertFalse(CreateBinFileAnalysis.isValidThresholdToken(""));
        assertFalse(CreateBinFileAnalysis.isValidThresholdToken("Infinity"));
        assertFalse(CreateBinFileAnalysis.isValidThresholdToken("auto"));
    }

    @Test
    public void numericTokenRequiresFiniteNumber() {
        assertTrue(CreateBinFileAnalysis.isValidNumericToken("0"));
        assertTrue(CreateBinFileAnalysis.isValidNumericToken("-0.5"));
        assertTrue(CreateBinFileAnalysis.isValidNumericToken("12.25"));

        assertFalse(CreateBinFileAnalysis.isValidNumericToken(""));
        assertFalse(CreateBinFileAnalysis.isValidNumericToken("NaN"));
        assertFalse(CreateBinFileAnalysis.isValidNumericToken("abc"));
    }
}
