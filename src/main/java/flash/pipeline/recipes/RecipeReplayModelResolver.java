package flash.pipeline.recipes;

import flash.pipeline.cellpose.Cellpose3DRunner;
import flash.pipeline.segmentation.SegmentationMethod;
import flash.pipeline.segmentation.SegmentationTokenParser;
import flash.pipeline.segmentation.catalog.ModelCatalog;
import flash.pipeline.segmentation.catalog.ModelCatalogIO;
import flash.pipeline.stardist.StarDist3DRunner;

import java.io.File;
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
        CELLPOSE
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
            if (method.isStarDist()) {
                String modelKey = SegmentationMethod.starDistModelKey(method);
                File modelFile;
                try {
                    modelFile = StarDist3DRunner.resolveStarDistModelFile(
                            modelKey, root.toFile(), null, channelLabel(i));
                } catch (IllegalArgumentException e) {
                    throw e;
                } catch (IllegalStateException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException("Could not resolve StarDist model '"
                            + modelKey + "' from the project catalog: " + e.getMessage(), e);
                }
                resolved.add(new ResolvedModelUse(
                        i, Engine.STARDIST, modelKey, modelFile.getAbsolutePath()));
            } else if (method.isCellpose()) {
                String modelKey = SegmentationMethod.cellposeModelKey(method);
                String argument = Cellpose3DRunner.resolvePretrainedModelArgument(modelKey, catalog);
                resolved.add(new ResolvedModelUse(i, Engine.CELLPOSE, modelKey, argument));
            }
        }
        return Collections.unmodifiableList(resolved);
    }

    public static void validate(Path projectRoot, List<String> segmentationTokens) {
        resolve(projectRoot, segmentationTokens);
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
