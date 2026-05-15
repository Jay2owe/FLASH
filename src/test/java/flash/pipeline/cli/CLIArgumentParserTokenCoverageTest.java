package flash.pipeline.cli;

import flash.pipeline.bin.BinField;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class CLIArgumentParserTokenCoverageTest {

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
}
