package flash.pipeline.ui.variations;

import flash.pipeline.ui.config.CellposeParameterStage;
import flash.pipeline.ui.config.ClassicalSegmentationStage;
import flash.pipeline.ui.config.ConfigQcContext;
import flash.pipeline.ui.config.StarDistParameterStage;

import ij.ImagePlus;

import java.io.File;

public final class VariationEngineContext {

    private final ParameterSweep.Method method;
    private final String channelName;
    private final ImagePlus rawSource;
    private final ImagePlus filteredSource;
    private final ConfigQcContext configContext;
    private final File binFolder;
    private final Object baseParameters;
    private final ClassicalSegmentationStage.PreviewAdapter classicalPreviewAdapter;
    private final StarDistParameterStage.PreviewAdapter starDistPreviewAdapter;
    private final CellposeParameterStage.PreviewAdapter cellposePreviewAdapter;

    private VariationEngineContext(ParameterSweep.Method method,
                                   String channelName,
                                   ImagePlus rawSource,
                                   ImagePlus filteredSource,
                                   ConfigQcContext configContext,
                                   Object baseParameters,
                                   ClassicalSegmentationStage.PreviewAdapter classicalPreviewAdapter,
                                   StarDistParameterStage.PreviewAdapter starDistPreviewAdapter,
                                   CellposeParameterStage.PreviewAdapter cellposePreviewAdapter) {
        this.method = method;
        this.channelName = channelName == null ? "" : channelName;
        this.rawSource = rawSource;
        this.filteredSource = filteredSource;
        this.configContext = configContext;
        this.binFolder = configContext == null ? null : configContext.getBinFolder();
        this.baseParameters = baseParameters;
        this.classicalPreviewAdapter = classicalPreviewAdapter;
        this.starDistPreviewAdapter = starDistPreviewAdapter;
        this.cellposePreviewAdapter = cellposePreviewAdapter;
    }

    public static VariationEngineContext forClassical(String channelName,
                                                      ImagePlus rawSource,
                                                      ImagePlus filteredSource,
                                                      ConfigQcContext configContext,
                                                      ParameterCombo baseParameters,
                                                      ClassicalSegmentationStage.PreviewAdapter previewAdapter) {
        return new VariationEngineContext(ParameterSweep.Method.CLASSICAL, channelName,
                rawSource, filteredSource, configContext, baseParameters,
                previewAdapter, null, null);
    }

    public static VariationEngineContext forStarDist(String channelName,
                                                     ImagePlus rawSource,
                                                     ImagePlus filteredSource,
                                                     ConfigQcContext configContext,
                                                     StarDistParameterStage.Parameters baseParameters,
                                                     StarDistParameterStage.PreviewAdapter previewAdapter) {
        return new VariationEngineContext(ParameterSweep.Method.STARDIST, channelName,
                rawSource, filteredSource, configContext, baseParameters,
                null, previewAdapter, null);
    }

    public static VariationEngineContext forCellpose(String channelName,
                                                     ImagePlus rawSource,
                                                     ImagePlus filteredSource,
                                                     ConfigQcContext configContext,
                                                     CellposeParameterStage.Parameters baseParameters,
                                                     CellposeParameterStage.PreviewAdapter previewAdapter) {
        return new VariationEngineContext(ParameterSweep.Method.CELLPOSE, channelName,
                rawSource, filteredSource, configContext, baseParameters,
                null, null, previewAdapter);
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

    public ClassicalSegmentationStage.PreviewAdapter classicalPreviewAdapter() {
        return classicalPreviewAdapter;
    }

    public StarDistParameterStage.PreviewAdapter starDistPreviewAdapter() {
        return starDistPreviewAdapter;
    }

    public CellposeParameterStage.PreviewAdapter cellposePreviewAdapter() {
        return cellposePreviewAdapter;
    }
}
