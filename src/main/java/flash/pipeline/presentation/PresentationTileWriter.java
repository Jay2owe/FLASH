package flash.pipeline.presentation;

import flash.pipeline.io.CsvSupport;
import flash.pipeline.io.IoUtils;
import ij.IJ;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Renders saved presentation-ready PNGs into annotated copies and contact-sheet
 * overview images.
 */
public final class PresentationTileWriter {

    private static final Color TILE_BG = Color.WHITE;
    private static final Color TILE_LINE = new Color(210, 210, 210);
    private static final Color TILE_TEXT = new Color(35, 35, 35);
    private static final Color TILE_HELP_TEXT = new Color(90, 90, 90);
    private static final int MAX_ANNOTATION_PREVIEW_DIMENSION = 640;

    private PresentationTileWriter() {}

    public static void writeRequestedOutputs(File annotatedImagesDir,
                                             File tilesDir,
                                             File manifestFile,
                                             List<PresentationTileRecord> records,
                                             Map<String, String> conditions,
                                             PresentationTileConfig config) throws IOException {
        if (manifestFile == null || records == null || records.isEmpty() || config == null) {
            return;
        }

        if (config.annotateIndividualImages() && annotatedImagesDir != null) {
            writeAnnotatedImageCopies(annotatedImagesDir, records, conditions, config);
        }

        writeManifest(manifestFile, records, conditions);

        if (config.createOverviewTile() && tilesDir != null) {
            File out = new File(tilesDir, "Presentation_Overview_"
                    + (config.groupRowsBy() == PresentationTileConfig.GroupRowsBy.CONDITION
                    ? "ByCondition" : "ByAnimal") + ".png");
            writeOverviewTile(out, records, conditions, config);
        }
    }

    public static void writeManifest(File manifest,
                                     List<PresentationTileRecord> records,
                                     Map<String, String> conditions) throws IOException {
        File parent = manifest.getParentFile();
        if (parent != null) IoUtils.mustMkdirs(parent);

        File temp = tempFileFor(manifest);
        boolean moved = false;
        try {
            PrintWriter pw = CsvSupport.newWriter(temp);
            try {
                pw.println(CsvSupport.joinRow(Arrays.asList(
                        "Animal", "Condition", "Hemisphere", "Region",
                        "OutputName", "StainName", "ChannelIndex",
                        "ImagePath", "AnnotatedImagePath",
                        "WidthPx", "HeightPx", "PixelWidthUm", "PixelHeightUm",
                        "SourceImageId")));
                for (PresentationTileRecord record : safeRecords(records)) {
                    pw.println(CsvSupport.joinRow(Arrays.asList(
                            record.animal(),
                            conditionFor(record, conditions),
                            record.hemisphere(),
                            record.region(),
                            record.outputName(),
                            record.stainName(),
                            String.valueOf(record.channelIndex()),
                            absolutePath(record.imageFile()),
                            absolutePath(record.annotatedImageFile()),
                            String.valueOf(record.widthPx()),
                            String.valueOf(record.heightPx()),
                            formatNumber(record.pixelWidthUm()),
                            formatNumber(record.pixelHeightUm()),
                            record.imageId())));
                }
            } finally {
                pw.close();
            }
            moveAtomically(temp.toPath(), manifest.toPath());
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temp.toPath());
            }
        }
    }

    public static List<PresentationTileRecord> readManifest(File manifest) throws IOException {
        List<PresentationTileRecord> records = new ArrayList<PresentationTileRecord>();
        if (manifest == null || !manifest.isFile()) return records;

        CsvSupport.RecordReader reader = CsvSupport.openRecordReader(manifest);
        try {
            CsvSupport.Record headerRecord = reader.readRecord();
            if (headerRecord == null) return records;
            String[] header = CsvSupport.parseRecord(headerRecord.text);
            Map<String, Integer> columns = new LinkedHashMap<String, Integer>();
            for (int i = 0; i < header.length; i++) {
                columns.put(header[i], Integer.valueOf(i));
            }

            CsvSupport.Record rowRecord;
            while ((rowRecord = reader.readRecord()) != null) {
                if (CsvSupport.isBlankRecord(rowRecord.text)) continue;
                String[] row = CsvSupport.parseRecord(rowRecord.text);
                String imagePath = field(row, columns, "ImagePath");
                if (imagePath.isEmpty()) continue;
                PresentationTileRecord record = new PresentationTileRecord(
                        new File(imagePath),
                        field(row, columns, "Animal"),
                        field(row, columns, "Hemisphere"),
                        field(row, columns, "Region"),
                        firstField(row, columns, "SourceImageId", "ImageId"),
                        field(row, columns, "OutputName"),
                        field(row, columns, "StainName"),
                        parseInt(field(row, columns, "ChannelIndex"), -1),
                        parseInt(field(row, columns, "WidthPx"), 1),
                        parseInt(field(row, columns, "HeightPx"), 1),
                        parseDouble(field(row, columns, "PixelWidthUm"), Double.NaN),
                        parseDouble(field(row, columns, "PixelHeightUm"), Double.NaN));
                String annotatedPath = field(row, columns, "AnnotatedImagePath");
                if (!annotatedPath.isEmpty()) {
                    record.setAnnotatedImageFile(new File(annotatedPath));
                }
                records.add(record);
            }
        } finally {
            reader.close();
        }
        return records;
    }

    public static void writeAnnotatedImageCopies(File annotatedRoot,
                                                 List<PresentationTileRecord> records,
                                                 Map<String, String> conditions,
                                                 PresentationTileConfig config) throws IOException {
        IoUtils.mustMkdirs(annotatedRoot);
        for (PresentationTileRecord record : safeRecords(records)) {
            File source = record.imageFile();
            if (source == null || !source.isFile()) continue;
            BufferedImage image = ImageIO.read(source);
            if (image == null) continue;

            BufferedImage annotated = toArgb(image);
            Graphics2D g = annotated.createGraphics();
            try {
                applyQualityHints(g);
                Rectangle imageRect = new Rectangle(0, 0, annotated.getWidth(), annotated.getHeight());
                drawAnnotations(g, record, conditions, config, imageRect, 1.0);
            } finally {
                g.dispose();
            }

            File animalDir = new File(annotatedRoot, record.animal());
            IoUtils.mustMkdirs(animalDir);
            File out = new File(animalDir, source.getName());
            writePngAtomically(annotated, out);
            record.setAnnotatedImageFile(out);
        }
    }

    public static void writeOverviewTile(File outputFile,
                                         List<PresentationTileRecord> records,
                                         Map<String, String> conditions,
                                         PresentationTileConfig config) throws IOException {
        List<PresentationTileRecord> usable = safeRecords(records);
        if (usable.isEmpty()) return;

        List<String> columns = orderedOutputNames(usable, config.channelOrder());
        if (columns.isEmpty()) return;

        List<Row> rows = orderedRows(usable, conditions, config.groupRowsBy());
        if (rows.isEmpty()) return;

        Map<String, PresentationTileRecord> byRowAndColumn = new LinkedHashMap<String, PresentationTileRecord>();
        for (PresentationTileRecord record : usable) {
            byRowAndColumn.put(record.imageKey() + "\n" + record.outputName(), record);
        }

        Font headerFont = new Font(Font.SANS_SERIF, Font.BOLD, 16);
        Font rowFont = new Font(Font.SANS_SERIF, Font.PLAIN, 14);
        Font groupFont = new Font(Font.SANS_SERIF, Font.BOLD, 15);

        int cell = config.cellSizePx();
        int groupCount = groupCount(rows);
        TileLayout layout = createTileLayout(columns, rows, cell, groupCount,
                headerFont, rowFont, groupFont);

        int width = layout.width;
        int height = layout.height;

        BufferedImage tile = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = tile.createGraphics();
        try {
            applyQualityHints(g);
            g.setColor(TILE_BG);
            g.fillRect(0, 0, width, height);

            int x0 = layout.margin + layout.rowLabelWidth + layout.rowLabelGap;
            int y = layout.margin;
            drawColumnHeaders(g, columns, x0, y, cell, layout.colGap,
                    layout.headerHeight, headerFont);
            y += layout.headerHeight;

            String lastGroup = null;
            for (int r = 0; r < rows.size(); r++) {
                Row row = rows.get(r);
                if (!row.groupLabel.equals(lastGroup)) {
                    drawGroupLabel(g, row.groupLabel, layout.margin, y,
                            width - layout.margin * 2, layout.groupHeaderHeight, groupFont);
                    y += layout.groupHeaderHeight;
                    lastGroup = row.groupLabel;
                }

                drawRowLabel(g, row.label, layout.margin, y, layout.rowLabelWidth, cell, rowFont);
                for (int c = 0; c < columns.size(); c++) {
                    int x = x0 + c * (cell + layout.colGap);
                    PresentationTileRecord record = byRowAndColumn.get(row.key + "\n" + columns.get(c));
                    drawCell(g, record, conditions, config, x, y, cell);
                }
                y += cell;
                if (r < rows.size() - 1) {
                    y += layout.rowGap;
                }
            }
        } finally {
            g.dispose();
        }

        File parent = outputFile.getParentFile();
        if (parent != null) IoUtils.mustMkdirs(parent);
        writePngAtomically(tile, outputFile);
        IJ.log("  - Presentation overview tile written: " + outputFile.getAbsolutePath());
    }

    private static TileLayout createTileLayout(List<String> columns,
                                               List<Row> rows,
                                               int cell,
                                               int groupCount,
                                               Font headerFont,
                                               Font rowFont,
                                               Font groupFont) {
        BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scratch.createGraphics();
        try {
            applyQualityHints(g);
            FontMetrics headerFm = g.getFontMetrics(headerFont);
            FontMetrics rowFm = g.getFontMetrics(rowFont);
            FontMetrics groupFm = g.getFontMetrics(groupFont);
            int margin = 6;
            int colGap = 4;
            int rowGap = 4;
            int rowLabelGap = 6;
            int rowLabelWidth = tightRowLabelWidth(rows, rowFm, cell);
            if (rowLabelWidth <= 0) rowLabelGap = 0;
            int headerHeight = headerFm.getHeight() + 4;
            int groupHeaderHeight = groupFm.getHeight() + 4;
            int width = margin * 2 + rowLabelWidth + rowLabelGap
                    + columns.size() * cell
                    + Math.max(0, columns.size() - 1) * colGap;
            int height = margin * 2 + headerHeight
                    + groupCount * groupHeaderHeight
                    + rows.size() * cell
                    + Math.max(0, rows.size() - 1) * rowGap;
            return new TileLayout(width, height, margin, colGap, rowGap,
                    rowLabelWidth, rowLabelGap, headerHeight, groupHeaderHeight);
        } finally {
            g.dispose();
        }
    }

    private static int groupCount(List<Row> rows) {
        int count = 0;
        String lastGroup = null;
        for (Row row : rows) {
            if (!row.groupLabel.equals(lastGroup)) {
                count++;
                lastGroup = row.groupLabel;
            }
        }
        return count;
    }

    public static void writePngAtomically(BufferedImage image, File outputFile) throws IOException {
        File parent = outputFile.getParentFile();
        if (parent != null) IoUtils.mustMkdirs(parent);
        File temp = tempFileFor(outputFile);
        boolean moved = false;
        try {
            if (!ImageIO.write(image, "png", temp)) {
                throw new IOException("No PNG writer available");
            }
            moveAtomically(temp.toPath(), outputFile.toPath());
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temp.toPath());
            }
        }
    }

    private static File tempFileFor(File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null) IoUtils.mustMkdirs(parent);
        return File.createTempFile(tempPrefix(target), ".tmp",
                parent == null ? new File(".") : parent);
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        // Atomic move with retry/backoff for transient locks (cloud-sync, AV).
        // No in-place fallback: tile images can be large, never read into memory.
        IoUtils.moveReplacing(source, target);
    }

    private static String tempPrefix(File target) {
        String name = target == null ? "presentation" : target.getName();
        String clean = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return clean.length() < 3 ? "tmp" + clean : clean;
    }

    public static BufferedImage renderAnnotationPreview(PresentationTileConfig config) {
        return renderAnnotationPreview(config, null);
    }

    public static BufferedImage renderAnnotationPreview(PresentationTileConfig config,
                                                        PresentationTileRecord representative) {
        int sourceW = representative == null ? 420 : representative.widthPx();
        int sourceH = representative == null ? 260 : representative.heightPx();
        double previewScale = previewScale(sourceW, sourceH);
        int w = Math.max(1, (int) Math.round(sourceW * previewScale));
        int h = Math.max(1, (int) Math.round(sourceH * previewScale));

        BufferedImage preview = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = preview.createGraphics();
        try {
            applyQualityHints(g);
            drawSyntheticPreviewBackground(g, w, h);
            PresentationTileRecord record = previewRecord(representative, sourceW, sourceH);
            Map<String, String> conditions = new LinkedHashMap<String, String>();
            conditions.put(record.animal(), "Control");
            drawAnnotations(g, record, conditions, config,
                    new Rectangle(0, 0, w, h), previewScale, previewScale, previewScale);
        } finally {
            g.dispose();
        }
        return preview;
    }

    private static PresentationTileRecord previewRecord(PresentationTileRecord representative,
                                                        int width,
                                                        int height) {
        if (representative == null) {
            return new PresentationTileRecord(
                    null, "Animal1", "LH", "Cortex", "DAPI", "DAPI",
                    0, width, height, 0.5, 0.5);
        }
        return new PresentationTileRecord(
                null,
                representative.animal(),
                representative.hemisphere(),
                representative.region(),
                representative.imageId(),
                representative.outputName(),
                representative.stainName(),
                representative.channelIndex(),
                width,
                height,
                representative.pixelWidthUm(),
                representative.pixelHeightUm());
    }

    private static double previewScale(int width, int height) {
        int maxDimension = Math.max(width, height);
        if (maxDimension <= MAX_ANNOTATION_PREVIEW_DIMENSION) return 1.0;
        return (double) MAX_ANNOTATION_PREVIEW_DIMENSION / maxDimension;
    }

    private static void drawSyntheticPreviewBackground(Graphics2D g, int width, int height) {
        g.setColor(new Color(18, 18, 20));
        g.fillRect(0, 0, width, height);
        g.setPaint(new java.awt.GradientPaint(
                0, 0, new Color(12, 26, 55),
                width, height, new Color(15, 90, 82)));
        g.fillRect(0, 0, width, height);
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.28f));
        g.setColor(new Color(160, 220, 255));
        int spot = Math.max(12, Math.min(width, height) / 18);
        int step = Math.max(spot * 2, Math.min(width, height) / 8);
        for (int y = step / 2; y < height; y += step) {
            for (int x = step / 2; x < width; x += step) {
                g.fillOval(x - spot / 2, y - spot / 2, spot, spot);
            }
        }
        g.setComposite(AlphaComposite.SrcOver);
    }

    private static void drawColumnHeaders(Graphics2D g, List<String> columns,
                                          int x0, int y, int cell, int colGap,
                                          int headerHeight, Font font) {
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        for (int i = 0; i < columns.size(); i++) {
            int x = x0 + i * (cell + colGap);
            g.setColor(TILE_TEXT);
            String label = fitSingleLine(columns.get(i), fm, cell);
            int textX = x + Math.max(0, (cell - fm.stringWidth(label)) / 2);
            int textY = y + 2 + fm.getAscent();
            g.drawString(label, textX, textY);
        }
    }

    private static void drawGroupLabel(Graphics2D g, String label,
                                       int x, int y, int width, int height, Font font) {
        if (height <= 0 || width <= 0) return;
        g.setFont(font);
        g.setColor(TILE_TEXT);
        FontMetrics fm = g.getFontMetrics();
        String fitted = fitSingleLine(label, fm, width);
        g.drawString(fitted, x, y + 2 + fm.getAscent());
    }

    private static void drawRowLabel(Graphics2D g, String label,
                                     int x, int y, int width, int height, Font font) {
        if (width <= 0 || height <= 0) return;
        g.setFont(font);
        g.setColor(TILE_TEXT);
        FontMetrics fm = g.getFontMetrics();
        List<String> lines = fitWrappedLines(label, fm, width, height);
        int lineHeight = fm.getHeight();
        int totalHeight = lines.size() * lineHeight;
        int textY = y + Math.max(fm.getAscent(),
                (height - totalHeight) / 2 + fm.getAscent());
        for (String line : lines) {
            g.drawString(line, x, textY);
            textY += lineHeight;
        }
    }

    private static void drawCell(Graphics2D g,
                                 PresentationTileRecord record,
                                 Map<String, String> conditions,
                                 PresentationTileConfig config,
                                 int x, int y, int cell) {
        g.setColor(Color.BLACK);
        g.fillRect(x, y, cell, cell);
        g.setColor(TILE_LINE);
        g.drawRect(x, y, cell, cell);

        if (record == null) {
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            g.setColor(TILE_HELP_TEXT);
            g.drawString("Not saved", x + 12, y + 24);
            return;
        }

        File imageFile = record.preferredImageFile(config.annotateIndividualImages());
        BufferedImage image = null;
        try {
            image = imageFile == null ? null : ImageIO.read(imageFile);
        } catch (IOException e) {
            IJ.log("  - Warning: could not read tile image " + imageFile + ": " + e.getMessage());
        }
        if (image == null) {
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            g.setColor(TILE_HELP_TEXT);
            g.drawString("Missing image", x + 12, y + 24);
            return;
        }

        double scale = Math.min((double) cell / image.getWidth(), (double) cell / image.getHeight());
        int drawW = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int drawH = Math.max(1, (int) Math.round(image.getHeight() * scale));
        int drawX = x + (cell - drawW) / 2;
        int drawY = y + (cell - drawH) / 2;
        g.drawImage(image, drawX, drawY, drawW, drawH, null);

        if (config.annotateOverviewTile() && !config.annotateIndividualImages()) {
            double recordScale = drawW / (double) Math.max(1, record.widthPx());
            drawAnnotations(g, record, conditions, config,
                    new Rectangle(drawX, drawY, drawW, drawH), recordScale);
        }
    }

    public static void drawAnnotations(Graphics2D g,
                                        PresentationTileRecord record,
                                        Map<String, String> conditions,
                                        PresentationTileConfig config,
                                        Rectangle imageRect,
                                        double scaleFactor) {
        drawAnnotations(g, record, conditions, config, imageRect,
                scaleFactor, 1.0, Math.max(0.6, scaleFactor));
    }

    public static void drawAnnotations(Graphics2D g,
                                        PresentationTileRecord record,
                                        Map<String, String> conditions,
                                        PresentationTileConfig config,
                                        Rectangle imageRect,
                                        double scaleFactor,
                                        double styleScale,
                                        double scaleBarThicknessScale) {
        if (record == null || config == null || imageRect == null) return;
        double safeStyleScale = styleScale > 0 && Double.isFinite(styleScale) ? styleScale : 1.0;
        double safeThicknessScale = scaleBarThicknessScale > 0 && Double.isFinite(scaleBarThicknessScale)
                ? scaleBarThicknessScale : safeStyleScale;
        if (config.labelMode() != PresentationTileConfig.LabelMode.NONE) {
            String label = labelText(record, conditions, config);
            if (!label.isEmpty()) {
                drawTextLabel(g, label, imageRect, config.labelPosition(),
                        config.labelFontSizePx(), config.annotationColor(), safeStyleScale,
                        config.hasLabelFraction() ? config.labelFracX() : -1.0,
                        config.hasLabelFraction() ? config.labelFracY() : -1.0);
            }
        }
        if (config.scaleBarEnabled()) {
            drawScaleBar(g, record, imageRect, scaleFactor, safeStyleScale, safeThicknessScale, config);
        }
    }

    private static void drawScaleBar(Graphics2D g,
                                     PresentationTileRecord record,
                                     Rectangle imageRect,
                                     double scaleFactor,
                                     double styleScale,
                                     double thicknessScale,
                                     PresentationTileConfig config) {
        double pixelWidthUm = record.pixelWidthUm();
        if (!Double.isFinite(pixelWidthUm) || pixelWidthUm <= 0) {
            return;
        }
        int barLen = (int) Math.round((config.scaleBarLengthUm() / pixelWidthUm) * scaleFactor);
        int inset = scaledDimension(Math.max(8, config.labelFontSizePx() / 2), styleScale);
        barLen = Math.min(barLen, imageRect.width - inset * 2);
        if (barLen < 4) return;

        int thickness = scaledDimension(config.scaleBarThicknessPx(), thicknessScale);
        int x;
        int y;
        boolean captionBelow;
        if (config.hasScaleBarFraction()) {
            x = fracOriginX(imageRect, config.scaleBarFracX(), barLen, inset);
            y = fracOriginY(imageRect, config.scaleBarFracY(), thickness, inset);
            captionBelow = (y + thickness / 2) < (imageRect.y + imageRect.height / 2);
        } else {
            x = horizontalPosition(config.scaleBarPosition(), imageRect, inset, barLen);
            y = verticalPosition(config.scaleBarPosition(), imageRect, inset, thickness);
            captionBelow = isTop(config.scaleBarPosition());
        }

        g.setColor(config.annotationColor());
        g.fillRect(x, y, barLen, thickness);

        String label = formatScale(config.scaleBarLengthUm()) + " um";
        int baseFontSize = Math.max(8, (int) Math.round(config.labelFontSizePx() * 0.78));
        Font font = new Font(Font.SANS_SERIF, Font.BOLD,
                scaledDimension(baseFontSize, styleScale));
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        int textX = x + Math.max(0, (barLen - fm.stringWidth(label)) / 2);
        int textY = y - scaledDimension(4, styleScale);
        if (captionBelow) {
            textY = y + thickness + fm.getAscent() + scaledDimension(3, styleScale);
        }
        drawAnnotationText(g, label, textX, textY, config.annotationColor());
    }

    private static void drawTextLabel(Graphics2D g,
                                      String text,
                                      Rectangle imageRect,
                                      PresentationTileConfig.Position position,
                                      int fontSize,
                                      Color color,
                                      double styleScale,
                                      double fracX,
                                      double fracY) {
        Font font = new Font(Font.SANS_SERIF, Font.BOLD,
                scaledDimension(fontSize, styleScale));
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        int inset = scaledDimension(Math.max(8, fontSize / 2), styleScale);
        int textW = fm.stringWidth(text);
        int textX;
        int textY;
        if (fracX >= 0 && fracY >= 0) {
            int textH = fm.getAscent() + fm.getDescent();
            textX = fracOriginX(imageRect, fracX, textW, inset);
            textY = fracOriginY(imageRect, fracY, textH, inset) + fm.getAscent();
        } else {
            textX = horizontalPosition(position, imageRect, inset, textW);
            if (isTop(position)) {
                textY = imageRect.y + inset + fm.getAscent();
            } else {
                textY = imageRect.y + imageRect.height - inset;
            }
        }
        drawAnnotationText(g, text, textX, textY, color);
    }

    /**
     * Top-left X for a {@code contentWidth}-wide box at {@code frac} of the
     * tile width, clamped so the box stays inside {@code rect} minus {@code inset}.
     */
    private static int fracOriginX(Rectangle rect, double frac, int contentWidth, int inset) {
        int min = rect.x + inset;
        int max = rect.x + rect.width - inset - contentWidth;
        if (max < min) {
            max = min;
        }
        int pos = rect.x + (int) Math.round(frac * rect.width);
        return Math.max(min, Math.min(max, pos));
    }

    private static int fracOriginY(Rectangle rect, double frac, int contentHeight, int inset) {
        int min = rect.y + inset;
        int max = rect.y + rect.height - inset - contentHeight;
        if (max < min) {
            max = min;
        }
        int pos = rect.y + (int) Math.round(frac * rect.height);
        return Math.max(min, Math.min(max, pos));
    }

    private static void drawAnnotationText(Graphics2D g, String text, int textX, int baseline,
                                           Color color) {
        g.setColor(color);
        g.drawString(text, textX, baseline);
    }

    private static int scaledDimension(int value, double scale) {
        double safeScale = scale > 0 && Double.isFinite(scale) ? scale : 1.0;
        return Math.max(1, (int) Math.round(value * safeScale));
    }

    private static int horizontalPosition(PresentationTileConfig.Position position,
                                          Rectangle rect, int inset, int contentWidth) {
        if (position == PresentationTileConfig.Position.TOP_RIGHT
                || position == PresentationTileConfig.Position.BOTTOM_RIGHT) {
            return rect.x + rect.width - inset - contentWidth;
        }
        return rect.x + inset;
    }

    private static int verticalPosition(PresentationTileConfig.Position position,
                                        Rectangle rect, int inset, int contentHeight) {
        if (position == PresentationTileConfig.Position.TOP_LEFT
                || position == PresentationTileConfig.Position.TOP_RIGHT) {
            return rect.y + inset;
        }
        return rect.y + rect.height - inset - contentHeight;
    }

    private static boolean isTop(PresentationTileConfig.Position position) {
        return position == PresentationTileConfig.Position.TOP_LEFT
                || position == PresentationTileConfig.Position.TOP_RIGHT;
    }

    private static String labelText(PresentationTileRecord record,
                                    Map<String, String> conditions,
                                    PresentationTileConfig config) {
        String template;
        switch (config.labelMode()) {
            case NONE:
                return "";
            case IMAGE_NAME:
                template = "{animal} {hemisphere} {region}";
                break;
            case CONDITION_IMAGE:
                template = "{condition} {animal} {hemisphere} {region}";
                break;
            case CUSTOM:
                template = config.customLabelTemplate().isEmpty()
                        ? "{stain}" : config.customLabelTemplate();
                break;
            case STAIN_NAME:
            default:
                template = "{stain}";
                break;
        }
        return replaceTokens(template, record, conditions).replaceAll("\\s+", " ").trim();
    }

    private static String replaceTokens(String template,
                                        PresentationTileRecord record,
                                        Map<String, String> conditions) {
        String text = template == null ? "" : template;
        text = text.replace("{animal}", record.animal());
        text = text.replace("{condition}", conditionFor(record, conditions));
        text = text.replace("{hemisphere}", record.hemisphere());
        text = text.replace("{region}", record.region());
        text = text.replace("{channel}", record.stainName());
        text = text.replace("{stain}", record.stainName());
        text = text.replace("{output}", record.outputName());
        return text;
    }

    private static List<String> orderedOutputNames(List<PresentationTileRecord> records,
                                                   List<String> requestedOrder) {
        LinkedHashSet<String> names = new LinkedHashSet<String>();
        for (String requested : requestedOrder == null ? Collections.<String>emptyList() : requestedOrder) {
            String trimmed = requested == null ? "" : requested.trim();
            if (!trimmed.isEmpty() && containsOutput(records, trimmed)) {
                names.add(trimmed);
            }
        }
        for (PresentationTileRecord record : records) {
            if (!record.outputName().isEmpty()) {
                names.add(record.outputName());
            }
        }
        return new ArrayList<String>(names);
    }

    private static boolean containsOutput(List<PresentationTileRecord> records, String outputName) {
        for (PresentationTileRecord record : records) {
            if (record.outputName().equals(outputName)) return true;
        }
        return false;
    }

    private static List<Row> orderedRows(List<PresentationTileRecord> records,
                                         Map<String, String> conditions,
                                         PresentationTileConfig.GroupRowsBy groupRowsBy) {
        LinkedHashMap<String, Row> rows = new LinkedHashMap<String, Row>();
        for (PresentationTileRecord record : records) {
            String groupLabel = groupRowsBy == PresentationTileConfig.GroupRowsBy.CONDITION
                    ? conditionFor(record, conditions) : record.animal();
            if (groupLabel.isEmpty()) groupLabel = "Unassigned";
            Row existing = rows.get(record.imageKey());
            if (existing == null) {
                rows.put(record.imageKey(), new Row(record.imageKey(),
                        record.imageLabel(), groupLabel, record));
            }
        }

        List<Row> out = new ArrayList<Row>(rows.values());
        Collections.sort(out, new Comparator<Row>() {
            @Override
            public int compare(Row a, Row b) {
                int group = compareText(a.groupLabel, b.groupLabel);
                if (group != 0) return group;
                int animal = compareText(a.record.animal(), b.record.animal());
                if (animal != 0) return animal;
                int region = compareText(a.record.region(), b.record.region());
                if (region != 0) return region;
                int hemisphere = compareText(a.record.hemisphere(), b.record.hemisphere());
                if (hemisphere != 0) return hemisphere;
                return compareText(a.record.imageId(), b.record.imageId());
            }
        });
        return out;
    }

    private static List<PresentationTileRecord> safeRecords(List<PresentationTileRecord> records) {
        List<PresentationTileRecord> out = new ArrayList<PresentationTileRecord>();
        if (records == null) return out;
        for (PresentationTileRecord record : records) {
            if (record != null) out.add(record);
        }
        return out;
    }

    private static BufferedImage toArgb(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_ARGB) return image;
        BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(image, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    public static void applyQualityHints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setStroke(new BasicStroke(1f));
    }

    private static String conditionFor(PresentationTileRecord record, Map<String, String> conditions) {
        if (conditions != null) {
            String[] keys = new String[]{
                    record.imageId(),
                    record.imageKey(),
                    record.animal()
            };
            for (int i = 0; i < keys.length; i++) {
                String key = keys[i];
                if (key == null || key.trim().isEmpty()) continue;
                String condition = conditions.get(key);
                if (condition != null && !condition.trim().isEmpty()) {
                    return condition.trim();
                }
            }
        }
        return "Unassigned";
    }

    private static List<String> wrap(String text, FontMetrics fm, int maxWidth) {
        List<String> lines = new ArrayList<String>();
        String source = text == null ? "" : text.trim();
        if (source.isEmpty()) {
            lines.add("");
            return lines;
        }
        String[] words = source.split("\\s+");
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (fm.stringWidth(candidate) <= maxWidth || line.length() == 0) {
                line.setLength(0);
                line.append(candidate);
            } else {
                lines.add(line.toString());
                line.setLength(0);
                line.append(word);
            }
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines;
    }

    private static int tightRowLabelWidth(List<Row> rows, FontMetrics fm, int cell) {
        int maxTextWidth = 0;
        int longestWordWidth = 0;
        for (Row row : rows) {
            String label = row == null ? "" : row.label;
            String source = label == null ? "" : label.trim();
            maxTextWidth = Math.max(maxTextWidth, fm.stringWidth(source));
            if (!source.isEmpty()) {
                String[] words = source.split("\\s+");
                for (String word : words) {
                    longestWordWidth = Math.max(longestWordWidth, fm.stringWidth(word));
                }
            }
        }
        if (maxTextWidth <= 0) return 0;

        int preferredMin = Math.min(96, Math.max(56, cell / 2));
        int minWidth = Math.min(maxTextWidth, Math.max(longestWordWidth, preferredMin));
        int maxWidth = Math.min(maxTextWidth, Math.max(minWidth, Math.min(260, Math.max(96, cell))));
        int availableHeight = Math.max(fm.getHeight(), cell - 2);
        for (int width = minWidth; width <= maxWidth; width += 4) {
            if (rowLabelsFit(rows, fm, width, availableHeight)) {
                return width;
            }
        }
        return maxWidth;
    }

    private static boolean rowLabelsFit(List<Row> rows, FontMetrics fm, int width, int height) {
        int lineHeight = fm.getHeight();
        int maxLines = Math.max(1, height / Math.max(1, lineHeight));
        for (Row row : rows) {
            List<String> lines = wrap(row == null ? "" : row.label, fm, width);
            if (lines.size() > maxLines) return false;
            for (String line : lines) {
                if (fm.stringWidth(line) > width) return false;
            }
        }
        return true;
    }

    private static List<String> fitWrappedLines(String text, FontMetrics fm, int width, int height) {
        List<String> wrapped = wrap(text, fm, width);
        int maxLines = Math.max(1, height / Math.max(1, fm.getHeight()));
        List<String> out = new ArrayList<String>();
        int count = Math.min(wrapped.size(), maxLines);
        for (int i = 0; i < count; i++) {
            String line = wrapped.get(i);
            if (i == count - 1 && wrapped.size() > count) {
                line = ellipsize(line, fm, width);
            } else {
                line = fitSingleLine(line, fm, width);
            }
            out.add(line);
        }
        if (out.isEmpty()) out.add("");
        return out;
    }

    private static String fitSingleLine(String text, FontMetrics fm, int width) {
        String clean = text == null ? "" : text.trim();
        if (width <= 0 || clean.isEmpty()) return "";
        if (fm.stringWidth(clean) <= width) return clean;
        return ellipsize(clean, fm, width);
    }

    private static String ellipsize(String text, FontMetrics fm, int width) {
        if (width <= 0) return "";
        String suffix = "...";
        if (fm.stringWidth(suffix) > width) return "";
        String clean = text == null ? "" : text.trim();
        int end = clean.length();
        while (end > 0) {
            String candidate = clean.substring(0, end).trim() + suffix;
            if (fm.stringWidth(candidate) <= width) return candidate;
            end--;
        }
        return suffix;
    }

    private static int compareText(String a, String b) {
        String aa = a == null ? "" : a;
        String bb = b == null ? "" : b;
        return aa.compareToIgnoreCase(bb);
    }

    private static String absolutePath(File file) {
        return file == null ? "" : file.getAbsolutePath();
    }

    private static String field(String[] row, Map<String, Integer> columns, String name) {
        Integer index = columns.get(name);
        if (index == null || index.intValue() < 0 || index.intValue() >= row.length) return "";
        return row[index.intValue()].trim();
    }

    private static String firstField(String[] row, Map<String, Integer> columns,
                                     String primary, String fallback) {
        String value = field(row, columns, primary);
        return value.isEmpty() ? field(row, columns, fallback) : value;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String formatNumber(double value) {
        return Double.isFinite(value) ? String.valueOf(value) : "";
    }

    private static String formatScale(double value) {
        if (Math.abs(value - Math.round(value)) < 0.0001) {
            return String.valueOf((long) Math.round(value));
        }
        return String.format(java.util.Locale.US, "%.2f", value);
    }

    private static final class TileLayout {
        final int width;
        final int height;
        final int margin;
        final int colGap;
        final int rowGap;
        final int rowLabelWidth;
        final int rowLabelGap;
        final int headerHeight;
        final int groupHeaderHeight;

        TileLayout(int width,
                   int height,
                   int margin,
                   int colGap,
                   int rowGap,
                   int rowLabelWidth,
                   int rowLabelGap,
                   int headerHeight,
                   int groupHeaderHeight) {
            this.width = width;
            this.height = height;
            this.margin = margin;
            this.colGap = colGap;
            this.rowGap = rowGap;
            this.rowLabelWidth = rowLabelWidth;
            this.rowLabelGap = rowLabelGap;
            this.headerHeight = headerHeight;
            this.groupHeaderHeight = groupHeaderHeight;
        }
    }

    private static final class Row {
        final String key;
        final String label;
        final String groupLabel;
        final PresentationTileRecord record;

        Row(String key, String label, String groupLabel, PresentationTileRecord record) {
            this.key = key;
            this.label = label;
            this.groupLabel = groupLabel;
            this.record = record;
        }
    }
}
