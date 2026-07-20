package flash.pipeline.runrecord;

import flash.pipeline.project.ProjectFile;
import flash.pipeline.project.ProjectFileCodec;

/**
 * Stable content hash of the active {@link ProjectFile}. Records which project
 * state was current when an analysis ran.
 *
 * <p>The hash is base64url of SHA-256 over the canonical JSON produced by
 * {@link ProjectFileCodec#encode(ProjectFile)}. The codec is deterministic
 * (LinkedHashMap insertion order preserved), so the same logical project always
 * hashes identically, and a key reorder that round-trips through the codec is
 * indistinguishable from the original.
 */
public final class ProjectFileHasher {

    private ProjectFileHasher() {
    }

    /** Hash the project, or empty string for a null/legacy (absent) project. */
    public static String hash(ProjectFile project) {
        if (project == null) {
            return "";
        }
        return InputFingerprinter.hashUtf8(ProjectFileCodec.encode(project));
    }
}
