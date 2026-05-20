package flash.pipeline.segmentation;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public final class SegmentationTokenCodec {
    private SegmentationTokenCodec() {
    }

    public static String percentEncodeToken(String value) {
        String safe = value == null ? "" : value;
        byte[] bytes = safe.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xff;
            if ((b >= 'A' && b <= 'Z')
                    || (b >= 'a' && b <= 'z')
                    || (b >= '0' && b <= '9')
                    || b == '-' || b == '_' || b == '.' || b == '~') {
                sb.append((char) b);
            } else {
                sb.append('%');
                char high = Character.toUpperCase(Character.forDigit((b >> 4) & 0x0f, 16));
                char low = Character.toUpperCase(Character.forDigit(b & 0x0f, 16));
                sb.append(high).append(low);
            }
        }
        return sb.toString();
    }

    public static String percentDecodeToken(String encoded) {
        if (encoded == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < encoded.length(); i++) {
            char ch = encoded.charAt(i);
            if (ch == '%' && i + 2 < encoded.length()) {
                int high = Character.digit(encoded.charAt(i + 1), 16);
                int low = Character.digit(encoded.charAt(i + 2), 16);
                if (high >= 0 && low >= 0) {
                    out.write((high << 4) + low);
                    i += 2;
                    continue;
                }
            }
            byte[] raw = String.valueOf(ch).getBytes(StandardCharsets.UTF_8);
            out.write(raw, 0, raw.length);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
