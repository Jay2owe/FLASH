package flash.pipeline.analyses.spatial;

import ij.ImagePlus;

import java.util.Map;

public final class InMemoryLabelImageProvider implements LabelImageProvider {
    private final Map<SectionKey, Map<String, ImagePlus>> cache;
    private final LabelImageProvider fallback;

    public InMemoryLabelImageProvider(Map<SectionKey, Map<String, ImagePlus>> cache,
                                      LabelImageProvider fallback) {
        this.cache = cache;
        this.fallback = fallback;
    }

    @Override
    public ImagePlus get(String channelName, SectionKey section) {
        ImagePlus cached = cachedImage(channelName, section);
        if (cached != null) {
            return cached;
        }
        return fallback == null ? null : fallback.get(channelName, section);
    }

    @Override
    public void release(String channelName, SectionKey section) {
        // No-op: ThreeDObjectAnalysis owns cached label lifecycle.
    }

    @Override
    public void release(String channelName, SectionKey section, ImagePlus image) {
        if (image == null) {
            return;
        }
        ImagePlus cached = cachedImage(channelName, section);
        if (cached == image) {
            return;
        }
        if (fallback != null) {
            fallback.release(channelName, section, image);
            return;
        }
        image.changes = false;
        image.close();
        image.flush();
    }

    public boolean hasCached(String channelName, SectionKey section) {
        return cachedImage(channelName, section) != null;
    }

    public int cachedLabelCount() {
        int count = 0;
        if (cache == null) {
            return 0;
        }
        synchronized (cache) {
            for (Map<String, ImagePlus> perChannel : cache.values()) {
                if (perChannel != null) {
                    count += perChannel.size();
                }
            }
        }
        return count;
    }

    public void clear() {
        if (cache != null) {
            synchronized (cache) {
                cache.clear();
            }
        }
    }

    private ImagePlus cachedImage(String channelName, SectionKey section) {
        if (cache == null || section == null) {
            return null;
        }
        synchronized (cache) {
            Map<String, ImagePlus> perChannel = cache.get(section);
            return perChannel == null ? null : perChannel.get(channelName);
        }
    }
}
