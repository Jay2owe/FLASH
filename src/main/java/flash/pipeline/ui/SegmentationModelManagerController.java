package flash.pipeline.ui;

import flash.pipeline.segmentation.catalog.ModelCatalog;
import flash.pipeline.segmentation.catalog.ModelCatalogIO;
import flash.pipeline.segmentation.catalog.ModelEntry;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
        List<ModelEntry> out = new ArrayList<ModelEntry>();
        for (ModelEntry entry : catalog.all()) {
            if (engineFilter != null && entry.engine != engineFilter) {
                continue;
            }
            if (sourceFilter != null && entry.source != sourceFilter) {
                continue;
            }
            out.add(entry);
        }
        return Collections.unmodifiableList(out);
    }

    public boolean canEdit(ModelEntry entry) {
        return ModelCatalogIO.isProjectWritableEntry(entry);
    }

    public boolean canDelete(ModelEntry entry) {
        return ModelCatalogIO.isProjectWritableEntry(entry);
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
                defaults == null ? existing.defaults : defaults,
                metadata, existing.supportsSecondChannel);

        replaceOrAdd(edited);
        persist();
        return edited;
    }

    public synchronized void delete(String modelKey) throws IOException {
        ModelEntry existing = requireEntry(modelKey);
        if (!canDelete(existing)) {
            throw new IOException("Stock model entries are read-only.");
        }
        catalog.remove(modelKey);
        persist();
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
}
