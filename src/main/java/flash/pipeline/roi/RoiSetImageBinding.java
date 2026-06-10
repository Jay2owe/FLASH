package flash.pipeline.roi;

import ij.IJ;
import ij.gui.Roi;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Binds drawn/cropped ROI pairs to images by the FLASH durable image identity
 * ({@code imageKey}) rather than by position in the ROI Manager. This lets a region's
 * ROI zip cover any subset of a project's images (mixed containers + loose TIFs), and
 * survive reordering or adding/removing other files.
 *
 * <p>{@code imageKey} is the existing join key
 * {@code sourceKind|sourceFile|seriesIndex|originalName} (see
 * {@link flash.pipeline.naming.OrientationManifestRow#buildImageKey}). It contains path
 * separators and spaces, so it is not safe as an ROI / zip-entry name; this class encodes
 * it as a stable, fixed-length, filesystem-safe {@link #token(String) token}.
 *
 * <p>Naming contract for an image with key {@code k}:
 * <ul>
 *   <li>drawn ROI name: {@code token(k)}</li>
 *   <li>cropped ROI name: {@code token(k) + "_Cropped"}</li>
 * </ul>
 *
 * <p>Pure and dependency-light (only ImageJ {@link Roi}); no ImagePlus or UI, so it is
 * unit-testable on the non-headless test JVM.
 */
public final class RoiSetImageBinding {

    /** Suffix marking the top-left-shifted (cropped) ROI of a pair. */
    public static final String CROPPED_SUFFIX = "_Cropped";

    /** Hex length of the binding token body (64 bits): ample for a project's images. */
    private static final int TOKEN_HEX_LENGTH = 16;

    private RoiSetImageBinding() {}

    /**
     * Stable, deterministic, zip-safe token for a durable {@code imageKey}. The same input
     * always yields the same token across runs and JVMs; distinct keys (including ones
     * differing only in {@code seriesIndex} or {@code sourceFile}) yield distinct tokens.
     * The token is {@code 'k'} followed by lowercase hex, so it never contains an
     * underscore and can never be mistaken for the {@link #CROPPED_SUFFIX}.
     *
     * @throws IllegalArgumentException if {@code imageKey} is null or blank (a programming
     *         error: callers must resolve a real identity before binding an ROI).
     */
    public static String token(String imageKey) {
        if (imageKey == null || imageKey.trim().isEmpty()) {
            throw new IllegalArgumentException("imageKey must be non-empty to bind an ROI");
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(imageKey.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(TOKEN_HEX_LENGTH);
            for (int i = 0; sb.length() < TOKEN_HEX_LENGTH && i < digest.length; i++) {
                sb.append(Character.forDigit((digest[i] >> 4) & 0xF, 16));
                sb.append(Character.forDigit(digest[i] & 0xF, 16));
            }
            return "k" + sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 is guaranteed present on every JRE.
            throw new IllegalStateException("SHA-1 unavailable for ROI token", e);
        }
    }

    /** ROI name for the user-drawn ROI of an image. */
    public static String drawnRoiName(String imageKey) {
        return token(imageKey);
    }

    /** ROI name for the top-left-shifted (cropped) ROI of an image. */
    public static String croppedRoiName(String imageKey) {
        return token(imageKey) + CROPPED_SUFFIX;
    }

    /** True when the ROI name marks the cropped half of a pair. */
    public static boolean isCropped(String roiName) {
        return roiName != null && roiName.endsWith(CROPPED_SUFFIX);
    }

    /**
     * True when {@code candidate} is a generated binding token ({@code 'k'} + lowercase hex).
     * Used to reject legacy / imported ROI names (e.g. {@code "SCN"}) that are not identity
     * tokens, so they are not mistaken for image-bound ROIs.
     */
    public static boolean isToken(String candidate) {
        if (candidate == null || candidate.length() < 2 || candidate.charAt(0) != 'k') return false;
        for (int i = 1; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) return false;
        }
        return true;
    }

    /**
     * The binding token stored in an ROI name, stripping the cropped suffix if present.
     * Returns {@code null} for a {@code null} name.
     */
    public static String tokenOf(String roiName) {
        if (roiName == null) return null;
        if (roiName.endsWith(CROPPED_SUFFIX)) {
            return roiName.substring(0, roiName.length() - CROPPED_SUFFIX.length());
        }
        return roiName;
    }

    /** A drawn ROI plus its top-left-shifted (cropped) counterpart for one image. */
    public static final class RoiPair {
        public Roi drawn;
        public Roi cropped;
        public final String token;

        public RoiPair(String token) {
            this.token = token;
        }

        public boolean isComplete() {
            return drawn != null && cropped != null;
        }
    }

    /**
     * Group a zip's ROIs (in any order) into per-image pairs keyed by their binding token.
     * Unpaired or duplicate ROIs are logged and skipped rather than silently mispaired, so
     * structural problems surface in {@link RoiSetValidator} instead of corrupting analysis.
     */
    public static Map<String, RoiPair> indexByToken(List<Roi> rois) {
        Map<String, RoiPair> byToken = new LinkedHashMap<String, RoiPair>();
        if (rois == null) return byToken;
        for (Roi roi : rois) {
            if (roi == null) continue;
            String name = roi.getName();
            String token = tokenOf(name);
            if (token == null || token.isEmpty()) {
                IJ.log("[FLASH] ROI with no binding name skipped: " + name);
                continue;
            }
            if (!isToken(token)) {
                IJ.log("[FLASH] ROI name is not an identity token, skipped (legacy/import?): " + name);
                continue;
            }
            RoiPair pair = byToken.get(token);
            if (pair == null) {
                pair = new RoiPair(token);
                byToken.put(token, pair);
            }
            if (isCropped(name)) {
                if (pair.cropped != null) {
                    IJ.log("[FLASH] Duplicate cropped ROI for token " + token + " skipped: " + name);
                } else {
                    pair.cropped = roi;
                }
            } else if (pair.drawn != null) {
                IJ.log("[FLASH] Duplicate drawn ROI for token " + token + " skipped: " + name);
            } else {
                pair.drawn = roi;
            }
        }
        return byToken;
    }
}
