package flash.pipeline.results;

import flash.pipeline.image.NamedFilterLoader;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.naming.ChannelFilenameCodec;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

/** Writes macro-style per-channel Analysis Details for 3D Object Analysis. */
public final class ObjectAnalysisDetailsWriter {

    private ObjectAnalysisDetailsWriter() {}

    public static File analysisDetailsWriteDir(File projectDirectory) {
        if (projectDirectory == null) {
            throw new IllegalArgumentException("Project directory must not be null.");
        }
        return FlashProjectLayout.forDirectory(projectDirectory.getAbsolutePath())
                .objectAnalysisDetailsWriteDir();
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
        flash.pipeline.io.IoUtils.mustMkdirs(analysisDetailsDir);

        File out = new File(analysisDetailsDir, ChannelFilenameCodec.toSafe(channelName) + ".txt");
        String macroText = loadObjectFilterMacro(binDir, channelIndex1Based);

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
            w.write("// Model: Versatile (fluorescent nuclei)\n");
            w.write("// QC/Sanity Parameters: probThresh=" + formatValue(probThresh)
                    + ", nmsThresh=" + formatValue(nmsThresh)
                    + ", linking=" + formatValue(linkingMaxDistance)
                    + ", gapClosing=" + formatValue(gapClosingMaxDistance)
                    + ", frameGap=" + maxFrameGap
                    + ", area=" + formatRange(areaMin, areaMax)
                    + ", quality>=" + formatValue(qualityMin)
                    + ", intensity>=" + formatValue(intensityMin) + "\n");
            w.write("run(\"StarDist 3D\", \"input=" + channelName + "_stardist_input"
                    + " modelChoice='Versatile (fluorescent nuclei)'"
                    + " probThresh=" + probThresh
                    + " nmsThresh=" + nmsThresh + "\");\n");

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
        flash.pipeline.io.IoUtils.mustMkdirs(analysisDetailsDir);

        File out = new File(analysisDetailsDir, ChannelFilenameCodec.toSafe(channelName) + ".txt");
        String macroText = loadObjectFilterMacro(binDir, channelIndex1Based);

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
            w.write("// Model: " + modelToken + "\n");
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
                        + " --pretrained_model " + modelToken
                        + " --chan 1 --chan2 2 --channel_axis <derived>"
                        + " --diameter " + diameter
                        + " --flow_threshold " + flowThreshold
                        + " --cellprob_threshold " + cellprobThreshold
                        + " --do_3D --z_axis 0 --save_tif"
                        + (useGpu ? " --use_gpu" : "")
                        + "  // anisotropy derived from image calibration when available\n");
            } else {
                w.write("// python -m cellpose --image_path <stack.tif> --savedir <output_dir>"
                        + " --pretrained_model " + modelToken
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

        flash.pipeline.io.IoUtils.mustMkdirs(analysisDetailsDir);

        File out = new File(analysisDetailsDir, ChannelFilenameCodec.toSafe(channelName) + ".txt");
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

    private static String readFile(File file) throws Exception {
        byte[] bytes = Files.readAllBytes(file.toPath());
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String ensureTrailingNewline(String text) {
        if (text == null || text.isEmpty()) return "";
        return text.endsWith("\n") ? text : text + "\n";
    }

    private static String formatRange(double min, double max) {
        return formatValue(min) + "-" + formatValue(max);
    }

    private static String formatValue(double value) {
        if (Double.isInfinite(value)) return "Infinity";
        return String.valueOf(value);
    }
}
