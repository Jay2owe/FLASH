package flash.pipeline.recipes;

import flash.pipeline.cellpose.Cellpose3DRunner;
import flash.pipeline.click.training.ObjectClassifierPersistence;
import flash.pipeline.segmentation.SegmentationMethod;
import flash.pipeline.segmentation.SegmentationTokenParser;
import flash.pipeline.segmentation.catalog.ModelCatalog;
import flash.pipeline.segmentation.catalog.ModelCatalogIO;
import flash.pipeline.segmentation.catalog.ModelEntry;
import flash.pipeline.stardist.StarDist3DRunner;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Resolves catalog-backed model references from replayed segmentation tokens.
 */
public final class RecipeReplayModelResolver {

    public enum Engine {
        STARDIST,
        CELLPOSE,
        TRAINED_RF
    }

    public static final class ResolvedModelUse {
        public final int channelIndex;
        public final Engine engine;
        public final String modelKey;
        public final String runtimeArgument;

        private ResolvedModelUse(int channelIndex, Engine engine,
                                 String modelKey, String runtimeArgument) {
            this.channelIndex = channelIndex;
            this.engine = engine;
            this.modelKey = modelKey;
            this.runtimeArgument = runtimeArgument;
        }
    }

    private RecipeReplayModelResolver() {
    }

    public static List<ResolvedModelUse> resolve(Path projectRoot,
                                                 List<String> segmentationTokens) {
        Path root = requireProjectRoot(projectRoot);
        if (segmentationTokens == null || segmentationTokens.isEmpty()) {
            return Collections.emptyList();
        }

        ModelCatalog catalog = ModelCatalogIO.read(root);
        List<ResolvedModelUse> resolved = new ArrayList<ResolvedModelUse>();
        for (int i = 0; i < segmentationTokens.size(); i++) {
            String token = segmentationTokens.get(i);
            if (token == null || token.trim().isEmpty()) {
                continue;
            }
            SegmentationMethod method = SegmentationTokenParser.parseLenient(token);
            resolved.addAll(resolveMethod(root, catalog, i, method));
        }
        return Collections.unmodifiableList(resolved);
    }

    public static void validate(Path projectRoot, List<String> segmentationTokens) {
        Path root = requireProjectRoot(projectRoot);
        if (segmentationTokens == null || segmentationTokens.isEmpty()) {
            return;
        }

        ModelCatalog catalog = ModelCatalogIO.read(root);
        List<String> unresolved = new ArrayList<String>();
        List<String> details = new ArrayList<String>();
        for (int i = 0; i < segmentationTokens.size(); i++) {
            String token = segmentationTokens.get(i);
            if (token == null || token.trim().isEmpty()) {
                continue;
            }
            SegmentationMethod method = SegmentationTokenParser.parseLenient(token);
            try {
                resolveMethod(root, catalog, i, method);
            } catch (RuntimeException e) {
                unresolved.add(channelLabel(i) + " " + modelLabel(method));
                details.add(e.getMessage());
            }
        }
        if (!unresolved.isEmpty()) {
            throw new IllegalStateException("Missing segmentation models: " + unresolved
                    + ". " + joinDetails(details));
        }
    }

    private static List<ResolvedModelUse> resolveMethod(Path root,
                                                        ModelCatalog catalog,
                                                        int channelIndex,
                                                        SegmentationMethod method) {
        List<ResolvedModelUse> resolved = new ArrayList<ResolvedModelUse>();
        if (method == null) {
            return resolved;
        }
        if (method.isStarDist()) {
            resolved.add(resolveStarDist(root, channelIndex, method));
        } else if (method.isCellpose()) {
            resolved.add(resolveCellpose(catalog, channelIndex, method));
        } else if (method.isTrainedRf()) {
            resolved.add(resolveTrainedRf(root, catalog, channelIndex, method));
            SegmentationMethod base = SegmentationMethod.trainedRfBase(method);
            if (base.isStarDist()) {
                resolved.add(resolveStarDist(root, channelIndex, base));
            } else if (base.isCellpose()) {
                resolved.add(resolveCellpose(catalog, channelIndex, base));
            }
        }
        return resolved;
    }

    private static ResolvedModelUse resolveStarDist(Path root,
                                                    int channelIndex,
                                                    SegmentationMethod method) {
        String modelKey = SegmentationMethod.starDistModelKey(method);
        File modelFile;
        try {
            modelFile = StarDist3DRunner.resolveStarDistModelFile(
                    modelKey, root.toFile(), null, channelLabel(channelIndex));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Could not resolve StarDist model '"
                    + modelKey + "' from the project catalog: " + e.getMessage(), e);
        }
        return new ResolvedModelUse(
                channelIndex, Engine.STARDIST, modelKey, modelFile.getAbsolutePath());
    }

    private static ResolvedModelUse resolveCellpose(ModelCatalog catalog,
                                                    int channelIndex,
                                                    SegmentationMethod method) {
        String modelKey = SegmentationMethod.cellposeModelKey(method);
        String argument = Cellpose3DRunner.resolvePretrainedModelArgument(modelKey, catalog);
        return new ResolvedModelUse(channelIndex, Engine.CELLPOSE, modelKey, argument);
    }

    private static ResolvedModelUse resolveTrainedRf(Path root,
                                                     ModelCatalog catalog,
                                                     int channelIndex,
                                                     SegmentationMethod method) {
        String modelKey = SegmentationMethod.trainedRfModelKey(method);
        Path modelPath = resolveTrainedRfModelPath(root, catalog, modelKey);
        return new ResolvedModelUse(
                channelIndex, Engine.TRAINED_RF, modelKey, modelPath.toString());
    }

    private static Path resolveTrainedRfModelPath(Path root,
                                                  ModelCatalog catalog,
                                                  String modelKey) {
        String key = modelKey == null ? "" : modelKey.trim();
        if (key.isEmpty()) {
            throw new IllegalArgumentException("Trained RF model key is missing.");
        }
        if (catalog != null) {
            java.util.Optional<ModelEntry> entry = catalog.get(key);
            if (entry.isPresent()) {
                ModelEntry modelEntry = entry.get();
                if (modelEntry.engine != ModelEntry.Engine.SMILE_RF) {
                    throw new IllegalArgumentException("Catalog model '" + key
                            + "' is not a Smile RF model.");
                }
                Path resolved;
                try {
                    resolved = catalog.resolve(modelEntry);
                } catch (IOException e) {
                    throw new IllegalStateException("Could not resolve trained RF model '"
                            + key + "': " + e.getMessage(), e);
                }
                if (resolved == null || !Files.isRegularFile(resolved)) {
                    throw new IllegalStateException("Trained RF model file for '" + key
                            + "' does not exist: " + resolved
                            + ". Please retrain/import it via Manage Models or select a different model.");
                }
                return resolved.toAbsolutePath().normalize();
            }
        }

        Path legacyPath = ObjectClassifierPersistence.modelPath(root, key);
        if (Files.isRegularFile(legacyPath)) {
            return legacyPath;
        }
        throw new IllegalArgumentException("Trained RF model '" + key
                + "' not found in catalog and expected file does not exist: " + legacyPath
                + ". Please retrain/import it via Manage Models or select a different model.");
    }

    private static String modelLabel(SegmentationMethod method) {
        if (method == null) return "<unknown>";
        if (method.isStarDist()) {
            return "stardist:" + SegmentationMethod.starDistModelKey(method);
        }
        if (method.isCellpose()) {
            return "cellpose:" + SegmentationMethod.cellposeModelKey(method);
        }
        if (method.isTrainedRf()) {
            return "trained_rf:" + SegmentationMethod.trainedRfModelKey(method);
        }
        return method.rawToken == null || method.rawToken.trim().isEmpty()
                ? method.engine.name()
                : method.rawToken.trim();
    }

    private static String joinDetails(List<String> details) {
        StringBuilder sb = new StringBuilder();
        for (String detail : details) {
            if (detail == null || detail.trim().isEmpty()) {
                continue;
            }
            if (sb.length() > 0) sb.append("; ");
            sb.append(detail.trim());
        }
        return sb.toString();
    }

    private static Path requireProjectRoot(Path projectRoot) {
        if (projectRoot == null) {
            throw new IllegalArgumentException("Project root is required for recipe replay model resolution.");
        }
        return projectRoot.toAbsolutePath().normalize();
    }

    private static String channelLabel(int channelIndex) {
        return "channel " + (channelIndex + 1);
    }
}
