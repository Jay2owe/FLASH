package flash.pipeline.orientation;

/**
 * Forward-application scope for a captured ROI orientation transform.
 */
public enum BroadcastScope {
    /**
     * Applies only to the current image and never creates a forward rule.
     */
    THIS_IMAGE,

    /**
     * Applies literally to later images with the same known hemisphere.
     */
    SAME_HEMISPHERE,

    /**
     * Applies the captured transform literally to every later image.
     */
    ALL_LITERAL,

    /**
     * Applies to every later image, toggling horizontal flip for the opposite known hemisphere.
     */
    ALL_MIRRORED
}
