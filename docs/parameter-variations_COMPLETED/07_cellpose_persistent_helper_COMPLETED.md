# Step 07 — Cellpose: persistent Python helper + one-shot fallback

## Goal

Make Cellpose sweeps usable. A 25-cell sweep through the current `Cellpose3DRunner` runs the model load (5–15 s) × 25 = 4–6 minutes of pure overhead before any inference. A tiny persistent Python helper that loads the model once and reads JSON-line param sets from stdin cuts that to ~10 s + 5 s × 25 = ~2 min. Falls back to one-shot if the helper fails to launch or stalls.

## Pre-conditions

- Steps 01–06 complete.
- `Cellpose3DRunner` works (existing code).

## Deliverables

### `src/main/resources/flash/pipeline/cellpose/cellpose_loop.py` (bundled JAR resource)

Add under `src/main/resources/flash/pipeline/cellpose/cellpose_loop.py`. Start from this skeleton, then mirror the real options in `Cellpose3DRunner.buildCellposeCommand(...)` (`--chan`, `--chan2`, `--channel_axis`, `--do_3D`, `--z_axis`, `--anisotropy`) so persistent and one-shot previews segment the same image shape. This will be longer than the original 60-line brainstorm sketch; keep it small, but correctness beats brevity.

```python
#!/usr/bin/env python
"""Persistent Cellpose worker for FLASH Parameter Variations.

Usage: cellpose_loop.py <model> <image_tif> <out_dir> [--gpu] [--has-second-channel]
                    [--channel-axis N] [--do-3d] [--z-axis N] [--anisotropy X]

Reads JSON-line param sets from stdin:
  {"id": "v01", "diameter": 30.0, "flow_threshold": 0.4, "cellprob_threshold": 0.0}

Writes one line to stdout per request:
  {"id": "v01", "mask_path": "<out_dir>/v01_cp_masks.tif", "duration_ms": 4823}
  or {"id": "v01", "error": "..."}
"""
import sys, json, time, traceback
from pathlib import Path
from cellpose import models, io

def main():
    model_path = sys.argv[1]
    image_path = sys.argv[2]
    out_dir = Path(sys.argv[3])
    use_gpu = "--gpu" in sys.argv
    out_dir.mkdir(parents=True, exist_ok=True)

    model = models.CellposeModel(gpu=use_gpu, pretrained_model=model_path)
    img = io.imread(image_path)
    print(json.dumps({"ready": True}), flush=True)

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
            t0 = time.time()
            # Fill in channel_axis / z_axis / anisotropy from argv so this
            # matches Cellpose3DRunner.buildCellposeCommand(...).
            masks, _, _ = model.eval(
                img,
                diameter=req["diameter"],
                flow_threshold=req["flow_threshold"],
                cellprob_threshold=req["cellprob_threshold"],
            )
            mask_path = out_dir / f"{req['id']}_cp_masks.tif"
            io.imsave(str(mask_path), masks)
            print(json.dumps({
                "id": req["id"],
                "mask_path": str(mask_path),
                "duration_ms": int((time.time() - t0) * 1000),
            }), flush=True)
        except Exception as e:
            print(json.dumps({
                "id": req.get("id", "?"),
                "error": str(e),
                "traceback": traceback.format_exc(),
            }), flush=True)

if __name__ == "__main__":
    main()
```

The script handles only `diameter`, `flow_threshold`, `cellprob_threshold`. `MODEL` switching requires reloading the model — fallback to one-shot for model sweeps (cellpose model load is what we're trying to avoid).

### `CellposePersistentWorker.java`

`flash.pipeline.cellpose.CellposePersistentWorker`

Responsibilities:
- Extract `cellpose_loop.py` from JAR resources to a temp file.
- Use `CellposeRuntime.probeConfigured()` to find the configured Python executable.
- Spawn `python <tempScript> ...` as a subprocess, capture stdin/stdout/stderr. Do not use `python -m cellpose_loop` for an extracted temp file.
- Set `OMP_NUM_THREADS`, `MKL_NUM_THREADS`, `OPENBLAS_NUM_THREADS`, and `NUMEXPR_NUM_THREADS` from `GpuConcurrency.threadsPerInference()`, matching `Cellpose3DRunner.runCellposeCommand(...)`.
- Acquire `GpuConcurrency.gpuSemaphore()` before launching the helper and release it in `close()`. Existing one-shot Cellpose also gates CPU/GPU subprocesses this way, even when `useGpu=false`.
- Wait for `{"ready": true}` on stdout with a 30s timeout.
- Expose `Future<CellposeWorkerResult> submit(CellposeWorkerRequest request)` where `CellposeWorkerResult` contains request id, nullable `ImagePlus labelImage`, duration, and nullable error text:
  - Build JSON request, write to stdin, flush.
  - Read one stdout line, parse mask path/duration/error.
  - Because `CellposePersistentWorker` lives in `flash.pipeline.cellpose`, it can reuse package-private `Cellpose3DRunner.readMaskImage(...)`; the UI strategy then wraps the worker result in `VariationResult`.
- Expose `void close()`: close stdin, `process.waitFor(5, SECONDS)`, `destroyForcibly()`, `taskkill /F /T /PID` on Windows if still alive.

Internally use a single-threaded `ExecutorService` so requests are serialised (the helper is single-threaded by design — Cellpose holds one GPU context).

### `CellposePersistent.java` (strategy)

`flash.pipeline.ui.variations.strategy.CellposePersistent implements VariationStrategy`

`dispatch(sweep, publisher, cancelCheck)`:
1. Validate that `MODEL` is not a swept parameter (eligibility check).
2. Write cropped input to a temp TIFF.
3. Construct `CellposePersistentWorker` and wait for ready signal inside `dispatch(...)`, not on the EDT.
4. For each combo in dispatch order:
   - Cancel check.
   - Cache check; publish on hit.
   - Submit request to worker; receive `VariationResult`.
   - Cache + publish.
5. Close worker.

### `CellposeOneShot.java` (fallback strategy)

`flash.pipeline.ui.variations.strategy.CellposeOneShot implements VariationStrategy`

Wraps the existing `CellposeParameterStage.PreviewAdapter.runPreview(...)` per cell, sequentially. The adapter calls `Cellpose3DRunner.run(...)`, which is already GPU-semaphore-gated. Used when:
- `MODEL` is swept (each cell needs a different model anyway).
- Persistent worker fails to launch within 30s.
- Persistent worker dies mid-sweep; the strategy closes it and runs `CellposeOneShot` on the remaining cells.

### Optional one-shot cancellation hook

Current reality: `Cellpose3DRunner.runCellposeCommand(...)` starts a local `Process process = pb.start()` at `Cellpose3DRunner.java:226`, reads stdout, and then calls `process.waitFor()` at line 241. The subprocess is not reachable from outside the method.

For v1, persistent-helper cancellation is handled by `CellposePersistentWorker.close()` because it owns the process. One-shot fallback may cancel only between cells unless this optional overload is added:

```java
public static ImagePlus runCancellable(ImagePlus input, ImagePlus companionInput, String model,
                                       double diameter, double flowThreshold, double cellprobThreshold,
                                       boolean useGpu, String channelName,
                                       AtomicReference<Process> processSink);
```

Inside the existing `runCellposeCommand`, set `processSink.set(process)` before `process.waitFor()`. The strategy's cancel path reads the reference, calls `destroy()` → `destroyForcibly()` → `taskkill /F /T /PID <pid>` on Windows.

`process.pid()` is Java 9+. FLASH targets Java 8 per `CLAUDE_CONTEXT.md`. Use `ProcessHandle` if available (Java 9+) or fall back to reflection / `Field f = process.getClass().getDeclaredField("pid")` for Java 8. Wrap in try/catch and degrade gracefully (one-shot kill via `destroyForcibly` only) if reflection fails.

### Chooser update

```java
case CELLPOSE:
    if (sweep.sweptIds().contains(MODEL)) return new CellposeOneShot(...);
    return new CellposePersistent(...); // dispatch() launches helper; on launch/ready failure it runs CellposeOneShot for remaining cells
```

Do not launch Python from the chooser on the EDT. Helper extraction, ready timeout, and fallback happen inside the strategy worker.

## Acceptance

- `CellposePersistentWorkerTest`: spawn the helper against a tiny synthetic image, send 3 param requests, verify all return masks within 3× the time of a normal Cellpose run. Skip if `cellpose` Python module is unavailable in test env.
- `CellposeOneShotTest`: 2-cell sweep via the existing `Cellpose3DRunner` works (regression guard).
- `VariationStrategyChooserTest` extended: Cellpose with MODEL swept -> `CellposeOneShot`. Cellpose without MODEL -> `CellposePersistent` wrapper; a separate strategy test verifies it falls back to one-shot when the helper cannot start.
- Manual: 5-cell sweep of `cellprob_threshold` finishes in ~30s after the initial helper-load wait, with tile-by-tile fill-in.
- Manual cancellation: launch a 10-cell persistent-helper sweep, hit cancel after cell 3. The remaining 7 cells stop within 5 seconds and no orphaned Python processes remain (`Get-Process python` or task manager). If one-shot fallback is active and the optional `runCancellable(...)` overload was not implemented, cancellation may wait for the current Cellpose subprocess to finish.

## Tests location

`src/test/java/flash/pipeline/cellpose/CellposePersistentWorkerTest.java`
`src/test/java/flash/pipeline/ui/variations/strategy/CellposePersistentTest.java`
`src/test/java/flash/pipeline/ui/variations/strategy/CellposeOneShotTest.java`

## Notes / gotchas

- Bundle the Python script in the JAR via Maven resources. `getClass().getResourceAsStream("/flash/pipeline/cellpose/cellpose_loop.py")` retrieves it; copy to `Files.createTempFile("flash_cellpose_loop_", ".py")`.
- The helper's stdout MUST be line-buffered — `flush=True` in every `print` is essential. JSON parsing requires complete lines.
- stderr from the helper should also be captured (separate thread reading the stream into a ring buffer). On error, surface stderr in the `VariationResult.error` field.
- Cellpose 4.x renamed `pretrained_model` to `pretrained_model` (unchanged) but added `gpu_number` etc. Verify against the version on the user's machine before assuming arg names — the helper script is the right place to be defensive about Cellpose API drift.
- Companion-channel preprocessing (line 129-145 of `Cellpose3DRunner`) — for v1, send the merged 2-channel TIFF to the helper just like the one-shot path does. Don't try to optimise companion merge into the persistent worker.
- Windows-specific: `taskkill` is at `C:\Windows\System32\taskkill.exe` — invoke as `new ProcessBuilder("taskkill", "/F", "/T", "/PID", String.valueOf(pid)).start()`. Don't shell out via `cmd /c` — it loses the exit code.
- If the user has no Python at all, the helper launch fails fast inside the strategy and routes to one-shot. Don't surface this as an error unless one-shot also fails.
