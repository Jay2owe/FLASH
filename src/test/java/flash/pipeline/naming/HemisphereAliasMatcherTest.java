package flash.pipeline.naming;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HemisphereAliasMatcherTest {

    @Test
    public void match_tokenAliasesSuggestExpectedHemisphere() {
        HemisphereAliasMatcher.Suggestion left =
                HemisphereAliasMatcher.match("Mouse_Left_SCN");
        HemisphereAliasMatcher.Suggestion right =
                HemisphereAliasMatcher.match("Mouse-RightHemisphere-SCN");

        assertEquals(OrientationManifestRow.Hemisphere.LH, left.hemisphere);
        assertEquals(OrientationManifestRow.Hemisphere.RH, right.hemisphere);
        assertTrue(left.hasHemisphere());
    }

    @Test
    public void match_obscureEmbeddedWordAliasButNotSingleLetterInsideName() {
        HemisphereAliasMatcher.Suggestion left =
                HemisphereAliasMatcher.match("series1left1");
        HemisphereAliasMatcher.Suggestion ambiguousSingleLetter =
                HemisphereAliasMatcher.match("series1L1");

        assertEquals(OrientationManifestRow.Hemisphere.LH, left.hemisphere);
        assertFalse(ambiguousSingleLetter.hasHemisphere());
    }

    @Test
    public void match_userAliasesAreSupportedWithoutChangingBuiltIns() {
        Map<OrientationManifestRow.Hemisphere, List<String>> aliases =
                new LinkedHashMap<OrientationManifestRow.Hemisphere, List<String>>();
        aliases.put(OrientationManifestRow.Hemisphere.LH, Arrays.asList("ipsi"));
        aliases.put(OrientationManifestRow.Hemisphere.RH, Arrays.asList("contra"));

        assertEquals(OrientationManifestRow.Hemisphere.LH,
                HemisphereAliasMatcher.match("Mouse_ipsi_SCN", aliases).hemisphere);
        assertEquals(OrientationManifestRow.Hemisphere.RH,
                HemisphereAliasMatcher.match("Mouse_contra_SCN", aliases).hemisphere);
    }
}
