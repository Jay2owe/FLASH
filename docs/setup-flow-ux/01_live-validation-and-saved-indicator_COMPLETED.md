# Stage 01 — Live validation + "Saved ✓" indicator

## Why this stage exists
Right now you only find out a value is wrong after clicking Next and getting a
modal error, and there is no visible confirmation that your progress is saved.
Both make the wizard feel fragile. The fix is small wiring on top of API that
already exists (`setPrimaryButtonEnabled`, `addHelpText`, `writtenAtMillis`).

## Prerequisites
None. (Best done alongside or after the config-save-load-safety folder, but it
does not depend on it.)

## Plan-review corrections (codex xhigh review — these OVERRIDE the sketch below)
- The "Saved" timestamp CANNOT come from `BinUserConfig` (it has no `writtenAtMillis`).
  `persistIncremental` returns void. After persisting, read the timestamp from the persisted
  config (`ChannelConfigIO.read(settingsDir).writtenAtMillis`, or have `persistIncremental`
  return it) and populate the label from that.
- Validation listeners attach to ALREADY-CREATED fields. Do not insert new
  `addStringField`/`addNumericField`/`addChoice`/`addToggle` before existing retrieval order
  (the per-type counter trap). Help/message rows are safe to append.

## Read first
- `docs/setup-flow-ux/00_overview.md`
- `src/main/java/flash/pipeline/ui/PipelineDialog.java`: `addStringField:594`,
  `addChoice:612`, `addHelpText:581`, `addMessage:636`,
  `setPrimaryButtonEnabled:975`, `wasBackPressed:1111`
- `src/main/java/flash/pipeline/ui/ToggleSwitch.java:62` (`addChangeListener`)
- In `CreateBinFileAnalysis.java` (navigate by symbol; numbers approximate, file
  is ~9,900 lines): the step-1 identity collector `collectBinConfigFromUser`
  (~2714) and the per-channel value fields; the per-step save calls
  `persistIncremental` (~1419) and where each dialog is shown.
- An existing example of `setPrimaryButtonEnabled` wired to a control, if any
  (grep the codebase).

## Scope
- Add a small reusable validation helper that, given a `JTextField` (or
  `JComboBox`) plus a validity predicate and a hint string, attaches a
  `DocumentListener` (or action listener) that updates an `addHelpText` label and
  calls `dialog.setPrimaryButtonEnabled(valid)`. Run it once on open to set the
  initial state.
- Apply it to the high-value fields where bad input currently slips through to a
  late error: particle size range (e.g. `min-max` / `100-Infinity`), display
  min-max range, numeric thresholds, and non-empty channel names. Reuse existing
  validation regexes/parsers if present; do not invent stricter rules than the
  current post-dialog checks.
- Add a "Saved ✓ <time>" `addMessage`/`addHelpText` label to each wizard step,
  updated after each `persistIncremental` from the config's `writtenAtMillis`
  (format the epoch millis as local `HH:mm`, Java 8 friendly, `Locale`-safe).
- Gate ONLY the primary/Next button. Leave Back and Cancel always enabled.

## Out of scope
- The review screen (stage 02) and presets/import (stage 03).
- Changing what counts as valid downstream, or the persistence format.
- Re-ordering existing field additions (would break `PipelineDialog`'s per-type
  counters). Append the help labels; do not move existing fields.

## Files touched
| Path | Action | Reason |
|------|--------|--------|
| `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java` | MODIFY | wire validation + "Saved ✓" into the step dialogs |
| `src/main/java/flash/pipeline/ui/PipelineDialog.java` | MODIFY (maybe) | small helper `bindValidation(field, predicate, hintLabel)` if it belongs here rather than in the analysis |
| `src/test/java/...` | NEW (optional) | unit test the validity predicates (the regex/range parsers), which are pure and testable without a UI |

## Implementation sketch
Validation wiring (grounded in the real API):
```java
JTextField size = dialog.addStringField("Particle size (min-max)", "100-Infinity", 12);
JLabel hint = dialog.addHelpText("");
Runnable check = new Runnable() {
    public void run() {
        boolean ok = isValidSizeRange(size.getText().trim());   // reuse existing parser
        hint.setText(ok ? "" : "Use min-max, for example 50-Infinity");
        dialog.setPrimaryButtonEnabled(ok);
    }
};
size.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
    public void insertUpdate(javax.swing.event.DocumentEvent e) { check.run(); }
    public void removeUpdate(javax.swing.event.DocumentEvent e) { check.run(); }
    public void changedUpdate(javax.swing.event.DocumentEvent e) { check.run(); }
});
check.run();   // set initial enabled state
```
With several validated fields on one screen, combine their predicates so Next is
enabled only when all are valid.

"Saved ✓" indicator:
```java
JLabel saved = dialog.addHelpText("");
// after a successful persistIncremental(...):
saved.setText("Saved ✓ " + formatTime(cfg.writtenAtMillis));   // HH:mm, local
```
`formatTime` via `new SimpleDateFormat("HH:mm")` (Java 8 OK) or
`Instant.ofEpochMilli(...).atZone(ZoneId.systemDefault())`.

## Exit gate
1. `bash mvnw clean package -Denforcer.skip=true` compiles clean.
2. Pure-logic unit tests for the validity predicates pass (valid + invalid
   examples for each field type).
3. Manual: open Set Up Configuration, type an invalid size range => Next is
   disabled with a hint; fix it => Next enables. Back and Cancel stay enabled
   throughout.
4. Manual: after moving past a step, the screen shows "Saved ✓ <time>".
5. Manual headless run: no NPE, no blocking dialog, wizard logic unchanged
   (the validation/labels are simply not shown).

## Known risks
- Predicate must not be stricter than the existing post-dialog validation, or a
  resumed/old value could leave Next permanently disabled. If unsure, allow the
  current saved value through.
- `PipelineDialog` per-type field counters: only ever append help/message
  labels; never reorder `addStringField`/`addNumericField`/`addChoice` calls.
- Keep all listener work on the EDT (it already is); the predicates must be cheap
  (no IO).
