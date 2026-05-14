package flash.pipeline.ui.preview;

import flash.pipeline.image.FilterMacroEditorModel;
import flash.pipeline.image.dag.DagIR;
import flash.pipeline.image.dag.DagLine;
import flash.pipeline.image.dag.DagNode;
import flash.pipeline.image.dag.DagToIjmEmitter;
import flash.pipeline.image.dag.IjmToDagLoader;
import flash.pipeline.ui.config.FilterParameterStage;
import flash.pipeline.ui.variations.FilterVariationEngineContext;
import flash.pipeline.ui.variations.VariationCache;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class PipelineFigureExporter {

    static final int THUMBNAIL_SIZE = 96;
    static final int LABEL_HEIGHT = 22;
    static final int PADDING = 10;
    static final int GAP = 12;

    private static final Color BACKGROUND = Color.WHITE;
    private static final Color TILE_BACKGROUND = new Color(0x18, 0x1B, 0x1F);
    private static final Color BORDER = new Color(0xC9, 0xCF, 0xD6);
    private static final Color LABEL = new Color(0x24, 0x2A, 0x30);
    private static final Font LABEL_FONT = new Font("SansSerif", Font.BOLD, 11);

    private PipelineFigureExporter() {
    }

    public static BufferedImage render(String selectedMacroText,
                                       ImagePlus rawSource,
                                       FilterParameterStage.PreviewAdapter previewAdapter,
                                       VariationCache cache) throws Exception {
        if (rawSource == null) {
            throw new IllegalArgumentException("rawSource must not be null");
        }
        if (previewAdapter == null) {
            throw new IllegalArgumentException("previewAdapter must not be null");
        }

        List<String> prefixMacros = prefixMacros(selectedMacroText);
        List<String> labels = prefixLabels(selectedMacroText);
        List<Tile> tiles = new ArrayList<Tile>();
        tiles.add(new Tile(labels.get(0), rawSource));
        for (int i = 1; i < prefixMacros.size(); i++) {
            String prefixMacro = prefixMacros.get(i);
            ImagePlus filtered = filteredPreview(rawSource, prefixMacro,
                    previewAdapter, cache);
            tiles.add(new Tile(labels.get(i), filtered));
        }
        return compose(tiles);
    }

    public static void exportPNG(BufferedImage img, File out) throws IOException {
        if (img == null) {
            throw new IllegalArgumentException("img must not be null");
        }
        if (out == null) {
            throw new IllegalArgumentException("file must not be null");
        }
        File parent = out.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            if (!parent.mkdirs() && !parent.isDirectory()) {
                throw new IOException("Could not create " + parent.getAbsolutePath());
            }
        }
        if (!ImageIO.write(img, "png", out)) {
            throw new IOException("No PNG writer is available.");
        }
    }

    static List<String> prefixMacros(String selectedMacroText) {
        String macro = selectedMacroText == null ? "" : selectedMacroText;
        DagIR dag = embeddedLinearDag(macro);
        if (dag != null) {
            return dagPrefixMacros(dag);
        }
        FilterMacroEditorModel.MacroDefinition parsed =
                FilterMacroEditorModel.parse(macro);
        List<FilterMacroEditorModel.Entry> entries = entries(parsed);
        List<String> prefixes = new ArrayList<String>();
        prefixes.add("");
        for (int i = 1; i <= entries.size(); i++) {
            prefixes.add(prefixMacro(macro, i));
        }
        return prefixes;
    }

    private static List<String> prefixLabels(String selectedMacroText) {
        DagIR dag = embeddedLinearDag(selectedMacroText);
        if (dag != null) {
            return dagPrefixLabels(dag);
        }
        FilterMacroEditorModel.MacroDefinition parsed =
                FilterMacroEditorModel.parse(selectedMacroText == null
                        ? ""
                        : selectedMacroText);
        List<FilterMacroEditorModel.Entry> entries = entries(parsed);
        List<String> labels = new ArrayList<String>();
        labels.add("raw");
        for (int i = 0; i < entries.size(); i++) {
            String label = entries.get(i).label;
            labels.add("+" + compactLabel(label == null || label.trim().isEmpty()
                    ? "step " + (i + 1)
                    : label.trim()));
        }
        return labels;
    }

    private static String prefixMacro(String selectedMacroText, int prefixLength) {
        FilterMacroEditorModel.MacroDefinition clone =
                FilterMacroEditorModel.parse(selectedMacroText == null
                        ? ""
                        : selectedMacroText);
        List<FilterMacroEditorModel.Entry> cloneEntries = entries(clone);
        int keep = Math.max(0, Math.min(prefixLength, cloneEntries.size()));
        for (int i = cloneEntries.size() - 1; i >= keep; i--) {
            clone.removeEntry(cloneEntries.get(i));
        }
        return clone.render();
    }

    private static DagIR embeddedLinearDag(String macroContent) {
        try {
            DagIR dag = IjmToDagLoader.loadEmbeddedDag(macroContent);
            return dag != null && dag.isLinear() && !dag.lines.isEmpty()
                    ? dag
                    : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<String> dagPrefixMacros(DagIR dag) {
        List<DagNode> enabled = enabledDagNodes(dag);
        List<String> prefixes = new ArrayList<String>();
        prefixes.add("");
        for (int i = 1; i <= enabled.size(); i++) {
            prefixes.add(dagPrefixMacro(dag, i));
        }
        return prefixes;
    }

    private static List<String> dagPrefixLabels(DagIR dag) {
        List<DagNode> enabled = enabledDagNodes(dag);
        List<String> labels = new ArrayList<String>();
        labels.add("raw");
        for (int i = 0; i < enabled.size(); i++) {
            labels.add("+" + compactLabel(dagNodeLabel(enabled.get(i), i)));
        }
        return labels;
    }

    private static String dagPrefixMacro(DagIR dag, int prefixLength) {
        DagLine line = dag.lines.get(0);
        List<DagNode> clonedOps = new ArrayList<DagNode>();
        int keptEnabled = 0;
        for (int i = 0; i < line.ops.size(); i++) {
            DagNode source = line.ops.get(i);
            DagNode copy = new DagNode(source.id, source.type, source.args,
                    source.commandName, source.menuPath);
            if (source.disabled) {
                copy.disabled = true;
            } else if (keptEnabled >= prefixLength) {
                copy.disabled = true;
            } else {
                keptEnabled++;
            }
            clonedOps.add(copy);
        }
        DagIR prefix = new DagIR(dag.version,
                Collections.singletonList(new DagLine(line.id, clonedOps)),
                dag.combiners,
                dag.output,
                dag.executionTier);
        return DagToIjmEmitter.emit(prefix);
    }

    private static List<DagNode> enabledDagNodes(DagIR dag) {
        List<DagNode> out = new ArrayList<DagNode>();
        if (dag == null || dag.lines.isEmpty()) {
            return out;
        }
        List<DagNode> ops = dag.lines.get(0).ops;
        for (int i = 0; i < ops.size(); i++) {
            DagNode node = ops.get(i);
            if (node != null && !node.disabled) {
                out.add(node);
            }
        }
        return out;
    }

    private static String dagNodeLabel(DagNode node, int index) {
        if (node == null) {
            return "step " + (index + 1);
        }
        String command = DagToIjmEmitter.commandFor(node.type);
        if (command == null || command.trim().isEmpty()) {
            command = node.commandName;
        }
        if (command == null || command.trim().isEmpty()) {
            command = "step " + (index + 1);
        }
        return command;
    }

    private static List<FilterMacroEditorModel.Entry> entries(
            FilterMacroEditorModel.MacroDefinition macro) {
        List<FilterMacroEditorModel.Entry> out =
                new ArrayList<FilterMacroEditorModel.Entry>();
        if (macro == null) {
            return out;
        }
        List<FilterMacroEditorModel.Section> sections = macro.getSections();
        for (int i = 0; i < sections.size(); i++) {
            out.addAll(sections.get(i).entries);
        }
        return out;
    }

    private static ImagePlus filteredPreview(ImagePlus rawSource,
                                             String prefixMacro,
                                             FilterParameterStage.PreviewAdapter previewAdapter,
                                             VariationCache cache) throws Exception {
        String key = cacheKey(rawSource, prefixMacro);
        ImagePlus cached = cache == null ? null : cache.get(key);
        if (cached != null) {
            return cached;
        }
        ImagePlus sourceCopy = copyImage(rawSource);
        ImagePlus filtered = null;
        try {
            filtered = previewAdapter.createFilteredPreview(sourceCopy, prefixMacro);
            if (filtered == null) {
                throw new IllegalStateException("Filter preview returned no image.");
            }
            if (cache != null) {
                cache.put(key, filtered);
            }
            return filtered;
        } finally {
            if (sourceCopy != null && sourceCopy != filtered) {
                previewAdapter.close(sourceCopy);
            }
        }
    }

    private static BufferedImage compose(List<Tile> tiles) {
        int count = Math.max(1, tiles == null ? 0 : tiles.size());
        int width = PADDING * 2 + count * THUMBNAIL_SIZE
                + Math.max(0, count - 1) * GAP;
        int height = PADDING * 2 + LABEL_HEIGHT + THUMBNAIL_SIZE;
        BufferedImage out = new BufferedImage(width, height,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(BACKGROUND);
            g.fillRect(0, 0, width, height);
            for (int i = 0; i < count; i++) {
                Tile tile = tiles.get(i);
                int x = PADDING + i * (THUMBNAIL_SIZE + GAP);
                paintLabel(g, tile.label, x, PADDING, THUMBNAIL_SIZE);
                paintThumbnail(g, tile.image, x, PADDING + LABEL_HEIGHT);
                if (i < count - 1) {
                    paintArrow(g, x + THUMBNAIL_SIZE, PADDING + LABEL_HEIGHT);
                }
            }
        } finally {
            g.dispose();
        }
        return out;
    }

    private static void paintLabel(Graphics2D g,
                                   String label,
                                   int x,
                                   int y,
                                   int width) {
        g.setFont(LABEL_FONT);
        FontMetrics fm = g.getFontMetrics();
        String text = fitText(label == null ? "" : label, fm, width - 4);
        int textWidth = fm.stringWidth(text);
        g.setColor(LABEL);
        g.drawString(text, x + Math.max(0, (width - textWidth) / 2),
                y + 15);
    }

    private static void paintThumbnail(Graphics2D g, ImagePlus image, int x, int y) {
        g.setColor(TILE_BACKGROUND);
        g.fillRect(x, y, THUMBNAIL_SIZE, THUMBNAIL_SIZE);
        BufferedImage source = imageToBuffered(image);
        if (source != null) {
            double scale = Math.min(
                    THUMBNAIL_SIZE / (double) Math.max(1, source.getWidth()),
                    THUMBNAIL_SIZE / (double) Math.max(1, source.getHeight()));
            int drawW = Math.max(1, (int) Math.round(source.getWidth() * scale));
            int drawH = Math.max(1, (int) Math.round(source.getHeight() * scale));
            int dx = x + Math.max(0, (THUMBNAIL_SIZE - drawW) / 2);
            int dy = y + Math.max(0, (THUMBNAIL_SIZE - drawH) / 2);
            Object previous = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(source, dx, dy, drawW, drawH, null);
            if (previous != null) {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, previous);
            }
        }
        g.setColor(BORDER);
        g.drawRect(x, y, THUMBNAIL_SIZE, THUMBNAIL_SIZE);
    }

    private static void paintArrow(Graphics2D g, int x, int y) {
        int midY = y + THUMBNAIL_SIZE / 2;
        int startX = x + 3;
        int endX = x + GAP - 3;
        g.setColor(new Color(0x7A, 0x82, 0x89));
        g.drawLine(startX, midY, endX, midY);
        g.drawLine(endX, midY, endX - 4, midY - 4);
        g.drawLine(endX, midY, endX - 4, midY + 4);
    }

    private static BufferedImage imageToBuffered(ImagePlus image) {
        if (image == null) {
            return null;
        }
        ImageProcessor processor = image.getProcessor();
        if (processor == null) {
            return null;
        }
        int width = Math.max(1, processor.getWidth());
        int height = Math.max(1, processor.getHeight());
        double[] range = finiteRange(processor);
        BufferedImage out = new BufferedImage(width, height,
                BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int grey = greyFor(processor.getPixelValue(x, y),
                        range[0], range[1]);
                int rgb = (grey << 16) | (grey << 8) | grey;
                out.setRGB(x, y, rgb);
            }
        }
        return out;
    }

    private static double[] finiteRange(ImageProcessor processor) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        if (processor != null) {
            int width = Math.max(1, processor.getWidth());
            int height = Math.max(1, processor.getHeight());
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    double value = processor.getPixelValue(x, y);
                    if (!Double.isFinite(value)) {
                        continue;
                    }
                    if (value < min) min = value;
                    if (value > max) max = value;
                }
            }
        }
        if (!Double.isFinite(min) || !Double.isFinite(max)) {
            return new double[] { 0.0d, 255.0d };
        }
        return new double[] { min, max };
    }

    private static int greyFor(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return 0;
        }
        double scaled = max > min
                ? ((value - min) / (max - min)) * 255.0d
                : value;
        return Math.max(0, Math.min(255, (int) Math.round(scaled)));
    }

    private static ImagePlus copyImage(ImagePlus source) {
        if (source == null) {
            return null;
        }
        ImageStack stack = source.getStack();
        if (stack == null) {
            return source.duplicate();
        }
        ImagePlus duplicate = source.duplicate();
        if (source.getCalibration() != null) {
            duplicate.setCalibration(source.getCalibration().copy());
        }
        return duplicate;
    }

    private static String cacheKey(ImagePlus rawSource, String prefixMacro) {
        String sourceHash = FilterVariationEngineContext.sourceImageHash(rawSource);
        String macroHash = sha256(prefixMacro == null ? "" : prefixMacro);
        return "pipeline-figure-" + sha256("source=" + sourceHash
                + ":prefix=" + macroHash).substring(0, 16);
    }

    private static String fitText(String text, FontMetrics fm, int maxWidth) {
        String safe = text == null ? "" : text;
        if (fm.stringWidth(safe) <= maxWidth) {
            return safe;
        }
        String suffix = "...";
        int suffixWidth = fm.stringWidth(suffix);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < safe.length(); i++) {
            String candidate = out.toString() + safe.charAt(i);
            if (fm.stringWidth(candidate) + suffixWidth > maxWidth) {
                break;
            }
            out.append(safe.charAt(i));
        }
        return out.toString() + suffix;
    }

    private static String compactLabel(String value) {
        String compact = value == null ? "" : value.trim();
        while (compact.endsWith(".")) {
            compact = compact.substring(0, compact.length() - 1).trim();
        }
        return compact.replaceAll("\\s+", " ");
    }

    private static String sha256(String value) {
        String raw = value == null ? "" : value;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (int i = 0; i < bytes.length; i++) {
                out.append(String.format(Locale.ROOT, "%02x",
                        Integer.valueOf(bytes[i] & 0xff)));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static final class Tile {
        final String label;
        final ImagePlus image;

        Tile(String label, ImagePlus image) {
            this.label = label == null ? "" : label;
            this.image = image;
        }
    }
}
