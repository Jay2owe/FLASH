# Catalog Merge And Dedupe

## Why this stage exists

The command registry from Stage 01 is only useful once `FilterCatalog` consumes it. This stage makes the catalog include user-installed Fiji/plugin commands while preserving the existing fast/native filter entries users already rely on. Downstream setup UI stages depend on a single catalog list that distinguishes fast entries from legacy commands and avoids confusing duplicates.

## Prerequisites

- `01_command-registry.md` must be completed and renamed with `_COMPLETED`.

## Read first

- `docs/dynamic-filter-command-catalog/00_overview.md`
- `docs/dynamic-filter-command-catalog/01_command-registry_COMPLETED.md`
- `src/main/java/flash/pipeline/ui/sandbox/FijiCommandRegistry.java`
- `src/main/java/flash/pipeline/ui/sandbox/FilterCatalog.java` lines 31-315.
- `src/test/java/flash/pipeline/ui/sandbox/FilterCatalogTest.java` lines 15-84.
- Reference only: `C:/Users/jamie/UK Dementia Research Institute Dropbox/Brancaccio Lab/Jamie/Experiments/Macro-Builder/src/main/java/macro/builder/ui/sandbox/FilterCatalog.java` lines 33-45 and 194-216 for grouping legacy plugin commands separately from ordinary Fiji commands.

## Scope

- Merge `FijiCommandRegistry.allCommands()` into `FilterCatalog` tier-two entries.
- Preserve existing fast/native entries from `seedTierOne()` and do not duplicate them as legacy entries.
- Preserve existing menu-bar discovered entries and prefer their menu paths over registry-only generic paths.
- Add registry-only commands as legacy entries with a generic path such as `Fiji commands > Command Name`.
- Reuse existing skip logic for non-useful commands such as `About`, `Help`, `Refresh Menus`, and `Compile and Run`.
- Add focused tests for merge, dedupe, and skip behavior.

## Out of scope

- Do not change `AddFilterPopover`; Stage 03 owns picker presentation.
- Do not change `FilterParameterStage.applyAddFilter`; Stage 04 owns legacy add/probe behavior.
- Do not add command safety filtering beyond the existing basic skip list. Many commands will still be unsuitable as filters, and Stage 04 must handle that at add-time.
- Do not change DAG execution or macro emitting.

## Files touched

| path | action | reason |
| --- | --- | --- |
| `src/main/java/flash/pipeline/ui/sandbox/FilterCatalog.java` | MODIFY | Merge registry commands with existing fast and menu-bar catalog entries. |
| `src/test/java/flash/pipeline/ui/sandbox/FilterCatalogTest.java` | MODIFY | Cover registry-only commands, dedupe, and skipped commands. |

## Implementation sketch

Keep the current constructor shape:

```java
seedTierOne();
addTierTwoEntries(tierTwoEntries);
refresh();
```

Change `addTierTwoEntries` so it dedupes against entries already added by `seedTierOne()`:

```java
private void addTierTwoEntries(List<Entry> tierTwoEntries) {
    List<Entry> source = tierTwoEntries == null ? getCachedTierTwoEntries() : tierTwoEntries;
    for (int i = 0; i < source.size(); i++) {
        addIfAbsent(source.get(i));
    }
}

private void addIfAbsent(Entry candidate) {
    if (candidate == null || candidate.stub) return;
    String key = commandKey(candidate.commandName.length() > 0
            ? candidate.commandName
            : candidate.label);
    if (key.length() == 0) return;
    for (int i = 0; i < entries.size(); i++) {
        Entry existing = entries.get(i);
        String existingKey = commandKey(existing.commandName.length() > 0
                ? existing.commandName
                : existing.label);
        if (key.equals(existingKey)) return;
    }
    entries.add(candidate);
}
```

Modify tier-two loading:

```java
private static List<Entry> loadTierTwoEntries() {
    List<Entry> menuEntries = Collections.emptyList();
    if (!GraphicsEnvironment.isHeadless()) {
        try {
            menuEntries = collectTierTwoFromMenuBar(Menus.getMenuBar());
        } catch (Throwable t) {
            menuEntries = Collections.emptyList();
        }
    }
    return mergeRegistryCommands(menuEntries, FijiCommandRegistry.allCommands());
}
```

Add merge helper:

```java
static List<Entry> mergeRegistryCommands(List<Entry> menuEntries,
                                          List<FijiCommandRegistry.Command> registryCommands) {
    List<Entry> out = new ArrayList<Entry>();
    Set<String> seen = new HashSet<String>();

    if (menuEntries != null) {
        for (Entry entry : menuEntries) {
            String key = commandKey(entry.commandName);
            if (key.length() == 0 || seen.contains(key)) continue;
            seen.add(key);
            out.add(entry);
        }
    }

    if (registryCommands != null) {
        for (FijiCommandRegistry.Command command : registryCommands) {
            String label = normalizeLabel(command.name);
            String key = commandKey(label);
            if (key.length() == 0 || seen.contains(key) || shouldSkipCommand(label)) continue;
            seen.add(key);
            out.add(Entry.legacy("Fiji commands", label, "Fiji commands > " + label));
        }
    }
    return out;
}
```

Add a command key helper that strips ImageJ ellipsis and normalizes case:

```java
private static String commandKey(String raw) {
    return normalizeLabel(raw).toLowerCase(Locale.ROOT);
}
```

Test cases:

- Registry-only command appears as a legacy entry with `commandName`.
- A registry command matching a menu-bar command does not create a duplicate.
- A registry command matching a fast/native command such as `Median...` does not create a duplicate when constructing a full `FilterCatalog`.
- Skipped commands such as `Refresh Menus` are absent.

## Exit gate

1. `FilterCatalog.getAllEntries()` includes injected registry-only commands when `FijiCommandRegistry.setForTests(...)` is used.
2. Fast/native commands still appear once and keep `[fast]`.
3. Menu-bar commands keep their real menu path when both menu-bar and registry sources contain the same command.
4. Run:

```powershell
.\mvnw.cmd "-Dtest=FijiCommandRegistryTest,FilterCatalogTest" "-Denforcer.skip=true" test
```

## Known risks

- Risk level: high. `Menus.getCommands()` includes arbitrary commands, including commands that are not image filters. This stage must only list them as legacy entries; it must not mark them fast or native.
- Risk level: medium. Registry-only commands have no reliable menu path. Use a generic path rather than inventing a false plugin location.
- Risk level: medium. Deduping too aggressively could hide a plugin command with the same display label as a core command. Prefer preserving fast/native entries and menu-bar entries first because they have known behavior or better path context.
- Risk level: medium. If `shouldSkipCommand` becomes too broad, useful plugin commands may disappear. Keep skip logic conservative.
