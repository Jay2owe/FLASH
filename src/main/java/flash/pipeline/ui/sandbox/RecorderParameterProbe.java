package flash.pipeline.ui.sandbox;

import flash.pipeline.image.FilterMacroEditorModel;
import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.plugin.frame.Recorder;

import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RecorderParameterProbe {

    private static final Pattern RUN_LINE = Pattern.compile(
            "run\\s*\\(\\s*\"((?:\\\\.|[^\"])*)\"(?:\\s*,\\s*\"((?:\\\\.|[^\"])*)\")?\\s*\\)\\s*;?");

    private RecorderParameterProbe() {}

    public static synchronized ProbeResult probe(ImagePlus preview, String commandName) {
        if (GraphicsEnvironment.isHeadless()) {
            return ProbeResult.cancelled(commandName, "Recorder probing is disabled in headless mode.");
        }
        if (preview == null) {
            return ProbeResult.cancelled(commandName, "No preview image is available.");
        }
        String command = commandName == null ? "" : commandName.trim();
        if (command.length() == 0) {
            return ProbeResult.cancelled(commandName, "No Fiji command was selected.");
        }

        Recorder rec;
        try {
            rec = resolveRecorder();
        } catch (Throwable t) {
            return ProbeResult.cancelled(command, cleanMessage(t));
        }
        if (rec == null) {
            return ProbeResult.cancelled(command, "Could not open the ImageJ Recorder.");
        }

        boolean priorRecord = Recorder.record;
        boolean priorRecordInMacros = Recorder.recordInMacros;
        String before = safeText(rec);
        int beforeLength = before.length();
        try {
            Recorder.record = true;
            Recorder.recordInMacros = true;
            IJ.run(preview, command, "");
            String after = safeText(rec);
            String diff = after.length() >= beforeLength ? after.substring(beforeLength) : after;
            ProbeResult parsed = parseLastRunLine(diff, command);
            if (parsed.userCancelled) {
                return ProbeResult.cancelled(command, "Command was cancelled or did not record a run line.");
            }
            return parsed;
        } catch (Throwable t) {
            return ProbeResult.cancelled(command, cleanMessage(t));
        } finally {
            Recorder.record = priorRecord;
            Recorder.recordInMacros = priorRecordInMacros;
        }
    }

    static Recorder resolveRecorder() {
        Frame frame = WindowManager.getFrame("Recorder");
        if (frame instanceof Recorder) return (Recorder) frame;
        return new Recorder();
    }

    static ProbeResult parseLastRunLine(String recorderDiff, String expectedCommandName) {
        String diff = recorderDiff == null ? "" : recorderDiff;
        Matcher matcher = RUN_LINE.matcher(diff);
        ProbeResult last = null;
        while (matcher.find()) {
            String command = unescape(matcher.group(1));
            String options = matcher.group(2) == null ? "" : unescape(matcher.group(2));
            last = ProbeResult.captured(command, options);
        }
        if (last == null) return ProbeResult.cancelled(expectedCommandName, "");
        return last;
    }

    public static List<FilterMacroEditorModel.Parameter> parseOptions(String optionsString) {
        List<FilterMacroEditorModel.Parameter> params =
                new ArrayList<FilterMacroEditorModel.Parameter>();
        List<String> tokens = tokenizeOptions(optionsString);
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            int equals = token.indexOf('=');
            if (equals <= 0 || equals == token.length() - 1) continue;
            String key = token.substring(0, equals).trim();
            String value = token.substring(equals + 1).trim();
            if (key.length() == 0 || value.length() == 0) continue;
            params.add(new FilterMacroEditorModel.Parameter(key, value, value, "", ""));
        }
        return params;
    }

    static List<String> tokenizeOptions(String optionsString) {
        List<String> tokens = new ArrayList<String>();
        String text = optionsString == null ? "" : optionsString.trim();
        if (text.length() == 0) return tokens;
        StringBuilder current = new StringBuilder();
        int bracketDepth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '[') bracketDepth++;
            if (c == ']' && bracketDepth > 0) bracketDepth--;
            if (Character.isWhitespace(c) && bracketDepth == 0) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) tokens.add(current.toString());
        return tokens;
    }

    private static String safeText(Recorder rec) {
        try {
            String text = rec.getText();
            return text == null ? "" : text;
        } catch (Throwable t) {
            return "";
        }
    }

    private static String unescape(String text) {
        if (text == null || text.indexOf('\\') < 0) return text == null ? "" : text;
        StringBuilder sb = new StringBuilder(text.length());
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                sb.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else {
                sb.append(c);
            }
        }
        if (escaped) sb.append('\\');
        return sb.toString();
    }

    private static String cleanMessage(Throwable t) {
        if (t == null) return "";
        String message = t.getMessage();
        if (message == null || message.trim().isEmpty()) return t.getClass().getSimpleName();
        return message;
    }

    public static final class ProbeResult {
        public final boolean userCancelled;
        public final String commandName;
        public final String optionsString;
        public final String errorMessage;

        private ProbeResult(boolean userCancelled, String commandName,
                            String optionsString, String errorMessage) {
            this.userCancelled = userCancelled;
            this.commandName = commandName == null ? "" : commandName;
            this.optionsString = optionsString == null ? "" : optionsString;
            this.errorMessage = errorMessage == null ? "" : errorMessage;
        }

        static ProbeResult captured(String commandName, String optionsString) {
            return new ProbeResult(false, commandName, optionsString, "");
        }

        static ProbeResult cancelled(String commandName, String errorMessage) {
            return new ProbeResult(true, commandName, "", errorMessage);
        }
    }
}
