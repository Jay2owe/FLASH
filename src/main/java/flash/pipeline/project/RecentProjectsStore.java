package flash.pipeline.project;

import flash.pipeline.bin.BinConfigIO;
import flash.pipeline.ui.wizard.JsonIO;
import ij.IJ;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read/write the recent-projects list at
 * {@code <pluginsDir>/.flash-recent.json}. Per-Fiji-install (not per-user),
 * so the list resets when Fiji is reinstalled.
 *
 * <p>All entry-points take an explicit {@code pluginsDir} so tests can use a
 * temp folder. Production callers use {@link #resolvePluginsDir()}.
 */
public final class RecentProjectsStore {
    public static final String FILE_NAME = ".flash-recent.json";
    private static final int SCHEMA_VERSION = 1;
    private static final String K_SCHEMA_VERSION = "schemaVersion";
    private static final String K_ENTRIES = "entries";
    private static final String K_NAME = "name";
    private static final String K_PATH = "path";
    private static final String K_LAST_OPENED_AT = "lastOpenedAt";

    private RecentProjectsStore() {
    }

    /** Returns the list ordered most-recent-first; never null. */
    public static List<RecentProject> read(File pluginsDir) {
        File file = file(pluginsDir);
        if (file == null || !file.isFile()) {
            return new ArrayList<RecentProject>();
        }
        try {
            String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            Map<String, Object> root = JsonIO.parseObject(json);
            int version = JsonIO.intValue(root.get(K_SCHEMA_VERSION), -1);
            if (version != SCHEMA_VERSION) {
                return new ArrayList<RecentProject>();
            }
            List<Object> rows = JsonIO.asList(root.get(K_ENTRIES));
            List<RecentProject> out = new ArrayList<RecentProject>();
            for (Object row : rows) {
                Map<String, Object> obj = JsonIO.asObject(row);
                String path = JsonIO.stringValue(obj.get(K_PATH));
                if (path == null || path.trim().isEmpty()) {
                    continue;
                }
                out.add(new RecentProject(
                        JsonIO.stringValue(obj.get(K_NAME)),
                        path,
                        longValue(obj.get(K_LAST_OPENED_AT), 0L)));
            }
            sortDescending(out);
            return out;
        } catch (IOException e) {
            IJ.log("[FLASH] Could not read " + file.getAbsolutePath() + ": " + e.getMessage());
            return new ArrayList<RecentProject>();
        }
    }

    public static void write(File pluginsDir, List<RecentProject> entries) throws IOException {
        if (pluginsDir == null) {
            throw new IOException("Cannot write " + FILE_NAME + " without a plugins directory.");
        }
        List<RecentProject> sorted = new ArrayList<RecentProject>(entries == null ? Collections.<RecentProject>emptyList() : entries);
        sortDescending(sorted);
        if (sorted.size() > RecentProject.MAX_ENTRIES) {
            sorted = new ArrayList<RecentProject>(sorted.subList(0, RecentProject.MAX_ENTRIES));
        }

        Map<String, Object> root = JsonIO.object();
        root.put(K_SCHEMA_VERSION, Integer.valueOf(SCHEMA_VERSION));
        List<Object> rows = new ArrayList<Object>();
        for (RecentProject entry : sorted) {
            Map<String, Object> row = JsonIO.object();
            row.put(K_NAME, entry.name);
            row.put(K_PATH, entry.path);
            row.put(K_LAST_OPENED_AT, Long.valueOf(entry.lastOpenedAt));
            rows.add(row);
        }
        root.put(K_ENTRIES, rows);

        BinConfigIO.writeAtomic(new File(pluginsDir, FILE_NAME).toPath(),
                Arrays.asList(JsonIO.write(root)));
    }

    /**
     * Record that the user just opened the given project. De-duplicates by
     * {@code path} (case-insensitive), prepends the new entry, and caps the
     * list at {@link RecentProject#MAX_ENTRIES}. Returns the new list.
     */
    public static List<RecentProject> recordOpened(File pluginsDir, RecentProject entry) throws IOException {
        if (entry == null || entry.path == null || entry.path.trim().isEmpty()) {
            return read(pluginsDir);
        }
        List<RecentProject> existing = read(pluginsDir);
        List<RecentProject> next = new ArrayList<RecentProject>(existing.size() + 1);
        next.add(entry);
        String canonical = canonicalisePath(entry.path);
        for (RecentProject prior : existing) {
            if (!canonicalisePath(prior.path).equals(canonical)) {
                next.add(prior);
            }
        }
        write(pluginsDir, next);
        return new ArrayList<RecentProject>(next.subList(0, Math.min(next.size(), RecentProject.MAX_ENTRIES)));
    }

    /**
     * Resolve the running Fiji's plugins directory via {@link IJ#getDir(String)}.
     * Returns {@code null} when ImageJ is not initialised (e.g. unit tests) —
     * callers must handle null and route to a test path.
     */
    public static File resolvePluginsDir() {
        String dir = IJ.getDir("plugins");
        if (dir == null || dir.isEmpty()) {
            return null;
        }
        return new File(dir);
    }

    private static File file(File pluginsDir) {
        return pluginsDir == null ? null : new File(pluginsDir, FILE_NAME);
    }

    private static void sortDescending(List<RecentProject> entries) {
        Collections.sort(entries, new Comparator<RecentProject>() {
            @Override
            public int compare(RecentProject a, RecentProject b) {
                return Long.compare(b.lastOpenedAt, a.lastOpenedAt);
            }
        });
    }

    private static String canonicalisePath(String path) {
        if (path == null) return "";
        try {
            return new File(path).getCanonicalPath().toLowerCase(java.util.Locale.ROOT);
        } catch (IOException e) {
            return new File(path).getAbsolutePath().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
