package flash.pipeline.representative;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One selectable source series with the preview thumbnails shown in Step 2.
 */
public final class RepresentativeSeries {

    /**
     * Source used to populate the representative selection thumbnail.
     */
    public enum PreviewSource {
        CACHE,
        PRESENTATION,
        GENERATED
    }

    private final String id;
    private final int seriesIndex;
    private final int seriesNumber;
    private final String seriesName;
    private final String animal;
    private final String condition;
    private final String hemisphere;
    private final String region;
    private final File sourcePath;
    private final List<ChannelThumbnail> channelThumbnails;
    private final BufferedImage mergeThumbnail;
    private final File mergeCacheFile;
    private final PreviewSource previewSource;
    private final boolean cacheHit;
    private final double pixelWidthUm;
    private final double pixelHeightUm;

    public RepresentativeSeries(String id,
                                int seriesIndex,
                                int seriesNumber,
                                String seriesName,
                                String animal,
                                String condition,
                                String hemisphere,
                                String region,
                                File sourcePath,
                                List<ChannelThumbnail> channelThumbnails,
                                BufferedImage mergeThumbnail,
                                File mergeCacheFile,
                                PreviewSource previewSource,
                                boolean cacheHit) {
        this(id, seriesIndex, seriesNumber, seriesName, animal, condition,
                hemisphere, region, sourcePath, channelThumbnails, mergeThumbnail,
                mergeCacheFile, previewSource, cacheHit, Double.NaN, Double.NaN);
    }

    public RepresentativeSeries(String id,
                                int seriesIndex,
                                int seriesNumber,
                                String seriesName,
                                String animal,
                                String condition,
                                String hemisphere,
                                String region,
                                File sourcePath,
                                List<ChannelThumbnail> channelThumbnails,
                                BufferedImage mergeThumbnail,
                                File mergeCacheFile,
                                PreviewSource previewSource,
                                boolean cacheHit,
                                double pixelWidthUm,
                                double pixelHeightUm) {
        this.id = clean(id);
        this.seriesIndex = seriesIndex;
        this.seriesNumber = seriesNumber;
        this.seriesName = clean(seriesName);
        this.animal = clean(animal);
        this.condition = clean(condition);
        this.hemisphere = clean(hemisphere);
        this.region = clean(region);
        this.sourcePath = sourcePath;
        this.channelThumbnails = immutableThumbnails(channelThumbnails);
        this.mergeThumbnail = mergeThumbnail;
        this.mergeCacheFile = mergeCacheFile;
        this.previewSource = previewSource == null ? PreviewSource.GENERATED : previewSource;
        this.cacheHit = cacheHit;
        this.pixelWidthUm = pixelWidthUm;
        this.pixelHeightUm = pixelHeightUm;
    }

    public String id() {
        return id;
    }

    public int seriesIndex() {
        return seriesIndex;
    }

    public int seriesNumber() {
        return seriesNumber;
    }

    public String seriesName() {
        return seriesName;
    }

    public String animal() {
        return animal;
    }

    public String condition() {
        return condition;
    }

    public String hemisphere() {
        return hemisphere;
    }

    public String region() {
        return region;
    }

    public File sourcePath() {
        return sourcePath;
    }

    public List<ChannelThumbnail> channelThumbnails() {
        return channelThumbnails;
    }

    public BufferedImage mergeThumbnail() {
        return mergeThumbnail;
    }

    public File mergeCacheFile() {
        return mergeCacheFile;
    }

    public PreviewSource previewSource() {
        return previewSource;
    }

    public boolean cacheHit() {
        return cacheHit;
    }

    public double pixelWidthUm() {
        return pixelWidthUm;
    }

    public double pixelHeightUm() {
        return pixelHeightUm;
    }

    private static List<ChannelThumbnail> immutableThumbnails(List<ChannelThumbnail> thumbnails) {
        List<ChannelThumbnail> copy = new ArrayList<ChannelThumbnail>();
        if (thumbnails != null) {
            for (ChannelThumbnail thumbnail : thumbnails) {
                if (thumbnail != null) copy.add(thumbnail);
            }
        }
        return Collections.unmodifiableList(copy);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * One channel preview image plus its persistent cache file.
     */
    public static final class ChannelThumbnail {
        private final int channelIndex;
        private final String channelName;
        private final BufferedImage image;
        private final File cacheFile;

        public ChannelThumbnail(int channelIndex,
                                String channelName,
                                BufferedImage image,
                                File cacheFile) {
            this.channelIndex = channelIndex;
            this.channelName = clean(channelName);
            this.image = image;
            this.cacheFile = cacheFile;
        }

        public int channelIndex() {
            return channelIndex;
        }

        public String channelName() {
            return channelName;
        }

        public BufferedImage image() {
            return image;
        }

        public File cacheFile() {
            return cacheFile;
        }
    }
}
