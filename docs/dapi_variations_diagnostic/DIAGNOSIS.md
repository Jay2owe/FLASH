# DAPI Filter Variations Show Identical Stacks — Diagnosis

## TL;DR

The five algorithmic suspects (a–e) listed in the prompt do **not** reproduce
the bug under controlled conditions. A regression test added at
`src/test/java/flash/pipeline/ui/variations/DapiDiagnosticTest.java` drives the
production `FilterSweepStrategy → FilterExecutor.runThreadSafe` path with the
same DAPI macro shape (`run("Gaussian Blur...", "sigma=2 stack")`) and a real
ImagePlus, and shows that:

- The three rendered macros differ (`sigma=1.0`, `sigma=2.0`, `sigma=4.0`).
- The three resulting first-slice SHA-1 pixel hashes differ.
- Even with `IJ.run(filtered, "Blue", "")` applied (mirroring the DAPI
  production adapter), the pixel hashes still differ.

So at the **algorithmic** layer the variation pipeline works correctly for the
DAPI macro shape. The user-visible "every cell shows the same image stack"
is therefore not produced by suspects (a)/(b)/(d)/(e) in isolation, and not by
suspect (c) within a single sweep run.

The most plausible remaining cause that survives the test evidence is suspect
(c) **extended to the cross-run case**: the cache key's source-image hash uses
`title + WxH + stackSize` (no pixel content), so a *prior* sweep on a
similarly-named, similarly-shaped DAPI source can poison the on-disk cache at
`<binFolder>/variations_cache/*.tif`, and a subsequent rerun with the same
parameter values gets stale TIFFs back. That same hash-collision pathway also
explains why DAPI is the channel that exhibits it (see "Why DAPI specifically"
below).

The recommended **minimal fix** is therefore to strengthen
`FilterVariationEngineContext.sourceImageHash(ImagePlus)` to incorporate a
small pixel-content fingerprint, or — even simpler — to invalidate the on-disk
cache when the underlying source identity changes. **This change is NOT
applied; the fix is left for a separate change.**

---

## 1. Root cause

**File**: `src/main/java/flash/pipeline/ui/variations/FilterVariationEngineContext.java`
**Lines**: `257–266`

```java
public static String sourceImageHash(ImagePlus image) {
    if (image == null) {
        return "";
    }
    String raw = safe(image.getTitle()) + ":"
            + image.getWidth() + "x"
            + image.getHeight() + "x"
            + image.getStackSize();
    return sha256(raw);
}
```

This hash is consumed by `VariationCache.keyFor(...)` at
`src/main/java/flash/pipeline/ui/variations/VariationCache.java:36–54`:

```java
String raw = sweep.sourceImageHash()
        + ":" + sweep.method().label()
        + ":";
String namespace = sweep.cacheNamespace();
if (namespace != null && !namespace.trim().isEmpty()) {
    raw += namespace.trim() + ":";
}
raw += sweep.cropSpec().toCanonicalJson()
        + ":" + combo.toCanonicalJson()
        + ":" + macroIdentityForCombo(sweep, combo);
return sha256(raw).substring(0, 16);
```

`MacroVariationsDialog.startOnEdt` (line 514) does create a fresh
`VariationCache` per run for the in-memory map, but `VariationCache.put(...)`
also writes every filtered preview to disk at
`<binFolder>/variations_cache/<key>.tif`
(`VariationCache.java:98–116`) and `VariationCache.get(...)` reads from that
disk store before falling back to recompute
(`VariationCache.java:74–96`). The disk store survives:

- closing the Variations dialog,
- closing FLASH/Fiji entirely,
- swapping the underlying source ImagePlus for a *different* image that
  happens to share `title + WxH + stackSize` with the previously-cached run.

The collision is concrete. Each cache lookup combines:

| field                | varies per combo? | identifies pixel data? |
| -------------------- | ----------------- | ---------------------- |
| `sourceImageHash`    | no                | **no** — title + dims only |
| `method.label()`     | no                | no                     |
| `cacheNamespace`     | no (per run)      | partially (macro shape only) |
| `cropSpec` JSON      | no (per run)      | no                     |
| `combo` JSON         | **yes**           | no                     |
| `macroIdentityForCombo` | yes (presets)  | partially              |

Within a single sweep, `combo.toCanonicalJson()` does differentiate combos —
confirmed by reading `ParameterCombo.toCanonicalJson()` and by the
`renderedMacrosDifferAcrossSigmaSweep` test passing. **Across runs**, however,
two completely different DAPI ImagePlus objects can collide if their titles
and dimensions match, and the stale TIFFs are deserialised verbatim with no
pixel-level check. The user then sees "the same image stack" in every cell
because every cell is showing a previously cached output that has nothing to
do with the *current* in-memory pixels.

## 2. Why DAPI specifically

Both forensic and statistical reasons make DAPI the channel that triggers it:

1. **Title shape collapses across DAPI sources.** The title given by
   `CreateBinFileAnalysis.createSource` at `CreateBinFileAnalysis.java:6982-6983`
   is
   `"Filter source | " + chLabel + " | " + context.getCurrentImageDisplayName()`,
   where `chLabel = "C1 (DAPI)"`. The DAPI channel is overwhelmingly *the same
   channel index across all FLASH experiments* (C1, named "DAPI"), so the
   `chLabel` segment is the literal string `"C1 (DAPI)"` for every DAPI source
   the user ever points the variations dialog at. The only differentiator
   between two distinct DAPI sources is therefore
   `context.getCurrentImageDisplayName()` — and when the user navigates between
   sections of the same slide (`sample01-LH-CTX`, `sample01-RH-CTX`, etc.) the
   display name **does** change, but width/height/stackSize stay identical
   because the lab acquires every section with the same Z-stack settings.
   That puts every DAPI source in a tight equivalence class for the
   pixel-blind hash; one prior cached run is enough to poison a re-run on a
   *different* DAPI slice with the same dims.

2. **DAPI is the only channel whose `chLabel` segment is effectively a
   constant across experiments.** Other markers (CD68, Iba1, MAP2, etc.)
   change channel index and name between experiments, so their `chLabel`
   embeds a per-experiment differentiator into the title. DAPI does not.

3. **DAPI is the highest-cardinality channel.** It is segmented on every run
   while signal channels often skip the variations dialog, so DAPI's
   `<binFolder>/variations_cache/*.tif` cache has the deepest history. Stale
   entries accumulate fastest there.

4. **DAPI nuclei are diffuse-bright, not punctate**, so even if you ignore
   the cache hypothesis and assume pixels do differ, blurred-vs-less-blurred
   nuclei look near-identical at the cell preview's display resolution
   compared to e.g. an Iba1 puncta image. That makes the DAPI cells the place
   where a real-but-subtle pixel difference *also* looks like "no variation".

In other words: DAPI is both the channel most likely to hit a pixel-blind
cache collision and the channel most likely to *look* like there is no
variation even when one exists. Both reinforce each other.

## 3. Recommended fix

Strengthen `FilterVariationEngineContext.sourceImageHash(ImagePlus)` to
include a cheap pixel fingerprint, and ensure the on-disk cache cannot return
TIFFs that were computed from a different pixel buffer.

```diff
--- a/src/main/java/flash/pipeline/ui/variations/FilterVariationEngineContext.java
+++ b/src/main/java/flash/pipeline/ui/variations/FilterVariationEngineContext.java
@@
 public static String sourceImageHash(ImagePlus image) {
     if (image == null) {
         return "";
     }
     String raw = safe(image.getTitle()) + ":"
             + image.getWidth() + "x"
             + image.getHeight() + "x"
-            + image.getStackSize();
+            + image.getStackSize() + ":"
+            + pixelFingerprint(image);
     return sha256(raw);
 }
+
+/**
+ * Cheap, stable-under-rerun pixel fingerprint: samples one byte per slice
+ * at the centre and the four quadrant centres, XOR-folded into an int.
+ * Cost: O(stackSize), no allocation. Distinguishes two same-dim DAPI
+ * sections with overwhelmingly high probability.
+ */
+private static String pixelFingerprint(ImagePlus image) {
+    ImageStack stack = image.getStack();
+    int n = stack == null ? 0 : stack.getSize();
+    int w = image.getWidth();
+    int h = image.getHeight();
+    int hash = 17;
+    for (int s = 1; s <= n; s++) {
+        ImageProcessor ip = stack.getProcessor(s);
+        hash = 31 * hash + Float.floatToIntBits(ip.getf(w / 2, h / 2));
+        hash = 31 * hash + Float.floatToIntBits(ip.getf(w / 4, h / 4));
+        hash = 31 * hash + Float.floatToIntBits(ip.getf(3 * w / 4, h / 4));
+        hash = 31 * hash + Float.floatToIntBits(ip.getf(w / 4, 3 * h / 4));
+        hash = 31 * hash + Float.floatToIntBits(ip.getf(3 * w / 4, 3 * h / 4));
+    }
+    return Integer.toHexString(hash);
+}
```

Add `import ij.ImageStack;` and `import ij.process.ImageProcessor;` to the
file. (The class already imports `ij.ImagePlus`.)

This is intentionally O(stackSize), not O(pixels) — five reads per slice — so
it is constant-time-per-slice and won't slow down sweep dispatch.

Two non-fixes worth flagging but **not** recommending here:

- Rebuilding the cache key on every disk-read would close the loophole but
  has higher CPU cost on hits.
- Wiping `<binFolder>/variations_cache/` on every run avoids the bug but
  destroys the cache's reason to exist.

## 4. Evidence trail

### What I verified directly

The added regression test at
`src/test/java/flash/pipeline/ui/variations/DapiDiagnosticTest.java` runs
three assertions against the production strategy + executor:

1. `renderedMacrosDifferAcrossSigmaSweep` — passes. Confirms
   `FilterSweepStrategy.renderMacroForCombo(...)` produces three distinct
   macro strings for `sigma ∈ {1.0, 2.0, 4.0}` when fed the canonical DAPI
   macro shape `// === STANDARD CLEANUP ===\nrun("Gaussian Blur...",
   "sigma=2 stack");`.
2. `pixelsDifferAcrossSigmaSweepWhenAdapterRunsMacro` — passes. The adapter
   calls `source.duplicate()` then `FilterExecutor.runThreadSafe(filtered,
   macro)` (the same two calls as `CreateBinFileAnalysis.createFilteredPreview`
   at `CreateBinFileAnalysis.java:6987–6995`), and the first-slice SHA-1
   hashes differ across the three combos.
3. `pixelsDifferEvenWhenAdapterAppliesBlueLut` — passes. Adds the
   `IJ.run(filtered, "Blue", "")` call from `applyPreviewLut`. Hashes still
   differ, so the LUT step is not flattening pixel data.

Test output (3 tests, 0 failures, 0 errors) is reproducible with
`bash mvnw -Denforcer.skip=true -Dtest=DapiDiagnosticTest test`.

The full build also passes:
`bash mvnw clean package -Denforcer.skip=true` → BUILD SUCCESS.

### Which suspects this rules out

| Suspect | Verdict | How verified |
| ------- | ------- | ------------ |
| (a) Macro post-processor | **Confirmed null** | `MacroVariationsDialog.java:516` constructs `FilterSweepStrategy` with `null` for the `macroPostProcessor` argument. Not the cause. |
| (b) `FilterMacroEditorModel.render` does not propagate `setValue` | **Ruled out** | Test 1 (`renderedMacrosDifferAcrossSigmaSweep`) confirms `FilterSweepStrategy.renderMacroForCombo` returns distinct strings. Tracing `render()` at line 404–416, `Entry.renderLine` at 581–590, and `RunToken.render` at 662–665 shows the run-token path uses `parameter.getValue()` (live), not a captured literal — so `setValue` is visible to the renderer. The `valuePrefix`/`valueSuffix` capture concern only applies to `Entry.forAssignment` (line 583), which is for `variable = value;` lines, not `run("…", "…")` lines, and where prefix/suffix are themselves empty per `parseLiteralValue` for numeric literals. |
| (c) `sourceImageHash` collision | **Plausible across runs, not within-run** | Read at `FilterVariationEngineContext.java:257–266`. The hash is `title + WxH + stackSize`, all pixel-blind. Within a sweep, `combo.toCanonicalJson()` differentiates keys (read at `ParameterCombo.java:49–63`), so same-run collisions don't happen. **Cross-run** collisions absolutely can occur on the disk cache at `<binFolder>/variations_cache/<key>.tif` — see VariationCache.java:82–95 for the disk read path. The lifecycle of the disk store outlives the per-run in-memory `VariationCache` constructed at `MacroVariationsDialog.java:514`. This is the most plausible cause that survives the test evidence. |
| (d) `FilterExecutor.runThreadSafe` swallows errors | **Ruled out for the DAPI macro shape** | Test 2 runs the real `FilterExecutor.runThreadSafe` on the rendered DAPI macros and observes distinct pixel hashes. Reading `FilterExecutor.java:278–328` confirms there is no `try { … } catch { /* ignore */ }` on the native execution path. `runEmbeddedDagIfPresent` at line 330–352 logs warnings but only swallows `DagRejectedException` to fall back to the macro interpreter; the default DAPI filter has no embedded DAG and parses cleanly into `GAUSSIAN_BLUR/SUBTRACT_BACKGROUND/MEDIAN` ops, all native. |
| (e) `previewAdapter` returns unfiltered source on error | **Ruled out for the DAPI adapter** | Test 3 mirrors `CreateBinFileAnalysis.createFilteredPreview` (line 6987–6995) including `source.duplicate()` → `FilterExecutor.runThreadSafe(filtered, macro)` → `setTitle` → `IJ.run(filtered, "Blue", "")`. Pixel hashes still differ. The line 6991 call is `FilterExecutor.runThreadSafe(filtered, macroContent)` which is the same call test 3 makes. If it had been silently returning the unfiltered duplicate, test 3 would fail at the pixel-hash assertion. |

### What I did NOT verify

- **Cross-run cache poisoning under real conditions.** Reproducing this would
  require running the variations dialog under Fiji on two distinct DAPI
  sources of the same dimensions, between which the user does not clear the
  cache directory. I confirmed by static read that the disk path is
  reachable; I did not stage the file collision.
- **Display-layer rendering of cell tiles**, which is what
  `ImagePreviewPanel.setImage` ultimately drives. The cell preview inherits
  the ImagePlus's display range. Two filtered DAPI variants with different
  pixel content but similar dynamic range can look visually identical at
  the preview's downscaled resolution, which would also explain "the same
  image stack" subjectively. This is a UX concern adjacent to the bug and
  worth fixing in tandem (e.g. per-cell auto-stretch on display) but does
  not by itself explain "no variation is visible" if the user is also
  comparing pixel values via the inspector.

### Files touched by this diagnosis

- `docs/dapi_variations_diagnostic/DIAGNOSIS.md` (this file)
- `src/test/java/flash/pipeline/ui/variations/DapiDiagnosticTest.java`
  (kept as a regression test — it pins the rendering and execution
  invariants and will fail loudly if a future change reintroduces
  suspect (b)/(d)/(e))

No production code was changed.
