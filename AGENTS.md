# FLASH — Agent Guide

The Pipeline for Fluorescence Automated Spatial Histology. Java/Maven ImageJ plugin. Build target `target/FLASH-<version>.jar`. Package root is `flash.pipeline`, entry class `FLASH_Pipeline`.

## Before broad search: query the knowledge graph

A pre-built knowledge graph of the entire `src/` tree lives at `graphify-out/graph.json` (2236+ nodes, ~129 communities). **Use it before reaching for Glob/Grep on architecture questions** — typically 30-100x cheaper than raw search.

```bash
python -m graphify query "<your question>" --graph graphify-out/graph.json
```

Examples worth trying:
- `python -m graphify query "where is filter macro execution wired?" --graph graphify-out/graph.json`
- `python -m graphify query "what handles cell click in variations collage?" --graph graphify-out/graph.json`
- `python -m graphify query "DAPI channel preview pipeline" --graph graphify-out/graph.json`

The graph auto-rebuilds via a post-commit hook on code changes (no LLM tokens). If query results look stale or empty (`0 edges`), the hook may have failed — fall back to grep and report it.

There is also an Obsidian-format vault at `graphify-out/obsidian/` with one note per node, tagged by `pkg/role/subsystem/community`. Useful if your tools include filesystem read.

## Build

```bash
export JAVA_HOME="/c/Users/Owner/OneDrive - Imperial College London/ImageJ/Experiments/First Experiment Round/Combined/Oracle_JDK-23"
bash mvnw clean package -Denforcer.skip=true
```

`-Denforcer.skip=true` is required. Java 8 source compatibility is mandatory (Fiji runtime constraint). Maven parent is `pom-scijava`. `mcib3d-core` and Apache POI are `provided` — Fiji supplies them at runtime. If `mcib3d-core` is missing from the local Maven cache, install once from `Fiji.app/plugins/mcib3d-suite/mcib3d-core-4.1.7b.jar`.

## What "deploy", "push", "update site" mean here

These are three distinct operations. Do not conflate them.

- **deploy** = copy built JAR into local Fiji `plugins/` and the shared lab plugin folder. Never pushes to GitHub. Never uploads to update site. Only runs on explicit request.
- **push** = publish to public GitHub (`origin/master`). Requires a public-safety audit (no Dropbox/OneDrive/LabAdmin paths, no secrets, no `CLAUDE_CONTEXT`, no planning docs). Use `--force-with-lease` when replacing history, never plain `--force`.
- **update site** = upload to `https://sites.imagej.net/FLASH/`. Only runs on explicit request, separate from deploy and push.

## Hard rules

- Do not modify `CLAUDE.md` or `pom.xml` unless the task explicitly asks for it.
- Pre-existing dirty worktree changes from parallel sessions must be left untouched unless the task says to include them. Stage commits with explicit `git add <file>` per file, never `git add -A`/`git add .`.
- Don't skip hooks (`--no-verify`) or bypass signing. If a hook fails, investigate the root cause.
- Don't push to `origin/master` unless the user explicitly approved that scope. `git push origin <staging-branch>` is fine; `git push origin HEAD:master` needs approval.

## Local-only files (do not commit, do not publish)

The following exist on disk for local agent use but must never reach the public branch:

- `CLAUDE.md`, `CLAUDE_CONTEXT.md`, `CLAUDE_CONTEXT_CONDENSED.md` (already in `.gitignore`)
- `.claude/`, `docs/` (planning notes), local scripts under `scripts/` that reference Dropbox paths
- `graphify-out/` (regenerated locally; not for the public tree)

## Useful conventions to know

- Filename convention parsed by `flash.pipeline.naming.ImageNameParser`: `Experiment-AnimalID_Hemisphere_Region`. Hemisphere must be `LH` or `RH`. `parse()` falls back to full title as animal ID; never use `parseStrict()` in production paths.
- `PipelineDialog` (not `GenericDialog`) for new UI. `ToggleSwitch` for boolean options. Use bold section headers and compact spacing.
- `Duplicator` is NOT thread-safe — use `ImageProcessor.crop()` per slice in parallel paths.
- Filter macros run via `FilterExecutor.runThreadSafe(image, macro)`. Some macros close the original; recover via `FilterExecutor.adoptResultIfOriginalClosed(...)`.
- The variations cache lives at `<binFolder>/variations_cache/<key>.tif` and persists across runs. Cache keys must incorporate pixel-content fingerprint (already done in `FilterVariationEngineContext.sourceImageHash`), not just title+dims, or stale TIFFs poison subsequent runs.

## Where to look first by area

- Analysis dispatch: `flash.pipeline.FLASH_Pipeline`, `flash.pipeline.cli.CLIArgumentParser`
- Channel setup wizard: `flash.pipeline.analyses.wizard.ChannelSetupWizard`, `flash.pipeline.analyses.CreateBinFileAnalysis`
- Filter macros: `flash.pipeline.image.FilterExecutor`, `flash.pipeline.image.FilterMacroEditorModel`
- Variations UI: `flash.pipeline.ui.variations.*` (filter sweeps, presets, step swap)
- Segmentation: `flash.pipeline.ui.config.{StarDist,Cellpose,Classical,EnhancedClassical}*`
- 3D morphometry: `flash.pipeline.analyses.SpatialAnalysis.run3DMorphometry`
