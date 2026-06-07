package flash.pipeline.representative;

import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.io.ImageCache;
import flash.pipeline.io.IoUtils;
import flash.pipeline.io.SeriesMeta;
import flash.pipeline.io.TifCache;
import flash.pipeline.naming.ChannelFilenameCodec;
import flash.pipeline.presentation.PresentationTileConfig;
import flash.pipeline.presentation.PresentationTileRecord;
import flash.pipeline.presentation.PresentationTileWriter;
import ij.IJ;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes the final full-resolution representative image figure PNG.
 */
public final class RepresentativeFigureWriter {

    private static final Color TILE_BG = Color.WHITE;
    private static final Color TILE_LINE = new Color(210, 210, 210);
    private static final Color TILE_TEXT = new Color(35, 35, 35);
    private static final Color TILE_HELP_TEXT = new Color(90, 90, 90);
    private static final int MARGIN = 6;
    private static final int COL_GAP = 4;
    private static final int CONDITION_GAP = 12;
    private static final int ROW_GAP = 8;
    private static final String MERGE_NAME = "Merge";
    private static final String INDIVIDUAL_IMAGES_DIR = "Individual Images";

    public File write(String directory,
                      RepresentativeFigureConfig config,
                      ImageCache imageCache,
                      int parallelThreads,
                      boolean useTifCache) throws Exception {
        return write(directory, config, imageCache, parallelThreads, useTifCache, null);
    }

    public File write(String directory,
                      RepresentativeFigureConfig config,
                      ImageCache imageCache,
                      int parallelThreads,
                      boolean useTifCache,
                      List<SeriesMeta> metas) throws Exception {
        RepresentativeFigureConfig safeConfig = requireCompleteConfig(config);
        List<RepresentativePreviewRenderer.RenderedFinalSeries> rendered =
                RepresentativePreviewRenderer.renderFinal(
                        directory, safeConfig, imageCache, parallelThreads, useTifCache, metas);
        return writeRenderedFigure(directory, safeConfig, rendered);
    }

    static File writeRenderedFigureForTests(
            String directory,
            RepresentativeFigureConfig config,
            List<RepresentativePreviewRenderer.RenderedFinalSeries> rendered)
            throws IOException {
        return writeRenderedFigure(directory, requireCompleteConfig(config), rendered);
    }

    private static File writeRenderedFigure(
            String directory,
            RepresentativeFigureConfig config,
            List<RepresentativePreviewRenderer.RenderedFinalSeries> rendered)
            throws IOException {
        if (rendered == null || rendered.isEmpty()) {
            throw new IllegalStateException("No representative images were rendered.");
        }
        validateRenderedConditions(config.layout, rendered);

        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
        File outDir = layout.representativeFiguresDir();
        IoUtils.mustMkdirs(outDir);

        BufferedImage figure = renderFigureImage(config, rendered);
        File output = uniqueOutputFile(outDir, baseNameFor(config.layout) + ".png");
        PresentationTileWriter.writePngAtomically(figure, output);
        writeIndividualImages(directory, outDir, rendered);
        return output;
    }

    private static void writeIndividualImages(
            String directory,
            File representativeFiguresDir,
            List<RepresentativePreviewRenderer.RenderedFinalSeries> rendered)
            throws IOException {
        File root = new File(representativeFiguresDir, INDIVIDUAL_IMAGES_DIR);
        prepareCleanIndividualImagesRoot(root, representativeFiguresDir);
        int pngCount = 0;
        int originalTifCount = 0;
        for (RepresentativePreviewRenderer.RenderedFinalSeries series : rendered) {
            if (series == null) continue;
            File conditionDir = new File(root, conditionFolderName(series));
            IoUtils.mustMkdirs(conditionDir);
            List<RepresentativePreviewRenderer.RenderedFinalChannel> channels =
                    series.channels();
            for (int i = 0; i < channels.size(); i++) {
                RepresentativePreviewRenderer.RenderedFinalChannel channel = channels.get(i);
                if (channel == null || channel.image() == null) continue;
                File output = new File(conditionDir, channelFileName(channel));
                PresentationTileWriter.writePngAtomically(channel.image(), output);
                pngCount++;
            }
            if (series.mergeImage() != null) {
                PresentationTileWriter.writePngAtomically(
                        series.mergeImage(), new File(conditionDir, MERGE_NAME + ".png"));
                pngCount++;
            }
            File original = originalTifSource(directory, series);
            if (original != null) {
                copyFileAtomically(original, new File(conditionDir, originalTifName(series)));
                originalTifCount++;
            } else {
                IJ.log("[Representative Figure] Original TIFF not available for "
                        + RepresentativeSelection.conditionLabel(series.condition())
                        + " (series " + series.seriesNumber() + ").");
            }
        }
        IJ.log("[Representative Figure] Saved individual images to "
                + root.getAbsolutePath() + " (" + pngCount + " PNG"
                + (pngCount == 1 ? "" : "s") + ", " + originalTifCount
                + " original TIFF" + (originalTifCount == 1 ? "" : "s") + ").");
    }

    private static void prepareCleanIndividualImagesRoot(File root, File expectedParent)
            throws IOException {
        if (root != null && root.exists()) {
            assertIndividualImagesRoot(root, expectedParent);
            deleteRecursively(root);
        }
        IoUtils.mustMkdirs(root);
    }

    private static void assertIndividualImagesRoot(File root, File expectedParent)
            throws IOException {
        File canonicalRoot = root.getCanonicalFile();
        File canonicalParent = expectedParent.getCanonicalFile();
        if (!INDIVIDUAL_IMAGES_DIR.equals(canonicalRoot.getName())
                || !sameFile(canonicalRoot.getParentFile(), canonicalParent)) {
            throw new IOException("Refusing to clean unexpected individual images folder: "
                    + root.getAbsolutePath());
        }
    }

    private static void deleteRecursively(File file) throws IOException {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (int i = 0; i < children.length; i++) {
                    deleteRecursively(children[i]);
                }
            }
        }
        if (!file.delete() && file.exists()) {
            throw new IOException("Could not delete stale representative image output: "
                    + file.getAbsolutePath());
        }
    }

    static BufferedImage renderFigureImage(
            RepresentativeFigureConfig config,
            List<RepresentativePreviewRenderer.RenderedFinalSeries> rendered) {
        RepresentativeFigureConfig safeConfig = requireCompleteConfig(config);
        List<RepresentativePreviewRenderer.RenderedFinalSeries> safeRendered =
                rendered == null
                        ? Collections.<RepresentativePreviewRenderer.RenderedFinalSeries>emptyList()
                        : rendered;
        if (safeRendered.isEmpty()) {
            throw new IllegalArgumentException("Rendered representative images are required.");
        }

        List<String> outputs = orderedOutputs(safeRendered, safeConfig.tileConfig.channelOrder());
        if (outputs.isEmpty()) {
            throw new IllegalArgumentException("At least one channel or merge tile is required.");
        }

        Map<String, RepresentativePreviewRenderer.RenderedFinalSeries> byCondition =
                renderedByCondition(safeRendered);
        Map<String, String> conditions = conditionLookup(safeRendered);

        int cell = safeConfig.tileConfig.cellSizePx();
        Font conditionFont = new Font(Font.SANS_SERIF, Font.BOLD, 15);
        Font channelFont = new Font(Font.SANS_SERIF, Font.BOLD, 16);
        FigureLayout layout = createFigureLayout(
                safeConfig.layout, outputs, cell, conditionFont, channelFont);

        BufferedImage figure = new BufferedImage(
                layout.width, layout.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = figure.createGraphics();
        try {
            PresentationTileWriter.applyQualityHints(g);
            g.setColor(TILE_BG);
            g.fillRect(0, 0, layout.width, layout.height);

            int y = MARGIN;
            List<List<String>> rows = safeConfig.layout.rows();
            for (int r = 0; r < rows.size(); r++) {
                List<String> row = rows.get(r);
                int x = MARGIN;
                for (int c = 0; c < row.size(); c++) {
                    String condition = row.get(c);
                    RepresentativePreviewRenderer.RenderedFinalSeries series =
                            byCondition.get(RepresentativeSelection.conditionLabel(condition));
                    drawConditionBlock(g, condition, series, outputs, conditions,
                            safeConfig.tileConfig, layout, x, y);
                    x += layout.conditionBlockWidth + CONDITION_GAP;
                }
                y += layout.rowHeight + ROW_GAP;
            }
        } finally {
            g.dispose();
        }
        return figure;
    }

    private static void drawConditionBlock(
            Graphics2D g,
            String condition,
            RepresentativePreviewRenderer.RenderedFinalSeries series,
            List<String> outputs,
            Map<String, String> conditions,
            PresentationTileConfig config,
            FigureLayout layout,
            int x,
            int y) {
        g.setFont(layout.conditionFont);
        g.setColor(TILE_TEXT);
        FontMetrics conditionFm = g.getFontMetrics();
        String conditionLabel = fitSingleLine(condition, conditionFm, layout.conditionBlockWidth);
        int conditionX = x + Math.max(0,
                (layout.conditionBlockWidth - conditionFm.stringWidth(conditionLabel)) / 2);
        g.drawString(conditionLabel, conditionX, y + conditionFm.getAscent() + 2);

        int headerY = y + layout.conditionHeaderHeight;
        drawOutputHeaders(g, outputs, x, headerY, layout.cell,
                layout.channelHeaderHeight, layout.channelFont);

        int tileY = headerY + layout.channelHeaderHeight;
        Map<String, RenderedTile> tiles = tilesByOutput(series);
        for (int i = 0; i < outputs.size(); i++) {
            int tileX = x + i * (layout.cell + COL_GAP);
            drawTile(g, tiles.get(outputs.get(i)), conditions, config,
                    tileX, tileY, layout.cell);
        }
    }

    private static void drawOutputHeaders(Graphics2D g,
                                          List<String> outputs,
                                          int x,
                                          int y,
                                          int cell,
                                          int headerHeight,
                                          Font font) {
        g.setFont(font);
        g.setColor(TILE_TEXT);
        FontMetrics fm = g.getFontMetrics();
        for (int i = 0; i < outputs.size(); i++) {
            int cellX = x + i * (cell + COL_GAP);
            String label = fitSingleLine(outputs.get(i), fm, cell);
            int textX = cellX + Math.max(0, (cell - fm.stringWidth(label)) / 2);
            int textY = y + Math.max(fm.getAscent(), (headerHeight + fm.getAscent()) / 2 - 1);
            g.drawString(label, textX, textY);
        }
    }

    private static void drawTile(Graphics2D g,
                                 RenderedTile tile,
                                 Map<String, String> conditions,
                                 PresentationTileConfig config,
                                 int x,
                                 int y,
                                 int cell) {
        g.setColor(Color.BLACK);
        g.fillRect(x, y, cell, cell);
        g.setColor(TILE_LINE);
        g.drawRect(x, y, cell, cell);

        if (tile == null || tile.image == null) {
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            g.setColor(TILE_HELP_TEXT);
            g.drawString("Missing image", x + 12, y + 24);
            return;
        }

        BufferedImage image = tile.image;
        double scale = Math.min((double) cell / image.getWidth(),
                (double) cell / image.getHeight());
        int drawW = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int drawH = Math.max(1, (int) Math.round(image.getHeight() * scale));
        int drawX = x + (cell - drawW) / 2;
        int drawY = y + (cell - drawH) / 2;
        g.drawImage(image, drawX, drawY, drawW, drawH, null);

        if (config.annotateOverviewTile()) {
            double recordScale = drawW / (double) Math.max(1, tile.record.widthPx());
            PresentationTileWriter.drawAnnotations(g, tile.record, conditions, config,
                    new Rectangle(drawX, drawY, drawW, drawH), recordScale);
        }
    }

    private static FigureLayout createFigureLayout(RepresentativeLayout representativeLayout,
                                                   List<String> outputs,
                                                   int cell,
                                                   Font conditionFont,
                                                   Font channelFont) {
        BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scratch.createGraphics();
        try {
            PresentationTileWriter.applyQualityHints(g);
            FontMetrics conditionFm = g.getFontMetrics(conditionFont);
            FontMetrics channelFm = g.getFontMetrics(channelFont);
            int conditionHeaderHeight = conditionFm.getHeight() + 4;
            int channelHeaderHeight = channelFm.getHeight() + 4;
            int conditionBlockWidth = outputs.size() * cell
                    + Math.max(0, outputs.size() - 1) * COL_GAP;
            int maxConditionsPerRow = representativeLayout.maxColumnCount();
            int width = MARGIN * 2
                    + maxConditionsPerRow * conditionBlockWidth
                    + Math.max(0, maxConditionsPerRow - 1) * CONDITION_GAP;
            int rowHeight = conditionHeaderHeight + channelHeaderHeight + cell;
            int height = MARGIN * 2
                    + representativeLayout.rowCount() * rowHeight
                    + Math.max(0, representativeLayout.rowCount() - 1) * ROW_GAP;
            return new FigureLayout(width, height, cell, conditionBlockWidth,
                    conditionHeaderHeight, channelHeaderHeight, rowHeight,
                    conditionFont, channelFont);
        } finally {
            g.dispose();
        }
    }

    private static Map<String, RenderedTile> tilesByOutput(
            RepresentativePreviewRenderer.RenderedFinalSeries series) {
        LinkedHashMap<String, RenderedTile> tiles = new LinkedHashMap<String, RenderedTile>();
        if (series == null) return tiles;
        for (RepresentativePreviewRenderer.RenderedFinalChannel channel : series.channels()) {
            if (channel == null) continue;
            tiles.put(channel.channelName(), new RenderedTile(channel.image(),
                    recordFor(series, channel.channelName(), channel.channelName(),
                            channel.channelIndex(), channel.image())));
        }
        tiles.put(MERGE_NAME, new RenderedTile(series.mergeImage(),
                recordFor(series, MERGE_NAME, MERGE_NAME, -1, series.mergeImage())));
        return tiles;
    }

    private static PresentationTileRecord recordFor(
            RepresentativePreviewRenderer.RenderedFinalSeries series,
            String outputName,
            String stainName,
            int channelIndex,
            BufferedImage image) {
        int width = image == null ? 1 : image.getWidth();
        int height = image == null ? 1 : image.getHeight();
        return new PresentationTileRecord(
                null,
                series.animal(),
                series.hemisphere(),
                series.region(),
                series.id(),
                outputName,
                stainName,
                channelIndex,
                width,
                height,
                series.pixelWidthUm(),
                series.pixelHeightUm());
    }

    private static List<String> orderedOutputs(
            List<RepresentativePreviewRenderer.RenderedFinalSeries> rendered,
            List<String> requestedOrder) {
        LinkedHashSet<String> available = new LinkedHashSet<String>();
        for (RepresentativePreviewRenderer.RenderedFinalSeries series : rendered) {
            if (series == null) continue;
            for (RepresentativePreviewRenderer.RenderedFinalChannel channel : series.channels()) {
                if (channel != null && !channel.channelName().isEmpty()) {
                    available.add(channel.channelName());
                }
            }
            available.add(MERGE_NAME);
        }

        LinkedHashSet<String> ordered = new LinkedHashSet<String>();
        if (requestedOrder != null) {
            for (String requested : requestedOrder) {
                String clean = clean(requested);
                if (available.contains(clean)) ordered.add(clean);
            }
        }
        for (String output : available) {
            ordered.add(output);
        }
        return Collections.unmodifiableList(new ArrayList<String>(ordered));
    }

    private static Map<String, RepresentativePreviewRenderer.RenderedFinalSeries> renderedByCondition(
            List<RepresentativePreviewRenderer.RenderedFinalSeries> rendered) {
        LinkedHashMap<String, RepresentativePreviewRenderer.RenderedFinalSeries> out =
                new LinkedHashMap<String, RepresentativePreviewRenderer.RenderedFinalSeries>();
        for (RepresentativePreviewRenderer.RenderedFinalSeries series : rendered) {
            if (series == null) continue;
            out.put(RepresentativeSelection.conditionLabel(series.condition()), series);
        }
        return out;
    }

    private static Map<String, String> conditionLookup(
            List<RepresentativePreviewRenderer.RenderedFinalSeries> rendered) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        for (RepresentativePreviewRenderer.RenderedFinalSeries series : rendered) {
            if (series == null) continue;
            String condition = RepresentativeSelection.conditionLabel(series.condition());
            putIfNotEmpty(out, series.id(), condition);
            putIfNotEmpty(out, imageKey(series), condition);
            putIfNotEmpty(out, series.animal(), condition);
        }
        return out;
    }

    private static void validateRenderedConditions(
            RepresentativeLayout layout,
            List<RepresentativePreviewRenderer.RenderedFinalSeries> rendered) {
        LinkedHashSet<String> expected = new LinkedHashSet<String>(layout.flattenedConditions());
        LinkedHashSet<String> actual = new LinkedHashSet<String>();
        if (rendered != null) {
            for (RepresentativePreviewRenderer.RenderedFinalSeries series : rendered) {
                if (series != null) {
                    actual.add(RepresentativeSelection.conditionLabel(series.condition()));
                }
            }
        }
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                    "Rendered representative conditions do not match the chosen layout.");
        }
    }

    private static RepresentativeFigureConfig requireCompleteConfig(
            RepresentativeFigureConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("Representative figure config is required.");
        }
        if (config.selection == null || !config.selection.isComplete()) {
            throw new IllegalStateException("Representative selection is not complete.");
        }
        if (config.layout == null) {
            throw new IllegalStateException("Representative layout is not set.");
        }
        if (!config.layout.containsExactlyConditions(config.selection.conditionNames())) {
            throw new IllegalStateException(
                    "Representative layout does not match the selected conditions.");
        }
        if (config.tileConfig == null) {
            throw new IllegalStateException("Representative tile configuration is not set.");
        }
        return config;
    }

    private static File uniqueOutputFile(File dir, String filename) {
        File first = new File(dir, filename);
        if (!first.exists()) return first;
        int dot = filename.toLowerCase().endsWith(".png")
                ? filename.length() - 4 : filename.length();
        String base = filename.substring(0, dot);
        String ext = filename.substring(dot);
        for (int i = 2; i < 10000; i++) {
            File candidate = new File(dir, base + "_" + i + ext);
            if (!candidate.exists()) return candidate;
        }
        throw new IllegalStateException("Could not choose a unique representative figure filename.");
    }

    private static String baseNameFor(RepresentativeLayout layout) {
        List<String> conditions = layout.flattenedConditions();
        StringBuilder sb = new StringBuilder("Representative_Figure");
        int limit = Math.min(3, conditions.size());
        for (int i = 0; i < limit; i++) {
            String safe = safeFileName(conditions.get(i));
            if (!safe.isEmpty()) sb.append('_').append(safe);
        }
        if (conditions.size() > limit) {
            sb.append("_plus").append(conditions.size() - limit);
        }
        String base = sb.toString();
        return base.length() > 140 ? base.substring(0, 140) : base;
    }

    private static String imageKey(
            RepresentativePreviewRenderer.RenderedFinalSeries series) {
        StringBuilder sb = new StringBuilder(series.animal());
        if (!series.hemisphere().isEmpty()) sb.append('|').append(series.hemisphere());
        if (!series.region().isEmpty()) sb.append('|').append(series.region());
        if (!series.id().isEmpty()) sb.append('|').append(series.id());
        return sb.toString();
    }

    private static void putIfNotEmpty(Map<String, String> map, String key, String value) {
        String cleanKey = clean(key);
        if (!cleanKey.isEmpty()) map.put(cleanKey, value);
    }

    private static String conditionFolderName(
            RepresentativePreviewRenderer.RenderedFinalSeries series) {
        String condition = RepresentativeSelection.conditionLabel(series.condition());
        if (condition.isEmpty()) condition = series.animal();
        if (condition.isEmpty()) condition = series.id();
        return safeFileBase(condition, "Condition");
    }

    private static String channelFileName(
            RepresentativePreviewRenderer.RenderedFinalChannel channel) {
        int channelNumber = Math.max(1, channel.channelIndex() + 1);
        return "C" + channelNumber + "_"
                + safeFileBase(channel.channelName(), "Channel") + ".png";
    }

    private static String originalTifName(
            RepresentativePreviewRenderer.RenderedFinalSeries series) {
        String name = series.seriesName();
        if (clean(name).isEmpty()) name = series.id();
        return "Original_" + safeFileBase(name, "Series") + ".tif";
    }

    private static File originalTifSource(
            String directory,
            RepresentativePreviewRenderer.RenderedFinalSeries series) {
        if (series == null) return null;
        File source = series.sourcePath();
        if (isTifFile(source)) return source;
        if (directory != null && !directory.trim().isEmpty()
                && series.seriesIndex() >= 0) {
            File cached = TifCache.cachedFileForSeries(directory, series.seriesIndex());
            if (isTifFile(cached)) return cached;
        }
        return null;
    }

    private static boolean isTifFile(File file) {
        if (file == null || !file.isFile()) return false;
        String name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".tif") || name.endsWith(".tiff");
    }

    private static void copyFileAtomically(File source, File target) throws IOException {
        if (source == null || target == null) return;
        if (sameFile(source, target)) return;
        File parent = target.getParentFile();
        if (parent != null) IoUtils.mustMkdirs(parent);
        File temp = File.createTempFile(tempPrefix(target), ".tmp",
                parent == null ? new File(".") : parent);
        boolean moved = false;
        try {
            Files.copy(source.toPath(), temp.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES);
            IoUtils.moveReplacing(temp.toPath(), target.toPath());
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temp.toPath());
            }
        }
    }

    private static boolean sameFile(File left, File right) {
        try {
            return left.getCanonicalFile().equals(right.getCanonicalFile());
        } catch (IOException e) {
            return left.getAbsoluteFile().equals(right.getAbsoluteFile());
        }
    }

    private static String tempPrefix(File target) {
        String name = target == null ? "tmp" : clean(target.getName());
        String prefix = name.length() < 3 ? "tmp" + name : name;
        return prefix.length() > 32 ? prefix.substring(0, 32) : prefix;
    }

    private static String fitSingleLine(String text, FontMetrics fm, int width) {
        String clean = clean(text);
        if (width <= 0 || clean.isEmpty()) return "";
        if (fm.stringWidth(clean) <= width) return clean;
        return ellipsize(clean, fm, width);
    }

    private static String ellipsize(String text, FontMetrics fm, int width) {
        if (width <= 0) return "";
        String suffix = "...";
        if (fm.stringWidth(suffix) > width) return "";
        String clean = clean(text);
        int end = clean.length();
        while (end > 0) {
            String candidate = clean.substring(0, end).trim() + suffix;
            if (fm.stringWidth(candidate) <= width) return candidate;
            end--;
        }
        return suffix;
    }

    private static String safeFileName(String value) {
        return safeFileBase(value, "Condition");
    }

    private static String safeFileBase(String value, String fallback) {
        String safe = ChannelFilenameCodec.toSafe(clean(value));
        if (safe == null || safe.isEmpty()) safe = clean(fallback);
        if (safe.isEmpty()) safe = "File";
        return safe.length() > 140 ? safe.substring(0, 140) : safe;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class RenderedTile {
        final BufferedImage image;
        final PresentationTileRecord record;

        RenderedTile(BufferedImage image, PresentationTileRecord record) {
            this.image = image;
            this.record = record;
        }
    }

    private static final class FigureLayout {
        final int width;
        final int height;
        final int cell;
        final int conditionBlockWidth;
        final int conditionHeaderHeight;
        final int channelHeaderHeight;
        final int rowHeight;
        final Font conditionFont;
        final Font channelFont;

        FigureLayout(int width,
                     int height,
                     int cell,
                     int conditionBlockWidth,
                     int conditionHeaderHeight,
                     int channelHeaderHeight,
                     int rowHeight,
                     Font conditionFont,
                     Font channelFont) {
            this.width = width;
            this.height = height;
            this.cell = cell;
            this.conditionBlockWidth = conditionBlockWidth;
            this.conditionHeaderHeight = conditionHeaderHeight;
            this.channelHeaderHeight = channelHeaderHeight;
            this.rowHeight = rowHeight;
            this.conditionFont = conditionFont;
            this.channelFont = channelFont;
        }
    }
}
