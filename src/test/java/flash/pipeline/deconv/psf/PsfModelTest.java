package flash.pipeline.deconv.psf;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class PsfModelTest {

    @Test
    public void modelMetadataIsPresentForEveryEnumValue() {
        Set<String> macroKeys = new HashSet<String>();
        for (PsfModel model : PsfModel.values()) {
            assertNotNull(model.displayName());
            assertFalse(model.displayName().trim().isEmpty());
            assertNotNull(model.description());
            assertFalse(model.description().trim().isEmpty());
            assertNotNull(model.epflMacroModelKey());
            assertFalse(model.epflMacroModelKey().trim().isEmpty());
            assertTrue("Duplicate macro key: " + model.epflMacroModelKey(),
                    macroKeys.add(model.epflMacroModelKey()));
        }
    }
}
