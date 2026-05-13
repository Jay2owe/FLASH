# Command Registry

## Why this stage exists

The end goal depends on knowing which Fiji/ImageJ commands are available in the user's own installation. `FilterCatalog` currently only discovers a restricted menu-bar subset, so extra plugin commands can be invisible. This stage adds a small cached registry around `ij.Menus.getCommands()` so later stages can merge those commands into the filter catalog without directly touching Fiji global state everywhere.

## Prerequisites

None.

## Read first

- `docs/dynamic-filter-command-catalog/00_overview.md`
- `README.md` lines 1-82 for the setup workflow and build context.
- `src/main/java/flash/pipeline/ui/sandbox/FilterCatalog.java` lines 179-199 for the current tier-two cache pattern.
- `src/main/java/flash/pipeline/runtime/DependencyRegistry.java` lines 670-674 for an existing FLASH `Menus.getCommands()` lookup.
- Reference only: an external ImageJAI `MenuCommandRegistry.java` implementation for a cached `Menus.getCommands()` snapshot pattern.

## Scope

- Add a FLASH-side command registry in the filter-builder package.
- Snapshot `ij.Menus.getCommands()` lazily and cache the result.
- Store both command name and implementing command value/class string when available.
- Return an immutable, sorted list for stable tests and stable catalog ordering.
- Add test-only injection/reset APIs so unit tests do not require a live Fiji menu table.
- Keep headless and too-early Fiji startup paths safe by returning an empty snapshot on failure.

## Out of scope

- Do not modify `FilterCatalog` to consume the registry yet. Stage 02 owns catalog merging.
- Do not expose legacy commands in setup UI. Stage 03 and Stage 04 own the picker and add flow.
- Do not add reload or refresh UI for commands installed after startup. That remains out of scope unless Stage 05 manual validation proves it is necessary.
- Do not fuzzy-match command names. The current requirement is discovery, not user intent parsing.

## Files touched

| path | action | reason |
| --- | --- | --- |
| `src/main/java/flash/pipeline/ui/sandbox/FijiCommandRegistry.java` | NEW | Cached snapshot of `ij.Menus.getCommands()`. |
| `src/test/java/flash/pipeline/ui/sandbox/FijiCommandRegistryTest.java` | NEW | Headless-safe unit coverage for sorting, immutability, and test injection. |

## Implementation sketch

Create a small registry class in the same package as `FilterCatalog`:

```java
package flash.pipeline.ui.sandbox;

import ij.Menus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FijiCommandRegistry {
    private static volatile List<Command> cached;

    private FijiCommandRegistry() {}

    public static List<Command> allCommands() {
        List<Command> local = cached;
        if (local != null) return local;
        synchronized (FijiCommandRegistry.class) {
            if (cached == null) cached = Collections.unmodifiableList(loadCommands());
            return cached;
        }
    }

    static void setForTests(Map<String, String> commands) {
        synchronized (FijiCommandRegistry.class) {
            cached = Collections.unmodifiableList(fromMap(commands));
        }
    }

    static void clearForTests() {
        synchronized (FijiCommandRegistry.class) {
            cached = null;
        }
    }

    private static List<Command> loadCommands() {
        Map<String, String> snapshot = new LinkedHashMap<String, String>();
        try {
            Hashtable commands = Menus.getCommands();
            if (commands != null) {
                for (Object key : commands.keySet()) {
                    if (key == null) continue;
                    Object value = commands.get(key);
                    snapshot.put(String.valueOf(key), value == null ? "" : String.valueOf(value));
                }
            }
        } catch (Throwable ignored) {
            // Headless tests or too-early ImageJ startup: leave empty.
        }
        return fromMap(snapshot);
    }

    private static List<Command> fromMap(Map<String, String> commands) {
        List<Command> out = new ArrayList<Command>();
        if (commands != null) {
            for (Map.Entry<String, String> entry : commands.entrySet()) {
                String name = entry.getKey() == null ? "" : entry.getKey().trim();
                if (name.length() == 0) continue;
                out.add(new Command(name, entry.getValue()));
            }
        }
        Collections.sort(out, new Comparator<Command>() {
            @Override public int compare(Command a, Command b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return out;
    }

    public static final class Command {
        public final String name;
        public final String className;

        Command(String name, String className) {
            this.name = name == null ? "" : name;
            this.className = className == null ? "" : className;
        }
    }
}
```

Test ideas:

```java
@Test
public void setForTestsReturnsSortedImmutableCommands() {
    Map<String, String> commands = new LinkedHashMap<String, String>();
    commands.put("Z Command", "z.Plugin");
    commands.put("A Command", "a.Plugin");
    FijiCommandRegistry.setForTests(commands);

    List<FijiCommandRegistry.Command> all = FijiCommandRegistry.allCommands();
    assertEquals("A Command", all.get(0).name);
    assertEquals("Z Command", all.get(1).name);
}

@Test
public void clearForTestsDoesNotThrowWithoutFijiMenus() {
    FijiCommandRegistry.clearForTests();
    assertNotNull(FijiCommandRegistry.allCommands());
}
```

## Exit gate

1. `src/main/java/flash/pipeline/ui/sandbox/FijiCommandRegistry.java` exists and compiles.
2. Unit tests prove injected command maps are sorted and immutable.
3. A headless test path can call `FijiCommandRegistry.allCommands()` without crashing.
4. Run:

```powershell
.\mvnw.cmd "-Dtest=FijiCommandRegistryTest" "-Denforcer.skip=true" test
```

## Known risks

- Risk level: medium. `Menus.getCommands()` can be unavailable in headless tests or before Fiji menus have fully initialized. The registry must catch `Throwable` and return an empty list rather than failing setup.
- Risk level: medium. `Hashtable` iteration order is unstable. Sort output so later catalog tests do not flicker.
- Risk level: high downstream. The registry deliberately includes arbitrary Fiji/plugin commands. This stage must not imply those commands are safe filters; later stages must still gate probing and legacy execution carefully.
