package flash.pipeline.ui;

import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.attributes.ViewBox;
import com.github.weisj.jsvg.parser.SVGLoader;

import ij.IJ;
import ij.plugin.PlugIn;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GradientPaint;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * About FLASH dialog. Header brand mark (placeholder bolt icon) + workflow diagram
 * (Setup → Analyse → Export) + version + author info from project metadata.
 * Registered via plugins.config as a Help-menu entry.
 */
public final class AboutFlashDialog implements PlugIn {

    private static final Color HEADER_BG_TOP = FlashTheme.TEXT_HEADER;
    private static final Color HEADER_BG_BOTTOM = new Color(84, 110, 122);
    private static final Color HEADER_FG = FlashTheme.TEXT_ON_DARK;
    private static final Color HEADER_ACCENT = FlashTheme.WARNING_BORDER;
    private static final Color BODY_BG = FlashTheme.SURFACE;
    private static final Color STEP_BG = new Color(250, 251, 252);
    private static final Color STEP_BORDER = new Color(208, 215, 222);
    private static final Color TEXT_PRIMARY = FlashTheme.TEXT_PRIMARY;
    private static final Color TEXT_MUTED = FlashTheme.TEXT_MUTED;

    @Override
    public void run(String arg) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() { show(null); }
        });
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() { show(null); }
        });
    }

    public static void show(java.awt.Window owner) {
        if (GraphicsEnvironment.isHeadless()) {
            IJ.log("About FLASH dialog skipped because the JVM is headless.");
            return;
        }
        JDialog dialog = owner == null
                ? new JDialog((java.awt.Frame) null, "About FLASH", true)
                : new JDialog(owner, "About FLASH", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        BufferedImage brand = FlashIcons.brandImage(32);
        if (brand != null) dialog.setIconImage(brand);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BODY_BG);

        root.add(buildHeader(), BorderLayout.NORTH);
        root.add(buildBody(), BorderLayout.CENTER);
        root.add(buildFooter(dialog), BorderLayout.SOUTH);

        dialog.setContentPane(root);
        dialog.pack();
        dialog.setSize(480, dialog.getHeight());
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    private static JComponent buildHeader() {
        GradientHeader header = new GradientHeader();
        header.setLayout(new GridBagLayout());
        header.setPreferredSize(new Dimension(480, 120));

        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.gridy = 0;
        g.insets = new Insets(0, 0, 0, 0);

        JLabel brand = new JLabel(renderSvgIcon("/flash/icons/bolt.svg", 48, HEADER_ACCENT));
        g.gridx = 0; header.add(brand, g);

        JLabel wordmark = new JLabel("FLASH");
        wordmark.setFont(wordmark.getFont().deriveFont(Font.BOLD, 30f));
        wordmark.setForeground(HEADER_FG);
        wordmark.setBorder(FlashTheme.pad(0, 12, 0, 0));
        g.gridx = 1; header.add(wordmark, g);

        return header;
    }

    private static JComponent buildBody() {
        JPanel body = new JPanel();
        body.setBackground(BODY_BG);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(FlashTheme.pad(20, 24, 16, 24));

        JLabel tagline = new JLabel("The Pipeline for Fluorescence Automated Spatial Histology");
        tagline.setFont(tagline.getFont().deriveFont(Font.PLAIN, 12f));
        tagline.setForeground(TEXT_MUTED);
        tagline.setAlignmentX(Component.CENTER_ALIGNMENT);
        body.add(tagline);

        body.add(Box.createVerticalStrut(20));

        body.add(buildWorkflow());

        body.add(Box.createVerticalStrut(20));

        addRow(body, "Version",      readVersion());
        addRow(body, "Author",       "Jamie Malcolm");
        addRow(body, "Institution",  "UK Dementia Research Institute");
        addRow(body, "License",      "CC0 1.0 Universal");
        addRow(body, "Repository",   "github.com/Jay2owe/FLASH");

        return body;
    }

    private static JComponent buildWorkflow() {
        JPanel wf = new JPanel(new GridBagLayout());
        wf.setBackground(BODY_BG);
        wf.setAlignmentX(Component.CENTER_ALIGNMENT);

        GridBagConstraints g = new GridBagConstraints();
        g.gridy = 0;
        g.fill = GridBagConstraints.NONE;
        g.insets = new Insets(0, 0, 0, 0);

        g.gridx = 0; wf.add(makeStep("settings", "Setup", "channels · thresholds · z-slice"), g);
        g.gridx = 1; wf.add(makeArrow(), g);
        g.gridx = 2; wf.add(makeStep("microscope", "Analyse", "3D objects · intensity · spatial"), g);
        g.gridx = 3; wf.add(makeArrow(), g);
        g.gridx = 4; wf.add(makeStep("file-export", "Export", "Excel · statistics · QC"), g);

        return wf;
    }

    private static JComponent makeStep(String iconName, String title, String sub) {
        JPanel step = new JPanel();
        step.setLayout(new BoxLayout(step, BoxLayout.Y_AXIS));
        step.setBackground(STEP_BG);
        step.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(STEP_BORDER, 1),
                FlashTheme.pad(10)));

        JLabel icon = new JLabel(renderSvgIcon("/flash/icons/" + iconName + ".svg", 28, HEADER_BG_TOP));
        icon.setAlignmentX(Component.CENTER_ALIGNMENT);
        step.add(icon);
        step.add(Box.createVerticalStrut(6));

        JLabel t = new JLabel(title);
        t.setFont(t.getFont().deriveFont(Font.BOLD, 11f));
        t.setForeground(HEADER_BG_TOP);
        t.setAlignmentX(Component.CENTER_ALIGNMENT);
        step.add(t);

        JLabel s = new JLabel("<html><div style='text-align:center; width:90px'>" + sub + "</div></html>");
        s.setFont(s.getFont().deriveFont(Font.PLAIN, 9f));
        s.setForeground(TEXT_MUTED);
        s.setAlignmentX(Component.CENTER_ALIGNMENT);
        step.add(s);

        return step;
    }

    private static JComponent makeArrow() {
        JLabel arrow = new JLabel("→");
        arrow.setFont(arrow.getFont().deriveFont(Font.PLAIN, 18f));
        arrow.setForeground(TEXT_MUTED);
        arrow.setBorder(FlashTheme.pad(0, 8, 0, 8));
        return arrow;
    }

    private static void addRow(JPanel parent, String key, String value) {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));

        JLabel k = new JLabel(key);
        k.setFont(k.getFont().deriveFont(Font.PLAIN, 11f));
        k.setForeground(TEXT_MUTED);
        k.setPreferredSize(new Dimension(110, 18));

        JLabel v = new JLabel(value);
        v.setFont(v.getFont().deriveFont(Font.PLAIN, 11f));
        v.setForeground(TEXT_PRIMARY);

        row.add(k, BorderLayout.WEST);
        row.add(v, BorderLayout.CENTER);
        parent.add(row);
        parent.add(Box.createVerticalStrut(2));
    }

    private static JComponent buildFooter(final JDialog dialog) {
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(BODY_BG);
        footer.setBorder(FlashTheme.pad(12, 24, 16, 24));

        JLabel attribution = new JLabel("Icons: Tabler (MIT) · SVG: jsvg (MIT)");
        attribution.setFont(attribution.getFont().deriveFont(Font.PLAIN, 10f));
        attribution.setForeground(TEXT_MUTED);

        JButton close = new JButton("Close");
        close.addActionListener(e -> dialog.dispose());

        footer.add(attribution, BorderLayout.WEST);
        footer.add(close, BorderLayout.EAST);
        return footer;
    }

    private static String readVersion() {
        Package pkg = AboutFlashDialog.class.getPackage();
        String v = pkg == null ? null : pkg.getImplementationVersion();
        if (v == null || v.isEmpty()) return "4.0.0";
        return v;
    }

    private static javax.swing.Icon renderSvgIcon(String resourcePath, int size, Color color) {
        try {
            URL url = AboutFlashDialog.class.getResource(resourcePath);
            if (url == null) return null;
            String svgText;
            try (InputStream in = url.openStream()) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                svgText = out.toString("UTF-8");
            }
            String hex = String.format(Locale.ROOT, "#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
            svgText = svgText
                    .replace("stroke=\"currentColor\"", "stroke=\"" + hex + "\"")
                    .replace("fill=\"currentColor\"", "fill=\"" + hex + "\"");
            SVGLoader loader = new SVGLoader();
            SVGDocument doc = loader.load(new ByteArrayInputStream(svgText.getBytes(StandardCharsets.UTF_8)));
            if (doc == null) return null;
            BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            doc.render(null, g, new ViewBox(0, 0, size, size));
            g.dispose();
            return new javax.swing.ImageIcon(img);
        } catch (Exception ex) {
            return null;
        }
    }

    /** Custom panel that paints a top-to-bottom slate gradient. */
    private static final class GradientHeader extends JPanel {
        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setPaint(new GradientPaint(0, 0, HEADER_BG_TOP, 0, getHeight(), HEADER_BG_BOTTOM));
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.dispose();
        }
    }
}
