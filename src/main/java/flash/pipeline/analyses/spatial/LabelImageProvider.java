package flash.pipeline.analyses.spatial;

import ij.ImagePlus;

public interface LabelImageProvider {
    /**
     * Returns the label image for the requested channel and section, or null if unavailable.
     */
    ImagePlus get(String channelName, SectionKey section);

    /**
     * Signals that the caller is finished with the returned image.
     */
    void release(String channelName, SectionKey section);

    /**
     * Signals that the caller is finished with the returned image instance.
     * Disk-backed providers usually close fresh images here; shared in-memory
     * providers can keep cached images alive for later sub-analyses.
     */
    void release(String channelName, SectionKey section, ImagePlus image);
}
