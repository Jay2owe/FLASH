package flash.pipeline.intelligence.identity;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Stage 07b: grammar file store under FLASH/Config/.settings/naming_grammars/. */
public class NamingGrammarStoreTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static NamingGrammar sampleGrammar() {
        List<FieldRule> rules = new ArrayList<FieldRule>();
        rules.add(FieldRule.capture(FieldRule.Type.ANIMAL, "", "(?<=_)M\\d+"));
        rules.add(FieldRule.alias(FieldRule.Type.CONDITION, "Genotype",
                Arrays.asList(new ValuePattern("hAPP", Collections.singletonList("hAPP")))));
        return new NamingGrammar("Brancaccio", rules);
    }

    @Test
    public void saveListLoad_roundTrips() throws Exception {
        String dir = temp.newFolder("project").getAbsolutePath();
        NamingGrammarStore.save(dir, sampleGrammar());

        assertTrue(NamingGrammarStore.hasAny(dir));
        assertTrue(NamingGrammarStore.listNames(dir).contains("Brancaccio"));

        NamingGrammar back = NamingGrammarStore.load(dir, "Brancaccio");
        assertEquals("Brancaccio", back.name);
        assertEquals(2, back.rules.size());

        // behaviour survives the round-trip
        PartialIdentity p = new GrammarInterpreter().apply(back, "x_M14_hAPP");
        assertEquals("M14", p.animal().value);
        assertEquals("hAPP", p.conditions().get("genotype").value);
    }

    @Test
    public void writesUnderConfigSettingsNamingGrammars() throws Exception {
        File project = temp.newFolder("project2");
        NamingGrammarStore.save(project.getAbsolutePath(), sampleGrammar());

        File grammarDir = NamingGrammarStore.dir(project.getAbsolutePath());
        assertEquals("naming_grammars", grammarDir.getName());
        assertEquals(".settings", grammarDir.getParentFile().getName());
        assertEquals("Config", grammarDir.getParentFile().getParentFile().getName());
        assertTrue(new File(grammarDir, "Brancaccio.json").isFile());
    }

    @Test
    public void loadIfExists_missingReturnsNull() {
        String dir = temp.getRoot().getAbsolutePath();
        assertNull(NamingGrammarStore.loadIfExists(dir, "nope"));
    }
}
