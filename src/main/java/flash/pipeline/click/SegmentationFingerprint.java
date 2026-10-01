package flash.pipeline.click;

import ij.ImagePlus;
import ij.process.ImageProcessor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/** Binds object verdicts to the full original label map, never a rendered preview. */
public final class SegmentationFingerprint {
    private SegmentationFingerprint() { }

    public static String of(ImagePlus labels) {
        if (labels == null || labels.getStackSize() == 0) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String geometry = labels.getWidth() + ":" + labels.getHeight() + ":"
                    + labels.getNChannels() + ":" + labels.getNSlices() + ":"
                    + labels.getNFrames() + ":" + labels.getStackSize() + ":";
            digest.update(geometry.getBytes(StandardCharsets.UTF_8));
            byte[] buffer = new byte[8192];
            int used = 0;
            for (int z = 1; z <= labels.getStackSize(); z++) {
                ImageProcessor ip = labels.getStack().getProcessor(z);
                for (int p = 0; p < ip.getPixelCount(); p++) {
                    int bits = Float.floatToIntBits(ip.getf(p));
                    buffer[used++] = (byte) (bits >>> 24);
                    buffer[used++] = (byte) (bits >>> 16);
                    buffer[used++] = (byte) (bits >>> 8);
                    buffer[used++] = (byte) bits;
                    if (used == buffer.length) {
                        digest.update(buffer);
                        used = 0;
                    }
                }
            }
            digest.update(buffer, 0, used);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest.digest()) {
                hex.append(Character.forDigit((b >>> 4) & 15, 16));
                hex.append(Character.forDigit(b & 15, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public static void requireMatching(List<ClickStore.Click> clicks, ImagePlus labels)
            throws IOException {
        String current = of(labels);
        for (ClickStore.Click click : clicks) {
            if (click.segmentationFingerprint.isEmpty()) {
                throw new IOException("Object selections for '" + click.imageName
                        + "' have no segmentation fingerprint. Recapture these clicks "
                        + "on the current segmentation before exporting training data.");
            }
            if (!click.segmentationFingerprint.equals(current)) {
                throw new IOException("Segmentation has changed for '" + click.imageName
                        + "'. Recapture its object selections before exporting training data.");
            }
        }
    }
}
