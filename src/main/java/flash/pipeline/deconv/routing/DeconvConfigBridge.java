package flash.pipeline.deconv.routing;

import flash.pipeline.bin.ChannelConfig;
import flash.pipeline.deconv.DeconvParamsHash;
import flash.pipeline.deconv.ExpectedDeconvParams;
import flash.pipeline.deconv.engine.Algorithm;
import flash.pipeline.deconv.engine.DeconvSettings;
import flash.pipeline.deconv.psf.PsfModel;
import flash.pipeline.deconv.psf.PsfSpec;
import flash.pipeline.deconv.psf.ScopeModality;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bridges the lenient raw deconvolution primitives persisted in {@link ChannelConfig} (Stage 03) to
 * the validating runtime value objects — {@link DeconvSettings}, {@link PsfSpec}, {@link DeconvRouting}
 * — and provides the "preserve deconv across a config rewrite" helpers.
 *
 * <p>Validation lives here (the use point), never in the codec: building a {@link PsfSpec} may throw
 * {@link IllegalArgumentException} (e.g. NA&nbsp;&ge;&nbsp;immersion RI), which callers translate into
 * "deconvolution unavailable / re-prompt optics" rather than a config-load failure.</p>
 *
 * <p>Three of four config write paths rebuild {@code ChannelConfig} from a flat projection that has no
 * deconv fields; without the preserve helpers here an unrelated CLI/bypass/wizard save would silently
 * wipe deconvolution — exactly how {@code markerId} is preserved. The preserve helpers use
 * copy-if-absent semantics so they carry deconv forward through unrelated saves without ever clobbering
 * a freshly-edited value.</p>
 */
public final class DeconvConfigBridge {

    /** Defaults applied only when a persisted deconv field is genuinely missing. */
    private static final Algorithm DEFAULT_ALGORITHM = Algorithm.RL;
    private static final PsfModel DEFAULT_PSF_MODEL = PsfModel.GIBSON_LANNI;
    private static final int DEFAULT_ITERATIONS = 20;
    private static final double DEFAULT_REGULARIZATION = 0.0;
    private static final ScopeModality DEFAULT_MODALITY = ScopeModality.WIDEFIELD;

    private DeconvConfigBridge() {}

    // ---- opt-in / configured queries -----------------------------------

    /** A channel is opted in to deconvolution iff it carries a persisted engine key. */
    public static boolean isChannelDeconvOptedIn(ChannelConfig.Channel channel) {
        return channel != null && channel.deconvEngineKey != null && !channel.deconvEngineKey.trim().isEmpty();
    }

    /** Whether any channel in the config is opted in to deconvolution. */
    public static boolean isDeconvConfigured(ChannelConfig cfg) {
        if (cfg == null || cfg.channels == null) return false;
        for (ChannelConfig.Channel channel : cfg.channels) {
            if (isChannelDeconvOptedIn(channel)) return true;
        }
        return false;
    }

    /** Whether the config carries explicit per-channel routing tokens (else legacy-derive applies). */
    public static boolean hasRoutingKeys(ChannelConfig cfg) {
        if (cfg == null || cfg.channels == null) return false;
        for (ChannelConfig.Channel channel : cfg.channels) {
            if (channel != null && (channel.routeAnalysis != null || channel.routeDisplay != null)) {
                return true;
            }
        }
        return false;
    }

    // ---- runtime object builders ---------------------------------------

    /**
     * Build a {@link DeconvRouting} from the persisted per-channel route tokens and opt-in set.
     * Opted-in channels default to DECONV for a group whose route token is absent; non-opted channels
     * are forced RAW by the routing invariant.
     */
    public static DeconvRouting routingFrom(ChannelConfig cfg) {
        int n = (cfg == null || cfg.channels == null) ? 0 : cfg.channels.size();
        boolean[] opted = new boolean[n];
        SourceKind[] analysis = new SourceKind[n];
        SourceKind[] display = new SourceKind[n];
        for (int c = 0; c < n; c++) {
            ChannelConfig.Channel channel = cfg.channels.get(c);
            boolean in = isChannelDeconvOptedIn(channel);
            opted[c] = in;
            analysis[c] = in ? parseSource(channel.routeAnalysis, SourceKind.DECONV) : SourceKind.RAW;
            display[c] = in ? parseSource(channel.routeDisplay, SourceKind.DECONV) : SourceKind.RAW;
        }
        return DeconvRouting.of(opted, analysis, display);
    }

    /**
     * Per-channel {@link DeconvSettings}, or {@code null} when the channel is not opted in. Missing
     * algorithm / PSF model / iterations / regularization fall back to conservative defaults.
     */
    public static DeconvSettings settingsFor(ChannelConfig cfg, int channelIndex) {
        ChannelConfig.Channel channel = channelAt(cfg, channelIndex);
        if (!isChannelDeconvOptedIn(channel)) return null;
        Algorithm algorithm = parseEnum(Algorithm.class, channel.deconvAlgorithm, DEFAULT_ALGORITHM);
        PsfModel psfModel = parseEnum(PsfModel.class, channel.deconvPsfModel, DEFAULT_PSF_MODEL);
        int iterations = channel.deconvIterations == null
                ? DEFAULT_ITERATIONS : channel.deconvIterations.intValue();
        double regularization = channel.deconvRegularization == null
                ? DEFAULT_REGULARIZATION : channel.deconvRegularization.doubleValue();
        return new DeconvSettings(channel.deconvEngineKey, algorithm, psfModel, iterations, regularization);
    }

    /** The persisted scope modality, or the default when absent/unknown. */
    public static ScopeModality modalityFrom(ChannelConfig cfg) {
        if (cfg == null || cfg.deconvOptics == null) return DEFAULT_MODALITY;
        return parseEnum(ScopeModality.class, cfg.deconvOptics.scopeModality, DEFAULT_MODALITY);
    }

    /**
     * The per-channel config-derived params (optics + engine subset of the writer's params-hash field
     * set) used to detect a deconvolution-<em>parameter</em> change against the freshness manifest.
     *
     * <p>Only opted-in channels with a complete, comparable optics/settings set contribute an entry
     * (matching exactly what {@code CreateBinFileAnalysis.buildSetupDeconvHashParams} reads from the
     * config); a channel whose optics are incomplete is omitted, so its freshness degrades to
     * source-fingerprint-only rather than crashing. Delegates the field-set construction to
     * {@link DeconvParamsHash#buildConfigParams} so the consumer's expected params can never drift from
     * the writer's. Returns {@link ExpectedDeconvParams#none()} when nothing is comparable.</p>
     */
    public static ExpectedDeconvParams expectedParamsFor(ChannelConfig cfg) {
        if (cfg == null || cfg.channels == null || cfg.deconvOptics == null) {
            return ExpectedDeconvParams.none();
        }
        ChannelConfig.DeconvOptics optics = cfg.deconvOptics;
        if (optics.na == null || optics.immersionRi == null || optics.sampleRi == null) {
            return ExpectedDeconvParams.none();
        }
        ScopeModality modality = modalityFrom(cfg);
        Map<Integer, Map<String, String>> byChannel = new HashMap<Integer, Map<String, String>>();
        for (int c = 0; c < cfg.channels.size(); c++) {
            ChannelConfig.Channel channel = cfg.channels.get(c);
            if (!isChannelDeconvOptedIn(channel) || channel.emissionWavelengthNm == null) {
                continue;
            }
            DeconvSettings settings = settingsFor(cfg, c);
            if (settings == null) {
                continue;
            }
            Map<String, String> params = DeconvParamsHash.buildConfigParams(
                    settings, modality,
                    optics.na.doubleValue(), optics.immersionRi.doubleValue(), optics.sampleRi.doubleValue(),
                    optics.pinholeAiryUnits, channel.emissionWavelengthNm.doubleValue());
            byChannel.put(Integer.valueOf(c), params);
        }
        return ExpectedDeconvParams.of(byChannel);
    }

    /**
     * Build a validating {@link PsfSpec} for a channel from the shared optics plus caller-supplied
     * per-image geometry (pixel sizes in nm and PSF kernel dimensions). Throws
     * {@link IllegalArgumentException} (via {@link PsfSpec}) when the optics are missing or invalid —
     * callers surface this as "deconv unavailable / re-prompt", never a config-load failure.
     */
    public static PsfSpec psfSpecFor(ChannelConfig cfg,
                                     int channelIndex,
                                     double pixelSizeXyNm,
                                     double pixelSizeZNm,
                                     int sizeX,
                                     int sizeY,
                                     int sizeZ) {
        if (cfg == null || cfg.deconvOptics == null) {
            throw new IllegalArgumentException("Deconvolution optics are not configured.");
        }
        ChannelConfig.DeconvOptics optics = cfg.deconvOptics;
        ChannelConfig.Channel channel = channelAt(cfg, channelIndex);
        ScopeModality modality = parseEnum(ScopeModality.class, optics.scopeModality, DEFAULT_MODALITY);
        Double pinhole = modality == ScopeModality.CONFOCAL ? optics.pinholeAiryUnits : null;
        return new PsfSpec(
                requireDouble("numericalAperture", optics.na),
                requireDouble("immersionRi", optics.immersionRi),
                requireDouble("sampleRi", optics.sampleRi),
                requireDouble("emissionWavelengthNm", channel == null ? null : channel.emissionWavelengthNm),
                pixelSizeXyNm,
                pixelSizeZNm,
                sizeX,
                sizeY,
                sizeZ,
                modality,
                pinhole);
    }

    // ---- preserve-across-rewrite helpers -------------------------------

    /** Copy the root deconv optics from {@code source} into {@code target} when target has none. */
    public static void preserveRootDeconv(ChannelConfig target, ChannelConfig source) {
        if (target == null || source == null) return;
        if (target.deconvOptics == null && source.deconvOptics != null) {
            target.deconvOptics = source.deconvOptics;
        }
    }

    /** Copy each per-channel deconv field from {@code source} into {@code target} when target's is null. */
    public static void preserveChannelDeconv(ChannelConfig.Channel target, ChannelConfig.Channel source) {
        if (target == null || source == null) return;
        if (target.deconvEngineKey == null) target.deconvEngineKey = source.deconvEngineKey;
        if (target.deconvAlgorithm == null) target.deconvAlgorithm = source.deconvAlgorithm;
        if (target.deconvPsfModel == null) target.deconvPsfModel = source.deconvPsfModel;
        if (target.deconvIterations == null) target.deconvIterations = source.deconvIterations;
        if (target.deconvRegularization == null) target.deconvRegularization = source.deconvRegularization;
        if (target.emissionWavelengthNm == null) target.emissionWavelengthNm = source.emissionWavelengthNm;
        if (target.routeAnalysis == null) target.routeAnalysis = source.routeAnalysis;
        if (target.routeDisplay == null) target.routeDisplay = source.routeDisplay;
    }

    /** Preserve root optics + every matching-index channel's deconv fields (copy-if-absent). */
    public static void preserveDeconv(ChannelConfig target, ChannelConfig source) {
        if (target == null || source == null) return;
        preserveRootDeconv(target, source);
        if (target.channels == null || source.channels == null) return;
        for (int i = 0; i < target.channels.size(); i++) {
            ChannelConfig.Channel next = target.channels.get(i);
            ChannelConfig.Channel old = i < source.channels.size() ? source.channels.get(i) : null;
            preserveChannelDeconv(next, old);
        }
    }

    // ---- internals -----------------------------------------------------

    private static ChannelConfig.Channel channelAt(ChannelConfig cfg, int channelIndex) {
        if (cfg == null || cfg.channels == null) return null;
        if (channelIndex < 0 || channelIndex >= cfg.channels.size()) return null;
        return cfg.channels.get(channelIndex);
    }

    private static SourceKind parseSource(String token, SourceKind fallback) {
        if (token == null) return fallback;
        String normalized = token.trim().toLowerCase(Locale.ROOT);
        if ("deconv".equals(normalized) || "deconvolved".equals(normalized)) return SourceKind.DECONV;
        if ("raw".equals(normalized)) return SourceKind.RAW;
        return fallback;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String token, E fallback) {
        if (token == null) return fallback;
        try {
            return Enum.valueOf(type, token.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static double requireDouble(String label, Double value) {
        if (value == null) {
            throw new IllegalArgumentException(label + " is required for deconvolution but was not set.");
        }
        return value.doubleValue();
    }

    /** List accessor kept for symmetry with callers that hold a channel list directly. */
    static List<ChannelConfig.Channel> channelsOf(ChannelConfig cfg) {
        return cfg == null ? null : cfg.channels;
    }
}
