package flash.pipeline.intelligence.identity;

/**
 * Confidence in a single auto-detected identity field, ordered low to high.
 * Surfaced in the Project Builder as colour-coded cells (green/amber/grey).
 */
public enum Confidence {
    NONE, LOW, MEDIUM, HIGH
}
