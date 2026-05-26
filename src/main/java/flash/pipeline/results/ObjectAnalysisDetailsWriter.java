package flash.pipeline.results;

import flash.pipeline.image.NamedFilterLoader;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.naming.ChannelFilenameCodec;
import flash.pipeline.segmentation.SegmentationMethod;
import flash.pipeline.segmentation.SegmentationTokenParser;
import flash.pipeline.segmentation.catalog.ModelCatalog;
import flash.pipeline.segmentation.catalog.ModelCatalogIO;
import flash.pipeline.segmentation.catalog.ModelEntry;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Writes macro-style per-channel Analysis Details for 3D Object Analysis. */
public final class ObjectAnalysisDetailsWriter {
    public static final String FILENAME_PREFIX = "objects_";
    public static final String SEGMENTATION_MODELS_FILENAME = FILENAME_PREFIX + "segmentation_models.txt";
    private static final String MISSING_MODEL_ERROR = "Model not found in catalog at audit time";

    private ObjectAnalysisDetailsWriter() {}

    public static File analysisDetailsWriteDir(File projectDirectory) {
        if (projectDirectory == null) {
            throw new IllegalArgumentException("Project directory must not be null.");
        }
        return FlashProjectLayout.forDirectory(projectDirectory.getAbsolutePath())
                .analysisDetailsWriteDir();
    }

    public static String detailsFileName(String channelName) {
        return FILENAME_PREFIX + ChannelFilenameCodec.toSafe(channelName) + ".txt";
    }

    public static File segmentationModelsReportFile(File analysisDetailsDir) {
        if (analysisDetailsDir == null) {
            throw new IllegalArgumentException("Analysis details directory must not be null.");
        }
        return new File(analysisDetailsDir, SEGMENTATION_MODELS_FILENAME);
    }

    public static void writeSegmentationModelsReport(
            File analysisDetailsDir,
            File projectDirectory,
            List<String> channelNames,
            List<String> segmentationMethodTokens
    ) throws Exception {
        if (projectDirectory == null) {
            throw new IllegalArgumentException("Project directory must not be null.");
        }
        ModelCatalog catalog = ModelCatalogIO.read(projectDirectory.toPath().toAbsolutePath().normalize());
        writeSegmentationModelsReport(analysisDetailsDir, catalog, channelNames, segmentationMethodTokens);
    }

    public static void writeSegmentationModelsReport(
            File analysisDetailsDir,
            ModelCatalog catalog,
            String[] channelNames,
            String[] segmentationMethodTokens
    ) throws Exception {
        writeSegmentationModelsReport(
                analysisDetailsDir,
                catalog,
                channelNames == null ? null : Arrays.asList(channelNames),
                segmentationMethodTokens == null ? null : Arrays.asList(segmentationMethodTokens));
    }

    public static void writeSegmentationModelsReport(
            File analysisDetailsDir,
            ModelCatalog catalog,
            List<String> channelNames,
            List<String> segmentationMethodTokens
    ) throws Exception {
        if (analysisDetailsDir == null) {
            throw new IllegalArgumentException("Analysis details directory must not be null.");
        }

        List<ModelAuditRow> rows = modelAuditRows(catalog, channelNames, segmentationMethodTokens);
        File out = segmentationModelsReportFile(analysisDetailsDir);
        if (rows.isEmpty()) {
            Files.deleteIfExists(out.toPath());
            return;
        }

        flash.pipeline.io.IoUtils.mustMkdirs(analysisDetailsDir);
        try (Writer w = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8)) {
            w.write("<segmentation_models>\n");
            for (int i = 0; i < rows.size(); i++) {
                if (i > 0) {
                    w.write("\n");
                }
                writeModelAuditRow(w, rows.get(i));
            }
            w.write("</segmentation_models>\n");
        }
    }

    public static void writePerChannel(
            File analysisDetailsDir,
            File binDir,
            String channelName,
            int channelIndex1Based,
            String thresholdToken,
            String particleSizeToken,
            String[] allChannelNames
    ) throws Exception {
        writePerChannel(analysisDetailsDir, binDir, channelName, channelIndex1Based,
                thresholdToken, particleSizeToken, allChannelNames, false, null);
    }

    /**
     * Writes analysis details for a StarDist 3D segmented channel.
     * Records the applied filter macro, segmentation method, and StarDist QC parameters.
     */
    public static void writeStarDistPerChannel(
            File analysisDetailsDir,
            File binDir,
            String channelName,
            int channelIndex1Based,
            double probThresh,
            double nmsThresh,
            double linkingMaxDistance,
            double gapClosingMaxDistance,
            int maxFrameGap,
            double areaMin,
            double areaMax,
            double qualityMin,
            double intensityMin,
            String[] allChannelNames,
            boolean spatialAnalysis,
            Map<String, Double> colocThresholds
    ) throws Exception {
        writeStarDistPerChannel(analysisDetailsDir, binDir, channelName, channelIndex1Based,
                null, probThresh, nmsThresh, linkingMaxDistance, gapClosingMaxDistance,
                maxFrameGap, areaMin, areaMax, qualityMin, intensityMin, allChannelNames,
                spatialAnalysis, colocThresholds);
    }

    public static void writeStarDistPerChannel(
            File analysisDetailsDir,
            File binDir,
            String channelName,
            int channelIndex1Based,
            ModelEntry modelEntry,
            double probThresh,
            double nmsThresh,
            double linkingMaxDistance,
            double gapClosingMaxDistance,
            int maxFrameGap,
            double areaMin,
            double areaMax,
            double qualityMin,
            double intensityMin,
            String[] allChannelNames,
            boolean spatialAnalysis,
            Map<String, Double> colocThresholds
    ) throws Exception {
        flash.pipeline.io.IoUtils.mustMkdirs(analysisDetailsDir);

        File out = new File(analysisDetailsDir, detailsFileName(channelName));
        String macroText = loadObjectFilterMacro(binDir, channelIndex1Based);
        String modelSummary = modelSummary(modelEntry,
                "Versatile (fluorescent nuclei)",
                SegmentationMethod.DEFAULT_STARDIST_MODEL_KEY,
                "");
        String sourceSummary = sourceSummary(modelEntry);
        String fijiModelChoice = starDistFijiModelChoice(modelEntry, "Versatile (fluorescent nuclei)");
        String modelFileReference = modelFileReference(modelEntry);

        try (Writer w = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8)) {
            w.write("\n");
            w.write("<Filter Macro>\n");
            w.write("selectImage(" + channelName + "_stardist_input);\n");
            w.write(macroText);
            if (!macroText.endsWith("\n")) w.write("\n");
            w.write("</Filter Macro>\n");

            w.write("\n");
            w.write("<Segmentation Method>\n");
            w.write("StarDist 3D\n");
            w.write("</Segmentation Method>\n");

            w.write("\n");
            w.write("<Analysis Macro>\n");
            w.write("// Segmentation Method: StarDist 3D\n");
            w.write("// Model: " + modelSummary + "\n");
            if (!sourceSummary.isEmpty()) {
                w.write("// Model Source: " + sourceSummary + "\n");
            }
            w.write("// QC/Sanity Parameters: probThresh=" + formatValue(probThresh)
                    + ", nmsThresh=" + formatValue(nmsThresh)
                    + ", linking=" + formatValue(linkingMaxDistance)
                    + ", gapClosing=" + formatValue(gapClosingMaxDistance)
                    + ", frameGap=" + maxFrameGap
                    + ", area=" + formatRange(areaMin, areaMax)
                    + ", quality>=" + formatValue(qualityMin)
                    + ", intensity>=" + formatValue(intensityMin) + "\n");
            if (!fijiModelChoice.isEmpty()) {
                w.write("run(\"StarDist 3D\", \"input=" + channelName + "_stardist_input"
                        + " modelChoice='" + macroQuote(fijiModelChoice) + "'"
                        + " probThresh=" + probThresh
                        + " nmsThresh=" + nmsThresh + "\");\n");
            } else {
                w.write("// run(\"StarDist 3D\", \"input=" + channelName + "_stardist_input"
                        + " modelFile='" + macroQuote(modelFileReference) + "'"
                        + " probThresh=" + probThresh
                        + " nmsThresh=" + nmsThresh + "\");\n");
            }

            if (allChannelNames != null) {
                for (String other : allChannelNames) {
                    if (other == null || other.equals(channelName)) continue;
                    w.write("run(\"3D MultiColoc\", \"image_a=" + channelName + "_objects image_b=" + other + "_objects);\n");
                }
            }

            if (spatialAnalysis && allChannelNames != null) {
                w.write("// Spatial Analysis\n");
                for (String other : allChannelNames) {
                    if (other == null || other.equals(channelName)) continue;
                    w.write("run(\"Spatial Analysis\", \"nearest_neighbor " + channelName + " <-> " + other + "\");\n");
                }
            }

            w.write("</Analysis Macro>\n");

            if (spatialAnalysis && colocThresholds != null) {
                double threshold = colocThresholds.containsKey(channelName)
                        ? colocThresholds.get(channelName) : 30.0;
                w.write("\n");
                w.write("<Colocalisation Threshold (%)>\n");
                w.write(String.valueOf((int) threshold));
                w.write("\n</Colocalisation Threshold (%)>\n");
            }
        }
    }

    public static void writeCellposePerChannel(
            File analysisDetailsDir,
            File binDir,
            String channelName,
            int channelIndex1Based,
            String modelToken,
            double diameter,
            double flowThreshold,
            double cellprobThreshold,
            boolean useGpu,
            String companionChannelName,
            String[] allChannelNames,
            boolean spatialAnalysis,
            Map<String, Double> colocThresholds
    ) throws Exception {
        writeCellposePerChannel(analysisDetailsDir, binDir, channelName, channelIndex1Based,
                null, modelToken, diameter, flowThreshold, cellprobThreshold, useGpu,
                companionChannelName, allChannelNames, spatialAnalysis, colocThresholds);
    }

    public static void writeCellposePerChannel(
            File analysisDetailsDir,
            File binDir,
            String channelName,
            int channelIndex1Based,
            ModelEntry modelEntry,
            String modelToken,
            double diameter,
            double flowThreshold,
            double cellprobThreshold,
            boolean useGpu,
            String companionChannelName,
            String[] allChannelNames,
            boolean spatialAnalysis,
            Map<String, Double> colocThresholds
    ) throws Exception {
        flash.pipeline.io.IoUtils.mustMkdirs(analysisDetailsDir);

        File out = new File(analysisDetailsDir, detailsFileName(channelName));
        String macroText = loadObjectFilterMacro(binDir, channelIndex1Based);
        String modelSummary = modelSummary(modelEntry,
                modelToken,
                SegmentationMethod.canonicalCellposeModelKey(modelToken),
                "");
        String sourceSummary = sourceSummary(modelEntry);
        String cliModelToken = cellposeCliModelToken(modelEntry, modelToken);

        try (Writer w = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8)) {
            w.write("\n");
            w.write("<Filter Macro>\n");
            w.write("selectImage(" + channelName + "_cellpose_input);\n");
            w.write(macroText);
            if (!macroText.endsWith("\n")) w.write("\n");
            w.write("</Filter Macro>\n");

            w.write("\n");
            w.write("<Segmentation Method>\n");
            w.write("Cellpose\n");
            w.write("</Segmentation Method>\n");

            w.write("\n");
            w.write("<Analysis Macro>\n");
            w.write("// Segmentation Method: Cellpose\n");
            w.write("// Model: " + modelSummary + "\n");
            if (!sourceSummary.isEmpty()) {
                w.write("// Model Source: " + sourceSummary + "\n");
            }
            w.write("// Parameters: diameter=" + formatValue(diameter)
                    + ", flowThreshold=" + formatValue(flowThreshold)
                    + ", cellprobThreshold=" + formatValue(cellprobThreshold)
                    + ", useGpu=" + useGpu
                    + ", companionChannel=" + (companionChannelName == null || companionChannelName.trim().isEmpty()
                    ? "None" : companionChannelName)
                    + "\n");
            w.write("// External CLI concept (native 3D stack path):\n");
            if (companionChannelName != null && !companionChannelName.trim().isEmpty()) {
                w.write("// Companion channel: " + companionChannelName
                        + " (runner merges the filtered primary and companion stacks before calling Cellpose)\n");
                w.write("// python -m cellpose --image_path <stack.tif> --savedir <output_dir>"
                        + " --pretrained_model " + cliModelToken
                        + " --chan 1 --chan2 2 --channel_axis <derived>"
                        + " --diameter " + diameter
                        + " --flow_threshold " + flowThreshold
                        + " --cellprob_threshold " + cellprobThreshold
                        + " --do_3D --z_axis 0 --save_tif"
                        + (useGpu ? " --use_gpu" : "")
                        + "  // anisotropy derived from image calibration when available\n");
            } else {
                w.write("// python -m cellpose --image_path <stack.tif> --savedir <output_dir>"
                        + " --pretrained_model " + cliModelToken
                        + " --chan 0 --diameter " + diameter
                        + " --flow_threshold " + flowThreshold
                        + " --cellprob_threshold " + cellprobThreshold
                        + " --do_3D --z_axis 0 --save_tif"
                        + (useGpu ? " --use_gpu" : "")
                        + "  // anisotropy derived from image calibration when available\n");
            }

            if (allChannelNames != null) {
                for (String other : allChannelNames) {
                    if (other == null || other.equals(channelName)) continue;
                    w.write("run(\"3D MultiColoc\", \"image_a=" + channelName + "_objects image_b=" + other + "_objects);\n");
                }
            }

            if (spatialAnalysis && allChannelNames != null) {
                w.write("// Spatial Analysis\n");
                for (String other : allChannelNames) {
                    if (other == null || other.equals(channelName)) continue;
                    w.write("run(\"Spatial Analysis\", \"nearest_neighbor " + channelName + " <-> " + other + "\");\n");
                }
            }

            w.write("</Analysis Macro>\n");

            if (spatialAnalysis && colocThresholds != null) {
                double threshold = colocThresholds.containsKey(channelName)
                        ? colocThresholds.get(channelName) : 30.0;
                w.write("\n");
                w.write("<Colocalisation Threshold (%)>\n");
                w.write(String.valueOf((int) threshold));
                w.write("\n</Colocalisation Threshold (%)>\n");
            }
        }
    }

    public static void writePerChannel(
            File analysisDetailsDir,
            File binDir,
            String channelName,
            int channelIndex1Based,
            String thresholdToken,
            String particleSizeToken,
            String[] allChannelNames,
            boolean spatialAnalysis,
            Map<String, Double> colocThresholds
    ) throws Exception {
        writePerChannel(analysisDetailsDir, binDir, channelName, channelIndex1Based,
                thresholdToken, particleSizeToken, allChannelNames, spatialAnalysis,
                colocThresholds, null, null);
    }

    public static void writePerChannel(
            File analysisDetailsDir,
            File binDir,
            String channelName,
            int channelIndex1Based,
            String thresholdToken,
            String particleSizeToken,
            String[] allChannelNames,
            boolean spatialAnalysis,
            Map<String, Double> colocThresholds,
            ModelEntry modelEntry,
            SegmentationMethod segmentationMethod
    ) throws Exception {

        flash.pipeline.io.IoUtils.mustMkdirs(analysisDetailsDir);

        File out = new File(analysisDetailsDir, detailsFileName(channelName));
        String macroText = loadObjectFilterMacro(binDir, channelIndex1Based);

        String[] sizeParts = particleSizeToken == null ? new String[] {"100", "Infinity"} : particleSizeToken.split("-");
        String minSize = sizeParts.length > 0 ? sizeParts[0] : "100";
        String maxSize = sizeParts.length > 1 ? sizeParts[1] : "Infinity";

        try (Writer w = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8)) {
            w.write("\n");
            w.write("<Filter Macro>\n");
            w.write(macroText);
            if (!macroText.endsWith("\n")) w.write("\n");
            w.write("</Filter Macro>\n");

            w.write("\n");
            w.write("<Analysis Macro>\n");
            if (segmentationMethod != null && segmentationMethod.isTrainedRf()) {
                w.write("// Segmentation Method: Trained RF\n");
                w.write("// Model: " + modelSummary(modelEntry,
                        SegmentationMethod.trainedRfModelKey(segmentationMethod),
                        SegmentationMethod.trainedRfModelKey(segmentationMethod),
                        "Trained RF: ") + "\n");
                String sourceSummary = sourceSummary(modelEntry);
                if (!sourceSummary.isEmpty()) {
                    w.write("// Model Source: " + sourceSummary + "\n");
                }
                w.write("// Base Segmentation Method: "
                        + SegmentationTokenParser.format(SegmentationMethod.trainedRfBase(segmentationMethod))
                        + "\n");
            }
            w.write("selectImage(" + channelName + "_filtered);\n");
            w.write("run(\"3D OC Options\", \"volume surface nb_of_obj._voxels nb_of_surf._voxels integrated_density mean_gray_value std_dev_gray_value median_gray_value minimum_gray_value maximum_gray_value centroid mean_distance_to_surface std_dev_distance_to_surface median_distance_to_surface centre_of_mass bounding_box show_masked_image_(redirection_requiered) dots_size=5 font_size=10 show_numbers white_numbers store_results_within_a_table_named_after_the_image_(macro_friendly) redirect_to=[" + channelName + "_unfiltered]\");\n");
            w.write("run(\"3D Objects Counter\", \"threshold=" + thresholdToken + " slice=6 min.=" + minSize + " max.=" + maxSize + " objects statistics summary\");\n");

            if (allChannelNames != null) {
                for (String other : allChannelNames) {
                    if (other == null || other.equals(channelName)) continue;
                    w.write("run(\"3D MultiColoc\", \"image_a=" + channelName + "_objects image_b=" + other + "_objects);\n");
                }
            }

            if (spatialAnalysis && allChannelNames != null) {
                w.write("// Spatial Analysis\n");
                for (String other : allChannelNames) {
                    if (other == null || other.equals(channelName)) continue;
                    w.write("run(\"Spatial Analysis\", \"nearest_neighbor " + channelName + " <-> " + other + "\");\n");
                }
            }

            w.write("</Analysis Macro>\n");

            w.write("\n");
            w.write("<Particle Size (n Voxels, min-max)>\n");
            w.write(particleSizeToken == null ? "" : particleSizeToken);
            w.write("\n</Particle Size (n Voxels, min-max)>\n");

            w.write("\n");
            w.write("<Threshold>\n");
            w.write(thresholdToken == null ? "" : thresholdToken);
            w.write("\n</Threshold>\n");

            if (spatialAnalysis && colocThresholds != null) {
                double threshold = colocThresholds.containsKey(channelName)
                        ? colocThresholds.get(channelName) : 30.0;
                w.write("\n");
                w.write("<Colocalisation Threshold (%)>\n");
                w.write(String.valueOf((int) threshold));
                w.write("\n</Colocalisation Threshold (%)>\n");
            }
        }

    }

    private static String loadObjectFilterMacro(File binDir, int channelIndex1Based) throws Exception {
        if (binDir != null) {
            File filterFile = new File(binDir, "C" + channelIndex1Based + "_Filters.ijm");
            if (filterFile.exists()) {
                return ensureTrailingNewline(readFile(filterFile));
            }
        }

        return ensureTrailingNewline(NamedFilterLoader.loadDefaultFilter());
    }

    private static String modelSummary(ModelEntry entry,
                                       String fallbackDisplayName,
                                       String fallbackModelKey,
                                       String prefix) {
        String display = modelDisplayName(entry, fallbackDisplayName, fallbackModelKey);
        String key = entry == null ? cleanLine(fallbackModelKey) : cleanLine(entry.modelKey);
        StringBuilder sb = new StringBuilder();
        if (prefix != null) {
            sb.append(prefix);
        }
        sb.append(display);
        if (!key.isEmpty()) {
            sb.append(" (key=").append(key).append(")");
        }
        return sb.toString();
    }

    private static String modelDisplayName(ModelEntry entry,
                                           String fallbackDisplayName,
                                           String fallbackModelKey) {
        if (entry != null) {
            String name = cleanLine(entry.name);
            if (!name.isEmpty()) {
                return name;
            }
            String key = cleanLine(entry.modelKey);
            if (!key.isEmpty()) {
                return key;
            }
        }
        String fallback = cleanLine(fallbackDisplayName);
        if (!fallback.isEmpty()) {
            return fallback;
        }
        fallback = cleanLine(fallbackModelKey);
        return fallback.isEmpty() ? "Unknown model" : fallback;
    }

    private static String sourceSummary(ModelEntry entry) {
        if (entry == null || entry.source == null) {
            return "";
        }
        List<String> parts = new ArrayList<String>();
        parts.add(sourceLabel(entry.source));
        if (entry.pretrainedModel.isPresent()) {
            parts.add("pretrained name=" + cleanLine(entry.pretrainedModel.get()));
        }
        if (entry.fijiModelChoice.isPresent()) {
            parts.add("Fiji model choice=" + cleanLine(entry.fijiModelChoice.get()));
        }
        if (entry.filePath.isPresent()) {
            parts.add("path=" + publicPathReference(entry.filePath.get()));
        } else if (entry.resourcePath.isPresent()) {
            parts.add("resource=" + cleanLine(entry.resourcePath.get()));
        }
        if (entry.base.isPresent()) {
            parts.add("base=" + cleanLine(entry.base.get()));
        }
        return join(parts, "; ");
    }

    private static String sourceLabel(ModelEntry.Source source) {
        if (source == ModelEntry.Source.STOCK_RESOURCE) {
            return "Fiji stock resource";
        }
        if (source == ModelEntry.Source.STOCK_BUILTIN) {
            return "Fiji built-in";
        }
        if (source == ModelEntry.Source.USER_IMPORTED) {
            return "Custom import";
        }
        if (source == ModelEntry.Source.USER_TRAINED) {
            return "User trained";
        }
        return source.jsonValue();
    }

    private static String starDistFijiModelChoice(ModelEntry entry, String fallback) {
        if (entry == null) {
            return cleanLine(fallback);
        }
        if (entry.fijiModelChoice.isPresent()) {
            return cleanLine(entry.fijiModelChoice.get());
        }
        return entry.isStock() ? modelDisplayName(entry, fallback, entry.modelKey) : "";
    }

    private static String modelFileReference(ModelEntry entry) {
        if (entry == null) {
            return "";
        }
        if (entry.filePath.isPresent()) {
            return publicPathReference(entry.filePath.get());
        }
        if (entry.resourcePath.isPresent()) {
            return cleanLine(entry.resourcePath.get());
        }
        return cleanLine(entry.modelKey);
    }

    private static String cellposeCliModelToken(ModelEntry entry, String fallbackModelToken) {
        if (entry != null) {
            if (entry.pretrainedModel.isPresent()) {
                return cleanLine(entry.pretrainedModel.get());
            }
            if (entry.filePath.isPresent()) {
                return publicPathReference(entry.filePath.get());
            }
        }
        return cleanLine(fallbackModelToken);
    }

    private static String publicPathReference(String path) {
        String clean = cleanLine(path);
        if (clean.isEmpty()) {
            return "";
        }
        String normalized = clean.replace('\\', '/');
        boolean absolute = normalized.startsWith("/")
                || normalized.startsWith("//")
                || normalized.matches("^[A-Za-z]:/.*");
        if (!absolute) {
            return normalized;
        }
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 && slash + 1 < normalized.length()
                ? normalized.substring(slash + 1)
                : "model";
        return "${MACHINE_LOCAL}/" + name;
    }

    private static String macroQuote(String value) {
        return cleanLine(value).replace("'", "\\'");
    }

    private static String readFile(File file) throws Exception {
        byte[] bytes = Files.readAllBytes(file.toPath());
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String ensureTrailingNewline(String text) {
        if (text == null || text.isEmpty()) return "";
        return text.endsWith("\n") ? text : text + "\n";
    }

    private static List<ModelAuditRow> modelAuditRows(ModelCatalog catalog,
                                                      List<String> channelNames,
                                                      List<String> methodTokens) {
        List<ModelAuditRow> rows = new ArrayList<ModelAuditRow>();
        if (channelNames == null || methodTokens == null) {
            return rows;
        }
        int count = Math.min(channelNames.size(), methodTokens.size());
        for (int i = 0; i < count; i++) {
            String channelName = channelNames.get(i);
            String modelKey = modelKeyForToken(methodTokens.get(i));
            if (modelKey.isEmpty()) {
                continue;
            }
            Optional<ModelEntry> entry = catalog == null
                    ? Optional.<ModelEntry>empty()
                    : catalog.get(modelKey);
            rows.add(new ModelAuditRow(channelName, modelKey,
                    entry.isPresent() ? entry.get() : null,
                    entry.isPresent() ? null : MISSING_MODEL_ERROR));
        }
        return rows;
    }

    private static String modelKeyForToken(String token) {
        SegmentationMethod method = SegmentationTokenParser.parseLenient(token);
        if (method.isStarDist()) {
            return safe(SegmentationMethod.starDistModelKey(method));
        }
        if (method.isCellpose()) {
            return safe(SegmentationMethod.cellposeModelKey(method));
        }
        if (method.isTrainedRf()) {
            return safe(SegmentationMethod.trainedRfModelKey(method));
        }
        return "";
    }

    private static void writeModelAuditRow(Writer w, ModelAuditRow row) throws Exception {
        w.write("channel=" + cleanLine(row.channelName) + "\n");
        if (row.entry == null) {
            w.write("model_key=" + cleanLine(row.modelKey) + "\n");
            w.write("error=" + cleanLine(row.error) + "\n");
            return;
        }

        ModelEntry entry = row.entry;
        w.write("model_name=" + cleanLine(entry.name) + "\n");
        w.write("model_key=" + cleanLine(entry.modelKey) + "\n");
        if (entry.engine != null) {
            w.write("engine=" + entry.engine.jsonValue() + "\n");
        }
        if (entry.source != null) {
            w.write("source=" + entry.source.jsonValue() + "\n");
        }

        if (entry.engine == ModelEntry.Engine.SMILE_RF) {
            writeIfPresent(w, "trained_at", metadataString(entry, "trainedAt", "trained_at"));
            writeIfPresent(w, "cross_val_accuracy",
                    metadataString(entry, "crossValAccuracy", "cross_val_accuracy"));
            writeIfPresent(w, "quality_flag",
                    metadataString(entry, "qualityFlag", "quality_flag", "quality"));
            writeIfPresent(w, "feature_importance_top3", featureImportanceTop3(entry));
        }

        if (isUserImportedDeepModel(entry)) {
            writeIfPresent(w, "source_notebook",
                    metadataString(entry, "sourceNotebook", "source_notebook", "recommendedNotebook"));
        }

        writeIfPresent(w, "engine_version",
                metadataString(entry, "engineVersion", "engine_version"));
    }

    private static boolean isUserImportedDeepModel(ModelEntry entry) {
        return entry != null
                && entry.source == ModelEntry.Source.USER_IMPORTED
                && (entry.engine == ModelEntry.Engine.STARDIST
                || entry.engine == ModelEntry.Engine.CELLPOSE);
    }

    private static void writeIfPresent(Writer w, String key, String value) throws Exception {
        String clean = cleanLine(value);
        if (!clean.isEmpty()) {
            w.write(key + "=" + clean + "\n");
        }
    }

    private static String metadataString(ModelEntry entry, String... keys) {
        Object value = metadataValue(entry, keys);
        return value == null ? "" : String.valueOf(value);
    }

    private static Object metadataValue(ModelEntry entry, String... keys) {
        if (entry == null || entry.metadata == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (entry.metadata.containsKey(key)) {
                return entry.metadata.get(key);
            }
        }
        for (Map.Entry<String, Object> item : entry.metadata.entrySet()) {
            String normalized = normalizeMetadataKey(item.getKey());
            for (String key : keys) {
                if (normalized.equals(normalizeMetadataKey(key))) {
                    return item.getValue();
                }
            }
        }
        return null;
    }

    private static String featureImportanceTop3(ModelEntry entry) {
        Object direct = metadataValue(entry, "featureImportanceTop3", "feature_importance_top3");
        String directText = directFeatureList(direct);
        if (!directText.isEmpty()) {
            return directText;
        }

        Object rawImportance = metadataValue(entry,
                "featureImportance", "feature_importance", "featureImportances", "feature_importances");
        List<FeatureWeight> weights = featureWeightsFromMap(rawImportance);
        if (weights.isEmpty()) {
            weights = featureWeightsFromLists(
                    metadataValue(entry, "featureNames", "feature_names"),
                    rawImportance);
        }
        if (weights.isEmpty()) {
            weights = featureWeightsFromObjectList(rawImportance);
        }
        if (weights.isEmpty()) {
            return "";
        }

        Collections.sort(weights, new Comparator<FeatureWeight>() {
            @Override
            public int compare(FeatureWeight left, FeatureWeight right) {
                int byWeight = Double.compare(right.weight, left.weight);
                return byWeight != 0 ? byWeight : left.name.compareTo(right.name);
            }
        });

        List<String> names = new ArrayList<String>();
        for (FeatureWeight weight : weights) {
            if (!weight.name.isEmpty()) {
                names.add(weight.name);
            }
            if (names.size() == 3) {
                break;
            }
        }
        return join(names, ",");
    }

    private static String directFeatureList(Object direct) {
        if (direct == null) {
            return "";
        }
        if (direct instanceof String) {
            return cleanLine((String) direct);
        }
        List<String> values = stringList(direct);
        if (values.isEmpty()) {
            return cleanLine(String.valueOf(direct));
        }
        List<String> top = new ArrayList<String>();
        for (String value : values) {
            if (!value.isEmpty()) {
                top.add(value);
            }
            if (top.size() == 3) {
                break;
            }
        }
        return join(top, ",");
    }

    private static List<FeatureWeight> featureWeightsFromMap(Object rawImportance) {
        List<FeatureWeight> weights = new ArrayList<FeatureWeight>();
        if (!(rawImportance instanceof Map)) {
            return weights;
        }
        for (Map.Entry<?, ?> item : ((Map<?, ?>) rawImportance).entrySet()) {
            String name = cleanLine(item.getKey() == null ? "" : String.valueOf(item.getKey()));
            Double value = asDouble(item.getValue());
            if (!name.isEmpty() && value != null) {
                weights.add(new FeatureWeight(name, value.doubleValue()));
            }
        }
        return weights;
    }

    private static List<FeatureWeight> featureWeightsFromLists(Object rawNames, Object rawImportance) {
        List<FeatureWeight> weights = new ArrayList<FeatureWeight>();
        List<String> names = stringList(rawNames);
        List<Object> values = objectList(rawImportance);
        int count = Math.min(names.size(), values.size());
        for (int i = 0; i < count; i++) {
            Double weight = asDouble(values.get(i));
            String name = cleanLine(names.get(i));
            if (!name.isEmpty() && weight != null) {
                weights.add(new FeatureWeight(name, weight.doubleValue()));
            }
        }
        return weights;
    }

    @SuppressWarnings("unchecked")
    private static List<FeatureWeight> featureWeightsFromObjectList(Object rawImportance) {
        List<FeatureWeight> weights = new ArrayList<FeatureWeight>();
        for (Object item : objectList(rawImportance)) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) item).entrySet()) {
                if (entry.getKey() != null) {
                    row.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            String name = cleanLine(firstString(row, "name", "feature", "featureName", "feature_name"));
            Double weight = asDouble(firstValue(row, "weight", "importance", "value"));
            if (!name.isEmpty() && weight != null) {
                weights.add(new FeatureWeight(name, weight.doubleValue()));
            }
        }
        return weights;
    }

    private static String firstString(Map<String, Object> values, String... keys) {
        Object value = firstValue(values, keys);
        return value == null ? "" : String.valueOf(value);
    }

    private static Object firstValue(Map<String, Object> values, String... keys) {
        if (values == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (values.containsKey(key)) {
                return values.get(key);
            }
        }
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String normalized = normalizeMetadataKey(entry.getKey());
            for (String key : keys) {
                if (normalized.equals(normalizeMetadataKey(key))) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private static List<String> stringList(Object raw) {
        List<String> out = new ArrayList<String>();
        for (Object item : objectList(raw)) {
            String value = cleanLine(item == null ? "" : String.valueOf(item));
            if (!value.isEmpty()) {
                out.add(value);
            }
        }
        return out;
    }

    private static List<Object> objectList(Object raw) {
        List<Object> out = new ArrayList<Object>();
        if (raw == null) {
            return out;
        }
        if (raw instanceof List) {
            out.addAll((List<?>) raw);
            return out;
        }
        if (raw.getClass().isArray()) {
            int length = Array.getLength(raw);
            for (int i = 0; i < length; i++) {
                out.add(Array.get(raw, i));
            }
        }
        return out;
    }

    private static Double asDouble(Object value) {
        if (value instanceof Number) {
            double parsed = ((Number) value).doubleValue();
            return Double.isFinite(parsed) ? Double.valueOf(parsed) : null;
        }
        if (value != null) {
            try {
                double parsed = Double.parseDouble(String.valueOf(value).trim());
                return Double.isFinite(parsed) ? Double.valueOf(parsed) : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String normalizeMetadataKey(String key) {
        return key == null ? "" : key.replace("_", "").replace("-", "")
                .toLowerCase(java.util.Locale.ROOT);
    }

    private static String join(List<String> values, String separator) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(separator);
            }
            sb.append(value);
        }
        return sb.toString();
    }

    private static String cleanLine(String value) {
        return safe(value).replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String formatRange(double min, double max) {
        return formatValue(min) + "-" + formatValue(max);
    }

    private static String formatValue(double value) {
        if (Double.isInfinite(value)) return "Infinity";
        return String.valueOf(value);
    }

    private static final class ModelAuditRow {
        final String channelName;
        final String modelKey;
        final ModelEntry entry;
        final String error;

        ModelAuditRow(String channelName, String modelKey, ModelEntry entry, String error) {
            this.channelName = channelName == null ? "" : channelName;
            this.modelKey = modelKey == null ? "" : modelKey;
            this.entry = entry;
            this.error = error;
        }
    }

    private static final class FeatureWeight {
        final String name;
        final double weight;

        FeatureWeight(String name, double weight) {
            this.name = name == null ? "" : name;
            this.weight = weight;
        }
    }
}
