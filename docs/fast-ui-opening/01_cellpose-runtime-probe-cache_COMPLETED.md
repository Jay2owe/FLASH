# Stage 01: Cellpose Runtime Probe Cache + Async Hint Label

## Why

`CellposeRuntime.probeConfigured()` is an EDT blocker when the configured Python path exists: it currently just calls `probe(getPythonPath())` (`src/main/java/flash/pipeline/cellpose/CellposeRuntime.java:118-120`), and `probe(String)` starts the configured Python process, imports Cellpose/Torch, waits up to 20 seconds, and parses version/GPU output (`src/main/java/flash/pipeline/cellpose/CellposeRuntime.java:155-223`). Empty or missing paths return quickly (`src/main/java/flash/pipeline/cellpose/CellposeRuntime.java:139-152`), so the expensive case is "configured but cold".

Several UI construction paths call that synchronous probe before first paint, including the segmentation methods dialog (`src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:558-587`), the embedded Cellpose parameter stage factory (`src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:6024-6126`), the channel setup wizard constructor (`src/main/java/flash/pipeline/analyses/wizard/ChannelSetupWizard.java:45-52`), and the dependency warning rows built before the main pipeline dialog shows (`src/main/java/flash/pipeline/FLASH_Pipeline.java:564`, `src/main/java/flash/pipeline/FLASH_Pipeline.java:1247-1250`, `src/main/java/flash/pipeline/runtime/DependencyRegistry.java:913-928`).

We want one shared cached result that UI-opening code can read without blocking, plus hint labels that paint "Checking Cellpose..." immediately and update when the async probe lands.

## Prerequisites

- Read `docs/fast-ui-opening/00_overview.md:1-64`.
- Use Java 8-compatible APIs only. `CompletableFuture` is allowed; Java 9+ APIs such as `CompletableFuture.orTimeout` are not.
- There is no repo-root `CLAUDE.md` or `AGENTS.md` in this checkout; follow the session instructions supplied with this stage instead.

## Read First

- `src/main/java/flash/pipeline/cellpose/CellposeRuntime.java:35-62` - `Status` public fields and `summary()` behavior. Downstream code reads `status.ready`, `status.message`, `status.details`, and `status.gpuAvailable` directly.
- `src/main/java/flash/pipeline/cellpose/CellposeRuntime.java:108-120` - Python path getter/setter and current uncached `probeConfigured()`.
- `src/main/java/flash/pipeline/cellpose/CellposeRuntime.java:139-223` - actual runtime probe body; keep the external checks and messages unchanged.
- `src/main/java/flash/pipeline/cellpose/CellposeRuntime.java:243-255` - setup gate calls `probe(pythonPath)` directly for explicit path validation.
- `src/main/java/flash/pipeline/cellpose/CellposeRuntime.java:293-300` - `defaultSetupLabel()` is a second synchronous `probeConfigured()` caller.
- `src/main/java/flash/pipeline/cellpose/CellposeRuntime.java:372-383` and `src/main/java/flash/pipeline/cellpose/CellposeRuntime.java:421-429` - CPU/GPU install verification gets a fresh `Status`; cache invalidation/update must account for these.
- `src/main/java/flash/pipeline/cellpose/CellposeRuntime.java:493-505` and `src/main/java/flash/pipeline/cellpose/CellposeRuntime.java:566-571` - explicit-path setup flows use `probe(String)` and must not be incorrectly served by the current-path cache.
- `src/main/java/flash/pipeline/cellpose/CellposeRuntime.java:687-696` - NVIDIA check is a separate `nvidia-smi` process, not the Cellpose Python probe.
- `src/main/java/flash/pipeline/ui/config/CellposeParameterStage.java:66-69` - `RuntimeAdapter` currently exposes only synchronous `runtimeSummary()` plus GPU install hooks; it needs an async/status-shaped API.
- `src/main/java/flash/pipeline/ui/config/CellposeParameterStage.java:172-212` - constructor stores `defaultUseGpu` before controls are built.
- `src/main/java/flash/pipeline/ui/config/CellposeParameterStage.java:230-264` - `buildControls()` builds the hint row before returning to the dialog.
- `src/main/java/flash/pipeline/ui/config/CellposeParameterStage.java:570-590` - `buildHintRow()` calls `refreshRuntimeLabel()` at line 579 during control construction.
- `src/main/java/flash/pipeline/ui/config/CellposeParameterStage.java:777-784` - `refreshRuntimeLabel()` currently blocks through `runtimeAdapter.runtimeSummary()` at line 779.
- `src/main/java/flash/pipeline/ui/config/CellposeParameterStage.java:1089-1133` - GPU support button path calls `nvidiaGpuLikelyAvailable()` at line 1091 and refreshes the runtime label at line 1132.
- `src/main/java/flash/pipeline/ui/config/CellposeParameterStage.java:350-364` - `onLeave()` is the available lifecycle hook for clearing async UI callbacks; stages do not get a separate dispose callback.
- `src/main/java/flash/pipeline/ui/config/CellposeParameterStage.java:1380-1393` - noop adapter implementation must compile after the adapter API changes.
- `src/main/java/flash/pipeline/ui/config/ConfigQcDialog.java:350-388` - stage controls are built and `onEnter()` is called during dialog rebuild.
- `src/main/java/flash/pipeline/ui/config/ConfigQcDialog.java:673-690` - dialog close calls `leaveCurrentStage()`, which calls stage `onLeave()`.
- `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:558-587` - segmentation methods page blocks at line 566 before `dialog.showDialog()` at line 587; its help label captures `cellposeStatus` at lines 577-582.
- `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:597-604` - after user accepts Cellpose on the segmentation methods page, this is a real run/setup gate and may remain synchronous.
- `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:4577-4722` - legacy non-embedded Cellpose parameter dialog blocks at line 4587 and renders the runtime help text at lines 4719-4722.
- `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:5989-6013` - embedded Cellpose QC gate and dialog creation path.
- `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:6024-6126` - `createCellposeParameterStage()` blocks at line 6029, and the production `RuntimeAdapter.runtimeSummary()` blocks again at line 6104. This `6104` caller was missing from the original plan.
- `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:6112-6114` - production adapter calls the separate NVIDIA check.
- `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:7977-7984` - segmentation switcher keeps a synchronous gate after the user selects Cellpose.
- `src/main/java/flash/pipeline/analyses/wizard/ChannelSetupWizard.java:45-52` and `src/main/java/flash/pipeline/ui/wizard/SegmentationEnginePicker.java:18-33` - wizard availability is a fixed boolean today.
- `src/main/java/flash/pipeline/ui/wizard/SegmentationEnginePicker.java:149-168` - treating unknown Cellpose availability as `false` changes recommendations for complex crowded markers.
- `src/main/java/flash/pipeline/FLASH_Pipeline.java:564`, `src/main/java/flash/pipeline/FLASH_Pipeline.java:1247-1250`, `src/main/java/flash/pipeline/runtime/DependencyRegistry.java:913-928`, and `src/main/java/flash/pipeline/runtime/DependencyStatus.java:8-12` - dependency attention rows reach the Cellpose probe before the main dialog paints, and `DependencyStatus` has no `UNKNOWN` state.
- Exact current `probeConfigured()` callers from `rg -n "CellposeRuntime\.probeConfigured" src/main/java src/test/java`: `src/main/java/flash/pipeline/cellpose/Cellpose3DRunner.java:48`, `src/main/java/flash/pipeline/cellpose/CellposePersistentWorker.java:152`, `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:566`, `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:602`, `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:4587`, `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:6029`, `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:6104`, `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:7981`, `src/main/java/flash/pipeline/analyses/wizard/ChannelSetupWizard.java:51`, `src/main/java/flash/pipeline/runtime/DependencyRegistry.java:917`, `src/test/java/flash/pipeline/cellpose/CellposePersistentWorkerTest.java:27`, `src/test/java/flash/pipeline/ui/variations/integration/CellposeOneShotIntegrationTest.java:33`, and `src/test/java/flash/pipeline/ui/variations/integration/CellposePersistentIntegrationTest.java:33`.

## Scope

- Introduce a process-wide cache for `CellposeRuntime.Status` in `src/main/java/flash/pipeline/cellpose/CellposeRuntime.java:18-27`.
  - `Status cachedStatus()` returns the last-known real status immediately, or `Status.unknown()` if nothing has resolved yet.
  - `CompletableFuture<Status> probeAsync()` coalesces concurrent callers onto one in-flight probe and returns a completed future when a fresh cached status is available.
  - `void invalidateCache()` clears cached state for explicit refreshes and tests.
  - `probeConfigured()` remains the synchronous escape hatch for run gates such as `src/main/java/flash/pipeline/cellpose/Cellpose3DRunner.java:48`, `src/main/java/flash/pipeline/cellpose/CellposePersistentWorker.java:152`, `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:602`, and `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:7981`.
- Add a sentinel without changing existing field meanings: `Status.unknown()` should set `ready=false`, `configured=false`, `executableExists=false`, `gpuAvailable=false`, a clear "Checking Cellpose..." message, and a new explicit marker such as `public final boolean unknown` or `public boolean isUnknown()`. Real statuses from `probe(String)` at `src/main/java/flash/pipeline/cellpose/CellposeRuntime.java:139-223` must report `unknown=false`.
- Use split freshness rules instead of one blanket 30 second TTL:
  - A non-ready real status can expire after `MISSING_TTL_MS = 30_000L`, which limits lockout after an external install.
  - A ready real status should live longer, for example `READY_TTL_MS = 5 * 60_000L`, because re-probing a working Python runtime every 30 seconds can reintroduce UI churn during setup.
  - `setPythonPath()` at `src/main/java/flash/pipeline/cellpose/CellposeRuntime.java:112-116`, successful CPU install at `src/main/java/flash/pipeline/cellpose/CellposeRuntime.java:380-383`, and successful GPU verification at `src/main/java/flash/pipeline/cellpose/CellposeRuntime.java:421-429` must invalidate or update the cache immediately so TTL is not the only freshness mechanism.
- Change UI-opening call sites to avoid blocking:
  - `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:566` should use `cachedStatus()` for initial segmentation help/defaults, kick off `probeAsync()`, and update the relevant `JLabel`s when the result lands.
  - `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:4587` and `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:4719-4722` should render cached/unknown runtime text before the legacy dialog shows, then update the help label asynchronously if that legacy path remains live.
  - `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:6029` should use cached status for `defaultUseGpu`; do not block before constructing `CellposeParameterStage`.
  - `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:6104` must be removed from the blocking path by changing the `RuntimeAdapter` API used by `CellposeParameterStage`.
  - `src/main/java/flash/pipeline/analyses/wizard/ChannelSetupWizard.java:51` must not simply map unknown to unavailable without compensation, because `src/main/java/flash/pipeline/ui/wizard/SegmentationEnginePicker.java:160-168` would silently change complex-marker recommendations. Move the blocking check to a later user action or make availability re-evaluable after `probeAsync()` completes.
  - `src/main/java/flash/pipeline/runtime/DependencyRegistry.java:917` is a real dialog-opening path through `src/main/java/flash/pipeline/FLASH_Pipeline.java:564` and `src/main/java/flash/pipeline/FLASH_Pipeline.java:1247-1250`; either prewarm with `probeAsync()` before dependency rows are built or change the dependency UI path so the first main dialog paint does not wait for Cellpose.
- Do not back `RuntimeAdapter.nvidiaGpuLikelyAvailable()` with `Status.gpuAvailable`. The former checks `nvidia-smi` at `src/main/java/flash/pipeline/cellpose/CellposeRuntime.java:687-693`; the latter checks Torch CUDA inside the configured Cellpose Python at `src/main/java/flash/pipeline/cellpose/CellposeRuntime.java:167-173`. They answer different questions. If that button check becomes a blocker, give `isNvidiaGpuLikelyAvailable()` its own tiny timeout/cache, separate from this stage's Cellpose runtime cache.

## Out Of Scope

- Changing the actual Python/Cellpose/Torch validation script in `src/main/java/flash/pipeline/cellpose/CellposeRuntime.java:155-173`.
- Changing explicit-path setup probing in `src/main/java/flash/pipeline/cellpose/CellposeRuntime.java:243-255`, `src/main/java/flash/pipeline/cellpose/CellposeRuntime.java:493-505`, or `src/main/java/flash/pipeline/cellpose/CellposeRuntime.java:566-571` to use the current-path cache.
- Cellpose model download UX.
- Reworking non-Cellpose runtime probes such as StarDist or mcib3d.

## Files Touched

- `src/main/java/flash/pipeline/cellpose/CellposeRuntime.java`
- `src/main/java/flash/pipeline/ui/config/CellposeParameterStage.java`
- `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java`
- `src/main/java/flash/pipeline/analyses/wizard/ChannelSetupWizard.java`
- `src/main/java/flash/pipeline/runtime/DependencyRegistry.java`
- Possibly `src/main/java/flash/pipeline/FLASH_Pipeline.java` if dependency-row prewarming/deferment is implemented there.
- `src/test/java/flash/pipeline/cellpose/CellposeRuntimeCacheTest.java`
- `src/test/java/flash/pipeline/ui/config/CellposeParameterStageTest.java`
- Possibly `src/test/java/flash/pipeline/analyses/wizard/ChannelSetupWizardTest.java` if wizard availability timing changes.

## Implementation Sketch

1. In `CellposeRuntime`, add cache state near the existing constants at `src/main/java/flash/pipeline/cellpose/CellposeRuntime.java:18-27`:
   - `private static volatile Status cached;`
   - `private static volatile long cachedAtMs;`
   - `private static volatile CompletableFuture<Status> inFlight;`
   - `private static long cacheGeneration;` guarded by `LOCK`, so an old in-flight probe cannot overwrite the cache after `invalidateCache()`.
   - `private static final long MISSING_TTL_MS = 30_000L;`
   - `private static final long READY_TTL_MS = 5 * 60_000L;`
   - `private static final Object LOCK = new Object();`
   - A single-thread daemon `ExecutorService`; use a Java 8 `ThreadFactory` so this background thread cannot keep Fiji alive on shutdown.
2. In `CellposeRuntime.Status` at `src/main/java/flash/pipeline/cellpose/CellposeRuntime.java:35-62`, add an explicit unknown marker. Do not make downstream code infer unknown from `ready=false`, because existing code already treats non-ready as missing at `src/main/java/flash/pipeline/runtime/DependencyRegistry.java:918-927`, `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:6030-6032`, `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:6105-6109`, and `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:7617-7619`.
3. Rename the current `probeConfigured()` implementation at `src/main/java/flash/pipeline/cellpose/CellposeRuntime.java:118-120` to a private or package-private `probeConfiguredInternal()` that calls `probe(getPythonPath())`. `probeAsync()` must call `probeConfiguredInternal()`, not public `probeConfigured()`, to avoid recursion.
4. Implement `cachedStatus()` so it never starts work and never blocks. It should return the latest real `cached` value even if stale, because UI labels can show last-known status while `probeAsync()` refreshes in the background. Return `Status.unknown()` only when no real status has ever landed.
5. Implement `probeAsync()` so it synchronizes only while checking/updating `inFlight`, then releases `LOCK` before the probe runs. If a cached real status is still fresh under the split TTL rules, return `CompletableFuture.completedFuture(cached)`. Otherwise start one executor task, capture `cacheGeneration`, and in `whenComplete` update `cached`, `cachedAtMs`, and `inFlight=null` only if the generation still matches.
6. Implement `probeConfigured()` as the synchronous gate: return a fresh cached real status when available, otherwise wait on `probeAsync().get()`. It must not hold `LOCK` while waiting. Preserve the no-checked-exception signature by converting `InterruptedException` into an interrupted-thread status and converting `ExecutionException` into a non-ready status with details.
7. Make `invalidateCache()` increment `cacheGeneration`, clear `cached/cachedAtMs`, and clear `inFlight` under `LOCK`. Do not let an already-running old probe repopulate the cache after invalidation; the generation check from step 5 is required.
8. After `setPythonPath()` at `src/main/java/flash/pipeline/cellpose/CellposeRuntime.java:112-116`, invalidate the cache. After successful CPU install verification at `src/main/java/flash/pipeline/cellpose/CellposeRuntime.java:372-383` and successful GPU verification at `src/main/java/flash/pipeline/cellpose/CellposeRuntime.java:421-429`, update the cache with the verified `Status` instead of waiting for TTL.
9. Change `CellposeParameterStage.RuntimeAdapter` at `src/main/java/flash/pipeline/ui/config/CellposeParameterStage.java:66-69` from string-only to status/async based. The production adapter at `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:6102-6114`, noop adapter at `src/main/java/flash/pipeline/ui/config/CellposeParameterStage.java:1380-1393`, and test adapter at `src/test/java/flash/pipeline/ui/config/CellposeParameterStageTest.java:446-458` must all compile. One workable shape is:
   - `CellposeRuntime.Status cachedRuntimeStatus();`
   - `CompletableFuture<CellposeRuntime.Status> probeRuntimeAsync();`
   - `boolean nvidiaGpuLikelyAvailable();`
   - `GpuInstallResult installGpuSupport();`
10. In `CellposeParameterStage.refreshRuntimeLabel()` at `src/main/java/flash/pipeline/ui/config/CellposeParameterStage.java:777-784`, render the cached status immediately, then start `probeRuntimeAsync()`. Use `whenCompleteAsync(..., SwingUtilities::invokeLater)` rather than `thenAcceptAsync(...)` so failures cannot leave the label stuck at "Checking Cellpose...".
11. Prevent disposed-stage leaks in the async label path. `ConfigQcDialog` closes through `onLeave()` (`src/main/java/flash/pipeline/ui/config/ConfigQcDialog.java:673-690`), and `CellposeParameterStage.onLeave()` already clears stage resources (`src/main/java/flash/pipeline/ui/config/CellposeParameterStage.java:350-364`). Add a `private volatile boolean runtimeUiActive` and a monotonically increasing `runtimeProbeRequestId`; set active true in `buildControls()` at `src/main/java/flash/pipeline/ui/config/CellposeParameterStage.java:230-264`, set active false and increment the id in `onLeave()`, and have the EDT callback update only when active and the id still matches. If using `WeakReference<CellposeParameterStage>`, make sure the lambda captures only the weak reference and request id, not `this`.
12. Format runtime label text in one helper, for example `runtimeText(CellposeRuntime.Status status)`, so `Status.unknown()` consistently renders "Runtime: Checking Cellpose..." and real statuses preserve the current ready/missing wording from `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:6105-6109` and `src/main/java/flash/pipeline/ui/config/CellposeParameterStage.java:779-782`.
13. In `CreateBinFileAnalysis.createCellposeParameterStage()` at `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:6024-6126`, replace the blocking default GPU probe at line 6029 with cached status plus `probeAsync()` warmup. If async resolution changes GPU availability, update `gpuCheckBox` only if the user has not edited it and the saved method token did not explicitly contain a `gpu=` option.
14. In `CreateBinFileAnalysis.showFilteredSegmentationMethodsPage()` at `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:558-587`, replace the blocking probe at line 566 with cached status and async label refresh. Keep the accepted-selection gate at `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:597-604` synchronous so a stale unknown cannot start a Cellpose setup path.
15. In the legacy non-embedded path at `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:4577-4722`, replace line 4587 with cached status and update the runtime help label at lines 4719-4722 after async completion. Keep the preview/run buttons gated by the existing feature gate and runner path.
16. For `ChannelSetupWizard`, do not leave `src/main/java/flash/pipeline/analyses/wizard/ChannelSetupWizard.java:51` as a constructor-time blocker, but also do not silently convert unknown to unavailable for all recommendations. A concrete acceptable design is: constructor uses cached availability and starts `probeAsync()`; `deriveCurrentConfig()` at `src/main/java/flash/pipeline/analyses/wizard/ChannelSetupWizard.java:70-77` re-checks resolved availability before final derivation, blocking there only if Cellpose is still unknown and a complex crowded recommendation could depend on it (`src/main/java/flash/pipeline/ui/wizard/SegmentationEnginePicker.java:149-168`).
17. For dependency UI, account for the verified path through `src/main/java/flash/pipeline/FLASH_Pipeline.java:564` and `src/main/java/flash/pipeline/runtime/DependencyRegistry.java:917`. Because `DependencyStatus` has only `PRESENT/MISSING/ERROR` (`src/main/java/flash/pipeline/runtime/DependencyStatus.java:8-12`), do not feed `Status.unknown()` into `DependencyRegistry.cellposeProbe()` as ordinary missing without a refresh strategy. Either prewarm `CellposeRuntime.probeAsync()` early near the existing async GPU warmup at `src/main/java/flash/pipeline/FLASH_Pipeline.java:196-199`, or defer the Cellpose dependency row refresh until after first paint.
18. Add `CellposeRuntimeCacheTest` in `src/test/java/flash/pipeline/cellpose/CellposeRuntimeCacheTest.java`. Use a package-private test hook rather than running real Python; for example, set a test probe backend that blocks on `CountDownLatch`, increments an `AtomicInteger`, and returns a synthetic `Status`. Reset the hook and call `invalidateCache()` in `finally`.
19. Add/update UI tests in `src/test/java/flash/pipeline/ui/config/CellposeParameterStageTest.java`: `buildControls()` with an incomplete fake future should return without waiting, the runtime label should initially show checking/last-known text, completing the future should update the label on the EDT, and calling `onLeave()` before completing the future should prevent the detached label from changing.
20. Manual smoke: on a system with configured Cellpose, open "Set Up Configuration - Segmentation Methods" and embedded Cellpose QC. The dialog should paint before the Python probe finishes; the runtime hint/help text should update after the probe resolves; clicking a real Cellpose run path should still block or gate safely if the runtime is missing.

## Exit Gate

- `rg -n "CellposeRuntime\.probeConfigured" src/main/java src/test/java` no longer reports UI-construction calls at `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:566`, `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:4587`, `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:6029`, `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:6104`, or `src/main/java/flash/pipeline/analyses/wizard/ChannelSetupWizard.java:51`.
- The same `rg` output may still report synchronous run/setup gates at `src/main/java/flash/pipeline/cellpose/Cellpose3DRunner.java:48`, `src/main/java/flash/pipeline/cellpose/CellposePersistentWorker.java:152`, `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:602`, and `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:7981`.
- If `src/main/java/flash/pipeline/runtime/DependencyRegistry.java:917` still calls `probeConfigured()`, there must be a concrete prewarm/deferment change tied to `src/main/java/flash/pipeline/FLASH_Pipeline.java:196-199` or `src/main/java/flash/pipeline/FLASH_Pipeline.java:564`; otherwise the main dialog can still block on first open.
- A focused fake slow-probe test proves `CellposeParameterStage.buildControls()` returns before the fake future completes, using the path at `src/main/java/flash/pipeline/ui/config/CellposeParameterStage.java:230-264` and `src/main/java/flash/pipeline/ui/config/CellposeParameterStage.java:570-590`.
- A focused disposed-stage test proves completing the async probe after `CellposeParameterStage.onLeave()` at `src/main/java/flash/pipeline/ui/config/CellposeParameterStage.java:350-364` does not mutate a detached `runtimeLabel`.
- `CellposeRuntimeCacheTest` proves: unknown before first probe, concurrent `probeAsync()` calls coalesce to one backend call, `probeConfigured()` uses the cache when fresh, non-ready TTL expiry triggers a fresh backend call, ready TTL does not expire at 30 seconds, `invalidateCache()` prevents older in-flight completion from repopulating the cache, and `setPythonPath()` invalidates cached status.
- Existing real-runtime tests at `src/test/java/flash/pipeline/cellpose/CellposePersistentWorkerTest.java:27`, `src/test/java/flash/pipeline/ui/variations/integration/CellposeOneShotIntegrationTest.java:33`, and `src/test/java/flash/pipeline/ui/variations/integration/CellposePersistentIntegrationTest.java:33` still compile and continue to skip/run based on a real configured runtime.
- Run focused tests first, then the project build command from this stage context: `bash mvnw -Denforcer.skip=true test` and `bash mvnw -Denforcer.skip=true package`.

## Known Risks

- A plain `Status.unknown()` with `ready=false` can break behavior if it leaks into code that treats non-ready as missing: `src/main/java/flash/pipeline/runtime/DependencyRegistry.java:918-927`, `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:6030-6032`, `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:6105-6109`, `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:7617-7619`, and `src/main/java/flash/pipeline/ui/wizard/SegmentationEnginePicker.java:160-168`.
- A 30 second TTL is reasonable for non-ready statuses but too short for ready statuses. If ready status also expires at 30 seconds, repeated UI opens can restart the Python import path that this stage is meant to remove.
- If `probeConfigured()` waits on `probeAsync().get()` while holding `LOCK`, concurrent UI and runner calls can deadlock. The lock must only protect cache state transitions.
- If `invalidateCache()` does not guard against older in-flight completion, an obsolete probe can repopulate the cache after the user changes Python path at `src/main/java/flash/pipeline/cellpose/CellposeRuntime.java:112-116`.
- `CompletableFuture.thenAcceptAsync(status -> ..., SwingUtilities::invokeLater)` captures the stage strongly if the lambda references instance fields/methods directly. Use the lifecycle guard from `src/main/java/flash/pipeline/ui/config/CellposeParameterStage.java:350-364` and a weak-reference or request-id pattern that does not keep disposed stages alive for the full `PROBE_TIMEOUT_SECONDS` window from `src/main/java/flash/pipeline/cellpose/CellposeRuntime.java:20`.
- Do not conflate `CellposeRuntime.isNvidiaGpuLikelyAvailable()` at `src/main/java/flash/pipeline/cellpose/CellposeRuntime.java:687-696` with `Status.gpuAvailable` from the Python/Torch probe at `src/main/java/flash/pipeline/cellpose/CellposeRuntime.java:167-173`; the GPU install warning and default "Use GPU" checkbox answer different questions.
- The direct `probe(String)` paths in setup and install flows (`src/main/java/flash/pipeline/cellpose/CellposeRuntime.java:246`, `src/main/java/flash/pipeline/cellpose/CellposeRuntime.java:372`, `src/main/java/flash/pipeline/cellpose/CellposeRuntime.java:421`, `src/main/java/flash/pipeline/cellpose/CellposeRuntime.java:496`, `src/main/java/flash/pipeline/cellpose/CellposeRuntime.java:568`) intentionally validate explicit paths and should not be served blindly from the current configured-path cache.
