package flash.pipeline.ui.variations;

import flash.pipeline.ui.config.CellposeParameterStage;
import flash.pipeline.ui.config.ClassicalSegmentationStage;
import flash.pipeline.ui.config.ConfigQcContext;
import flash.pipeline.ui.config.StarDistParameterStage;

import ij.ImagePlus;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import flash.pipeline.segmentation.catalog.ModelCatalog;
import flash.pipeline.segmentation.catalog.ModelCatalogIO;
import flash.pipeline.segmentation.catalog.ModelEntry;

public final class VariationEngineContext {

    private final ParameterSweep.Method method;
    private final String channelName;
    private final ImagePlus rawSource;
    private final ImagePlus filteredSource;
    private final ConfigQcContext configContext;
    private final File binFolder;
    private final Object baseParameters;
    private final String cacheNamespace;
    private final ClassicalSegmentationStage.PreviewAdapter classicalPreviewAdapter;
    private final StarDistParameterStage.PreviewAdapter starDistPreviewAdapter;
    private final CellposeParameterStage.PreviewAdapter cellposePreviewAdapter;
    private MontageDisplayActionDelegate montageDisplayActionDelegate;

    private VariationEngineContext(ParameterSweep.Method method,
                                   String channelName,
                                   ImagePlus rawSource,
                                   ImagePlus filteredSource,
                                   ConfigQcContext configContext,
                                   Object baseParameters,
                                   ClassicalSegmentationStage.PreviewAdapter classicalPreviewAdapter,
                                   StarDistParameterStage.PreviewAdapter starDistPreviewAdapter,
                                   CellposeParameterStage.PreviewAdapter cellposePreviewAdapter,
                                   MontageDisplayActionDelegate montageDisplayActionDelegate) {
        this.method = method;
        this.channelName = channelName == null ? "" : channelName;
        this.rawSource = rawSource;
        this.filteredSource = filteredSource;
        this.configContext = configContext;
        this.binFolder = configContext == null ? null : configContext.getBinFolder();
        this.baseParameters = baseParameters;
        this.cacheNamespace = modelCacheNamespace(baseParameters, configContext);
        this.classicalPreviewAdapter = classicalPreviewAdapter;
        this.starDistPreviewAdapter = starDistPreviewAdapter;
        this.cellposePreviewAdapter = cellposePreviewAdapter;
        this.montageDisplayActionDelegate = montageDisplayActionDelegate;
    }

    public static VariationEngineContext forClassical(String channelName,
                                                      ImagePlus rawSource,
                                                      ImagePlus filteredSource,
                                                      ConfigQcContext configContext,
                                                      ParameterCombo baseParameters,
                                                      ClassicalSegmentationStage.PreviewAdapter previewAdapter) {
        return forClassical(channelName, rawSource, filteredSource, configContext,
                baseParameters, previewAdapter, null);
    }

    public static VariationEngineContext forClassical(String channelName,
                                                      ImagePlus rawSource,
                                                      ImagePlus filteredSource,
                                                      ConfigQcContext configContext,
                                                      ParameterCombo baseParameters,
                                                      ClassicalSegmentationStage.PreviewAdapter previewAdapter,
                                                      MontageDisplayActionDelegate montageDisplayActionDelegate) {
        return new VariationEngineContext(ParameterSweep.Method.CLASSICAL, channelName,
                rawSource, filteredSource, configContext, baseParameters,
                previewAdapter, null, null, montageDisplayActionDelegate);
    }

    public static VariationEngineContext forStarDist(String channelName,
                                                     ImagePlus rawSource,
                                                     ImagePlus filteredSource,
                                                     ConfigQcContext configContext,
                                                     StarDistParameterStage.Parameters baseParameters,
                                                     StarDistParameterStage.PreviewAdapter previewAdapter) {
        return forStarDist(channelName, rawSource, filteredSource, configContext,
                baseParameters, previewAdapter, null);
    }

    public static VariationEngineContext forStarDist(String channelName,
                                                     ImagePlus rawSource,
                                                     ImagePlus filteredSource,
                                                     ConfigQcContext configContext,
                                                     StarDistParameterStage.Parameters baseParameters,
                                                     StarDistParameterStage.PreviewAdapter previewAdapter,
                                                     MontageDisplayActionDelegate montageDisplayActionDelegate) {
        return new VariationEngineContext(ParameterSweep.Method.STARDIST, channelName,
                rawSource, filteredSource, configContext, baseParameters,
                null, previewAdapter, null, montageDisplayActionDelegate);
    }

    public static VariationEngineContext forCellpose(String channelName,
                                                     ImagePlus rawSource,
                                                     ImagePlus filteredSource,
                                                     ConfigQcContext configContext,
                                                     CellposeParameterStage.Parameters baseParameters,
                                                     CellposeParameterStage.PreviewAdapter previewAdapter) {
        return forCellpose(channelName, rawSource, filteredSource, configContext,
                baseParameters, previewAdapter, null);
    }

    public static VariationEngineContext forCellpose(String channelName,
                                                     ImagePlus rawSource,
                                                     ImagePlus filteredSource,
                                                     ConfigQcContext configContext,
                                                     CellposeParameterStage.Parameters baseParameters,
                                                     CellposeParameterStage.PreviewAdapter previewAdapter,
                                                     MontageDisplayActionDelegate montageDisplayActionDelegate) {
        return new VariationEngineContext(ParameterSweep.Method.CELLPOSE, channelName,
                rawSource, filteredSource, configContext, baseParameters,
                null, null, previewAdapter, montageDisplayActionDelegate);
    }

    public ParameterSweep.Method method() {
        return method;
    }

    public ParameterSweep.Method getMethod() {
        return method;
    }

    public String channelName() {
        return channelName;
    }

    public String getChannelName() {
        return channelName;
    }

    public ImagePlus rawSource() {
        return rawSource;
    }

    public ImagePlus getRawSource() {
        return rawSource;
    }

    public ImagePlus filteredSource() {
        return filteredSource;
    }

    public ImagePlus getFilteredSource() {
        return filteredSource;
    }

    public ConfigQcContext configContext() {
        return configContext;
    }

    public ConfigQcContext getConfigContext() {
        return configContext;
    }

    public File binFolder() {
        return binFolder;
    }

    public File getBinFolder() {
        return binFolder;
    }

    public Object baseParameters() {
        return baseParameters;
    }

    public Object getBaseParameters() {
        return baseParameters;
    }

    /** Fixed settings omitted from the sweep axes must still identify cached outputs. */
    public String cacheNamespace() {
        return cacheNamespace;
    }

    private static String modelCacheNamespace(Object parameters, ConfigQcContext context) {
        if (!(parameters instanceof StarDistParameterStage.Parameters)) return "";
        String key = ((StarDistParameterStage.Parameters) parameters).modelKey;
        String prefix = "stardist:model=" + key;
        if (context == null || context.getProjectDirectory() == null) return prefix;
        Path root = context.getProjectDirectory().toPath();
        try {
            Path catalogFile = ModelCatalogIO.catalogDirectory(root)
                    .resolve(ModelCatalogIO.CATALOG_FILENAME);
            List<ModelEntry> entries = Files.isRegularFile(catalogFile)
                    ? ModelCatalogIO.readProjectCatalogFile(catalogFile)
                    : Collections.<ModelEntry>emptyList();
            // Avoid external model discovery in this UI path.
            for (ModelEntry entry : entries) {
                if (key.equals(entry.modelKey)) {
                    Path model = new ModelCatalog(root, entries).resolve(entry);
                    if (model == null || !Files.isRegularFile(model)) {
                        return prefix + ":unverified-session=" + UUID.randomUUID();
                    }
                    return prefix + ":sha256=" + modelDigest(model);
                }
            }
            for (ModelEntry entry : ModelCatalogIO.readStockResources()) {
                if (key.equals(entry.modelKey)) return prefix;
            }
        } catch (IOException | RuntimeException e) {
            ij.IJ.log("[FLASH] Model identity could not be verified for variation cache: "
                    + key + ". Existing cached results will not be reused.");
        }
        // An unavailable/custom identity must not retrieve another run's result.
        return prefix + ":unverified-session=" + UUID.randomUUID();
    }

    private static String modelDigest(Path model) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(model)) {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = input.read(buffer)) != -1) digest.update(buffer, 0, n);
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest.digest()) {
                hex.append(Character.forDigit((b >>> 4) & 15, 16));
                hex.append(Character.forDigit(b & 15, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public ClassicalSegmentationStage.PreviewAdapter classicalPreviewAdapter() {
        return classicalPreviewAdapter;
    }

    public StarDistParameterStage.PreviewAdapter starDistPreviewAdapter() {
        return starDistPreviewAdapter;
    }

    public CellposeParameterStage.PreviewAdapter cellposePreviewAdapter() {
        return cellposePreviewAdapter;
    }

    public MontageDisplayActionDelegate montageDisplayActionDelegate() {
        return montageDisplayActionDelegate;
    }

    public MontageDisplayActionDelegate getMontageDisplayActionDelegate() {
        return montageDisplayActionDelegate;
    }

    public void setMontageDisplayActionDelegate(
            MontageDisplayActionDelegate montageDisplayActionDelegate) {
        this.montageDisplayActionDelegate = montageDisplayActionDelegate;
    }
}
