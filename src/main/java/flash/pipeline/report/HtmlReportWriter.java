package flash.pipeline.report;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the QC Report as a standalone HTML file with inline CSS.
 * No JavaScript, no external dependencies. Works offline in any browser.
 */
public class HtmlReportWriter {

    public static void write(File file, QualityReport report) throws IOException {
        StringBuilder html = new StringBuilder(8192);
        html.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"UTF-8\">\n");
        html.append("<title>FLASH - QC Report</title>\n");
        html.append("<style>\n");
        html.append(CSS);
        html.append("</style>\n</head>\n<body>\n");

        // Header
        html.append("<div class=\"header\">\n");
        html.append("<h1>FLASH - Quality Control Report</h1>\n");
        html.append("<p class=\"meta\">Project: <code>").append(esc(report.getProjectDir())).append("</code></p>\n");
        html.append("<p class=\"meta\">Generated: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())).append("</p>\n");
        html.append("<p class=\"meta\">Analysis started: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(report.getStartTime()))).append("</p>\n");
        html.append("</div>\n\n");

        // Global Settings
        html.append("<details open>\n<summary>Global Settings</summary>\n");
        html.append("<table>\n<thead><tr><th>Setting</th><th>Value</th></tr></thead>\n<tbody>\n");
        addRow(html, "Headless Mode", String.valueOf(report.isHeadless()));
        addRow(html, "Parallel Processing", report.isParallel() ? "Yes (" + report.getThreadCount() + " threads)" : "No");
        addRow(html, "Aggressive Memory", String.valueOf(report.isAggressiveMemory()));
        addRow(html, "Verbose Logging", String.valueOf(report.isVerboseLogging()));
        addRow(html, "Overwrite Behavior", report.getOverwriteBehavior() != null ? report.getOverwriteBehavior() : "Auto-Overwrite");
        html.append("</tbody>\n</table>\n</details>\n\n");

        // Per-analysis sections
        SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss");
        boolean has3DQcData = !report.getImageQcData().isEmpty();
        boolean hasSpectralPreviewData = !report.getSpectralPreviewData().isEmpty();

        for (QualityReport.AnalysisSection section : report.getSections()) {
            boolean is3D = "3D Object Analysis".equals(section.name);
            boolean isSpectral = "Spectral Decontamination".equals(section.name);
            html.append("<details").append((is3D && has3DQcData) || (isSpectral && hasSpectralPreviewData)
                    ? " open" : "").append(">\n");
            html.append("<summary>").append(esc(section.name));
            html.append(" <span class=\"ts\">").append(timeFmt.format(new Date(section.timestamp))).append("</span>");
            html.append("</summary>\n");

            // Parameter table
            html.append("<table>\n<thead><tr><th>Parameter</th><th>Value</th></tr></thead>\n<tbody>\n");
            for (Map.Entry<String, String> e : section.params.entrySet()) {
                addRow(html, e.getKey(), e.getValue());
            }
            html.append("</tbody>\n</table>\n");

            // 3D Object Analysis image gallery
            if (is3D && has3DQcData) {
                html.append("<h3>Segmentation QC Gallery</h3>\n");
                for (Map.Entry<String, List<QualityReport.ChannelQC>> entry : report.getImageQcData().entrySet()) {
                    html.append("<div class=\"image-block\">\n");
                    html.append("<h4>").append(esc(entry.getKey())).append("</h4>\n");
                    for (QualityReport.ChannelQC qc : entry.getValue()) {
                        html.append("<div class=\"channel-strip\">\n");
                        html.append("<span class=\"ch-label\">").append(esc(qc.channelName));
                        html.append(" <em>(").append(esc(qc.lutColor)).append(")</em></span>\n");
                        html.append("<div class=\"thumbs\">\n");
                        appendThumb(html, "Original", qc.originalB64);
                        appendThumb(html, "Mask", qc.maskB64);
                        appendThumb(html, "Overlay", qc.overlayB64);
                        html.append("</div>\n</div>\n");
                    }
                    html.append("</div>\n");
                }
            }

            if (isSpectral && hasSpectralPreviewData) {
                html.append("<h3>Spectral Preview Gallery</h3>\n");
                LinkedHashMap<String, List<QualityReport.SpectralPreviewQC>> byCondition =
                        new LinkedHashMap<String, List<QualityReport.SpectralPreviewQC>>();
                for (QualityReport.SpectralPreviewQC preview : report.getSpectralPreviewData()) {
                    String condition = preview == null || preview.conditionName == null || preview.conditionName.isEmpty()
                            ? "Unassigned"
                            : preview.conditionName;
                    List<QualityReport.SpectralPreviewQC> group = byCondition.get(condition);
                    if (group == null) {
                        group = new ArrayList<QualityReport.SpectralPreviewQC>();
                        byCondition.put(condition, group);
                    }
                    group.add(preview);
                }
                for (Map.Entry<String, List<QualityReport.SpectralPreviewQC>> entry : byCondition.entrySet()) {
                    html.append("<div class=\"image-block spectral-block\">\n");
                    html.append("<h4>").append(esc(entry.getKey())).append("</h4>\n");
                    for (QualityReport.SpectralPreviewQC preview : entry.getValue()) {
                        html.append("<div class=\"spectral-preview\">\n");
                        html.append("<div class=\"spectral-preview-header\">");
                        html.append("<span class=\"ch-label\">").append(esc(preview.imageLabel)).append("</span>");
                        html.append("<span class=\"spectral-meta\">")
                                .append(esc(preview.conditionRole))
                                .append(" | ")
                                .append(esc(preview.selectionRole))
                                .append("</span>");
                        html.append("</div>\n");
                        html.append("<div class=\"thumbs\">\n");
                        appendBufferedThumb(html, "Raw target", preview.rawTargetImage);
                        appendBufferedThumb(html, "Corrected target", preview.correctedTargetImage);
                        appendBufferedThumb(html, "Final overlay", preview.overlayImage);
                        html.append("</div>\n");
                        if (!preview.metricLines.isEmpty()
                                || !preview.coefficientLines.isEmpty()
                                || !preview.warningLines.isEmpty()) {
                            html.append("<div class=\"spectral-lines\">\n");
                            appendLineGroup(html, "Metrics", preview.metricLines, "metric-line");
                            appendLineGroup(html, "Coefficients", preview.coefficientLines, "coef-line");
                            appendLineGroup(html, "Warnings", preview.warningLines, "warning-line");
                            html.append("</div>\n");
                        }
                        html.append("</div>\n");
                    }
                    html.append("</div>\n");
                }
            }

            html.append("</details>\n\n");
        }

        // Footer
        html.append("<div class=\"footer\">Generated by FLASH (Fluorescence Automated Spatial Histology)</div>\n");
        html.append("</body>\n</html>\n");

        Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8);
        try {
            w.write(html.toString());
        } finally {
            w.close();
        }
    }

    private static void addRow(StringBuilder html, String key, String value) {
        html.append("<tr><td>").append(esc(key)).append("</td><td>").append(esc(value)).append("</td></tr>\n");
    }

    private static void appendThumb(StringBuilder html, String label, String b64) {
        html.append("<div class=\"thumb\">\n");
        html.append("<div class=\"thumb-label\">").append(label).append("</div>\n");
        if (b64 != null && !b64.isEmpty()) {
            html.append("<img src=\"data:image/jpeg;base64,").append(b64).append("\" alt=\"").append(label).append("\">\n");
        } else {
            html.append("<div class=\"no-img\">No image</div>\n");
        }
        html.append("</div>\n");
    }

    private static void appendBufferedThumb(StringBuilder html, String label, java.awt.image.BufferedImage image) {
        appendThumb(html, label, image == null ? "" : QualityReport.toBase64Jpeg(image, 0.8f));
    }

    private static void appendLineGroup(StringBuilder html,
                                        String title,
                                        List<String> lines,
                                        String cssClass) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        html.append("<div class=\"line-group ").append(cssClass).append("\">\n");
        html.append("<div class=\"line-title\">").append(esc(title)).append("</div>\n");
        html.append("<ul>\n");
        for (String line : lines) {
            html.append("<li>").append(esc(line)).append("</li>\n");
        }
        html.append("</ul>\n");
        html.append("</div>\n");
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static final String CSS =
            "* { box-sizing: border-box; margin: 0; padding: 0; }\n" +
            "body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;\n" +
            "  max-width: 1200px; margin: 0 auto; padding: 20px; background: #fafafa; color: #333; }\n" +
            ".header { margin-bottom: 24px; padding-bottom: 16px; border-bottom: 2px solid #2563eb; }\n" +
            ".header h1 { font-size: 1.5em; color: #1e40af; margin-bottom: 8px; }\n" +
            ".meta { font-size: 0.9em; color: #666; margin: 2px 0; }\n" +
            "code { background: #e5e7eb; padding: 2px 6px; border-radius: 3px; font-size: 0.85em; }\n" +
            "details { margin-bottom: 16px; background: #fff; border: 1px solid #e5e7eb;\n" +
            "  border-radius: 6px; overflow: hidden; }\n" +
            "summary { padding: 12px 16px; background: #f3f4f6; cursor: pointer;\n" +
            "  font-weight: 600; font-size: 1.05em; user-select: none; }\n" +
            "summary:hover { background: #e5e7eb; }\n" +
            ".ts { font-weight: 400; font-size: 0.8em; color: #888; margin-left: 8px; }\n" +
            "table { width: 100%; border-collapse: collapse; margin: 12px 0; }\n" +
            "th, td { text-align: left; padding: 8px 12px; border-bottom: 1px solid #e5e7eb; }\n" +
            "th { background: #f9fafb; font-weight: 600; font-size: 0.9em; color: #555; }\n" +
            "tbody tr:nth-child(even) { background: #f9fafb; }\n" +
            "tbody tr:hover { background: #eff6ff; }\n" +
            "h3 { margin: 16px 16px 8px; font-size: 1.1em; color: #1e40af; }\n" +
            ".image-block { margin: 8px 16px 16px; padding: 12px; background: #f9fafb;\n" +
            "  border: 1px solid #e5e7eb; border-radius: 4px; }\n" +
            ".image-block h4 { margin-bottom: 8px; font-size: 0.95em; }\n" +
            ".channel-strip { margin-bottom: 12px; }\n" +
            ".spectral-block { background: #f8fafc; }\n" +
            ".spectral-preview { margin-top: 12px; padding-top: 12px; border-top: 1px solid #dbeafe; }\n" +
            ".spectral-preview:first-of-type { margin-top: 0; padding-top: 0; border-top: 0; }\n" +
            ".spectral-preview-header { display: flex; justify-content: space-between; gap: 8px;\n" +
            "  align-items: baseline; margin-bottom: 8px; flex-wrap: wrap; }\n" +
            ".spectral-meta { font-size: 0.8em; color: #64748b; }\n" +
            ".ch-label { display: block; font-weight: 600; margin-bottom: 4px; font-size: 0.9em; }\n" +
            ".ch-label em { font-weight: 400; color: #888; }\n" +
            ".thumbs { display: flex; gap: 8px; flex-wrap: wrap; }\n" +
            ".thumb { text-align: center; }\n" +
            ".thumb-label { font-size: 0.75em; color: #888; margin-bottom: 2px; }\n" +
            ".thumb img { max-width: 300px; height: auto; border: 1px solid #ddd; border-radius: 3px; }\n" +
            ".no-img { width: 300px; height: 200px; background: #eee; display: flex;\n" +
            "  align-items: center; justify-content: center; color: #999; font-size: 0.85em;\n" +
            "  border: 1px solid #ddd; border-radius: 3px; }\n" +
            ".spectral-lines { display: flex; gap: 12px; flex-wrap: wrap; margin-top: 10px; }\n" +
            ".line-group { min-width: 220px; flex: 1 1 220px; background: #fff; border: 1px solid #e2e8f0;\n" +
            "  border-radius: 4px; padding: 8px 10px; }\n" +
            ".line-title { font-size: 0.8em; font-weight: 600; color: #334155; margin-bottom: 4px; }\n" +
            ".line-group ul { margin: 0; padding-left: 18px; }\n" +
            ".line-group li { font-size: 0.82em; color: #475569; margin: 2px 0; }\n" +
            ".warning-line { border-color: #fecaca; background: #fff7f7; }\n" +
            ".footer { margin-top: 32px; padding-top: 16px; border-top: 1px solid #e5e7eb;\n" +
            "  text-align: center; font-size: 0.85em; color: #999; }\n";
}
