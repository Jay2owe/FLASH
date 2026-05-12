# 01 - Add `ClassicalSegmentationStage`

## Goal

Create one Classical method-specific setup stage that owns both:

- object threshold selection
- particle-size filtering

The stage saves both settings only when the user locks in the stage.

## New Class

Add:

```text
src/main/java/flash/pipeline/ui/config/ClassicalSegmentationStage.java
```

Suggested public shape:

```java
public final class ClassicalSegmentationStage implements ConfigQcStage {
    public interface ThresholdStore {
        String get();
        void set(String token);
    }

    public interface SizeStore {
        String get();
        void set(String token);
    }

    public interface PreviewAdapter {
        ImagePlus createRawSource(ConfigQcContext context) throws Exception;
        ImagePlus createFilteredSource(ConfigQcContext context) throws Exception;
        ObjectsCounter3DWrapper.Result runPreview(ImagePlus filteredSource,
                                                  int threshold,
                                                  int minSize,
                                                  int maxSize) throws Exception;
        int countObjects(ObjectsCounter3DWrapper.Result result);
        void close(ImagePlus image);
    }
}
```

The adapter intentionally uses `createFilteredSource(...)`, not `createThresholdSource(...)`, because the same filtered input feeds both the live threshold preview and the object preview.

## State To Carry

Keep explicit fields for:

- `rawSource`
- `filteredSource`
- `thresholdPreview`
- `labelPreview`
- `previewWorker`
- current threshold lower and upper values
- saved size token
- restart threshold
- restart size token
- stale object-preview flag
- last object count

Use the same close discipline as `ParticleSizeStage`: close unique `ImagePlus` instances on leave, and close replaced previews after installing a new one.

## Controls

Build one compact control panel:

```text
Threshold
Lower [slider] 42    Upper [slider] 255
Method [Default]    Background [Dark]       [Auto] [Reset]

Objects
Min size [100]      Max size [Infinity]     [Run Object Preview] [Reset sizes]
```

Do not expose the `ThresholdControlPanel` preview dropdown. Either:

- add a small API to `ThresholdControlPanel` to hide/fix the preview selector, or
- create a compact threshold control for this stage using the existing slider and histogram components.

The fixed threshold rendering mode should be the existing red threshold overlay.

## Stage Entry

On `onEnter(...)`:

1. close any old worker and images
2. create `rawSource`
3. create `filteredSource`
4. initialize threshold control from saved threshold, restart threshold, or auto threshold
5. render the threshold preview
6. set the normal preview:
   - left pane: threshold preview
   - right pane: empty or previous object label map
7. mark object preview stale
8. register the object preview button with `actions.registerPreviewButton(...)`

## Threshold Changes

On slider, auto, or reset:

1. re-render `thresholdPreview` from `filteredSource`
2. update the left pane immediately
3. mark object preview stale
4. update status, for example:

```text
Threshold 42. Object preview is out of date.
```

Do not run 3D Objects Counter during slider edits.

## Size Changes

On min or max size edit:

1. validate only enough to show obvious errors
2. mark object preview stale
3. leave the existing label map visible, but stale, if one exists

This matches StarDist and Cellpose: parameter edits do not automatically run the expensive preview.

## Run Object Preview

When `Run Object Preview` is pressed:

1. collect current threshold lower value
2. parse min and max voxel sizes with `ObjectsCounter3DWrapper.parseMinSizeVoxels(...)` and `parseMaxSizeVoxels(...)`
3. run the adapter on a `SwingWorker`
4. install the object label map in the right pane
5. style labels with `LabelMapStyler.apply(...)`
6. clear stale state
7. update status:

```text
Objects: 128 ready. Threshold 42.
```

## Lock In

On `lockIn(...)`:

1. save threshold token to `ThresholdStore`
2. save size token to `SizeStore`
3. clear restart state
4. return `true`

The threshold save should preserve the existing behaviour from `CreateBinFileAnalysis.createChannelThresholdStage(...)`: object threshold and intensity threshold are both updated where that store is wired to the user config.

## Tests For This Stage

Add:

```text
src/test/java/flash/pipeline/ui/config/ClassicalSegmentationStageTest.java
```

Minimum tests:

- entering the stage creates raw and filtered sources
- left pane is threshold preview, not raw source
- threshold edits rerender left pane and do not run object preview
- threshold edits mark object preview stale
- size edits mark object preview stale
- object preview uses the current unsaved threshold
- lock-in writes both threshold and size
- restart preserves current unsaved threshold and size
