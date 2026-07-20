package flash.pipeline.help;

import flash.pipeline.FLASH_Pipeline;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Central registry for focused help topics shown beside visible analyses.
 *
 * <p>Each topic is an at-a-glance card: a short {@code whatItDoes} lead, one
 * concept diagram, compact {@code needsFirst}/{@code produces} lines, the auto
 * workflow flow-diagram, and one or two {@code watchOut} pitfalls.
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
        put(topics, representativeFigureTopic());
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
                "Custom segmentation models",
                "Register a StarDist or Cellpose model trained outside FLASH when stock segmentation settings cannot represent the objects reliably.",
                list("A model trained outside FLASH (StarDist SavedModel zip or Cellpose 3 model)",
                        "The Custom Model Manager"),
                list("A project model entry under FLASH/Config/Segmentation models/"),
                list("Train externally", "Import via Custom Model Manager", "Apply the model token to the channel"),
                list("Cellpose 4 / Cellpose-SAM are not supported by the pinned Cellpose 3.1.1.2 runtime.",
                        "StarDist runs per-slice in 2D with Z-linking, not full 3D."),
                Collections.<AnalysisHelpTopic.HelpImage>emptyList());
    }

    private static AnalysisHelpTopic enhancedClassicalSegmentationTopic() {
        return new AnalysisHelpTopic(
                FLASH_Pipeline.IDX_CREATE_BIN,
                "enhanced-classical-segmentation-method",
                "Enhanced Classical segmentation method",
                "Starts from the normal threshold and voxel-size detection, then filters candidate objects by measured morphology during segmentation.",
                list("A filtered channel preview and a signal threshold", "Minimum and maximum voxel sizes"),
                list("An enhanced_classical method token with encoded morphology predicates"),
                list("Run object preview", "Add morphology filters", "Re-run preview", "Lock the method"),
                list("Morphology filters change the segmented object set itself, not a later cleanup step.",
                        "Strict filters can drop real objects that are dim, clipped, or at the edge."),
                Collections.<AnalysisHelpTopic.HelpImage>emptyList());
    }

    private static AnalysisHelpTopic customModelManagerTopic() {
        return new AnalysisHelpTopic(
                FLASH_Pipeline.IDX_CREATE_BIN,
                "custom-model-manager",
                "Custom Model Manager",
                "Lists stock and project-specific segmentation models and keeps stable model keys so saved methods stay reproducible.",
                list("The current project", "A StarDist zip or a Cellpose model/name to import"),
                list("Catalog JSON and copied files under FLASH/Config/Segmentation models/"),
                list("Open from a StarDist/Cellpose stage", "Add or edit an entry", "Return and refresh the model list"),
                list("Deleting a user model can make older runs report a missing model key.",
                        "Use project-relative paths so replay stays portable."),
                Collections.<AnalysisHelpTopic.HelpImage>emptyList());
    }

    private static AnalysisHelpTopic setupConfigurationTopic() {
        return new AnalysisHelpTopic(
                FLASH_Pipeline.IDX_CREATE_BIN,
                "set-up-configuration",
                "Set Up Configuration",
                "Tells FLASH what each channel contains and saves the thresholds, filters, segmentation choices, and Z-range that every later analysis reuses.",
                list("The project folder of images", "Which marker is in each channel (DAPI, IBA1, GFAP, ...)"),
                list("FLASH/Config/.settings/channel_config.json - names, thresholds, filters, segmentation, Z-range"),
                list("Pick folder", "Name channels", "Set thresholds & filters", "Choose Z-range", "Save"),
                list("A wrong channel identity makes every later result hard to read, even if the run succeeds.",
                        "Thresholds copied from another experiment can miss dim signal or include background."),
                optionalImages(
                        "set-up-configuration",
                        image("overview.jpg", "Settings overview",
                                "One row per channel with a column for each decision (marker, threshold, segmentation, filter, display range, Z-range), saved once and reused by every downstream analysis."),
                        image("parameter-variations.jpg", "Parameter variations",
                                "Sweep filtering, segmentation, and deconvolution parameters and pick the values that best match your images."),
                        image("check-settings.jpg", "Check settings on reference images",
                                "Preview each chosen setting (filter, threshold, segmentation, display) on a representative image to confirm it looks right before the batch."),
                        image("macro-building.jpg", "Build a filter macro",
                                "Build, record, or import a custom ImageJ filter macro and save it as a reusable preset during filtering.")));
    }

    private static AnalysisHelpTopic drawAndSaveRoisTopic() {
        return new AnalysisHelpTopic(
                FLASH_Pipeline.IDX_DRAW_ROIS,
                "draw-save-rois",
                "Draw ROIs and Orientate Images",
                "Draw the named regions that bound later measurements, with always-open rotate/flip controls that save each image's orientation for reuse.",
                list("Channel config", "Project images"),
                list("FLASH/Results/Analysis Images/ROIs/<name> ROIs.zip",
                        "FLASH/Results/Tables/Project Summary/Image Orientation.csv - saved rotate/flip per image"),
                list("Open image", "Rotate / flip", "Draw regions", "Save ROIs", "Next image"),
                list("Changing orientation after drawing an unsaved ROI clears that ROI so coordinates still match the image.",
                        "Inconsistent region names split one region into separate groups at aggregation."),
                optionalImages(
                        "draw-save-rois",
                        image("orientate-rois.jpg", "Draw ROIs and orientate images",
                                "Draw any number of named regions on each image, then orientate each image: rotate it upright and flip it to match the opposite hemisphere.")));
    }

    private static AnalysisHelpTopic deconvolutionTopic() {
        return new AnalysisHelpTopic(
                FLASH_Pipeline.IDX_DECONVOLUTION,
                "deconvolution",
                "3D Deconvolution",
                "Reverses the microscope's optical blur on a Z-stack so structures blurred together become sharp and separable before segmentation or intensity measurement.",
                list("Real Z-stacks", "Microscope metadata (voxel size, Z step, wavelength)"),
                list("FLASH/Results/Analysis Images/Deconvolution/<image>_C<n>.tif"),
                list("Pick channels", "Preview on a crop", "Run batch"),
                list("Over-sharpening creates halos and ringing that look like real structure.",
                        "Wrong voxel size, wavelength, or refractive index gives a wrong PSF."),
                optionalImages(
                        "deconvolution",
                        image("deconvolution.jpg", "Raw vs deconvolved",
                                "Deconvolution reverses the microscope's optical blur, so structures that were blurred together in the raw stack become sharp and separable.")));
    }

    private static AnalysisHelpTopic spectralDecontaminationTopic() {
        return new AnalysisHelpTopic(
                FLASH_Pipeline.IDX_SPECTRAL_DECONTAMINATION,
                "spectral-decontamination",
                "Spectral Decontamination (Experimental)",
                "Experimental step that subtracts channel bleed-through or autofluorescence before you measure the target signal.",
                list("Channel config", "Target and contaminating/autofluorescence channels", "Controls where possible"),
                list("FLASH/Results/Tables/Spectral Decontamination/ - summaries and coefficients"),
                list("Assign channel roles", "Pick correction", "Preview", "Save & run"),
                list("Over-correction removes real target signal, especially when controls do not match the tissue.",
                        "Contamination varies by region and session, so one coefficient may not fit every image."),
                optionalImages(
                        "spectral-decontamination",
                        image("spectral-decontamination.jpg", "Spectral Decontamination",
                                "Estimate and remove channel bleed-through and autofluorescence from the target channel, leaving the true target signal.")));
    }

    private static AnalysisHelpTopic splitAndMergeTopic() {
        return new AnalysisHelpTopic(
                FLASH_Pipeline.IDX_SPLIT_MERGE,
                "split-merge",
                "Make Presentation Images",
                "Builds per-channel images, coloured merges, overview tile montages, and display-ready exports for visual checking and figures. Display only, not measurements.",
                list("Channel config (names, colours, display ranges)", "Project images"),
                list("FLASH/Results/Presentation Images/Images/<animal>/ - split + merged composites",
                        "FLASH/Results/Presentation Images/Tiles/ - overview montage when enabled",
                        "FLASH/Results/Presentation Images/OME-TIFF/ when enabled"),
                list("Set per-channel display", "Merge channels", "Tile montage", "Export images"),
                list("These are display choices, not quantitative fluorescence; do not use display-enhanced PNGs as input to intensity or object analysis."),
                optionalImages(
                        "split-merge",
                        image("presentation-images.jpg", "Make Presentation Images",
                                "Split each channel, give it a display colour (LUT) and contrast, merge the channels into a composite, and lay out an overview montage.")));
    }

    private static AnalysisHelpTopic representativeFigureTopic() {
        return new AnalysisHelpTopic(
                FLASH_Pipeline.IDX_REPRESENTATIVE_FIGURE,
                "representative-figure",
                "Make Representative Image Figure",
                "For each condition, rank images by a metric, pick the representative, and assemble a labelled figure panel with consistent display ranges.",
                list("Presentation images or source images", "Condition assignments"),
                list("A PNG figure under FLASH/Results/"),
                list("Choose ranking metric", "Pick one image per condition", "Lay out & label", "Export PNG"),
                list("A representative figure is for visual comparison, not a replacement for the quantitative CSVs.",
                        "Display ranges change appearance only, not measured intensity."),
                optionalImages(
                        "representative-figure",
                        image("representative-figure.jpg", "Make Representative Image Figure",
                                "For each condition, rank the images by a metric, pick the representative, and assemble a labelled figure with consistent display.")));
    }

    private static AnalysisHelpTopic intensityTopic() {
        return new AnalysisHelpTopic(
                FLASH_Pipeline.IDX_INTENSITY,
                "intensity",
                "Fluorescence Intensity Analysis",
                "Reports mean intensity per region. Optionally counts only above-threshold pixels, or only inside a mask channel.",
                list("Channel config", "ROI zips"),
                list("FLASH/Results/Tables/Intensity/<channel>.csv"),
                list("Load ROIs", "Measure", "Threshold", "Save CSV"),
                list("ROI zips must match the images, or values land on the wrong region; thresholds do not transfer across staining batches."),
                optionalImages(
                        "intensity",
                        image("modes.jpg", "Where intensity is measured",
                                "The same image measured four ways: the whole image, inside one ROI, several ROIs separately, or only above-threshold (binarised) pixels."),
                        image("spatial.jpg", "Intensity-Spatial",
                                "Within one region: the spatial pattern of one channel (uniform, patchy, hotspots) and the relationship between two channels (co-located, anti-correlated, distance shell).")));
    }

    private static AnalysisHelpTopic threeDObjectTopic() {
        return new AnalysisHelpTopic(
                FLASH_Pipeline.IDX_3D_OBJECT,
                "three-d-object",
                "3D Object Analysis",
                "Segments 3D objects and measures their count, size, shape, intensity, and built-in cross-channel colocalisation, with optional process length. Further spatial measures run in Spatial Analysis.",
                list("Channel config (thresholds, segmentation)", "ROI zips to restrict to tissue"),
                list("FLASH/Results/Tables/Objects/<channel>.csv",
                        "FLASH/Results/Analysis Images/Segmentation/<animal>/ - label maps + masked images"),
                list("Segment channels", "Filter by ROI & size", "Measure objects", "Colocalise channels"),
                list("The wrong object channel gives plausible counts for the wrong marker.",
                        "Colocalisation % depends on segmentation quality, so check the masks before reading it biologically."),
                optionalImages(
                        "three-d-object",
                        image("segment-count.jpg", "Segment, count and measure",
                                "Raw signal is segmented into discrete 3D objects, counted across the whole image or within ROIs, and measured per object (volume, surface area, intensity, coordinates)."),
                        image("colocalisation.jpg", "Spatial colocalisation",
                                "Between two channels: voxel overlap and centroid coincidence (CPC), the bounding-box families (box overlap, centroid-in-box, box volume fill), and nearest-neighbour distance."),
                        image("morphometry.jpg", "Morphometry",
                                "Per-object shape features: sphericity, elongation, flatness, Feret diameter, and composite shape indices.")));
    }

    private static AnalysisHelpTopic spatialTopic() {
        return new AnalysisHelpTopic(
                FLASH_Pipeline.IDX_SPATIAL,
                "spatial",
                "Spatial Analysis",
                "Computes a choice of distances, colocalisation, territories, clustering, heatmaps, and morphometry from the object CSVs that 3D Object Analysis already produced. No images are resegmented; each section in the dialog turns on one family of measurements.",
                list("Object CSVs in FLASH/Results/Tables/Objects/",
                        "Object label images (for CPC, morphometry, heatmaps)"),
                list("FLASH/Results/Tables/Spatial/ - distances, CPC, Voronoi, statistics",
                        "FLASH/Results/Tables/Morphometry/ when morphometry is on"),
                list("Load object CSVs", "Pick measurements", "Compute", "Write spatial tables"),
                list("Bad segmentation creates bad spatial findings; check object maps and counts before interpreting them.",
                        "Force re-run overwrites existing outputs for the selected sections."),
                optionalImages(
                        "spatial",
                        image("colocalisation.jpg", "Spatial colocalisation",
                                "Between two channels: voxel overlap and centroid coincidence (CPC), the bounding-box families (box overlap, centroid-in-box, box volume fill), and nearest-neighbour distance."),
                        image("morphometry.jpg", "Morphometry",
                                "Per-object shape features: sphericity, elongation, flatness, Feret diameter, and composite shape indices.")));
    }

    private static AnalysisHelpTopic aggregationTopic() {
        return new AnalysisHelpTopic(
                FLASH_Pipeline.IDX_AGGREGATION,
                "aggregation",
                "Combine results per condition / animal",
                "Combines the per-image CSVs from intensity, object, and spatial analyses into project-level master tables, grouped by animal or condition.",
                list("Per-image CSVs in FLASH/Results/Tables/ (Intensity, Objects, Spatial)",
                        "FLASH/Results/Tables/Project Summary/Conditions.csv"),
                list("FLASH/Results/Tables/Project Summary/3D Objects.csv",
                        "FLASH/Results/Tables/Project Summary/Image Intensities.csv"),
                list("Scan result tables", "Attach conditions", "Group rows", "Write master tables"),
                list("Blank or inconsistent condition or animal names split one group into several rows.",
                        "Aggregation reuses existing CSV values; it does not recompute segmentation, intensity, or spatial measurements."),
                optionalImages(
                        "aggregation",
                        image("aggregation.jpg", "Group per-image results",
                                "Combines the many per-image result rows into project master tables (3D Objects.csv, Image Intensities.csv), grouped by animal or condition with condition labels attached.")));
    }

    private static AnalysisHelpTopic statisticsTopic() {
        return new AnalysisHelpTopic(
                FLASH_Pipeline.IDX_STATISTICS,
                "statistics",
                "Statistical Analysis",
                "Compares conditions for each metric in the master tables, auto-selects the right test (parametric or non-parametric, paired, post-hoc), and writes p-values.",
                list("FLASH/Results/Tables/Project Summary/3D Objects.csv or Image Intensities.csv",
                        "FLASH/Results/Tables/Project Summary/Conditions.csv"),
                list("FLASH/Results/Tables/Project Summary/Statistics.csv"),
                list("Load master tables", "Group by condition", "Run tests", "Write Statistics.csv"),
                list("Too few samples per condition makes a metric skipped or p-values unstable.",
                        "Image-level rows are not automatically independent replicates; check aggregation granularity."),
                optionalImages(
                        "statistics",
                        image("statistics.jpg", "Compare conditions per metric",
                                "Compares conditions for each metric, automatically selecting the right test (parametric or non-parametric, paired, post-hoc), and writes p-values and significance to Statistics.csv.")));
    }

    private static AnalysisHelpTopic excelExportTopic() {
        return new AnalysisHelpTopic(
                FLASH_Pipeline.IDX_EXCEL_EXPORT,
                "excel-export",
                "Excel Summary Export",
                "Collects the master CSVs, conditions, and optional statistics into one shareable Summary.xlsx with a sheet per metric plus a methods appendix.",
                list("FLASH/Results/Tables/Project Summary/3D Objects.csv or Image Intensities.csv",
                        "FLASH/Results/Tables/Project Summary/Conditions.csv",
                        "FLASH/Results/Tables/Project Summary/Statistics.csv (for the Statistics sheet)"),
                list("FLASH/Results/Summary.xlsx"),
                list("Pick preset & sheets", "Collect CSVs", "Build workbook", "Save Summary.xlsx"),
                list("The Statistics sheet is stale or absent if you did not run Statistics after the latest aggregation.",
                        "Export packages existing values; it does not recompute anything."),
                optionalImages(
                        "excel-export",
                        image("excel-export.jpg", "CSVs into one workbook",
                                "Collects every results CSV into one shareable Summary.xlsx with multiple sheets: Experimental Conditions, Data Summary, per-metric sheets, Statistics, and a Methods Appendix.")));
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
