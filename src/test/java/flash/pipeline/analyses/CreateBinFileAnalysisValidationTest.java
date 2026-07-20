package flash.pipeline.analyses;

import flash.pipeline.bin.ChannelConfig;
import flash.pipeline.bin.ChannelConfigIO;
import flash.pipeline.naming.ChannelFilenameCodec;
import org.junit.Test;

import java.util.Arrays;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Unit coverage for Set Up Configuration live-validation predicates. */
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
        assertFalse(CreateBinFileAnalysis.isValidChannelCountToken("0.5"));
        assertFalse(CreateBinFileAnalysis.isValidChannelCountToken("1.5"));
        assertFalse(CreateBinFileAnalysis.isValidChannelCountToken("65"));
        assertFalse(CreateBinFileAnalysis.isValidChannelCountToken("Infinity"));
        assertFalse(CreateBinFileAnalysis.isValidChannelCountToken("abc"));
    }

    @Test
    public void displayRangeMatchesExistingParser() {
        assertTrue(CreateBinFileAnalysis.isValidDisplayRangeToken("None"));
        assertTrue(CreateBinFileAnalysis.isValidDisplayRangeToken("0-255"));
        assertTrue(CreateBinFileAnalysis.isValidDisplayRangeToken("10.5-200.25"));
        assertTrue(CreateBinFileAnalysis.isValidDisplayRangeToken("auto:0.35"));

        assertFalse(CreateBinFileAnalysis.isValidDisplayRangeToken(""));
        assertFalse(CreateBinFileAnalysis.isValidDisplayRangeToken("0"));
        assertFalse(CreateBinFileAnalysis.isValidDisplayRangeToken("low-high"));
        assertFalse(CreateBinFileAnalysis.isValidDisplayRangeToken("auto:bad"));
    }

    @Test
    public void sizeRangeMatchesExistingParser() {
        assertTrue(CreateBinFileAnalysis.isValidSizeRangeToken("100-Infinity"));
        assertTrue(CreateBinFileAnalysis.isValidSizeRangeToken("0-500"));
        assertTrue(CreateBinFileAnalysis.isValidSizeRangeToken("25.5-100.5"));

        assertFalse(CreateBinFileAnalysis.isValidSizeRangeToken(""));
        assertFalse(CreateBinFileAnalysis.isValidSizeRangeToken("100"));
        assertFalse(CreateBinFileAnalysis.isValidSizeRangeToken("100-many"));
        assertFalse(CreateBinFileAnalysis.isValidSizeRangeToken("200-100"));
        assertFalse(CreateBinFileAnalysis.isValidSizeRangeToken("0-1e999"));
        assertFalse(CreateBinFileAnalysis.isValidSizeRangeToken("0-NaN"));
    }

    @Test
    public void thresholdTokenAllowsDefaultOrFiniteNumber() {
        assertTrue(CreateBinFileAnalysis.isValidThresholdToken("default"));
        assertTrue(CreateBinFileAnalysis.isValidThresholdToken("Default"));
        assertTrue(CreateBinFileAnalysis.isValidThresholdToken("123"));
        assertTrue(CreateBinFileAnalysis.isValidThresholdToken("12.5"));

        assertFalse(CreateBinFileAnalysis.isValidThresholdToken(""));
        assertFalse(CreateBinFileAnalysis.isValidThresholdToken("-1"));
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

    @Test
    public void sharedCompletionValidatorHasIdenticalGuiAndHeadlessSemantics() {
        ChannelConfig cfg = validConfig("DAPI", "IBA1");
        cfg.channels.get(1).segmentationMethod = "classical:otsu";
        ChannelConfigIO.ValidationContext context = new ChannelConfigIO.ValidationContext(
                Arrays.asList(new ChannelConfigIO.SourceSeries(0, 2, 12)),
                Arrays.asList(Boolean.TRUE, Boolean.TRUE), true);

        ChannelConfigIO.ValidationResult direct = ChannelConfigIO.validateForCompletion(cfg, context);
        ChannelConfigIO.ValidationResult publication =
                CreateBinFileAnalysis.validateCompletionConfig(cfg, context);

        assertTrue(direct.isValid());
        assertEquals(direct.diagnostic(), publication.diagnostic());
        assertEquals(direct.isValid(), publication.isValid());
        assertEquals("classical:otsu",
                ChannelConfigIO.toBinConfig(cfg).segmentationMethods.get(1));
        assertEquals("classical:otsu", ChannelConfigIO.fromBinConfig(
                ChannelConfigIO.toBinConfig(cfg)).channels.get(1).segmentationMethod);

        CreateBinFileAnalysis.BinUserConfig user =
                new CreateBinFileAnalysis.BinUserConfig(
                        Arrays.asList("DAPI"), Arrays.asList("Blue"),
                        Arrays.asList("default"), Arrays.asList("100-Infinity"),
                        Arrays.asList("None"), Arrays.asList("Default"),
                        Arrays.asList("default"));
        user.segmentationMethods.set(0, "classical:otsu");
        assertEquals("classical:otsu",
                ChannelConfigIO.fromBinUserConfig(user).channels.get(0).segmentationMethod);
    }

    @Test
    public void outputNameCollisionsReportBothChannelIndexesAndField() {
        assertOutputCollision("DAPI", "DAPI");
        assertOutputCollision("DAPI", "dapi");
        assertOutputCollision("GFP/YFP", "gfp/yfp");

        assertEquals(ChannelFilenameCodec.toSafe(" foo ").toLowerCase(Locale.ROOT),
                ChannelFilenameCodec.windowsCollisionKey(" foo "));
        assertTrue(ChannelConfigIO.validateForCompletion(
                validConfig("foo", "foo ")).isValid());
    }

    @Test
    public void malformedFieldsUnavailableFilterAndSourceMismatchCannotComplete() {
        ChannelConfig cfg = validConfig("DAPI", "IBA1");
        cfg.channels.get(1).color = "Not a LUT";
        cfg.channels.get(1).threshold = "auto:Invented:dark";
        cfg.channels.get(1).size = "200-100";
        cfg.channels.get(1).segmentationMethod = "classical:unexpected";
        ChannelConfigIO.ValidationContext context = new ChannelConfigIO.ValidationContext(
                Arrays.asList(new ChannelConfigIO.SourceSeries(0, 1, 12)),
                Arrays.asList(Boolean.TRUE, Boolean.FALSE), true);

        String diagnostic = ChannelConfigIO.validateForCompletion(cfg, context).diagnostic();

        assertTrue(diagnostic.contains("Channel 2 field 'color'"));
        assertTrue(diagnostic.contains("Channel 2 field 'threshold'"));
        assertTrue(diagnostic.contains("Channel 2 field 'size'"));
        assertTrue(diagnostic.contains("Channel 2 field 'segmentation'"));
        assertTrue(diagnostic.contains("Channel 2 field 'filter' is unavailable"));
        assertTrue(diagnostic.contains("sourceChannelCount"));
    }

    @Test
    public void particleSizeRangeRejectsNonfiniteOverflowAndReversal() {
        assertSizeValidity("0-Infinity", true);
        assertSizeValidity("0-NaN", false);
        assertSizeValidity("0--Infinity", false);
        assertSizeValidity("0-+Infinity", false);
        assertSizeValidity("0-1e999", false);
        assertSizeValidity("200-100", false);
    }

    @Test
    public void completionThresholdRejectsNonfiniteNumericTokens() {
        ChannelConfig cfg = validConfig("DAPI");
        cfg.channels.get(0).threshold = "Infinity";
        cfg.channels.get(0).intensityThreshold = "1e999";

        String diagnostic = ChannelConfigIO.validateForCompletion(cfg).diagnostic();

        assertTrue(diagnostic.contains("field 'threshold' is malformed"));
        assertTrue(diagnostic.contains("field 'intensityThreshold' is malformed"));
    }

    @Test
    public void subsetZCoverageRequiresEveryObservedSeriesWithinBounds() {
        ChannelConfig cfg = validConfig("DAPI", "IBA1");
        cfg.zSliceMode = flash.pipeline.zslice.ZSliceMode.PER_IMAGE;
        cfg.zSliceSelections.put("0", new flash.pipeline.zslice.ZSliceRange(2, 8));
        ChannelConfigIO.ValidationContext context = new ChannelConfigIO.ValidationContext(
                Arrays.asList(
                        new ChannelConfigIO.SourceSeries(0, 2, 10),
                        new ChannelConfigIO.SourceSeries(1, 2, 6)),
                Arrays.asList(Boolean.TRUE, Boolean.TRUE), true);

        ChannelConfigIO.ValidationResult result = ChannelConfigIO.validateForCompletion(cfg, context);

        assertFalse(result.isValid());
        assertTrue(result.diagnostic().contains("Source series 2 field 'zSliceSelections' is missing"));
    }

    @Test
    public void fullStackModeRejectsSourceMetadataWithNoZSlices() {
        ChannelConfig cfg = validConfig("DAPI");
        ChannelConfigIO.ValidationContext context = new ChannelConfigIO.ValidationContext(
                Arrays.asList(new ChannelConfigIO.SourceSeries(0, 1, 0)),
                Arrays.asList(Boolean.TRUE), true);

        ChannelConfigIO.ValidationResult result =
                ChannelConfigIO.validateForCompletion(cfg, context);

        assertFalse(result.isValid());
        assertTrue(result.diagnostic().contains("field 'sourceZSlices'"));
        assertTrue(result.diagnostic().contains("found 0"));
    }

    private static ChannelConfig validConfig(String... names) {
        ChannelConfig cfg = new ChannelConfig();
        for (int i = 0; i < names.length; i++) {
            ChannelConfig.Channel channel = new ChannelConfig.Channel();
            channel.index = i;
            channel.name = names[i];
            channel.color = i == 0 ? "Blue" : "Green";
            channel.markerId = "";
            channel.markerShape = "";
            channel.threshold = "default";
            channel.size = "100-Infinity";
            channel.minmax = "None";
            channel.intensityThreshold = "auto:Otsu:dark";
            channel.segmentationMethod = "classical";
            channel.filterPreset = "Default";
            for (String property : Arrays.asList(
                    ChannelConfig.P_NAME, ChannelConfig.P_COLOR, ChannelConfig.P_MARKER,
                    ChannelConfig.P_THRESHOLD, ChannelConfig.P_SIZE, ChannelConfig.P_MINMAX,
                    ChannelConfig.P_INTENSITY, ChannelConfig.P_SEGMENTATION, ChannelConfig.P_FILTER)) {
                channel.status.put(property, ChannelConfig.PropertyStatus.COMMITTED);
            }
            cfg.channels.add(channel);
        }
        return cfg;
    }

    private static void assertSizeValidity(String token, boolean expected) {
        ChannelConfig cfg = validConfig("DAPI");
        cfg.channels.get(0).size = token;
        assertEquals(token, expected,
                ChannelConfigIO.validateForCompletion(cfg).isValid());
    }

    private static void assertOutputCollision(String first, String second) {
        ChannelConfigIO.ValidationResult result =
                ChannelConfigIO.validateForCompletion(validConfig(first, second));
        assertFalse(result.isValid());
        ChannelConfigIO.ValidationIssue collision = result.issues().get(0);
        assertEquals(0, collision.channelIndex);
        assertEquals(1, collision.otherChannelIndex);
        assertEquals(ChannelConfig.P_NAME, collision.field);
        assertTrue(collision.message.contains("Channels 1 and 2"));
    }
}
