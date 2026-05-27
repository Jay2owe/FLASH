package flash.pipeline.results;

import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.io.IoUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Writes the small landing page for the user-facing results folder.
 */
public final class StartHereWriter {

    private StartHereWriter() {}

    public static File write(FlashProjectLayout layout) throws IOException {
        if (layout == null) {
            throw new IllegalArgumentException("Layout must not be null.");
        }
        File out = layout.startHereWriteFile();
        File parent = out.getParentFile();
        if (parent != null) {
            IoUtils.mustMkdirs(parent);
        }
        File temp = File.createTempFile(tempPrefix(out), ".tmp",
                parent == null ? new File(".") : parent);
        boolean moved = false;
        try {
            Writer writer = new OutputStreamWriter(new FileOutputStream(temp), StandardCharsets.UTF_8);
            try {
                writer.write(render(layout));
            } finally {
                writer.close();
            }
            IoUtils.moveReplacing(temp.toPath(), out.toPath());
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temp.toPath());
            }
        }
        return out;
    }

    private static String tempPrefix(File target) {
        String name = target == null ? "start-here" : target.getName();
        String clean = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return clean.length() < 3 ? "tmp" + clean : clean;
    }

    static String render(FlashProjectLayout layout) {
        StringBuilder html = new StringBuilder(4096);
        html.append("<!DOCTYPE html>\n");
        html.append("<html lang=\"en\">\n<head>\n");
        html.append("<meta charset=\"UTF-8\">\n");
        html.append("<title>FLASH Results</title>\n");
        html.append("<style>");
        html.append("body{font-family:-apple-system,BlinkMacSystemFont,Segoe UI,sans-serif;");
        html.append("margin:32px;line-height:1.45;color:#202124;background:#fff;}");
        html.append("h1{font-size:28px;margin:0 0 8px;}h2{font-size:18px;margin:28px 0 8px;}");
        html.append("p{max-width:760px;}ul{padding-left:22px;}li{margin:6px 0;}");
        html.append("a{color:#0b57d0;text-decoration:none;}a:hover{text-decoration:underline;}");
        html.append("code{background:#f1f3f4;padding:2px 4px;border-radius:3px;}");
        html.append("</style>\n");
        html.append("</head>\n<body>\n");
        html.append("<h1>FLASH Results</h1>\n");
        html.append("<p>Open the folders below to find tables, images, quality-control reports, and run records for this project.</p>\n");

        html.append("<h2>Main files</h2>\n<ul>\n");
        appendOptionalFile(html, layout.summaryWorkbookWriteFile(), FlashProjectLayout.SUMMARY_WORKBOOK_FILENAME);
        appendOptionalFile(html, layout.qcReportWriteFile(),
                FlashProjectLayout.QC_DIR + "/" + FlashProjectLayout.QC_REPORT_FILENAME);
        html.append("<li><a href=\"Tables/Project%20Summary/\">Tables/Project Summary/</a></li>\n");
        html.append("</ul>\n");

        html.append("<h2>Folders</h2>\n<ul>\n");
        appendFolder(html, "Tables", "CSV tables grouped by analysis.");
        appendFolder(html, "Presentation Images", "Images prepared for figures and review.");
        appendFolder(html, "Analysis Images", "Masks, label maps, overlays, heatmaps, and other analysis images.");
        appendFolder(html, "QC", "Quality-control report and overlay images.");
        appendFolder(html, "Run Records", "Run history, settings snapshots, replay commands, and analysis details.");
        html.append("</ul>\n");

        html.append("</body>\n</html>\n");
        return html.toString();
    }

    private static void appendOptionalFile(StringBuilder html, File file, String relativePath) {
        if (file != null && file.isFile()) {
            html.append("<li><a href=\"")
                    .append(encodeHref(relativePath))
                    .append("\">")
                    .append(escape(relativePath))
                    .append("</a></li>\n");
        }
    }

    private static void appendFolder(StringBuilder html, String folder, String description) {
        html.append("<li><a href=\"")
                .append(encodeHref(folder))
                .append("/\">")
                .append(escape(folder))
                .append("/</a> - ")
                .append(escape(description))
                .append("</li>\n");
    }

    private static String encodeHref(String path) {
        return path.replace(" ", "%20");
    }

    private static String escape(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
