package flash.pipeline.bin;

import java.util.Map;

/**
 * Ordered, additive migration of a parsed {@code channel_config.json} map from
 * an older {@code schemaVersion} up to {@link ChannelConfigCodec#schemaVersion()}.
 *
 * <p>Each hop ({@code vN -> vN+1}) must be additive: fill new keys with sensible
 * defaults, never delete or repurpose an existing key. This keeps an old project
 * loadable after a format change instead of being rejected and overwritten.
 *
 * <p>The chain is currently empty because the schema is still at version 1 (the
 * only valid on-disk version), so {@link #upgrade} is an identity transform. The
 * structure is in place so the first real bump is a one-line, unit-tested hop.
 */
final class ChannelConfigMigrations {

    private ChannelConfigMigrations() {
    }

    /**
     * Apply every migration hop needed to bring {@code root} (parsed at
     * {@code fromVersion}) up to the current schema. Hops run in ascending order
     * and each is additive, so the result is safe to decode with the current
     * field extractor. Mutates and returns {@code root}.
     */
    static Map<String, Object> upgrade(Map<String, Object> root, int fromVersion) {
        Map<String, Object> r = root;
        // Add ordered hops here as the schema grows, e.g.:
        //   if (fromVersion < 2) r = v1ToV2(r);
        //   if (fromVersion < 3) r = v2ToV3(r);
        // Each must only fill missing keys with defaults; never remove keys.
        return r;
    }
}
