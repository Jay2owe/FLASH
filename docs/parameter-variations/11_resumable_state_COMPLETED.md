# Step 11 — Resumable state (variations_state.json)

## Goal

A slow Cellpose sweep that the user closed mid-run shouldn't be lost. On dialog re-open, offer "Resume from 12/25 completed" if the sweep matches what's on disk.

## Pre-conditions

- Steps 01–10 complete.
- `VariationCache` already persists label images on disk under `.bin/variations_cache/`.

## Deliverables

### `VariationStateStore.java`

`flash.pipeline.ui.variations.state.VariationStateStore`

Schema (saved as `.bin/variations_state.json` in the project's bin folder, NOT per-channel — single shared file):

```json
{
  "version": 1,
  "image_hash": "abc123def456...",
  "channel": "DAPI",
  "method": "Cellpose",
  "sweep": {
    "diameter": [25, 30, 35],
    "flow_threshold": [0.3, 0.5, 0.7],
    "cellprob_threshold": [0.0]
  },
  "crop": { "mode": "CENTRE_256", "bounds": null },
  "completed": [
    { "combo_id": "0_0_0", "label_cache_key": "...", "n_objects": 42, "duration_ms": 4500 }
  ],
  "started_at": "2026-05-13T11:14:00Z",
  "updated_at": "2026-05-13T11:18:33Z"
}
```

API:

```java
public class VariationStateStore {
    public VariationStateStore(Path binFolder);
    public Optional<VariationState> load();
    public void save(VariationState state);
    public void clear();
}
```

Use the existing `flash.pipeline.ui.wizard.JsonIO` / `flash.pipeline.intelligence.MiniJson` helpers. Build `LinkedHashMap` / `List` objects explicitly so output order is stable. Don't add a JSON dependency and don't hand-roll a second parser.

### Save cadence

Save after every cell completes. Cheap (< 10ms even at 100 cells) and survives crashes.

### Dialog integration

On dialog open:

```java
Optional<VariationState> prior = stateStore.load();
prior.ifPresent(s -> {
    if (s.method().equals(method) && s.channel().equals(channelName) && s.imageHash().equals(currentImageHash)) {
        int n = s.completed().size();
        int total = s.sweep().cellCount();
        int resume = JOptionPane.showConfirmDialog(this,
            "A previous sweep is " + n + "/" + total + " complete. Resume?",
            "Resume Parameter Variations", JOptionPane.YES_NO_CANCEL_OPTION);
        if (resume == JOptionPane.YES_OPTION) {
            applyState(s);
            return;
        }
    }
});
```

`applyState`:
- Populates the parameter editor with `s.sweep`.
- Pre-fills `crop` from `s.crop`.
- Builds cell panels and marks the completed ones as already-done (load labels from `VariationCache` by `label_cache_key`).
- The executor's dispatch order starts from the first uncompleted cell.

If the user picks NO, the state is cleared and a fresh sweep begins.

If `image_hash` doesn't match (user changed the source image), the state is silently discarded.

### Executor hook

`VariationExecutor` calls `stateStore.recordCompletion(combo, cacheKey, nObjects, durationMs)` from `process()` after each cell. The store internally debounces disk writes to once every 500ms.

On cancel: state remains on disk. The user can resume later.

On successful completion: state is cleared.

## Acceptance

- `VariationStateStoreTest`:
  - Save + load round-trip.
  - Missing file → empty optional.
  - Corrupt file → empty optional, no exception.
  - Mismatched image_hash → silently discarded by the dialog logic.
- Manual: start a 10-cell Cellpose sweep, let 4 cells finish, close the dialog. Re-open. Dialog offers resume. Accept. The remaining 6 cells render; the first 4 appear pre-populated.

## Tests location

`src/test/java/flash/pipeline/ui/variations/state/VariationStateStoreTest.java`

## Notes / gotchas

- One state file per `.bin/` folder. If the user has multiple channels on different methods, that's fine — the dialog filters by `(channel, method)` and ignores others. The store overwrites on save (one active sweep at a time).
- `image_hash` should be the SHA-256 of the channel's pixel bytes, identical to the cache-key hash. Reuse the helper.
- Combo IDs in the state file: encode as `"i0_i1_i2"` where each index is the position of the chosen value in that parameter's value list. Stable, JSON-safe, and survives small edits to the value list (a mismatched ID is just treated as missing — the cell re-runs).
- Don't persist the actual label image — it's already in `VariationCache`. The state only references it by cache key.
- Old state from prior FLASH versions: if `version` is missing or > 1, treat as corrupt and ignore.
