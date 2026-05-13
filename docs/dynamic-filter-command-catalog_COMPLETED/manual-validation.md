# Dynamic Filter Command Catalog Manual Validation

Date: 2026-05-12
Fiji path: local Fiji.app installation used for manual validation.
FLASH jar/build: target\FLASH-4.0.0.jar from git a3fac63 before this validation stage commit

## Checks

1. Open Fiji and launch FLASH setup analysis.
   - Result: Not run in this non-interactive Codex CLI session.
   - Expected: FLASH opens without startup command-catalog errors.
2. Enter filter setup for a channel and open `+ Add filter...`.
   - Result: Not run in this non-interactive Codex CLI session.
   - Expected: The picker opens and remains searchable.
3. Search for a known fast command such as `Median`.
   - Result: Covered by focused unit tests; interactive Fiji check not run.
   - Expected: Visible as `[fast]`; adding it behaves as before.
4. Search for a Fiji command discovered through `Menus.getCommands()`.
   - Result: Covered by injected-registry unit tests; interactive Fiji check not run.
   - Expected: Visible as `[legacy]`.
5. Add a legacy command that records cleanly.
   - Result: Unit tests verify legacy append with captured options emits `executionTier=legacy` and the recorded options; interactive Fiji dialog check not run.
   - Suggested bundled target: `Despeckle`.
   - Suggested extra-plugin target from this Fiji install: `Anisotropic Diffusion 2D` if it records a clean `run(...)` line.
   - Expected: Fiji parameter dialog appears; after OK, accordion gains one row; emitted macro contains `executionTier=legacy` and the recorded options.
6. Cancel a legacy command dialog.
   - Result: Unit tests verify no recorded `run(...)` line is treated as cancelled and setup legacy failure leaves the macro unchanged; interactive Fiji dialog check not run.
   - Expected: Filter is unchanged; no blank command is appended.
7. Try an unsuitable command if practical, such as a command that is not an image filter.
   - Result: Headless/no-preview failure path covered by unit tests; interactive Fiji check not run.
   - Expected: Clear failure/status; filter is unchanged.
8. Run filter preview after adding a valid legacy command.
   - Result: Unit tests verify legacy DAG emission and legacy executor routing; interactive Fiji preview/window-cleanup check not run.
   - Expected: Preview completes or reports the plugin command's error clearly; no stray windows remain.

## Result

Automated validation: PASS.

Interactive Fiji validation: NOT RUN. This worker can locate the local Fiji install and build/test the code, but it cannot visually operate the setup wizard and plugin dialogs. Before public release, run the eight checks above in Fiji and record the actual command/plugin used, especially the valid extra-plugin command and cancelled/unsuitable command behavior.
