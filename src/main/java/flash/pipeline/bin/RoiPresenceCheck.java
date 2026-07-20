package flash.pipeline.bin;

import flash.pipeline.roi.RoiIO;

import java.io.File;
import java.io.FilenameFilter;
import java.util.Locale;

/** Checks whether a project folder already has saved ROI archives. */
public final class RoiPresenceCheck {
    private RoiPresenceCheck() {}

    public static boolean hasSavedRois(String directory) {
        if (directory == null || directory.trim().isEmpty()) return false;
        File root = new File(directory);
        if (!root.isDirectory()) return false;

        if (!RoiIO.listRoiZipFiles(root).isEmpty()) return true;

        return hasRoiZip(RoiIO.partialWriteDir(root));
    }

    private static boolean hasRoiZip(File dir) {
        if (dir == null || !dir.isDirectory()) return false;
        File[] zips = dir.listFiles(new FilenameFilter() {
            @Override public boolean accept(File parent, String name) {
                if (name == null) return false;
                String lower = name.toLowerCase(Locale.ROOT);
                return lower.endsWith("rois.zip")
                        || (lower.endsWith(".zip") && lower.contains("roiset"));
            }
        });
        return zips != null && zips.length > 0;
    }
}
