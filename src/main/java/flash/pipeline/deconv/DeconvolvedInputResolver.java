package flash.pipeline.deconv;

import flash.pipeline.naming.ImageNameParser;
import ij.IJ;

import java.io.File;
import java.util.List;

/**
 * Resolves a raw input image to its deconvolved mirror when one exists and is
 * newer than the source image.
 */
public final class DeconvolvedInputResolver {

    private static volatile LogSink logSink = new LogSink() {
        @Override
        public void log(String message) {
            IJ.log(message);
        }
    };

    private DeconvolvedInputResolver() {}

    public static File resolveInput(File originalImage, boolean useDeconvIfAvailable) {
        if (originalImage == null) return null;
        String baseName = ImageNameParser.stripExtension(originalImage.getName());
        return resolveInput(originalImage.getParentFile(), originalImage, baseName, useDeconvIfAvailable);
    }

    public static File resolveInput(File rootDir,
                                    File originalSource,
                                    String imageBaseName,
                                    boolean useDeconvIfAvailable) {
        if (originalSource == null) return null;

        String label = labelFor(originalSource, imageBaseName);
        if (!useDeconvIfAvailable) {
            log("[Deconv] " + label + ": useDeconv disabled - using raw");
            return originalSource;
        }

        List<File> mirrors = mirrorReadCandidates(rootDir, imageBaseName);
        boolean foundMirror = false;
        for (int i = 0; i < mirrors.size(); i++) {
            File mirror = mirrors.get(i);
            if (mirror == null || !mirror.isFile()) {
                continue;
            }
            foundMirror = true;
            if (mirror.lastModified() >= originalSource.lastModified()) {
                log("[Deconv] " + label + ": using deconvolved stack");
                return mirror;
            }
        }

        if (!foundMirror) {
            log("[Deconv] " + label + ": no deconvolved mirror - using raw");
            return originalSource;
        }

        log("[Deconv] " + label + ": deconvolved stale - using raw");
        return originalSource;
    }

    public static File mirrorPathFor(File originalImage) {
        if (originalImage == null) return null;
        String baseName = ImageNameParser.stripExtension(originalImage.getName());
        return mirrorPathFor(originalImage.getParentFile(), baseName);
    }

    public static File mirrorPathFor(File rootDir, String imageBaseName) {
        if (rootDir == null) return null;
        String baseName = imageBaseName == null ? "" : imageBaseName.trim();
        if (baseName.isEmpty()) return null;
        List<File> candidates = DeconvolutionIO.mergedDeconvFileReadCandidates(rootDir, baseName);
        File existing = DeconvolutionIO.firstExistingFile(candidates);
        return existing == null ? DeconvolutionIO.mergedDeconvFile(rootDir, baseName) : existing;
    }

    static void setLogSinkForTest(LogSink sink) {
        if (sink != null) {
            logSink = sink;
        }
    }

    static void resetForTest() {
        logSink = new LogSink() {
            @Override
            public void log(String message) {
                IJ.log(message);
            }
        };
    }

    private static String labelFor(File originalSource, String imageBaseName) {
        String baseName = imageBaseName == null ? "" : imageBaseName.trim();
        if (!baseName.isEmpty()) return baseName;
        return originalSource == null ? "image" : originalSource.getName();
    }

    private static void log(String message) {
        logSink.log(message);
    }

    private static List<File> mirrorReadCandidates(File rootDir, String imageBaseName) {
        String baseName = imageBaseName == null ? "" : imageBaseName.trim();
        if (rootDir == null || baseName.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        return DeconvolutionIO.mergedDeconvFileReadCandidates(rootDir, baseName);
    }

    interface LogSink {
        void log(String message);
    }
}
