package flash.pipeline.ui.sandbox;

import org.junit.After;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class FijiCommandRegistryTest {

    @After
    public void clearRegistry() {
        FijiCommandRegistry.clearForTests();
    }

    @Test
    public void setForTestsReturnsSortedImmutableCommands() {
        Map<String, String> commands = new LinkedHashMap<String, String>();
        commands.put("Z Command", "z.Plugin");
        commands.put("A Command", "a.Plugin");

        FijiCommandRegistry.setForTests(commands);

        List<FijiCommandRegistry.Command> all = FijiCommandRegistry.allCommands();
        assertEquals(2, all.size());
        assertEquals("A Command", all.get(0).name);
        assertEquals("a.Plugin", all.get(0).className);
        assertEquals("Z Command", all.get(1).name);
        assertEquals("z.Plugin", all.get(1).className);
    }

    @Test(expected = UnsupportedOperationException.class)
    public void injectedCommandListIsImmutable() {
        Map<String, String> commands = new LinkedHashMap<String, String>();
        commands.put("A Command", "a.Plugin");
        FijiCommandRegistry.setForTests(commands);

        FijiCommandRegistry.allCommands().clear();
    }

    @Test
    public void clearForTestsDoesNotThrowWithoutFijiMenus() {
        FijiCommandRegistry.clearForTests();

        assertNotNull(FijiCommandRegistry.allCommands());
    }

    @Test
    public void setForTestsSkipsBlankCommandNames() {
        Map<String, String> commands = new LinkedHashMap<String, String>();
        commands.put("   ", "blank.Plugin");
        commands.put(null, "null.Plugin");
        commands.put("Real Command", null);

        FijiCommandRegistry.setForTests(commands);

        List<FijiCommandRegistry.Command> all = FijiCommandRegistry.allCommands();
        assertEquals(1, all.size());
        assertEquals("Real Command", all.get(0).name);
        assertEquals("", all.get(0).className);
    }
}
