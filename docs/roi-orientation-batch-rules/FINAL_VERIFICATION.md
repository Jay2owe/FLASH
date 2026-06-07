# roi-orientation-batch-rules - Final Verification

**Date:** 2026-06-07
**Verifier:** codex final-verify

## What was verified
- End-to-end behaviour: saved presets persist under `FLASH/Config/.settings/orientation_presets.json`; repeat-last, preset apply, same-hemisphere, all-images literal, and mirrored LH/RH rules are covered by controller and panel tests; active rules render prefetched images before first `show()`.
- Cross-stage integration: `BroadcastRule` math is called only through `OrientationBatchController.applyActiveRuleOnOpen`; `RoiOrientationPanel` subscribes to the controller actions; `DrawAndSaveROIsAnalysis` constructs one controller per run, binds every `PreparedImage`, applies active rules before showing, and passes the controller to the panel.
- Tests and build: wrapper-based compile, full unit suite, and package all pass; no `integration` Maven profile exists in `pom.xml`.
- Code quality: new public classes have class Javadocs; pure model/controller/codec classes have no `ij.*` imports; new classes are all referenced by production callers or tests; no draft TODO/commented-out code was found in the touched feature surface.
- Documentation and vocabulary: panel instructions and analysis-help catalog mention Repeat last, saved presets, apply-to buttons, same-hemisphere scope, all-images literal scope, and LH/RH mirroring; vocabulary matches `THIS_IMAGE`, `SAME_HEMISPHERE`, `ALL_LITERAL`, and `ALL_MIRRORED` semantics.
- Required inputs: `CLAUDE.md` was requested but is absent in this checkout, so verification used the supplied AGENTS instructions plus `docs/roi-orientation-batch-rules/00_overview.md` house rules. Existing dirty deconvolution files were left untouched.

## Fixes applied
- (none)

## Test results
- mvn clean compile: PASS via `bash mvnw clean compile '-Denforcer.skip=true'`. Maven/JDK 25 emitted wrapper/runtime warnings about Jansi native access and Guava `Unsafe`; javac produced no source warnings.
- mvn test: PASS. Standalone `bash mvnw -q '-Denforcer.skip=true' test` exited 0; the full package run reported 2,838 tests, 0 failures, 0 errors, 30 skipped.
- mvn test -Pintegration: N/A. No `integration` profile is declared in `pom.xml`.
- mvn package: PASS via `bash mvnw clean package '-Denforcer.skip=true'`; deployable artifact confirmed at `target/FLASH-4.0.0.jar`.

## Known limitations confirmed
- `SAME_HEMISPHERE` remaining count is an upper bound over later images because the controller deliberately does not scan future image hemispheres or change the one-image-at-a-time loop.
- `Repeat last` is button-only; no keyboard shortcut was added.
- Full hands-on GUI smoke verification still requires a display session with a real multi-image project. Automated tests cover the model math, persisted presets, Swing panel controls, and pre-show rule application path.
- The optional helper-diagram generator was not regenerated during final verification; user-facing help text is current.

## Sign-off
**roi-orientation-batch-rules layer COMPLETE**
