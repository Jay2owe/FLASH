package flash.pipeline.ui;

import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.attributes.ViewBox;
import com.github.weisj.jsvg.parser.SVGLoader;

import javax.swing.Icon;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public final class FlashIcons {

    private static final SVGLoader LOADER = new SVGLoader();
    private static final Map<String, Icon> CACHE = new HashMap<String, Icon>();

    private FlashIcons() {}

    private static final Color DEFAULT_ACTION_COLOR = FlashTheme.TEXT_PRIMARY;
    private static final int DEFAULT_ACTION_SIZE = FlashTheme.ICON_ACTION_SIZE;

    public static Icon chevronRight(int size, Color color) { return load("chevron-right", size, color); }
    public static Icon chevronDown(int size, Color color)  { return load("chevron-down",  size, color); }
    public static Icon save(int size, Color color)         { return load("save",          size, color); }
    public static Icon importFile(int size, Color color)   { return load("import",        size, color); }
    public static Icon folderOpen(int size, Color color)   { return load("folder-open",   size, color); }
    public static Icon refresh(int size, Color color)      { return load("refresh",       size, color); }
    public static Icon build(int size, Color color)        { return load("build",         size, color); }
    public static Icon record(int size, Color color)       { return load("record",        size, color); }
    public static Icon play(int size, Color color)         { return load("play",          size, color); }
    public static Icon wand(int size, Color color)         { return load("wand",          size, color); }
    public static Icon check(int size, Color color)        { return load("check",         size, color); }
    public static Icon closeX(int size, Color color)       { return load("close-x",       size, color); }
    public static Icon sliders(int size, Color color)      { return load("sliders",       size, color); }
    public static Icon tags(int size, Color color)         { return load("tags",          size, color); }
    public static Icon palette(int size, Color color)      { return load("palette",       size, color); }
    public static Icon ruler(int size, Color color)        { return load("ruler-2",       size, color); }
    public static Icon sun(int size, Color color)          { return load("sun",           size, color); }
    public static Icon stack(int size, Color color)        { return load("stack-2",       size, color); }
    public static Icon microscope(int size, Color color)   { return load("microscope",    size, color); }
    public static Icon fileExport(int size, Color color)   { return load("file-export",   size, color); }
    public static Icon bolt(int size, Color color)         { return load("bolt",          size, color); }
    public static Icon chartBar(int size, Color color)     { return load("chart-bar",     size, color); }
    public static Icon settings(int size, Color color)     { return load("settings",      size, color); }

    public static Icon save()        { return save(DEFAULT_ACTION_SIZE, DEFAULT_ACTION_COLOR); }
    public static Icon folderOpen()  { return folderOpen(DEFAULT_ACTION_SIZE, DEFAULT_ACTION_COLOR); }
    public static Icon refresh()     { return refresh(DEFAULT_ACTION_SIZE, DEFAULT_ACTION_COLOR); }
    public static Icon importFile()  { return importFile(DEFAULT_ACTION_SIZE, DEFAULT_ACTION_COLOR); }
    public static Icon build()       { return build(DEFAULT_ACTION_SIZE, DEFAULT_ACTION_COLOR); }
    public static Icon record()      { return record(DEFAULT_ACTION_SIZE, DEFAULT_ACTION_COLOR); }
    public static Icon play()        { return play(DEFAULT_ACTION_SIZE, DEFAULT_ACTION_COLOR); }
    public static Icon wand()        { return wand(DEFAULT_ACTION_SIZE, DEFAULT_ACTION_COLOR); }
    public static Icon check()       { return check(DEFAULT_ACTION_SIZE, DEFAULT_ACTION_COLOR); }
    public static Icon closeX()      { return closeX(FlashTheme.ICON_SMALL_SIZE, FlashTheme.TEXT_DISABLED); }
    public static Icon sliders()     { return sliders(DEFAULT_ACTION_SIZE, DEFAULT_ACTION_COLOR); }

    /** 18 px section-header icon in slate-blue (H2 colour). */
    public static Icon section(String op) {
        Color c = FlashTheme.TEXT_HEADER;
        int size = FlashTheme.ICON_SECTION_SIZE;
        if (op.equals("tags"))       return load("tags", size, c);
        if (op.equals("palette"))    return load("palette", size, c);
        if (op.equals("ruler"))      return load("ruler-2", size, c);
        if (op.equals("sun"))        return load("sun", size, c);
        if (op.equals("stack"))      return load("stack-2", size, c);
        if (op.equals("microscope")) return load("microscope", size, c);
        if (op.equals("settings"))   return load("settings", size, c);
        if (op.equals("sliders"))    return load("sliders", size, c);
        return load(op, size, c);
    }

    /** Phase-chip icon. White for active phase, slate-blue for inactive. */
    public static Icon phaseChip(String op, boolean active) {
        Color c = active ? FlashTheme.TEXT_ON_DARK : FlashTheme.TEXT_HEADER;
        int size = FlashTheme.ICON_PHASE_SIZE;
        if (op.equals("settings"))   return load("settings", size, c);
        if (op.equals("microscope")) return load("microscope", size, c);
        if (op.equals("file-export"))return load("file-export", size, c);
        return load(op, size, c);
    }

    /**
     * Returns the FLASH brand mark as a BufferedImage for JDialog.setIconImage.
     * Placeholder uses Tabler 'bolt' in FLASH amber until a real mark is commissioned.
     */
    public static java.awt.image.BufferedImage brandImage(int size) {
        try {
            java.net.URL url = FlashIcons.class.getResource("/flash/icons/bolt.svg");
            if (url == null) return null;
            String svgText = readAll(url);
            String hex = String.format("#%02x%02x%02x",
                    FlashTheme.WARNING_BORDER.getRed(),
                    FlashTheme.WARNING_BORDER.getGreen(),
                    FlashTheme.WARNING_BORDER.getBlue());
            svgText = svgText
                    .replace("stroke=\"currentColor\"", "stroke=\"" + hex + "\"")
                    .replace("fill=\"currentColor\"", "fill=\"" + hex + "\"");
            java.io.ByteArrayInputStream bin = new java.io.ByteArrayInputStream(svgText.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            com.github.weisj.jsvg.SVGDocument doc = LOADER.load(bin);
            if (doc == null) return null;
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = img.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            doc.render(null, g, new com.github.weisj.jsvg.attributes.ViewBox(0, 0, size, size));
            g.dispose();
            return img;
        } catch (Exception ex) {
            return null;
        }
    }

    /** Apply an icon + standard 6 px text gap to a button. No-op if icon is null. */
    public static void apply(javax.swing.JButton button, Icon icon) {
        if (button == null || icon == null) return;
        button.setIcon(icon);
        button.setIconTextGap(6);
    }

    private static Icon load(String name, int size, Color color) {
        String key = name + "@" + size + "@" + Integer.toHexString(color.getRGB());
        Icon cached = CACHE.get(key);
        if (cached != null) return cached;
        Icon icon = renderToIcon(name, size, color);
        if (icon != null) CACHE.put(key, icon);
        return icon;
    }

    private static Icon renderToIcon(String name, int size, Color color) {
        String resourcePath = "/flash/icons/" + name + ".svg";
        URL url = FlashIcons.class.getResource(resourcePath);
        if (url == null) return null;
        try {
            String svgText = readAll(url);
            String hex = String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
            svgText = svgText
                    .replace("stroke=\"currentColor\"", "stroke=\"" + hex + "\"")
                    .replace("fill=\"currentColor\"", "fill=\"" + hex + "\"");
            ByteArrayInputStream bin = new ByteArrayInputStream(svgText.getBytes(StandardCharsets.UTF_8));
            SVGDocument doc = LOADER.load(bin);
            if (doc == null) return null;
            BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            doc.render(null, g, new ViewBox(0, 0, size, size));
            g.dispose();
            return new BufferedImageIcon(img);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String readAll(URL url) throws IOException {
        try (InputStream in = url.openStream()) {
            byte[] buf = new byte[4096];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    private static final class BufferedImageIcon implements Icon {
        private final BufferedImage img;
        BufferedImageIcon(BufferedImage img) { this.img = img; }
        @Override public void paintIcon(Component c, Graphics g, int x, int y) { g.drawImage(img, x, y, null); }
        @Override public int getIconWidth()  { return img.getWidth(); }
        @Override public int getIconHeight() { return img.getHeight(); }
    }
}
