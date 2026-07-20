package flash.pipeline.deconv.routing;

/**
 * Which pixel source a routed consumer (or a setup preview) should read for a channel:
 * the raw acquisition ({@link #RAW}) or its deconvolved mirror ({@link #DECONV}).
 *
 * <p>Shared by {@link DeconvRouting} and the Set&nbsp;Up&nbsp;Configuration preview seam so there
 * is exactly one vocabulary for raw-vs-deconvolved across the codebase.</p>
 */
public enum SourceKind {
    RAW,
    DECONV
}
