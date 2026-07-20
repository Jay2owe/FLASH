package flash.pipeline.segmentation;

/**
 * Signals that a segmentation backend failed before it could produce a valid
 * label image. A null ImagePlus remains reserved for no-label-map outcomes.
 */
public class SegmentationRunFailureException extends RuntimeException {
    public SegmentationRunFailureException(String message, Throwable cause) {
        super(safeMessage(message), cause);
    }

    public SegmentationRunFailureException(String message) {
        super(safeMessage(message));
    }

    private static String safeMessage(String message) {
        if (message == null || message.trim().isEmpty()) {
            return "Segmentation backend failed.";
        }
        return message.trim();
    }
}
