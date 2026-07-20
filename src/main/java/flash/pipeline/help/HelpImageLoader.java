package flash.pipeline.help;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import java.awt.Dimension;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

/**
 * Loads analysis help images from packaged classpath resources.
 */
public final class HelpImageLoader {

    private HelpImageLoader() {
    }

    public static boolean resourceExists(String resourcePath) {
        InputStream stream = openResource(resourcePath);
        if (stream == null) {
            return false;
        }
        try {
            return true;
        } finally {
            closeQuietly(stream);
        }
    }

    public static BufferedImage loadImage(String resourcePath) {
        InputStream stream = openResource(resourcePath);
        if (stream == null) {
            return null;
        }
        try {
            return ImageIO.read(stream);
        } catch (IOException e) {
            return null;
        } finally {
            closeQuietly(stream);
        }
    }

    public static ImageIcon loadScaledIcon(String resourcePath, int maxWidth, int maxHeight) {
        BufferedImage image = loadImage(resourcePath);
        if (image == null) {
            return null;
        }
        Dimension scaledSize = scaledSize(image.getWidth(), image.getHeight(), maxWidth, maxHeight);
        Image scaled = image.getScaledInstance(scaledSize.width, scaledSize.height, Image.SCALE_SMOOTH);
        return new ImageIcon(scaled);
    }

    public static Dimension scaledSize(int width, int height, int maxWidth, int maxHeight) {
        double scale = Math.min((double) maxWidth / Math.max(1, width),
                (double) maxHeight / Math.max(1, height));
        scale = Math.min(1.0d, scale);
        int scaledWidth = Math.max(1, (int) Math.round(Math.max(1, width) * scale));
        int scaledHeight = Math.max(1, (int) Math.round(Math.max(1, height) * scale));
        return new Dimension(scaledWidth, scaledHeight);
    }

    public static String normalizeResourcePath(String resourcePath) {
        if (resourcePath == null) {
            return null;
        }
        String trimmed = resourcePath.trim().replace('\\', '/');
        if (trimmed.isEmpty()) {
            return "";
        }
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    private static InputStream openResource(String resourcePath) {
        String path = normalizeResourcePath(resourcePath);
        if (path == null || path.length() == 0) {
            return null;
        }

        InputStream stream = HelpImageLoader.class.getResourceAsStream(path);
        if (stream != null) {
            return stream;
        }

        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader == null ? null : loader.getResourceAsStream(path.substring(1));
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // Best-effort resource cleanup only.
        }
    }
}
