package flash.pipeline.analyses.spatial;

import flash.pipeline.analyses.SpatialAnalysis;
import ij.IJ;
import ij.ImagePlus;

import java.io.File;

public final class DiskLabelImageProvider implements LabelImageProvider {
    private final SpatialAnalysis owner;
    private final String directory;

    public DiskLabelImageProvider(SpatialAnalysis owner, String directory) {
        this.owner = owner;
        this.directory = directory;
    }

    @Override
    public ImagePlus get(String channelName, SectionKey section) {
        File labelFile = owner.resolveCpcLabelFile(directory, channelName, section);
        if (labelFile == null || !labelFile.isFile()) {
            return null;
        }
        return IJ.openImage(labelFile.getAbsolutePath());
    }

    @Override
    public void release(String channelName, SectionKey section) {
        // Two-argument release carries no image instance; nothing to close here.
    }

    @Override
    public void release(String channelName, SectionKey section, ImagePlus image) {
        if (image != null) {
            image.changes = false;
            image.close();
            image.flush();
        }
        release(channelName, section);
    }
}
