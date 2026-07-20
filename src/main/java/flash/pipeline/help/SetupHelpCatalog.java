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
            "Tells FLASH what each channel represents and how to display it. The saved names and colours are reused across "
                    + "setup files, result tables, previews, ROI drawing, and exported presentation images.",
            section("Controls",
                    "Number of channels: how many colour channels each image has.",
                    "Channel name: the marker, stain, or signal identity written into outputs.",
                    "LUT: the display colour used for previews and generated images."),
            section("Saved output",
                    "Stored in FLASH/Config/.settings/channel_config.json."));

    public static final SetupHelpTopic ANALYSIS_SCOPE = topic(
            "analysis-scope",
            "Analysis Scope",
            "Choose whether FLASH analyses the whole z-stack or only selected z-slices. This affects object counts, "
                    + "intensity summaries, spatial analysis, and presentation-ready images.",
            section("Controls",
                    "Restrict analysis to selected z-slices: OFF analyses the full z-stack.",
                    "ON opens Z-Slice Subset before the other quality-check stages."),
            section("Watch out",
                    "Changing this later can change counts, volumes, intensities, and presentation-ready images."));

    public static final SetupHelpTopic Z_SLICE_SUBSET = topic(
            "z-slice-subset",
            "Z-Slice Subset",
            "Appears only when Restrict analysis to selected z-slices is on. Choose which z-slices are analysed for each "
                    + "image. Ranges are inclusive: 11-30 keeps slices 11 through 30.",
            section("Controls",
                    "Total z-slices: number of z-slices in the current image.",
                    "Start / End: first and last z-slice to keep. Use current Z copies the current preview slice into either field.",
                    "Next image: save this range and move on. Accept selection: save the final range and finish.",
                    "Restart from first image: review the z-slice choices again from the start.",
                    "Apply current range to all remaining images: reuse this range for the remaining images where it fits.",
                    "Use largest range that fits all images: set every image to 1 through the smallest z-slice count in the dataset."),
            section("Saved output",
                    "Selected ranges are saved in FLASH/Config/.settings/channel_config.json."),
            section("Watch out",
                    "Changing the z-slice range changes counts, volumes, intensities, and presentation-ready images."));

    public static final SetupHelpTopic Z_SLICE_PARTIAL_APPLY = topic(
            "z-slice-partial-apply",
            "Range Does Not Fit Every Remaining Image",
            "Appears only when Apply current range to all remaining images fits some remaining images but not all of them.",
            section("Options",
                    "Apply to the compatible images, handle the outliers manually: batch-apply where possible, then stop at the first outlier.",
                    "Continue manually on all remaining images: continue image-by-image."));

    public static final SetupHelpTopic Z_SLICE_FINALISE = topic(
            "z-slice-finalise",
            "Finalise Z-Slice Subset",
            "Appears after the per-image ranges have been chosen.",
            section("Options",
                    "Keep customised slices per image: preserve each image's chosen range.",
                    "Use the same number of slices per image: trim each range to the same slice count for more comparable stack depth."));

    public static final SetupHelpTopic Z_SLICE_SAME_COUNT = topic(
            "z-slice-same-count",
            "Same Slice Count",
            "How FLASH trims the selected z-slice ranges when every image should keep the same number of slices.",
            section("Positioning strategy",
                    "Centre within each image's selected range: keep the middle of each range.",
                    "Top-aligned / Bottom-aligned: keep the first or last slices of each range.",
                    "Shared absolute window: use identical slice numbers for every image when possible."));

    public static final SetupHelpTopic SETTINGS_MODE = topic(
            "settings-mode",
            "Settings Mode",
            "Choose which settings to adjust interactively. Turn a channel ON to open its preview stage; leave it OFF to use the saved or default value.",
            section("Controls",
                    "All Settings Mode Options: turns every visible settings-mode toggle on or off.",
                    "Set Filter and Parameters: choose a filter preset and tune its parameters.",
                    "Display Ranges: set how channels are scaled for previews and presentation-ready images.",
                    "Channel Thresholds: set signal-positive pixels for ROI / Intensity Analysis and classical object detection.",
                    "Segmentation Method: choose which channels open object segmentation setup."),
            section("Status icons",
                    "Tick means full saved data, ! means partial saved data, blank means none found."));

    public static final SetupHelpTopic QC_IMAGE_SELECTION = topic(
            "select-images-for-quality-check",
            "Select Images for Quality Check",
            "Choose how FLASH picks the image series to open for the preview stages you enabled in Settings Mode.",
            section("Selection mode",
                    "Manually select images: pick exact series. Best when you know which images are representative, difficult, or important.",
                    "Randomly select images: a random sample for a quick unbiased check on a consistent dataset. Number of random images sets the count.",
                    "Min and max per condition: scans selected QC channels for low-signal and high-signal examples per condition. Recompute reruns the scan."),
            section("Watch out",
                    "The settings locked in from these QC images are saved and reused for the full dataset."));

    public static final SetupHelpTopic SEGMENTATION_METHOD = topic(
            "segmentation-method",
            "Segmentation Method",
            "Choose how this channel becomes detected 3D objects. The method controls which preview and parameter screen opens next.",
            section("Options",
                    "Classical (3D Objects Counter): threshold-based, for bright clean signal such as puncta, plaques, or well-separated structures.",
                    "StarDist 3D: AI for round or star-convex objects, especially crowded nuclei or soma.",
                    "Cellpose: AI for cells and cell-like objects with flexible shapes or complex morphology."),
            section("Watch out",
                    "Choose the method for the biology and image quality, not just the most advanced option."));

    public static final SetupHelpTopic CLASSICAL_OBJECT_SEGMENTATION = topic(
            "classical-object-segmentation",
            "Classical Object Segmentation",
            "Turns this channel into counted 3D objects from the filtered image, a signal threshold, and a voxel-size range.",
            section("Channel Threshold",
                    "Set the signal threshold; signal below it is excluded, at or above it is kept.",
                    "The left Threshold preview updates as you adjust the threshold.",
                    "The saved threshold applies to all images for this channel and is reused for ROI / Intensity Analysis and classical object detection."),
            section("Object Preview",
                    "The right Object preview shows the 3D Objects Counter label map after you press Run Object Preview.",
                    "Threshold or size edits make it out of date until you run it again.",
                    "Large view inspects the original or filtered input alongside the threshold and object previews."),
            section("Particle Size",
                    "Particle sizes (voxels): the object-size range to keep. Min removes specks and debris; Max caps size (Infinity = no upper limit).",
                    "Run Object Preview: detect with the current threshold and size range. Reset sizes: restore the saved range."),
            section("Watch out",
                    "If the threshold is too low, background becomes objects. If it is too high, real dim objects disappear."));

    public static final SetupHelpTopic STARDIST = topic(
            "stardist-object-segmentation",
            "StarDist",
            "Detects compact round or star-convex objects per slice, then links the detections through z-slices into 3D objects.",
            section("Detection",
                    "Probability: minimum StarDist confidence needed to keep a detection.",
                    "NMS: overlap tolerance when detections compete for the same object."),
            section("Linking",
                    "Distance: maximum movement allowed between neighbouring z-slices.",
                    "Gap distance: maximum movement when reconnecting across a missing z-slice.",
                    "Frame gap: number of missing z-slices StarDist may skip while linking (0 = adjacent slices only)."),
            section("Filters",
                    "Area min / max: remove small or large objects (Area max 0 = no upper limit).",
                    "Quality min: remove low StarDist/TrackMate quality detections. Intensity min: remove dim detections.",
                    "Run Preview / Reset to saved. Filtered, Raw, Overlay objects: choose the preview source."));

    public static final SetupHelpTopic CELLPOSE = topic(
            "cellpose-object-segmentation",
            "Cellpose",
            "Detects cells or cell-like objects using a selected Cellpose model, an expected object size, and detection thresholds.",
            section("Model",
                    "Model: choose the Cellpose model. cyto3 is the recommended first choice for irregular whole cells and glial soma; nuclei for rounded nuclei; tissuenet_cp3 for tissue sections that cyto3 under-segments.",
                    "Companion: optional second channel for models that support it.",
                    "Use GPU / Install GPU Support: run on a compatible GPU, installing the managed runtime if needed."),
            section("Detection",
                    "Diameter: expected object diameter. Use 0 only if Cellpose should estimate size automatically.",
                    "Flow threshold: mask-quality cutoff (higher is more permissive; 0 disables). Cell probability: minimum object probability.",
                    "Run Preview / Reset to saved. Filtered, Raw, Overlay objects: choose the preview source."));

    public static final SetupHelpTopic FILTER_PARAMETERS = topic(
            "set-filter-and-parameters",
            "Set Filter and Parameters",
            "Choose the image-processing filter applied before thresholding or segmentation, then preview how it changes this channel. The saved filter applies to all images for this channel.",
            section("Filter",
                    "Tune a preset by editing its parameters and steps, or build a custom macro and save it as a reusable preset.",
                    "Presets: Default, Punctate Signal / High Background, Ramified Cells, Clustered Small, Clustered Large, Overlapping Cellular Marker, Puncta Resolve, Diffuse Object, Custom."),
            section("Controls",
                    "Custom macro...: build visually, record Fiji actions, or import a macro file.",
                    "+ Add filter / Run Preview / Reset / Save preset...",
                    "Per step: Move up or down, Disable, Remove. Expand all / Collapse all. Advanced... shows less-common parameters."));

    public static final SetupHelpTopic DISPLAY_RANGE = topic(
            "display-range",
            "Display Range",
            "Set how this channel is scaled for display. This changes brightness and contrast for viewing, previews, and "
                    + "presentation-ready images from the main UI; it does not change raw data or measurement values.",
            section("Brightness/Contrast",
                    "Manual min/max: set the dark and bright ends directly. Auto-enhance contrast computes them from each image using the saturated-pixels percentage.",
                    "Minimum / Maximum: values shown as the dark and bright ends. Brightness moves the range; Contrast widens or narrows it.",
                    "Suggest: a display range from the histogram. Reset: the previous range for this image. Set: lock in and move to the next image."),
            section("Watch out",
                    "Display Range is for visual output only. Do not use it as a threshold."));

    public static final SetupHelpTopic CHANNEL_THRESHOLD = topic(
            "channel-threshold",
            "Channel Threshold",
            "Set the signal threshold for this channel. Signal below this value is excluded; signal at or above it is kept.",
            section("Controls",
                    "The saved threshold applies to all images for this channel.",
                    "Used for thresholded ROI / Intensity Analysis and, for Classical segmentation, deciding which pixels can become objects.",
                    "Auto: suggest a threshold from the method and background mode. Reset: the previous threshold. Set: lock in and move to the next image."),
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
