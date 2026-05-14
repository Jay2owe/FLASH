package flash.pipeline.ui.variations;

import flash.pipeline.image.FilterMacroEditorModel;
import flash.pipeline.ui.config.CellposeParameterStage;
import flash.pipeline.ui.config.ClassicalSegmentationStage;
import flash.pipeline.ui.config.ConfigQcContext;
import flash.pipeline.ui.config.FilterParameterStage;
import flash.pipeline.ui.config.StarDistParameterStage;

import ij.ImagePlus;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

public final class FilterVariationEngineContext {

    private final FilterMacroEditorModel.MacroDefinition baseMacro;
    private final ImagePlus sourceImage;
    private final CropSpec initialCropSpec;
    private final String channelName;
    private final ConfigQcContext configContext;
    private final FilterParameterStage.PreviewAdapter previewAdapter;
    private final ClassicalSegmentationStage.PreviewAdapter classicalPreviewAdapter;
    private final StarDistParameterStage.PreviewAdapter starDistPreviewAdapter;
    private final CellposeParameterStage.PreviewAdapter cellposePreviewAdapter;
    private final String sourceImageHash;
    private final String cacheNamespace;

    public FilterVariationEngineContext(FilterMacroEditorModel.MacroDefinition baseMacro,
                                        ImagePlus sourceImage,
                                        CropSpec initialCropSpec,
                                        String channelName,
                                        ConfigQcContext configContext,
                                        FilterParameterStage.PreviewAdapter previewAdapter) {
        this(baseMacro, sourceImage, initialCropSpec, channelName, configContext,
                previewAdapter, null, null, null);
    }

    public FilterVariationEngineContext(FilterMacroEditorModel.MacroDefinition baseMacro,
                                        ImagePlus sourceImage,
                                        CropSpec initialCropSpec,
                                        String channelName,
                                        ConfigQcContext configContext,
                                        FilterParameterStage.PreviewAdapter previewAdapter,
                                        ClassicalSegmentationStage.PreviewAdapter classicalPreviewAdapter,
                                        StarDistParameterStage.PreviewAdapter starDistPreviewAdapter,
                                        CellposeParameterStage.PreviewAdapter cellposePreviewAdapter) {
        if (baseMacro == null) {
            throw new IllegalArgumentException("baseMacro must not be null");
        }
        if (sourceImage == null) {
            throw new IllegalArgumentException("sourceImage must not be null");
        }
        if (configContext == null) {
            throw new IllegalArgumentException("configContext must not be null");
        }
        if (previewAdapter == null) {
            throw new IllegalArgumentException("previewAdapter must not be null");
        }
        this.baseMacro = baseMacro;
        this.sourceImage = sourceImage;
        this.initialCropSpec = initialCropSpec == null ? CropSpec.full() : initialCropSpec;
        this.channelName = channelName == null ? "" : channelName;
        this.configContext = configContext;
        this.previewAdapter = previewAdapter;
        this.classicalPreviewAdapter = classicalPreviewAdapter == null
                ? adapterFromAttribute(configContext,
                "classicalPreviewAdapter",
                ClassicalSegmentationStage.PreviewAdapter.class)
                : classicalPreviewAdapter;
        this.starDistPreviewAdapter = starDistPreviewAdapter == null
                ? adapterFromAttribute(configContext,
                "starDistPreviewAdapter",
                StarDistParameterStage.PreviewAdapter.class)
                : starDistPreviewAdapter;
        this.cellposePreviewAdapter = cellposePreviewAdapter == null
                ? adapterFromAttribute(configContext,
                "cellposePreviewAdapter",
                CellposeParameterStage.PreviewAdapter.class)
                : cellposePreviewAdapter;
        this.sourceImageHash = sourceImageHash(sourceImage);
        this.cacheNamespace = "filter:" + sha256(baseMacro.render());
    }

    public FilterMacroEditorModel.MacroDefinition baseMacro() {
        return baseMacro;
    }

    public FilterMacroEditorModel.MacroDefinition getBaseMacro() {
        return baseMacro;
    }

    public ImagePlus sourceImage() {
        return sourceImage;
    }

    public ImagePlus getSourceImage() {
        return sourceImage;
    }

    public CropSpec initialCropSpec() {
        return initialCropSpec;
    }

    public CropSpec getInitialCropSpec() {
        return initialCropSpec;
    }

    public String channelName() {
        return channelName;
    }

    public String getChannelName() {
        return channelName;
    }

    public ConfigQcContext configContext() {
        return configContext;
    }

    public ConfigQcContext getConfigContext() {
        return configContext;
    }

    public File binFolder() {
        return configContext.getBinFolder();
    }

    public File getBinFolder() {
        return binFolder();
    }

    public FilterParameterStage.PreviewAdapter previewAdapter() {
        return previewAdapter;
    }

    public FilterParameterStage.PreviewAdapter getPreviewAdapter() {
        return previewAdapter;
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

    public String sourceImageHash() {
        return sourceImageHash;
    }

    public String getSourceImageHash() {
        return sourceImageHash;
    }

    public String cacheNamespace() {
        return cacheNamespace;
    }

    public String getCacheNamespace() {
        return cacheNamespace;
    }

    public static String sourceImageHash(ImagePlus image) {
        if (image == null) {
            return "";
        }
        String raw = safe(image.getTitle()) + ":"
                + image.getWidth() + "x"
                + image.getHeight() + "x"
                + image.getStackSize();
        return sha256(raw);
    }

    private static String sha256(String value) {
        String raw = value == null ? "" : value;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (int i = 0; i < bytes.length; i++) {
                out.append(String.format(Locale.ROOT, "%02x",
                        Integer.valueOf(bytes[i] & 0xff)));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static <T> T adapterFromAttribute(ConfigQcContext context,
                                              String key,
                                              Class<T> type) {
        if (context == null || key == null || type == null) {
            return null;
        }
        Object value = context.getAttribute(key);
        return type.isInstance(value) ? type.cast(value) : null;
    }
}
