package flash.pipeline.feedback;

import ij.IJ;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Builds the exact text files shown in the feedback preview and written to the ZIP. */
final class FeedbackReport {

    static final String DEFAULT_RECIPIENT = "jm3923@ic.ac.uk";
    static final int MAX_ATTACHMENT_BYTES = 1000000;

    private FeedbackReport() {
    }

    static LinkedHashMap<String, String> build(Request request,
                                                FeedbackDiagnostics.Snapshot diagnostics)
            throws IOException {
        Request safeRequest = request == null ? new Request() : request;
        FeedbackDiagnostics.Snapshot snapshot = diagnostics == null
                ? new FeedbackDiagnostics.Snapshot("", "", "") : diagnostics;
        LinkedHashMap<String, String> entries = new LinkedHashMap<String, String>();
        List<String> tags = tags(safeRequest, snapshot);

        StringBuilder feedback = new StringBuilder();
        feedback.append("FLASH feedback\n");
        feedback.append("Category: ").append(safe(safeRequest.category)).append('\n');
        feedback.append("Summary: ").append(safe(safeRequest.summary)).append('\n');
        feedback.append("Diagnostic tags: ").append(join(tags)).append('\n');
        feedback.append("Created: ").append(timestamp()).append("\n\n");
        feedback.append(safe(safeRequest.message).trim()).append('\n');
        entries.put("feedback.txt", FeedbackDiagnostics.redact(feedback.toString()));

        if (safeRequest.includeSystem) {
            entries.put("system-info.txt", FeedbackDiagnostics.redact(
                    systemInfo(safeRequest.includeProject ? safeRequest.projectDirectory : "")));
        } else if (safeRequest.includeProject && !safe(safeRequest.projectDirectory).trim().isEmpty()) {
            entries.put("project-context.txt", FeedbackDiagnostics.redact(
                    "Project directory: " + safeRequest.projectDirectory + "\n"));
        }
        if (safeRequest.includeLog && snapshot.hasLog()) {
            entries.put("imagej-log.txt", FeedbackDiagnostics.redact(snapshot.log));
        }
        if (safeRequest.includeExceptions && snapshot.hasExceptions()) {
            entries.put("exceptions.txt", FeedbackDiagnostics.redact(snapshot.exceptions));
        }
        if (safeRequest.includeConsole && snapshot.hasConsole()) {
            entries.put("console.txt", FeedbackDiagnostics.redact(snapshot.console));
        }

        int attachmentNumber = 1;
        for (File file : safeRequest.attachments) {
            if (file == null || !file.isFile()) continue;
            String name = safeEntryName(file.getName());
            if (name.isEmpty()) name = "diagnostic-" + attachmentNumber + ".txt";
            String entry = uniqueEntry(entries, "attached/" + name);
            entries.put(entry, FeedbackDiagnostics.redact(readText(file)));
            attachmentNumber++;
        }
        return entries;
    }

    static String preview(Map<String, String> entries) {
        StringBuilder out = new StringBuilder();
        if (entries == null || entries.isEmpty()) return "No diagnostic content selected.";
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            out.append("===== ").append(entry.getKey()).append(" =====\n");
            out.append(safe(entry.getValue())).append("\n\n");
        }
        return out.toString();
    }

    static List<String> tags(Request request, FeedbackDiagnostics.Snapshot diagnostics) {
        List<String> tags = new ArrayList<String>();
        if (request == null) return tags;
        if (request.includeLog && diagnostics != null && diagnostics.hasLog()) tags.add("log");
        if (request.includeExceptions && diagnostics != null && diagnostics.hasExceptions()) {
            tags.add("exception");
        }
        if (request.includeConsole && diagnostics != null && diagnostics.hasConsole()) tags.add("console");
        if (request.includeSystem) tags.add("system");
        if (request.includeProject && !safe(request.projectDirectory).trim().isEmpty()) tags.add("project");
        if (!request.attachments.isEmpty()) tags.add("attached-files");
        return tags;
    }

    static String recipient() {
        String configured = System.getProperty("flash.feedback.email", "").trim();
        return configured.isEmpty() ? DEFAULT_RECIPIENT : configured;
    }

    private static String systemInfo(String projectDirectory) {
        StringBuilder out = new StringBuilder();
        out.append("FLASH version: ").append(version()).append('\n');
        out.append("ImageJ version: ").append(safe(IJ.getVersion())).append('\n');
        out.append("Java: ").append(System.getProperty("java.version", "unknown")).append('\n');
        out.append("Java vendor: ").append(System.getProperty("java.vendor", "unknown")).append('\n');
        out.append("OS: ").append(System.getProperty("os.name", "unknown")).append(' ')
                .append(System.getProperty("os.version", "")).append(' ')
                .append(System.getProperty("os.arch", "")).append('\n');
        out.append("Processors: ").append(Runtime.getRuntime().availableProcessors()).append('\n');
        out.append("Max JVM memory: ").append(Runtime.getRuntime().maxMemory()).append('\n');
        if (!safe(projectDirectory).trim().isEmpty()) {
            out.append("Project directory: ").append(projectDirectory).append('\n');
        }
        return out.toString();
    }

    private static String version() {
        Package pkg = FeedbackReport.class.getPackage();
        String version = pkg == null ? null : pkg.getImplementationVersion();
        return version == null || version.trim().isEmpty() ? "4.0.0" : version;
    }

    private static String readText(File file) throws IOException {
        long length = file.length();
        if (length > MAX_ATTACHMENT_BYTES) {
            throw new IOException("Diagnostic text file is larger than 1 MB: " + file.getName());
        }
        byte[] bytes = Files.readAllBytes(file.toPath());
        String text;
        try {
            text = new String(bytes, StandardCharsets.UTF_8);
        } catch (RuntimeException ignored) {
            text = new String(bytes, Charset.defaultCharset());
        }
        return FeedbackDiagnostics.limit(text);
    }

    private static String uniqueEntry(Map<String, String> entries, String desired) {
        if (!entries.containsKey(desired)) return desired;
        int dot = desired.lastIndexOf('.');
        String base = dot > 0 ? desired.substring(0, dot) : desired;
        String ext = dot > 0 ? desired.substring(dot) : "";
        for (int i = 2; i < 1000; i++) {
            String candidate = base + "-" + i + ext;
            if (!entries.containsKey(candidate)) return candidate;
        }
        return base + "-copy" + ext;
    }

    private static String safeEntryName(String name) {
        return safe(name).replaceAll("[^A-Za-z0-9._-]", "_")
                .replace("..", "_");
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(new Date());
    }

    private static String join(List<String> values) {
        if (values == null || values.isEmpty()) return "none";
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) out.append(", ");
            out.append(value);
        }
        return out.toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    static final class Request {
        String category = "Bug / error";
        String summary = "";
        String message = "";
        boolean includeLog;
        boolean includeExceptions;
        boolean includeConsole;
        boolean includeSystem = true;
        boolean includeProject;
        String projectDirectory = "";
        final List<File> attachments = new ArrayList<File>();
    }
}
