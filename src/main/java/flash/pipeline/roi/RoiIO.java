package flash.pipeline.roi;

import flash.pipeline.io.FlashProjectLayout;

import ij.gui.Roi;
import ij.io.RoiDecoder;
import ij.plugin.frame.RoiManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Macro-equivalent ROI zip discovery and loading. */
public final class RoiIO {
    private RoiIO() {}

    public static File roiSetWriteDir(File directory) {
        return layout(directory).analysisImagesRoiDir();
    }

    public static File attributesWriteDir(File directory) {
        return layout(directory).tablesRoiWriteDir();
    }

    public static File imageOutputsWriteDir(File directory) {
        return layout(directory).analysisImagesRoiDir();
    }

    public static File imageOutputsWriteDir(File directory, String animalName) {
        return imageOutputsWriteDir(directory);
    }

    public static File partialWriteDir(File directory) {
        return layout(directory).analysisImagesRoiDir();
    }

    public static List<File> roiSetReadDirs(File directory) {
        return Collections.singletonList(roiSetWriteDir(requireDirectory(directory)));
    }

    public static List<File> attributesReadDirs(File directory) {
        return Collections.singletonList(attributesWriteDir(requireDirectory(directory)));
    }

    /** FLASH ROI zip discovery in the current Results layout. */
    public static List<File> listRoiZipFiles(File directory) {
        Map<String, File> byIdentity = new LinkedHashMap<String, File>();
        for (File dir : roiSetReadDirs(directory)) {
            addRoiZipFiles(byIdentity, dir);
        }
        return new ArrayList<File>(byIdentity.values());
    }

    public static List<File> listRoiPropertiesCsvFiles(File directory) {
        Map<String, File> byIdentity = new LinkedHashMap<String, File>();
        for (File dir : attributesReadDirs(directory)) {
            addRoiPropertiesCsvFiles(byIdentity, dir);
        }
        return new ArrayList<File>(byIdentity.values());
    }

    public static void openZipIntoRoiManager(RoiManager rm, File zip) {
        if (rm == null || zip == null) return;
        rm.runCommand("Open", zip.getAbsolutePath());
    }

    /**
     * Load ROIs directly from a zip file — no RoiManager needed.
     * Returns ROIs in zip entry order (alphabetical by entry name).
     */
    public static List<Roi> loadRoisFromZip(File zip) {
        List<Roi> rois = new ArrayList<Roi>();
        if (zip == null || !zip.exists()) return rois;

        try {
            ZipInputStream zis = new ZipInputStream(new FileInputStream(zip));
            try {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    String name = entry.getName();
                    if (!name.endsWith(".roi")) continue;

                    byte[] bytes = readAllBytes(zis);
                    Roi roi = RoiDecoder.openFromByteArray(bytes);
                    if (roi != null) {
                        if (roi.getName() == null || roi.getName().isEmpty()) {
                            // Strip .roi extension for name
                            String roiName = name;
                            if (roiName.contains("/")) {
                                roiName = roiName.substring(roiName.lastIndexOf('/') + 1);
                            }
                            if (roiName.endsWith(".roi")) {
                                roiName = roiName.substring(0, roiName.length() - 4);
                            }
                            roi.setName(roiName);
                        }
                        rois.add(roi);
                    }
                }
            } finally {
                zis.close();
            }
        } catch (IOException e) {
            ij.IJ.log("WARNING: Failed to load ROIs from " + zip.getName() + ": " + e.getMessage());
        }
        return rois;
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) >= 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static void addRoiZipFiles(Map<String, File> out, File dir) {
        if (dir == null || !dir.isDirectory()) return;
        File[] zips = dir.listFiles((d, name) -> isRoiZipName(name));
        if (zips == null) return;

        Arrays.sort(zips, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        for (File zip : zips) {
            if (containsCanonicalPath(out, zip)) continue;
            String nameKey = "name:" + normalizedRoiSetName(zip.getName());
            if (out.containsKey(nameKey)) continue;
            out.put(nameKey, zip);
        }
    }

    private static void addRoiPropertiesCsvFiles(Map<String, File> out, File dir) {
        if (dir == null || !dir.isDirectory()) return;
        File[] csvs = dir.listFiles((d, name) -> isRoiPropertiesCsvName(name));
        if (csvs == null) return;

        Arrays.sort(csvs, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        for (File csv : csvs) {
            if (containsCanonicalPath(out, csv)) continue;
            String nameKey = "name:" + csv.getName().toLowerCase(Locale.ROOT);
            if (out.containsKey(nameKey)) continue;
            out.put(nameKey, csv);
        }
    }

    private static boolean isRoiZipName(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith("rois.zip");
    }

    private static boolean isRoiPropertiesCsvName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".csv") && lower.contains("roi properties");
    }

    private static String normalizedRoiSetName(String fileName) {
        String name = fileName == null ? "" : fileName.trim();
        name = name.replaceAll("(?i)\\s*ROIs\\.zip$", "");
        name = name.replaceAll("(?i)\\.zip$", "");
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private static String canonicalPath(File file) {
        try {
            return file.getCanonicalPath().toLowerCase(Locale.ROOT);
        } catch (IOException e) {
            return file.getAbsolutePath().toLowerCase(Locale.ROOT);
        }
    }

    private static boolean containsCanonicalPath(Map<String, File> files, File candidate) {
        String candidatePath = canonicalPath(candidate);
        for (File file : files.values()) {
            if (candidatePath.equals(canonicalPath(file))) return true;
        }
        return false;
    }

    private static FlashProjectLayout layout(File directory) {
        return FlashProjectLayout.forDirectory(requireDirectory(directory).getAbsolutePath());
    }

    private static File requireDirectory(File directory) {
        if (directory == null) {
            throw new IllegalArgumentException("Project directory must not be null.");
        }
        return directory;
    }
}
