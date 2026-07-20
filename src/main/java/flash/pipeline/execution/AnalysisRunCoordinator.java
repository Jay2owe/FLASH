package flash.pipeline.execution;

import flash.pipeline.analyses.Analysis;
import flash.pipeline.analyses.DeconvolutionAnalysis;
import flash.pipeline.audit.RunSettingsSnapshot;
import flash.pipeline.bin.BinField;
import flash.pipeline.bin.BinSetupDispatcher;
import flash.pipeline.bin.ChannelConfig;
import flash.pipeline.bin.ChannelConfigIO;
import flash.pipeline.cli.CLIConfig;
import flash.pipeline.deconv.ExpectedDeconvParams;
import flash.pipeline.deconv.routing.DeconvConfigBridge;
import flash.pipeline.deconv.routing.DeconvRouting;
import flash.pipeline.deconv.routing.DeconvRoutingGroup;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.runrecord.AnalysisRunContext;
import flash.pipeline.runrecord.AnalysisRunContextFactory;
import flash.pipeline.runrecord.ParameterSnapshot;
import flash.pipeline.runrecord.RunRecord;
import flash.pipeline.runrecord.RunRecordAware;
import flash.pipeline.runrecord.RunRecordIO;
import flash.pipeline.project.ProjectFile;
import ij.IJ;

import org.scijava.plugin.Plugin;
import org.scijava.service.AbstractService;
import org.scijava.service.Service;

import javax.swing.JOptionPane;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Single integration point that wraps every analysis execution in an
 * {@link AnalysisRunContext}, so every entry path (GUI loop, CLI batch,
 * auto-aggregation, and the phase-04 Command shims) produces a run record from
 * one place.
 *
 * <p>Implemented as a SciJava service so phase-04 commands can inject it with
 * {@code @Parameter}; it can also be constructed directly for GUI/CLI use and
 * tests because {@link #run} does not depend on injected fields.
 */
@Plugin(type = Service.class)
public class AnalysisRunCoordinator extends AbstractService {

    /** Source of the most recent bin-setup outcome; overridable for tests. */
    interface BinOutcomeProvider {
        BinSetupDispatcher.Outcome lastOutcome();

        String lastReason();
    }

    private BinOutcomeProvider binOutcomeProvider = new BinOutcomeProvider() {
        @Override
        public BinSetupDispatcher.Outcome lastOutcome() {
            return BinSetupDispatcher.getLastOutcome();
        }

        @Override
        public String lastReason() {
            return BinSetupDispatcher.getLastOutcomeReason();
        }
    };

    /** Verifies that the mandatory terminal run-record snapshot is readable. */
    interface RecordPublicationVerifier {
        void verify(String expectedRunId, File recordFile) throws Exception;
    }

    /** Receives the one terminal result emitted for an invocation. */
    interface TerminalResultEmitter {
        void emit(RunResult result);
    }

    private RecordPublicationVerifier recordPublicationVerifier =
            new RecordPublicationVerifier() {
                @Override
                public void verify(String expectedRunId, File recordFile) {
                    verifyPublishedRecord(expectedRunId, recordFile);
                }
            };

    private TerminalResultEmitter terminalResultEmitter = new TerminalResultEmitter() {
        @Override
        public void emit(RunResult result) {
            // Default callers receive the same result directly (or the original failure below).
        }
    };

    private boolean writeLegacyAudit = true;

    /** Enumerates the project's series for the deconv preflight; overridable for tests. */
    interface DeconvSeriesEnumerator {
        List<DeconvPreflight.SeriesRef> list(String directory) throws Exception;
    }

    /** Runs the persisted-config deconv batch for the preflight; overridable for tests. */
    interface DeconvBatchRunner {
        boolean run(String directory) throws Exception;
    }

    /** The user's answer to the headed 3-option preflight offer. */
    enum PreflightChoice { DECONVOLVE, USE_RAW, CANCEL }

    /** Shows the headed 3-option preflight offer; overridable for tests. */
    interface DeconvPreflightPrompter {
        PreflightChoice ask(String analysisLabel, List<DeconvPreflight.MissingMirror> missing);
    }

    private DeconvSeriesEnumerator deconvSeriesEnumerator = new DeconvSeriesEnumerator() {
        @Override
        public List<DeconvPreflight.SeriesRef> list(String directory) throws Exception {
            return new DeconvolutionAnalysis().listDeconvSeriesRefs(directory);
        }
    };

    private DeconvBatchRunner deconvBatchRunner = new DeconvBatchRunner() {
        @Override
        public boolean run(String directory) throws Exception {
            return new DeconvolutionAnalysis().deconvolveFromPersistedConfig(directory);
        }
    };

    private DeconvPreflightPrompter deconvPreflightPrompter = new DeconvPreflightPrompter() {
        @Override
        public PreflightChoice ask(String analysisLabel, List<DeconvPreflight.MissingMirror> missing) {
            return promptDeconvPreflightDialog(analysisLabel, missing);
        }
    };

    /**
     * Run {@code body} (typically {@code analysis.execute(directory)}) inside a
     * run-record context. Exactly one mutually exclusive terminal result is
     * emitted. Ordinary failures are still rethrown after that result is
     * emitted so existing GUI/CLI handling remains intact until their caller
     * migration.
     */
    public RunResult run(Analysis analysis,
                         int analysisIndex,
                         String analysisLabel,
                         String directory,
                         CLIConfig cliConfig,
                         Map<String, Object> commandParameters,
                         String parentRunId,
                         Callable<Void> body) {
        String analysisKey = analysis == null ? "" : analysis.getClass().getSimpleName();
        ProjectFile project = AnalysisRunContextFactory.currentProjectFor(directory);
        Map<String, Object> parameters = resolveParameters(commandParameters, cliConfig);

        // Thread-local setup state belongs to one invocation. Analyses that need setup repopulate
        // it during body.call(); clearing here prevents a prior analysis from determining this run.
        BinSetupDispatcher.clearLastFieldSources();

        AnalysisRunContext context = AnalysisRunContext.open(analysisKey, analysisIndex, analysisLabel,
                directory, project, parameters, parentRunId == null ? "" : parentRunId);

        boolean aware = analysis instanceof RunRecordAware;
        if (aware) {
            ((RunRecordAware) analysis).setRunRecordContext(context);
        }

        Throwable executionFailure = null;
        Throwable publicationFailure = null;
        boolean discardRecord = false;
        boolean guiCancel = false;
        boolean preflightCancelled = false;
        String blockedReason = "";
        String cancellationReason = "";
        boolean warningsPresent = false;
        try {
            // Stage 17 preflight (single choke point): before ANY pixel-consuming analysis, verify
            // its routed channels have fresh deconvolved mirrors. A requireFresh hard-fail throws
            // here and is handled exactly like an analysis failure below. A headed Cancel returns
            // true so we skip body.call() and record a cancel.
            if (deconvPreflight(analysisIndex, directory, cliConfig, analysisLabel, context)) {
                preflightCancelled = true;
            } else if (body != null) {
                body.call();
            }
            guiCancel = AnalysisCancellation.wasCancelRequestedInActiveScope();
            if (preflightCancelled) {
                // User cancelled at the preflight: skip the consumer, discard the empty record and
                // report a cancel so the GUI loop stops (same shape as a wizard cancel).
                discardRecord = true;
                cancellationReason = "Analysis was cancelled at the deconvolution preflight.";
            } else if (guiCancel && !context.hasRecordedOutputs()) {
                discardRecord = true;
                cancellationReason = "Analysis was cancelled before output recording started.";
            } else if (guiCancel) {
                cancellationReason = "Analysis was cancelled after output recording had started.";
                context.warn(cancellationReason);
                warningsPresent = true;
            } else if (binOutcomeProvider.lastOutcome() == BinSetupDispatcher.Outcome.BLOCKED) {
                blockedReason = actionableSetupReason(
                        binOutcomeProvider.lastReason(), analysisLabel, false);
                context.warn(blockedReason);
                warningsPresent = true;
            } else if (binOutcomeProvider.lastOutcome() == BinSetupDispatcher.Outcome.CANCELLED) {
                cancellationReason = actionableSetupReason(
                        binOutcomeProvider.lastReason(), analysisLabel, true);
                context.warn(cancellationReason);
                warningsPresent = true;
            }
        } catch (Exception e) {
            guiCancel = AnalysisCancellation.wasCancelRequestedInActiveScope();
            if (isMissingSetupParameter(e)) {
                blockedReason = actionableSetupReason(e.getMessage(), analysisLabel, false);
                context.warn(blockedReason);
                warningsPresent = true;
            } else {
                context.error("Analysis failed", e);
                executionFailure = (e instanceof RuntimeException)
                        ? e : new RuntimeException(e);
            }
        } catch (Error e) {
            guiCancel = AnalysisCancellation.wasCancelRequestedInActiveScope();
            context.error("Analysis failed", e);
            executionFailure = e;
        } finally {
            if (aware) {
                ((RunRecordAware) analysis).setRunRecordContext(null);
            }
            if (discardRecord && executionFailure == null) {
                context.discard();
            } else if (guiCancel) {
                context.closeWithoutWaitingForFingerprints();
            } else {
                context.close();
            }
        }

        if (!discardRecord) {
            try {
                recordPublicationVerifier.verify(context.runId(), context.recordFile());
            } catch (Exception failure) {
                publicationFailure = failure;
            }
        }

        if (writeLegacyAudit && !discardRecord) {
            writeLegacyAudit(analysis, analysisIndex, analysisLabel, directory, cliConfig,
                    context.parametersSnapshot());
        }

        if (RunRecord.STATUS_WARN.equals(context.status())) {
            warningsPresent = true;
        }

        RunResult result;
        if (publicationFailure != null) {
            if (executionFailure == null) {
                executionFailure = new IllegalStateException(
                        "Required run-record publication failed for run " + context.runId()
                                + " at " + safePath(context.recordFile()),
                        publicationFailure);
            } else if (publicationFailure != executionFailure) {
                executionFailure.addSuppressed(publicationFailure);
            }
            result = RunResult.publicationFailed(context.runId(), context.recordFile(),
                    executionFailure, warningsPresent);
        } else if (executionFailure != null) {
            result = RunResult.failed(context.runId(), context.recordFile(),
                    executionFailure, warningsPresent);
        } else if (RunRecord.STATUS_FAILED.equals(context.status())) {
            executionFailure = new IllegalStateException(
                    "Analysis recorded a failed terminal status without propagating its cause.");
            result = RunResult.failed(context.runId(), context.recordFile(),
                    executionFailure, warningsPresent);
        } else if (!blockedReason.isEmpty()) {
            result = RunResult.blocked(context.runId(), context.recordFile(),
                    blockedReason, warningsPresent);
        } else if (discardRecord) {
            result = RunResult.cancelledWithoutRecord(cancellationReason);
        } else if (!cancellationReason.isEmpty()) {
            result = RunResult.cancelled(context.runId(), context.recordFile(),
                    cancellationReason, warningsPresent);
        } else if (RunRecord.STATUS_WARN.equals(context.status())) {
            result = RunResult.completedWithWarnings(context.runId(), context.recordFile());
        } else {
            result = RunResult.completed(context.runId(), context.recordFile());
        }

        terminalResultEmitter.emit(result);
        if (executionFailure instanceof Error) {
            throw (Error) executionFailure;
        }
        if (executionFailure instanceof RuntimeException) {
            throw (RuntimeException) executionFailure;
        }
        return result;
    }

    private static void verifyPublishedRecord(String expectedRunId, File recordFile) {
        RunRecord latest = RunRecordIO.readLatest(recordFile);
        if (latest == null) {
            throw new IllegalStateException("No readable terminal run record at "
                    + safePath(recordFile));
        }
        if (expectedRunId == null || !expectedRunId.equals(latest.runId)) {
            throw new IllegalStateException("Run-record identity mismatch at "
                    + safePath(recordFile));
        }
        if (latest.finishedAtMillis <= 0L) {
            throw new IllegalStateException("Run record has no terminal snapshot at "
                    + safePath(recordFile));
        }
    }

    private static String actionableSetupReason(String reason,
                                                String analysisLabel,
                                                boolean cancelled) {
        if (reason != null && !reason.trim().isEmpty()) {
            return reason.trim();
        }
        return safeLabel(analysisLabel) + (cancelled
                ? " setup was cancelled; the analysis body did not complete."
                : " is blocked because required setup is incomplete. Complete setup and retry.");
    }

    private static String safePath(File file) {
        return file == null ? "<unknown>" : file.getAbsolutePath();
    }

    private static boolean isMissingSetupParameter(Exception e) {
        if (!(e instanceof IllegalArgumentException)) {
            return false;
        }
        String message = e.getMessage();
        return message != null
                && message.startsWith("Cannot run ")
                && message.contains("missing parameter `");
    }

    private static Map<String, Object> resolveParameters(Map<String, Object> commandParameters,
                                                         CLIConfig cliConfig) {
        if (commandParameters != null) {
            return commandParameters;
        }
        if (cliConfig != null) {
            return ParameterSnapshot.fromCliConfig(cliConfig);
        }
        return new LinkedHashMap<String, Object>();
    }

    private void writeLegacyAudit(Analysis analysis, int analysisIndex, String analysisLabel,
                                  String directory, CLIConfig cliConfig,
                                  Map<String, Object> analysisParameters) {
        try {
            Set<BinField> required = analysis == null
                    ? EnumSet.noneOf(BinField.class)
                    : analysis.requiredBinFields();
            Map<BinField, String> sources = BinSetupDispatcher.getLastFieldSources();
            RunSettingsSnapshot.writeForAnalysis(directory, analysisLabel, analysisIndex,
                    required, sources, cliConfig, analysisParameters);
        } catch (Throwable t) {
            IJ.log("[FLASH] Could not write legacy run-settings snapshot: " + t.getMessage());
        }
    }

    // --- deconvolution preflight (Stage 17) -------------------------------

    /**
     * Layered batch trigger (A): before a pixel-consuming analysis runs, detect missing/stale
     * deconvolved mirrors for its routed channels and respond loudly.
     *
     * <ul>
     *   <li>Exempt analysis ({@link DeconvRoutingGroup#groupFor} empty), no persisted routing keys,
     *       or the group routes nothing to deconv &rarr; cheap no-op (no series enumeration).</li>
     *   <li>Fully-fresh &rarr; no-op after only cheap manifest reads.</li>
     *   <li>Headed &amp; missing/stale &rarr; offer Deconvolve now / Use raw / Cancel. Deconvolve now
     *       runs the persisted-config batch (blocking) before the consumer; Cancel aborts this run.</li>
     *   <li>Headless/CLI &amp; missing/stale &rarr; log a prominent WARN + record it and proceed on
     *       raw, UNLESS {@code deconv.requireFresh=true}, which hard-fails the run.</li>
     * </ul>
     *
     * @return {@code true} when the caller must SKIP {@code body.call()} (a headed Cancel);
     *         {@code false} to proceed (no-op, warn-and-proceed, or after a "Deconvolve now" batch).
     * @throws IllegalStateException on the headless {@code requireFresh} hard-fail path.
     */
    private boolean deconvPreflight(int analysisIndex, String directory, CLIConfig cliConfig,
                                    String analysisLabel, AnalysisRunContext context) {
        Optional<DeconvRoutingGroup> group = DeconvRoutingGroup.groupFor(analysisIndex);
        if (!group.isPresent()) {
            return false; // exempt analysis: cheapest fast path (no config read, no enumeration)
        }
        if (directory == null || directory.trim().isEmpty()) {
            return false;
        }

        ChannelConfig config = readChannelConfigQuietly(directory);
        DeconvRouting routing = DeconvPreflight.routedGroupRouting(group, config);
        if (routing == null) {
            return false; // no persisted routing keys / group routes nothing to deconv
        }

        List<DeconvPreflight.SeriesRef> series;
        try {
            series = deconvSeriesEnumerator.list(directory);
        } catch (Exception e) {
            // Never crash a run over a preflight enumeration failure; the consumer still falls back
            // per-series (its own raw fallback), just without this early warning.
            IJ.log("[Deconv preflight] Could not enumerate series in " + directory
                    + " - skipping preflight: " + e.getMessage());
            return false;
        }

        // Params-staleness-aware freshness: derive the per-channel expected deconvolution params from
        // the same persisted config the routed consumers use, so the preflight and the consumer never
        // disagree — including after a parameter change that left the source bytes unchanged.
        ExpectedDeconvParams expectedParams = DeconvConfigBridge.expectedParamsFor(config);
        List<DeconvPreflight.MissingMirror> missing = DeconvPreflight.scan(
                new File(directory), routing, group.get(), series, expectedParams);
        if (missing.isEmpty()) {
            return false; // fully fresh: only cheap manifest reads were paid
        }

        boolean headless = GraphicsEnvironment.isHeadless() || cliConfig != null;
        boolean requireFresh = cliConfig != null && cliConfig.getDeconv() != null
                && cliConfig.getDeconv().isRequireFresh();

        if (headless) {
            if (requireFresh) {
                // Propagates to run()'s catch: recorded as a failure + rethrown so a strict CLI
                // pipeline stops rather than silently measuring/rendering on raw.
                throw new IllegalStateException(preflightSummary(analysisLabel, missing)
                        + " deconv.requireFresh=true, so the run is aborted. Run 3D Deconvolution"
                        + " first (e.g. sequence run_deconv before this analysis) to produce fresh"
                        + " mirrors.");
            }
            warnProceedOnRaw(context, preflightSummary(analysisLabel, missing)
                    + " Proceeding on RAW pixels for those channels (deconv.requireFresh=false).");
            return false;
        }

        // Headed: offer Deconvolve now / Use raw / Cancel.
        PreflightChoice choice = deconvPreflightPrompter.ask(analysisLabel, missing);
        if (choice == PreflightChoice.DECONVOLVE) {
            try {
                deconvBatchRunner.run(directory);
            } catch (Exception e) {
                warnProceedOnRaw(context,
                        "Deconvolution preflight batch failed; proceeding on raw. " + e.getMessage());
            }
            return false;
        }
        if (choice == PreflightChoice.CANCEL) {
            IJ.log("[Deconv preflight] " + safeLabel(analysisLabel)
                    + " cancelled at the deconvolution preflight.");
            return true; // run() skips body.call() and records a cancel
        }
        // USE_RAW
        warnProceedOnRaw(context, preflightSummary(analysisLabel, missing)
                + " User chose to proceed on RAW pixels for those channels.");
        return false;
    }

    private static void warnProceedOnRaw(AnalysisRunContext context, String message) {
        IJ.log("[Deconv preflight] WARNING: " + message);
        if (context != null) {
            context.warn(message);
        }
    }

    private static String safeLabel(String analysisLabel) {
        return analysisLabel == null || analysisLabel.trim().isEmpty() ? "This analysis" : analysisLabel;
    }

    private static String preflightSummary(String analysisLabel,
                                           List<DeconvPreflight.MissingMirror> missing) {
        int shown = Math.min(missing.size(), 6);
        StringBuilder sb = new StringBuilder();
        sb.append(safeLabel(analysisLabel)).append(" routes ").append(missing.size())
                .append(" channel-image(s) to deconvolution but their mirror(s) are missing or stale (");
        for (int i = 0; i < shown; i++) {
            if (i > 0) sb.append(", ");
            sb.append(missing.get(i));
        }
        if (missing.size() > shown) {
            sb.append(", +").append(missing.size() - shown).append(" more");
        }
        sb.append(").");
        return sb.toString();
    }

    private PreflightChoice promptDeconvPreflightDialog(String analysisLabel,
                                                        List<DeconvPreflight.MissingMirror> missing) {
        String[] options = { "Deconvolve now", "Use raw", "Cancel" };
        String message = preflightSummary(analysisLabel, missing)
                + "\n\nDeconvolve the missing/stale mirrors now (recommended: tune-on = measure-on),\n"
                + "proceed on raw pixels for those channels, or cancel this analysis?";
        int result = JOptionPane.showOptionDialog(null, message, "Deconvolution preflight",
                JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE, null,
                options, options[0]);
        if (result == 0) return PreflightChoice.DECONVOLVE;
        if (result == 1) return PreflightChoice.USE_RAW;
        return PreflightChoice.CANCEL; // option 2, window closed, or ESC
    }

    private static ChannelConfig readChannelConfigQuietly(String directory) {
        try {
            return ChannelConfigIO.read(
                    FlashProjectLayout.forDirectory(directory).configurationWriteDir());
        } catch (RuntimeException e) {
            return null;
        }
    }

    // --- test seams -------------------------------------------------------

    void setBinOutcomeProviderForTests(BinOutcomeProvider provider) {
        this.binOutcomeProvider = provider;
    }

    void setRecordPublicationVerifierForTests(RecordPublicationVerifier verifier) {
        if (verifier != null) {
            this.recordPublicationVerifier = verifier;
        }
    }

    void setTerminalResultEmitterForTests(TerminalResultEmitter emitter) {
        if (emitter != null) {
            this.terminalResultEmitter = emitter;
        }
    }

    void setWriteLegacyAuditForTests(boolean enabled) {
        this.writeLegacyAudit = enabled;
    }

    void setDeconvSeriesEnumeratorForTests(DeconvSeriesEnumerator enumerator) {
        if (enumerator != null) {
            this.deconvSeriesEnumerator = enumerator;
        }
    }

    void setDeconvBatchRunnerForTests(DeconvBatchRunner runner) {
        if (runner != null) {
            this.deconvBatchRunner = runner;
        }
    }

    void setDeconvPreflightPrompterForTests(DeconvPreflightPrompter prompter) {
        if (prompter != null) {
            this.deconvPreflightPrompter = prompter;
        }
    }
}
