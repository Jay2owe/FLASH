package flash.pipeline.replay;

import flash.pipeline.execution.AnalysisRegistry;
import flash.pipeline.runrecord.RunRecord;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable preflight result for a reproduce-verbatim request. */
public final class ReplayPlan {
    public enum Status {
        READY,
        WARN,
        BLOCKED
    }

    private final RunRecord parent;
    private final AnalysisRegistry analysis;
    private final File parentProjectRoot;
    private final File replayRoot;
    private final List<String> warnings;
    private final List<String> blockers;

    ReplayPlan(RunRecord parent,
               AnalysisRegistry analysis,
               File parentProjectRoot,
               File replayRoot,
               List<String> warnings,
               List<String> blockers) {
        this.parent = parent;
        this.analysis = analysis;
        this.parentProjectRoot = parentProjectRoot;
        this.replayRoot = replayRoot;
        this.warnings = immutableCopy(warnings);
        this.blockers = immutableCopy(blockers);
    }

    public RunRecord parent() {
        return parent;
    }

    public AnalysisRegistry analysis() {
        return analysis;
    }

    public File parentProjectRoot() {
        return parentProjectRoot;
    }

    public File replayRoot() {
        return replayRoot;
    }

    public List<String> warnings() {
        return warnings;
    }

    public List<String> blockers() {
        return blockers;
    }

    public List<String> messages() {
        List<String> out = new ArrayList<String>();
        for (String blocker : blockers) {
            out.add("BLOCKED: " + blocker);
        }
        for (String warning : warnings) {
            out.add("WARN: " + warning);
        }
        return Collections.unmodifiableList(out);
    }

    public Status status() {
        if (!blockers.isEmpty()) {
            return Status.BLOCKED;
        }
        if (!warnings.isEmpty()) {
            return Status.WARN;
        }
        return Status.READY;
    }

    public boolean canExecute() {
        return status() != Status.BLOCKED;
    }

    private static List<String> immutableCopy(List<String> source) {
        List<String> copy = new ArrayList<String>();
        if (source != null) {
            copy.addAll(source);
        }
        return Collections.unmodifiableList(copy);
    }
}
