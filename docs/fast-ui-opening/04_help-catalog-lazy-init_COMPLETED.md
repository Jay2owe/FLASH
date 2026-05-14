# Stage 04: Help Catalog Lazy Init

## Why

`AnalysisHelpCatalog` currently defines `private static final Map<Integer, AnalysisHelpTopic> TOPICS = buildTopics();` at `src/main/java/flash/pipeline/help/AnalysisHelpCatalog.java:15`. Accessors at `AnalysisHelpCatalog.java:20`, `AnalysisHelpCatalog.java:24`, and `AnalysisHelpCatalog.java:28` read that field directly, so `buildTopics()` runs during outer class initialization.

The original plan overstated the win. `buildTopics()` at `AnalysisHelpCatalog.java:32` constructs 11 `AnalysisHelpTopic` objects, a `LinkedHashMap`, several small immutable `List` copies, and optional image metadata. It performs no file I/O, resource lookup, image loading, Swing work, logging, or global mutation. The cost is real allocation work on the first access, but it is probably trivial unless a profiler has shown otherwise; the previous 10-20 ms estimate should be treated as unverified.

The holder idiom only defers work from `AnalysisHelpCatalog` class initialization to the first accessor call. It does **not** automatically move the cost to the first Help click, because `PipelineDialog.addAnalysisHelpHeader()` currently calls `AnalysisHelpCatalog.forAnalysis(analysisIndex)` while building many dialogs (`src/main/java/flash/pipeline/ui/PipelineDialog.java:297`). Those dialogs still pay the build cost before first paint if they are the first production access. The top-level row help path in `FLASH_Pipeline` is already click-time (`src/main/java/flash/pipeline/FLASH_Pipeline.java:779` -> `FLASH_Pipeline.java:793`).

**Reconsider:** as a standalone holder-only stage, this is likely a negligible or no-op user-visible win. Drop this stage unless profiling shows `AnalysisHelpCatalog.buildTopics()` is on a measured slow opening path, or expand the stage to stop `addAnalysisHelpHeader()` from resolving the topic before the button is clicked.

## Prerequisites

- Read `docs/fast-ui-opening/00_overview.md`.
- Re-run the caller grep before implementation because this stage is only useful if first access is class-load-time rather than accessor-time:
  ```powershell
  rg -n "AnalysisHelpCatalog\." src
  rg -n "addAnalysisHelpHeader\(" src/main/java
  ```

## Read First

- `src/main/java/flash/pipeline/help/AnalysisHelpCatalog.java:13` - class declaration.
- `src/main/java/flash/pipeline/help/AnalysisHelpCatalog.java:15` - current eager `TOPICS = buildTopics()` field.
- `src/main/java/flash/pipeline/help/AnalysisHelpCatalog.java:20` - `forAnalysis(...)`.
- `src/main/java/flash/pipeline/help/AnalysisHelpCatalog.java:24` - `hasTopic(...)`.
- `src/main/java/flash/pipeline/help/AnalysisHelpCatalog.java:28` - `all()`.
- `src/main/java/flash/pipeline/help/AnalysisHelpCatalog.java:32` - `buildTopics()`.
- `src/main/java/flash/pipeline/help/AnalysisHelpCatalog.java:48` - map insertion helper.
- `src/main/java/flash/pipeline/help/AnalysisHelpCatalog.java:523` - list helper.
- `src/main/java/flash/pipeline/help/AnalysisHelpCatalog.java:527` - image metadata helper.
- `src/main/java/flash/pipeline/help/AnalysisHelpCatalog.java:531` - optional image path helper.
- `src/main/java/flash/pipeline/help/AnalysisHelpTopic.java:24` - constructor copies and validates the topic lists.
- `src/main/java/flash/pipeline/ui/PipelineDialog.java:296` - `addAnalysisHelpHeader(...)`.
- `src/main/java/flash/pipeline/ui/PipelineDialog.java:297` - current eager accessor during dialog construction.
- `src/main/java/flash/pipeline/FLASH_Pipeline.java:779` - top-level row help button click handler.
- `src/main/java/flash/pipeline/FLASH_Pipeline.java:793` - top-level row help resolves catalog only after click.
- `src/test/java/flash/pipeline/help/AnalysisHelpCatalogTest.java` - existing catalog behavior tests.

Caller verification from `rg -n "AnalysisHelpCatalog\." src` on 2026-05-14:

- Production callers are only `src/main/java/flash/pipeline/ui/PipelineDialog.java:297` and `src/main/java/flash/pipeline/FLASH_Pipeline.java:793`.
- Test callers are in `AnalysisHelpCatalogTest`, `AnalysisHelpDialogTest`, `AnalysisHelpAssetTest`, and `AnalysisDialogHelpAttachmentTest`.
- `rg -n "ServiceLoader|Class\.forName\(.*AnalysisHelpCatalog|loadClass\(.*AnalysisHelpCatalog|AnalysisHelpCatalog\.class" src/main/java src/test/java` found no current reflection, `ServiceLoader`, or `.class` lookup for `AnalysisHelpCatalog`. Imports and Javadocs do not initialize the class.
- `rg -n "addAnalysisHelpHeader\(" src/main/java` found many dialog-construction call sites, including `CreateBinFileAnalysis`, `DrawAndSaveROIsAnalysis`, `IntensityAnalysisV2`, `ThreeDObjectAnalysis`, `SplitAndMergeImageChannelsAnalysis`, and `SpatialAnalysis`. These remain pre-paint accessor paths after a holder-only change.

## Scope

- If this stage is kept, replace the eager `static final` with the initialize-on-demand holder idiom (Bloch / Pugh pattern):
  ```java
  private static final class Holder {
      static final Map<Integer, AnalysisHelpTopic> TOPICS = buildTopics();
  }
  ```
  All accessors (`forAnalysis`, `hasTopic`, `all`) read `Holder.TOPICS`.
- Confirm `buildTopics()` remains referentially transparent before changing it. Current code is pure construction of immutable topic data: it creates local objects, fills a local map, wraps it with `Collections.unmodifiableMap(...)`, and has no I/O or external state.
- Be explicit about what changes: the first build moves from outer class initialization to the first accessor call. It does **not** move dialog-header access to first Help click unless `PipelineDialog.addAnalysisHelpHeader()` is also changed, which is outside this holder-only scope.

## Out Of Scope

- Re-shaping the help topic data.
- Adding caching beyond what the holder already provides.
- Changing `PipelineDialog.addAnalysisHelpHeader()` to resolve topics lazily on button click. That is the change needed if the desired user-visible behavior is "no topic build during dialog construction".
- Changing `SetupHelpCatalog`, `SpatialHelpCatalog`, or `IntensitySpatialHelpCatalog`.

## Files Touched

- If retained: `src/main/java/flash/pipeline/help/AnalysisHelpCatalog.java`.
- If retained and a deterministic lazy-init assertion is added: `src/test/java/flash/pipeline/help/AnalysisHelpCatalogTest.java`.
- Do not touch broader UI code in this stage unless the stage is explicitly expanded; otherwise the plan's "first Help click" claim is false.

## Implementation Sketch

1. Decide whether to keep or drop this stage. Keep it only if a measured profile shows `buildTopics()` contributes meaningfully to a cold dialog-open path, or if you are willing to pair it with lazy help-button resolution in a separate stage.
2. Move `buildTopics()` invocation inside a private static `Holder` class.
3. Replace the three accessor references to `TOPICS` with `Holder.TOPICS`.
4. Do not add a normal same-JVM test that assumes class initialization can be reset. Java initializes each class once per classloader, and existing tests may already have touched the catalog before the lazy-init test runs.
5. If adding a runtime lazy-init test, make it deterministic:
   - Add a package-private test hook on the outer class, such as `static int buildTopicsCallCountForTests()`, backed by a counter incremented only inside `buildTopics()`. Reading the hook must not reference `Holder`.
   - In the test, load `flash.pipeline.help.AnalysisHelpCatalog` through a fresh isolated `URLClassLoader` built from the test class path, then use reflection only.
   - Assert that `Class.forName("flash.pipeline.help.AnalysisHelpCatalog", true, loader)` initializes the outer class but leaves `buildTopicsCallCountForTests()` at `0`.
   - Invoke `forAnalysis(0)` reflectively and assert the count becomes `1`.
   - Invoke `all()` and `hasTopic(0)` reflectively and assert the count stays `1`.

## Exit Gate

- If the stage is dropped: record that `buildTopics()` is pure, in-memory, first-access-only work and that current production accessor paths make the holder-only win negligible.
- If the stage is implemented: help topics are still returned correctly for every analysis index that previously worked.
- `rg -n "AnalysisHelpCatalog\." src/main/java` still shows only intentional production accessors, and the implementation notes explain whether each one is click-time or dialog-construction-time.
- `rg -n "addAnalysisHelpHeader\(" src/main/java` is reviewed after the change. If these call sites still resolve through `PipelineDialog.java:297`, do not claim "first Help click" behavior.
- A deterministic lazy-init test uses an isolated classloader or separate JVM. A same-classloader counter test is not sufficient because prior tests can initialize `Holder` before the assertion runs.
- Existing `AnalysisHelpCatalogTest`, `AnalysisHelpDialogTest`, `AnalysisHelpAssetTest`, and help-attachment tests still pass.
- `.\mvnw.cmd '-Denforcer.skip=true' test` passes when implementation work is allowed.

## Known Risks

- The likely benefit is negligible. Current code allocates topic metadata only once per JVM/classloader, and the method body is pure in-memory construction.
- The holder idiom is thread-safe under Java class initialization rules, but it only defers until first `Holder.TOPICS` access. It does not defer `PipelineDialog.addAnalysisHelpHeader()` because that method already calls `forAnalysis(...)`.
- A lazy-init test can easily become nondeterministic if it runs in the normal test classloader after another test has already called `AnalysisHelpCatalog.forAnalysis(...)`, `hasTopic(...)`, or `all()`.
- A package-private build counter is test instrumentation in production code. Keep it minimal, or prefer a source/bytecode structure test if runtime proof is not worth the extra surface.
- Native-image/AOT concerns are low for the current ImageJ/Fiji Java 8 target. If FLASH later adopts GraalVM native-image or similar ahead-of-time compilation, class-initialization timing may be configured at build time and should be rechecked.
- If any test or static analyzer assumes `TOPICS` is a direct field, the test will fail to compile and reveal the assumption; update the test rather than preserving the eager field.
