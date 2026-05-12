# Validation And Risk Gates

## Why this stage exists

The implementation touches command discovery, setup UI, Recorder probing, DAG emission, and legacy execution. Unit tests can prove the controlled paths, but the highest-risk behavior involves real Fiji/plugin commands and must be manually validated in Fiji. This stage adds missing regression tests and documents the manual checks needed before considering the feature ready.

## Prerequisites

- `01_command-registry.md` must be completed and renamed with `_COMPLETED`.
- `02_catalog-merge-dedupe.md` must be completed and renamed with `_COMPLETED`.
- `03_setup-picker-legacy-visibility.md` must be completed and renamed with `_COMPLETED`.
- `04_inline-legacy-add-probe.md` must be completed and renamed with `_COMPLETED`.

## Read first

- `docs/dynamic-filter-command-catalog/00_overview.md`
- All completed stage files in `docs/dynamic-filter-command-catalog/`.
- `src/test/java/flash/pipeline/ui/sandbox/FilterCatalogTest.java` lines 15-84.
- `src/test/java/flash/pipeline/ui/sandbox/FilterBuilderPanelTest.java` lines 19-180.
- `src/test/java/flash/pipeline/ui/config/FilterParameterStageTest.java` lines 37-620 and 764-790.
- `src/test/java/flash/pipeline/ui/sandbox/RecorderParameterProbeTest.java` lines 11-37.
- `src/test/java/flash/pipeline/image/dag/DagToIjmEmitterTest.java` lines 93-126.
- `README.md` lines 109-120 for Maven build commands.

## Scope

- Fill any test gaps left by Stages 01-04.
- Run focused unit tests for command registry, catalog merge, picker filtering, setup add behavior, Recorder parsing, and DAG emission.
- Run a broader compile/test check if focused tests pass.
- Add a short manual validation note to this folder describing the Fiji checks performed and their result.
- Explicitly validate failure behavior for unsuitable or cancelled legacy commands.

## Out of scope

- Do not implement new feature behavior unless a validation failure reveals a bug in Stages 01-04.
- Do not add a broad allowlist/blocklist unless validation proves the feature is unsafe without one; if needed, document it as a follow-up instead of making a large unplanned policy change.
- Do not publish, deploy, or upload to an update site. This stage validates local code only.

## Files touched

| path | action | reason |
| --- | --- | --- |
| `src/test/java/flash/pipeline/ui/sandbox/FijiCommandRegistryTest.java` | MODIFY | Add any missing registry edge-case tests. |
| `src/test/java/flash/pipeline/ui/sandbox/FilterCatalogTest.java` | MODIFY | Ensure dynamic command merge and dedupe are covered. |
| `src/test/java/flash/pipeline/ui/config/AddFilterPopoverTest.java` | MODIFY | Ensure legacy-capable picker filtering remains covered. |
| `src/test/java/flash/pipeline/ui/sandbox/FilterBuilderPanelTest.java` | MODIFY | Ensure legacy append with args round-trips through emitted IJM. |
| `src/test/java/flash/pipeline/ui/config/FilterParameterStageTest.java` | MODIFY | Ensure setup legacy failure paths leave macros unchanged. |
| `docs/dynamic-filter-command-catalog/manual-validation.md` | NEW | Record manual Fiji validation steps and results. |

## Implementation sketch

Focused unit command:

```powershell
.\mvnw.cmd "-Dtest=FijiCommandRegistryTest,FilterCatalogTest,AddFilterPopoverTest,FilterBuilderPanelTest,FilterParameterStageTest,RecorderParameterProbeTest,DagToIjmEmitterTest" "-Denforcer.skip=true" test
```

Broader check:

```powershell
.\mvnw.cmd "-Denforcer.skip=true" test
```

If the full test suite is too slow or has unrelated existing failures, run:

```powershell
.\mvnw.cmd "-Denforcer.skip=true" test -DskipITs
```

and record the exact failure or limitation in the final report.

Create `manual-validation.md` with this shape:

```markdown
# Dynamic Filter Command Catalog Manual Validation

Date: YYYY-MM-DD
Fiji path: TODO
FLASH jar/build: TODO

## Checks

1. Open Fiji and launch FLASH setup analysis.
2. Enter filter setup for a channel and open `+ Add filter...`.
3. Search for a known fast command such as `Median`.
   - Expected: visible as `[fast]`; adding it behaves as before.
4. Search for a Fiji command discovered through `Menus.getCommands()`.
   - Expected: visible as `[legacy]`.
5. Add a legacy command that records cleanly.
   - Expected: Fiji parameter dialog appears; after OK, accordion gains one row; emitted macro contains `executionTier=legacy` and the recorded options.
6. Cancel a legacy command dialog.
   - Expected: filter is unchanged; no blank command is appended.
7. Try an unsuitable command if practical, such as a command that is not an image filter.
   - Expected: clear failure/status; filter is unchanged.
8. Run filter preview after adding a valid legacy command.
   - Expected: preview completes or reports the plugin command's error clearly; no stray windows remain.

## Result

TODO: PASS/FAIL with notes.
```

Preferred extra-plugin target is not specified in the source conversation. Use whichever extra plugin is already installed locally and records a macro command cleanly. If none is available, record that limitation.

## Exit gate

1. Focused tests pass.
2. Broader Maven test or compile check has been attempted and any unrelated failures are documented.
3. `manual-validation.md` exists and records the manual Fiji checks, including at least one valid legacy command or a clear note that no suitable extra plugin was available.
4. Cancel/failure behavior is verified: no blank legacy command is appended to the filter macro.
5. The final implementation still preserves native fast filter behavior.

## Known risks

- Risk level: very high. Passing unit tests does not prove arbitrary Fiji/plugin commands are safe. Manual Fiji validation is required because plugin dialogs, macro recording, and WindowManager cleanup only behave fully inside Fiji.
- Risk level: high. Some extra plugin commands may record non-`run(...)` macro code or depend on active windows in a way FLASH cannot replay safely. The accepted behavior is failure with no macro change, not trying to force support.
- Risk level: high. A legacy command can make batch analysis slower and single-threaded for that filter chain. This is acceptable only because the command is clearly marked `[legacy]`.
- Risk level: medium. Local Fiji installs differ. Manual validation must state which command/plugin was tested so future failures can be reproduced.
