package flash.pipeline.deconv.routing;

import java.util.Arrays;

/**
 * Immutable per-channel, two-group deconvolution routing.
 *
 * <p>For each channel it records whether the channel is opted in to deconvolution and, for each
 * {@link DeconvRoutingGroup}, whether that group reads the raw or the deconvolved source
 * ({@link SourceKind}). This replaces the old single per-analysis {@code useDeconvolvedInput}
 * boolean over a whole merged file.</p>
 *
 * <p><b>Invariant:</b> a channel that is not opted in is {@link SourceKind#RAW} in both groups.
 * Callers should {@link #normalize(boolean[])} against the actually-selected channels at both
 * config-write time and consumer-read time (headless never opens the wizard), and non-opted
 * channels are additionally forced RAW here so a stray {@code DECONV} token can never leak through.</p>
 *
 * <p>Dependency-light on purpose (no ImageJ types) so it is trivially unit-testable and reusable by
 * both the config codec and the pixel consumers.</p>
 */
public final class DeconvRouting {

    private final boolean[] optedIn;
    private final SourceKind[] analysis;
    private final SourceKind[] display;

    private DeconvRouting(boolean[] optedIn, SourceKind[] analysis, SourceKind[] display) {
        int n = optedIn.length;
        if (analysis.length != n || display.length != n) {
            throw new IllegalArgumentException("routing arrays must share one channel count");
        }
        this.optedIn = optedIn;
        this.analysis = enforceOptInInvariant(optedIn, analysis);
        this.display = enforceOptInInvariant(optedIn, display);
    }

    /** Build from explicit vectors (defensive copies taken; opt-in invariant enforced). */
    public static DeconvRouting of(boolean[] optedIn, SourceKind[] analysis, SourceKind[] display) {
        return new DeconvRouting(
                copyBooleans(optedIn),
                copyKinds(analysis, optedIn == null ? 0 : optedIn.length),
                copyKinds(display, optedIn == null ? 0 : optedIn.length));
    }

    /** All channels RAW and not opted in. */
    public static DeconvRouting none(int channelCount) {
        int n = Math.max(0, channelCount);
        return new DeconvRouting(new boolean[n], filled(n, SourceKind.RAW), filled(n, SourceKind.RAW));
    }

    /**
     * Decision 5 default: every opted-in (selected) channel reads DECONV for both groups; every
     * other channel reads RAW.
     */
    public static DeconvRouting defaultFor(boolean[] selectedChannels) {
        int n = selectedChannels == null ? 0 : selectedChannels.length;
        boolean[] opted = copyBooleans(selectedChannels);
        SourceKind[] both = new SourceKind[n];
        for (int c = 0; c < n; c++) {
            both[c] = opted[c] ? SourceKind.DECONV : SourceKind.RAW;
        }
        return new DeconvRouting(opted, both.clone(), both.clone());
    }

    /**
     * Decision 8 legacy fallback: when a project has no routing keys, derive routing from the
     * existing whole-image {@code useDeconvolvedInput} toggle — true &rarr; every channel DECONV,
     * false &rarr; every channel RAW — applied to the requested group's semantics (both groups get
     * the same vector, which is exactly the old behaviour).
     */
    public static DeconvRouting legacyFromToggle(boolean useDeconvolvedInput, int sizeC) {
        int n = Math.max(0, sizeC);
        boolean[] opted = new boolean[n];
        SourceKind kind = useDeconvolvedInput ? SourceKind.DECONV : SourceKind.RAW;
        SourceKind[] vec = filled(n, kind);
        Arrays.fill(opted, useDeconvolvedInput);
        return new DeconvRouting(opted, vec.clone(), vec.clone());
    }

    public int channelCount() {
        return optedIn.length;
    }

    public boolean isOptedIn(int channel) {
        return channel >= 0 && channel < optedIn.length && optedIn[channel];
    }

    /** Whether any channel is opted in to deconvolution. */
    public boolean anyOptedIn() {
        for (boolean b : optedIn) {
            if (b) return true;
        }
        return false;
    }

    /** The per-channel source vector for a group (defensive copy). */
    public SourceKind[] vectorFor(DeconvRoutingGroup group) {
        return groupArray(group).clone();
    }

    public SourceKind sourceFor(DeconvRoutingGroup group, int channel) {
        SourceKind[] vec = groupArray(group);
        if (channel < 0 || channel >= vec.length) return SourceKind.RAW;
        return vec[channel];
    }

    public boolean usesDeconv(DeconvRoutingGroup group, int channel) {
        return sourceFor(group, channel) == SourceKind.DECONV;
    }

    /** Whether any channel in the group reads DECONV (i.e. the group needs a mirror at all). */
    public boolean groupUsesDeconv(DeconvRoutingGroup group) {
        for (SourceKind kind : groupArray(group)) {
            if (kind == SourceKind.DECONV) return true;
        }
        return false;
    }

    /**
     * True when the group's vector equals the plain opt-in default (DECONV for opted-in channels,
     * RAW otherwise). Stage 14 uses this to take the cheap merged {@code _deconv.tif} fast-path; a
     * group that diverges from this needs per-channel composition.
     */
    public boolean isGroupDefault(DeconvRoutingGroup group) {
        SourceKind[] vec = groupArray(group);
        for (int c = 0; c < vec.length; c++) {
            SourceKind expected = optedIn[c] ? SourceKind.DECONV : SourceKind.RAW;
            if (vec[c] != expected) return false;
        }
        return true;
    }

    /** True when the two groups' vectors differ for at least one channel. */
    public boolean isDivergent() {
        for (int c = 0; c < analysis.length; c++) {
            if (analysis[c] != display[c]) return true;
        }
        return false;
    }

    /**
     * Enforce the invariant against the set of channels actually selected this run: any channel not
     * in {@code selectedChannels} is forced not-opted-in and RAW for both groups. Channels shorter
     * than this routing are treated as not selected; a longer selection mask is ignored past the
     * channel count.
     */
    public DeconvRouting normalize(boolean[] selectedChannels) {
        int n = optedIn.length;
        boolean[] opted = new boolean[n];
        SourceKind[] a = new SourceKind[n];
        SourceKind[] d = new SourceKind[n];
        for (int c = 0; c < n; c++) {
            boolean selected = selectedChannels != null && c < selectedChannels.length && selectedChannels[c];
            if (selected) {
                opted[c] = optedIn[c];
                a[c] = analysis[c];
                d[c] = display[c];
            } else {
                opted[c] = false;
                a[c] = SourceKind.RAW;
                d[c] = SourceKind.RAW;
            }
        }
        return new DeconvRouting(opted, a, d);
    }

    // ---- immutable copiers ---------------------------------------------

    public DeconvRouting withOptedIn(int channel, boolean opted) {
        boolean[] next = optedIn.clone();
        if (channel >= 0 && channel < next.length) next[channel] = opted;
        return new DeconvRouting(next, analysis.clone(), display.clone());
    }

    public DeconvRouting withSource(DeconvRoutingGroup group, int channel, SourceKind kind) {
        SourceKind[] a = analysis.clone();
        SourceKind[] d = display.clone();
        SourceKind[] target = group == DeconvRoutingGroup.DISPLAY ? d : a;
        if (channel >= 0 && channel < target.length) {
            target[channel] = kind == null ? SourceKind.RAW : kind;
        }
        return new DeconvRouting(optedIn.clone(), a, d);
    }

    // ---- internals -----------------------------------------------------

    private SourceKind[] groupArray(DeconvRoutingGroup group) {
        return group == DeconvRoutingGroup.DISPLAY ? display : analysis;
    }

    private static SourceKind[] enforceOptInInvariant(boolean[] optedIn, SourceKind[] vec) {
        SourceKind[] out = new SourceKind[optedIn.length];
        for (int c = 0; c < optedIn.length; c++) {
            SourceKind kind = c < vec.length && vec[c] != null ? vec[c] : SourceKind.RAW;
            out[c] = optedIn[c] ? kind : SourceKind.RAW;
        }
        return out;
    }

    private static boolean[] copyBooleans(boolean[] src) {
        return src == null ? new boolean[0] : src.clone();
    }

    private static SourceKind[] copyKinds(SourceKind[] src, int expectedLength) {
        SourceKind[] out = new SourceKind[expectedLength];
        for (int c = 0; c < expectedLength; c++) {
            out[c] = src != null && c < src.length && src[c] != null ? src[c] : SourceKind.RAW;
        }
        return out;
    }

    private static SourceKind[] filled(int n, SourceKind kind) {
        SourceKind[] out = new SourceKind[n];
        Arrays.fill(out, kind);
        return out;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DeconvRouting)) return false;
        DeconvRouting that = (DeconvRouting) o;
        return Arrays.equals(optedIn, that.optedIn)
                && Arrays.equals(analysis, that.analysis)
                && Arrays.equals(display, that.display);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(optedIn);
        result = 31 * result + Arrays.hashCode(analysis);
        result = 31 * result + Arrays.hashCode(display);
        return result;
    }

    @Override
    public String toString() {
        return "DeconvRouting{optedIn=" + Arrays.toString(optedIn)
                + ", analysis=" + Arrays.toString(analysis)
                + ", display=" + Arrays.toString(display) + '}';
    }
}
