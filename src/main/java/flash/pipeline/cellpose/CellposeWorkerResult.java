package flash.pipeline.cellpose;

import ij.ImagePlus;

import java.nio.file.Path;
import java.util.Optional;

public final class CellposeWorkerResult {

    private final String id;
    private final ImagePlus labelImage;
    private final long durationMs;
    private final String errorText;
    private final Optional<Path> cellprobPath;

    public CellposeWorkerResult(String id,
                                ImagePlus labelImage,
                                long durationMs,
                                String errorText) {
        this(id, labelImage, durationMs, errorText, Optional.<Path>empty());
    }

    public CellposeWorkerResult(String id,
                                ImagePlus labelImage,
                                long durationMs,
                                String errorText,
                                Optional<Path> cellprobPath) {
        this.id = id == null ? "" : id;
        this.labelImage = labelImage;
        this.durationMs = Math.max(0L, durationMs);
        this.errorText = errorText == null ? "" : errorText;
        this.cellprobPath = cellprobPath == null
                ? Optional.<Path>empty()
                : cellprobPath;
    }

    public static CellposeWorkerResult success(String id,
                                               ImagePlus labelImage,
                                               long durationMs) {
        return success(id, labelImage, durationMs, Optional.<Path>empty());
    }

    public static CellposeWorkerResult success(String id,
                                               ImagePlus labelImage,
                                               long durationMs,
                                               Optional<Path> cellprobPath) {
        return new CellposeWorkerResult(id, labelImage, durationMs, "", cellprobPath);
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

    public Optional<Path> cellprobPath() {
        return cellprobPath;
    }

    public Optional<Path> getCellprobPath() {
        return cellprobPath;
    }

    public boolean hasError() {
        return !errorText.isEmpty() || labelImage == null;
    }
}
