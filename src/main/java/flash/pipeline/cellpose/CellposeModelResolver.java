package flash.pipeline.cellpose;

import flash.pipeline.segmentation.SegmentationMethod;
import flash.pipeline.segmentation.catalog.ModelCatalog;
import flash.pipeline.segmentation.catalog.ModelEntry;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

public final class CellposeModelResolver {

    public static final class Resolved {
        public final boolean built_in;
        public final String pretrainedName;
        public final String absolutePath;

        private Resolved(boolean builtIn, String pretrainedName, String absolutePath) {
            this.built_in = builtIn;
            this.pretrainedName = pretrainedName;
            this.absolutePath = absolutePath;
        }

        public static Resolved builtIn(String pretrainedName) {
            return new Resolved(true, pretrainedName, null);
        }

        public static Resolved file(String absolutePath) {
            return new Resolved(false, null, absolutePath);
        }
    }

    public Optional<Resolved> resolve(String modelKey, ModelCatalog catalog) {
        String key = normalizeModelKey(modelKey);
        if (key == null || catalog == null) {
            return Optional.empty();
        }
        Optional<ModelEntry> found = catalog.get(key);
        if (!found.isPresent()) {
            return Optional.empty();
        }
        ModelEntry entry = found.get();
        if (entry.engine != ModelEntry.Engine.CELLPOSE) {
            return Optional.empty();
        }
        if (entry.source == ModelEntry.Source.STOCK_BUILTIN) {
            if (!entry.pretrainedModel.isPresent()) {
                return Optional.empty();
            }
            return Optional.of(Resolved.builtIn(entry.pretrainedModel.get()));
        }
        try {
            Path resolved = catalog.resolve(entry);
            if (resolved == null) {
                return Optional.empty();
            }
            return Optional.of(Resolved.file(resolved.toAbsolutePath().normalize().toString()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public static String normalizeModelKey(String modelKey) {
        if (modelKey == null || modelKey.trim().isEmpty()) {
            return null;
        }
        return SegmentationMethod.canonicalCellposeModelKey(modelKey);
    }
}
