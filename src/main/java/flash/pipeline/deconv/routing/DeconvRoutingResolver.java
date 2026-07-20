package flash.pipeline.deconv.routing;

import flash.pipeline.bin.ChannelConfig;

/**
 * Resolves the effective {@link DeconvRouting} a pixel-loading consumer should use for its group,
 * applying the §C3 precedence contract and signalling a tuned-on warning when the interactive toggle
 * is flipped away from the source the parameters were tuned on.
 *
 * <p><b>Precedence (§C3), highest first:</b></p>
 * <ol>
 *   <li><b>Explicit CLI flag</b> — the analysis's deconv toggle was explicitly set on the CLI
 *       ({@code cliFlag != null}). A whole-image {@link DeconvRouting#legacyFromToggle} hard
 *       override; no interactive warning.</li>
 *   <li><b>Interactive toggle flipped</b> — a dialog was shown and the final toggle value differs
 *       from the persisted-derived default. Whole-image {@code legacyFromToggle(toggleValue)}; for
 *       the {@link DeconvRoutingGroup#ANALYSIS} group a flip away from the tuned source signals a
 *       warning (object thresholds / particle sizes / segmentation were tuned on that source).
 *       {@link DeconvRoutingGroup#DISPLAY} flips are cosmetic and never warn.</li>
 *   <li><b>Persisted routing</b> — the config carries per-channel routing keys
 *       ({@link DeconvConfigBridge#hasRoutingKeys}); use {@link DeconvConfigBridge#routingFrom}.</li>
 *   <li><b>Legacy default</b> — no routing keys: {@code legacyFromToggle(legacyDefaultToggle)} where
 *       {@code legacyDefaultToggle} preserves today's default (the old {@code useDeconvolvedInput}
 *       default = {@code true}) — i.e. unchanged legacy behaviour.</li>
 * </ol>
 *
 * <p>Whatever rule fires, the result is {@link DeconvRouting#normalize normalized} against the
 * selected channels so any non-opted / unselected channel is forced RAW.</p>
 *
 * <p>Dependency-light (only the lenient {@link ChannelConfig} + the routing value model), so it is
 * trivially unit-testable without booting ImageJ.</p>
 */
public final class DeconvRoutingResolver {

    private DeconvRoutingResolver() {}

    /** Immutable outcome: the effective routing plus the tuned-on warning signal. */
    public static final class Result {
        /** The effective, already-normalized per-channel routing. */
        public final DeconvRouting routing;
        /** True when an ANALYSIS-group toggle was flipped away from the tuned source. */
        public final boolean warnTunedFlip;
        /** {@code "deconv"} or {@code "raw"} — the source the parameters were tuned on, or null. */
        public final String tunedSourceLabel;

        Result(DeconvRouting routing, boolean warnTunedFlip, String tunedSourceLabel) {
            this.routing = routing;
            this.warnTunedFlip = warnTunedFlip;
            this.tunedSourceLabel = tunedSourceLabel;
        }
    }

    /**
     * Mutable, fluent inputs to {@link #resolve(Inputs)}. Callers set what they know; unset values
     * keep conservative defaults ({@code null} CLI flag = absent, no interactive dialog, legacy
     * default toggle = {@code true}).
     */
    public static final class Inputs {
        private int sizeC;
        private boolean[] selectedChannels;
        private DeconvRoutingGroup group = DeconvRoutingGroup.ANALYSIS;
        private ChannelConfig channelConfig;
        private Boolean cliFlag;
        private boolean interactivePresent;
        private boolean toggleValue;
        private boolean toggleDefault;
        private boolean legacyDefaultToggle = true;

        /** Authoritative channel count (source series sizeC / config channel count). */
        public Inputs sizeC(int value) { this.sizeC = value; return this; }

        /** Channels selected/opted this run; {@code null} = none selected (normalize forces RAW). */
        public Inputs selectedChannels(boolean[] value) {
            this.selectedChannels = value == null ? null : value.clone();
            return this;
        }

        /** The routing group this consumer belongs to. */
        public Inputs group(DeconvRoutingGroup value) {
            this.group = value == null ? DeconvRoutingGroup.ANALYSIS : value;
            return this;
        }

        /** The persisted lenient channel config (nullable). */
        public Inputs channelConfig(ChannelConfig value) { this.channelConfig = value; return this; }

        /** The explicit CLI toggle value, or {@code null} when the flag was absent. */
        public Inputs cliFlag(Boolean value) { this.cliFlag = value; return this; }

        /** Whether an interactive dialog was shown (headless / suppressed runs pass {@code false}). */
        public Inputs interactivePresent(boolean value) { this.interactivePresent = value; return this; }

        /** The final interactive toggle value (meaningful only when {@link #interactivePresent}). */
        public Inputs toggleValue(boolean value) { this.toggleValue = value; return this; }

        /** The persisted-derived default the dialog showed (used to detect a flip). */
        public Inputs toggleDefault(boolean value) { this.toggleDefault = value; return this; }

        /** Today's legacy default toggle (the old {@code useDeconvolvedInput} default = {@code true}). */
        public Inputs legacyDefaultToggle(boolean value) { this.legacyDefaultToggle = value; return this; }
    }

    /**
     * The interactive toggle DEFAULT a consumer should show: ON when the persisted group uses
     * deconvolution, else the legacy default. So a subsequent "flipped" test is {@code final != this}.
     */
    public static boolean toggleDefaultFor(ChannelConfig cfg, DeconvRoutingGroup group, boolean legacyDefault) {
        if (DeconvConfigBridge.hasRoutingKeys(cfg)) {
            return DeconvConfigBridge.routingFrom(cfg).groupUsesDeconv(group);
        }
        return legacyDefault;
    }

    /** Apply the §C3 precedence and normalize. Never returns {@code null}. */
    public static Result resolve(Inputs in) {
        if (in == null) {
            throw new IllegalArgumentException("resolver inputs must not be null");
        }
        DeconvRoutingGroup group = in.group;
        int sizeC = Math.max(0, in.sizeC);
        boolean hasKeys = DeconvConfigBridge.hasRoutingKeys(in.channelConfig);

        DeconvRouting routing;
        boolean warnTunedFlip = false;
        String tunedSourceLabel = null;

        if (in.cliFlag != null) {
            // Rule 1: explicit CLI flag — HARD override, whole-image on/off. No interactive warning.
            routing = DeconvRouting.legacyFromToggle(in.cliFlag.booleanValue(), sizeC);
        } else if (in.interactivePresent && in.toggleValue != in.toggleDefault) {
            // Rule 2: the user flipped the toggle away from its persisted-derived default.
            routing = DeconvRouting.legacyFromToggle(in.toggleValue, sizeC);
            // Only an ANALYSIS-group flip silently invalidates tuned thresholds/particle sizes/
            // segmentation; and only when deconvolution was actually configured (a source exists to
            // have been tuned on). Display flips are cosmetic.
            if (group == DeconvRoutingGroup.ANALYSIS
                    && DeconvConfigBridge.isDeconvConfigured(in.channelConfig)) {
                warnTunedFlip = true;
                // The parameters were tuned on the DEFAULT source (what setup/persisted routing used);
                // flipping away from it is the invalidation.
                tunedSourceLabel = in.toggleDefault ? "deconv" : "raw";
            }
        } else if (hasKeys) {
            // Rule 3: honour persisted per-channel routing (headless never opens the wizard).
            routing = DeconvConfigBridge.routingFrom(in.channelConfig);
        } else {
            // Rule 4: legacy fallback — whole-image on/off from today's default. Unchanged behaviour.
            routing = DeconvRouting.legacyFromToggle(in.legacyDefaultToggle, sizeC);
        }

        // ALWAYS normalize: force RAW for any non-opted / unselected channel.
        routing = routing.normalize(in.selectedChannels);
        return new Result(routing, warnTunedFlip, tunedSourceLabel);
    }
}
