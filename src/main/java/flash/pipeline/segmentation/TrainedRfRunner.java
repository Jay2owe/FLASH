package flash.pipeline.segmentation;

import flash.pipeline.cellpose.Cellpose3DRunner;
import flash.pipeline.click.training.ObjectClassifierPersistence;
import flash.pipeline.click.training.ObjectClassifierScorer;
import flash.pipeline.click.training.ObjectFeatureExtractor;
import flash.pipeline.image.ImageOps;
import flash.pipeline.objects.ObjectsCounter3DWrapper;
import flash.pipeline.segmentation.catalog.ModelCatalog;
import flash.pipeline.segmentation.catalog.ModelCatalogIO;
import flash.pipeline.segmentation.catalog.ModelEntry;
import flash.pipeline.stardist.StarDist3DRunner;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.ResultsTable;
import ij.process.ImageProcessor;
import smile.classification.RandomForest;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public final class TrainedRfRunner {
    public static final String OBJECT_STATS_PROPERTY = "flash.trained_rf.objectStats";

    public interface WarningSink {
        void warn(String message);
    }

    public interface BaseRunner {
        ImagePlus run(ImagePlus channelImage, TrainedRfParameters params);
    }

    public ImagePlus run(ImagePlus channelImage, TrainedRfParameters params) {
        if (channelImage == null) {
            throw new IllegalArgumentException("channelImage must not be null");
        }
        TrainedRfParameters safe = params == null
                ? new TrainedRfParameters("", null, null, null, null,
                SegmentationMethod.classical("classical"), 0.5, 0, 0, Integer.MAX_VALUE,
                channelImage, null, null, null)
                : params;

        SegmentationMethod base = safe.base == null
                ? SegmentationMethod.classical("classical")
                : safe.base;

        if (base.isTrainedRf()) {
            warn(safe, "Warning: Trained RF post-filter cannot use another Trained RF as its base; "
                    + "returning the base output unchanged.");
            return runBaseUnchanged(channelImage, safe);
        }

        ImagePlus baseLabels = runSupportedBase(channelImage, safe, base);
        if (baseLabels == null) {
            return null;
        }

        ModelBundle bundle = loadModel(safe);
        ObjectFeatureExtractor extractor = new ObjectFeatureExtractor();
        ImagePlus intensity = safe.intensityImage == null ? channelImage : safe.intensityImage;
        ImagePlus aux = safe.auxImage == null ? cellprobImage(baseLabels) : safe.auxImage;
        List<ObjectFeatureExtractor.FeatureRow> rows =
                extractor.extractFromLabelImage(baseLabels, intensity, aux, null);

        ObjectClassifierScorer scorer = new ObjectClassifierScorer();
        List<ObjectClassifierScorer.ScoreResult> scores =
                scorer.score(bundle.model, bundle.featureNames, rows, safe.keepThreshold);
        ImagePlus filtered = scorer.filterLabelImage(baseLabels, scores);
        ResultsTable table = scorer.toResultsTable(scores);
        if (filtered != null) {
            filtered.setProperty(OBJECT_STATS_PROPERTY, table);
        }
        return filtered;
    }

    private ImagePlus runSupportedBase(ImagePlus channelImage,
                                       TrainedRfParameters params,
                                       SegmentationMethod base) {
        if (params.baseRunner != null) {
            return params.baseRunner.run(channelImage, params);
        }
        if (base.isEnhancedClassical()) {
            return new EnhancedClassicalRunner().run(
                    channelImage,
                    EnhancedClassicalParameters.fromMethod(base,
                            params.intensityImage == null ? channelImage : params.intensityImage,
                            new EnhancedClassicalParameters.WarningSink() {
                                @Override
                                public void warn(String message) {
                                    IJ.log(message);
                                }
                            }));
        }
        if (base.isStarDist()) {
            return StarDist3DRunner.run(channelImage, base, null, projectRootFile(params));
        }
        if (base.isCellpose()) {
            return Cellpose3DRunner.run(
                    channelImage,
                    params.cellposeCompanionImage,
                    SegmentationMethod.cellposeModelKey(base),
                    SegmentationMethod.cellposeDiameter(base),
                    SegmentationMethod.cellposeFlow(base),
                    SegmentationMethod.cellposeCellprob(base),
                    SegmentationMethod.cellposeUseGpu(base),
                    null,
                    projectRootFile(params),
                    true);
        }
        return runClassicalBase(channelImage, params);
    }

    private ImagePlus runClassicalBase(ImagePlus channelImage, TrainedRfParameters params) {
        ObjectsCounter3DWrapper.Result result = new ObjectsCounter3DWrapper().runNative(
                channelImage,
                params.threshold,
                params.minSize,
                params.maxSize,
                false,
                params.intensityImage == null ? channelImage : params.intensityImage,
                true,
                false);
        ImagePlus labels = result == null ? null : result.getObjectsMap();
        if (labels == null) {
            labels = emptyLabelMapLike(channelImage);
        }
        return labels;
    }

    private ImagePlus runBaseUnchanged(ImagePlus channelImage, TrainedRfParameters params) {
        if (params.baseRunner != null) {
            return params.baseRunner.run(channelImage, params);
        }
        return ImageOps.duplicateThreadSafe(channelImage);
    }

    private ModelBundle loadModel(TrainedRfParameters params) {
        if (params.model != null) {
            String[] featureNames = params.featureNames.length == 0
                    ? featureNamesFromModel(params.model)
                    : params.featureNames;
            if (featureNames.length == 0) {
                featureNames = ObjectFeatureExtractor.allFeatureNames();
            }
            return new ModelBundle(params.model, featureNames);
        }

        Path modelPath = params.modelPath;
        String[] featureNames = params.featureNames;
        if (modelPath == null && params.projectRoot != null && params.modelKey != null
                && !params.modelKey.trim().isEmpty()) {
            ModelCatalog catalog = ModelCatalogIO.read(params.projectRoot);
            Optional<ModelEntry> entry = catalog.get(params.modelKey);
            if (entry.isPresent()) {
                ModelEntry modelEntry = entry.get();
                if (modelEntry.engine != ModelEntry.Engine.SMILE_RF) {
                    throw new IllegalArgumentException("Catalog model '" + params.modelKey
                            + "' is not a Smile RF model.");
                }
                featureNames = ObjectClassifierPersistence.featureNamesFromMetadata(modelEntry);
                try {
                    modelPath = catalog.resolve(modelEntry);
                } catch (IOException e) {
                    throw new IllegalArgumentException("Could not resolve Smile RF model '"
                            + params.modelKey + "': " + e.getMessage(), e);
                }
            }
        }
        if (modelPath == null && params.projectRoot != null && params.modelKey != null
                && !params.modelKey.trim().isEmpty()) {
            modelPath = ObjectClassifierPersistence.modelPath(params.projectRoot, params.modelKey);
        }
        if (modelPath == null) {
            throw new IllegalArgumentException("No Smile RF model or model path supplied.");
        }
        try {
            RandomForest model = ObjectClassifierPersistence.loadModel(modelPath);
            if (featureNames == null || featureNames.length == 0) {
                featureNames = featureNamesFromModel(model);
            }
            if (featureNames == null || featureNames.length == 0) {
                featureNames = ObjectFeatureExtractor.allFeatureNames();
            }
            return new ModelBundle(model, featureNames);
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not load Smile RF model from " + modelPath
                    + ": " + e.getMessage(), e);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Could not deserialize Smile RF model from " + modelPath
                    + ": " + e.getMessage(), e);
        }
    }

    private static String[] featureNamesFromModel(RandomForest model) {
        if (model == null) return new String[0];
        try {
            smile.data.type.StructType schema = model.schema();
            if (schema == null || schema.length() == 0) return new String[0];
            String[] names = new String[schema.length()];
            for (int i = 0; i < names.length; i++) {
                names[i] = schema.fieldName(i);
            }
            return names;
        } catch (RuntimeException e) {
            return new String[0];
        }
    }

    private static ImagePlus emptyLabelMapLike(ImagePlus source) {
        ImagePlus empty = ImageOps.duplicateThreadSafe(source);
        if (empty == null || empty.getStack() == null) return empty;
        ImageStack stack = empty.getStack();
        for (int slice = 1; slice <= stack.getSize(); slice++) {
            ImageProcessor processor = stack.getProcessor(slice);
            if (processor == null) continue;
            processor.setValue(0.0);
            processor.fill();
        }
        empty.updateAndDraw();
        return empty;
    }

    private static File projectRootFile(TrainedRfParameters params) {
        Path root = params == null ? null : params.projectRoot;
        return root == null ? null : root.toFile();
    }

    private static ImagePlus cellprobImage(ImagePlus labelImage) {
        if (labelImage == null) return null;
        try {
            Object property = labelImage.getProperty(Cellpose3DRunner.CELLPROB_IMAGE_PROPERTY);
            return property instanceof ImagePlus ? (ImagePlus) property : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void warn(TrainedRfParameters params, String message) {
        WarningSink sink = params == null ? null : params.warningSink;
        if (sink != null) {
            sink.warn(message);
        } else {
            IJ.log(message);
        }
    }

    private static final class ModelBundle {
        final RandomForest model;
        final String[] featureNames;

        ModelBundle(RandomForest model, String[] featureNames) {
            this.model = model;
            this.featureNames = featureNames;
        }
    }
}
