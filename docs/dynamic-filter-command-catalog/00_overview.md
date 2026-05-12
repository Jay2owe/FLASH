# Dynamic Fiji Command Filter Catalog

## End goal

During setup analysis filter customisation, users can click `+ Add filter...` and see more than FLASH's curated native filter steps. The list should include Fiji/ImageJ menu commands discovered from the user's own installation, including commands supplied by extra plugins they have installed. Fast native FLASH filters should still behave as they do now, while plugin commands should be added through the existing legacy macro path with clear failure messages when a command is not suitable.

## Why we're doing this

The setup filter builder currently exposes only the curated fast filters in the inline `+ Add filter...` picker. The standalone Macro Builder-style UI can discover more commands, but setup hides legacy commands before the user can choose them. After this ships, users can build setup filters with their installed Fiji plugins instead of dropping into separate manual macro editing.

## Architecture overview

FLASH already has a filter catalog and DAG-based filter builder:

```text
Fiji startup menus / Menus.getCommands()
        |
        v
FilterCatalog entries: fast native + legacy Fiji/plugin commands
        |
        v
Setup AddFilterPopover and canvas FilterBuilderPanel
        |
        v
DAG JSON + emitted IJM macro
        |
        v
native executor for fast steps OR locked legacy macro executor for plugin commands
```

The main change is to use `ij.Menus.getCommands()` as a startup command snapshot, merge it with the existing catalog, stop filtering legacy entries out of the setup picker, and add a safe inline path for probing legacy command parameters.

## Stage map

| NN | name | one-line goal | rough size | depends on |
| --- | --- | --- | --- | --- |
| 01 | `command-registry` | Add a cached `Menus.getCommands()` command snapshot with test injection/reset support. | small | none |
| 02 | `catalog-merge-dedupe` | Merge registry commands into `FilterCatalog` without duplicating fast/native entries or menu-bar entries. | medium | 01 |
| 03 | `setup-picker-legacy-visibility` | Prepare the setup `+ Add filter...` picker to show searchable legacy Fiji/plugin commands with clear badges, but keep production legacy visibility gated until Stage 04. | small | 02 |
| 04 | `inline-legacy-add-probe` | Add selected legacy commands from setup by probing the command dialog with the Recorder, storing recorded options, then enabling legacy entries in production. | medium-large | 03 |
| 05 | `validation-and-risk-gates` | Add focused unit tests and manual Fiji validation checks for dynamic command discovery and legacy command behavior. | medium | 04 |

## Known risks and open questions

- Highest risk: `Menus.getCommands()` includes many commands that are not image filters. Some may open files, change global state, launch tools, require a specific image type, or fail to record a clean macro `run(...)` line. Stage 04 must leave the filter unchanged and show a clear failure if probing fails.
- Highest risk: legacy commands run through ImageJ's global window and macro state. The existing locked legacy executor and cleanup are essential; do not bypass them for plugin commands.
- Highest risk: setup's hidden `FilterBuilderPanel` currently has no preview runner. Stage 04 must either provide a minimal runner from `FilterParameterStage.sourceImage` or add a targeted append API that accepts already recorded options.
- `Menus.getCommands()` returns command names, not reliable full menu paths. Stage 02 should prefer real menu-bar paths when available and mark registry-only commands with a generic source such as `Fiji commands`.
- Commands installed after FLASH starts may not appear until a refresh or restart unless a reload hook is added. A reload button is out of scope unless discovered to be necessary during validation.
- Proposed manual validation: test with at least one bundled Fiji command and one extra plugin command installed in the local Fiji app. Please confirm which extra plugin should be the manual validation target if there is a preferred one.

## House rules

- Preserve current fast/native filter behavior and ordering.
- Do not make plugin commands run through the native executor.
- Do not silently add commands when parameter probing fails.
- Keep headless tests safe: no direct `Menus.getCommands()` or UI probing path should crash in headless mode.
- Do not invent new filter semantics; this work only exposes and preserves user-selected Fiji/ImageJ commands.
- Follow project build constraints from `README.md`: Maven project, Java 8 bytecode, Fiji/ImageJ plugin compatibility.
- Communication and generated task files should stay concise and use exact file paths and commands.

## How to run a stage

After the numbered stage files are reviewed and written, run:

```text
/do-step docs/dynamic-filter-command-catalog/
```
