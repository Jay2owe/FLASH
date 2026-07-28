package flash.pipeline.feedback;

import ij.IJ;
import ij.WindowManager;
import ij.text.TextWindow;

import javax.swing.JTextArea;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.TextArea;
import java.util.Locale;
import java.util.regex.Pattern;

/** Captures the diagnostic text that is already visible inside Fiji/ImageJ. */
final class FeedbackDiagnostics {

    static final int MAX_SECTION_CHARS = 500000;
    private static final Pattern EMAIL = Pattern.compile(
            "(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");

    private FeedbackDiagnostics() {
    }

    static Snapshot capture() {
        StringBuilder log = new StringBuilder();
        StringBuilder exceptions = new StringBuilder();
        StringBuilder console = new StringBuilder();

        appendDistinct(log, safe(IJ.getLog()));
        Frame[] frames = WindowManager.getNonImageWindows();
        if (frames != null) {
            for (Frame frame : frames) {
                if (frame == null) continue;
                Kind kind = classifyTitle(frame.getTitle());
                if (kind == Kind.OTHER) continue;
                String text = extractText(frame);
                if (kind == Kind.LOG) appendDistinct(log, text);
                if (kind == Kind.EXCEPTION) appendDistinct(exceptions, text);
                if (kind == Kind.CONSOLE) appendDistinct(console, text);
            }
        }
        return new Snapshot(limit(log.toString()), limit(exceptions.toString()),
                limit(console.toString()));
    }

    static Kind classifyTitle(String title) {
        String normalized = safe(title).trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("exception") || normalized.equals("error")
                || normalized.startsWith("error") || normalized.endsWith(" error")
                || normalized.endsWith(" errors")) {
            return Kind.EXCEPTION;
        }
        if (normalized.contains("console") || normalized.contains("stdout")
                || normalized.contains("stderr")) {
            return Kind.CONSOLE;
        }
        if (normalized.equals("log") || normalized.equals("imagej log")
                || normalized.equals("fiji log")) {
            return Kind.LOG;
        }
        return Kind.OTHER;
    }

    static String redact(String text) {
        return redact(text, System.getProperty("user.home", ""));
    }

    static String redact(String text, String userHome) {
        String out = safe(text);
        String home = safe(userHome).trim();
        if (!home.isEmpty()) {
            out = replaceIgnoreCase(out, home, "<USER_HOME>");
            out = replaceIgnoreCase(out, home.replace('\\', '/'), "<USER_HOME>");
        }
        return EMAIL.matcher(out).replaceAll("<EMAIL>");
    }

    static String limit(String text) {
        String safeText = safe(text);
        if (safeText.length() <= MAX_SECTION_CHARS) return safeText;
        return safeText.substring(0, MAX_SECTION_CHARS)
                + "\n\n[Truncated by FLASH after " + MAX_SECTION_CHARS + " characters.]\n";
    }

    private static String extractText(Frame frame) {
        try {
            if (frame instanceof TextWindow) {
                return safe(((TextWindow) frame).getTextPanel().getText());
            }
            StringBuilder out = new StringBuilder();
            appendComponentText(frame, out);
            return out.toString();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static void appendComponentText(Component component, StringBuilder out) {
        if (component instanceof TextArea) {
            appendDistinct(out, ((TextArea) component).getText());
        } else if (component instanceof JTextArea) {
            appendDistinct(out, ((JTextArea) component).getText());
        } else if (component instanceof JTextComponent) {
            appendDistinct(out, ((JTextComponent) component).getText());
        }
        if (component instanceof Container) {
            Component[] children = ((Container) component).getComponents();
            for (Component child : children) {
                appendComponentText(child, out);
            }
        }
    }

    private static void appendDistinct(StringBuilder target, String value) {
        String normalized = safe(value).trim();
        if (normalized.isEmpty()) return;
        if (target.indexOf(normalized) >= 0) return;
        if (target.length() > 0) target.append("\n\n");
        target.append(normalized);
    }

    private static String replaceIgnoreCase(String text, String target, String replacement) {
        if (target == null || target.isEmpty()) return text;
        return Pattern.compile(Pattern.quote(target), Pattern.CASE_INSENSITIVE)
                .matcher(text).replaceAll(replacement);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    enum Kind { LOG, EXCEPTION, CONSOLE, OTHER }

    static final class Snapshot {
        final String log;
        final String exceptions;
        final String console;

        Snapshot(String log, String exceptions, String console) {
            this.log = safe(log);
            this.exceptions = safe(exceptions);
            this.console = safe(console);
        }

        boolean hasLog() { return !log.trim().isEmpty(); }
        boolean hasExceptions() { return !exceptions.trim().isEmpty(); }
        boolean hasConsole() { return !console.trim().isEmpty(); }
    }
}
