# Custom Models and Click Training - Methodology

FLASH stores selectable segmentation models in a project-scoped model catalog. The catalog can contain bundled stock entries, user-imported StarDist TensorFlow SavedModel `.zip` exports, Cellpose model files or registered Cellpose names, and user-trained Smile Random Forest models. Saved channel configuration uses stable model keys in the segmentation method token so a project can be reopened without relying on a display name.

## Model Catalog

The model catalog is written under the project's FLASH settings area and keeps metadata such as engine type, source type, model key, display name, default parameters, training date, click counts where available, and project-relative model file paths. Stock entries are read-only. User-imported or user-trained entries can be renamed or deleted from the Custom Model Manager.

For reproducibility, keep the catalog with the project outputs. Run snapshots and saved channel configuration record the selected model key. The top-level pipeline recipe selects which analyses to run; it should be paired with the saved configuration and model catalog when replaying a model-dependent analysis.

## Smile RF Object Classifier

For Classical and Enhanced Classical bases, FLASH can train an in-process Random Forest post-filter using Smile (Statistical Machine Intelligence and Learning Engine). The pinned dependency is `com.github.haifengl:smile-core:2.6.0`; Maven Central lists this artifact under the GNU Lesser General Public License, Version 3 (LGPL-3.0): https://central.sonatype.com/artifact/com.github.haifengl/smile-core/2.6.0

The classifier is trained from object-level features extracted from the current segmentation preview, with user clicks providing positive and negative labels. The trained model is serialized as a Smile model file and registered in the catalog as a `smile_rf` entry with its base segmentation token.

## Click-Driven Dataset Packaging

For StarDist and Cellpose, FLASH does not train the deep network inside Fiji. Instead, click annotations are converted into training folders suitable for external tools. StarDist packaging writes image and label data for external training routes such as ZeroCostDL4Mic or a compatible StarDist Python workflow. Cellpose packaging writes images, masks, and a command template for Cellpose 3 GUI or CLI training.

After external training, the resulting StarDist `.zip` or Cellpose model file/name is imported through the model catalog. Cellpose 4, Cellpose-SAM, and `cpsam` models are outside the pinned Cellpose 3 runtime and should not be described as supported unless the runtime is upgraded.

## Reproducibility Notes

Record the model key, model source, catalog file, training click counts, external training tool version, and any parameter defaults used for each channel. For StarDist, state that FLASH applies 2D StarDist slice-by-slice and links detections across Z, rather than running native 3D StarDist. For Cellpose, state the Cellpose 3 model name or file and whether GPU execution was used.

When sharing or replaying an analysis, include the project catalog and copied model files together with the saved channel configuration, run snapshot, and replay command. If a model key is missing at replay time, restore the catalog entry rather than substituting a different model silently.
