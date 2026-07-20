package flash.pipeline.intelligence;

import java.util.ArrayList;
import java.util.List;

/**
 * Structured result of a "Check my data" run.
 * Sections are rendered in order; within a section, each Finding is one line.
 * Severity is advisory only — it changes the icon/colour, not the flow.
 */
public final class DiagnosticsReport {

    public enum Severity { OK, INFO, WARN, ERROR }

    public static final class Finding {
        public final Severity severity;
        public final String message;
        public Finding(Severity severity, String message) {
            this.severity = severity == null ? Severity.INFO : severity;
            this.message = message == null ? "" : message;
        }
    }

    public static final class Section {
        public final String title;
        public final List<Finding> findings = new ArrayList<Finding>();
        public Section(String title) { this.title = title == null ? "" : title; }

        public void add(Severity s, String msg) { findings.add(new Finding(s, msg)); }
        public void ok(String msg)    { add(Severity.OK, msg); }
        public void info(String msg)  { add(Severity.INFO, msg); }
        public void warn(String msg)  { add(Severity.WARN, msg); }
        public void error(String msg) { add(Severity.ERROR, msg); }
        public boolean isEmpty() { return findings.isEmpty(); }
    }

    public final List<Section> sections = new ArrayList<Section>();
    public final String directory;
    public final long timestampMillis;

    public DiagnosticsReport(String directory) {
        this.directory = directory == null ? "" : directory;
        this.timestampMillis = System.currentTimeMillis();
    }

    public Section addSection(String title) {
        Section s = new Section(title);
        sections.add(s);
        return s;
    }

    /** Count of findings at or above a given severity, across all sections. */
    public int countAtLeast(Severity min) {
        int n = 0;
        for (Section s : sections) {
            for (Finding f : s.findings) {
                if (f.severity.ordinal() >= min.ordinal()) n++;
            }
        }
        return n;
    }
}
