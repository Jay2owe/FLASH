package flash.pipeline.feedback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FeedbackSupportTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void defaultRecipientUsesImperialAddress() {
        assertEquals("jm3923@ic.ac.uk", FeedbackReport.DEFAULT_RECIPIENT);
    }

    @Test
    public void diagnosticWindowTitlesAreClassifiedConservatively() {
        assertEquals(FeedbackDiagnostics.Kind.LOG, FeedbackDiagnostics.classifyTitle("Log"));
        assertEquals(FeedbackDiagnostics.Kind.EXCEPTION,
                FeedbackDiagnostics.classifyTitle("Exception"));
        assertEquals(FeedbackDiagnostics.Kind.EXCEPTION,
                FeedbackDiagnostics.classifyTitle("Error Log"));
        assertEquals(FeedbackDiagnostics.Kind.CONSOLE,
                FeedbackDiagnostics.classifyTitle("Fiji Console"));
        assertEquals(FeedbackDiagnostics.Kind.OTHER,
                FeedbackDiagnostics.classifyTitle("Results"));
        assertEquals(FeedbackDiagnostics.Kind.OTHER,
                FeedbackDiagnostics.classifyTitle("Analysis progress logbook"));
    }

    @Test
    public void redactionRemovesHomePathsAndEmailAddresses() {
        String text = "C:\\Users\\Alice\\data\\image.lif\n"
                + "C:/Users/Alice/data/image.lif\n"
                + "alice@example.org";

        String redacted = FeedbackDiagnostics.redact(text, "C:\\Users\\Alice");

        assertFalse(redacted.contains("Alice"));
        assertFalse(redacted.contains("alice@example.org"));
        assertTrue(redacted.contains("<USER_HOME>\\data\\image.lif"));
        assertTrue(redacted.contains("<EMAIL>"));
    }

    @Test
    public void reportIncludesOnlySelectedDiagnosticsAndSanitizedAttachments() throws Exception {
        File attached = temp.newFile("Exception.txt");
        Files.write(attached.toPath(),
                "Failure at C:\\Users\\Alice\\data\nalice@example.org".getBytes(StandardCharsets.UTF_8));
        FeedbackReport.Request request = new FeedbackReport.Request();
        request.category = "Bug / error";
        request.summary = "Plugin did not open";
        request.message = "It stopped before the main window.";
        request.includeLog = true;
        request.includeExceptions = true;
        request.includeConsole = false;
        request.includeSystem = false;
        request.attachments.add(attached);
        FeedbackDiagnostics.Snapshot snapshot = new FeedbackDiagnostics.Snapshot(
                "log line", "exception line", "console line");

        LinkedHashMap<String, String> entries = FeedbackReport.build(request, snapshot);

        assertTrue(entries.containsKey("feedback.txt"));
        assertTrue(entries.containsKey("imagej-log.txt"));
        assertTrue(entries.containsKey("exceptions.txt"));
        assertFalse(entries.containsKey("console.txt"));
        assertFalse(entries.containsKey("system-info.txt"));
        assertTrue(entries.containsKey("attached/Exception.txt"));
        assertTrue(entries.get("feedback.txt").contains("log, exception, attached-files"));
        assertFalse(entries.get("attached/Exception.txt").contains("alice@example.org"));
    }

    @Test
    public void bundleWriterProducesExpectedUtf8ZipEntries() throws Exception {
        Map<String, String> entries = new LinkedHashMap<String, String>();
        entries.put("feedback.txt", "hello");
        entries.put("exceptions.txt", "failure: α");
        File output = new File(temp.getRoot(), "feedback.zip");

        FeedbackBundleWriter.write(entries, output);

        assertTrue(output.isFile());
        try (ZipFile zip = new ZipFile(output)) {
            assertEquals("hello", read(zip, "feedback.txt"));
            assertEquals("failure: α", read(zip, "exceptions.txt"));
        }
    }

    @Test
    public void mailDraftContainsRecipientTagsAndBundleInstruction() throws Exception {
        URI mailto = FeedbackMail.buildMailto("jamie@example.org", "Bug / error",
                "LIF failed", "log, exception", "C:\\Temp\\FLASH feedback.zip");
        String value = mailto.toASCIIString();

        assertTrue(value.startsWith("mailto:jamie@example.org?"));
        assertTrue(value.contains("FLASH%20Feedback"));
        assertTrue(value.contains("log%2C%20exception"));
        assertTrue(value.contains("log%2Bexception"));
        assertTrue(value.contains("FLASH%20feedback.zip"));
    }

    @Test
    public void oversizedDiagnosticSectionIsMarkedAsTruncated() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < FeedbackDiagnostics.MAX_SECTION_CHARS + 10; i++) text.append('x');

        String limited = FeedbackDiagnostics.limit(text.toString());

        assertTrue(limited.contains("[Truncated by FLASH"));
        assertTrue(limited.length() < text.length() + 100);
    }

    @Test
    public void feedbackCommandIsRegisteredInFijiHelpMenu() throws Exception {
        java.io.InputStream input = getClass().getClassLoader().getResourceAsStream("plugins.config");
        assertTrue(input != null);
        byte[] bytes;
        try (java.io.InputStream in = input;
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int count;
            while ((count = in.read(buffer)) >= 0) out.write(buffer, 0, count);
            bytes = out.toByteArray();
        }
        String config = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(config.contains("Help, \"Send FLASH Feedback...\", "
                + "flash.pipeline.feedback.FeedbackDialog"));
    }

    private static String read(ZipFile zip, String name) throws Exception {
        ZipEntry entry = zip.getEntry(name);
        assertTrue(entry != null);
        byte[] bytes = new byte[(int) entry.getSize()];
        int offset = 0;
        try (java.io.InputStream in = zip.getInputStream(entry)) {
            while (offset < bytes.length) {
                int count = in.read(bytes, offset, bytes.length - offset);
                if (count < 0) break;
                offset += count;
            }
        }
        return new String(bytes, 0, offset, StandardCharsets.UTF_8);
    }
}
