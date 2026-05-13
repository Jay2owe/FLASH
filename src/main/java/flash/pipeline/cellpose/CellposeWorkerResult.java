package flash.pipeline.cellpose;

import ij.ImagePlus;

public final class CellposeWorkerResult {

    private final String id;
    private final ImagePlus labelImage;
    private final long durationMs;
    private final String errorText;

    public CellposeWorkerResult(String id,
                                ImagePlus labelImage,
                                long durationMs,
                                String errorText) {
        this.id = id == null ? "" : id;
        this.labelImage = labelImage;
        this.durationMs = Math.max(0L, durationMs);
        this.errorText = errorText == null ? "" : errorText;
    }

    public static CellposeWorkerResult success(String id,
                                               ImagePlus labelImage,
                                               long durationMs) {
        return new CellposeWorkerResult(id, labelImage, durationMs, "");
    }

    public static CellposeWorkerResult failure(String id, String errorText) {
        return new CellposeWorkerResult(id, null, 0L, errorText);
    }

    public String id() {
        return id;
    }

    public String getId() {
        return id;
    }

    public ImagePlus labelImage() {
        return labelImage;
    }

    public ImagePlus getLabelImage() {
        return labelImage;
    }

    public long durationMs() {
        return durationMs;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public String errorText() {
        return errorText;
    }

    public String getErrorText() {
        return errorText;
    }

    public boolean hasError() {
        return !errorText.isEmpty() || labelImage == null;
    }
}
