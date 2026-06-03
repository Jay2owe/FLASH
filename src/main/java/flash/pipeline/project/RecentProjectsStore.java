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
 * Read/write the recent-projects list in the user's FLASH settings directory.
 *
 * <p>All entry-points take an explicit {@code storeDir} so tests can use a
 * temp folder. Production callers use {@link #resolveStoreDir()}.
 */
public final class RecentProjectsStore {
    public static final String FILE_NAME = ".flash-recent.json";
    private static final String STORE_DIR_NAME = ".flash";
    private static final int SCHEMA_VERSION = 1;
    private static final String K_SCHEMA_VERSION = "schemaVersion";
    private static final String K_ENTRIES = "entries";
    private static final String K_NAME = "name";
    private static final String K_PATH = "path";
    private static final String K_LAST_OPENED_AT = "lastOpenedAt";

    private RecentProjectsStore() {
    }

    /** Returns the list ordered most-recent-first; never null. */
    public static List<RecentProject> read(File storeDir) {
        return read(storeDir, legacyPluginsDirFor(storeDir));
    }

    static List<RecentProject> read(File storeDir, File legacyPluginsDir) {
        File file = migrateLegacyIfNeeded(storeDir, legacyPluginsDir);
        if (file == null || !file.isFile()) {
            return new ArrayList<RecentProject>();
        }
        return readExistingFile(file, storeDir);
    }

    private static List<RecentProject> readExistingFile(File file, File upgradeDir) {
        try {
            String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            Map<String, Object> root = JsonIO.parseObject(json);
            int version = JsonIO.intValue(root.get(K_SCHEMA_VERSION), -1);
            List<RecentProject> out = parseEntries(root);
            sortDescending(out);
            if (version != SCHEMA_VERSION && upgradeDir != null && !out.isEmpty()) {
                try {
                    write(upgradeDir, out);
                } catch (IOException e) {
                    IJ.log("[FLASH] Could not upgrade " + file.getAbsolutePath() + ": " + e.getMessage());
                }
            }
            return out;
        } catch (IOException e) {
            IJ.log("[FLASH] Could not read " + file.getAbsolutePath() + ": " + e.getMessage());
            return new ArrayList<RecentProject>();
        }
    }

    public static void write(File storeDir, List<RecentProject> entries) throws IOException {
        if (storeDir == null) {
            throw new IOException("Cannot write " + FILE_NAME + " without a recent-projects directory.");
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

        BinConfigIO.writeAtomic(new File(storeDir, FILE_NAME).toPath(),
                Arrays.asList(JsonIO.write(root)));
    }

    /**
     * Record that the user just opened the given project. De-duplicates by
     * {@code path} (case-insensitive), prepends the new entry, and caps the
     * list at {@link RecentProject#MAX_ENTRIES}. Returns the new list.
     */
    public static List<RecentProject> recordOpened(File storeDir, RecentProject entry) throws IOException {
        return recordOpenedReplacing(storeDir, entry, null);
    }

    /**
     * Record an opened project while removing a stale path it replaced (for
     * example after a Dropbox project moved between Windows user profiles).
     */
    public static List<RecentProject> recordOpenedReplacing(File storeDir, RecentProject entry,
                                                            String obsoletePath) throws IOException {
        if (entry == null || entry.path == null || entry.path.trim().isEmpty()) {
            return read(storeDir);
        }
        List<RecentProject> existing = read(storeDir);
        List<RecentProject> next = new ArrayList<RecentProject>(existing.size() + 1);
        next.add(entry);
        String canonical = canonicalisePath(entry.path);
        String obsoleteCanonical = canonicalisePath(obsoletePath);
        for (RecentProject prior : existing) {
            String priorCanonical = canonicalisePath(prior.path);
            if (!priorCanonical.equals(canonical) && !priorCanonical.equals(obsoleteCanonical)) {
                next.add(prior);
            }
        }
        write(storeDir, next);
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

    /**
     * Resolve the per-user FLASH settings directory for recent projects.
     * Prefers ImageJ's user directory and falls back to {@code user.home}.
     */
    public static File resolveStoreDir() {
        File imagej = subdir(IJ.getDir("imagej"), true);
        if (imagej != null) {
            return imagej;
        }
        return subdir(System.getProperty("user.home"), true);
    }

    private static File migrateLegacyIfNeeded(File storeDir, File legacyPluginsDir) {
        File storeFile = file(storeDir);
        if (storeFile == null || storeFile.isFile()) {
            return storeFile;
        }
        File legacyFile = file(legacyPluginsDir);
        if (legacyFile == null || !legacyFile.isFile() || samePath(storeFile, legacyFile)) {
            return storeFile;
        }
        List<RecentProject> imported = readExistingFile(legacyFile, null);
        if (imported.isEmpty()) {
            return storeFile;
        }
        try {
            write(storeDir, imported);
            return storeFile;
        } catch (IOException e) {
            IJ.log("[FLASH] Could not migrate recent projects from "
                    + legacyFile.getAbsolutePath() + ": " + e.getMessage());
            return legacyFile;
        }
    }

    private static List<RecentProject> parseEntries(Map<String, Object> root) {
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
        return out;
    }

    private static File legacyPluginsDirFor(File storeDir) {
        if (storeDir == null || !STORE_DIR_NAME.equals(storeDir.getName())) {
            return null;
        }
        return resolvePluginsDir();
    }

    private static File subdir(String parent, boolean create) {
        if (parent == null || parent.trim().isEmpty()) {
            return null;
        }
        File dir = new File(parent, STORE_DIR_NAME);
        if (dir.isDirectory()) {
            return dir;
        }
        if (dir.exists()) {
            return null;
        }
        if (create && dir.mkdirs() && dir.isDirectory()) {
            return dir;
        }
        return null;
    }

    private static File file(File storeDir) {
        return storeDir == null ? null : new File(storeDir, FILE_NAME);
    }

    private static boolean samePath(File a, File b) {
        if (a == null || b == null) {
            return false;
        }
        try {
            return a.getCanonicalPath().equalsIgnoreCase(b.getCanonicalPath());
        } catch (IOException e) {
            return a.getAbsolutePath().equalsIgnoreCase(b.getAbsolutePath());
        }
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
