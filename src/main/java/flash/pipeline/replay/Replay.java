package flash.pipeline.replay;

import flash.pipeline.FLASH_Pipeline;
import flash.pipeline.audit.ReplayCommandFormatter;
import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.BinConfigIO;
import flash.pipeline.execution.AnalysisRegistry;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.project.ProjectFile;
import flash.pipeline.project.ProjectFileIO;
import flash.pipeline.runrecord.EnvironmentSnapshot;
import flash.pipeline.runrecord.InputFingerprinter;
import flash.pipeline.runrecord.ConfigurationSnapshot;
import flash.pipeline.runrecord.ProjectFileHasher;
import flash.pipeline.runrecord.RunDiff;
import flash.pipeline.runrecord.RunRecord;
import flash.pipeline.runrecord.RunRecordIO;
import flash.pipeline.runrecord.RunSummary;
import flash.pipeline.runrecord.Ulid;
import ij.IJ;

import javax.swing.BorderFactory;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reproduce-verbatim orchestration for run records. */
public final class Replay {

    interface Runner {
        void run(ReplayPlan plan, String macroOptions) throws Exception;
    }

    private static Runner runner = new Runner() {
        @Override
        public void run(ReplayPlan plan, String macroOptions) {
            new FLASH_Pipeline().runReplayCli(macroOptions,
                    plan.parent().runId,
                    plan.parent().parameters);
        }
    };

    private Replay() {
    }

    public static ReplayPlan plan(RunRecord parent) {
        List<String> warnings = new ArrayList<String>();
        List<String> blockers = new ArrayList<String>();

        AnalysisRegistry analysis = AnalysisRegistry.forRecord(parent);
        if (parent == null) {
            blockers.add("No parent run was provided.");
            return new ReplayPlan(null, null, null, null, warnings, blockers);
        }
        if (RunRecord.STATUS_RUNNING.equals(parent.status)) {
            blockers.add("This run has no terminal record; a running or interrupted run cannot be reproduced verbatim.");
        }
        String recordedArtifact = parent.extras == null ? ""
                : safe(String.valueOf(parent.extras.get("flashArtifactFingerprint")));
        if (recordedArtifact.isEmpty() || "null".equals(recordedArtifact)) {
            blockers.add("This run has no loaded FLASH code fingerprint; its exact build cannot be reproduced verbatim.");
        } else if (!recordedArtifact.equals(EnvironmentSnapshot.flashArtifactFingerprint())) {
            blockers.add("Loaded FLASH code changed since this run; verbatim replay requires the recorded build.");
        }
        if (analysis == null) {
            blockers.add("Analysis key is not registered in this FLASH version: "
                    + safe(parent.analysis) + " (index " + parent.analysisIndex + ").");
        } else if (!safe(parent.analysis).isEmpty()
                && !analysis.analysisKey().equals(safe(parent.analysis))) {
            blockers.add("Run analysis key " + parent.analysis
                    + " no longer maps to index " + parent.analysisIndex + ".");
        } else if (!analysis.hasReplayParameterAdapter()
                && parent.parameters != null && !parent.parameters.isEmpty()) {
            blockers.add("Analysis " + analysis.analysisKey()
                    + " has no load-from-run parameter adapter. " + analysis.note());
        }

        File parentProjectRoot = projectRoot(parent);
        if (parentProjectRoot == null || !parentProjectRoot.isDirectory()) {
            blockers.add("Parent project root is missing: "
                    + (parentProjectRoot == null ? "(unknown)" : parentProjectRoot.getAbsolutePath()));
        } else if (!safe(parent.projectFileHash).isEmpty()) {
            ProjectFile currentProject = ProjectFileIO.read(FlashProjectLayout
                    .forDirectory(parentProjectRoot.getAbsolutePath()).configurationWriteDir());
            if (currentProject == null) {
                blockers.add("The recorded project manifest is missing or unreadable; verbatim replay cannot verify the original input selection.");
            } else if (!parent.projectFileHash.equals(ProjectFileHasher.hash(currentProject))) {
                blockers.add("Project manifest changed since this run; verbatim replay would use a different input selection or metadata.");
            }
        }

        validateConfiguration(parent, parentProjectRoot, blockers);

        validateInputs(parent, warnings, blockers);

        File replayRoot = null;
        if (parentProjectRoot != null) {
            FlashProjectLayout parentLayout =
                    FlashProjectLayout.forDirectory(parentProjectRoot.getAbsolutePath());
            replayRoot = new File(parentLayout.replayWorkspacesWriteDir(),
                    Ulid.next() + "_replay_of_" + fileSafe(parent.runId));
            if (!canCreateUnder(parentLayout.replayWorkspacesWriteDir())) {
                blockers.add("Replay workspaces folder is not writable: "
                        + parentLayout.replayWorkspacesWriteDir().getAbsolutePath());
            }
        }

        return new ReplayPlan(parent, analysis, parentProjectRoot, replayRoot, warnings, blockers);
    }

    public static void execute(ReplayPlan plan, Window owner) {
        if (plan == null) {
            throw new IllegalArgumentException("Replay plan must not be null.");
        }
        if (!plan.canExecute()) {
            showOrThrow(owner, "Replay is blocked:\n" + join(plan.messages()), "Reproduce verbatim");
            return;
        }
        if (!confirm(plan, owner)) {
            return;
        }

        // A dialog or delayed invocation may outlive the original preflight checks.
        ReplayPlan revalidated = plan(plan.parent());
        if (!revalidated.canExecute()) {
            showOrThrow(owner, "Replay is blocked:\n" + join(revalidated.messages()), "Reproduce verbatim");
            return;
        }

        File replayRoot = plan.replayRoot();
        if (replayRoot == null) {
            throw new IllegalStateException("Replay root was not planned.");
        }
        if (replayRoot.exists()) {
            throw new IllegalStateException("Replay root already exists: " + replayRoot.getAbsolutePath());
        }
        if (!replayRoot.mkdirs() && !replayRoot.isDirectory()) {
            throw new IllegalStateException("Could not create replay root: " + replayRoot.getAbsolutePath());
        }
        assertFreshReplayRoot(replayRoot);

        try {
            copyRecordedConfiguration(plan.parent(), plan.parentProjectRoot(), replayRoot);
            rewriteProjectOutputRoot(replayRoot);
            String macroOptions = macroOptionsFor(plan);
            runner.run(plan, macroOptions);
            logReplay(plan, findChildRecord(plan));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Replay failed: " + e.getMessage(), e);
        }
    }

    static void setRunnerForTests(Runner next) {
        runner = next == null ? new Runner() {
            @Override
            public void run(ReplayPlan plan, String macroOptions) {
                new FLASH_Pipeline().runReplayCli(macroOptions,
                        plan.parent().runId,
                        plan.parent().parameters);
            }
        } : next;
    }

    private static void validateInputs(RunRecord parent, List<String> warnings, List<String> blockers) {
        if (parent.inputs == null || parent.inputs.isEmpty()) {
            blockers.add("This run has no recorded inputs; verbatim replay cannot verify its original data selection.");
            return;
        }
        for (RunRecord.InputItem input : parent.inputs) {
            if (input == null || safe(input.path).isEmpty()) {
                blockers.add("Recorded input has no source path; verbatim replay cannot verify it.");
                continue;
            }
            File file = new File(input.path);
            if (!file.isFile()) {
                blockers.add("Input file is missing: " + file.getAbsolutePath());
                continue;
            }
            if (safe(input.fingerprint).isEmpty()) {
                blockers.add("Input has no recorded fingerprint; verbatim replay cannot verify "
                        + file.getAbsolutePath());
            } else {
                InputFingerprinter.FingerprintMode mode;
                if ("full".equals(input.fingerprintMode)) {
                    mode = InputFingerprinter.FingerprintMode.FULL;
                } else if ("fast".equals(input.fingerprintMode)
                        || safe(input.fingerprintMode).isEmpty()) {
                    mode = InputFingerprinter.FingerprintMode.FAST;
                } else {
                    blockers.add("Unsupported input fingerprint mode " + input.fingerprintMode
                            + " for " + file.getAbsolutePath());
                    continue;
                }
                InputFingerprinter.FingerprintResult fresh = InputFingerprinter.fingerprint(file, mode);
                if (fresh.hasValue() && (file.length() != fresh.sizeBytes
                        || file.lastModified() != fresh.lastModifiedMillis)) {
                    blockers.add("Input changed while its fingerprint was being verified: " + file.getAbsolutePath());
                } else if (fresh.hasValue() && !input.fingerprint.equals(fresh.value)) {
                    blockers.add("Input fingerprint drifted: " + file.getAbsolutePath());
                } else if (!fresh.hasValue()) {
                    blockers.add("Could not verify input fingerprint: " + file.getAbsolutePath()
                            + ". " + fresh.warning);
                }
            }
        }
    }

    private static void validateConfiguration(RunRecord parent, File root, List<String> blockers) {
        Object saved = parent.extras == null ? null : parent.extras.get(ConfigurationSnapshot.EXTRA_KEY);
        if (!(saved instanceof Map)) {
            blockers.add("This run has no configuration and ROI fingerprints; verbatim replay cannot verify its original settings or region selections.");
            return;
        }
        if (root == null || !root.isDirectory()) return;
        try {
            Map<String, Object> current = ConfigurationSnapshot.capture(root);
            if (!saved.equals(current)) {
                blockers.add("Configuration or ROI files changed since this run; verbatim replay would use different settings or region selections.");
            }
        } catch (IOException failure) {
            blockers.add("Could not verify the recorded configuration or ROI files: " + failure.getMessage());
        }
    }

    private static void copyRecordedConfiguration(RunRecord parent, File sourceRoot, File targetRoot)
            throws IOException {
        Map<?, ?> fingerprints = (Map<?, ?>) parent.extras.get(ConfigurationSnapshot.EXTRA_KEY);
        for (Map.Entry<?, ?> entry : fingerprints.entrySet()) {
            if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof String)) {
                throw new IOException("Malformed recorded configuration fingerprint.");
            }
            String relative = (String) entry.getKey();
            File source = ConfigurationSnapshot.resolve(sourceRoot, relative);
            File target = ConfigurationSnapshot.resolve(targetRoot, relative);
            Files.createDirectories(target.getParentFile().toPath());
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            InputFingerprinter.FingerprintResult copied = InputFingerprinter.fullFingerprint(target);
            if (!entry.getValue().equals(copied.value)) {
                throw new IOException("Configuration changed while preparing replay: " + relative);
            }
        }
    }

    private static File projectRoot(RunRecord parent) {
        if (parent == null) {
            return null;
        }
        String root = safe(parent.projectRoot);
        if (root.isEmpty()) {
            root = safe(parent.outputRoot);
        }
        return root.isEmpty() ? null : new File(root).getAbsoluteFile();
    }

    private static boolean canCreateUnder(File dir) {
        if (dir == null) {
            return false;
        }
        File probe = dir;
        while (probe != null && !probe.exists()) {
            probe = probe.getParentFile();
        }
        return probe != null && probe.isDirectory() && probe.canWrite();
    }

    private static boolean confirm(ReplayPlan plan, Window owner) {
        if (owner == null || GraphicsEnvironment.isHeadless()) {
            return true;
        }
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        String message = "Re-run " + plan.analysis().analysisKey()
                + " with " + inputCount(plan.parent()) + " input(s) into a new replay folder?";
        javax.swing.JLabel label = new javax.swing.JLabel("<html><body width='520'>"
                + escapeHtml(message) + "<br><br>"
                + escapeHtml(plan.replayRoot().getAbsolutePath()) + "</body></html>");
        panel.add(label, BorderLayout.NORTH);
        JTree tree = flash.pipeline.runrecord.ui.RunDetailPanel
                .createParameterTree(plan.parent().parameters);
        JScrollPane scroll = new JScrollPane(tree);
        scroll.setPreferredSize(new Dimension(560, 360));
        scroll.setBorder(BorderFactory.createTitledBorder("Parameters"));
        panel.add(scroll, BorderLayout.CENTER);
        if (!plan.warnings().isEmpty()) {
            javax.swing.JTextArea warnings = new javax.swing.JTextArea(join(plan.warnings()));
            warnings.setEditable(false);
            warnings.setLineWrap(true);
            warnings.setWrapStyleWord(true);
            JScrollPane warnScroll = new JScrollPane(warnings);
            warnScroll.setPreferredSize(new Dimension(560, 96));
            warnScroll.setBorder(BorderFactory.createTitledBorder("Warnings"));
            panel.add(warnScroll, BorderLayout.SOUTH);
        }
        int choice = JOptionPane.showConfirmDialog(owner, panel,
                "Reproduce verbatim", JOptionPane.OK_CANCEL_OPTION,
                plan.status() == ReplayPlan.Status.WARN
                        ? JOptionPane.WARNING_MESSAGE
                        : JOptionPane.PLAIN_MESSAGE);
        return choice == JOptionPane.OK_OPTION;
    }

    private static void showOrThrow(Window owner, String message, String title) {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException(message);
        }
        JOptionPane.showMessageDialog(owner, message, title, JOptionPane.WARNING_MESSAGE);
    }

    private static void assertFreshReplayRoot(File replayRoot) {
        File[] files = replayRoot.listFiles();
        if (files != null && files.length > 0) {
            throw new IllegalStateException("Replay root is not empty: " + replayRoot.getAbsolutePath());
        }
    }

    private static void rewriteProjectOutputRoot(File replayRoot) throws IOException {
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(replayRoot.getAbsolutePath());
        ProjectFile project = ProjectFileIO.read(layout.configurationWriteDir());
        if (project == null) {
            return;
        }
        project.outputRoot = replayRoot.getAbsolutePath();
        if (project.extras != null) {
            project.extras.put("replayOutputRootRewritten", Boolean.TRUE);
        }
        ProjectFileIO.write(layout.configurationWriteDir(), project);
    }

    private static String macroOptionsFor(ReplayPlan plan) {
        BinConfig cfg = BinConfigIO.readPartialFromDirectory(plan.replayRoot().getAbsolutePath());
        String command = ReplayCommandFormatter.format(plan.replayRoot().getAbsolutePath(),
                plan.analysis().analysisIndex(), cfg);
        String options = extractOptions(command);
        if (!containsToken(options, "no_aggregate")) {
            options = options + " no_aggregate";
        }
        return options;
    }

    private static String extractOptions(String command) {
        int start = command.indexOf("\", \"");
        int end = command.lastIndexOf("\");");
        if (start < 0 || end <= start) {
            return "";
        }
        return command.substring(start + 4, end);
    }

    private static boolean containsToken(String options, String token) {
        if (options == null || token == null) {
            return false;
        }
        String[] parts = options.split("\\s+");
        for (String part : parts) {
            if (token.equals(part)) {
                return true;
            }
        }
        return false;
    }

    private static RunRecord findChildRecord(ReplayPlan plan) {
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(plan.replayRoot().getAbsolutePath());
        List<RunSummary> summaries = RunRecordIO.readIndex(layout.runJsonlWriteDir());
        for (RunSummary summary : summaries) {
            if (summary != null && plan.parent().runId.equals(summary.parentRunId)
                    && summary.recordFile != null) {
                return RunRecordIO.readLatest(summary.recordFile);
            }
        }
        return summaries.isEmpty() || summaries.get(0).recordFile == null
                ? null
                : RunRecordIO.readLatest(summaries.get(0).recordFile);
    }

    private static void logReplay(ReplayPlan plan, RunRecord child) {
        String childId = child == null ? "(unknown)" : safe(child.runId);
        int changes = child == null ? 0 : RunDiff.diff(plan.parent(), child).size();
        IJ.log("[FLASH] Run " + childId + " is a verbatim replay of "
                + safe(plan.parent().runId)
                + " (FLASH " + safe(plan.parent().flashVersion)
                + " -> " + EnvironmentSnapshot.flashVersion()
                + "; " + changes + " parameter changes)");
    }

    private static int inputCount(RunRecord record) {
        return record == null || record.inputs == null ? 0 : record.inputs.size();
    }

    private static String join(List<String> lines) {
        StringBuilder out = new StringBuilder();
        if (lines != null) {
            for (String line : lines) {
                if (out.length() > 0) {
                    out.append('\n');
                }
                out.append(line);
            }
        }
        return out.toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String fileSafe(String value) {
        String safe = safe(value);
        if (safe.isEmpty()) {
            return "unknown";
        }
        return safe.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
