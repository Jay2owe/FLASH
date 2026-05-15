package flash.pipeline.segmentation;

import ij.ImagePlus;
import smile.classification.RandomForest;

import java.nio.file.Path;
import java.util.Arrays;

public final class TrainedRfParameters {
    public final String modelKey;
    public final Path projectRoot;
    public final Path modelPath;
    public final RandomForest model;
    public final String[] featureNames;
    public final SegmentationMethod base;
    public final double keepThreshold;
    public final int threshold;
    public final int minSize;
    public final int maxSize;
    public final ImagePlus intensityImage;
    public final ImagePlus auxImage;
    public final TrainedRfRunner.BaseRunner baseRunner;
    public final TrainedRfRunner.WarningSink warningSink;

    public TrainedRfParameters(String modelKey,
                               Path projectRoot,
                               Path modelPath,
                               RandomForest model,
                               String[] featureNames,
                               SegmentationMethod base,
                               double keepThreshold,
                               int threshold,
                               int minSize,
                               int maxSize,
                               ImagePlus intensityImage,
                               ImagePlus auxImage,
                               TrainedRfRunner.BaseRunner baseRunner,
                               TrainedRfRunner.WarningSink warningSink) {
        this.modelKey = modelKey == null ? "" : modelKey.trim();
        this.projectRoot = projectRoot;
        this.modelPath = modelPath;
        this.model = model;
        this.featureNames = featureNames == null ? new String[0] : Arrays.copyOf(featureNames, featureNames.length);
        this.base = base == null ? SegmentationMethod.classical("classical") : base;
        this.keepThreshold = Double.isFinite(keepThreshold) ? keepThreshold : 0.5;
        this.threshold = threshold;
        this.minSize = Math.max(0, minSize);
        this.maxSize = Math.max(this.minSize, maxSize);
        this.intensityImage = intensityImage;
        this.auxImage = auxImage;
        this.baseRunner = baseRunner;
        this.warningSink = warningSink;
    }

    public static TrainedRfParameters fromMethod(SegmentationMethod method,
                                                 Path projectRoot,
                                                 ImagePlus intensityImage,
                                                 int threshold,
                                                 int minSize,
                                                 int maxSize,
                                                 double keepThreshold) {
        SegmentationMethod safe = method == null
                ? SegmentationMethod.classical("classical")
                : method;
        return new TrainedRfParameters(
                SegmentationMethod.trainedRfModelKey(safe),
                projectRoot,
                null,
                null,
                null,
                SegmentationMethod.trainedRfBase(safe),
                keepThreshold,
                threshold,
                minSize,
                maxSize,
                intensityImage,
                null,
                null,
                null);
    }

    public TrainedRfParameters withBaseRunner(TrainedRfRunner.BaseRunner runner) {
        return new TrainedRfParameters(modelKey, projectRoot, modelPath, model, featureNames, base,
                keepThreshold, threshold, minSize, maxSize, intensityImage, auxImage, runner, warningSink);
    }

    public TrainedRfParameters withWarningSink(TrainedRfRunner.WarningSink sink) {
        return new TrainedRfParameters(modelKey, projectRoot, modelPath, model, featureNames, base,
                keepThreshold, threshold, minSize, maxSize, intensityImage, auxImage, baseRunner, sink);
    }
}
