package flash.pipeline.io;

import flash.pipeline.intelligence.MiniJson;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single-file runtime status store for a FLASH project.
 */
public final class ProjectStatusStore {

    public static final int SCHEMA_VERSION = 1;
    public static final String SECTION_ANALYSIS = "analysis";
    public static final String SECTION_SUMMARY_HISTORY = "summaryHistory";
    public static final String SECTION_MARKERS = "markers";
    public static final String SECTION_CLI_STATUS = "cliStatus";
    public static final String SECTION_LAST_RUN_RECIPE = "lastRunRecipe";

    private ProjectStatusStore() {
    }

    public static File statusFile(String directory) {
        return FlashProjectLayout.forDirectory(directory).statusWriteFile();
    }

    public static File statusFile(File projectRoot) {
        return FlashProjectLayout.forDirectory(requireProjectRoot(projectRoot).getAbsolutePath())
                .statusWriteFile();
    }

    public static synchronized Map<String, Object> load(String directory) throws IOException {
        return load(new File(directory));
    }

    public static synchronized Map<String, Object> load(File projectRoot) throws IOException {
        return new LinkedHashMap<String, Object>(readRoot(projectRoot));
    }

    public static synchronized Map<String, Object> readAnalysisStatus(File projectRoot,
                                                                       String analysisId) throws IOException {
        if (isBlank(analysisId)) {
            return Collections.emptyMap();
        }
        Map<String, Object> analysis = object(readRoot(projectRoot).get(SECTION_ANALYSIS));
        return object(analysis.get(analysisId.trim()));
    }

    public static synchronized void writeAnalysisStatus(File projectRoot,
                                                        String analysisId,
                                                        Map<String, Object> status) throws IOException {
        if (isBlank(analysisId) || status == null) {
            return;
        }
        update(projectRoot, new RootEditor() {
            @Override public void edit(LinkedHashMap<String, Object> root) {
                LinkedHashMap<String, Object> analysis = mutableObject(root.get(SECTION_ANALYSIS));
                analysis.put(analysisId.trim(), new LinkedHashMap<String, Object>(status));
                root.put(SECTION_ANALYSIS, analysis);
            }
        });
    }

    public static synchronized Map<String, Object> readSummaryHistory(String directory) throws IOException {
        return object(readRoot(new File(directory)).get(SECTION_SUMMARY_HISTORY));
    }

    public static synchronized void writeSummaryHistory(String directory,
                                                        Map<String, Object> snapshot) throws IOException {
        if (snapshot == null) {
            return;
        }
        update(new File(directory), new RootEditor() {
            @Override public void edit(LinkedHashMap<String, Object> root) {
                root.put(SECTION_SUMMARY_HISTORY, new LinkedHashMap<String, Object>(snapshot));
            }
        });
    }

    public static boolean hasMarker(String directory, String markerName) {
        try {
            return hasMarker(new File(directory), markerName);
        } catch (IOException e) {
            return false;
        }
    }

    public static synchronized boolean hasMarker(File projectRoot, String markerName) throws IOException {
        if (isBlank(markerName)) {
            return false;
        }
        Object value = object(readRoot(projectRoot).get(SECTION_MARKERS)).get(markerName.trim());
        return Boolean.TRUE.equals(value) || Boolean.parseBoolean(String.valueOf(value));
    }

    public static synchronized void setMarker(String directory,
                                              String markerName,
                                              boolean enabled) throws IOException {
        setMarker(new File(directory), markerName, enabled);
    }

    public static synchronized void setMarker(File projectRoot,
                                              String markerName,
                                              boolean enabled) throws IOException {
        if (isBlank(markerName)) {
            return;
        }
        update(projectRoot, new RootEditor() {
            @Override public void edit(LinkedHashMap<String, Object> root) {
                LinkedHashMap<String, Object> markers = mutableObject(root.get(SECTION_MARKERS));
                if (enabled) {
                    markers.put(markerName.trim(), Boolean.TRUE);
                } else {
                    markers.remove(markerName.trim());
                }
                root.put(SECTION_MARKERS, markers);
            }
        });
    }

    public static synchronized void writeCliStatus(File projectRoot,
                                                   boolean ok,
                                                   List<String> failed,
                                                   String reason) throws IOException {
        LinkedHashMap<String, Object> status = new LinkedHashMap<String, Object>();
        status.put("ok", Boolean.valueOf(ok));
        if (reason != null && !reason.trim().isEmpty()) {
            status.put("reason", reason);
        }
        List<String> failedRows = new ArrayList<String>();
        if (failed != null) {
            for (String item : failed) {
                if (item != null) {
                    failedRows.add(item);
                }
            }
        }
        status.put("failed", failedRows);
        writeSection(projectRoot, SECTION_CLI_STATUS, status);
    }

    public static synchronized void writeLastRunRecipe(String directory,
                                                       Map<String, Object> recipe) throws IOException {
        if (recipe == null) {
            return;
        }
        writeSection(new File(directory), SECTION_LAST_RUN_RECIPE,
                new LinkedHashMap<String, Object>(recipe));
    }

    private static synchronized void writeSection(File projectRoot,
                                                  String sectionName,
                                                  Map<String, Object> section) throws IOException {
        update(projectRoot, new RootEditor() {
            @Override public void edit(LinkedHashMap<String, Object> root) {
                root.put(sectionName, section);
            }
        });
    }

    private static void update(File projectRoot, RootEditor editor) throws IOException {
        LinkedHashMap<String, Object> root = readRoot(projectRoot);
        editor.edit(root);
        writeRoot(projectRoot, root);
    }

    private static LinkedHashMap<String, Object> readRoot(File projectRoot) throws IOException {
        File file = statusFile(projectRoot);
        if (!file.isFile()) {
            return newRoot();
        }
        Object parsed = MiniJson.parse(new String(Files.readAllBytes(file.toPath()),
                StandardCharsets.UTF_8));
        if (!(parsed instanceof Map)) {
            throw new IOException("Project status JSON root must be an object.");
        }
        return normalizeRoot(object(parsed));
    }

    private static void writeRoot(File projectRoot,
                                  LinkedHashMap<String, Object> root) throws IOException {
        File file = statusFile(projectRoot);
        File parent = file.getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }
        LinkedHashMap<String, Object> normalized = normalizeRoot(root);
        File temp = File.createTempFile("status-", ".tmp",
                parent == null ? new File(".") : parent);
        boolean moved = false;
        try {
            Files.write(temp.toPath(), (MiniJson.write(normalized) + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            moveAtomically(temp.toPath(), file.toPath());
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temp.toPath());
            }
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static LinkedHashMap<String, Object> newRoot() {
        LinkedHashMap<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("schemaVersion", Integer.valueOf(SCHEMA_VERSION));
        return root;
    }

    private static LinkedHashMap<String, Object> normalizeRoot(Map<String, Object> raw) {
        LinkedHashMap<String, Object> root = newRoot();
        for (String section : orderedSections()) {
            if (raw.containsKey(section)) {
                root.put(section, raw.get(section));
            }
        }
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String key = entry.getKey();
            if ("schemaVersion".equals(key) || root.containsKey(key)) {
                continue;
            }
            root.put(key, entry.getValue());
        }
        return root;
    }

    private static List<String> orderedSections() {
        List<String> sections = new ArrayList<String>();
        sections.add(SECTION_ANALYSIS);
        sections.add(SECTION_SUMMARY_HISTORY);
        sections.add(SECTION_MARKERS);
        sections.add(SECTION_CLI_STATUS);
        sections.add(SECTION_LAST_RUN_RECIPE);
        return sections;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (value instanceof Map) {
            return new LinkedHashMap<String, Object>((Map<String, Object>) value);
        }
        return Collections.emptyMap();
    }

    private static LinkedHashMap<String, Object> mutableObject(Object value) {
        return new LinkedHashMap<String, Object>(object(value));
    }

    private static File requireProjectRoot(File projectRoot) {
        if (projectRoot == null) {
            throw new IllegalArgumentException("Project directory must not be null.");
        }
        return projectRoot;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private interface RootEditor {
        void edit(LinkedHashMap<String, Object> root) throws IOException;
    }
}
