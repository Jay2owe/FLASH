# project-home-screen — Final Verification

**Date:** 2026-06-03
**Verifier:** codex final-verify

## What was verified
- End-to-end home-screen reopen flow: recent choice resolves through `ProjectService`, loads via `ProjectService.load`, records the recent entry, sets `fastReopen`, skips the duplicate output-folder warning, and restores the saved last-run recipe into the analysis toggles.
- Cross-stage integration: `ProjectHomeDialog.Choice` routing, `RecentProjectCard` status/relocation callbacks, `RecentProjectsStore.resolveStoreDir` migration, `PreFlightChecks.confirmProceedOnOutputFolder(..., knownProject)`, `ProjectStatusStore.readLastRunRecipe`, and `PipelineRecipe.toSelections`.
- Headless/CLI parity: CLI detection still returns before home-screen routing, and `ProjectHomeDialog.open` rejects headless mode like `ProjectBuilderDialog.open`.
- Builder preservation: new-project, foreign-folder, empty-folder, config-only FLASH-folder, and edit-existing routes still go through the full `ProjectBuilderDialog` path.
- Code quality: no orphan project-home-screen classes found, no unused import left in touched project-home code, and new public nested API types have one-line Javadocs.
- Documentation and vocabulary: `00_overview.md`, all eight `*_COMPLETED.md` files, `_verification.md`, and per-stage verifier notes match the implementation. No `docs/project-home-screen/README.md`, DPIA template, PDF, or generated report artifact exists for this plan, so those checks are N/A.

## Fixes applied
- `0bc84f2`: removed an unused import and added one-line Javadocs for new public nested project-home API types.

## Test results
- `bash mvnw -q clean compile '-Denforcer.skip=true'`: PASS. Java 25 emitted Maven/Jansi/Guava runtime warnings only; no project compiler warnings were emitted by the quiet compile gate.
- `bash mvnw test '-Denforcer.skip=true' -fae`: PASS with the plan-declared baseline exception. Full run: 2674 tests, 2 failures, 0 errors, 30 skipped. The only failures were `FilterParameterStageVaryButtonTest.branchedMacroDisablesVaryButtonWithTooltip` and `FilterParameterStageVaryButtonTest.bundledCompoundPresetsClassifyBranchedAndDisableVary`.
- `bash mvnw test '-Denforcer.skip=true' '-Dtest=!FilterParameterStageVaryButtonTest' -fae`: PASS. 2672 tests, 0 failures, 0 errors, 32 skipped.
- `bash mvnw test -Pintegration`: N/A. `pom.xml` has no Maven `integration` profile.
- `bash mvnw clean package '-Denforcer.skip=true' '-Dtest=!FilterParameterStageVaryButtonTest' -fae`: PASS on rerun. 2672 tests, 0 failures, 0 errors, 32 skipped. Produced `target/FLASH-4.0.0.jar`.

## Known limitations confirmed
- The analysis dialog's later "Change Directory" button still opens the full builder, not the new home screen. Stage 04 explicitly allowed this as a follow-up to avoid widening scope.
- Interactive Fiji click-through was not performed in this automated final pass. Manual verification step: launch FLASH in a headed Fiji session with a recent project, double-click the top recent, confirm the analysis dialog opens with the last-run recipe pre-ticked and no intermediate "Save & Open" or re-run warning, then check New project/Edit project still open the full builder.
- The two `FilterParameterStageVaryButtonTest` failures remain the unrelated plan-declared baseline and were not modified.

## Sign-off
**project-home-screen layer COMPLETE** — all project-home-screen checks pass within the documented baseline-test exception.
