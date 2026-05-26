package flash.pipeline.help;

import flash.pipeline.FLASH_Pipeline;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Central registry for focused help topics shown beside visible analyses.
 */
public final class AnalysisHelpCatalog {

    public static final AnalysisHelpTopic TRAIN_CUSTOM_SEGMENTATION_MODELS =
            trainCustomSegmentationModelsTopic();
    public static final AnalysisHelpTopic ENHANCED_CLASSICAL_SEGMENTATION =
            enhancedClassicalSegmentationTopic();
    public static final AnalysisHelpTopic CUSTOM_MODEL_MANAGER =
            customModelManagerTopic();

    private static final Map<Integer, AnalysisHelpTopic> TOPICS = buildTopics();
    private static final Map<String, AnalysisHelpTopic> AUXILIARY_TOPICS = buildAuxiliaryTopics();

    private AnalysisHelpCatalog() {
    }

    public static AnalysisHelpTopic forAnalysis(int analysisIndex) {
        return TOPICS.get(Integer.valueOf(analysisIndex));
    }

    public static boolean hasTopic(int analysisIndex) {
        return TOPICS.containsKey(Integer.valueOf(analysisIndex));
    }

    public static AnalysisHelpTopic forKey(String key) {
        if (key == null) return null;
        for (AnalysisHelpTopic topic : TOPICS.values()) {
            if (key.equals(topic.key)) return topic;
        }
        return AUXILIARY_TOPICS.get(key);
    }

    public static Map<Integer, AnalysisHelpTopic> all() {
        return TOPICS;
    }

    public static Map<String, AnalysisHelpTopic> auxiliaryTopics() {
        return AUXILIARY_TOPICS;
    }

    private static Map<Integer, AnalysisHelpTopic> buildTopics() {
        Map<Integer, AnalysisHelpTopic> topics = new LinkedHashMap<Integer, AnalysisHelpTopic>();
        put(topics, setupConfigurationTopic());
        put(topics, drawAndSaveRoisTopic());
        put(topics, deconvolutionTopic());
        put(topics, spectralDecontaminationTopic());
        put(topics, splitAndMergeTopic());
        put(topics, intensityTopic());
        put(topics, threeDObjectTopic());
        put(topics, spatialTopic());
        put(topics, aggregationTopic());
        put(topics, statisticsTopic());
        put(topics, excelExportTopic());
        return Collections.unmodifiableMap(topics);
    }

    private static Map<String, AnalysisHelpTopic> buildAuxiliaryTopics() {
        Map<String, AnalysisHelpTopic> topics = new LinkedHashMap<String, AnalysisHelpTopic>();
        putAuxiliary(topics, TRAIN_CUSTOM_SEGMENTATION_MODELS);
        putAuxiliary(topics, ENHANCED_CLASSICAL_SEGMENTATION);
        putAuxiliary(topics, CUSTOM_MODEL_MANAGER);
        return Collections.unmodifiableMap(topics);
    }

    private static void put(Map<Integer, AnalysisHelpTopic> topics, AnalysisHelpTopic topic) {
        topics.put(Integer.valueOf(topic.analysisIndex), topic);
    }

    private static void putAuxiliary(Map<String, AnalysisHelpTopic> topics, AnalysisHelpTopic topic) {
        if (topics.put(topic.key, topic) != null) {
            throw new IllegalStateException("Duplicate analysis helper key: " + topic.key);
        }
    }

    private static AnalysisHelpTopic trainCustomSegmentationModelsTopic() {
        return new AnalysisHelpTopic(
                FLASH_Pipeline.IDX_CREATE_BIN,
                "train-custom-segmentation-models",
                "Train custom segmentation models",
                "Use project-registered custom StarDist, Cellpose, or trained RF models when stock segmentation settings cannot represent the objects reliably.",
                list(
                        "Use this when stock Classical, Enhanced Classical, StarDist, or Cellpose settings repeatedly miss the same object class or include the same artefact.",
                        "The Train Custom Engine launcher is hidden by default while its click-collection flow is redesigned.",
                        "For full written steps, see docs/training_segmentation_models.md in the FLASH source tree."),
                list(
                        "Representative images opened in Set Up Configuration quality control.",
                        "Instance label images or external training data for StarDist and Cellpose models.",
                        "For StarDist or Cellpose, a model trained outside FLASH that can be imported through the Custom Model Manager."),
                list(
                        "Import finished StarDist and Cellpose models through the Custom Model Manager while the training launcher is hidden.",
                        "Developers can re-enable the hidden launcher with the flash.trainCustomEngine.ui.enabled system property while testing the redesigned flow.",
                        "Cellpose 4, Cellpose-SAM, and cpsam models are not supported by the pinned Cellpose 3.1.1.2 runtime.",
                        "FLASH runs StarDist per slice as 2D detections with Z-linking; full 3D StarDist is not built in."),
                list(
                        "Run the wizard training step; Classical and Enhanced Classical train in FLASH, while StarDist and Cellpose export datasets for Python training.",
                        "For StarDist, train externally and export a Fiji-compatible TensorFlow SavedModel zip with model.export_TF().",
                        "For Cellpose, train with Cellpose 3 and import the trained file or registered model name.",
                        "Open the Custom Model Manager from the model row, then use Add StarDist... or Add Cellpose... to save the new catalog entry.",
                        "Apply the recommended segmentation token to the channel or keep the previous method."),
                list(
                        "A project model catalog entry under Configuration/Segmentation Models/ in the selected project folder.",
                        "For Smile RF, a serialized Random Forest model and metadata with click counts and base engine.",
                        "For StarDist and Cellpose, a packaged training dataset plus the imported model reference after external training.",
                        "A stable segmentation token such as trained_rf:<modelKey>:base=<base> or model=<modelKey> on a deep-learning token."),
                list(
                        "Training on too few examples makes the model look precise on the preview but fail across the batch; start RF with roughly 20 to 30 positive and 20 to 30 negative clicks across several images.",
                        "Deep StarDist and Cellpose networks are not trained inside FLASH; FLASH packages data and registers the resulting model.",
                        "A model entry is project scoped. Copy the project catalog with the project if another machine needs to replay it.",
                        "Do not use Cellpose 4 / Cellpose-SAM outputs with this runtime until FLASH explicitly upgrades that backend.",
                        "Do not expect StarDist to use full volumetric context; FLASH links 2D per-slice detections through Z."),
                Collections.<AnalysisHelpTopic.HelpImage>emptyList());
    }

    private static AnalysisHelpTopic enhancedClassicalSegmentationTopic() {
        return new AnalysisHelpTopic(
                FLASH_Pipeline.IDX_CREATE_BIN,
                "enhanced-classical-segmentation-method",
                "Enhanced Classical segmentation method",
                "Enhanced Classical starts with the normal threshold and voxel-size object detection, then filters candidate objects by measured morphology during segmentation.",
                list(
                        "Use this for threshold-friendly signal where debris, merged cells, or elongated artefacts still survive the basic size filter.",
                        "Use it when the old filter-macro workflow needed several ImageJ commands before 3D Objects Counter to remove the same false positives.",
                        "Skip it when the target needs instance separation from StarDist, Cellpose, or a trained model rather than post-detection object filtering."),
                list(
                        "A filtered channel preview, the raw source image, a signal threshold, and minimum and maximum voxel sizes.",
                        "Optional morphology predicates defined in the Filter by morphology section.",
                        "Object statistics produced during the preview run."),
                list(
                        "Supported features are volume, surface_area, sphericity, elongation, compactness, mean_intensity, max_intensity, and feret_diameter_max.",
                        "Supported operators are >=, <=, >, and <.",
                        "Each enabled row is combined with AND logic, so an object must pass every enabled predicate."),
                list(
                        "Run the normal object preview to create candidate objects.",
                        "Enable morphology rows, choose a feature, operator, and value, then rerun the object preview.",
                        "FLASH applies the morphology predicates inside the segmentation run before downstream counts and measurements are saved.",
                        "Lock the stage to write a canonical enhanced_classical token with encoded morph predicates."),
                list(
                        "The saved segmentation method token records thresh, minSize, maxSize, and an encoded morph predicate list.",
                        "Downstream object tables contain only objects that passed both the threshold/size gates and the morphology gates.",
                        "The predicate summary shows how many preview objects survive each enabled filter."),
                list(
                        "Morphology filters are not a later cleanup step; changing them changes the segmented object set itself.",
                        "Very strict filters can remove true objects that are dim, clipped, or partly outside the image.",
                        "Predicates depend on the candidate labels produced by thresholding, so a bad threshold still gives bad morphology.",
                        "Use the old three-command macro chain only when image preprocessing is the real problem; use Enhanced Classical when object-level filtering is the problem."),
                Collections.<AnalysisHelpTopic.HelpImage>emptyList());
    }

    private static AnalysisHelpTopic customModelManagerTopic() {
        return new AnalysisHelpTopic(
                FLASH_Pipeline.IDX_CREATE_BIN,
                "custom-model-manager",
                "Custom Model Manager",
                "The Custom Model Manager lists stock and project-specific segmentation models and keeps stable model keys for reproducible saved methods.",
                list(
                        "Use this to import a Fiji-compatible StarDist model zip, add a Cellpose model file or registered name, inspect trained Smile RF entries, or clean up project models.",
                        "Use it when a saved channel method references a missing model key and you need to restore or replace the project catalog entry.",
                        "Skip it for bundled stock models unless you only need to inspect which model is selected."),
                list(
                        "The current project directory, because the catalog is project scoped.",
                        "For StarDist, a TensorFlow SavedModel zip that the Fiji StarDist stack can load.",
                        "For Cellpose, a model file copied into the project or a registered Cellpose model name available to the Cellpose 3 runtime."),
                list(
                        "Stock entries are read-only and come from FLASH resources or supported built-in Cellpose names.",
                        "User-imported and user-trained entries can be renamed or deleted when the project owns them.",
                        "Model keys are the stable identifiers saved in segmentation tokens; display names are for users."),
                list(
                        "Open the manager from a StarDist or Cellpose parameter stage, or after a training wizard run.",
                        "Filter by engine or source, select an entry, and review its key, source, file/resource path, defaults, and metadata.",
                        "Add or edit user entries, then return to the parameter stage and refresh the model list."),
                list(
                        "Catalog JSON and copied files under Configuration/Segmentation Models/ in the selected project folder.",
                        "Project-relative file paths for portable replay and sharing.",
                        "Stable model keys used by StarDist, Cellpose, and trained_rf segmentation method tokens."),
                list(
                        "Do not edit the same project catalog from two Fiji sessions at once; the most recent save wins.",
                        "A project-relative path is required for portable replay. Avoid depending on machine-local absolute paths.",
                        "Deleting a user model can make older bins or snapshots report a missing model key.",
                        "The manager registers models; it does not validate every biological use case of a model."),
                Collections.<AnalysisHelpTopic.HelpImage>emptyList());
    }

    private static AnalysisHelpTopic setupConfigurationTopic() {
        return new AnalysisHelpTopic(
                FLASH_Pipeline.IDX_CREATE_BIN,
                "set-up-configuration",
                "Set Up Configuration",
                "Define what each image channel contains and save the thresholds, filters, segmentation choices, and Z-slice range that other analyses reuse.",
                list(
                        "Use this first for a new experiment or after changing staining, imaging settings, channel order, or analysis thresholds.",
                        "Use it again when downstream results suggest the saved channel names, object thresholds, particle-size filters, display ranges, or intensity thresholds are wrong.",
                        "Skip it only when the selected project already has a valid FLASH configuration for the same images."),
                list(
                        "The selected project folder with the images you plan to analyse.",
                        "The marker or stain in each channel, such as DAPI, IBA1, GFAP, NeuN, amyloid, or an autofluorescence channel.",
                        "Reasonable object thresholds, particle-size filters, display ranges, intensity thresholds, segmentation method choices, and any planned Z-slice subset."),
                list(
                        "Choose the image folder, inspect the detected channel count, and assign a clear identity to every channel.",
                        "Pick threshold and filter values for objects you want to count, and choose intensity thresholds for signal measurements.",
                        "Confirm whether the full Z-stack or a saved Z-slice range should be used before saving the configuration."),
                list(
                        "FLASH writes the configuration to FLASH/Set Up Configuration/.settings/Channel_Data.txt.",
                        "Per-channel filter macros are saved as FLASH/Set Up Configuration/.settings/C1_Filters.ijm, C2_Filters.ijm, and so on.",
                        "Channel identities are saved in FLASH/Set Up Configuration/.settings/channel_identities.json, and Z-slice choices are saved in FLASH/Set Up Configuration/.settings/ZSlice_Selections.csv when used.",
                        "Later analyses read these files automatically; they do not ask for the same channel setup again."),
                list(
                        "FLASH/Set Up Configuration/.settings/Channel_Data.txt with channel names, lookup-table colours, thresholds, particle-size filters, display ranges, intensity thresholds, filter presets, segmentation methods, and Z-slice mode.",
                        "FLASH/Set Up Configuration/.settings/channel_identities.json when marker identities were recorded.",
                        "FLASH/Set Up Configuration/.settings/C1_Filters.ijm and matching filter macro files for channels that need object filtering.",
                        "FLASH/Set Up Configuration/.settings/ZSlice_Selections.csv when analysis is limited to selected Z slices."),
                list(
                        "A wrong channel identity can make every later output hard to interpret, even if the analysis runs successfully.",
                        "Thresholds copied from a different experiment can miss dim signal or include background; check representative images before batch runs.",
                        "Forgetting a Z-slice choice can make results differ between runs if one run used the full stack and another used only a slab.",
                        "Changing configuration after ROIs or objects were produced can make old outputs inconsistent with new measurements."),
                optionalImages(
                        "set-up-configuration",
                        image("setup.png", "Setup dialog", "Channel names, thresholds, filters, segmentation methods, and Z-slice choices are reviewed before saving."),
                        image("workflow.png", "Configuration workflow", "Choose folder, inspect channels, assign identities, tune filters, save configuration, then run downstream analyses."),
                        image("example-output.png", "Saved configuration files", "The active setup lives in FLASH/Set Up Configuration so it can be reused by the rest of the pipeline.")));
    }

    private static AnalysisHelpTopic drawAndSaveRoisTopic() {
        return new AnalysisHelpTopic(
                FLASH_Pipeline.IDX_DRAW_ROIS,
                "draw-save-rois",
                "Draw and Save ROIs",
                "Create the regions of interest that limit later measurements, with always-open rotate/flip controls that save each image transform for reuse.",
                list(
                        "Use this after Set Up Configuration and before intensity, object, spatial, or aggregation steps that need region boundaries.",
                        "Use it when each image needs manually drawn anatomical regions or orientation correction before downstream measurement.",
                        "Use append mode only when you are adding missing regions to an existing ROI set; images that already have saved ROIs are not revisited."),
                list(
                        "A saved channel configuration so FLASH knows which channel is best for display.",
                        "Images opened from the selected project folder.",
                        "Region names that will be used consistently across animals, conditions, and images.",
                        "Any needed rotate or flip corrections; the ROI drawing panel saves them with Image Orientation.csv."),
                list(
                        "Open a representative image and pick the display channel that makes the region boundary easiest to see.",
                        "When each ROI image opens, the orientation buttons are available beside the image.",
                        "Use the always-available orientation panel only if the image needs rotating or flipping; if you do nothing, FLASH keeps the current orientation.",
                        "Draw each anatomical region carefully and name it consistently.",
                        "Save the ROI set for the image, then repeat or append until all required images have ROI files."),
                list(
                        "Apply any saved image transform before the image is shown for drawing.",
                        "FLASH stores the main ROI zip files in FLASH/Results/Analysis Images/ROIs/.",
                        "FLASH stores ROI properties in FLASH/Results/Tables/ROIs/ for later volume and aggregation steps.",
                        "After each image, FLASH writes or updates FLASH/Results/Tables/ROIs/Image Orientation.csv with the confirmed orientation transform.",
                        "Later analyses load these ROI sets and saved transforms automatically."),
                list(
                        "FLASH/Results/Analysis Images/ROIs/<name> ROIs.zip contains the drawn regions for an image or region set.",
                        "FLASH/Results/Tables/ROIs/<name> ROI Properties.csv records ROI measurements used by aggregation and normalization.",
                        "FLASH/Results/Analysis Images/ROIs/<image>_Cropped.PNG stores cropped ROI previews.",
                        "FLASH/Results/Tables/ROIs/Image Orientation.csv records saved rotate and flip transforms confirmed during ROI drawing."),
                list(
                        "Inconsistent ROI names create separate groups during aggregation, even if they refer to the same anatomical region.",
                        "Changing orientation after drawing an unsaved ROI clears or invalidates that ROI so coordinates still match the displayed image.",
                        "Missing ROI files cause later measurements to skip images or fail preflight checks.",
                        "Old ROI files drawn with the wrong orientation should be redrawn or verified rather than relying on a later transform alone.",
                        "Very loose ROIs include background; very tight ROIs can cut off real signal at the boundary."),
                optionalImages(
                        "draw-save-rois",
                        image("setup.png", "ROI drawing setup", "Choose the display channel, review orientation controls, and decide whether to create new ROI sets or append to existing ones."),
                        image("workflow.png", "ROI workflow", "Open image, apply saved or adjusted orientation, draw named regions, save ROI set, then reuse those regions for downstream measurements."),
                        image("example-output.png", "ROI output files", "ROI zip files, ROI Properties CSV files, and saved image transforms are written for downstream analyses.")));
    }

    private static AnalysisHelpTopic deconvolutionTopic() {
        return new AnalysisHelpTopic(
                FLASH_Pipeline.IDX_DECONVOLUTION,
                "deconvolution",
                "3D Deconvolution",
                "Sharpen 3D Z-stacks with a point spread function before segmentation or intensity measurement.",
                list(
                        "Use this when optical blur makes nearby objects merge, hides puncta, or makes 3D boundaries hard to segment.",
                        "Use it only for real Z-stacks with suitable microscope metadata and enough memory for the selected engine.",
                        "Skip it for already deconvolved images, poor metadata, or when preview shows ringing or amplified noise."),
                list(
                        "Raw 3D image stacks from the selected project folder.",
                        "Microscope metadata such as pixel size, Z step, objective information, emission wavelength, and refractive index when available.",
                        "A selected deconvolution engine, point spread function settings, iteration count, regularization choice, and optional use of cached outputs."),
                list(
                        "Choose the images and channels to correct, then confirm the engine and point spread function settings.",
                        "Preview representative raw and deconvolved output before committing to the batch.",
                        "Run the batch and let downstream analyses use deconvolved stacks only when their own opt-in setting is enabled."),
                list(
                        "FLASH writes corrected stacks to FLASH/3D Deconvolution/.",
                        "Per-parameter cache files are stored under FLASH/Cache/3D Deconvolution/ so repeated runs can skip matching work.",
                        "Downstream modules can prefer deconvolved inputs when that option is enabled, with fallback to raw inputs when no matching corrected stack exists."),
                list(
                        "FLASH/3D Deconvolution/<image>_C<channel>.tif contains per-channel corrected stacks.",
                        "FLASH/3D Deconvolution/<image>_deconv.tif contains a merged corrected stack when produced.",
                        "FLASH/3D Deconvolution/<image>_deconv_details.txt records run details for the corrected image.",
                        "FLASH/Cache/3D Deconvolution/<paramsHash>/ stores reusable intermediate outputs for matching settings."),
                list(
                        "Over-sharpening can create halos or ringing that look like real biological structure.",
                        "Wrong voxel size, wavelength, or refractive-index settings can make the point spread function wrong.",
                        "Deconvolution changes image appearance; treat it as blur correction, not proof that signal increased.",
                        "Large Z-stacks can exceed memory limits, so preview and batch size matter."),
                optionalImages(
                        "deconvolution",
                        image("setup.png", "Deconvolution setup", "Engine, point spread function, iteration, and cache settings are chosen before running the batch."),
                        image("workflow.png", "Deconvolution workflow", "Select stacks, configure blur correction, preview output, write corrected images, then opt in downstream."),
                        image("example-output.png", "Deconvolution outputs", "Corrected stacks and run details are saved under FLASH/3D Deconvolution.")));
    }

    private static AnalysisHelpTopic spectralDecontaminationTopic() {
        return new AnalysisHelpTopic(
                FLASH_Pipeline.IDX_SPECTRAL_DECONTAMINATION,
                "spectral-decontamination",
                "Spectral Decontamination (Experimental)",
                "Configure an experimental correction step that reduces channel bleed-through or autofluorescence before measuring target signal.",
                list(
                        "Use this only when preview images or controls show that one channel contaminates another, or when autofluorescence is a clear source of false signal.",
                        "Use it with suitable controls whenever possible, and inspect previews before batch runs.",
                        "Skip it when channels are clean, controls are missing for a hard correction decision, or the experimental method is not appropriate for the dataset."),
                list(
                        "A valid channel configuration from Set Up Configuration.",
                        "Target, bleed-through, autofluorescence, and excluded channel roles.",
                        "Condition assignments, control condition names when used, and a selected correction feature stack.",
                        "Representative preview images to check whether correction keeps real target signal while reducing false signal."),
                list(
                        "Choose the target channel and any contaminating or autofluorescence channels.",
                        "Select a correction goal and feature stack, such as linear unmixing, quiet-channel gating, local autofluorescence correction, or object scoring.",
                        "Preview the correction, inspect coefficients or thresholds, then save the settings before running the batch."),
                list(
                        "FLASH saves dataset-level settings to FLASH/Set Up Configuration/.settings/Spectral_Decontamination_Config.json.",
                        "Batch summaries and coefficients are written to FLASH/Spectral Decontamination/.",
                        "Corrected images, masks, cleaned object maps, and parameter maps are written under FLASH/Spectral Decontamination/Image Outputs/."),
                list(
                        "FLASH/Set Up Configuration/.settings/Spectral_Decontamination_Config.json records channel roles, correction goal, conditions, and selected feature stack.",
                        "FLASH/Spectral Decontamination/per_image_summary.csv summarizes each processed image.",
                        "FLASH/Spectral Decontamination/correction_coefficients.csv records fitted correction values when the chosen stack produces them.",
                        "FLASH/Spectral Decontamination/preview_selection.csv records the preview subset when saved.",
                        "FLASH/Spectral Decontamination/Image Outputs/Series 001 - <name>/corrected_<target>.tif and related files store corrected images, masks, object scores, or parameter maps depending on the chosen features."),
                list(
                        "Over-correction can remove real target signal, especially when controls do not match the experimental tissue.",
                        "Saturated pixels are uncertain; correction cannot recover true signal hidden by clipping.",
                        "Autofluorescence and bleed-through can vary by region, condition, or imaging session, so one coefficient may not fit every image.",
                        "This module is experimental; treat outputs as correction-assisted evidence and keep the saved settings and summaries with the results."),
                optionalImages(
                        "spectral-decontamination",
                        image("setup.png", "Spectral setup", "Target, bleed-through, autofluorescence, controls, and correction stack are selected before correction."),
                        image("workflow.png", "Spectral workflow", "Choose channel roles, estimate contamination, preview correction, save settings, then write corrected outputs."),
                        image("example-output.png", "Spectral outputs", "Configuration, summaries, coefficients, previews, and corrected images are saved in the FLASH output folders.")));
    }

    private static AnalysisHelpTopic splitAndMergeTopic() {
        return new AnalysisHelpTopic(
                FLASH_Pipeline.IDX_SPLIT_MERGE,
                "split-merge",
                "Make Presentation Images",
                "Create per-channel images, merged composites, and display-ready exports for visual checking and figure preparation.",
                list(
                        "Use this when you need channel-separated images, merged colour composites, or OME-TIFF exports for review, presentation, or figure assembly.",
                        "Use it after Set Up Configuration so saved channel names, colours, display ranges, and optional autofluorescence-channel hints can be applied.",
                        "Skip it when you only need quantitative CSV measurements; intensity and object measurements do not depend on display-enhanced image exports."),
                list(
                        "A saved configuration from Set Up Configuration with channel names, lookup-table colours, and display ranges.",
                        "The selected project folder containing the images or stacks to export.",
                        "Optional merge settings, processing method choices, OME-TIFF preference, and background-subtraction source channel when autofluorescence should be removed from display outputs."),
                list(
                        "Choose how each channel should be displayed, such as automatic contrast, saved min-max display range, or raw-looking output.",
                        "Choose whether merged composites and OME-TIFF exports should be produced.",
                        "If background subtraction is enabled, confirm the source channel and the target channels it should be subtracted from."),
                list(
                        "FLASH reads the configured channel names, colours, display ranges, and optional autofluorescence identity.",
                        "Each image is split into channel-specific views and the selected display settings are applied to those exported images.",
                        "Selected channels are combined into merged colour composites, with additional merge specifications used when provided.",
                        "FLASH saves the display outputs under Results/Presentation Images/ and records the run settings under Results/Run Records/."),
                list(
                        "FLASH/Results/Presentation Images/Images/<animal>/ contains split-channel images and merged composite images.",
                        "FLASH/Results/Presentation Images/OME-TIFF/ contains OME-TIFF exports when that option is enabled.",
                        "FLASH/Results/Presentation Images/Annotated Images/ and FLASH/Results/Presentation Images/Tiles/ hold annotated copies and overview tiles when tile options are enabled.",
                        "Saved min-max display settings can also update the active configuration so later display exports use the same ranges."),
                list(
                        "Contrast stretching, lookup-table colours, and merged composites are display choices, not quantitative fluorescence measurements.",
                        "Do not use display-enhanced PNGs as input for intensity or object quantification; run the quantitative modules on the configured project images.",
                        "Wrong channel names, colours, or min-max ranges can make figures misleading even when the underlying measurements are unchanged.",
                        "Background subtraction can help visual review, but the source channel and target-channel choices should be checked on representative images."),
                optionalImages(
                        "split-merge",
                        image("setup.png", "Split and merge setup", "Per-channel processing, merge, OME-TIFF, and background-subtraction options are reviewed before export."),
                        image("workflow.png", "Split and merge workflow", "Read configured channels, split images, apply display settings, create composites, then save display outputs."),
                        image("example-output.png", "Split and merge outputs", "Split-channel images, merged composites, OME-TIFF files, and annotated copies live under FLASH/Results/Presentation Images.")));
    }

    private static AnalysisHelpTopic intensityTopic() {
        return new AnalysisHelpTopic(
                FLASH_Pipeline.IDX_INTENSITY,
                "intensity",
                "Fluorescence Intensity Analysis",
                "Measure fluorescence inside saved ROIs, with optional thresholding, channel-mask restriction, and pixel-based spatial analysis.",
                list(
                        "Use this when the question is how bright a marker is inside each saved region of interest, or how the signal is spatially distributed within those regions.",
                        "Use it after Set Up Configuration and Draw and Save ROIs, because the analysis needs configured channels and ROI zip files.",
                        "Use thresholded measurement when you want bright positive signal only, and whole-ROI measurement when background and dim signal should remain part of the mean."),
                list(
                        "A saved channel configuration from Set Up Configuration, including channel names and intensity thresholds when thresholded measurement is used.",
                        "Saved ROI zip files from Draw and Save ROIs; intensity measurement is region-based and expects ROI sets.",
                        "The measurement channel choices, optional per-channel binarisation thresholds, and optional Channel ROI Mask if measurement should be restricted to another channel's positive area."),
                list(
                        "Choose the channels whose fluorescence should be measured.",
                        "For each measured channel, decide whether to measure the whole ROI or only pixels above the configured threshold.",
                        "Choose which ROI sets should be measured, and optionally choose a channel mask that restricts measurement to positive pixels in a second channel."),
                list(
                        "FLASH loads the project images, saved ROIs, configured channel names, and intensity thresholds.",
                        "For each selected ROI set, the chosen measurement channel is measured inside each ROI.",
                        "When binarisation is enabled, thresholded signal summaries such as positive area and thresholded intensity are written alongside the measurement.",
                        "When Channel ROI Mask is selected, the ROI measurement is also limited to pixels that are positive in the mask channel.",
                        "FLASH saves per-channel intensity CSV files and run details under the Fluorescence Intensity analysis folder."),
                list(
                        "FLASH/Image Analysis/Image Intensities/<channel>.csv contains per-image and per-ROI intensity measurements for each measured channel.",
                        "FLASH/Image Analysis/Image Intensities/Analysis Details/ records the intensity settings used for the run.",
                        "Columns can include mean intensity, positive area, area fraction, and thresholded summaries depending on the selected options."),
                list(
                        "Display-adjusted split/merge images are not the measurement source; avoid treating figure contrast as quantitative signal.",
                        "ROI zip files must match the images being measured, or values can be assigned to the wrong region or image.",
                        "Thresholds copied from a different staining batch can turn real signal into background or include noise as positive area.",
                        "Channel ROI Mask answers a narrower question than normal ROI measurement, so check that the masking channel represents the biology you intend."),
                optionalImages(
                        "intensity",
                        image("setup.png", "Intensity setup", "Measurement channels, binarisation thresholds, ROI sets, and optional channel mask are selected before analysis."),
                        image("workflow.png", "Intensity workflow", "Load images and ROIs, choose measurement and mask channels, apply thresholds when selected, then save ROI-level CSV tables."),
                        image("example-output.png", "Intensity output table", "Per-channel CSV files in FLASH/Image Analysis/Image Intensities contain ROI-level measurements and thresholded summaries.")));
    }

    private static AnalysisHelpTopic threeDObjectTopic() {
        return new AnalysisHelpTopic(
                FLASH_Pipeline.IDX_3D_OBJECT,
                "three-d-object",
                "3D Object Analysis",
                "Segment 3D objects and measure object counts, size, shape, intensity, colocalisation, and optional process length.",
                list(
                        "Use this when the question is how many 3D objects are present, how large they are, or whether segmented objects overlap between channels.",
                        "Use it after Set Up Configuration and Draw and Save ROIs so thresholds, filters, segmentation methods, size limits, and region boundaries are available.",
                        "Use optional process-length extraction only for channels where skeleton-like branches are biologically meaningful."),
                list(
                        "A saved channel configuration from Set Up Configuration with object thresholds, particle-size filters, filter macros, and segmentation method choices.",
                        "Saved ROI sets from Draw and Save ROIs when counts and object tables should be restricted to tissue regions.",
                        "Correct channel identities for object channels, nuclear marker selection when process length is enabled, and colocalisation threshold choices when overlap metrics are used."),
                list(
                        "Confirm which object channels should be segmented and whether classical thresholding or StarDist-based nuclear segmentation is configured for each channel.",
                        "Confirm minimum and maximum particle-size filters so noise and oversized artefacts are excluded without removing real objects.",
                        "Choose whether to calculate volumetric overlap, centroid coincidence, centroid ROI filtering, process length, or linked spatial analysis options."),
                list(
                        "FLASH loads the configured channels, applies per-channel filters, and segments 3D objects using the selected segmentation method.",
                        "Objects are filtered by ROI and size settings, then labelled for each channel.",
                        "For each object, FLASH records morphometric measurements, redirected intensity measurements, centroid coordinates, and per-channel counts.",
                        "When enabled, pairwise colocalisation and centroid coincidence are calculated between channel pairs.",
                        "When enabled, process images are skeletonised and measured after subtracting the selected nuclear marker signal.",
                        "FLASH saves object CSV tables, calibration data, analysis details, and any produced masks or object maps under the 3D Object Analysis folder."),
                list(
                        "FLASH/Image Analysis/3D Objects/Objects/<channel>.csv contains object-level tables for each segmented channel.",
                        "FLASH/Image Analysis/3D Objects/Objects/calibration.properties records calibration used with object CSV outputs.",
                        "FLASH/Image Analysis/3D Objects/Objects/Analysis Details/ records object-analysis settings used for the run.",
                        "FLASH/Image Analysis/3D Objects/Image Outputs/<animal>/ contains masked images and object-label maps when those image outputs are produced."),
                list(
                        "Choosing the wrong object channel can produce plausible-looking counts for the wrong marker.",
                        "Size filters that are too strict can remove real cells, puncta, or processes; filters that are too loose can keep debris and merged objects.",
                        "Colocalisation percentages depend on segmentation quality, so inspect masks or object maps before interpreting overlap biologically.",
                        "Touching objects can be merged by classical threshold segmentation unless preprocessing or the selected segmentation method separates them.",
                        "Process-length output is meaningful only when the selected channel really contains branch-like signal and the nuclear marker subtraction is appropriate."),
                optionalImages(
                        "three-d-object",
                        image("setup.png", "3D object setup", "Segmentation, colocalisation, ROI filtering, and process-length options are selected before running object analysis."),
                        image("workflow.png", "3D object workflow", "Load configured channels, threshold or segment in 3D, filter objects, calculate measurements, then save object tables."),
                        image("example-output.png", "3D object outputs", "Object CSVs, calibration, analysis details, masks, and object maps are saved under FLASH/Image Analysis/3D Objects.")));
    }

    private static AnalysisHelpTopic spatialTopic() {
        return new AnalysisHelpTopic(
                FLASH_Pipeline.IDX_SPATIAL,
                "spatial",
                "Spatial Analysis",
                "Recompute spatial relationships and optional morphometry from object CSVs and label images already produced by 3D Object Analysis. No images are resegmented here; each section in the dialog turns on one family of measurements, and each section heading has its own helper button for detail.",
                list(
                        "Spatial Distances section computes nearest-neighbour distances between objects in different channels, perpendicular distances to drawn line ROIs, and per-channel Ripley K/L/G point-pattern statistics.",
                        "Colocalization section flags object pairs as colocalised by voxel-percent overlap (Volumetric overlap) or by centroid-in-object geometry (CPC).",
                        "Voronoi Tessellation section reports per-object territory area, neighbour count, and an inter-channel interaction matrix with permutation test.",
                        "Morphometric Analysis section appends 2D and 3D shape features plus composite, population-normalised, and spatially aware shape scores to the object CSVs.",
                        "Cell Phenotyping section runs unsupervised k-means clustering on each object's multi-channel feature profile and writes a Cluster column per object.",
                        "Density Heatmaps section saves per-channel Gaussian-KDE density maps as TIFF and PNG.",
                        "Use this after 3D Object Analysis has created object CSVs and you want distances, clustering, colocalisation summaries, territories, heatmaps, or morphometry without resegmenting images.",
                        "Use it when segmentation looks acceptable but you want to change spatial-analysis choices such as nearest-neighbour distances, Ripley's K/L/G statistics, CPC summaries, Voronoi territories, density heatmaps, or k-means phenotyping.",
                        "Use the morphometry options when saved object label images should be re-read to add 2D shape, 3D shape, composite morphology, population scoring, or spatial-morphometric columns."),
                list(
                        "Object CSV files from FLASH/Image Analysis/3D Objects/Objects/, or the legacy Data Analysis/Objects folder for older projects.",
                        "Object label images from FLASH/Image Analysis/3D Objects/Image Outputs/ when CPC, morphology, 3D morphometry, or heatmaps need saved object maps.",
                        "Calibration metadata from the object output folder when distances, Ripley's K/L/G statistics, Voronoi territories, heatmaps, or micron-scaled morphometry should be reported in calibrated units.",
                        "Channel identities, object names, ROI or region columns, and optional colocalisation thresholds for deciding which partner objects count as overlapping.",
                        "Optional line ROI sets can be consumed from the existing line-distance workflow when the Spatial Analysis options dialog exposes line-distance calculation for the project."),
                list(
                        "Confirm the object channels loaded from the existing CSV files; Spatial Analysis does not create new segmentations.",
                        "Choose nearest-neighbour distances for per-object DistToClosest and ClosestTo columns between channel pairs.",
                        "Choose advanced spatial summaries only when the required centroid and calibration data are present, such as calibrated XM/YM columns for Ripley's K/L/G or Voronoi outputs.",
                        "Choose CPC, volumetric overlap, heatmaps, phenotyping, and morphometry options only when their prerequisite object tables or saved label images are available."),
                list(
                        "Find the existing object CSV folder and load one table per detected channel.",
                        "Append calibrated centroid columns when calibration metadata are available.",
                        "Group objects by ROI, region, or section, then compute selected nearest-neighbour, colocalisation, statistics, Voronoi, heatmap, phenotyping, and morphometry measurements.",
                        "Write new spatial or morphometry summary CSVs, and update the same object CSV tables with added spatial and Morph_ columns."),
                list(
                        "Updated FLASH/Image Analysis/3D Objects/Objects/<channel>.csv files with added columns such as XM_um, YM_um, ZM_um, <channel>_DistToClosest_<partner>, <channel>_ClosestTo_<partner>, CPC, Voronoi, Cluster, and Morph_ measurements.",
                        "FLASH/Image Analysis/Spatial Analysis/Spatial/Spatial_Statistics_<channel>.csv contains Ripley's K/L/G summaries when spatial statistics run.",
                        "FLASH/Image Analysis/Spatial Analysis/Spatial/CPC_Spatial_Summary.csv and CPC_Multi_Target_Summary.csv summarize centroid-in-object colocalisation when CPC columns are available or recomputed.",
                        "FLASH/Image Analysis/Spatial Analysis/Spatial/Voronoi_<channel>.csv, Interaction_Matrix.csv, and Phenotyping/Clusters_<channel>.csv are written when Voronoi or k-means phenotyping are selected.",
                        "FLASH/Image Analysis/Spatial Analysis/Morphometry/ stores morphology, population summary, PPRP, and morphometry analysis-detail CSVs when morphometric options are selected.",
                        "FLASH/Image Analysis/Spatial Analysis/Image Outputs/<animal>/Heatmaps/ stores density heatmap TIFF and PNG files when heatmaps are selected."),
                list(
                        "Running this before 3D Object Analysis leaves no object CSVs to load, so Spatial Analysis has nothing to measure.",
                        "Bad segmentation creates bad spatial findings; inspect object maps and object counts before interpreting clustering, proximity, or morphology biologically.",
                        "Distances and heatmaps depend on consistent coordinates and calibration, so rerun or verify object outputs after changing image orientation, Z-slice choices, or calibration.",
                        "Ripley's K/L/G, Voronoi, and heatmaps can be skipped when calibrated centroids, enough objects, or label-image dimensions are missing.",
                        "Changing colocalisation thresholds changes derived CPC or volumetric summary interpretation; keep the saved settings with the results.",
                        "Force re-run at the top of the dialog overwrites existing outputs for the selected sections. Switch it on after changing thresholds, KDE bandwidth, cluster k, or any other setting whose old results you want recomputed."),
                optionalImages(
                        "spatial",
                        image("setup.png", "Spatial setup", "Distance, statistics, colocalisation, heatmap, phenotyping, and morphometry choices are selected after object CSVs already exist."),
                        image("workflow.png", "Spatial workflow", "Load object tables, use centroid coordinates and label images, compute spatial features, then update object CSVs and write spatial summaries."),
                        image("nearest-neighbour.png", "Nearest-neighbour distances", "Centroids are compared within each ROI or region so every object can record its closest partner object and distance."),
                        image("example-output.png", "Spatial output table", "Updated object tables and spatial summary CSVs contain added distance, CPC, Voronoi, Cluster, and Morph_ columns.")));
    }

    private static AnalysisHelpTopic aggregationTopic() {
        return new AnalysisHelpTopic(
                FLASH_Pipeline.IDX_AGGREGATION,
                "aggregation",
                "Combine results per condition / animal",
                "Combine per-image intensity, object, ROI, spatial, morphometry, and line-distance CSV files into project-level master tables grouped by animal, condition, image, ROI, region, hemisphere, or section.",
                list(
                        "Use this after Fluorescence Intensity Analysis, 3D Object Analysis, Spatial Analysis, or another quantitative step has written per-image CSV outputs.",
                        "Use it when you need one project-level table for condition comparisons, animal-level summaries, or Excel export.",
                        "Use it again after changing condition assignments, ROI attributes, object outputs, spatial outputs, or aggregation granularity."),
                list(
                        "Per-analysis CSV files from FLASH/Image Analysis/Image Intensities/, FLASH/Image Analysis/3D Objects/, FLASH/Image Analysis/Spatial Analysis/, and any legacy Data Analysis folders that still contain compatible outputs.",
                        "ROI attribute CSV files from FLASH/Results/Tables/ROIs/ when per-mm3 normalization or ROI metadata should be included.",
                        "Condition assignments saved in FLASH/Results Export/Conditions.csv, with legacy fallback when opening older projects.",
                        "A selected aggregation granularity and output mode, such as per-animal, per-hemisphere, per-region, per-section, raw, normalized, or both."),
                list(
                        "Review the condition-assignment table and assign every animal to the correct condition before running.",
                        "Choose the grouping granularity that matches the biological replicate you plan to compare later.",
                        "Choose raw, per-mm3, or both output types only when the required volume data are available."),
                list(
                        "Scan the configured FLASH output folders and legacy fallback folders for compatible CSV files.",
                        "Read object, intensity, ROI attribute, spatial, morphometry, and line-distance tables without rerunning the upstream analyses.",
                        "Attach parsed animal, condition, image, ROI, region, hemisphere, and analysis-type metadata to compatible rows.",
                        "Combine matching measurements into master object and intensity tables, then write the result aggregation outputs."),
                list(
                        "FLASH/Results Export/3D Objects.csv contains raw object and spatial summary rows at the selected grouping level.",
                        "FLASH/Results Export/3D Objects.csv contains volume-normalized _permm3 columns when per-mm3 output is enabled and volume data exist.",
                        "FLASH/Results Export/Image Intensities.csv contains aggregated fluorescence intensity measurements.",
                        "FLASH/Results Export/Conditions.csv records the condition assignments used by aggregation and statistics.",
                        "FLASH/Results Export/Aggregation_Analysis_Details.txt records aggregation settings and run details."),
                list(
                        "Running aggregation before upstream CSVs exist leaves empty or missing master tables for statistics and Excel export.",
                        "Inconsistent animal names, ROI names, or filename parsing can split one biological group into multiple rows.",
                        "Animals with blank or NULL condition labels are not ready for meaningful condition-level statistics.",
                        "Per-mm3 output requires ROI volume data; without it, normalized outputs can be skipped or incomplete.",
                        "Aggregation reuses existing CSV values and metadata. It does not recalculate segmentation, intensity, or spatial measurements."),
                optionalImages(
                        "aggregation",
                        image("setup.png", "Aggregation setup", "Condition assignments, grouping granularity, and raw or normalized output choices are reviewed before combining results."),
                        image("workflow.png", "Aggregation workflow", "Per-analysis CSV folders are scanned, metadata are attached, compatible rows are combined, and master tables are written."),
                        image("example-output.png", "Master table output", "3D Objects.csv and Image Intensities.csv are saved under FLASH/Results Export.")));
    }

    private static AnalysisHelpTopic statisticsTopic() {
        return new AnalysisHelpTopic(
                FLASH_Pipeline.IDX_STATISTICS,
                "statistics",
                "Statistical Analysis",
                "Run configured group comparisons from aggregated FLASH master tables and write project-level statistics CSV outputs.",
                list(
                        "Use this after Combine results per condition / animal has produced master object or intensity tables.",
                        "Use it when you need a CSV summary of condition comparisons for selected metrics.",
                        "Use the Statistics Helper or presets when paired design, distribution assumptions, post-hoc method, or metric scope should differ from the default automatic settings."),
                list(
                        "FLASH/Results Export/3D Objects.csv or Image Intensities.csv from aggregation.",
                        "Condition assignments from FLASH/Results Export/Conditions.csv, with legacy fallback for older projects.",
                        "A valid statistics configuration, either default automatic selection or a helper/preset choice for paired mode, distribution mode, post-hoc method, and metric filter.",
                        "Enough animal-level or selected grouping-level samples per condition for the configured comparisons to run."),
                list(
                        "Confirm every animal has the intended condition label in the condition-assignment table.",
                        "Choose or save a Statistics preset if you need paired tests, forced parametric or non-parametric routing, Tukey HSD, Dunn's test, raw p-values, or selected metrics only.",
                        "Check that the aggregation granularity matches the replicate unit you intend to compare."),
                list(
                        "Load available master object and intensity tables from FLASH/Results Export/ with legacy fallback.",
                        "Group numeric metric columns by condition using the saved condition manifest.",
                        "Apply the configured automatic, parametric, non-parametric, paired, and post-hoc routing for each eligible metric.",
                        "Skip metrics with insufficient usable values and record the reason in the output table.",
                        "Write one statistics table for the project without changing the upstream master CSV files."),
                list(
                        "FLASH/Results Export/Statistics.csv contains one row per global test, skipped metric, and eligible pairwise comparison.",
                        "Statistics rows include metric name, group labels, test name, statistic, p-value, adjusted p-value when applicable, normality result, paired flag, post-hoc method, and notes.",
                        "Excel Summary Export can read Statistics.csv and add a Statistics sheet when that section is enabled."),
                list(
                        "Running statistics before aggregation means there are no master tables to compare.",
                        "Too few samples per condition can make a metric skipped or make p-values unstable.",
                        "Image-level rows are not automatically independent biological replicates; check aggregation granularity before interpreting results.",
                        "Forcing parametric or non-parametric routing changes the test used, but it does not validate the experimental design.",
                        "Pairwise and post-hoc outputs describe the configured comparisons only; they do not replace study-design checks outside FLASH."),
                optionalImages(
                        "statistics",
                        image("setup.png", "Statistics setup", "Condition assignments, presets, and helper-derived test choices are checked before running statistics."),
                        image("workflow.png", "Statistics workflow", "Master tables and condition labels are loaded, metrics are grouped, tests are routed, and Statistics.csv is written."),
                        image("example-output.png", "Statistics table", "Statistics.csv records global tests, pairwise rows, adjusted p-values, paired flags, and skip notes.")));
    }

    private static AnalysisHelpTopic excelExportTopic() {
        return new AnalysisHelpTopic(
                FLASH_Pipeline.IDX_EXCEL_EXPORT,
                "excel-export",
                "Excel Summary Export",
                "Create a multi-sheet Excel workbook from aggregated master CSVs, condition assignments, optional statistics outputs, and upstream analysis details.",
                list(
                        "Use this after aggregation, and after Statistical Analysis if you want statistics included in the workbook.",
                        "Use it when you need a shareable workbook with condition summaries, per-metric sheets, statistics, and method details collected in one file.",
                        "Use an Excel export preset when the workbook should target a specific audience, such as exploratory review, supervisor review, figure-ready supplement, collaborator handoff, methods supplement, minimal export, or archive."),
                list(
                        "FLASH/Results Export/3D Objects.csv or Image Intensities.csv from aggregation.",
                        "FLASH/Results Export/Conditions.csv for condition-to-animal groupings.",
                        "FLASH/Results Export/Statistics.csv when the Statistics sheet is enabled.",
                        "Analysis Details files from upstream analysis folders when data summary or methods appendix sheets are enabled.",
                        "An Excel export preset, or the default preset choices shown in the export dialog."),
                list(
                        "Choose the workbook preset and review which sheets will be included.",
                        "Enable or disable experimental conditions, data summary, per-metric, statistics, methods appendix, significance highlighting, and header styling according to the selected preset.",
                        "Confirm the output should be written as the current project workbook before running."),
                list(
                        "Find master object and intensity CSVs, condition assignments, statistics CSVs, and available analysis-detail files from the current FLASH layout with legacy fallback.",
                        "Create workbook sheets for enabled sections, including Experimental Conditions, Data Summary, per-metric sheets, Statistics, and optional Methods Appendix.",
                        "Apply the selected formatting, significance highlighting, summary-statistic mode, and sheet-name cleanup.",
                        "Save the finished workbook to the Excel Summary Export folder."),
                list(
                        "FLASH/Results Export/Summary.xlsx is the generated workbook.",
                        "The workbook can include Experimental Conditions, Data Summary, per-metric measurement sheets, Statistics, and Methods Appendix sheets depending on the selected preset.",
                        "The Statistics sheet reflects FLASH/Results Export/Statistics.csv when that file exists and the preset includes it.",
                        "Per-metric sheets are derived from 3D Objects.csv and Image Intensities.csv; they are not new measurements."),
                list(
                        "Exporting before aggregation means the workbook has no master object or intensity tables to summarize.",
                        "The Statistics sheet is absent or stale if Statistical Analysis has not been run after the latest aggregation.",
                        "Excel export packages existing CSV values and run details; it does not recalculate segmentation, intensity, aggregation, or p-values.",
                        "An old Summary.xlsx can be mistaken for a new export, so check the file timestamp after rerunning.",
                        "Disabled preset sections are intentionally left out of the workbook, even if the source CSV files exist."),
                optionalImages(
                        "excel-export",
                        image("setup.png", "Excel export setup", "Workbook presets and included sheet sections are reviewed before export."),
                        image("workflow.png", "Excel export workflow", "Master CSVs, conditions, statistics, and analysis details are collected into a formatted workbook."),
                        image("example-output.png", "Workbook output", "Summary.xlsx is saved under FLASH/Results Export with enabled summary, detail, and statistics sheets.")));
    }

    private static java.util.List<String> list(String... values) {
        return Arrays.asList(values);
    }

    private static AnalysisHelpTopic.HelpImage image(String fileName, String title, String caption) {
        return new AnalysisHelpTopic.HelpImage(fileName, title, caption, true);
    }

    private static java.util.List<AnalysisHelpTopic.HelpImage> optionalImages(
            String key, AnalysisHelpTopic.HelpImage... images) {
        AnalysisHelpTopic.HelpImage[] copy = new AnalysisHelpTopic.HelpImage[images.length];
        for (int i = 0; i < images.length; i++) {
            AnalysisHelpTopic.HelpImage image = images[i];
            copy[i] = new AnalysisHelpTopic.HelpImage(
                    "/help/analysis/" + key + "/" + image.resourcePath,
                    image.title,
                    image.caption,
                    true);
        }
        return Arrays.asList(copy);
    }
}
