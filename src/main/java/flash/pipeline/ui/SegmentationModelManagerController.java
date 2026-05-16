package flash.pipeline.ui;

import flash.pipeline.segmentation.catalog.ModelCatalog;
import flash.pipeline.segmentation.catalog.ModelCatalogIO;
import flash.pipeline.segmentation.catalog.ModelEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.LinkedHashSet;

/**
 * Testable controller for the segmentation model manager dialog.
 */
public final class SegmentationModelManagerController {
    private final Path projectRoot;
    private final SegmentationModelImportService importService;
    private ModelCatalog catalog;

    public SegmentationModelManagerController(Path projectRoot) {
        this(projectRoot, ModelCatalogIO.read(projectRoot));
    }

    public SegmentationModelManagerController(Path projectRoot, ModelCatalog catalog) {
        if (projectRoot == null) {
            throw new IllegalArgumentException("Project root must not be null.");
        }
        this.projectRoot = projectRoot;
        this.catalog = catalog == null ? ModelCatalogIO.read(projectRoot) : catalog;
        this.importService = new SegmentationModelImportService(projectRoot);
    }

    public synchronized ModelCatalog catalog() {
        return catalog;
    }

    public synchronized Optional<ModelEntry> get(String modelKey) {
        return catalog.get(modelKey);
    }

    public synchronized List<ModelEntry> list(ModelEntry.Engine engineFilter,
                                              ModelEntry.Source sourceFilter) {
        return list(engineFilter, sourceFilter, null);
    }

    public synchronized List<ModelEntry> list(ModelEntry.Engine engineFilter,
                                              ModelEntry.Source sourceFilter,
                                              String tagFilter) {
        String tag = clean(tagFilter);
        List<ModelEntry> out = new ArrayList<ModelEntry>();
        for (ModelEntry entry : catalog.all()) {
            if (engineFilter != null && entry.engine != engineFilter) {
                continue;
            }
            if (sourceFilter != null && entry.source != sourceFilter) {
                continue;
            }
            if (tag != null && !entry.tags.contains(tag)) {
                continue;
            }
            out.add(entry);
        }
        return Collections.unmodifiableList(out);
    }

    public synchronized List<String> allTags(ModelEntry.Engine engineFilter,
                                             ModelEntry.Source sourceFilter) {
        TreeSet<String> tags = new TreeSet<String>();
        for (ModelEntry entry : catalog.all()) {
            if (engineFilter != null && entry.engine != engineFilter) {
                continue;
            }
            if (sourceFilter != null && entry.source != sourceFilter) {
                continue;
            }
            tags.addAll(entry.tags);
        }
        return Collections.unmodifiableList(new ArrayList<String>(tags));
    }

    public boolean canEdit(ModelEntry entry) {
        return ModelCatalogIO.isProjectWritableEntry(entry);
    }

    public boolean canDelete(ModelEntry entry) {
        return ModelCatalogIO.isProjectWritableEntry(entry);
    }

    public boolean canDuplicate(ModelEntry entry) {
        return entry != null
                && entry.isStock()
                && !ModelCatalogIO.isProjectWritableEntry(entry)
                && !ModelCatalogIO.isDiscoveredEntry(entry);
    }

    public synchronized ModelEntry addStarDistModel(Path sourceFile,
                                                    String displayName,
                                                    String description,
                                                    Map<String, Object> defaults)
            throws IOException {
        Path file = importService.validateStarDistZip(sourceFile);
        String name = normalizedName(displayName, baseName(file));
        String key = importService.uniqueModelKey(catalog, ModelEntry.Engine.STARDIST, name);
        ModelEntry entry = new ModelEntry(key, name, clean(description),
                ModelEntry.Engine.STARDIST, ModelEntry.Source.USER_IMPORTED,
                null, null, null, null, null,
                defaultsOr(defaults, defaultStarDistDefaults()),
                importMetadata("stardist_file"), false);

        ModelEntry saved = catalog.add(entry, file);
        persist();
        return saved;
    }

    public synchronized ModelEntry addCellposeFileModel(Path sourceFile,
                                                        String displayName,
                                                        String description,
                                                        Map<String, Object> defaults)
            throws IOException {
        Path file = importService.validateCellposeModelFile(sourceFile);
        String name = normalizedName(displayName, baseName(file));
        String key = importService.uniqueModelKey(catalog, ModelEntry.Engine.CELLPOSE, name);
        ModelEntry entry = new ModelEntry(key, name, clean(description),
                ModelEntry.Engine.CELLPOSE, ModelEntry.Source.USER_IMPORTED,
                null, null, null, null, null,
                defaultsOr(defaults, defaultCellposeDefaults()),
                importMetadata("cellpose_file"), true);

        ModelEntry saved = catalog.add(entry, file);
        persist();
        return saved;
    }

    public synchronized ModelEntry addCellposeRegisteredName(String registeredName,
                                                             String displayName,
                                                             String description,
                                                             Map<String, Object> defaults)
            throws IOException {
        String pretrainedModel = importService.validateCellposeRegisteredName(registeredName);
        String name = normalizedName(displayName, "Cellpose - " + pretrainedModel);
        String key = importService.uniqueModelKey(catalog, ModelEntry.Engine.CELLPOSE, name);
        Map<String, Object> metadata = importMetadata("cellpose_registered");
        metadata.put(ModelCatalogIO.PROJECT_REGISTERED_METADATA_KEY, Boolean.TRUE);
        ModelEntry entry = new ModelEntry(key, name, clean(description),
                ModelEntry.Engine.CELLPOSE, ModelEntry.Source.STOCK_BUILTIN,
                null, null, pretrainedModel, null, null,
                defaultsOr(defaults, defaultCellposeDefaults()),
                metadata, true);

        replaceOrAdd(entry);
        persist();
        return entry;
    }

    public synchronized ModelEntry edit(String modelKey,
                                        String displayName,
                                        String description,
                                        Map<String, Object> defaults)
            throws IOException {
        return edit(modelKey, displayName, description, defaults, null);
    }

    public synchronized ModelEntry edit(String modelKey,
                                        String displayName,
                                        String description,
                                        Map<String, Object> defaults,
                                        Set<String> tags)
            throws IOException {
        ModelEntry existing = requireEntry(modelKey);
        if (!canEdit(existing)) {
            throw new IOException("Stock model entries are read-only.");
        }
        String name = normalizedName(displayName, existing.name);
        Map<String, Object> metadata = mutableCopy(existing.metadata);
        metadata.put("updatedAt", Instant.now().toString());
        ModelEntry edited = new ModelEntry(existing.modelKey, name, clean(description),
                existing.engine, existing.source, value(existing.filePath),
                value(existing.resourcePath), value(existing.pretrainedModel),
                value(existing.fijiModelChoice), value(existing.base),
                tags == null ? existing.tags : normalizeTags(tags),
                defaults == null ? existing.defaults : defaults,
                metadata, existing.supportsSecondChannel);

        replaceOrAdd(edited);
        persist();
        return edited;
    }

    public synchronized void delete(String modelKey) throws IOException {
        delete(modelKey, false);
    }

    public synchronized void delete(String modelKey, boolean removeFiles) throws IOException {
        ModelEntry existing = requireEntry(modelKey);
        if (!canDelete(existing)) {
            throw new IOException("Stock model entries are read-only.");
        }
        catalog.remove(modelKey, removeFiles);
        persist();
    }

    public synchronized ModelEntry duplicateAsUser(String modelKey) throws IOException {
        ModelEntry existing = requireEntry(modelKey);
        if (!canDuplicate(existing)) {
            throw new IOException("Only read-only stock model entries can be duplicated.");
        }

        String name = duplicateName(existing.name);
        String key = importService.uniqueModelKey(catalog, existing.engine, name);
        Map<String, Object> metadata = importMetadata("duplicated_stock");
        metadata.put("duplicatedFrom", existing.modelKey);

        ModelEntry copy;
        if (existing.source == ModelEntry.Source.STOCK_BUILTIN) {
            String pretrained = importService.validateCellposeRegisteredName(
                    value(existing.pretrainedModel));
            metadata.put(ModelCatalogIO.PROJECT_REGISTERED_METADATA_KEY, Boolean.TRUE);
            copy = new ModelEntry(key, name, existing.description,
                    existing.engine, ModelEntry.Source.STOCK_BUILTIN,
                    null, null, pretrained,
                    value(existing.fijiModelChoice), value(existing.base),
                    existing.tags, existing.defaults, metadata,
                    existing.supportsSecondChannel);
            replaceOrAdd(copy);
            persist();
            return get(key).orElse(copy);
        }

        Path source = catalog.resolve(existing);
        if (source == null || !Files.isRegularFile(source)) {
            throw new IOException("Stock model file is missing: " + existing.modelKey);
        }
        validateResolvedModelFile(existing, source);
        copy = new ModelEntry(key, name, existing.description,
                existing.engine, ModelEntry.Source.USER_IMPORTED,
                null, null, null,
                value(existing.fijiModelChoice), value(existing.base),
                existing.tags, existing.defaults, metadata,
                existing.supportsSecondChannel);
        ModelEntry saved = catalog.add(copy, source);
        persist();
        return get(saved.modelKey).orElse(saved);
    }

    public synchronized Map<String, ModelStatus> validateAll() {
        LinkedHashMap<String, ModelStatus> out = new LinkedHashMap<String, ModelStatus>();
        for (ModelEntry entry : catalog.all()) {
            out.put(entry.modelKey, status(entry));
        }
        return Collections.unmodifiableMap(out);
    }

    public synchronized ModelStatus status(String modelKey) {
        Optional<ModelEntry> entry = catalog.get(modelKey);
        return entry.isPresent()
                ? status(entry.get())
                : ModelStatus.missing("Model entry is not in the catalog.");
    }

    public synchronized ModelStatus status(ModelEntry entry) {
        if (entry == null) {
            return ModelStatus.invalid("Model entry is null.");
        }
        try {
            if (entry.source == ModelEntry.Source.STOCK_BUILTIN) {
                importService.validateCellposeRegisteredName(value(entry.pretrainedModel));
                return ModelStatus.ok();
            }

            Path resolved = catalog.resolve(entry);
            if (resolved == null) {
                return ModelStatus.missing("Model file is not registered.");
            }
            if (!Files.isRegularFile(resolved)) {
                return ModelStatus.missing("Model file is missing: " + resolved);
            }
            validateResolvedModelFile(entry, resolved);
            return ModelStatus.ok();
        } catch (IOException e) {
            return ModelStatus.invalid(e.getMessage());
        }
    }

    public synchronized void reload() {
        catalog = ModelCatalogIO.read(projectRoot);
    }

    public String cellposeFileWarning(Path sourceFile) {
        return importService.warningForCellposeModelFile(sourceFile);
    }

    private void persist() throws IOException {
        ModelCatalogIO.writeProject(projectRoot, catalog);
        reload();
    }

    private ModelEntry requireEntry(String modelKey) throws IOException {
        Optional<ModelEntry> existing = catalog.get(modelKey);
        if (!existing.isPresent()) {
            throw new IOException("Model entry not found: " + modelKey);
        }
        return existing.get();
    }

    private void replaceOrAdd(ModelEntry entry) {
        List<ModelEntry> entries = new ArrayList<ModelEntry>(catalog.all());
        boolean replaced = false;
        for (int i = 0; i < entries.size(); i++) {
            if (entry.modelKey.equals(entries.get(i).modelKey)) {
                entries.set(i, entry);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            entries.add(entry);
        }
        catalog = new ModelCatalog(projectRoot, entries);
    }

    private static String normalizedName(String requested, String fallback) throws IOException {
        String value = clean(requested);
        if (value == null || value.isEmpty()) {
            value = clean(fallback);
        }
        if (value == null || value.isEmpty()) {
            throw new IOException("Model name must not be empty.");
        }
        return value;
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String value(Optional<String> optional) {
        return optional != null && optional.isPresent() ? optional.get() : null;
    }

    private static String baseName(Path file) {
        if (file == null || file.getFileName() == null) {
            return "Model";
        }
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String duplicateName(String name) {
        String cleaned = clean(name);
        return (cleaned == null ? "Model" : cleaned) + " (copy)";
    }

    private void validateResolvedModelFile(ModelEntry entry, Path source) throws IOException {
        if (entry.engine == ModelEntry.Engine.STARDIST) {
            importService.validateResolvedStarDistZip(source);
        } else if (entry.engine == ModelEntry.Engine.CELLPOSE) {
            importService.validateResolvedCellposeModelFile(source);
        } else if (source == null || !Files.isRegularFile(source)) {
            throw new IOException("Model file does not exist: " + source);
        }
    }

    private static Map<String, Object> defaultsOr(Map<String, Object> requested,
                                                  Map<String, Object> fallback) {
        return requested == null || requested.isEmpty()
                ? fallback
                : new LinkedHashMap<String, Object>(requested);
    }

    private static Map<String, Object> defaultStarDistDefaults() {
        Map<String, Object> defaults = new LinkedHashMap<String, Object>();
        defaults.put("probThresh", Double.valueOf(0.5));
        defaults.put("nmsThresh", Double.valueOf(0.3));
        return defaults;
    }

    private static Map<String, Object> defaultCellposeDefaults() {
        Map<String, Object> defaults = new LinkedHashMap<String, Object>();
        defaults.put("diameter", Double.valueOf(30.0));
        defaults.put("flowThreshold", Double.valueOf(0.4));
        defaults.put("cellprobThreshold", Double.valueOf(0.0));
        return defaults;
    }

    private static Map<String, Object> importMetadata(String importKind) {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("importKind", importKind);
        metadata.put("importedAt", Instant.now().toString());
        metadata.put("qualityFlag", "USER_PROVIDED");
        return metadata;
    }

    private static Map<String, Object> mutableCopy(Map<String, Object> input) {
        return input == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(input);
    }

    static Set<String> normalizeTags(Set<String> requested) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        if (requested != null) {
            for (String tag : requested) {
                String cleaned = clean(tag);
                if (cleaned != null) {
                    out.add(cleaned);
                }
            }
        }
        return Collections.unmodifiableSet(out);
    }

    public static final class ModelStatus {
        public enum State {
            OK("OK"),
            MISSING("Missing"),
            INVALID("Invalid");

            private final String label;

            State(String label) {
                this.label = label;
            }

            public String label() {
                return label;
            }
        }

        private final State state;
        private final String message;

        private ModelStatus(State state, String message) {
            this.state = state;
            this.message = message == null ? "" : message;
        }

        static ModelStatus ok() {
            return new ModelStatus(State.OK, "");
        }

        static ModelStatus missing(String message) {
            return new ModelStatus(State.MISSING, message);
        }

        static ModelStatus invalid(String message) {
            return new ModelStatus(State.INVALID, message);
        }

        public State state() {
            return state;
        }

        public String label() {
            return state.label();
        }

        public String message() {
            return message;
        }

        @Override
        public String toString() {
            return message.isEmpty() ? label() : label() + ": " + message;
        }
    }
}
