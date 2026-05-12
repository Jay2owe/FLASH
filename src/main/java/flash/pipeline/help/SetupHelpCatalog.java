package flash.pipeline.help;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Confirmed Set Up Configuration helper text for blue question-mark buttons.
 */
public final class SetupHelpCatalog {

    public static final SetupHelpTopic CHANNEL_IDENTITY = topic(
            "channel-identity",
            "Channel Identity",
            "This step tells FLASH what each image channel represents and how it should be displayed. "
                    + "The saved names and colours are reused for setup files, result tables, previews, "
                    + "making presentation-ready images, ROI drawing, and exported presentation images.",
            section("Controls",
                    "Preset dropdown: loads a saved Set Up Configuration preset.",
                    "Save as preset: saves the current setup as a reusable preset.",
                    "Channel Setup Helper: opens a guided marker-aware setup helper.",
                    "Number of channels: the number of colour channels in each image.",
                    "Channel name: the marker, stain, or signal identity written into outputs.",
                    "LUT: the display colour used for previews and generated images."),
            section("Saved output",
                    "FLASH saves these choices in FLASH/Set Up Configuration/.settings/Channel_Data.txt."));

    public static final SetupHelpTopic ANALYSIS_SCOPE = topic(
            "analysis-scope",
            "Analysis Scope",
            "Choose whether FLASH analyses the whole z-stack or only selected z-slices. "
                    + "This affects object counts, intensity summaries, spatial analysis, and presentation-ready images.",
            section("Controls",
                    "Restrict analysis to selected z-slices: OFF analyses the full z-stack.",
                    "Restrict analysis to selected z-slices: ON opens Z-Slice Subset before the other quality-check stages."),
            section("Watch out",
                    "Changing this later can change counts, volumes, intensities, and presentation-ready images."));

    public static final SetupHelpTopic Z_SLICE_SUBSET = topic(
            "z-slice-subset",
            "Z-Slice Subset",
            "This stage appears only when Restrict analysis to selected z-slices is on. "
                    + "Choose which z-slices are analysed for each image. Ranges are inclusive: 11-30 keeps slices 11 through 30.",
            section("Controls",
                    "Total z-slices: number of z-slices in the current image.",
                    "Start: first z-slice to keep.",
                    "Use current Z as start: copies the current preview slice into Start.",
                    "End: last z-slice to keep.",
                    "Use current Z as end: copies the current preview slice into End.",
                    "Action: what to do after locking in the current range.",
                    "Next image: save this range and move on.",
                    "Accept selection: save the final range and finish.",
                    "Restart from first image: review the z-slice choices again from the start.",
                    "Apply current range to all remaining images: reuse this range for the remaining images where it fits."),
            section("Saved output",
                    "FLASH saves selected ranges in FLASH/Set Up Configuration/.settings/ZSlice_Selections.csv."),
            section("Watch out",
                    "Changing the z-slice range changes counts, volumes, intensities, and presentation-ready images."));

    public static final SetupHelpTopic Z_SLICE_PARTIAL_APPLY = topic(
            "z-slice-partial-apply",
            "Range Does Not Fit Every Remaining Image",
            "This appears only when Apply current range to all remaining images fits some remaining images but not all of them.",
            section("Options",
                    "Apply to the compatible images, handle the outliers manually: batch-apply where possible, then stop at the first outlier.",
                    "Continue manually on all remaining images: continue image-by-image."));

    public static final SetupHelpTopic Z_SLICE_FINALISE = topic(
            "z-slice-finalise",
            "Finalise Z-Slice Subset",
            "This appears after the per-image ranges have been chosen.",
            section("Options",
                    "Keep customised slices per image: preserve each image's chosen range.",
                    "Use the same number of slices per image: trim each range to the same slice count for more comparable stack depth."));

    public static final SetupHelpTopic Z_SLICE_SAME_COUNT = topic(
            "z-slice-same-count",
            "Same Slice Count",
            "Choose how FLASH trims the selected z-slice ranges when every image should keep the same number of slices.",
            section("Positioning strategy",
                    "Centre within each image's selected range: keep the middle of each range.",
                    "Top-aligned: keep the first slices of each range.",
                    "Bottom-aligned: keep the last slices of each range.",
                    "Shared absolute window: use identical slice numbers for every image when possible."));

    public static final SetupHelpTopic SETTINGS_MODE = topic(
            "settings-mode",
            "Settings Mode",
            "Choose which settings you want to adjust interactively. Turn a channel ON to open the matching preview stage; leave it OFF to use the saved or default value.",
            section("Controls",
                    "All Settings Mode Options: turns all visible settings-mode toggles on or off.",
                    "Set Filter and Parameters: choose a filter preset and adjust its parameters.",
                    "Display Ranges: set how channels are scaled for previews and presentation-ready images.",
                    "Channel Thresholds: set signal-positive pixels for ROI / Intensity Analysis and classical object detection.",
                    "Segmentation Method: choose Classical, StarDist 3D, or Cellpose for object analysis.",
                    "Object Size Filter: keep classical objects within a voxel-size range.",
                    "TrackMate-StarDist Parameters: tune channels using StarDist 3D.",
                    "Cellpose 3D Parameters: tune channels using Cellpose."),
            section("Status icons",
                    "Tick means full saved data, ! means partial saved data, blank means no saved data found."));

    public static final SetupHelpTopic QC_IMAGE_SELECTION = topic(
            "select-images-for-quality-check",
            "Select Images for Quality Check",
            "Choose one of three ways to pick the image series FLASH will open for the interactive preview stages selected in Settings Mode. The other controls tune the selected choice.",
            section("Selection mode",
                    "Manually select images: choose exact image series. Best when you know which images are representative, difficult, or important.",
                    "Randomly select images: FLASH chooses a random sample. Best for a quick unbiased check when the dataset is fairly consistent. Number of random images sets the count.",
                    "Min and max per condition: FLASH scans selected QC channels and chooses low-signal and high-signal examples per condition. Recompute cached min/max selection reruns this scan."),
            section("Watch out",
                    "The settings locked in from these QC images are saved and reused for the full dataset."));

    public static final SetupHelpTopic SEGMENTATION_METHOD = topic(
            "segmentation-method",
            "Segmentation Method",
            "Choose one of three ways to turn this channel into detected 3D objects. The method you choose controls which preview and parameter screen opens next.",
            section("Options",
                    "Classical: 3D Objects Counter: threshold-based segmentation for bright, clean signal such as puncta, plaques, or well-separated labelled structures.",
                    "StarDist 3D: AI segmentation for round or star-convex objects, especially crowded nuclei or soma.",
                    "Cellpose: AI segmentation for cells and cell-like objects with flexible shapes or complex morphology."),
            section("Watch out",
                    "Choose the method for the biology and image quality, not just the most advanced option."));

    public static final SetupHelpTopic CLASSICAL_OBJECT_SEGMENTATION = topic(
            "classical-object-segmentation",
            "Classical Object Segmentation",
            "Classical object segmentation uses the filtered image, a signal threshold, and a voxel-size range to turn this channel into counted 3D objects.",
            section("Channel Threshold",
                    "Set the signal threshold for this channel. Signal below this value is excluded; signal at or above it is kept.",
                    "The left Threshold preview updates as you adjust the threshold.",
                    "The saved threshold is applied to all images for this channel and reused for ROI / Intensity Analysis and classical object detection."),
            section("Object Preview",
                    "The right Object preview shows the 3D Objects Counter label map after you press Run Object Preview.",
                    "Threshold or size edits make the Object preview out of date until you run it again.",
                    "Large view lets you inspect the original or filtered input without thresholding, alongside the threshold and object previews."),
            section("Particle Size",
                    "Particle sizes (voxels): the object-size range to keep.",
                    "Min: smallest object size to keep. Increase this to remove specks and debris.",
                    "Max: largest object size to keep. Use Infinity when there should be no upper limit.",
                    "Run Object Preview: runs 3D object detection with the current threshold and size range.",
                    "Reset sizes: restores the saved size range."),
            section("Watch out",
                    "If the threshold is too low, background becomes objects. If it is too high, real dim objects disappear."));

    public static final SetupHelpTopic STARDIST = topic(
            "stardist-object-segmentation",
            "StarDist",
            "StarDist object segmentation detects compact round or star-convex objects, then links detections through z-slices into 3D objects.",
            section("Detection",
                    "Probability: minimum StarDist confidence needed to keep a detection.",
                    "NMS: overlap tolerance when detections compete for the same object."),
            section("Linking",
                    "Distance: maximum movement allowed between neighbouring z-slices.",
                    "Gap distance: maximum movement allowed when reconnecting across a missing z-slice.",
                    "Frame gap: number of missing z-slices StarDist may skip while linking. 0 means adjacent slices only."),
            section("Filters",
                    "Area min: removes small detected objects.",
                    "Area max: removes large detected objects. Use 0 for no upper limit.",
                    "Quality min: removes detections with low StarDist / TrackMate quality scores.",
                    "Intensity min: removes dim detections by mean signal intensity.",
                    "Run Preview: runs StarDist with the current settings.",
                    "Reset to saved: restores the saved StarDist settings.",
                    "Filtered: preview the filtered image used for StarDist.",
                    "Raw: preview the original channel before filtering.",
                    "Overlay objects: draw detected StarDist objects over the selected preview source."));

    public static final SetupHelpTopic CELLPOSE = topic(
            "cellpose-object-segmentation",
            "Cellpose",
            "Cellpose object segmentation detects cells or cell-like objects using a selected Cellpose model, expected object size, and detection thresholds.",
            section("Model",
                    "Model: choose the Cellpose model.",
                    "cyto3: recommended first choice for irregular whole-cell bodies and glial soma.",
                    "cyto2: legacy whole-cell model that may suit older cytoplasmic datasets.",
                    "cyto: older cytoplasm model, mainly for legacy comparisons.",
                    "nuclei: for rounded nuclear objects.",
                    "tissuenet_cp3: broad tissue-trained model; useful if cyto3 under-segments tissue sections.",
                    "livecell_cp3: cultured-cell model, usually less suitable for fixed tissue sections.",
                    "Companion: optional second channel for models that support it.",
                    "Use GPU: runs Cellpose on a compatible GPU when available.",
                    "Install GPU Support: installs the managed GPU runtime if needed."),
            section("Detection",
                    "Diameter: expected object diameter. Use 0 only if Cellpose should estimate size automatically.",
                    "Flow threshold: mask quality cutoff. Higher values are more permissive; 0 disables this check.",
                    "Cell probability: minimum Cellpose object probability.",
                    "Run Preview: runs Cellpose with the current settings.",
                    "Reset to saved: restores the saved Cellpose settings.",
                    "Filtered: preview the filtered image used for Cellpose.",
                    "Raw: preview the original channel before filtering.",
                    "Overlay objects: draw detected Cellpose objects over the selected preview source."));

    public static final SetupHelpTopic FILTER_PARAMETERS = topic(
            "set-filter-and-parameters",
            "Set Filter and Parameters",
            "Choose the image-processing filter used before thresholding or object segmentation, then preview how it changes this channel. The saved filter is applied to all images for this channel.",
            section("Filter",
                    "You can tune an existing preset by changing its parameters and filter steps, or create a custom macro and save it as a reusable preset.",
                    "Default: standard median and background subtraction.",
                    "Punctate Signal / High Background: for distinct puncta against diffuse background.",
                    "Ramified Cells (Microglia/Astrocytes): for complex cells with thin processes.",
                    "Clustered Small: for crowded small objects.",
                    "Clustered Large: for crowded large objects.",
                    "Overlapping Cellular Marker: for dense overlapping cellular stains.",
                    "Puncta Resolve: for separating puncta from dim cytoplasm.",
                    "Diffuse Object: for faint diffuse staining.",
                    "Custom: use or create a custom macro instead of a bundled preset."),
            section("Controls",
                    "Open in canvas...: opens the visual filter builder for custom macros or larger structural edits.",
                    "+ Add filter...: adds another filter step.",
                    "Run Preview: applies the current filter to the selected QC image.",
                    "Reset: restores the saved filter.",
                    "Save preset...: saves the modified filter as a reusable preset.",
                    "Filters: lists the current filter steps.",
                    "Expand all / Collapse all: opens or closes all filter-step panels.",
                    "Advanced...: shows less-common parameters.",
                    "Move step up / Move step down: changes step order.",
                    "Disable this step: skips a filter step without deleting it.",
                    "Remove this step: deletes a filter step."));

    public static final SetupHelpTopic DISPLAY_RANGE = topic(
            "display-range",
            "Display Range",
            "Set how this channel is scaled for display. This changes brightness and contrast for viewing, previews, and presentation-ready images from the main UI; it does not change raw data or measurement values.",
            section("Brightness/Contrast",
                    "Minimum: signal value shown as the dark end of the display range.",
                    "Maximum: signal value shown as the bright end of the display range.",
                    "Brightness: moves the display range up or down.",
                    "Contrast: widens or narrows the display range.",
                    "Auto: suggests a display range from the image histogram.",
                    "Reset: restores the previous display range for this image.",
                    "Set: locks in the current display range and moves to the next image."),
            section("Watch out",
                    "Display Range is for visual output only. Do not use it as a threshold."));

    public static final SetupHelpTopic CHANNEL_THRESHOLD = topic(
            "channel-threshold",
            "Channel Threshold",
            "Set the signal threshold for this channel. Signal below this value is excluded; signal at or above it is kept.",
            section("Controls",
                    "The saved threshold is applied to all images for this channel.",
                    "It is used for thresholded ROI / Intensity Analysis and, for Classical object segmentation, for deciding which pixels can become objects.",
                    "Use the threshold preview to choose a cutoff that works across the selected QC images.",
                    "Auto: suggests a threshold from the selected method and background mode.",
                    "Reset: restores the previous threshold for this image.",
                    "Set: locks in the current threshold and moves to the next image."),
            section("Watch out",
                    "If the threshold is too low, background is included as signal. If it is too high, real dim signal is removed."));

    private static final Map<String, SetupHelpTopic> TOPICS = buildTopics();

    private SetupHelpCatalog() {
    }

    public static SetupHelpTopic forKey(String key) {
        return key == null ? null : TOPICS.get(key);
    }

    public static Map<String, SetupHelpTopic> all() {
        return TOPICS;
    }

    private static Map<String, SetupHelpTopic> buildTopics() {
        Map<String, SetupHelpTopic> topics = new LinkedHashMap<String, SetupHelpTopic>();
        put(topics, CHANNEL_IDENTITY);
        put(topics, ANALYSIS_SCOPE);
        put(topics, Z_SLICE_SUBSET);
        put(topics, Z_SLICE_PARTIAL_APPLY);
        put(topics, Z_SLICE_FINALISE);
        put(topics, Z_SLICE_SAME_COUNT);
        put(topics, SETTINGS_MODE);
        put(topics, QC_IMAGE_SELECTION);
        put(topics, SEGMENTATION_METHOD);
        put(topics, CLASSICAL_OBJECT_SEGMENTATION);
        put(topics, STARDIST);
        put(topics, CELLPOSE);
        put(topics, FILTER_PARAMETERS);
        put(topics, DISPLAY_RANGE);
        put(topics, CHANNEL_THRESHOLD);
        return Collections.unmodifiableMap(topics);
    }

    private static void put(Map<String, SetupHelpTopic> topics, SetupHelpTopic topic) {
        if (topics.put(topic.key, topic) != null) {
            throw new IllegalStateException("Duplicate setup helper key: " + topic.key);
        }
    }

    private static SetupHelpTopic topic(String key, String title, String summary,
                                        SetupHelpTopic.Section... sections) {
        return new SetupHelpTopic(key, title, summary, Arrays.asList(sections));
    }

    private static SetupHelpTopic.Section section(String heading, String... items) {
        return new SetupHelpTopic.Section(heading, list(items));
    }

    private static List<String> list(String... items) {
        return Arrays.asList(items);
    }
}
