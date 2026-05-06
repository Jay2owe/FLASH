package flash.pipeline.analyses;

import flash.pipeline.bin.BinField;

import java.util.EnumSet;
import java.util.Set;

/**
 * One analysis step from the IHF Pipeline.
 */
public interface Analysis {
    void execute(String directory);

    /** Bin fields this analysis needs before it can run. Empty means no bin requirements. */
    default Set<BinField> requiredBinFields() {
        return EnumSet.noneOf(BinField.class);
    }

    /** True when this analysis can use user-drawn regions of interest. */
    default boolean benefitsFromRois() {
        return false;
    }

    /** True when this analysis needs visible ImageJ windows/user input to run. */
    default boolean requiresHeadedMode() {
        return false;
    }

    /** When true, batch modules should avoid displaying image windows. */
    default void setHeadless(boolean headless) {}

    /** When true, aggressively free memory after each image. */
    default void setAggressiveMemory(boolean aggressive) {}

    /** When true, print detailed step-by-step debug logging. */
    default void setVerboseLogging(boolean verbose) {}

    /** When true, skip images whose primary output files already exist. */
    default void setSkipExisting(boolean skip) {}

    /** Set the number of parallel threads for image processing (1 = sequential). */
    default void setParallelThreads(int threads) {}

    /** Provide a shared image cache for reuse across analyses within the same session. */
    default void setImageCache(flash.pipeline.io.ImageCache cache) {}

    /** Set the number of producer threads for loading images in parallel (1 = sequential loading). */
    default void setLoaderThreads(int threads) {}

    /** Set the percentage of total threads dedicated to image loading (0-100). */
    default void setLoaderPercent(int percent) {}

    /** When true, save raw images as TIFs on first load and reuse them for subsequent analyses. */
    default void setUseTifCache(boolean use) {}

    /** Provide a shared QualityReport for QC data collection. */
    default void setQualityReport(flash.pipeline.report.QualityReport report) {}

    /** When true, suppress completion dialogs (used when multiple analyses are queued). */
    default void setSuppressDialogs(boolean suppress) {}

    /** Provides parsed CLI state for headless analyses that expose custom flags. */
    default void setCliConfig(flash.pipeline.cli.CLIConfig config) {}
}
