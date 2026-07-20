package flash.pipeline.intensity.spatial;

import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.io.IoUtils;
import flash.pipeline.naming.ChannelFilenameCodec;
import ij.IJ;
import ij.ImagePlus;
import ij.process.ImageProcessor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Best-effort PNG writer for visual intensity-spatial verification overlays.
 */
public final class IntensitySpatialOverlayWriter {
    public interface FailureSink {
        void accept(File target, Exception failure);
    }

    public File overlayRoot(File projectRoot, String animalName) {
        File overlaysRoot = FlashProjectLayout
                .forDirectory(projectRoot.getAbsolutePath())
                .analysisImagesIntensityOverlaysDir();
        return new File(overlaysRoot, safeComponent(animalName));
    }

    public File overlayFile(File projectRoot,
                            String animalName,
                            String imageBase,
                            String roiLabel,
                            String channelName,
                            String analysisName) {
        String name = safeComponent(imageBase)
                + "_" + safeComponent(roiLabel)
                + "_" + ChannelFilenameCodec.toSafe(channelName)
                + "_" + safeComponent(analysisName)
                + ".png";
        return new File(overlayRoot(projectRoot, animalName), name);
    }

    public boolean writePngBestEffort(File projectRoot,
                                      String animalName,
                                      String imageBase,
                                      String roiLabel,
                                      String channelName,
                                      String analysisName,
                                      BufferedImage image,
                                      FailureSink failureSink) {
        File target = overlayFile(projectRoot, animalName, imageBase, roiLabel, channelName, analysisName);
        try {
            if (image == null) {
                throw new IllegalArgumentException("Overlay image is null.");
            }
            IoUtils.mustMkdirs(target.getParentFile());
            if (!ImageIO.write(image, "png", target)) {
                throw new IllegalStateException("No PNG writer available.");
            }
            return true;
        } catch (Exception e) {
            reportFailure(target, e, failureSink);
            return false;
        }
    }

    public boolean writePngBestEffort(File projectRoot,
                                      String animalName,
                                      String imageBase,
                                      String roiLabel,
                                      String channelName,
                                      String analysisName,
                                      ImagePlus image,
                                      FailureSink failureSink) {
        BufferedImage buffered = null;
        if (image != null) {
            ImageProcessor processor = image.getProcessor();
            if (processor != null) {
                buffered = processor.getBufferedImage();
            }
        }
        return writePngBestEffort(projectRoot, animalName, imageBase, roiLabel,
                channelName, analysisName, buffered, failureSink);
    }

    private static void reportFailure(File target, Exception failure, FailureSink failureSink) {
        if (failureSink != null) {
            failureSink.accept(target, failure);
        } else {
            IJ.log("[FLASH] Intensity-spatial overlay write skipped for "
                    + target.getAbsolutePath() + ": " + failure.getMessage());
        }
    }

    private static String safeComponent(String value) {
        String raw = value == null || value.trim().isEmpty() ? "Unspecified" : value.trim();
        String safe = raw.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        return safe.isEmpty() ? "Unspecified" : safe;
    }
}
