# Soteras et al. FLASH recorded-analysis replay

This bundle calls the public Java application programming interface (API)
`FLASH_Pipeline.runReplayCli` with original recorded parameters. It freezes input
and configuration hashes, copies declared files into a separate empty project,
runs the recorded analysis stages, and writes a success/failure receipt. Original
images and projects are read only. It does not generate manuscript figures.

**Verified:** a recorded simulated intensity analysis replayed in Fiji using
FLASH 4.0.0, ImageJ 1.54p99 and Java 11.0.31. All 120 measurement rows in the two
channel tables matched the original run after excluding the new run identifier.
This verifies the API route, not the identity of the historical paper image runs.

## Original paper provenance still required

The supplied manuscript describes filtered/binarized region area, 3D object
counts and integrated densities, intensity-weighted centroid coincidence, and
StarDist nuclei linked by TrackMate. The recovered historical project files
contain some filter macros and object settings, but not complete modern FLASH
run records/configurations for every final figure. This bundle deliberately
does not invent those settings or substitute today's defaults.

Paper nuclei settings: StarDist "Versatile (fluorescent nuclei)", probability
threshold 0.5, non-maximum suppression 0.4; TrackMate sparse linking with 5-pixel
linking/gap distance and one-slice maximum gap; discard unlinked single-slice
detections. Recover the original models, region masks, filters, object thresholds,
centroid weighting and software versions as part of each producing project.

The separately prepared final `MOAB2_Nature_Code` bundle handles the MOAB-2
staining workflow. Its final unweighted local-mean method supersedes the earlier
correlation-veto trial. It is a separate producer, not an interchangeable FLASH
preset. A full raw-image paper comparison still requires its original image data.

## Freeze a completed recorded project

Supply completed JSON Lines run records in their original execution order,
the original project root and the exact installed FLASH plugin JAR. Python uses
only its standard library for capture and table comparison.

```powershell
cd "C:\path\to\FLASH"
python reproduce/soteras_2026/capture_replay.py --project "C:\paper-data\configured-project" --records "C:\paper-data\configured-project\FLASH\Results\Run Records\runs\first.jsonl" "C:\paper-data\configured-project\FLASH\Results\Run Records\runs\second.jsonl" --flash-jar "C:\Fiji.app\plugins\FLASH-4.0.0.jar" --out "C:\paper-settings\project.local.json"
```

Capture requires cleanly completed runs, processed inputs, a current
`FLASH/Config/.settings/channel_config.json`, and consistent software versions.
It rejects setup and figure stages. Manifests remain local: filenames can contain
animal or participant identifiers. Input images and manifests are not bundled.

This captures the files **as they exist now**. Historical run records alone do
not prove their inputs or configuration have remained unchanged since the paper
run. Establish that identity from the original archive and numerical reference
before claiming exact paper reproduction. Keep the original Fiji dependencies,
StarDist model and any external resources as well as the plugin JAR; the manifest
does not archive the entire Fiji installation.

## Replay using Fiji's script runner

Open `replay_flash.groovy` in Fiji's Script Editor, select Groovy, and run it.
Its three file parameters select the frozen manifest, original source project
and an **existing empty, separate output folder**. The script checks the FLASH
binary, ImageJ and Java versions, validates every declared file hash, and runs
the recorded public API with its saved parameter map. Automatic aggregation and
quality-control reports are disabled unless explicitly represented; pass the
recorded aggregation stage separately when required.

The same script can run from a Fiji installation supporting headless script
execution. The tested route here is Fiji's Groovy runner in the disposable
ImageJ Plugin Test Harness, not a claim that every original segmentation plugin
supports headless execution.

```powershell
cd "C:\path\to\Fiji.app"
.\ImageJ-win64.exe --headless --run "C:\path\to\FLASH\reproduce\soteras_2026\replay_flash.groovy" "manifestFile='C:/paper-settings/project.local.json',sourceProject='C:/paper-data/configured-project',outputProject='C:/paper-rerun/empty-project'"
```

The replay uses an empty output project with auto-overwrite enabled; source
outputs are never reused. Dependencies, available processors/graphics devices
and external paths used by individual presets must match the original workflow.
Only the intensity replay has been exercised end to end with this bundle.

## Compare the original measurements

List the actual relative table paths for the analysis. New run IDs differ by
design; every other column and measurement must match. Rows may be reordered,
but duplicates are retained. Relative tolerance is 1e-8; absolute tolerance is
1e-10. Failures leave a comparison receipt rather than an unqualified success.

```powershell
cd "C:\path\to\FLASH"
python reproduce/soteras_2026/compare_tables.py --original "C:\paper-data\configured-project" --replay "C:\paper-rerun\replayed-project" --tables "FLASH/Results/Tables/Intensity/DAPI.csv" "FLASH/Results/Tables/Intensity/Signal.csv" --out "C:\paper-rerun\comparison.json"
python -m unittest discover -s reproduce/soteras_2026 -p "test_*.py" -v
```

Pass the verified per-animal/region exports to PyFLASH's manuscript analysis
scripts. PyFLASH statistical reruns and FLASH image quantification are distinct
steps; matching a final cached batch does not independently verify segmentation.
