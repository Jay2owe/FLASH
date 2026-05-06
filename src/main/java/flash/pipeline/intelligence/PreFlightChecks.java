package flash.pipeline.intelligence;

import ij.IJ;

import javax.swing.JOptionPane;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Silent pre-flight guards.
 *
 * These checks fire before analyses run, never change settings, and only
 * surface UI when something is actually wrong. They are advisory except
 * P-11 Write-Permission which blocks (you cannot produce output without
 * a writable folder).
 *
 * Features implemented here:
 *  L-08 Truncated-File Detector
 *  L-10 Running-on-Output Detector
 *  P-10 Disk-Space Pre-Flight
 *  P-11 Write-Permission Check
 *  P-13 Windows Path-Length + Non-ASCII Check
 */
public final class PreFlightChecks {

    /** Output sub-folder names that indicate a previous run produced outputs here. */
    private static final Set<String> OUTPUT_FOLDER_NAMES = new HashSet<String>(
            Arrays.asList("Data Analysis", "Image Analysis", "ImageJ Exports"));

    /** Extensions that should not be scanned for truncation (only raw microscopy). */
    private static final Set<String> RAW_IMAGE_EXTS = new HashSet<String>(
            Arrays.asList("lif", "czi", "nd2", "tif", "tiff", "ome.tif", "ome.tiff"));

    private PreFlightChecks() {}

    public static final class DirectoryFileScan {
        private final File directory;
        private final File[] cleanFiles;

        private DirectoryFileScan(File directory, File[] cleanFiles) {
            this.directory = directory;
            this.cleanFiles = cleanFiles == null ? new File[0] : cleanFiles.clone();
        }

        public File getDirectory() {
            return directory;
        }

        public File[] getCleanFiles() {
            return cleanFiles.clone();
        }
    }

    public static DirectoryFileScan scanCleanFiles(String directory) {
        File dir = new File(directory == null ? "" : directory);
        File[] files = dir.isDirectory() ? JunkFileFilter.listCleanFiles(dir) : new File[0];
        return new DirectoryFileScan(dir, files);
    }

    // ─────────────────────────────────────────────
    // L-10 Running-on-Output Detector
    // ─────────────────────────────────────────────

    public static final class OutputFolderResult {
        public final boolean likelyOutput;
        public final List<String> detected;
        public OutputFolderResult(boolean likelyOutput, List<String> detected) {
            this.likelyOutput = likelyOutput;
            this.detected = detected == null ? Collections.<String>emptyList() : detected;
        }
    }

    /** Non-interactive scan. Returns which output-suffix folders exist as siblings. */
    public static OutputFolderResult detectOutputFolder(String directory) {
        if (directory == null || directory.isEmpty()) {
            return new OutputFolderResult(false, Collections.<String>emptyList());
        }
        File dir = new File(directory);
        if (!dir.isDirectory()) {
            return new OutputFolderResult(false, Collections.<String>emptyList());
        }
        File[] children = JunkFileFilter.listCleanDirectories(dir);

        List<String> found = new ArrayList<String>();
        for (File child : children) {
            if (child.isDirectory() && OUTPUT_FOLDER_NAMES.contains(child.getName())) {
                found.add(child.getName());
            }
        }
        // Warn only if ≥2 of the canonical output folders are siblings.
        boolean likely = found.size() >= 2;
        return new OutputFolderResult(likely, found);
    }

    /**
     * Returns true if the user wants to proceed. Shows a single confirm dialog
     * when the folder looks like a previous run's output.
     */
    public static boolean confirmProceedOnOutputFolder(String directory) {
        OutputFolderResult r = detectOutputFolder(directory);
        if (!r.likelyOutput) return true;

        String msg = "This folder already contains a previous run's outputs:\n\n"
                + "    " + String.join(", ", r.detected) + "\n\n"
                + "Continuing will re-run the pipeline in place. Existing outputs may be overwritten.\n\n"
                + "Continue?";
        int choice = JOptionPane.showConfirmDialog(
                null, msg, "Re-run on existing output folder?",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        boolean proceed = (choice == JOptionPane.YES_OPTION);
        if (proceed) {
            IJ.log("[PreFlight] Re-run confirmed on existing output folder: " + directory);
        }
        return proceed;
    }

    // ─────────────────────────────────────────────
    // L-08 Truncated-File Detector
    // ─────────────────────────────────────────────

    public static List<File> findTruncatedImages(String directory) {
        return findTruncatedImages(scanCleanFiles(directory));
    }

    public static List<File> findTruncatedImages(DirectoryFileScan scan) {
        List<File> out = new ArrayList<File>();
        File[] files = scan == null ? new File[0] : scan.cleanFiles;

        // Collect raw-image files (non-junk) and their sizes.
        List<File> images = new ArrayList<File>();
        long[] sizes = new long[files.length];
        int sizeIdx = 0;

        for (File f : files) {
            String ext = extension(f.getName());
            if (!RAW_IMAGE_EXTS.contains(ext)) continue;
            images.add(f);
            long sz = f.length();
            sizes[sizeIdx++] = sz;
        }
        if (images.isEmpty()) return out;

        long median = medianOf(Arrays.copyOf(sizes, sizeIdx));
        long floorBytes = 1024L * 1024L; // 1 MB absolute floor
        long folderFloor = Math.max(floorBytes, median / 10); // or 10% of folder median

        for (File f : images) {
            if (f.length() < folderFloor) out.add(f);
        }
        return out;
    }

    // ─────────────────────────────────────────────
    // P-10 Disk-Space Pre-Flight
    // ─────────────────────────────────────────────

    /** Rough per-step multiplier: input bytes × 4 covers split TIFs + label stacks + CSVs. */
    private static final double OUTPUT_SIZE_MULTIPLIER = 4.0;

    public static final class DiskSpaceResult {
        public final long inputBytes;
        public final long freeBytes;
        public final long estimatedOutputBytes;
        public final boolean warn;
        public final boolean likelyInsufficient;
        public DiskSpaceResult(long input, long free, long estOut, boolean warn, boolean insufficient) {
            this.inputBytes = input;
            this.freeBytes = free;
            this.estimatedOutputBytes = estOut;
            this.warn = warn;
            this.likelyInsufficient = insufficient;
        }
    }

    public static DiskSpaceResult checkDiskSpace(String directory) {
        return checkDiskSpace(scanCleanFiles(directory));
    }

    public static DiskSpaceResult checkDiskSpace(DirectoryFileScan scan) {
        File dir = scan == null ? new File("") : scan.directory;
        long inputBytes = sumRawImageBytes(scan == null ? new File[0] : scan.cleanFiles);
        long estimatedOutput = (long) (inputBytes * OUTPUT_SIZE_MULTIPLIER);
        long free;
        try {
            Path p = dir.exists() ? dir.toPath() : new File(".").toPath();
            FileStore store = Files.getFileStore(p);
            free = store.getUsableSpace();
        } catch (IOException e) {
            free = Long.MAX_VALUE; // don't block if we can't read
        }
        boolean warn = free < (long) (estimatedOutput * 1.5);
        boolean insufficient = free < (long) (estimatedOutput * 1.1);
        return new DiskSpaceResult(inputBytes, free, estimatedOutput, warn, insufficient);
    }

    // ─────────────────────────────────────────────
    // P-11 Write-Permission Check
    // ─────────────────────────────────────────────

    /** Returns null if writable; otherwise a message describing the problem. */
    public static String checkWritePermission(String directory) {
        if (directory == null) return "No directory selected.";
        File dir = new File(directory);
        if (!dir.exists()) return "Directory does not exist: " + directory;
        if (!dir.isDirectory()) return "Not a directory: " + directory;
        try {
            File probe = File.createTempFile(".ihf_probe_", "", dir);
            if (!probe.delete()) {
                // Non-fatal; probe file is tiny.
            }
        } catch (IOException e) {
            return "Cannot write to: " + directory + " (" + e.getMessage() + ")";
        }
        return null;
    }

    // ─────────────────────────────────────────────
    // P-13 Windows Path-Length + Non-ASCII
    // ─────────────────────────────────────────────

    public static final class PathIssue {
        public final File file;
        public final String reason;
        public PathIssue(File file, String reason) { this.file = file; this.reason = reason; }
    }

    public static List<PathIssue> findPathIssues(String directory) {
        return findPathIssues(scanCleanFiles(directory));
    }

    public static List<PathIssue> findPathIssues(DirectoryFileScan scan) {
        List<PathIssue> out = new ArrayList<PathIssue>();
        File[] files = scan == null ? new File[0] : scan.cleanFiles;

        boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

        for (File f : files) {
            String path = f.getAbsolutePath();
            if (isWindows && path.length() > 240) {
                out.add(new PathIssue(f, "Path too long (" + path.length()
                        + " chars; Windows limit ~260)"));
            }
            String name = f.getName();
            if (containsNonAscii(name)) {
                out.add(new PathIssue(f, "Filename contains non-ASCII characters"));
            }
        }
        return out;
    }

    // ─────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────

    private static String extension(String name) {
        if (name == null) return "";
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".ome.tif"))  return "ome.tif";
        if (lower.endsWith(".ome.tiff")) return "ome.tiff";
        int dot = lower.lastIndexOf('.');
        return dot < 0 ? "" : lower.substring(dot + 1);
    }

    private static long medianOf(long[] arr) {
        if (arr == null || arr.length == 0) return 0L;
        long[] copy = Arrays.copyOf(arr, arr.length);
        Arrays.sort(copy);
        return copy[copy.length / 2];
    }

    private static long sumRawImageBytes(File[] files) {
        if (files == null) return 0L;
        long total = 0L;
        for (File f : files) {
            if (!RAW_IMAGE_EXTS.contains(extension(f.getName()))) continue;
            total += f.length();
        }
        return total;
    }

    private static boolean containsNonAscii(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c > 0x7E) return true;
        }
        return false;
    }

    /** Format a byte count as a short human-readable string (e.g. "1.3 GB"). */
    public static String humanBytes(long bytes) {
        double v = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int u = 0;
        while (v >= 1024.0 && u < units.length - 1) {
            v /= 1024.0;
            u++;
        }
        return String.format(Locale.ROOT, "%.1f %s", v, units[u]);
    }
}
