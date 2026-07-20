package flash.pipeline.deconv;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-channel config-derived deconvolution parameters (the optics/engine subset of the writer's
 * params-hash field set, WITHOUT image geometry) that a routed consumer or the autorun preflight
 * uses to detect a DECONVOLUTION-PARAMETER change against the freshness manifest.
 *
 * <p>Built once per run from the persisted channel config (see
 * {@code DeconvConfigBridge.expectedParamsFor}). It carries only the 11 config-controlled keys
 * ({@link DeconvParamsHash#buildConfigParams}); geometry is intentionally excluded because it is a
 * pure function of the raw source, which the manifest verifies independently via its content
 * fingerprint. {@link DeconvManifest} overlays these config params onto the mirror's recorded
 * geometry and recomputes the expected hash, so a change to any deconvolution parameter that leaves
 * the source bytes unchanged is detected (the mirror reads as stale → raw fallback), while a Dropbox
 * re-hydration is not.</p>
 *
 * <p>Immutable and ImageJ-free. A {@code null} per-channel map (a channel not opted in, or optics
 * incomplete) means "skip the params check for this channel" — freshness degrades to
 * source-fingerprint-only, exactly the pre-existing behaviour.</p>
 */
public final class ExpectedDeconvParams {

    private static final ExpectedDeconvParams NONE =
            new ExpectedDeconvParams(Collections.<Integer, Map<String, String>>emptyMap());

    private final Map<Integer, Map<String, String>> byChannel;

    private ExpectedDeconvParams(Map<Integer, Map<String, String>> byChannel) {
        this.byChannel = byChannel;
    }

    /** The empty instance: every channel skips the params check (source-fingerprint-only freshness). */
    public static ExpectedDeconvParams none() {
        return NONE;
    }

    /**
     * Wrap a per-channel map (channel index &rarr; config params). Null/empty per-channel entries are
     * dropped; an overall empty result collapses to {@link #none()}. Defensive copies are taken.
     */
    public static ExpectedDeconvParams of(Map<Integer, Map<String, String>> byChannel) {
        if (byChannel == null || byChannel.isEmpty()) {
            return NONE;
        }
        Map<Integer, Map<String, String>> copy = new HashMap<Integer, Map<String, String>>();
        for (Map.Entry<Integer, Map<String, String>> e : byChannel.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue().isEmpty()) {
                continue;
            }
            copy.put(e.getKey(),
                    Collections.unmodifiableMap(new HashMap<String, String>(e.getValue())));
        }
        return copy.isEmpty() ? NONE : new ExpectedDeconvParams(copy);
    }

    /** Config-derived params for the channel, or {@code null} to skip the params-staleness check. */
    public Map<String, String> forChannel(int channelIndex) {
        return byChannel.get(Integer.valueOf(channelIndex));
    }

    public boolean isEmpty() {
        return byChannel.isEmpty();
    }
}
