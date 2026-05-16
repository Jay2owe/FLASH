package flash.pipeline.segmentation;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SegmentationTokenParserNestedBaseTest {

    @Test
    public void roundTripsTrainedRfTokenWithStarDistBaseContainingModelSegment() {
        String token = "trained_rf:projectModel_microglia:"
                + "base=stardist%3A0.5%3A0.3%3Aarea%3D20-2000%3Amodel%3Duser_stardist_v1";

        SegmentationMethod parsed = SegmentationTokenParser.parse(token);
        String formatted = SegmentationTokenParser.format(parsed);

        assertTrue(parsed.isTrainedRf());
        assertEquals("stardist:0.5:0.3:area=20-2000:model=user_stardist_v1",
                parsed.params.get("base"));
        assertEquals(token, formatted);
        SegmentationMethod base = SegmentationMethod.trainedRfBase(parsed);
        assertTrue(base.isStarDist());
        assertEquals("user_stardist_v1", SegmentationMethod.starDistModelKey(base));
    }

    @Test
    public void formatterPercentEncodesNestedBaseToken() {
        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("modelKey", "projectModel_microglia");
        params.put("base", "stardist:0.5:0.3:area=20-2000:model=user_stardist_v1");

        String formatted = SegmentationTokenParser.format(new SegmentationMethod(
                SegmentationMethod.Engine.TRAINED_RF, params, ""));

        assertEquals("trained_rf:projectModel_microglia:"
                        + "base=stardist%3A0.5%3A0.3%3Aarea%3D20-2000%3Amodel%3Duser_stardist_v1",
                formatted);
    }
}
