package flash.pipeline.analyses;

import flash.pipeline.bin.BinField;
import flash.pipeline.cli.CLIConfig;
import flash.pipeline.io.ImageCache;
import flash.pipeline.representative.RepresentativeFigureConfig;
import flash.pipeline.report.QualityReport;
import flash.pipeline.runrecord.LoadedRunParameters;
import ij.IJ;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Scaffold analysis for building a representative image figure.
 */
public class RepresentativeFigureAnalysis implements Analysis {
    private final RepresentativeFigureConfig config = new RepresentativeFigureConfig();

    private boolean headless = true;
    private boolean verboseLogging = false;
    private boolean skipExisting = false;
    private int parallelThreads = 1;
    private ImageCache imageCache = null;
    private int loaderThreads = 1;
    private int loaderPercent = 50;
    private boolean useTifCache = false;
    private QualityReport qualityReport = null;
    private boolean suppressDialogs = false;
    private CLIConfig cliConfig = null;

    @Override
    public void execute(String directory) {
        IJ.log("[Representative Figure] Make Representative Image Figure is not yet implemented.");
    }

    @Override
    public Set<BinField> requiredBinFields() {
        return EnumSet.noneOf(BinField.class);
    }

    @Override
    public boolean benefitsFromRois() {
        return false;
    }

    @Override
    public boolean requiresHeadedMode() {
        return true;
    }

    @Override
    public void setHeadless(boolean headless) {
        this.headless = headless;
    }

    @Override
    public void setVerboseLogging(boolean verbose) {
        this.verboseLogging = verbose;
    }

    @Override
    public void setSkipExisting(boolean skip) {
        this.skipExisting = skip;
    }

    @Override
    public void setParallelThreads(int threads) {
        this.parallelThreads = Math.max(1, threads);
    }

    @Override
    public void setImageCache(ImageCache cache) {
        this.imageCache = cache;
    }

    @Override
    public void setLoaderThreads(int threads) {
        this.loaderThreads = Math.max(1, threads);
    }

    @Override
    public void setLoaderPercent(int percent) {
        this.loaderPercent = Math.max(0, Math.min(percent, 100));
    }

    @Override
    public void setUseTifCache(boolean use) {
        this.useTifCache = use;
    }

    @Override
    public void setQualityReport(QualityReport report) {
        this.qualityReport = report;
    }

    @Override
    public void setSuppressDialogs(boolean suppress) {
        this.suppressDialogs = suppress;
    }

    @Override
    public void setCliConfig(CLIConfig config) {
        this.cliConfig = config;
    }

    public LoadedRunParameters.Result applyLoadedParameters(Map<String, Object> parameters) {
        // TODO(representative-image-figure stage 10): restore persisted figure parameters into config.
        LoadedRunParameters.Result result = LoadedRunParameters.resultForKnownKeys(
                parameters, Collections.<String>emptySet());
        LoadedRunParameters.rememberLastResult(result);
        return result;
    }

    RepresentativeFigureConfig configForTests() {
        return config;
    }
}
