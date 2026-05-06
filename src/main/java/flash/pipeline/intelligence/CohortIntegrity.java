package flash.pipeline.intelligence;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Filesystem-only cohort diagnostics for the "Check my data" report.
 * No Bio-Formats, no pixel reading — each check is a quick walk of the
 * directory tree and returns findings appended to the report.
 *
 * Implements:
 *   P-07 Duplicate-File Detector
 *   P-09 Naming-Drift Clusterer (region tokens)
 *   P-12 ROI Zip Inspector
 */
public final class CohortIntegrity {

    private static final Set<String> RAW_IMAGE_EXTS = new HashSet<String>(
            Arrays.asList("lif", "czi", "nd2", "tif", "tiff"));

    private CohortIntegrity() {}

    /** P-07: lists clusters of byte-identical files. */
    public static void checkDuplicates(String directory, DiagnosticsReport.Section section) {
        File dir = new File(directory == null ? "" : directory);
        if (!dir.isDirectory()) { section.info("No directory."); return; }
        File[] files = JunkFileFilter.listCleanFiles(dir);
        if (files.length == 0) { section.info("No files."); return; }

        // Group by size first
        Map<Long, List<File>> bySize = new HashMap<Long, List<File>>();
        for (File f : files) {
            if (!RAW_IMAGE_EXTS.contains(extension(f.getName()))) continue;
            long sz = f.length();
            if (sz == 0) continue;
            List<File> bucket = bySize.get(sz);
            if (bucket == null) { bucket = new ArrayList<File>(); bySize.put(sz, bucket); }
            bucket.add(f);
        }

        // For each size bucket with >1 file, hash head and confirm with full hash.
        int clusters = 0;
        for (Map.Entry<Long, List<File>> e : bySize.entrySet()) {
            if (e.getValue().size() < 2) continue;
            Map<String, List<File>> byHead = new HashMap<String, List<File>>();
            for (File f : e.getValue()) {
                String h = headHash(f);
                if (h == null) continue;
                List<File> bucket = byHead.get(h);
                if (bucket == null) { bucket = new ArrayList<File>(); byHead.put(h, bucket); }
                bucket.add(f);
            }
            for (List<File> candidates : byHead.values()) {
                if (candidates.size() < 2) continue;
                // Confirm with full hash
                Map<String, List<File>> byFull = new HashMap<String, List<File>>();
                for (File f : candidates) {
                    String h = fullHash(f);
                    if (h == null) continue;
                    List<File> bucket = byFull.get(h);
                    if (bucket == null) { bucket = new ArrayList<File>(); byFull.put(h, bucket); }
                    bucket.add(f);
                }
                for (List<File> dups : byFull.values()) {
                    if (dups.size() < 2) continue;
                    clusters++;
                    StringBuilder sb = new StringBuilder();
                    sb.append("Identical files: ");
                    for (int i = 0; i < dups.size(); i++) {
                        if (i > 0) sb.append(" | ");
                        sb.append(dups.get(i).getName());
                    }
                    section.warn(sb.toString());
                }
            }
        }
        if (clusters == 0) section.ok("No duplicate files found.");
    }

    /** P-09: clusters near-duplicate region tokens in filenames. */
    public static void checkNamingDrift(String directory, DiagnosticsReport.Section section) {
        File dir = new File(directory == null ? "" : directory);
        if (!dir.isDirectory()) { section.info("No directory."); return; }
        File[] files = JunkFileFilter.listCleanFiles(dir);
        if (files.length == 0) { section.info("No files."); return; }

        // Pull the region token out of the filename by Jamie's convention.
        // Convention: Experiment-AnimalID_Hemisphere_Region(.ext)
        // We isolate the final "_Region" segment of the filename stem.
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (File f : files) {
            String ext = extension(f.getName());
            if (!RAW_IMAGE_EXTS.contains(ext)) continue;
            String stem = stripExtension(f.getName());
            int us = stem.lastIndexOf('_');
            if (us < 0 || us == stem.length() - 1) continue;
            String region = stem.substring(us + 1);
            Integer c = counts.get(region);
            counts.put(region, c == null ? 1 : c + 1);
        }
        if (counts.size() < 2) {
            section.ok("No region typo clusters detected.");
            return;
        }

        // Cluster tokens within edit distance 2 on their lower-cased, stripped form.
        List<String> tokens = new ArrayList<String>(counts.keySet());
        boolean[] used = new boolean[tokens.size()];
        int clusterCount = 0;
        for (int i = 0; i < tokens.size(); i++) {
            if (used[i]) continue;
            List<String> cluster = new ArrayList<String>();
            cluster.add(tokens.get(i));
            used[i] = true;
            for (int j = i + 1; j < tokens.size(); j++) {
                if (used[j]) continue;
                if (areNearDuplicates(tokens.get(i), tokens.get(j))) {
                    cluster.add(tokens.get(j));
                    used[j] = true;
                }
            }
            if (cluster.size() > 1) {
                clusterCount++;
                StringBuilder sb = new StringBuilder();
                sb.append("Possible typo cluster: ");
                for (int k = 0; k < cluster.size(); k++) {
                    if (k > 0) sb.append(" · ");
                    String t = cluster.get(k);
                    sb.append(t).append(" (").append(counts.get(t)).append(")");
                }
                section.warn(sb.toString());
            }
        }
        if (clusterCount == 0) section.ok("No region typo clusters detected.");
    }

    /** P-12: reports ROI zip contents without enforcing counts. */
    public static void checkRoiZips(String directory, DiagnosticsReport.Section section) {
        File dir = new File(directory == null ? "" : directory);
        if (!dir.isDirectory()) { section.info("No directory."); return; }

        List<File> zips = new ArrayList<File>();
        collectRoiZips(dir, zips, 0);
        if (zips.isEmpty()) { section.info("No ROI zips found."); return; }

        // Tally entries-per-zip and flag problematic archives.
        int problematic = 0;
        Map<Integer, Integer> countHistogram = new LinkedHashMap<Integer, Integer>();
        for (File zip : zips) {
            int entries = 0;
            boolean hasJunk = false;
            ZipFile zf = null;
            try {
                zf = new ZipFile(zip);
                java.util.Enumeration<? extends ZipEntry> it = zf.entries();
                while (it.hasMoreElements()) {
                    ZipEntry e = it.nextElement();
                    String name = e.getName();
                    if (name.startsWith("__MACOSX/") || name.startsWith(".")) {
                        hasJunk = true;
                        continue;
                    }
                    if (name.toLowerCase(Locale.ROOT).endsWith(".roi")) entries++;
                }
            } catch (IOException e) {
                section.error(zip.getName() + " -- could not open: " + e.getMessage());
                problematic++;
                continue;
            } finally {
                if (zf != null) try { zf.close(); } catch (IOException ignored) {}
            }
            Integer c = countHistogram.get(entries);
            countHistogram.put(entries, c == null ? 1 : c + 1);
            if (hasJunk) {
                section.warn(zip.getName() + " -- contains macOS resource-fork entries");
                problematic++;
            }
            if (entries == 0) {
                section.warn(zip.getName() + " -- no .roi entries inside");
                problematic++;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("ROI zip distribution: ");
        boolean first = true;
        for (Map.Entry<Integer, Integer> e : countHistogram.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(e.getValue()).append(" zip").append(e.getValue() == 1 ? "" : "s")
              .append(" with ").append(e.getKey()).append(" ROI").append(e.getKey() == 1 ? "" : "s");
            first = false;
        }
        section.info(sb.toString());
        if (problematic == 0) section.ok("No problematic ROI zips.");
    }

    // ── Helpers ───────────────────────────────────────

    private static void collectRoiZips(File dir, List<File> out, int depth) {
        if (depth > 2) return; // shallow; ROIs live one or two levels deep
        File[] files = JunkFileFilter.listCleanChildren(dir);
        for (File f : files) {
            if (f == null) continue;
            if (f.isDirectory()) {
                collectRoiZips(f, out, depth + 1);
            } else if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                String n = f.getName().toLowerCase(Locale.ROOT);
                if (n.contains("roi") || n.contains("rois")) out.add(f);
            }
        }
    }

    private static String headHash(File f) {
        return sha1(f, 65536);
    }

    private static String fullHash(File f) {
        return sha1(f, -1);
    }

    private static String sha1(File f, int limitBytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            FileInputStream in = new FileInputStream(f);
            try {
                byte[] buf = new byte[8192];
                int total = 0;
                int read;
                while ((read = in.read(buf)) > 0) {
                    md.update(buf, 0, read);
                    total += read;
                    if (limitBytes > 0 && total >= limitBytes) break;
                }
            } finally {
                in.close();
            }
            byte[] hash = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean areNearDuplicates(String a, String b) {
        if (a == null || b == null) return false;
        String na = normalizeToken(a);
        String nb = normalizeToken(b);
        if (na.equals(nb)) return true;
        if (Math.abs(na.length() - nb.length()) > 2) return false;
        // Don't merge two short tokens that differ by a character (CA1 vs CA3).
        if (na.length() <= 3 && nb.length() <= 3) return false;
        return editDistance(na, nb) <= 2;
    }

    private static String normalizeToken(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** Levenshtein edit distance (small strings only). */
    private static int editDistance(String a, String b) {
        int n = a.length();
        int m = b.length();
        if (n == 0) return m;
        if (m == 0) return n;
        int[] prev = new int[m + 1];
        int[] curr = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            curr[0] = i;
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[m];
    }

    private static String extension(String name) {
        if (name == null) return "";
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".ome.tif"))  return "tif";
        if (lower.endsWith(".ome.tiff")) return "tiff";
        int dot = lower.lastIndexOf('.');
        return dot < 0 ? "" : lower.substring(dot + 1);
    }

    private static String stripExtension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
