package flash.pipeline.results;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.io.IoUtils;
import flash.pipeline.presentation.PresentationTileConfig;
import flash.pipeline.representative.RepresentativeFigureConfig;
import flash.pipeline.representative.RepresentativeSelection;
import flash.pipeline.representative.RepresentativeSeries;

import java.awt.Color;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Writes the human-readable Analysis Details record for Representative Figure.
 */
public final class RepresentativeFigureDetailsWriter {

    public static final String FILE_NAME = "representative_figure.txt";

    private RepresentativeFigureDetailsWriter() {
    }

    public static File analysisDetailsWriteDir(File projectDirectory) {
        if (projectDirectory == null) {
            throw new IllegalArgumentException("Project directory must not be null.");
        }
        return FlashProjectLayout.forDirectory(projectDirectory.getAbsolutePath())
                .analysisDetailsWriteDir();
    }

    public static File write(File projectDirectory,
                             RepresentativeFigureConfig config,
                             BinConfig setupConfig,
                             File outputPng) throws Exception {
        File dir = analysisDetailsWriteDir(projectDirectory);
        IoUtils.mustMkdirs(dir);
        File out = new File(dir, FILE_NAME);
        File tmp = File.createTempFile(out.getName(), ".tmp", dir);
        boolean moved = false;
        try {
            try (Writer w = new OutputStreamWriter(
                    new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
                writeDetails(w, config, setupConfig, outputPng);
            }
            IoUtils.commitReplacingSmallFile(tmp.toPath(), out.toPath());
            moved = true;
            return out;
        } finally {
            if (!moved) {
                Files.deleteIfExists(tmp.toPath());
            }
        }
    }

    private static void writeDetails(Writer w,
                                     RepresentativeFigureConfig config,
                                     BinConfig setupConfig,
                                     File outputPng) throws Exception {
        RepresentativeFigureConfig safeConfig =
                config == null ? new RepresentativeFigureConfig() : config;
        BinConfig safeSetup = setupConfig == null ? new BinConfig() : setupConfig;

        w.write("// Representative Image Figure\n");
        w.write("Output PNG: " + path(outputPng) + "\n");
        w.write("Statistic: " + (safeConfig.statistic == null
                ? "" : safeConfig.statistic.label()) + "\n");
        if (safeConfig.existingResult != null) {
            w.write("Existing result: " + safeConfig.existingResult.relativePath
                    + " :: " + safeConfig.existingResult.columnName + "\n");
        }

        w.write("\n<Representative Series>\n");
        RepresentativeSelection selection = safeConfig.selection;
        if (selection == null || selection.asMap().isEmpty()) {
            w.write("No representative series recorded.\n");
        } else {
            for (Map.Entry<String, RepresentativeSeries> entry : selection.asMap().entrySet()) {
                RepresentativeSeries series = entry.getValue();
                w.write("Condition: " + entry.getKey() + "\n");
                if (series != null) {
                    w.write("  Role: representative\n");
                    w.write("  Series ID: " + series.id() + "\n");
                    w.write("  Series index: " + series.seriesIndex() + "\n");
                    w.write("  Series number: " + series.seriesNumber() + "\n");
                    w.write("  Series name: " + series.seriesName() + "\n");
                    w.write("  Animal: " + series.animal() + "\n");
                    w.write("  Hemisphere: " + series.hemisphere() + "\n");
                    w.write("  Region: " + series.region() + "\n");
                    w.write("  Source: " + path(series.sourcePath()) + "\n");
                }
            }
        }
        w.write("</Representative Series>\n");

        w.write("\n<Display Ranges>\n");
        TreeSet<Integer> channelIndexes = collectChannelIndexes(safeConfig, safeSetup);
        if (channelIndexes.isEmpty()) {
            w.write("No channel display ranges recorded.\n");
        } else {
            for (Integer index : channelIndexes) {
                int channelIndex = index == null ? -1 : index.intValue();
                w.write("Channel " + (channelIndex + 1)
                        + " (" + channelName(channelIndex, safeConfig, safeSetup) + "): "
                        + rangeSummary(channelIndex, safeConfig, safeSetup) + "\n");
            }
        }
        w.write("</Display Ranges>\n");

        w.write("\n<Condition Layout>\n");
        if (safeConfig.layout == null) {
            w.write("No layout recorded.\n");
        } else {
            List<List<String>> rows = safeConfig.layout.rows();
            for (int i = 0; i < rows.size(); i++) {
                w.write("Row " + (i + 1) + ": " + join(rows.get(i)) + "\n");
            }
            w.write("Columns: " + safeConfig.layout.maxColumnCount() + "\n");
            w.write("Axis assignment: condition_rows\n");
        }
        w.write("</Condition Layout>\n");

        writeTileConfig(w, safeConfig.tileConfig);
    }

    private static void writeTileConfig(Writer w, PresentationTileConfig config) throws Exception {
        w.write("\n<Presentation Tile Config>\n");
        if (config == null) {
            w.write("No tile configuration recorded.\n");
            w.write("</Presentation Tile Config>\n");
            return;
        }
        w.write("Create overview tile: " + config.createOverviewTile() + "\n");
        w.write("Annotate overview tile: " + config.annotateOverviewTile() + "\n");
        w.write("Annotate individual images: " + config.annotateIndividualImages() + "\n");
        w.write("Rows by: " + config.groupRowsBy().name() + "\n");
        w.write("Channel order: " + join(config.channelOrder()) + "\n");
        w.write("Cell size px: " + config.cellSizePx() + "\n");
        w.write("Scale bar: " + config.scaleBarEnabled() + "\n");
        w.write("Scale bar length um: " + config.scaleBarLengthUm() + "\n");
        w.write("Scale bar thickness px: " + config.scaleBarThicknessPx() + "\n");
        w.write("Scale bar position: " + config.scaleBarPosition().name() + "\n");
        w.write("Annotation colour: " + colorName(config.annotationColor()) + "\n");
        w.write("Label mode: " + config.labelMode().name() + "\n");
        w.write("Custom label template: " + config.customLabelTemplate() + "\n");
        w.write("Label font size px: " + config.labelFontSizePx() + "\n");
        w.write("Label position: " + config.labelPosition().name() + "\n");
        w.write("</Presentation Tile Config>\n");
    }

    private static TreeSet<Integer> collectChannelIndexes(RepresentativeFigureConfig config,
                                                          BinConfig setupConfig) {
        TreeSet<Integer> out = new TreeSet<Integer>();
        out.addAll(config.customDisplayRangesByChannel.keySet());
        for (int i = 0; i < setupConfig.channelNames.size(); i++) {
            out.add(Integer.valueOf(i));
        }
        RepresentativeSelection selection = config.selection;
        if (selection != null) {
            for (RepresentativeSeries series : selection.series()) {
                if (series == null) {
                    continue;
                }
                for (RepresentativeSeries.ChannelThumbnail thumbnail : series.channelThumbnails()) {
                    if (thumbnail != null) {
                        out.add(Integer.valueOf(thumbnail.channelIndex()));
                    }
                }
            }
        }
        return out;
    }

    private static String channelName(int channelIndex,
                                      RepresentativeFigureConfig config,
                                      BinConfig setupConfig) {
        if (channelIndex >= 0 && channelIndex < setupConfig.channelNames.size()) {
            String name = clean(setupConfig.channelNames.get(channelIndex));
            if (!name.isEmpty()) {
                return name;
            }
        }
        RepresentativeSelection selection = config.selection;
        if (selection != null) {
            for (RepresentativeSeries series : selection.series()) {
                if (series == null) {
                    continue;
                }
                for (RepresentativeSeries.ChannelThumbnail thumbnail : series.channelThumbnails()) {
                    if (thumbnail != null && thumbnail.channelIndex() == channelIndex) {
                        String name = clean(thumbnail.channelName());
                        if (!name.isEmpty()) {
                            return name;
                        }
                    }
                }
            }
        }
        return "C" + (channelIndex + 1);
    }

    private static String rangeSummary(int channelIndex,
                                       RepresentativeFigureConfig config,
                                       BinConfig setupConfig) {
        String custom = clean(config.customDisplayRangeForChannel(channelIndex));
        if (!custom.isEmpty()) {
            return custom + " (representative custom)";
        }
        if (channelIndex >= 0 && channelIndex < setupConfig.channelMinMax.size()) {
            String setup = clean(setupConfig.channelMinMax.get(channelIndex));
            if (!setup.isEmpty() && !"none".equalsIgnoreCase(setup)) {
                return setup + " (setup display range)";
            }
        }
        return "automatic fallback";
    }

    private static String path(File file) {
        return file == null ? "" : file.getAbsolutePath();
    }

    private static String colorName(Color color) {
        if (Color.BLACK.equals(color)) {
            return "Black";
        }
        if (Color.WHITE.equals(color)) {
            return "White";
        }
        return color == null ? "" : String.valueOf(color.getRGB());
    }

    private static String join(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(values.get(i));
        }
        return sb.toString();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
