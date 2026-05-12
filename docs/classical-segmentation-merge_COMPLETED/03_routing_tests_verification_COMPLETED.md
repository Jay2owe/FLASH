# 03 - Routing, Tests, And Verification

## Goal

Replace the two Classical setup stages with one merged stage, without changing StarDist, Cellpose, or standalone threshold setup.

## Routing Change

Update `CreateBinFileAnalysis.interactiveSegmentationObjectQC(...)`.

Current Classical routing:

```java
stages.add(classical ChannelThresholdStage);
stages.add(classical ParticleSizeStage);
```

Target Classical routing:

```java
stages.add(classical ClassicalSegmentationStage);
```

The method-specific stage list becomes:

1. `SegmentationMethodStage`
2. Classical-only `ClassicalSegmentationStage`
3. StarDist-only `StarDistParameterStage`
4. Cellpose-only `CellposeParameterStage`
5. optional AI threshold `ChannelThresholdStage`

The optional AI threshold stage stays separate because it is not part of Classical object generation.

## Factory Method

Add a factory in `CreateBinFileAnalysis`:

```java
ClassicalSegmentationStage createClassicalSegmentationStage(
        final BinUserConfig cfg,
        final File binFolder,
        final int channelIndex)
```

Reuse the existing adapter logic from:

- `createChannelThresholdStage(...)`
- `createParticleSizeStage(...)`

The new adapter should:

- duplicate the current channel for raw source
- apply configured filters to create filtered source
- run 3D Objects Counter exactly as `ParticleSizeStage` does now
- count objects exactly as `ParticleSizeStage` does now
- close visible legacy counter windows exactly as the current particle-size adapter does

## Method Stage Routing

Update `SegmentationMethodStage.stageKeyForChoice(...)`:

```java
if (CLASSICAL.equals(choice)) return ClassicalSegmentationStage.class.getName();
```

Update tests that currently expect Classical to route to `ChannelThresholdStage`.

## Existing Classes To Keep

Keep:

- `ChannelThresholdStage`
- `ParticleSizeStage`

Rationale:

- `ChannelThresholdStage` is still used outside merged Classical object setup.
- `ParticleSizeStage` can remain until no external code or tests rely on it. Removing it is optional cleanup, not part of this plan.

## Unit Tests

Add:

```text
src/test/java/flash/pipeline/ui/config/ClassicalSegmentationStageTest.java
```

Update:

```text
src/test/java/flash/pipeline/ui/config/SegmentationMethodStageTest.java
src/test/java/flash/pipeline/ui/config/ConfigQcDialogTest.java
src/test/java/flash/pipeline/analyses/CreateBinFileAnalysisTest.java
```

Important assertions:

- selecting Classical jumps to `ClassicalSegmentationStage`
- Classical flow has one method-specific stage after method selection
- StarDist and Cellpose still have one method-specific stage
- optional AI threshold still appears only for AI methods when requested
- `Lock in & Next` from Classical saves both threshold and size
- preview button stale state works after threshold and size edits

## Visual Verification

Run a local Fiji/UI check if possible:

1. open Set Up Configuration
2. choose a channel
3. select Classical
4. confirm the normal view shows:
   - left: threshold-applied preview
   - right: object preview area
5. drag threshold slider
6. confirm:
   - left preview changes live
   - object preview becomes stale
   - 3D Objects Counter does not run
7. edit min/max size
8. confirm object preview remains stale
9. press `Run Object Preview`
10. confirm right pane shows labels and object count
11. open `Large view`
12. confirm source without threshold, threshold preview, and object preview are available
13. lock in and verify saved config values

## Automated Test Command

Start with focused tests:

```text
mvn -Dtest=ClassicalSegmentationStageTest,SegmentationMethodStageTest,ConfigQcDialogTest test
```

Then run the broader affected setup tests:

```text
mvn -Dtest=CreateBinFileAnalysisTest,ChannelThresholdStageTest,ParticleSizeStageTest,PreviewPairPanelTest test
```

Finally run the full test suite if time allows:

```text
mvn test
```

## Completion Criteria

- The Classical object setup path has one method-specific stage.
- The normal merged Classical preview never shows raw or filtered input without the threshold.
- There is no threshold preview selector in the merged Classical stage.
- Large view provides access to original or filtered input without thresholding.
- Object preview is generated only from explicit user action.
- StarDist and Cellpose flows are unchanged except for any shared preview infrastructure needed by this plan.
