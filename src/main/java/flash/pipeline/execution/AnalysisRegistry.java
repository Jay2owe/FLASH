package flash.pipeline.execution;

import flash.pipeline.FLASH_Pipeline;
import flash.pipeline.analyses.Analysis;
import flash.pipeline.analyses.CreateBinFileAnalysis;
import flash.pipeline.analyses.DeconvolutionAnalysis;
import flash.pipeline.analyses.DrawAndSaveROIsAnalysis;
import flash.pipeline.analyses.IntensityAnalysisV2;
import flash.pipeline.analyses.LineDistanceAnalysis;
import flash.pipeline.analyses.MasterAggregationAnalysis;
import flash.pipeline.analyses.SpatialAnalysis;
import flash.pipeline.analyses.SplitAndMergeImageChannelsAnalysis;
import flash.pipeline.analyses.StatisticalAnalysis;
import flash.pipeline.analyses.ThreeDObjectAnalysis;
import flash.pipeline.decontamination.SpectralDecontaminationAnalysis;
import flash.pipeline.runrecord.LoadedRunParameters;
import flash.pipeline.runrecord.RunRecord;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stable analysis-key registry used by run records and replay.
 *
 * <p>Keys are the persisted {@link RunRecord#analysis} values. They must not be
 * inferred only from reflection because some registered steps are intentionally
 * wrapped or hidden: Excel export is runtime-gated through
 * {@code FLASH_Pipeline.createExcelExportAnalysis()}, and Line Distance remains
 * CLI-addressable even though it is not in the visible main-dialog order.
 */
public enum AnalysisRegistry {
    CREATE_BIN("CreateBinFileAnalysis", FLASH_Pipeline.IDX_CREATE_BIN,
            "Set Up Configuration", "run_bin", CreateBinFileAnalysis.class, true,
            "Registered setup analysis."),
    DRAW_ROIS("DrawAndSaveROIsAnalysis", FLASH_Pipeline.IDX_DRAW_ROIS,
            "Draw and Save ROIs", "run_roi", DrawAndSaveROIsAnalysis.class, false,
            "Registered ROI analysis; no replay parameter adapter is available."),
    DECONVOLUTION("DeconvolutionAnalysis", FLASH_Pipeline.IDX_DECONVOLUTION,
            "3D Deconvolution", "run_deconv", DeconvolutionAnalysis.class, true,
            "Registered deconvolution analysis."),
    SPLIT_MERGE("SplitAndMergeImageChannelsAnalysis", FLASH_Pipeline.IDX_SPLIT_MERGE,
            "Make Presentation Images", "run_split", SplitAndMergeImageChannelsAnalysis.class, true,
            "Registered presentation-image analysis."),
    THREE_D_OBJECT("ThreeDObjectAnalysis", FLASH_Pipeline.IDX_3D_OBJECT,
            "3D Object Analysis", "run_3d", ThreeDObjectAnalysis.class, true,
            "Registered 3D object analysis."),
    SPATIAL("SpatialAnalysis", FLASH_Pipeline.IDX_SPATIAL,
            "Spatial Analysis", "run_spatial", SpatialAnalysis.class, true,
            "Registered spatial analysis."),
    LINE_DISTANCE("LineDistanceAnalysis", FLASH_Pipeline.IDX_LINE_DISTANCE,
            "Line Distance Analysis", "run_distance", LineDistanceAnalysis.class, true,
            "Registered and CLI-addressable; hidden from the visible main-dialog order."),
    INTENSITY("IntensityAnalysisV2", FLASH_Pipeline.IDX_INTENSITY,
            "Fluorescence Intensity Analysis", "run_intensity", IntensityAnalysisV2.class, true,
            "Registered intensity analysis."),
    AGGREGATION("MasterAggregationAnalysis", FLASH_Pipeline.IDX_AGGREGATION,
            "Combine results per condition / animal", "run_aggregate", MasterAggregationAnalysis.class, false,
            "Registered aggregation analysis; configured through CLI/config state rather than a load-from-run adapter."),
    STATISTICS("StatisticalAnalysis", FLASH_Pipeline.IDX_STATISTICS,
            "Statistical Analysis", "run_statistics", StatisticalAnalysis.class, false,
            "Registered statistics analysis; configured through CLI/config state rather than a load-from-run adapter."),
    EXCEL_EXPORT("ExcelSummaryExportAnalysis", FLASH_Pipeline.IDX_EXCEL_EXPORT,
            "Excel Summary Export", "run_excel", null, false,
            "Registered runtime-gated analysis: FLASH_Pipeline.createExcelExportAnalysis() loads the concrete class only when Apache POI is available."),
    SPECTRAL_DECONTAMINATION("SpectralDecontaminationAnalysis", FLASH_Pipeline.IDX_SPECTRAL_DECONTAMINATION,
            "Spectral Decontamination (Experimental)", "run_spectral_decontamination",
            SpectralDecontaminationAnalysis.class, false,
            "Registered experimental analysis; parameters are applied inside its wizard, not on the analysis wrapper.");

    private static final Map<String, AnalysisRegistry> BY_KEY = new LinkedHashMap<String, AnalysisRegistry>();
    private static final Map<Integer, AnalysisRegistry> BY_INDEX = new LinkedHashMap<Integer, AnalysisRegistry>();
    private static final List<String> DOCUMENTED_SPECIAL_ENTRIES;

    static {
        for (AnalysisRegistry entry : values()) {
            BY_KEY.put(entry.analysisKey, entry);
            BY_INDEX.put(Integer.valueOf(entry.analysisIndex), entry);
        }
        List<String> docs = new ArrayList<String>();
        docs.add("ExcelSummaryExportAnalysis is registered at IDX_EXCEL_EXPORT/run_excel but instantiated through FLASH_Pipeline.createExcelExportAnalysis() so optional Apache POI classes stay runtime-gated.");
        docs.add("LineDistanceAnalysis is registered at IDX_LINE_DISTANCE/run_distance but intentionally hidden from the visible main-dialog order.");
        docs.add("ImageOrientationSetupAnalysis was deleted before atom 08 and is intentionally not registered.");
        DOCUMENTED_SPECIAL_ENTRIES = Collections.unmodifiableList(docs);
    }

    private final String analysisKey;
    private final int analysisIndex;
    private final String label;
    private final String cliFlag;
    private final Class<? extends Analysis> concreteClass;
    private final boolean replayParameterAdapter;
    private final String note;

    AnalysisRegistry(String analysisKey,
                     int analysisIndex,
                     String label,
                     String cliFlag,
                     Class<? extends Analysis> concreteClass,
                     boolean replayParameterAdapter,
                     String note) {
        this.analysisKey = analysisKey;
        this.analysisIndex = analysisIndex;
        this.label = label;
        this.cliFlag = cliFlag;
        this.concreteClass = concreteClass;
        this.replayParameterAdapter = replayParameterAdapter;
        this.note = note;
    }

    public String analysisKey() {
        return analysisKey;
    }

    public int analysisIndex() {
        return analysisIndex;
    }

    public String label() {
        return label;
    }

    public String cliFlag() {
        return cliFlag;
    }

    public Class<? extends Analysis> concreteClass() {
        return concreteClass;
    }

    public boolean hasConcreteClass() {
        return concreteClass != null;
    }

    public boolean hasReplayParameterAdapter() {
        return replayParameterAdapter;
    }

    public String note() {
        return note;
    }

    public static AnalysisRegistry forKey(String key) {
        if (key == null) {
            return null;
        }
        return BY_KEY.get(key.trim());
    }

    public static AnalysisRegistry forIndex(int index) {
        return BY_INDEX.get(Integer.valueOf(index));
    }

    public static AnalysisRegistry forRecord(RunRecord record) {
        if (record == null) {
            return null;
        }
        AnalysisRegistry byKey = forKey(record.analysis);
        return byKey == null ? forIndex(record.analysisIndex) : byKey;
    }

    public static List<AnalysisRegistry> registeredAnalyses() {
        List<AnalysisRegistry> out = new ArrayList<AnalysisRegistry>();
        out.addAll(BY_INDEX.values());
        return Collections.unmodifiableList(out);
    }

    public static List<String> documentedSpecialEntries() {
        return DOCUMENTED_SPECIAL_ENTRIES;
    }

    public LoadedRunParameters.Result applyReplayParameters(Analysis analysis,
                                                            Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return LoadedRunParameters.Result.empty();
        }
        if (!replayParameterAdapter) {
            throw new IllegalStateException("No replay parameter adapter is registered for "
                    + analysisKey + ". " + note);
        }
        if (analysis == null) {
            throw new IllegalArgumentException("analysis must not be null");
        }
        try {
            Method method = analysis.getClass().getMethod("applyLoadedParameters", Map.class);
            Object result = method.invoke(analysis, parameters);
            if (result instanceof LoadedRunParameters.Result) {
                return (LoadedRunParameters.Result) result;
            }
            return LoadedRunParameters.Result.empty();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Replay parameter adapter missing on "
                    + analysis.getClass().getName(), e);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Replay parameter adapter is not accessible on "
                    + analysis.getClass().getName(), e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IllegalStateException("Replay parameter adapter failed for " + analysisKey, cause);
        }
    }
}
