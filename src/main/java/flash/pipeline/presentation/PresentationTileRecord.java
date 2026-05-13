package flash.pipeline.presentation;

import java.io.File;

/**
 * One saved presentation image with enough metadata to place it in a tile and
 * draw biological scale annotations later.
 */
public final class PresentationTileRecord {
    private final File imageFile;
    private File annotatedImageFile;
    private final String animal;
    private final String hemisphere;
    private final String region;
    private final String imageId;
    private final String outputName;
    private final String stainName;
    private final int channelIndex;
    private final int widthPx;
    private final int heightPx;
    private final double pixelWidthUm;
    private final double pixelHeightUm;

    public PresentationTileRecord(File imageFile,
                                  String animal,
                                  String hemisphere,
                                  String region,
                                  String outputName,
                                  String stainName,
                                  int channelIndex,
                                  int widthPx,
                                  int heightPx,
                                  double pixelWidthUm,
                                  double pixelHeightUm) {
        this(imageFile, animal, hemisphere, region, "",
                outputName, stainName, channelIndex, widthPx, heightPx,
                pixelWidthUm, pixelHeightUm);
    }

    public PresentationTileRecord(File imageFile,
                                  String animal,
                                  String hemisphere,
                                  String region,
                                  String imageId,
                                  String outputName,
                                  String stainName,
                                  int channelIndex,
                                  int widthPx,
                                  int heightPx,
                                  double pixelWidthUm,
                                  double pixelHeightUm) {
        this.imageFile = imageFile;
        this.animal = clean(animal, "Unknown");
        this.hemisphere = clean(hemisphere, "");
        this.region = clean(region, "");
        this.imageId = clean(imageId, "");
        this.outputName = clean(outputName, "");
        this.stainName = clean(stainName, this.outputName);
        this.channelIndex = channelIndex;
        this.widthPx = Math.max(1, widthPx);
        this.heightPx = Math.max(1, heightPx);
        this.pixelWidthUm = pixelWidthUm;
        this.pixelHeightUm = pixelHeightUm;
    }

    public File imageFile() {
        return imageFile;
    }

    public File annotatedImageFile() {
        return annotatedImageFile;
    }

    public void setAnnotatedImageFile(File annotatedImageFile) {
        this.annotatedImageFile = annotatedImageFile;
    }

    public File preferredImageFile(boolean preferAnnotated) {
        if (preferAnnotated && annotatedImageFile != null && annotatedImageFile.isFile()) {
            return annotatedImageFile;
        }
        return imageFile;
    }

    public String animal() {
        return animal;
    }

    public String hemisphere() {
        return hemisphere;
    }

    public String region() {
        return region;
    }

    public String imageId() {
        return imageId;
    }

    public String outputName() {
        return outputName;
    }

    public String stainName() {
        return stainName;
    }

    public int channelIndex() {
        return channelIndex;
    }

    public int widthPx() {
        return widthPx;
    }

    public int heightPx() {
        return heightPx;
    }

    public double pixelWidthUm() {
        return pixelWidthUm;
    }

    public double pixelHeightUm() {
        return pixelHeightUm;
    }

    public String imageKey() {
        StringBuilder sb = new StringBuilder(animal);
        if (!hemisphere.isEmpty()) sb.append('|').append(hemisphere);
        if (!region.isEmpty()) sb.append('|').append(region);
        if (!imageId.isEmpty()) sb.append('|').append(imageId);
        return sb.toString();
    }

    public String imageLabel() {
        StringBuilder sb = new StringBuilder(animal);
        if (!hemisphere.isEmpty()) sb.append(' ').append(hemisphere);
        if (!region.isEmpty()) sb.append(' ').append(region);
        return sb.toString();
    }

    private static String clean(String value, String fallback) {
        String trimmed = value == null ? "" : value.trim();
        if (!trimmed.isEmpty()) return trimmed;
        return fallback == null ? "" : fallback;
    }
}
