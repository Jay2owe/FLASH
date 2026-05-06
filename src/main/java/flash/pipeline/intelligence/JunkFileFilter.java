package flash.pipeline.intelligence;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * L-09 Cloud-Placeholder & Junk Sweep.
 *
 * Recognises filenames that should never enter a file scan: OS junk,
 * Office lockfiles, macOS resource forks, Dropbox conflicted copies.
 * Also recognises cloud-only placeholder files (OneDrive / Dropbox "online-only")
 * by DOS file attributes on Windows.
 *
 * Silent guard: the plugin never reports these, they just disappear from the list.
 */
public final class JunkFileFilter {

    private JunkFileFilter() {}

    private static final Pattern JUNK_NAME = Pattern.compile(
            "^(\\._|~\\$).*"
            + "|^\\.DS_Store$"
            + "|^Thumbs\\.db$"
            + "|^desktop\\.ini$"
            + "|.*(?i)(conflicted copy|case conflict).*"
    );

    /** True if the file name matches a known junk pattern and should be ignored. */
    public static boolean isJunk(String fileName) {
        if (fileName == null || fileName.isEmpty()) return true;
        return JUNK_NAME.matcher(fileName).matches();
    }

    /** True if the File should be ignored: junk name, hidden, or cloud-only placeholder. */
    public static boolean shouldIgnore(File f) {
        if (f == null) return true;
        if (!f.exists()) return true;
        if (isJunk(f.getName())) return true;
        if (isCloudPlaceholder(f)) return true;
        return false;
    }

    /** Returns child entries with junk names / placeholders removed, preserving directory order. */
    public static File[] listCleanChildren(File dir) {
        if (dir == null || !dir.isDirectory()) return new File[0];
        File[] children = dir.listFiles();
        if (children == null || children.length == 0) return new File[0];

        List<File> clean = new ArrayList<File>(children.length);
        for (File child : children) {
            if (shouldIgnore(child)) continue;
            clean.add(child);
        }
        return clean.toArray(new File[clean.size()]);
    }

    /** Returns clean child files only, preserving directory order. */
    public static File[] listCleanFiles(File dir) {
        File[] children = listCleanChildren(dir);
        List<File> files = new ArrayList<File>(children.length);
        for (File child : children) {
            if (child.isFile()) files.add(child);
        }
        return files.toArray(new File[files.size()]);
    }

    /** Returns clean child directories only, preserving directory order. */
    public static File[] listCleanDirectories(File dir) {
        File[] children = listCleanChildren(dir);
        List<File> directories = new ArrayList<File>(children.length);
        for (File child : children) {
            if (child.isDirectory()) directories.add(child);
        }
        return directories.toArray(new File[directories.size()]);
    }

    /**
     * Best-effort detection of OneDrive / Dropbox "online only" placeholders on Windows.
     * These files report System+Archive DOS attributes; opening them triggers a
     * blocking cloud fetch that can hang a scan.
     *
     * On non-Windows platforms always returns false.
     */
    public static boolean isCloudPlaceholder(File f) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win")) return false;
        try {
            Object attrs = java.nio.file.Files.readAttributes(
                    f.toPath(), java.nio.file.attribute.DosFileAttributes.class);
            java.nio.file.attribute.DosFileAttributes dos =
                    (java.nio.file.attribute.DosFileAttributes) attrs;
            // OneDrive online-only placeholders set Offline/Reparse-Point combinations.
            // System+Archive is too aggressive; use isOther() which covers reparse points.
            return dos.isOther();
        } catch (Exception ignored) {
            return false;
        }
    }
}
