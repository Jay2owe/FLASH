# Parity audit: docs/custom-segmentation-engines/ vs implementation

Date: 2026-05-16
Total stages: 10 (plus PLAN.md)
Findings: 0 BLOCKER, 0 MAJOR, 0 MINOR, 7 IMPLEMENTED, 3 SUPERSEDED, 7 RESOLVED

Method note: `python -m graphify query "custom segmentation engine model catalog parser StarDist Cellpose implementation locations" --graph graphify-out/graph.json` was attempted, but local `python.exe` failed with "A specified logon session does not exist. It may already have been terminated." This audit continued with targeted source reads and `rg`.

## Executive summary

- Top remaining user-impacting gap: none in the counted custom-engine parity findings.
- Resolved gaps: per-channel Object Analysis details now report the selected StarDist/Cellpose/trained-RF model display name plus key; Custom Model Manager delete now keeps copied files by default; the manager now has status, validate-all, and duplicate-as-custom affordances for stock entries; training guidance now has a source-tree guide plus direct help links from the manager and QC model rows; CLI usage now documents the legacy StarDist form plus legacy token rewrites; and README now documents runtime/catalog constraints.
- Stages with 100% functional parity after merged-plan substitutions: 01, 02, 03, 04, 05.
- Stages with significant divergence: none among counted stages. Stage 10 is deliberately deferred/superseded.

## Stage 01 - Segmentation Config Model

### Feature: Central parser/formatter and BinConfig/BinConfigIO delegation
- Status: IMPLEMENTED
- Evidence: `SegmentationTokenParser` handles classical, enhanced classical, StarDist, Cellpose, and trained RF tokens in `src/main/java/flash/pipeline/segmentation/SegmentationTokenParser.java:35`; `BinConfig` delegates Cellpose getters through `SegmentationMethod` in `src/main/java/flash/pipeline/bin/BinConfig.java:195`; `BinConfigIO` writes line 7 with `segmentationTokensForWrite(...)` in `src/main/java/flash/pipeline/bin/BinConfigIO.java:230`; Cellpose tokens are canonicalized on write in `src/main/java/flash/pipeline/bin/BinConfigIO.java:454`.
- Gap: None.

### Feature: Exact pre-merge token shape and StarDist legacy formatter behavior
- Status: SUPERSEDED
- Evidence: The merged plan changed the canonical Cellpose grammar to `cellpose:<diameter>:<flow>:<cellprob>:...:model=<modelKey>` and says old StarDist tokens may format without `model=` unless new UI writes it in `docs/segmentation-and-training/00_overview.md:59` and `docs/segmentation-and-training/00_overview.md:64`; legacy Cellpose positional tokens still parse and are rewritten to canonical model-key form in `docs/segmentation-and-training/00_overview.md:70`.
- Gap: None. This is a deliberate merged-plan grammar change, not an implementation miss.

## Stage 02 - Model Catalog IO

### Feature: Catalog schema, stock entries, project-scoped file storage, and lookup
- Status: IMPLEMENTED
- Evidence: `ModelEntry` persists `modelKey`, `name`, `description`, `engine`, `source`, `filePath`, `resourcePath`, `pretrainedModel`, defaults, metadata, and `supportsSecondChannel` in `src/main/java/flash/pipeline/segmentation/catalog/ModelEntry.java:75` and `src/main/java/flash/pipeline/segmentation/catalog/ModelEntry.java:151`; stock StarDist and Cellpose entries live in `src/main/resources/segmentation_models/stock_catalog.json:5`, `src/main/resources/segmentation_models/stock_catalog.json:19`, `src/main/resources/segmentation_models/stock_catalog.json:33`, and `src/main/resources/segmentation_models/stock_catalog.json:51`; project entries merge after stock entries in `src/main/java/flash/pipeline/segmentation/catalog/ModelCatalogIO.java:65`; imported files are copied under catalog `files/<modelKey>/...` in `src/main/java/flash/pipeline/segmentation/catalog/ModelCatalog.java:100`.
- Gap: None.

### Feature: Absolute external model paths
- Status: SUPERSEDED
- Evidence: The pre-merge stage offered an "Use absolute path" advanced toggle in `docs/custom-segmentation-engines/06_custom-model-manager.md:50`, but the merged follow-up tracker explicitly defers external absolute paths and keeps imports copied into `Configuration/Segmentation Models/files/<modelKey>/` in `docs/segmentation-followups/priority-b/05_external_model_paths.md:5` and `docs/segmentation-followups/priority-b/05_external_model_paths.md:7`.
- Gap: None. This was intentionally deferred for portability.

## Stage 03 - StarDist Model Selection

### Feature: StarDist runner and preview paths honor selected catalog model
- Status: IMPLEMENTED
- Evidence: `StarDist3DRunner.run(...)` accepts `modelKey` in `src/main/java/flash/pipeline/stardist/StarDist3DRunner.java:205`; it resolves the model file from the catalog in `src/main/java/flash/pipeline/stardist/StarDist3DRunner.java:223`; TrackMate settings receive the resolved model file plus score and overlap thresholds in `src/main/java/flash/pipeline/stardist/StarDist3DRunner.java:491`; 3D Object Analysis passes the channel model key in `src/main/java/flash/pipeline/analyses/ThreeDObjectAnalysis.java:3529`; the embedded setup preview passes `parameters.modelKey` in `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:6610`.
- Gap: None. Missing custom models fail through the resolver rather than silently falling back.

## Stage 04 - Cellpose Model Selection

### Feature: Cellpose runner and persistent worker resolve model keys without cyto3 fallback
- Status: IMPLEMENTED
- Evidence: `CellposeModel.fromToken(...)` now throws on unknown fixed-enum names rather than returning `cyto3` in `src/main/java/flash/pipeline/cellpose/CellposeModel.java:66`; command building resolves `--pretrained_model` through `CellposeModelResolver` in `src/main/java/flash/pipeline/cellpose/Cellpose3DRunner.java:445`; missing catalog entries throw an actionable error in `src/main/java/flash/pipeline/cellpose/Cellpose3DRunner.java:498`; `CellposePersistentWorker` resolves the same model argument before starting Python in `src/main/java/flash/pipeline/cellpose/CellposePersistentWorker.java:292`.
- Gap: None.

## Stage 05 - QC Dialog Model UI

### Feature: StarDist and Cellpose QC selectors use the catalog and write stable model keys
- Status: IMPLEMENTED
- Evidence: StarDist loads catalog options and exposes `Manage models...` in `src/main/java/flash/pipeline/ui/config/StarDistParameterStage.java:513` and `src/main/java/flash/pipeline/ui/config/StarDistParameterStage.java:528`; StarDist applies entry defaults and writes `model=` through `SegmentationTokenParser.format(...)` in `src/main/java/flash/pipeline/ui/config/StarDistParameterStage.java:984` and `src/main/java/flash/pipeline/ui/config/StarDistParameterStage.java:1849`; Cellpose uses catalog options and `Manage models...` in `src/main/java/flash/pipeline/ui/config/CellposeParameterStage.java:668` and `src/main/java/flash/pipeline/ui/config/CellposeParameterStage.java:699`; Cellpose writes model keys through `formatMethod(...)` in `src/main/java/flash/pipeline/ui/config/CellposeParameterStage.java:1921`.
- Gap: None. Missing-model replacement rows and manager shortcuts are present in both stages.

## Stage 06 - Custom Model Manager

### Feature: Import and validate custom StarDist and Cellpose entries
- Status: IMPLEMENTED
- Evidence: StarDist import validates ZIP model markers in `src/main/java/flash/pipeline/ui/SegmentationModelImportService.java:38`; Cellpose file and registered-name validation are in `src/main/java/flash/pipeline/ui/SegmentationModelImportService.java:62` and `src/main/java/flash/pipeline/ui/SegmentationModelImportService.java:66`; controller imports copy files and persist relative catalog paths in `src/main/java/flash/pipeline/ui/SegmentationModelManagerController.java:97` and `src/main/java/flash/pipeline/ui/SegmentationModelManagerController.java:116`.
- Gap: None for import paths covered by the merged plan.

### Feature: Conservative delete flow
- Status: RESOLVED
- Severity: MAJOR
- Resolution: RESOLVED in commit `9c3b26e`. The delete dialog now says catalog entries are removed while copied files are kept by default, adds an unchecked "Also delete copied model files on disk" checkbox, and routes the checkbox through explicit metadata-only vs metadata-plus-files controller/catalog paths.
- Evidence: `SegmentationModelManagerDialog` adds the opt-in checkbox and conservative wording; `SegmentationModelManagerController.delete(modelKey)` now keeps files by default while `delete(modelKey, true)` removes them; `ModelCatalog.remove(modelKey, boolean removeFiles)` separates row removal from copied file deletion. `ModelManagerConservativeDeleteTest` covers both default and opt-in flows and asserts the saved catalog no longer references the deleted entry in either case.
- Gap: None.

### Feature: Stock-entry fork/edit, status column, and validate-all action
- Status: RESOLVED
- Severity: MINOR
- Resolution: RESOLVED in commit `35870b7`. The manager table now includes a `Status` column, `Validate all` refreshes status resolution for every catalog entry, and read-only stock rows expose `Duplicate as custom...` to create an editable user-owned copy with copied defaults.
- Evidence: `SegmentationModelManagerDialog` adds the status column, validate-all action, and duplicate button; `SegmentationModelManagerController` validates entries through resolved files or registered Cellpose names and creates custom duplicates without making stock entries editable in place; `SegmentationModelImportService` exposes resolved-file validation using the same StarDist/Cellpose import checks. `ModelManagerStatusColumnTest`, `ModelManagerStockForkTest`, and `ModelManagerValidateAllTest` cover the new behavior.
- Gap: None.

## Stage 07 - Training Guidance

### Feature: In-app training guide and source-tree training documentation
- Status: RESOLVED
- Severity: MINOR
- Evidence: The pre-merge spec required a setup-help topic, model-manager training button, QC-stage training link, and `docs/training_segmentation_models.md` in `docs/custom-segmentation-engines/07_training-guidance.md:32`, `docs/custom-segmentation-engines/07_training-guidance.md:38`, `docs/custom-segmentation-engines/07_training-guidance.md:39`, and `docs/custom-segmentation-engines/07_training-guidance.md:45`; implementation has an auxiliary analysis help topic in `src/main/java/flash/pipeline/help/AnalysisHelpCatalog.java:15` and `src/main/java/flash/pipeline/help/AnalysisHelpCatalog.java:89`; the model manager help button opens only `CUSTOM_MODEL_MANAGER` in `src/main/java/flash/pipeline/ui/SegmentationModelManagerDialog.java:196`; StarDist and Cellpose QC stages still return their ordinary setup topics in `src/main/java/flash/pipeline/ui/config/StarDistParameterStage.java:206` and `src/main/java/flash/pipeline/ui/config/CellposeParameterStage.java:276`. `docs/training_segmentation_models.md` does not exist.
- Resolution: RESOLVED in commit `fc31eab`. `docs/training_segmentation_models.md` now covers StarDist, Cellpose 3, trained RF, data sizing, pitfalls, import paths, Cellpose 4 exclusion, and per-slice StarDist behavior. `AnalysisHelpCatalog.TRAIN_CUSTOM_SEGMENTATION_MODELS` was refreshed with the same constraints, `SegmentationModelManagerDialog` now has a `Train a custom model...` help entry, and StarDist/Cellpose QC model rows expose compact training-help buttons.
- Gap: None.

## Stage 08 - CLI, Audit, Recipe Surfaces

### Feature: Replay, recipe, and stable model-key token preservation
- Status: IMPLEMENTED
- Evidence: replay commands include raw `segmentation_methods` in `src/main/java/flash/pipeline/audit/ReplayCommandFormatter.java:28`; recipe replay resolves StarDist and Cellpose model keys in `src/main/java/flash/pipeline/recipes/RecipeReplayModelResolver.java:60` and `src/main/java/flash/pipeline/recipes/RecipeReplayModelResolver.java:76`; CLI stores `segmentation_methods` into bin fields in `src/main/java/flash/pipeline/cli/CLIArgumentParser.java:467`.
- Gap: None for token preservation.

### Feature: Per-channel analysis details identify selected model by display name and key
- Status: RESOLVED
- Severity: MAJOR
- Evidence: The pre-merge spec required StarDist details to write `<display name> (key=<modelKey>)` and custom model-file comments in `docs/custom-segmentation-engines/08_cli-audit-recipe-surfaces.md:39`; it required Cellpose details to include display name plus key in `docs/custom-segmentation-engines/08_cli-audit-recipe-surfaces.md:54`; current StarDist details still hard-code `// Model: Versatile (fluorescent nuclei)` and `modelChoice='Versatile (fluorescent nuclei)'` in `src/main/java/flash/pipeline/results/ObjectAnalysisDetailsWriter.java:161`; the 3D Object call site passes thresholds but no model entry/key to that writer in `src/main/java/flash/pipeline/analyses/ThreeDObjectAnalysis.java:1020`; current Cellpose details write only `// Model: <modelToken>` in `src/main/java/flash/pipeline/results/ObjectAnalysisDetailsWriter.java:239`, and the call site passes only `cfg.getCellposeModel(...)` in `src/main/java/flash/pipeline/analyses/ThreeDObjectAnalysis.java:1044`.
- Gap: Analysis output can misrepresent the actual StarDist model used, and Cellpose details lack the friendly name/key pairing promised for reproducibility. The separate `segmentation_models.txt` sidecar partly mitigates this, but it does not fix the per-channel details file.
- Suggested fix: Resolve the selected catalog entry before calling `writeStarDistPerChannel(...)` / `writeCellposePerChannel(...)`, extend those signatures to accept display name, key, and Fiji/pretrained metadata, and update tests to cover `stardist_dsb2018_paper` and a custom model.
- Resolution: Fixed in commit `af0ecc6ab3b1a53740add453d2fb32b6c4bef15e`; `ObjectAnalysisDetailsWriter` now writes resolved display-name/key/source metadata for StarDist and Cellpose details, emits custom StarDist model-file comments, and adds trained-RF model metadata.

### Feature: Run snapshot includes derived `segmentation_models` metadata
- Status: RESOLVED
- Severity: MINOR
- Evidence: The pre-merge spec required a root-level `segmentation_models` array in `RunSettingsSnapshot.toJsonObject()` in `docs/custom-segmentation-engines/08_cli-audit-recipe-surfaces.md:66`; current `toJsonObject()` writes `flash_version`, timestamp, analysis, directory, `bin_config`, `field_sources`, and `replay_command`, but no model metadata array, in `src/main/java/flash/pipeline/audit/RunSettingsSnapshot.java:113`.
- Gap: Raw tokens are preserved, but snapshots lack the promised derived channel index, display name, model key, and source type metadata.
- Suggested fix: Add an additive `segmentation_models` array beside `bin_config`, populated by parsing `binConfig.segmentationMethods` and resolving entries from the project catalog when available.
- Resolution: Fixed in commit `e4b46498b1a6cbe43f4a25905cd992773aa3a518`; `RunSettingsSnapshot.toJsonObject()` now emits a root-level `segmentation_models` array with one derived metadata row per segmentation-method channel and degrades unresolved model keys to `source_type=unknown`.

### Feature: CLI usage documents legacy and model-key forms
- Status: RESOLVED
- Severity: MINOR
- Evidence: The pre-merge spec required CLI usage to list the legacy StarDist form, new StarDist model-key form, and Cellpose form in `docs/custom-segmentation-engines/08_cli-audit-recipe-surfaces.md:76`; current usage includes examples for enhanced classical, StarDist with `model=`, Cellpose with `model=`, and trained RF in `src/main/java/flash/pipeline/cli/CLIArgumentParser.java:239`, but it does not list `stardist:<prob>:<nms>` as a valid legacy form.
- Resolution: RESOLVED in commit `7ec6031`. `CLIArgumentParser.usage()` now lists `stardist:<prob>:<nms>` as the accepted legacy StarDist form, keeps the canonical StarDist and Cellpose `model=` examples, and clarifies that legacy Cellpose positional tokens are accepted and rewritten to canonical `model=` form internally.
- Gap: None.

## Stage 09 - Validation And Docs

### Feature: Public docs final pass
- Status: RESOLVED
- Severity: MINOR
- Evidence: The pre-merge validation stage required README coverage for pinned Cellpose 3/no Cellpose 4, Fiji-compatible StarDist zips, per-slice StarDist + Z-linking, and a link to `docs/training_segmentation_models.md` in `docs/custom-segmentation-engines/09_validation-docs.md:35`; current README mentions selectable built-ins/custom model import and external training in `README.md:90` and `README.md:92`, but does not include the Cellpose 4 exclusion, per-slice 2D StarDist caveat, model catalog layout, or the missing training guide link.
- Resolution: RESOLVED in commit `07a821c`. README now includes a compact runtime and catalog note covering Cellpose 3.1.1.2 pinning, no Cellpose 4 / Cellpose-SAM / cpsam, Fiji-compatible StarDist `.zip` imports, per-slice 2D StarDist with Z-linking, the `<projectRoot>/FLASH/Configuration/Segmentation Models/` catalog layout, and the training guide link.
- Gap: None.

## Stage 10 - Few-Shot Interactive Segmentation

### Feature: SAM/micro-SAM few-shot interactive segmentation backend
- Status: SUPERSEDED
- Evidence: The custom-engine folder marks Stage 10 as deferred in `docs/custom-segmentation-engines/SUPERSEDED.md:23`; the follow-up tracker says the 2026-05 pass built everything except the SAM backend in `docs/segmentation-followups/out-of-scope/01_sam_few_shot_backend.md:5` and `docs/segmentation-followups/out-of-scope/01_sam_few_shot_backend.md:7`.
- Gap: None. This was explicitly out of scope.

## Cross-stage findings

### Finding: Analysis model metadata is split across surfaces
- Counted under: Stage 08 MAJOR and MINOR findings.
- Gap: Runtime uses selected model keys correctly, replay preserves raw tokens, and `ObjectAnalysisDetailsWriter.writeSegmentationModelsReport(...)` emits a useful `segmentation_models.txt` sidecar. The parity miss is that the two originally promised audit surfaces, per-channel details and run snapshot JSON, were not both updated.

### Finding: Known prior-audit items not re-counted here
- Not re-reported: the setup-QC project-root bug and dataset metadata click-sidecar drift from `docs/segmentation-and-training/PARITY_CLICK_TRAINING.md`, and the trained RF/headless/runtime items from `docs/segmentation-and-training/AUDIT_PASS3.md` and `docs/segmentation-and-training/RUNTIME_AUDIT.md`.
