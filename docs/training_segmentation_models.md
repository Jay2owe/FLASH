# Training Segmentation Models

Use a stock model first when it finds the right object type with only small threshold, size, or filter changes. Train a custom model when the stock model repeatedly misses the same biology, includes the same artefacts, or cannot separate crowded objects after normal parameter tuning.

Custom training is worth the extra work when the object shape, staining pattern, species, tissue, imaging scale, or background looks different from the examples used by the stock model. For small numeric problems, try click-to-suggest filters before training.

## Where Custom Models Are Imported

After training, import models through the Custom Model Manager:

1. Open Set Up Configuration quality control.
2. Choose a StarDist or Cellpose segmentation method for the channel.
3. Open `Manage models...`.
4. Use `Add StarDist...` for a Fiji-compatible StarDist `.zip`, or `Add Cellpose...` for a Cellpose model file or registered Cellpose model name.

FLASH stores project model entries in `FLASH/Configuration/Segmentation Models/catalog.json` and copied files under `FLASH/Configuration/Segmentation Models/files/<modelKey>/...`.

## StarDist Workflow

StarDist is best for compact, roughly round or star-convex objects such as nuclei.

1. In Set Up Configuration quality control, click good and bad objects on representative images.
2. Open the Train Custom Engine wizard and choose the StarDist training route.
3. Export the dataset package from the wizard.
4. Train in Python with `stardist.models.StarDist2D`, ZeroCostDL4Mic, or another Fiji-compatible StarDist training workflow.
5. Export the trained model with `model.export_TF()` to create a TensorFlow SavedModel `.zip`.
6. Import that `.zip` through Custom Model Manager -> `Add StarDist...`.
7. Preview the imported model on several representative images before batch analysis.

Use a tested StarDist export environment if possible. Newer TensorFlow/Keras combinations can make Fiji-compatible export harder; Python 3.9, TensorFlow 2.10, and StarDist 0.8.x are a safer reference environment.

FLASH runs StarDist per slice as 2D detections, then links detections through Z. Full volumetric 3D StarDist is not built in.

## Cellpose Workflow

Cellpose is best for cells or cell-like objects with flexible shapes.

1. In Set Up Configuration quality control, click good and bad objects on representative images.
2. Open the Train Custom Engine wizard and choose the Cellpose training route.
3. Export the dataset package from the wizard.
4. Train with the Cellpose 3 GUI or command line. The exported data should follow Cellpose naming conventions such as `<image>.tif` with `<image>_masks.tif` or `<image>_seg.npy`.
5. For command-line training, start from a command like:

```text
python -m cellpose --train --dir <train_folder> --test_dir <validation_folder> --pretrained_model cyto3 --chan 0 --chan2 0
```

6. Cellpose writes trained models under `<train_folder>/models/`.
7. Import the trained model file through Custom Model Manager -> `Add Cellpose...`, or register it with `python -m cellpose --add_model <path>` and add the registered name through `Add Cellpose...` -> `Registered name`.
8. Preview the imported model on several representative images before batch analysis.

FLASH pins Cellpose `3.1.1.2`. Cellpose 4, Cellpose-SAM, and `cpsam` models are not supported.

## Trained RF Workflow

Trained RF (random forest) models are trained inside FLASH. Use this when the current classical, enhanced classical, StarDist, or Cellpose result is close, but repeated false positives can be learned from clicked examples.

1. Run the normal preview for the channel.
2. Click bad objects and, when useful, click good objects as positive examples.
3. Open the Train Custom Engine wizard.
4. Choose a Classical RF, Enhanced Classical RF, StarDist RF post-filter, or Cellpose RF post-filter route.
5. Train inside the wizard and save the resulting project model.

No Python training step is needed for trained RF models.

## How Much Training Data Is Enough

For trained RF, start with at least 20 to 30 bad-object clicks and 20 to 30 good-object clicks across 3 to 5 representative images. Add more clicks when the objects vary by section, condition, staining intensity, or image quality.

For StarDist or Cellpose, a small pilot can use 5 to 10 carefully labelled images if the signal is very consistent. A stronger model usually needs 10 to 30 images and hundreds to thousands of labelled objects. Hold back about 20% of labelled images for validation, meaning images used to check whether the model generalizes instead of memorizing the training set.

Add examples of failures, not only clean successes. Include dim objects, bright objects, crowded regions, background artefacts, edge objects, and images from every imaging condition you expect to batch process.

## Quality Checks And Common Pitfalls

Open labels in Fiji, the Cellpose GUI, or another viewer before training. Check that each object is one labelled instance, labels do not overlap, holes are not accidental, and masks line up with the image pixels.

Common failure modes:

- Too few examples: the preview image looks good, but batch images fail.
- Biased examples: only clean images were labelled, so noisy images are not handled.
- Bad masks: merged objects, missing edges, holes, and overlapping instances teach the wrong target.
- Wrong import route: StarDist needs a Fiji-compatible `.zip`; Cellpose needs a Cellpose 3 model file or registered name.
- Wrong runtime version: Cellpose 4 / `cpsam` outputs are not supported by FLASH's pinned Cellpose `3.1.1.2` runtime.
- Wrong dimensional expectation: FLASH StarDist is per-slice 2D with Z-linking, not full 3D StarDist.
