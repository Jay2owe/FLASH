package flash.pipeline.feedback;

import java.awt.Desktop;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Creates and opens a portable mailto draft; attachments remain user-reviewed. */
final class FeedbackMail {

    private FeedbackMail() {
    }

    static URI buildMailto(String recipient, String category, String summary,
                           String tags, String bundlePath) {
        String tagBlock = safe(tags).trim().isEmpty() || "none".equalsIgnoreCase(safe(tags).trim())
                ? "" : "[" + truncate(headerValue(tags).replace(", ", "+"), 80) + "]";
        String subject = "[FLASH Feedback][" + truncate(headerValue(category), 40) + "]"
                + tagBlock + " " + truncate(headerValue(summary), 160);
        StringBuilder body = new StringBuilder();
        body.append("Hello Jamie,\n\n");
        body.append("I prepared this feedback from FLASH.\n");
        body.append("Diagnostic tags: ").append(safe(tags)).append("\n\n");
        body.append("Please attach this diagnostic ZIP before sending:\n");
        body.append(safe(bundlePath)).append("\n\n");
        body.append("The ZIP was previewed and created locally; FLASH has not sent it automatically.\n");
        String uri = "mailto:" + safe(recipient)
                + "?subject=" + encode(subject)
                + "&body=" + encode(body.toString());
        return URI.create(uri);
    }

    static boolean open(URI mailto) {
        try {
            if (!Desktop.isDesktopSupported()) return false;
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.MAIL)) return false;
            desktop.mail(mailto);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String encode(String text) {
        try {
            return URLEncoder.encode(safe(text), StandardCharsets.UTF_8.name())
                    .replace("+", "%20");
        } catch (Exception impossible) {
            return safe(text).replace(" ", "%20");
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String truncate(String value, int max) {
        String safeValue = safe(value);
        return safeValue.length() <= max ? safeValue : safeValue.substring(0, max);
    }

    private static String headerValue(String value) {
        return safe(value).replace('\r', ' ').replace('\n', ' ').trim();
    }
}
