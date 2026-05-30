package flash.pipeline.execution;

import flash.pipeline.analyses.Analysis;
import flash.pipeline.audit.RunSettingsSnapshot;
import flash.pipeline.bin.BinField;
import flash.pipeline.bin.BinSetupDispatcher;
import flash.pipeline.cli.CLIConfig;
import flash.pipeline.runrecord.AnalysisRunContext;
import flash.pipeline.runrecord.AnalysisRunContextFactory;
import flash.pipeline.runrecord.ParameterSnapshot;
import flash.pipeline.runrecord.RunRecordAware;
import flash.pipeline.project.ProjectFile;
import ij.IJ;

import org.scijava.plugin.Plugin;
import org.scijava.service.AbstractService;
import org.scijava.service.Service;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
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
    }

    private BinOutcomeProvider binOutcomeProvider = new BinOutcomeProvider() {
        @Override
        public BinSetupDispatcher.Outcome lastOutcome() {
            return BinSetupDispatcher.getLastOutcome();
        }
    };

    private boolean writeLegacyAudit = true;

    /**
     * Run {@code body} (typically {@code analysis.execute(directory)}) inside a
     * run-record context. Records cancellation and missing setup parameters as
     * {@code warn}, thrown failures as {@code failed}, then rethrows ordinary
     * failures so existing GUI/CLI handling is unchanged.
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

        AnalysisRunContext context = AnalysisRunContext.open(analysisKey, analysisIndex, analysisLabel,
                directory, project, parameters, parentRunId == null ? "" : parentRunId);

        boolean aware = analysis instanceof RunRecordAware;
        if (aware) {
            ((RunRecordAware) analysis).setRunRecordContext(context);
        }

        RuntimeException runtimeFailure = null;
        Error errorFailure = null;
        try {
            if (body != null) {
                body.call();
            }
            if (binOutcomeProvider.lastOutcome() == BinSetupDispatcher.Outcome.CANCELLED) {
                context.warn("Bin setup was cancelled; analysis did not run to completion.");
            }
        } catch (Exception e) {
            if (isMissingSetupParameter(e)) {
                context.warn(e.getMessage());
            } else {
                context.error("Analysis failed", e);
                runtimeFailure = (e instanceof RuntimeException) ? (RuntimeException) e : new RuntimeException(e);
            }
        } catch (Error e) {
            context.error("Analysis failed", e);
            errorFailure = e;
        } finally {
            if (aware) {
                ((RunRecordAware) analysis).setRunRecordContext(null);
            }
            context.close();
        }

        if (writeLegacyAudit) {
            writeLegacyAudit(analysis, analysisIndex, analysisLabel, directory, cliConfig);
        }

        if (errorFailure != null) {
            throw errorFailure;
        }
        if (runtimeFailure != null) {
            throw runtimeFailure;
        }
        return new RunResult(context.runId(), context.status(), context.recordFile());
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
                                  String directory, CLIConfig cliConfig) {
        try {
            Set<BinField> required = analysis == null
                    ? EnumSet.noneOf(BinField.class)
                    : analysis.requiredBinFields();
            Map<BinField, String> sources = BinSetupDispatcher.getLastFieldSources();
            RunSettingsSnapshot.writeForAnalysis(directory, analysisLabel, analysisIndex,
                    required, sources, cliConfig);
        } catch (Throwable t) {
            IJ.log("[FLASH] Could not write legacy run-settings snapshot: " + t.getMessage());
        }
    }

    // --- test seams -------------------------------------------------------

    void setBinOutcomeProviderForTests(BinOutcomeProvider provider) {
        this.binOutcomeProvider = provider;
    }

    void setWriteLegacyAuditForTests(boolean enabled) {
        this.writeLegacyAudit = enabled;
    }
}
