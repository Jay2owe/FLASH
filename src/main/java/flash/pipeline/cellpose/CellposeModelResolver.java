package flash.pipeline.cellpose;

import flash.pipeline.segmentation.SegmentationMethod;
import flash.pipeline.segmentation.catalog.ModelCatalog;
import flash.pipeline.segmentation.catalog.ModelEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
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
            String pretrained = entry.pretrainedModel.get().trim();
            if (!isSafeRegisteredName(pretrained)
                    || CellposeRegisteredModels.isUnsupportedModelName(pretrained)) {
                return Optional.empty();
            }
            if (CellposeRegisteredModels.isDiscoveredCellposeEntry(entry)
                    && (!CellposeRegisteredModels.isSupportedBuiltinName(pretrained)
                    || !CellposeRegisteredModels.isValidatedForCurrentEnvironment(entry))) {
                return Optional.empty();
            }
            return Optional.of(Resolved.builtIn(pretrained));
        }
        if (CellposeRegisteredModels.isDiscoveredCellposeEntry(entry)) {
            if (!CellposeRegisteredModels.isValidatedForCurrentEnvironment(entry)) {
                return Optional.empty();
            }
            if (entry.filePath.isPresent()) {
                try {
                    Path path = Paths.get(entry.filePath.get());
                    if (path.isAbsolute() && !Files.isSymbolicLink(path)
                            && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        return Optional.of(Resolved.file(path.toAbsolutePath().normalize().toString()));
                    }
                } catch (Exception ignored) {
                }
            }
            // Discovered user models are validated by absolute file path. A
            // vanished file must never be reinterpreted as a built-in name.
            return Optional.empty();
        }
        try {
            Path resolved = catalog.resolve(entry);
            if (resolved == null) {
                return Optional.empty();
            }
            if (Files.isSymbolicLink(resolved)
                    || !Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
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

    private static boolean isSafeRegisteredName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return false;
        }
        String trimmed = name.trim();
        if (trimmed.indexOf('/') >= 0 || trimmed.indexOf('\\') >= 0) {
            return false;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.isISOControl(trimmed.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
