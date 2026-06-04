package flash.pipeline.bin;

import java.io.IOException;

/**
 * Thrown when a {@code channel_config.json} carries a {@code schemaVersion}
 * newer than this FLASH build understands. Extends {@link IOException} so the
 * existing {@code catch (IOException)} read paths handle it without change; the
 * typed form lets callers distinguish "made by a newer FLASH" (warn, do not
 * overwrite) from "corrupt" or "absent".
 */
public class NewerSchemaException extends IOException {
    private static final long serialVersionUID = 1L;

    private final int requestedVersion;
    private final int supportedVersion;

    public NewerSchemaException(int requestedVersion, int supportedVersion) {
        super("channel_config.json schemaVersion " + requestedVersion
                + " is newer than this FLASH build supports (max " + supportedVersion
                + "). Update FLASH to open this project.");
        this.requestedVersion = requestedVersion;
        this.supportedVersion = supportedVersion;
    }

    /** The schemaVersion found on disk. */
    public int getRequestedVersion() {
        return requestedVersion;
    }

    /** The highest schemaVersion this build can read/write. */
    public int getSupportedVersion() {
        return supportedVersion;
    }
}
