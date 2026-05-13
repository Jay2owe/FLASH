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
    private static final Color TILE_HEADER_BG = new Color(238, 241, 243);
    private static final Color TILE_GROUP_BG = new Color(225, 230, 233);
    private static final Color TILE_TEXT = new Color(35, 35, 35);
    private static final Color TILE_HELP_TEXT = new Color(90, 90, 90);

    private PresentationTileWriter() {}

    public static void writeRequestedOutputs(File splitMergeRoot,
                                             List<PresentationTileRecord> records,
                                             Map<String, String> conditions,
                                             PresentationTileConfig config) throws IOException {
        if (splitMergeRoot == null || records == null || records.isEmpty() || config == null) {
            return;
        }

        IoUtils.mustMkdirs(splitMergeRoot);
        if (config.annotateIndividualImages()) {
            File annotatedRoot = new File(splitMergeRoot, "Annotated Images");
            writeAnnotatedImageCopies(annotatedRoot, records, conditions, config);
        }

        writeManifest(new File(splitMergeRoot, "Presentation_Image_Manifest.csv"),
                records, conditions);

        if (config.createOverviewTile()) {
            File tileRoot = new File(splitMergeRoot, "Tiles");
            File out = new File(tileRoot, "Presentation_Overview_"
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

        PrintWriter pw = CsvSupport.newWriter(manifest);
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
            ImageIO.write(annotated, "png", out);
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

        int cell = config.cellSizePx();
        int margin = 18;
        int colGap = 10;
        int rowGap = 10;
        int rowLabelWidth = Math.max(190, Math.min(320, cell));
        int colHeaderHeight = 46;
        int groupHeaderHeight = 30;

        int groupCount = 0;
        String lastGroup = null;
        for (Row row : rows) {
            if (!row.groupLabel.equals(lastGroup)) {
                groupCount++;
                lastGroup = row.groupLabel;
            }
        }

        int width = margin * 2 + rowLabelWidth + columns.size() * cell
                + Math.max(0, columns.size() - 1) * colGap;
        int height = margin * 2 + colHeaderHeight
                + groupCount * groupHeaderHeight
                + rows.size() * cell
                + Math.max(0, rows.size() - 1) * rowGap;

        BufferedImage tile = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = tile.createGraphics();
        try {
            applyQualityHints(g);
            g.setColor(TILE_BG);
            g.fillRect(0, 0, width, height);

            Font headerFont = new Font(Font.SANS_SERIF, Font.BOLD, 16);
            Font rowFont = new Font(Font.SANS_SERIF, Font.PLAIN, 14);
            Font groupFont = new Font(Font.SANS_SERIF, Font.BOLD, 15);

            int x0 = margin + rowLabelWidth;
            int y = margin;
            drawColumnHeaders(g, columns, x0, y, cell, colGap, colHeaderHeight, headerFont);
            y += colHeaderHeight;

            lastGroup = null;
            for (Row row : rows) {
                if (!row.groupLabel.equals(lastGroup)) {
                    g.setColor(TILE_GROUP_BG);
                    g.fillRect(margin, y, width - margin * 2, groupHeaderHeight - 4);
                    g.setColor(TILE_TEXT);
                    g.setFont(groupFont);
                    FontMetrics fm = g.getFontMetrics();
                    g.drawString(row.groupLabel, margin + 10,
                            y + ((groupHeaderHeight - 4) + fm.getAscent() - fm.getDescent()) / 2);
                    y += groupHeaderHeight;
                    lastGroup = row.groupLabel;
                }

                drawRowLabel(g, row.label, margin, y, rowLabelWidth - 10, cell, rowFont);
                for (int c = 0; c < columns.size(); c++) {
                    int x = x0 + c * (cell + colGap);
                    PresentationTileRecord record = byRowAndColumn.get(row.key + "\n" + columns.get(c));
                    drawCell(g, record, conditions, config, x, y, cell);
                }
                y += cell + rowGap;
            }
        } finally {
            g.dispose();
        }

        File parent = outputFile.getParentFile();
        if (parent != null) IoUtils.mustMkdirs(parent);
        ImageIO.write(tile, "png", outputFile);
        IJ.log("  - Presentation overview tile written: " + outputFile.getAbsolutePath());
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
            PresentationTileRecord record = scaledPreviewRecord(representative, w, h, previewScale);
            Map<String, String> conditions = new LinkedHashMap<String, String>();
            conditions.put(record.animal(), "Control");
            drawAnnotations(g, record, conditions, config,
                    new Rectangle(0, 0, w, h), 1.0);
        } finally {
            g.dispose();
        }
        return preview;
    }

    private static PresentationTileRecord scaledPreviewRecord(PresentationTileRecord representative,
                                                              int width,
                                                              int height,
                                                              double previewScale) {
        if (representative == null) {
            return new PresentationTileRecord(
                    null, "Animal1", "LH", "Cortex", "DAPI", "DAPI",
                    0, width, height, 0.5, 0.5);
        }
        double pixelWidthUm = representative.pixelWidthUm();
        double pixelHeightUm = representative.pixelHeightUm();
        if (Double.isFinite(pixelWidthUm) && previewScale > 0) {
            pixelWidthUm = pixelWidthUm / previewScale;
        }
        if (Double.isFinite(pixelHeightUm) && previewScale > 0) {
            pixelHeightUm = pixelHeightUm / previewScale;
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
                pixelWidthUm,
                pixelHeightUm);
    }

    private static double previewScale(int width, int height) {
        int maxDimension = Math.max(width, height);
        if (maxDimension <= 4096) return 1.0;
        return 4096.0 / maxDimension;
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
            g.setColor(TILE_HEADER_BG);
            g.fillRect(x, y, cell, headerHeight - 8);
            g.setColor(TILE_LINE);
            g.drawRect(x, y, cell, headerHeight - 8);
            g.setColor(TILE_TEXT);
            String label = columns.get(i);
            int textX = x + Math.max(6, (cell - fm.stringWidth(label)) / 2);
            int textY = y + ((headerHeight - 8) + fm.getAscent() - fm.getDescent()) / 2;
            g.drawString(label, textX, textY);
        }
    }

    private static void drawRowLabel(Graphics2D g, String label,
                                     int x, int y, int width, int height, Font font) {
        g.setFont(font);
        g.setColor(TILE_TEXT);
        FontMetrics fm = g.getFontMetrics();
        List<String> lines = wrap(label, fm, width);
        int lineHeight = fm.getHeight();
        int totalHeight = lines.size() * lineHeight;
        int textY = y + Math.max(fm.getAscent() + 4,
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

    private static void drawAnnotations(Graphics2D g,
                                        PresentationTileRecord record,
                                        Map<String, String> conditions,
                                        PresentationTileConfig config,
                                        Rectangle imageRect,
                                        double scaleFactor) {
        if (record == null || config == null || imageRect == null) return;
        if (config.labelMode() != PresentationTileConfig.LabelMode.NONE) {
            String label = labelText(record, conditions, config);
            if (!label.isEmpty()) {
                drawTextLabel(g, label, imageRect, config.labelPosition(),
                        config.labelFontSizePx(), config.annotationColor());
            }
        }
        if (config.scaleBarEnabled()) {
            drawScaleBar(g, record, imageRect, scaleFactor, config);
        }
    }

    private static void drawScaleBar(Graphics2D g,
                                     PresentationTileRecord record,
                                     Rectangle imageRect,
                                     double scaleFactor,
                                     PresentationTileConfig config) {
        double pixelWidthUm = record.pixelWidthUm();
        if (!Double.isFinite(pixelWidthUm) || pixelWidthUm <= 0) {
            return;
        }
        int barLen = (int) Math.round((config.scaleBarLengthUm() / pixelWidthUm) * scaleFactor);
        int inset = Math.max(8, config.labelFontSizePx() / 2);
        barLen = Math.min(barLen, imageRect.width - inset * 2);
        if (barLen < 4) return;

        int thickness = Math.max(1, (int) Math.round(config.scaleBarThicknessPx() * Math.max(0.6, scaleFactor)));
        int x = horizontalPosition(config.scaleBarPosition(), imageRect, inset, barLen);
        int y = verticalPosition(config.scaleBarPosition(), imageRect, inset, thickness);

        g.setColor(config.annotationColor());
        g.fillRect(x, y, barLen, thickness);

        String label = formatScale(config.scaleBarLengthUm()) + " um";
        Font font = new Font(Font.SANS_SERIF, Font.BOLD,
                Math.max(8, (int) Math.round(config.labelFontSizePx() * 0.78)));
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        int textX = x + Math.max(0, (barLen - fm.stringWidth(label)) / 2);
        int textY = y - 4;
        if (isTop(config.scaleBarPosition())) {
            textY = y + thickness + fm.getAscent() + 3;
        }
        drawTextBox(g, label, textX, textY, fm, config.annotationColor());
    }

    private static void drawTextLabel(Graphics2D g,
                                      String text,
                                      Rectangle imageRect,
                                      PresentationTileConfig.Position position,
                                      int fontSize,
                                      Color color) {
        Font font = new Font(Font.SANS_SERIF, Font.BOLD, fontSize);
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        int inset = Math.max(8, fontSize / 2);
        int textW = fm.stringWidth(text);
        int textX = horizontalPosition(position, imageRect, inset, textW);
        int textY;
        if (isTop(position)) {
            textY = imageRect.y + inset + fm.getAscent();
        } else {
            textY = imageRect.y + imageRect.height - inset;
        }
        drawTextBox(g, text, textX, textY, fm, color);
    }

    private static void drawTextBox(Graphics2D g, String text, int textX, int baseline,
                                    FontMetrics fm, Color color) {
        int padX = 5;
        int padY = 3;
        int boxX = textX - padX;
        int boxY = baseline - fm.getAscent() - padY;
        int boxW = fm.stringWidth(text) + padX * 2;
        int boxH = fm.getAscent() + fm.getDescent() + padY * 2;

        Color bg = isLight(color)
                ? new Color(0, 0, 0, 145)
                : new Color(255, 255, 255, 185);
        java.awt.Composite oldComposite = g.getComposite();
        g.setComposite(AlphaComposite.SrcOver);
        g.setColor(bg);
        g.fillRect(boxX, boxY, boxW, boxH);
        g.setComposite(oldComposite);
        g.setColor(color);
        g.drawString(text, textX, baseline);
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

    private static void applyQualityHints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setStroke(new BasicStroke(1f));
    }

    private static String conditionFor(PresentationTileRecord record, Map<String, String> conditions) {
        if (conditions != null) {
            String condition = conditions.get(record.animal());
            if (condition != null && !condition.trim().isEmpty()) {
                return condition.trim();
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

    private static int compareText(String a, String b) {
        String aa = a == null ? "" : a;
        String bb = b == null ? "" : b;
        return aa.compareToIgnoreCase(bb);
    }

    private static boolean isLight(Color color) {
        if (color == null) return true;
        double luminance = (0.2126 * color.getRed())
                + (0.7152 * color.getGreen())
                + (0.0722 * color.getBlue());
        return luminance >= 128;
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
