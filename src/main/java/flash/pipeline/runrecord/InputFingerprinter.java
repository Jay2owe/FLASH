package flash.pipeline.runrecord;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Computes per-input fingerprints in two tiers.
 *
 * <ul>
 *   <li><b>fast</b> (default): {@code SHA-256( size(8 BE) || mtimeMs(8 BE) ||
 *       SHA-256(first 64 KB) )}, base64url-encoded. Tens of milliseconds even
 *       for multi-GB files because only the head is hashed.</li>
 *   <li><b>full</b> (opt-in): streamed SHA-256 of the whole file. Can take
 *       minutes on a large LIF, so it is only used when the settings toggle is
 *       on.</li>
 * </ul>
 *
 * Missing or unreadable files return an empty value plus a warning so the
 * caller can record the issue without scraping the log.
 */
public final class InputFingerprinter {

    public enum FingerprintMode {
        FAST("fast"),
        FULL("full");

        public final String token;

        FingerprintMode(String token) {
            this.token = token;
        }
    }

    private static final int HEAD_BYTES = 64 * 1024;
    private static final int STREAM_BUFFER = 64 * 1024;

    /** Outcome of a fingerprint attempt. */
    public static final class FingerprintResult {
        public final String mode;
        public final String value;
        public final long sizeBytes;
        public final long lastModifiedMillis;
        public final String warning;

        FingerprintResult(String mode, String value, long sizeBytes,
                          long lastModifiedMillis, String warning) {
            this.mode = mode;
            this.value = value == null ? "" : value;
            this.sizeBytes = sizeBytes;
            this.lastModifiedMillis = lastModifiedMillis;
            this.warning = warning == null ? "" : warning;
        }

        public boolean hasValue() {
            return !value.isEmpty();
        }
    }

    private InputFingerprinter() {
    }

    public static FingerprintResult fingerprint(File file, FingerprintMode mode) {
        return mode == FingerprintMode.FULL ? fullFingerprint(file) : fastFingerprint(file);
    }

    public static FingerprintResult fastFingerprint(File file) {
        FingerprintResult missing = checkReadable(file, FingerprintMode.FAST);
        if (missing != null) {
            return missing;
        }
        long size = file.length();
        long mtime = file.lastModified();
        try {
            byte[] headHash = sha256(readHead(file));
            ByteBuffer buffer = ByteBuffer.allocate(8 + 8 + headHash.length);
            buffer.putLong(size);
            buffer.putLong(mtime);
            buffer.put(headHash);
            String value = base64Url(sha256(buffer.array()));
            return new FingerprintResult(FingerprintMode.FAST.token, value, size, mtime, "");
        } catch (IOException e) {
            return new FingerprintResult(FingerprintMode.FAST.token, "", size, mtime,
                    "Could not fast-fingerprint " + file.getName() + ": " + e.getMessage());
        }
    }

    public static FingerprintResult fullFingerprint(File file) {
        FingerprintResult missing = checkReadable(file, FingerprintMode.FULL);
        if (missing != null) {
            return missing;
        }
        long size = file.length();
        long mtime = file.lastModified();
        MessageDigest digest = newSha256();
        InputStream in = null;
        try {
            in = Files.newInputStream(file.toPath());
            byte[] buffer = new byte[STREAM_BUFFER];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return new FingerprintResult(FingerprintMode.FULL.token, base64Url(digest.digest()),
                    size, mtime, "");
        } catch (IOException e) {
            return new FingerprintResult(FingerprintMode.FULL.token, "", size, mtime,
                    "Could not full-fingerprint " + file.getName() + ": " + e.getMessage());
        } finally {
            closeQuietly(in);
        }
    }

    private static FingerprintResult checkReadable(File file, FingerprintMode mode) {
        if (file == null) {
            return new FingerprintResult(mode.token, "", -1L, -1L, "Input file is null");
        }
        if (!file.isFile()) {
            return new FingerprintResult(mode.token, "", -1L, -1L,
                    "Input file missing or not a regular file: " + file.getAbsolutePath());
        }
        if (!file.canRead()) {
            return new FingerprintResult(mode.token, "", file.length(), file.lastModified(),
                    "Input file not readable: " + file.getAbsolutePath());
        }
        return null;
    }

    private static byte[] readHead(File file) throws IOException {
        byte[] buffer = new byte[HEAD_BYTES];
        InputStream in = null;
        try {
            in = Files.newInputStream(file.toPath());
            int total = 0;
            int read;
            while (total < HEAD_BYTES && (read = in.read(buffer, total, HEAD_BYTES - total)) != -1) {
                total += read;
            }
            if (total == HEAD_BYTES) {
                return buffer;
            }
            byte[] trimmed = new byte[total];
            System.arraycopy(buffer, 0, trimmed, 0, total);
            return trimmed;
        } finally {
            closeQuietly(in);
        }
    }

    private static byte[] sha256(byte[] data) {
        MessageDigest digest = newSha256();
        digest.update(data);
        return digest.digest();
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String base64Url(byte[] digest) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
                // Nothing useful to do when closing a read-only stream.
            }
        }
    }

    /** Convenience for callers that want to fingerprint a string payload (e.g. manifests). */
    static String hashUtf8(String value) {
        return base64Url(sha256((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
    }
}
