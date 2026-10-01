package flash.pipeline.runrecord;

import flash.pipeline.audit.RunSettingsSnapshot;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Captures the build/runtime environment a run executed in. Values are computed
 * once on first access and cached for the JVM lifetime, since they never change
 * during a session. Tests reset the cache via {@link #clearCacheForTests()}.
 *
 * <p>Deliberately does NOT capture raw {@code user.name} or hostname: run
 * records are designed to travel with copied projects, so avoidable personal
 * identifiers are left out in v1.
 */
public final class EnvironmentSnapshot {

    private static String flashVersion;
    private static String flashArtifactFingerprint;
    private static String fijiBuild;
    private static String jdkVersion;
    private static String osName;
    private static String biofVersion;
    private static String machineFingerprint;

    private EnvironmentSnapshot() {
    }

    /** FLASH plugin version, reusing the shared audit lookup. */
    public static synchronized String flashVersion() {
        if (flashVersion == null) {
            flashVersion = safe(RunSettingsSnapshot.flashVersion());
        }
        return flashVersion;
    }

    /** SHA-256 of the loaded plugin artifact, distinguishing rebuilds with the same version. */
    public static synchronized String flashArtifactFingerprint() {
        if (flashArtifactFingerprint == null) {
            try {
                java.security.CodeSource source = flash.pipeline.FLASH_Pipeline.class
                        .getProtectionDomain().getCodeSource();
                flashArtifactFingerprint = source == null ? ""
                        : computeArtifactFingerprint(new File(source.getLocation().toURI()));
            } catch (Exception unavailable) {
                flashArtifactFingerprint = "";
            }
        }
        return flashArtifactFingerprint;
    }

    /**
     * Files hash their complete bytes. Development directories hash sorted relative
     * file names and complete contents, excluding absolute paths and timestamps.
     * Length framing keeps different name/content arrangements unambiguous.
     */
    static String computeArtifactFingerprint(File artifact) {
        if (artifact == null) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final Path root = artifact.toPath();
            if (Files.isRegularFile(root)) {
                updateFile(digest, root);
            } else if (Files.isDirectory(root)) {
                final List<Path> files = new ArrayList<Path>();
                try (Stream<Path> paths = Files.walk(root)) {
                    paths.filter(Files::isRegularFile).forEach(files::add);
                }
                Collections.sort(files, new Comparator<Path>() {
                    @Override public int compare(Path first, Path second) {
                        return relativeName(root, first).compareTo(relativeName(root, second));
                    }
                });
                digest.update("FLASH directory artifact v1\u0000".getBytes(StandardCharsets.UTF_8));
                for (Path file : files) {
                    byte[] name = relativeName(root, file).getBytes(StandardCharsets.UTF_8);
                    updateLength(digest, name.length);
                    digest.update(name);
                    long size = Files.size(file);
                    updateLength(digest, size);
                    if (updateFile(digest, file) != size) {
                        throw new IOException("Artifact changed while computing its fingerprint.");
                    }
                }
            } else {
                return "";
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest.digest()) {
                hex.append(Character.forDigit((value >>> 4) & 15, 16));
                hex.append(Character.forDigit(value & 15, 16));
            }
            return hex.toString();
        } catch (Exception unavailable) {
            return "";
        }
    }

    private static String relativeName(Path root, Path file) {
        return root.relativize(file).toString().replace(File.separatorChar, '/');
    }

    private static void updateLength(MessageDigest digest, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) digest.update((byte) (value >>> shift));
    }

    private static long updateFile(MessageDigest digest, Path file) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long size = 0;
        try (InputStream input = new BufferedInputStream(Files.newInputStream(file))) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
                size += count;
            }
        }
        return size;
    }

    /** ImageJ/Fiji version and build, e.g. {@code 1.54f / 1.54f99}. */
    public static synchronized String fijiBuild() {
        if (fijiBuild == null) {
            fijiBuild = computeFijiBuild();
        }
        return fijiBuild;
    }

    /** Running JDK version. */
    public static synchronized String jdkVersion() {
        if (jdkVersion == null) {
            jdkVersion = safe(System.getProperty("java.version"));
        }
        return jdkVersion;
    }

    /** OS name, version and architecture. */
    public static synchronized String osName() {
        if (osName == null) {
            osName = computeOsName();
        }
        return osName;
    }

    /** Bio-Formats library version. */
    public static synchronized String biofVersion() {
        if (biofVersion == null) {
            biofVersion = computeBiofVersion();
        }
        return biofVersion;
    }

    /**
     * Optional salted machine fingerprint. Empty by default; a deployment may
     * opt in later. Never the raw hostname or username.
     */
    public static synchronized String machineFingerprint() {
        if (machineFingerprint == null) {
            machineFingerprint = "";
        }
        return machineFingerprint;
    }

    static synchronized void clearCacheForTests() {
        flashVersion = null;
        flashArtifactFingerprint = null;
        fijiBuild = null;
        jdkVersion = null;
        osName = null;
        biofVersion = null;
        machineFingerprint = null;
    }

    private static String computeFijiBuild() {
        try {
            String version = safe(ij.IJ.getVersion());
            String full = "";
            try {
                full = safe(ij.IJ.getFullVersion());
            } catch (Throwable ignored) {
                // getFullVersion may be unavailable on very old ImageJ; version alone is fine.
            }
            if (!full.isEmpty() && !full.equals(version)) {
                return version.isEmpty() ? full : version + " / " + full;
            }
            return version;
        } catch (Throwable t) {
            return "";
        }
    }

    private static String computeOsName() {
        String name = safe(System.getProperty("os.name"));
        String version = safe(System.getProperty("os.version"));
        String arch = safe(System.getProperty("os.arch"));
        StringBuilder out = new StringBuilder(name);
        if (!version.isEmpty()) {
            out.append(' ').append(version);
        }
        if (!arch.isEmpty()) {
            out.append(" (").append(arch).append(')');
        }
        return out.toString().trim();
    }

    private static String computeBiofVersion() {
        try {
            return safe(loci.formats.FormatTools.VERSION);
        } catch (Throwable t) {
            return "";
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
