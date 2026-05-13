package flash.pipeline;

import flash.pipeline.analyses.Analysis;
import flash.pipeline.report.QualityReport;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Verifies that {@link FLASH_Pipeline#configureAnalysis} applies the expected
 * run options, especially that CLI paths always set suppressDialogs=true.
 */
public class FLASH_PipelineAnalysisConfigurationTest {

    /** Spy that records every setter call. */
    static class SpyAnalysis implements Analysis {
        boolean headless;
        boolean requiresHeadedMode;
        boolean aggressiveMemory;
        boolean verboseLogging;
        boolean skipExisting;
        int parallelThreads;
        int loaderThreads;
        int loaderPercent;
        boolean useTifCache;
        boolean suppressDialogs;
        QualityReport qualityReport;
        flash.pipeline.io.ImageCache imageCache;

        @Override public void execute(String directory) {}
        @Override public boolean requiresHeadedMode() { return requiresHeadedMode; }
        @Override public void setHeadless(boolean h) { headless = h; }
        @Override public void setAggressiveMemory(boolean a) { aggressiveMemory = a; }
        @Override public void setVerboseLogging(boolean v) { verboseLogging = v; }
        @Override public void setSkipExisting(boolean s) { skipExisting = s; }
        @Override public void setParallelThreads(int t) { parallelThreads = t; }
        @Override public void setLoaderThreads(int t) { loaderThreads = t; }
        @Override public void setLoaderPercent(int p) { loaderPercent = p; }
        @Override public void setUseTifCache(boolean u) { useTifCache = u; }
        @Override public void setSuppressDialogs(boolean s) { suppressDialogs = s; }
        @Override public void setQualityReport(QualityReport r) { qualityReport = r; }
        @Override public void setImageCache(flash.pipeline.io.ImageCache c) { imageCache = c; }
    }

    @Test
    public void configureAnalysis_setsSuppressDialogsTrue() {
        FLASH_Pipeline pipeline = new FLASH_Pipeline();
        SpyAnalysis spy = new SpyAnalysis();
        QualityReport qr = new QualityReport();

        pipeline.configureAnalysis(spy, 8, true, qr);

        assertTrue("CLI path must set suppressDialogs=true", spy.suppressDialogs);
        assertSame(qr, spy.qualityReport);
    }

    @Test
    public void configureAnalysis_setsSuppressDialogsFalseForSingleGuiRun() {
        FLASH_Pipeline pipeline = new FLASH_Pipeline();
        SpyAnalysis spy = new SpyAnalysis();

        pipeline.configureAnalysis(spy, FLASH_Pipeline.IDX_AGGREGATION, false, new QualityReport());

        assertFalse("Single GUI run should not suppress dialogs", spy.suppressDialogs);
    }

    @Test
    public void configureAnalysis_setsImageCacheForSplitMerge3DAndIntensity() {
        FLASH_Pipeline pipeline = new FLASH_Pipeline();

        int[] cacheable = {
                FLASH_Pipeline.IDX_SPLIT_MERGE,
                FLASH_Pipeline.IDX_3D_OBJECT,
                FLASH_Pipeline.IDX_INTENSITY,
                FLASH_Pipeline.IDX_SPECTRAL_DECONTAMINATION
        };
        int[] nonCacheable = {
                FLASH_Pipeline.IDX_CREATE_BIN,
                FLASH_Pipeline.IDX_DRAW_ROIS,
                FLASH_Pipeline.IDX_DECONVOLUTION,
                FLASH_Pipeline.IDX_SPATIAL,
                FLASH_Pipeline.IDX_LINE_DISTANCE,
                FLASH_Pipeline.IDX_AGGREGATION,
                FLASH_Pipeline.IDX_STATISTICS,
                FLASH_Pipeline.IDX_EXCEL_EXPORT
        };

        for (int idx : cacheable) {
            SpyAnalysis spy = new SpyAnalysis();
            pipeline.configureAnalysis(spy, idx, false, new QualityReport());
            assertNotNull("Index " + idx + " should receive imageCache", spy.imageCache);
        }

        for (int idx : nonCacheable) {
            SpyAnalysis spy = new SpyAnalysis();
            pipeline.configureAnalysis(spy, idx, false, new QualityReport());
            assertNull("Index " + idx + " should NOT receive imageCache", spy.imageCache);
        }
    }

    @Test
    public void configureAnalysis_propagatesHeadlessMode() {
        FLASH_Pipeline pipeline = new FLASH_Pipeline();
        SpyAnalysis spy = new SpyAnalysis();

        // Default FLASH_Pipeline has headlessMode=true
        pipeline.configureAnalysis(spy, FLASH_Pipeline.IDX_3D_OBJECT, true, new QualityReport());

        assertTrue("headless flag should propagate", spy.headless);
    }

    @Test
    public void configureAnalysis_overridesHeadlessForHeadedOnlyAnalysis() {
        FLASH_Pipeline pipeline = new FLASH_Pipeline();
        SpyAnalysis spy = new SpyAnalysis();
        spy.requiresHeadedMode = true;

        // Default FLASH_Pipeline has headlessMode=true.
        pipeline.configureAnalysis(spy, FLASH_Pipeline.IDX_DRAW_ROIS, true, new QualityReport());

        assertFalse("Headed-only analyses must run with image windows enabled",
                spy.headless);
        assertTrue(spy.suppressDialogs);
    }

    @Test
    public void configureAnalysis_keepsHeadedOnlyAnalysisHeadlessInCli() {
        FLASH_Pipeline pipeline = new FLASH_Pipeline();
        SpyAnalysis spy = new SpyAnalysis();
        spy.requiresHeadedMode = true;
        pipeline.setCliInvocation(true);

        pipeline.configureAnalysis(spy, FLASH_Pipeline.IDX_DRAW_ROIS, true, new QualityReport());

        assertTrue("CLI should not open headed-only interactive analyses",
                spy.headless);
        assertTrue(spy.suppressDialogs);
    }

    @Test
    public void configureAnalysis_allowsSpectralDialogForSingleGuiRun() {
        FLASH_Pipeline pipeline = new FLASH_Pipeline();
        SpyAnalysis spy = new SpyAnalysis();

        pipeline.configureAnalysis(spy, FLASH_Pipeline.IDX_SPECTRAL_DECONTAMINATION, false, new QualityReport());

        assertFalse("Single GUI Spectral Decontamination run should open setup dialogs", spy.headless);
        assertFalse(spy.suppressDialogs);
    }

    @Test
    public void configureAnalysis_allowsSpectralDialogWhenDialogsSuppressedInGui() {
        FLASH_Pipeline pipeline = new FLASH_Pipeline();
        SpyAnalysis spy = new SpyAnalysis();

        pipeline.configureAnalysis(spy, FLASH_Pipeline.IDX_SPECTRAL_DECONTAMINATION, true, new QualityReport());

        assertFalse("GUI Spectral Decontamination should still open setup dialogs when queued with other analyses",
                spy.headless);
        assertTrue(spy.suppressDialogs);
    }

    @Test
    public void configureAnalysis_keepsSpectralHeadlessInCli() {
        FLASH_Pipeline pipeline = new FLASH_Pipeline();
        SpyAnalysis spy = new SpyAnalysis();
        pipeline.setCliInvocation(true);

        pipeline.configureAnalysis(spy, FLASH_Pipeline.IDX_SPECTRAL_DECONTAMINATION, true, new QualityReport());

        assertTrue("CLI Spectral Decontamination should stay headless", spy.headless);
        assertTrue(spy.suppressDialogs);
    }

    @Test
    public void guiExecutionCatchesLinkageErrorSoRepeatPromptCanContinue() {
        FLASH_Pipeline pipeline = new FLASH_Pipeline();
        Analysis failing = new Analysis() {
            @Override public void execute(String directory) {
                throw new NoClassDefFoundError("flash/pipeline/intensity/spatial/PeriodicityAnalysis$Plane");
            }
        };

        assertFalse(pipeline.executeAnalysisSafelyForGui(
                failing, FLASH_Pipeline.IDX_INTENSITY, "C:/fake/project"));
    }

    @Test(expected = ThreadDeath.class)
    public void guiExecutionRethrowsThreadDeath() {
        FLASH_Pipeline pipeline = new FLASH_Pipeline();
        Analysis failing = new Analysis() {
            @Override public void execute(String directory) {
                throw new ThreadDeath();
            }
        };

        pipeline.executeAnalysisSafelyForGui(
                failing, FLASH_Pipeline.IDX_INTENSITY, "C:/fake/project");
    }
}
