package flash.pipeline.execution;

import flash.pipeline.analyses.Analysis;
import flash.pipeline.bin.BinSetupDispatcher;
import flash.pipeline.bin.ChannelConfig;
import flash.pipeline.bin.ChannelConfigIO;
import flash.pipeline.cli.CLIArgumentParser;
import flash.pipeline.cli.CLIConfig;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.runrecord.RunRecord;
import flash.pipeline.runrecord.RunRecordIO;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class AnalysisRunCoordinatorFreshnessTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private static final Analysis ANALYSIS = new Analysis() {
        @Override public void execute(String directory) { }
    };

    @Test
    public void strictFreshnessStopsBeforeBodyWhenEnumerationFails() throws Exception {
        File root = configuredProject();
        AnalysisRunCoordinator coordinator = coordinator(false);
        coordinator.setDeconvSeriesEnumeratorForTests(directory -> {
            throw new IOException("source metadata unavailable");
        });
        AtomicBoolean ran = new AtomicBoolean();
        CLIConfig config = CLIArgumentParser.parse("dir=[" + root.getAbsolutePath()
                + "] deconv.requireFresh=true");
        assertNotNull(config);
        assertTrue(config.getDeconv().isRequireFresh());
        try {
            invoke(coordinator, root, config, ran);
            fail("freshness could not be verified");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("Could not verify deconvolution freshness"));
        }
        assertFalse(ran.get());
        assertEquals("failed", latest(root).status);
    }

    @Test
    public void nonStrictEnumerationFailureIsRecordedAsWarning() throws Exception {
        File root = configuredProject();
        AnalysisRunCoordinator coordinator = coordinator(false);
        coordinator.setDeconvSeriesEnumeratorForTests(directory -> {
            throw new IOException("source metadata unavailable");
        });
        AtomicBoolean ran = new AtomicBoolean();
        RunResult result = invoke(coordinator, root, null, ran);
        assertTrue(ran.get());
        assertEquals(RunResult.TerminalState.COMPLETED_WITH_WARNINGS, result.terminalState);
        assertTrue(latest(root).messages.stream().anyMatch(
                message -> message.text.contains("Could not verify deconvolution freshness")));
    }

    @Test
    public void unsuccessfulRequestedBatchCannotSilentlyCompleteOnRaw() throws Exception {
        File root = configuredProject();
        AnalysisRunCoordinator coordinator = headedWithMissingMirror(root);
        coordinator.setDeconvBatchRunnerForTests(directory -> false);
        RunResult result = invoke(coordinator, root, null, new AtomicBoolean());
        assertEquals(RunResult.TerminalState.COMPLETED_WITH_WARNINGS, result.terminalState);
        assertTrue(latest(root).messages.stream().anyMatch(
                message -> message.text.contains("batch did not complete")));
    }

    @Test
    public void successfulRequestedBatchIsRescannedBeforeConsumerRuns() throws Exception {
        File root = configuredProject();
        AnalysisRunCoordinator coordinator = headedWithMissingMirror(root);
        // A claimed successful batch that writes no mirror must still warn on the rescan.
        coordinator.setDeconvBatchRunnerForTests(directory -> true);
        RunResult result = invoke(coordinator, root, null, new AtomicBoolean());
        assertEquals(RunResult.TerminalState.COMPLETED_WITH_WARNINGS, result.terminalState);
        assertTrue(latest(root).messages.stream().anyMatch(
                message -> message.text.contains("batch left these mirrors missing or stale")));
    }

    private AnalysisRunCoordinator headedWithMissingMirror(File root) throws Exception {
        AnalysisRunCoordinator coordinator = coordinator(true);
        File source = new File(root, "source.tif");
        Files.write(source.toPath(), "pixels".getBytes(StandardCharsets.UTF_8));
        coordinator.setDeconvSeriesEnumeratorForTests(directory -> Collections.singletonList(
                new DeconvPreflight.SeriesRef("source", source)));
        coordinator.setDeconvPreflightPrompterForTests((label, missing) ->
                AnalysisRunCoordinator.PreflightChoice.DECONVOLVE);
        return coordinator;
    }

    private AnalysisRunCoordinator coordinator(final boolean headed) {
        AnalysisRunCoordinator coordinator = new AnalysisRunCoordinator() {
            @Override boolean isHeadlessPreflight(CLIConfig config) { return !headed; }
        };
        coordinator.setWriteLegacyAuditForTests(false);
        coordinator.setBinOutcomeProviderForTests(new AnalysisRunCoordinator.BinOutcomeProvider() {
            @Override public BinSetupDispatcher.Outcome lastOutcome() {
                return BinSetupDispatcher.Outcome.COMPLETED;
            }
            @Override public String lastReason() { return ""; }
        });
        return coordinator;
    }

    private File configuredProject() throws Exception {
        File root = temp.newFolder();
        ChannelConfig config = new ChannelConfig();
        ChannelConfig.Channel channel = new ChannelConfig.Channel();
        channel.index = 0;
        channel.name = "DAPI";
        channel.deconvEngineKey = "CLIJ2";
        channel.routeAnalysis = "deconv";
        channel.routeDisplay = "deconv";
        config.channels.add(channel);
        ChannelConfigIO.write(FlashProjectLayout.forDirectory(root.getAbsolutePath())
                .configurationWriteDir(), config);
        return root;
    }

    private RunResult invoke(AnalysisRunCoordinator coordinator, File root,
                             CLIConfig config, final AtomicBoolean ran) {
        return coordinator.run(ANALYSIS, 4, "3D Object Analysis", root.getAbsolutePath(),
                config, null, "", new Callable<Void>() {
                    @Override public Void call() { ran.set(true); return null; }
                });
    }

    private RunRecord latest(File root) {
        List<flash.pipeline.runrecord.RunSummary> summaries = RunRecordIO.readIndex(
                FlashProjectLayout.forDirectory(root.getAbsolutePath()).runJsonlWriteDir());
        assertEquals(1, summaries.size());
        return RunRecordIO.readLatest(summaries.get(0).recordFile);
    }
}
