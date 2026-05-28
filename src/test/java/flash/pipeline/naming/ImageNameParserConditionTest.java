package flash.pipeline.naming;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Covers the optional 5th token (Condition) parsed by
 * {@link ImageNameParser} and the parent-folder fallback used by the
 * project builder when the convention itself does not embed a condition.
 */
public class ImageNameParserConditionTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void parse_fullConventionWithCondition() {
        NameParts np = ImageNameParser.parse("MyExp-Mouse5_LH_Cortex_WT");
        assertTrue(np.strictMatch);
        assertEquals("MyExp", np.experiment);
        assertEquals("Mouse5", np.animal);
        assertEquals("LH", np.hemisphere);
        assertEquals("Cortex", np.region);
        assertEquals("WT", np.condition);
    }

    @Test
    public void parse_conditionAcceptsMultiCharToken() {
        NameParts np = ImageNameParser.parse("E-A1_RH_Hippo_KO5xFAD");
        assertTrue(np.strictMatch);
        assertEquals("Hippo", np.region);
        assertEquals("KO5xFAD", np.condition);
    }

    @Test
    public void parse_animalWithUnderscoreAndCondition() {
        NameParts np = ImageNameParser.parse("Exp-Mouse_5_LH_SCN_WT");
        assertTrue(np.strictMatch);
        assertEquals("Mouse_5", np.animal);
        assertEquals("LH", np.hemisphere);
        assertEquals("SCN", np.region);
        assertEquals("WT", np.condition);
    }

    @Test
    public void parse_conditionEmptyWhenNotPresent() {
        NameParts np = ImageNameParser.parse("MyExp-Mouse5_LH_Cortex");
        assertTrue(np.strictMatch);
        assertEquals("Cortex", np.region);
        assertEquals("", np.condition);
    }

    @Test
    public void parse_conditionEmptyOnFallback() {
        // Non-conforming filename → strictMatch=false, condition should not leak.
        NameParts np = ImageNameParser.parse("random_image.tif");
        assertFalse(np.strictMatch);
        assertEquals("", np.condition);
    }

    @Test
    public void parse_bioFormatsSeriesWithCondition() {
        NameParts np = ImageNameParser.parse("container.lif - Mouse5_LH_Cortex_WT");
        assertTrue(np.strictMatch);
        assertEquals("Mouse5", np.animal);
        assertEquals("LH", np.hemisphere);
        assertEquals("Cortex", np.region);
        assertEquals("WT", np.condition);
    }

    @Test
    public void guessConditionFromParentFolder_returnsImmediateParent() throws Exception {
        File wtDir = temp.newFolder("WT");
        File lif = new File(wtDir, "Mouse5.lif");
        assertTrue(lif.createNewFile());

        assertEquals("WT", ImageNameParser.guessConditionFromParentFolder(lif));
    }

    @Test
    public void guessConditionFromParentFolder_handlesNull() {
        assertEquals("", ImageNameParser.guessConditionFromParentFolder(null));
    }

    @Test
    public void guessConditionFromParentFolder_handlesNoParent() {
        // A bare filename with no parent path yields no guess.
        assertEquals("", ImageNameParser.guessConditionFromParentFolder(new File("standalone.lif")));
    }
}
