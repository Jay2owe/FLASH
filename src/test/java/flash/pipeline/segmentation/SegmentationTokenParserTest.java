package flash.pipeline.segmentation;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class SegmentationTokenParserTest {

    @After
    public void resetWarningSink() {
        SegmentationTokenParser.setWarningSinkForTest(null);
    }

    @Test
    public void canonicalTokensRoundTripByteForByte() {
        String[] tokens = {
                "classical",
                "enhanced_classical:thresh=42.5:minSize=100:maxSize=5000:"
                        + "morph=sphericity%3E%3D0.6%2Celongation%3C%3D2.0",
                "stardist:0.5:0.4:linking=5.0:gapClosing=6.0:"
                        + "area=10.0-100.0:quality=0.2:intensity=15.0:model=my_stardist",
                "cellpose:30:0.4:0.0:gpu=true:chan2=0:model=cellpose_cyto3",
                "trained_rf:rf_model:base=stardist%3A0.5%3A0.4%3Amodel%3Dstd"
        };

        for (int i = 0; i < tokens.length; i++) {
            assertEquals(tokens[i], SegmentationTokenParser.format(
                    SegmentationTokenParser.parse(tokens[i])));
        }
    }

    @Test
    public void currentStarDistTokenParsesWithDefaultsAndFilters() {
        SegmentationMethod method = SegmentationTokenParser.parse(
                "stardist:0.6:0.3:linking=4.5:gapClosing=6.5:"
                        + "frameGap=2:area=50.0-Infinity:quality=0.3:intensity=100.0");

        assertTrue(method.isStarDist());
        assertEquals(0.6, SegmentationMethod.starDistProb(method), 0.001);
        assertEquals(0.3, SegmentationMethod.starDistNms(method), 0.001);
        StarDistLinkingParams linking = SegmentationMethod.starDistLinking(method);
        assertEquals(4.5, linking.linkingMaxDistance, 0.001);
        assertEquals(6.5, linking.gapClosingMaxDistance, 0.001);
        assertEquals(2, linking.maxFrameGap);
        StarDistPostFilters filters = SegmentationMethod.starDistPostFilters(method);
        assertEquals(50.0, filters.areaMin, 0.001);
        assertTrue(Double.isInfinite(filters.areaMax));
        assertEquals(0.3, filters.qualityMin, 0.001);
        assertEquals(100.0, filters.intensityMin, 0.001);
    }

    @Test
    public void legacyAndCanonicalCellposeTokensParse() {
        SegmentationMethod legacy = SegmentationTokenParser.parse(
                "cellpose:30:cyto3:0.4:0.0:gpu=true:chan2=0");
        assertTrue(legacy.isCellpose());
        assertEquals(30.0, SegmentationMethod.cellposeDiameter(legacy), 0.001);
        assertEquals("cellpose_cyto3", SegmentationMethod.cellposeModelKey(legacy));
        assertEquals(0.4, SegmentationMethod.cellposeFlow(legacy), 0.001);
        assertEquals(0.0, SegmentationMethod.cellposeCellprob(legacy), 0.001);
        assertTrue(SegmentationMethod.cellposeUseGpu(legacy));
        assertEquals(0, SegmentationMethod.cellposeChan2(legacy));
        assertEquals("cellpose:30:0.4:0.0:gpu=true:chan2=0:model=cellpose_cyto3",
                SegmentationTokenParser.format(legacy));

        SegmentationMethod canonical = SegmentationTokenParser.parse(
                "cellpose:22.5:0.6:-1.0:gpu=false:model=my_model");
        assertEquals("my_model", SegmentationMethod.cellposeModelKey(canonical));
        assertEquals(22.5, SegmentationMethod.cellposeDiameter(canonical), 0.001);
        assertEquals(0.6, SegmentationMethod.cellposeFlow(canonical), 0.001);
        assertEquals(-1.0, SegmentationMethod.cellposeCellprob(canonical), 0.001);
        assertFalse(SegmentationMethod.cellposeUseGpu(canonical));
    }

    @Test
    public void lenientMalformedTokenFallsBackAndWarns() {
        final List<String> warnings = new ArrayList<String>();
        SegmentationTokenParser.setWarningSinkForTest(new SegmentationTokenParser.WarningSink() {
            @Override public void warn(String message) {
                warnings.add(message);
            }
        });

        SegmentationMethod method = SegmentationTokenParser.parseLenient("future_engine:value");

        assertTrue(method.isClassical());
        assertTrue(method.shouldPreserveRawTokenOnWrite());
        assertEquals("future_engine:value", method.rawToken);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("future_engine:value"));
    }

    @Test
    public void strictMalformedTokenThrows() {
        try {
            SegmentationTokenParser.parse("cellpose:not-a-number:cyto3");
            fail("Expected malformed Cellpose token to throw.");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Cellpose diameter"));
        }

        try {
            SegmentationTokenParser.parse("train_custom_engine");
            fail("Expected UI-only launcher token to throw.");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Unknown segmentation engine"));
        }
    }

    @Test
    public void morphPredicateEncodedAndUnencodedRoundTrip() {
        String unencoded = "enhanced_classical:thresh=10:minSize=20:maxSize=300:"
                + "morph=sphericity>=0.6,elongation<=2.0";

        SegmentationMethod method = SegmentationTokenParser.parse(unencoded);

        assertEquals("enhanced_classical:thresh=10:minSize=20:maxSize=300:"
                        + "morph=sphericity%3E%3D0.6%2Celongation%3C%3D2.0",
                SegmentationTokenParser.format(method));
        List<MorphPredicate> predicates = SegmentationMethod.morphPredicates(method);
        assertEquals(2, predicates.size());
        assertTrue(predicates.get(0).matches(0.7));
        assertFalse(predicates.get(1).matches(2.5));

        MorphPredicate unknown = MorphPredicate.parse("future_metric>999.0");
        assertTrue(unknown.matches(0.0));
    }

    @Test
    public void morphPredicateInclusiveOperatorsKeepExactBoundaryValues() {
        assertTrue(MorphPredicate.parse("volume>=27").matches(27));
        assertTrue(MorphPredicate.parse("volume<=27").matches(27));
        assertFalse(MorphPredicate.parse("volume>27").matches(27));
        assertFalse(MorphPredicate.parse("volume<27").matches(27));
    }

    @Test
    public void repeatedKeysTrailingColonsAndEmptySegmentsUseLastValue() {
        SegmentationMethod method = SegmentationTokenParser.parse(
                "stardist:0.5:0.4::quality=0.1:quality=0.2:");

        assertEquals(0.2, SegmentationMethod.starDistPostFilters(method).qualityMin, 0.001);
        assertEquals("stardist:0.5:0.4:quality=0.2", SegmentationTokenParser.format(method));
    }
}
