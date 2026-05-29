# FLASH config cleanup report

Cleanup range: `ef38b60^..210fb01`

Commits:
- `ef38b60` `flash-cleanup: retire derived sidecars (Channel_Data.txt, ZSlice_Selections.csv, channel_identities.json)`
- `90e204c` `flash-cleanup: remove .bin and legacy folder fallbacks`
- `2889701` `flash-cleanup: remove ImageOrientationSetupAnalysis (unused after rewrite)`
- `210fb01` `flash-cleanup: remove @Deprecated methods`

Summary:
- Changed files: 106 total (`93` modified, `11` deleted, `2` added).
- Diff volume: `875` inserted lines, `5271` deleted lines.
- Removed local legacy token matches from runtime/test source: `Channel_Data.txt`, `ZSlice_Selections.csv`, `channel_identities.json`, `LEGACY_BIN_DIR`, `ImageOrientationSetup`, `IDX_ORIENTATION_SETUP`, local `@Deprecated`, `parseStrict`, and `getLifFile`.

## Files deleted

Line counts are pre-deletion line counts.

| Lines | File |
| ---: | --- |
| 1222 | `src/main/java/flash/pipeline/analyses/ImageOrientationSetupAnalysis.java` |
| 149 | `src/main/java/flash/pipeline/bin/ChannelIdentitiesIO.java` |
| 100 | `src/main/java/flash/pipeline/io/OrientationAliasIO.java` |
| 93 | `src/test/java/flash/pipeline/analyses/ImageOrientationSetupModelTest.java` |
| 66 | `src/test/java/flash/pipeline/bin/BinConfigCellposeTokenRoundTripTest.java` |
| 109 | `src/test/java/flash/pipeline/bin/BinConfigIORoundTripTest.java` |
| 959 | `src/test/java/flash/pipeline/bin/BinConfigIOTest.java` |
| 68 | `src/test/java/flash/pipeline/bin/ChannelIdentitiesIOTest.java` |
| 164 | `src/test/java/flash/pipeline/bin/DerivedLegacyWriterTest.java` |
| 74 | `src/test/java/flash/pipeline/integration/FlashOutputLayoutCompatibilityTest.java` |
| 10 | `src/test/resources/channel-config/fixtures/3ch_classical_committed.Channel_Data.txt` |

## Files added

| Lines | File |
| ---: | --- |
| 67 | `src/test/java/flash/pipeline/bin/BinConfigIOJsonTest.java` |
| 75 | `src/test/java/flash/pipeline/TestConfigFiles.java` |

## Files modified

Line counts are current at `210fb01`.

| Lines | File |
| ---: | --- |
| 2048 | `src/main/java/flash/pipeline/FLASH_Pipeline.java` |
| 9737 | `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java` |
| 3280 | `src/main/java/flash/pipeline/analyses/IntensityAnalysisV2.java` |
| 2763 | `src/main/java/flash/pipeline/analyses/MasterAggregationAnalysis.java` |
| 6237 | `src/main/java/flash/pipeline/analyses/SpatialAnalysis.java` |
| 2682 | `src/main/java/flash/pipeline/analyses/SplitAndMergeImageChannelsAnalysis.java` |
| 5579 | `src/main/java/flash/pipeline/analyses/ThreeDObjectAnalysis.java` |
| 48 | `src/main/java/flash/pipeline/analyses/wizard/AggregationPresetIO.java` |
| 225 | `src/main/java/flash/pipeline/analyses/wizard/BinPreset.java` |
| 55 | `src/main/java/flash/pipeline/analyses/wizard/BinPresetIO.java` |
| 55 | `src/main/java/flash/pipeline/analyses/wizard/IntensityPresetIO.java` |
| 128 | `src/main/java/flash/pipeline/analyses/wizard/SpatialPresetIO.java` |
| 65 | `src/main/java/flash/pipeline/analyses/wizard/StatisticsPresetIO.java` |
| 92 | `src/main/java/flash/pipeline/analyses/wizard/ThreeDObjectPresetIO.java` |
| 291 | `src/main/java/flash/pipeline/bin/BinBypassDialog.java` |
| 229 | `src/main/java/flash/pipeline/bin/BinConfigIO.java` |
| 167 | `src/main/java/flash/pipeline/bin/BinMacroIndex.java` |
| 407 | `src/main/java/flash/pipeline/bin/BinSetupDispatcher.java` |
| 425 | `src/main/java/flash/pipeline/bin/ChannelConfigIO.java` |
| 86 | `src/main/java/flash/pipeline/bin/ChannelIdentities.java` |
| 139 | `src/main/java/flash/pipeline/click/ClicksConfigIO.java` |
| 572 | `src/main/java/flash/pipeline/click/training/cellpose/CellposeDatasetPackager.java` |
| 844 | `src/main/java/flash/pipeline/click/training/stardist/StarDistDatasetPackager.java` |
| 2932 | `src/main/java/flash/pipeline/decontamination/SpectralDecontaminationAnalysis.java` |
| 366 | `src/main/java/flash/pipeline/decontamination/SpectralDecontaminationConfig.java` |
| 495 | `src/main/java/flash/pipeline/decontamination/SpectralDecontaminationConfigIO.java` |
| 758 | `src/main/java/flash/pipeline/decontamination/wizard/SpectralDecontaminationWizard.java` |
| 57 | `src/main/java/flash/pipeline/decontamination/wizard/SpectralDecontamPresetIO.java` |
| 52 | `src/main/java/flash/pipeline/export/ExcelExportPresetIO.java` |
| 1463 | `src/main/java/flash/pipeline/export/ExcelSummaryExportAnalysis.java` |
| 266 | `src/main/java/flash/pipeline/help/AnalysisAdvisor.java` |
| 679 | `src/main/java/flash/pipeline/help/AnalysisHelpCatalog.java` |
| 296 | `src/main/java/flash/pipeline/help/SetupHelpCatalog.java` |
| 124 | `src/main/java/flash/pipeline/image/NamedFilterLoader.java` |
| 379 | `src/main/java/flash/pipeline/intelligence/AnalysisStatusScanner.java` |
| 150 | `src/main/java/flash/pipeline/intelligence/BinValidator.java` |
| 787 | `src/main/java/flash/pipeline/io/DeferredImageSupplier.java` |
| 346 | `src/main/java/flash/pipeline/io/FlashProjectLayout.java` |
| 198 | `src/main/java/flash/pipeline/io/TifCache.java` |
| 226 | `src/main/java/flash/pipeline/naming/ImageNameParser.java` |
| 86 | `src/main/java/flash/pipeline/naming/ImageOrientationResolver.java` |
| 88 | `src/main/java/flash/pipeline/orientation/OrientationTransformState.java` |
| 238 | `src/main/java/flash/pipeline/recipes/PipelineRecipe.java` |
| 221 | `src/main/java/flash/pipeline/results/IntensityDetailsWriter.java` |
| 324 | `src/main/java/flash/pipeline/segmentation/catalog/ModelKeyRewriter.java` |
| 361 | `src/main/java/flash/pipeline/ui/wizard/PresetIO.java` |
| 20 | `src/main/java/flash/pipeline/zslice/ZSliceConfigIO.java` |
| 120 | `src/test/java/flash/pipeline/FLASH_PipelineIndexShiftTest.java` |
| 1575 | `src/test/java/flash/pipeline/analyses/CreateBinFileAnalysisTest.java` |
| 411 | `src/test/java/flash/pipeline/analyses/DrawAndSaveROIsAnalysisTest.java` |
| 799 | `src/test/java/flash/pipeline/analyses/IntensityAnalysisV2Test.java` |
| 241 | `src/test/java/flash/pipeline/analyses/LineDistanceAnalysisTest.java` |
| 797 | `src/test/java/flash/pipeline/analyses/SplitAndMergeImageChannelsAnalysisTest.java` |
| 76 | `src/test/java/flash/pipeline/analyses/SplitAndMergeWizardBehaviorTest.java` |
| 561 | `src/test/java/flash/pipeline/analyses/ThreeDObjectAnalysisTest.java` |
| 143 | `src/test/java/flash/pipeline/analyses/TrainedRfMissingModelFailsAnalysisTest.java` |
| 107 | `src/test/java/flash/pipeline/analyses/wizard/BinPresetIOTest.java` |
| 107 | `src/test/java/flash/pipeline/analyses/wizard/IntensityPresetIOTest.java` |
| 100 | `src/test/java/flash/pipeline/analyses/wizard/SpatialPresetIOTest.java` |
| 146 | `src/test/java/flash/pipeline/analyses/wizard/ThreeDObjectPresetIOTest.java` |
| 145 | `src/test/java/flash/pipeline/audit/RunSettingsSnapshotSegmentationModelsTest.java` |
| 205 | `src/test/java/flash/pipeline/audit/RunSettingsSnapshotTest.java` |
| 64 | `src/test/java/flash/pipeline/bin/BinConfigEnhancedClassicalRoundTripTest.java` |
| 45 | `src/test/java/flash/pipeline/bin/BinConfigIODelegationTest.java` |
| 64 | `src/test/java/flash/pipeline/bin/BinConfigStarDistTokenRoundTripTest.java` |
| 80 | `src/test/java/flash/pipeline/bin/BinMacroIndexTest.java` |
| 364 | `src/test/java/flash/pipeline/bin/BinSetupDispatcherTest.java` |
| 72 | `src/test/java/flash/pipeline/bin/ChannelConfigGoldenFixtureTest.java` |
| 119 | `src/test/java/flash/pipeline/bin/ChannelConfigToBinConfigTest.java` |
| 219 | `src/test/java/flash/pipeline/bin/ConsumerAnalysesAgainstJsonProjectTest.java` |
| 98 | `src/test/java/flash/pipeline/click/ClicksConfigIOTest.java` |
| 153 | `src/test/java/flash/pipeline/click/training/cellpose/CellposeDatasetPackagerIntegrationTest.java` |
| 387 | `src/test/java/flash/pipeline/click/training/cellpose/CellposeDatasetPackagerTest.java` |
| 422 | `src/test/java/flash/pipeline/click/training/stardist/StarDistDatasetPackagerTest.java` |
| 157 | `src/test/java/flash/pipeline/decontamination/SpectralDecontaminationConfigIOTest.java` |
| 44 | `src/test/java/flash/pipeline/decontamination/wizard/SpectralDecontamAutoDetectTest.java` |
| 105 | `src/test/java/flash/pipeline/help/AnalysisAdvisorMemoizerTest.java` |
| 406 | `src/test/java/flash/pipeline/help/AnalysisHelpCatalogTest.java` |
| 124 | `src/test/java/flash/pipeline/intelligence/AnalysisStatusScannerTest.java` |
| 45 | `src/test/java/flash/pipeline/intelligence/BinValidatorTest.java` |
| 197 | `src/test/java/flash/pipeline/intelligence/PostRunSummaryTest.java` |
| 172 | `src/test/java/flash/pipeline/io/FlashProjectLayoutTest.java` |
| 502 | `src/test/java/flash/pipeline/io/ImageSourceDispatcherTest.java` |
| 114 | `src/test/java/flash/pipeline/io/LooseTiffRelocatorTest.java` |
| 40 | `src/test/java/flash/pipeline/io/TifCacheTest.java` |
| 174 | `src/test/java/flash/pipeline/naming/ImageNameParserTest.java` |
| 31 | `src/test/java/flash/pipeline/recipes/PipelineRecipeTest.java` |
| 63 | `src/test/java/flash/pipeline/recipes/RecipeReplayTokenCompatibilityTest.java` |
| 212 | `src/test/java/flash/pipeline/runtime/DependencyRuntimeIntegrationTest.java` |
| 168 | `src/test/java/flash/pipeline/segmentation/catalog/ModelKeyRewriterTest.java` |
| 86 | `src/test/java/flash/pipeline/ui/ModelKeyRewriterControllerTest.java` |
| 153 | `src/test/java/flash/pipeline/ui/wizard/PresetIOTest.java` |
| 86 | `src/test/resources/channel-config/fixtures/3ch_classical_committed.json` |

## Classes deleted entirely

Runtime classes:
- `flash.pipeline.analyses.ImageOrientationSetupAnalysis`
- `flash.pipeline.bin.ChannelIdentitiesIO`
- `flash.pipeline.io.OrientationAliasIO`

Test classes:
- `flash.pipeline.analyses.ImageOrientationSetupModelTest`
- `flash.pipeline.bin.BinConfigCellposeTokenRoundTripTest`
- `flash.pipeline.bin.BinConfigIORoundTripTest`
- `flash.pipeline.bin.BinConfigIOTest`
- `flash.pipeline.bin.ChannelIdentitiesIOTest`
- `flash.pipeline.bin.DerivedLegacyWriterTest`
- `flash.pipeline.integration.FlashOutputLayoutCompatibilityTest`

## Tests deleted entirely

Deleted full test classes: `7`

| Lines | Test |
| ---: | --- |
| 93 | `ImageOrientationSetupModelTest` |
| 66 | `BinConfigCellposeTokenRoundTripTest` |
| 109 | `BinConfigIORoundTripTest` |
| 959 | `BinConfigIOTest` |
| 68 | `ChannelIdentitiesIOTest` |
| 164 | `DerivedLegacyWriterTest` |
| 74 | `FlashOutputLayoutCompatibilityTest` |

Total deleted test-class lines: `1533`.

## Test count delta

Baseline was the supplied pre-cleanup count.

| Gate | Tests run | Failures | Errors | Skipped | Delta vs previous |
| --- | ---: | ---: | ---: | ---: | ---: |
| Supplied baseline before cleanup | 2362 | 0 | 0 | 28 | n/a |
| After sidecar cleanup (`ef38b60`) | 2375 | 0 | 0 | 28 | +13 |
| After legacy fallback cleanup (`90e204c`) | 2389 | 0 | 0 | 28 | +14 |
| After orientation cleanup (`2889701`) | 2384 | 0 | 0 | 28 | -5 |
| After deprecated-method cleanup (`210fb01`) | 2384 | 0 | 0 | 28 | 0 |
| Final package gate (`210fb01`) | 2384 | 0 | 0 | 28 | 0 |

Net test-count delta from the supplied baseline: `+22` run, skipped unchanged. The count increased despite deleting legacy parser tests because the cleanup added JSON-path coverage and shared test fixtures, while retained tests were moved from sidecar/legacy assertions to the new `channel_config.json` runtime path.

## Investigated and intentionally kept

- `ChannelIdentities` remains as an in-memory value object because intensity, spatial, split/merge, 3D objects, and spectral decontamination code still share that shape. The sidecar reader/writer class is gone; identities now come from `ChannelConfigIO.readChannelIdentities(...)`.
- `ZSliceConfigIO` remains because `signature(ZSliceConfig)` is still used as an in-memory/config signature helper. CSV read/write APIs were removed.
- `BinPreset` remains because it now represents JSON/preset payloads including marker identities and z-slice mode, not the old `Channel_Data.txt` mirror format.
- `Images` remains only as the active `FLASH/Results/Presentation Images/Images` output subfolder. Project-root legacy `Images/` read fallbacks were removed.
- `Image Analysis` remains only as a user-facing dialog section label in `FLASH_Pipeline`; legacy folder resolution for `Image Analysis/` was removed.
- Maven still reports a compile warning about deprecated external API use in `ObjectsCounter3DWrapper`. No local `@Deprecated` annotations remain in `src/main/java`.

## Final verification

- `bash mvnw clean test "-Denforcer.skip=true"` after `210fb01`: build success; `2384` tests run, `0` failures, `0` errors, `28` skipped.
- `bash mvnw clean package "-Denforcer.skip=true"` after `210fb01`: build success; `2384` tests run, `0` failures, `0` errors, `28` skipped; rebuilt `target/FLASH-4.0.0.jar`.
