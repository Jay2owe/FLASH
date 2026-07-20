package flash.pipeline.cli;

import flash.pipeline.bin.BinField;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CLIArgumentParserTokenCoverageTest {

    @Test
    public void launchDetectionDefersMalformedTopLevelDirectoryToStrictParser() {
        assertTrue(CLIArgumentParser.hasCliOptions("dir=[unclosed"));
        assertTrue(CLIArgumentParser.hasCliOptions("config_dir=\"unclosed"));
        assertNull(CLIArgumentParser.parse("dir=[unclosed"));
    }

    @Test
    public void launchDetectionIgnoresDirectoryLookalikeInsideMalformedBracketedValue() {
        assertTrue("control: a real top-level assignment is detected",
                CLIArgumentParser.hasCliOptions("note=[text] dir=[C:/data]"));
        assertFalse(CLIArgumentParser.hasCliOptions(
                "note=[dry run dir=[C:/not-a-cli"));
    }

    @Test
    public void launchDetectionIgnoresDirectoryLookalikeInsideMalformedQuotedValue() {
        assertFalse(CLIArgumentParser.hasCliOptions(
                "note=\"dry run dir=[C:/not-a-cli"));
    }

    @Test
    public void launchDetectionKeepsMidBareQuotesLiteralBeforeRealDirectory() {
        assertTrue(CLIArgumentParser.hasCliOptions(
                "note=O'Brien dir=[C:/data]"));
        assertTrue(CLIArgumentParser.hasCliOptions(
                "note=abc\"def dir=[C:/data]"));
    }

    @Test
    public void independentlySpecifiedCodecCorpusIsLossless() {
        String controlValue = "line1\nline2\t" + Character.toString((char) 1);
        String[][] cases = {
                {"plain", "plain"},
                {"", "[]"},
                {"two words", "[two words]"},
                {"C:\\", "C:\\"},
                {"\\\\server\\share\\study\\", "\\\\server\\share\\study\\"},
                {"C:\\Program Files\\FLASH\\", "[C:\\Program Files\\FLASH\\]"},
                {"\\\\server\\Lab Share\\study\\", "[\\\\server\\Lab Share\\study\\]"},
                {"a=b,c:d;%", "a=b,c:d;%"},
                {"O'Brien", "O'Brien"},
                {"abc\"def", "abc\"def"},
                {"\"leading quote", "[\"leading quote]"},
                {"quote \" and apostrophe '", "[quote \" and apostrophe ']"},
                {"left[inner]right", "[!FLASH1!left~[inner~]right]"},
                {controlValue, "[!FLASH1!line1~nline2~t~u0001]"},
                {"\u65E5\u672C\u8A9E \u0394 \u00E9 microglia",
                        "[\u65E5\u672C\u8A9E \u0394 \u00E9 microglia]"},
                {"!FLASH1! tagged ~ value", "[!FLASH1!!FLASH1! tagged ~~ value]"}
        };

        for (String[] testCase : cases) {
            String value = testCase[0];
            String expectedToken = testCase[1];
            assertEquals("canonical token for " + printable(value), expectedToken,
                    CLIArgumentParser.encodeValue(value));
            assertEquals("decoded value for " + printable(value), value,
                    CLIArgumentParser.getValue(
                            "before=ok field=" + expectedToken + " after=ok", "field"));
        }
    }

    @Test
    public void legacyValuesPreserveOrdinaryBackslashesAndOnlyDecodeClosingBracketEscape() {
        assertEquals("\\\\server\\share\\study\\",
                CLIArgumentParser.getValue(
                        "field=[\\\\server\\share\\study\\] after=ok", "field"));
        assertEquals("C:\\", CLIArgumentParser.getValue("field=[C:\\]", "field"));
        assertEquals("folder ] name", CLIArgumentParser.getValue(
                "field=[folder \\] name] after=ok", "field"));
        assertEquals("a\\\\b", CLIArgumentParser.getValue("field=[a\\\\b]", "field"));
    }

    @Test
    public void emptyValueRemainsDistinctFromAbsentField() {
        assertEquals("", CLIArgumentParser.getValue("present=[]", "present"));
        assertEquals("", CLIArgumentParser.getValue("present=", "present"));
        assertNull(CLIArgumentParser.getValue("other=value", "present"));
    }

    @Test
    public void everyConfigValueUsesCodecAtFinalSerializationBoundary() {
        CLIConfig config = new CLIConfig();
        config.directory = "C:\\__flash_missing_codec__\\study [\u0394]\\";
        config.repfig.customLabelTemplate = "line 1 [stain]\nline 2 \"quoted\"";
        config.repfig.saveName = "Mean intensity \u0394";
        config.aggregate.presetName = "Per-region subdivision";

        String serialized = CLIArgumentParser.serialize(config);
        assertEquals(config.directory, CLIArgumentParser.getValue(serialized, "dir"));
        assertEquals(config.repfig.customLabelTemplate,
                CLIArgumentParser.getValue(serialized, "repfig.label_text"));
        assertEquals(config.repfig.saveName,
                CLIArgumentParser.getValue(serialized, "repfig.save_name"));
        assertTrue(serialized, serialized.contains("aggregate.preset=[Per-region subdivision]"));

        CLIConfig reparsed = CLIArgumentParser.parse(serialized);
        assertNotNull(reparsed);
        assertEquals(config.directory, reparsed.getDirectory());
        assertEquals(config.repfig.customLabelTemplate,
                reparsed.getRepfig().getCustomLabelTemplate());
        assertEquals(config.repfig.saveName, reparsed.getRepfig().getSaveName());
    }

    @Test
    public void malformedGrammarReportsFieldAndAbsolutePosition() {
        assertMalformed("alpha=\"unclosed", "alpha", "unclosed");
        assertMalformed("alpha=[unclosed", "alpha", "missing closing");
        assertMalformed("alpha=[!FLASH1!value~", "alpha", "dangling");
        assertMalformed("alpha=[!FLASH1!value~q]", "alpha", "invalid");
        assertMalformed("alpha=bare]value", "alpha", "malformed bracket");
        assertMalformed("al\"pha=value", "al", "quote");
        assertMalformed("al'pha=value", "al", "quote");
        assertMalformed("alpha=[closed]tail", "alpha", "unexpected character");
    }

    @Test
    public void malformedFieldCausesWholeCliInvocationToFailClosed() {
        assertNull(CLIArgumentParser.parse(
                "dir=[C:/data] repfig.label_text=\"unclosed"));
    }

    @Test
    public void enhancedClassicalEncodedMorphRoundTripsIdentically() {
        String token = "enhanced_classical:thresh=120:minSize=200:maxSize=10000:"
                + "morph=sphericity%3E%3D0.6%2Celongation%3C%3D2.0";

        assertEquals(token, serializedChannelSegmentation(token));
    }

    @Test
    public void enhancedClassicalUnencodedMorphFormatsToEncodedCanonicalToken() {
        String input = "enhanced_classical:thresh=120:minSize=200:maxSize=10000:"
                + "morph=sphericity>=0.6,elongation<=2.0";
        String canonical = "enhanced_classical:thresh=120:minSize=200:maxSize=10000:"
                + "morph=sphericity%3E%3D0.6%2Celongation%3C%3D2.0";

        assertEquals(canonical, serializedChannelSegmentation(input));
    }

    @Test
    public void stardistModelAndFilterTokenRoundTrips() {
        String token = "stardist:0.5:0.3:linking=5.0:gapClosing=5.0:"
                + "area=20-2000:quality=0.2:intensity=50:model=user_microglia_iba1_v3";

        assertEquals(token, serializedChannelSegmentation(token));
    }

    @Test
    public void cellposeModelTokenRoundTrips() {
        String token = "cellpose:30.0:0.4:0.0:gpu=true:chan2=0:model=user_iba1_v3";

        assertEquals(token, serializedChannelSegmentation(token));
    }

    @Test
    public void trainedRfTokenRoundTrips() {
        String token = "trained_rf:projectModel_microglia_v1:base=classical";

        assertEquals(token, serializedChannelSegmentation(token));
    }

    @Test
    public void percentEncodedMorphSurvivesMacroOptionsRoundTrip() {
        String enhanced = "enhanced_classical:thresh=120:minSize=200:maxSize=10000:"
                + "morph=sphericity%3E%3D0.6%2Celongation%3C%3D2.0";
        String methods = "classical," + enhanced;

        CLIConfig parsed = CLIArgumentParser.parse("dir=[C:/data] segmentation_methods=[" + methods + "]");
        assertNotNull(parsed);

        String serialized = CLIArgumentParser.serialize(parsed);
        assertEquals(methods, CLIArgumentParser.getValue(serialized, "segmentation_methods"));

        CLIConfig reparsed = CLIArgumentParser.parse(serialized);
        assertNotNull(reparsed);
        assertEquals(methods, reparsed.getBinFieldValue(BinField.SEGMENTATION_METHODS));
    }

    private static String serializedChannelSegmentation(String token) {
        CLIConfig parsed = CLIArgumentParser.parse("dir=[C:/data] bin.channel1_segmentation=" + token);
        assertNotNull(parsed);
        assertEquals(token, parsed.getBin().getSegmentationMethods().get(Integer.valueOf(0)));

        String serialized = CLIArgumentParser.serialize(parsed);
        String value = CLIArgumentParser.getValue(serialized, "bin.channel1_segmentation");
        assertNotNull(value);
        return value;
    }

    private static void assertMalformed(String options, String field, String detail) {
        try {
            CLIArgumentParser.getValue(options, field);
            fail("Expected malformed option to fail: " + printable(options));
        } catch (RuntimeException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("field '" + field + "'"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("position "));
            assertTrue(expected.getMessage(),
                    expected.getMessage().toLowerCase().contains(detail.toLowerCase()));
        }
    }

    private static String printable(String value) {
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
