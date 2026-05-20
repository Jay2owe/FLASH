package flash.pipeline.deconv;

import flash.pipeline.io.FlashProjectLayout;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Filesystem helpers for standalone deconvolution outputs and cache layout.
 */
public final class DeconvolutionIO {

    private static final String CACHE_SUBDIR = "3D Deconvolution";
    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    private DeconvolutionIO() {}

    public static File deconvOutDir(File rootDir) {
        return layout(rootDir).analysisWriteDir(FlashProjectLayout.AnalysisFolder.DECONVOLUTION);
    }

    public static File cacheDir(File rootDir) {
        return new File(layout(rootDir).cacheRoot(), CACHE_SUBDIR);
    }

    public static List<File> deconvOutReadDirs(File rootDir) {
        return layout(rootDir).analysisReadDirs(FlashProjectLayout.AnalysisFolder.DECONVOLUTION);
    }

    public static List<File> cacheReadDirs(File rootDir) {
        List<File> out = new ArrayList<File>();
        out.add(cacheDir(rootDir));
        out.add(new File(rootDir, ".deconv_cache"));
        return Collections.unmodifiableList(out);
    }

    public static File cacheParamsDir(File rootDir, String paramsHash) {
        return new File(cacheDir(rootDir), safeToken(paramsHash));
    }

    public static List<File> cacheParamsReadDirs(File rootDir, String paramsHash) {
        List<File> out = new ArrayList<File>();
        List<File> roots = cacheReadDirs(rootDir);
        for (int i = 0; i < roots.size(); i++) {
            out.add(new File(roots.get(i), safeToken(paramsHash)));
        }
        return Collections.unmodifiableList(out);
    }

    public static File deconvFile(File rootDir, String imageBaseName, int channelIndex) {
        return new File(deconvOutDir(rootDir), baseName(imageBaseName) + "_C" + channelIndex + ".tif");
    }

    public static List<File> deconvFileReadCandidates(File rootDir, String imageBaseName, int channelIndex) {
        List<File> out = new ArrayList<File>();
        List<File> dirs = deconvOutReadDirs(rootDir);
        for (int i = 0; i < dirs.size(); i++) {
            out.add(new File(dirs.get(i), baseName(imageBaseName) + "_C" + channelIndex + ".tif"));
        }
        return Collections.unmodifiableList(out);
    }

    public static File mergedDeconvFile(File rootDir, String imageBaseName) {
        return new File(deconvOutDir(rootDir), baseName(imageBaseName) + "_deconv.tif");
    }

    public static List<File> mergedDeconvFileReadCandidates(File rootDir, String imageBaseName) {
        List<File> out = new ArrayList<File>();
        List<File> dirs = deconvOutReadDirs(rootDir);
        for (int i = 0; i < dirs.size(); i++) {
            out.add(new File(dirs.get(i), baseName(imageBaseName) + "_deconv.tif"));
        }
        return Collections.unmodifiableList(out);
    }

    public static File cacheFile(File rootDir, String paramsHash, String imageBaseName, int channelIndex) {
        return new File(cacheParamsDir(rootDir, paramsHash), baseName(imageBaseName) + "_C" + channelIndex + ".tif");
    }

    public static List<File> cacheFileReadCandidates(File rootDir,
                                                     String paramsHash,
                                                     String imageBaseName,
                                                     int channelIndex) {
        List<File> out = new ArrayList<File>();
        List<File> dirs = cacheParamsReadDirs(rootDir, paramsHash);
        for (int i = 0; i < dirs.size(); i++) {
            out.add(new File(dirs.get(i), baseName(imageBaseName) + "_C" + channelIndex + ".tif"));
        }
        return Collections.unmodifiableList(out);
    }

    public static File detailsFile(File rootDir, String imageBaseName) {
        return new File(deconvOutDir(rootDir), baseName(imageBaseName) + "_deconv_details.txt");
    }

    public static File firstExistingFile(List<File> candidates) {
        if (candidates == null) return null;
        for (int i = 0; i < candidates.size(); i++) {
            File candidate = candidates.get(i);
            if (candidate != null && candidate.isFile()) {
                return candidate;
            }
        }
        return null;
    }

    public static File firstFreshFile(File sourceFile, List<File> candidates) {
        if (candidates == null) return null;
        for (int i = 0; i < candidates.size(); i++) {
            File candidate = candidates.get(i);
            if (isCacheFresh(sourceFile, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    public static boolean isCacheFresh(File sourceFile, File cacheFile) {
        return sourceFile != null
                && cacheFile != null
                && cacheFile.isFile()
                && cacheFile.lastModified() >= sourceFile.lastModified();
    }

    public static String paramsHash(Map<String, String> params) {
        TreeMap<String, String> sorted = new TreeMap<String, String>();
        if (params != null) {
            sorted.putAll(params);
        }

        StringBuilder canonical = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (canonical.length() > 0) canonical.append('\n');
            canonical.append(entry.getKey()).append('=').append(entry.getValue() == null ? "" : entry.getValue());
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return encodeBase32(bytes, 10);
        } catch (Exception e) {
            throw new IllegalStateException("Could not compute deconvolution parameter hash.", e);
        }
    }

    public static String formatDouble(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private static String encodeBase32(byte[] bytes, int length) {
        StringBuilder sb = new StringBuilder(length);
        int buffer = 0;
        int bitsLeft = 0;
        int i = 0;
        while (sb.length() < length) {
            if (bitsLeft < 5) {
                if (i < bytes.length) {
                    buffer = (buffer << 8) | (bytes[i++] & 0xff);
                    bitsLeft += 8;
                } else {
                    buffer <<= (5 - bitsLeft);
                    bitsLeft = 5;
                }
            }
            int index = (buffer >> (bitsLeft - 5)) & 31;
            bitsLeft -= 5;
            sb.append(BASE32[index]);
        }
        return sb.toString();
    }

    private static FlashProjectLayout layout(File rootDir) {
        if (rootDir == null) {
            throw new IllegalArgumentException("Project directory must not be null.");
        }
        return FlashProjectLayout.forDirectory(rootDir.getAbsolutePath());
    }

    private static String baseName(String value) {
        String raw = value == null ? "" : value.trim();
        if (raw.isEmpty()) return "series";
        return safeToken(raw);
    }

    private static String safeToken(String value) {
        String raw = value == null ? "" : value.trim();
        if (raw.isEmpty()) return "series";
        return raw.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", " ").trim();
    }
}
