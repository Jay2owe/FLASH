package flash.pipeline.ui.demo;

import flash.pipeline.ui.FlashTheme;

import ij.plugin.PlugIn;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.AbstractBorder;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Desktop;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

public final class FlashStyleLab implements PlugIn {

    public static void main(String[] args) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() { showAsFrame(); }
        });
    }

    @Override
    public void run(String arg) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() { showAsFrame(); }
        });
    }

    private static void showAsFrame() {
        JFrame frame = new JFrame("FLASH Style Lab");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setContentPane(buildContent());
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private static JComponent buildContent() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.setBorder(new EmptyBorder(8, 8, 8, 8));
        tabs.addTab("1 · Typography", new TypographyTab().build());
        tabs.addTab("2 · Cards", new CardsTab().build());
        tabs.addTab("3 · Accents", new AccentsTab().build());
        tabs.addTab("4 · Icons", new IconsTab().build());
        tabs.addTab("ℹ︎ Notes", buildNotes());

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(FlashTheme.SURFACE);
        root.add(buildHeader(), BorderLayout.NORTH);
        root.add(tabs, BorderLayout.CENTER);
        root.add(buildFooter(), BorderLayout.SOUTH);
        root.setPreferredSize(new Dimension(1180, 760));
        return root;
    }

    private static JComponent buildHeader() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(FlashTheme.SURFACE);
        p.setBorder(new EmptyBorder(12, 16, 8, 16));

        JLabel title = new JLabel("FLASH Style Lab");
        title.setFont(FlashTheme.h1());
        title.setForeground(FlashTheme.TEXT_HEADER);

        JLabel sub = new JLabel("side-by-side comparisons for setup-analysis UI polish");
        sub.setFont(FlashTheme.captionItalic());
        sub.setForeground(FlashTheme.TEXT_MUTED);

        JPanel left = new JPanel();
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.setOpaque(false);
        left.add(title);
        left.add(Box.createVerticalStrut(2));
        left.add(sub);

        p.add(left, BorderLayout.WEST);
        return p;
    }

    private static JComponent buildFooter() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(FlashTheme.SURFACE);
        p.setBorder(new EmptyBorder(8, 16, 12, 16));
        String laf = UIManager.getLookAndFeel() == null ? "(none)" : UIManager.getLookAndFeel().getName();
        boolean flat = laf.toLowerCase().contains("flat");
        JLabel lafLabel = new JLabel("Look-and-Feel: " + laf + (flat ? "  (FlatLaf active)" : "  (default L&F — try Edit → Options → Look and Feel… → FlatLaf in Fiji to see the difference)"));
        lafLabel.setFont(FlashTheme.caption());
        lafLabel.setForeground(FlashTheme.TEXT_MUTED);
        p.add(lafLabel, BorderLayout.WEST);
        return p;
    }

    private static JComponent buildNotes() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(FlashTheme.SURFACE_RAISED);
        p.setBorder(new EmptyBorder(20, 24, 20, 24));

        StringBuilder sb = new StringBuilder();
        sb.append("<html><body style='font-family:sans-serif; font-size:11pt; color:#333;'>");
        sb.append("<h2 style='color:#37474F'>How to read this lab</h2>");
        sb.append("<p>Each tab shows the <b>same content</b> rendered in different ways so you can pick what you actually want. ");
        sb.append("Nothing here is wired into FLASH yet — these are evaluation surfaces only.</p>");
        sb.append("<ul>");
        sb.append("<li><b>Typography</b> — Compact (28px rows, hierarchy via weight + colour) vs Standard (32px rows, hierarchy via size). The wizard has many fields — Compact preserves density.</li>");
        sb.append("<li><b>Cards</b> — Flat (current) vs Bordered (1px line, rounded) vs Elevated (border + drop shadow on white surface).</li>");
        sb.append("<li><b>Accents</b> — Current FLASH pastel green/red vs Slate vs GitHub-style green vs Blue. Click each button to feel the hover/pressed state.</li>");
        sb.append("<li><b>Icons</b> — Tabler / Lucide / Phosphor / Material side-by-side for ops we'd actually use.</li>");
        sb.append("</ul>");
        sb.append("<p style='margin-top:16px'><b>To compare with FlatLaf active:</b> in Fiji go to <i>Edit → Options → Look and Feel…</i> and pick a FlatLaf option, then re-launch this lab. The footer will tell you which L&amp;F is currently active.</p>");
        sb.append("</body></html>");
        JLabel content = new JLabel(sb.toString());
        content.setVerticalAlignment(SwingConstants.TOP);
        p.add(content, BorderLayout.CENTER);
        return new JScrollPane(p);
    }

    // ============================================================
    // Tab 1 — Typography
    // ============================================================

    static final class TypographyTab {

        JComponent build() {
            JPanel root = new JPanel(new GridLayout(1, 2, 16, 0));
            root.setBackground(FlashTheme.SURFACE);
            root.setBorder(new EmptyBorder(16, 16, 16, 16));
            root.add(buildVariant("Compact",
                    "28 px rows · 12 pt body · 11 pt caption · hierarchy via weight + colour",
                    /*rowH*/ 28, /*headerSize*/ 13.5f, /*labelSize*/ 12f, /*captionSize*/ 11f,
                    /*rowGap*/ 4, /*sectionGap*/ 16, /*showCaptions*/ false));
            root.add(buildVariant("Standard",
                    "32 px rows · 13 pt body · 12 pt caption · hierarchy via size",
                    /*rowH*/ 32, /*headerSize*/ 15f, /*labelSize*/ 13f, /*captionSize*/ 12f,
                    /*rowGap*/ 8, /*sectionGap*/ 24, /*showCaptions*/ true));
            return new JScrollPane(root);
        }

        private JComponent buildVariant(String name, String spec,
                                        int rowH, float headerSize, float labelSize, float captionSize,
                                        int rowGap, int sectionGap, boolean showCaptions) {
            JPanel container = new JPanel();
            container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));
            container.setBackground(FlashTheme.SURFACE_RAISED);
            container.setBorder(new CompoundBorder(
                    new LineBorder(FlashTheme.BORDER, 1),
                    new EmptyBorder(16, 20, 20, 20)));

            JLabel variant = new JLabel(name);
            variant.setFont(FlashTheme.h1());
            variant.setForeground(FlashTheme.TEXT_HEADER);
            variant.setAlignmentX(Component.LEFT_ALIGNMENT);

            JLabel specLabel = new JLabel(spec);
            specLabel.setFont(FlashTheme.captionItalic());
            specLabel.setForeground(FlashTheme.TEXT_MUTED);
            specLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            specLabel.setBorder(new EmptyBorder(2, 0, 16, 0));

            container.add(variant);
            container.add(specLabel);

            // Section 1: Channel Identities
            container.add(sectionHeader("Channel Identities", headerSize));
            for (int i = 0; i < 4; i++) {
                container.add(channelRow(i, rowH, labelSize, captionSize, showCaptions));
                if (i < 3) container.add(Box.createVerticalStrut(rowGap));
            }
            container.add(Box.createVerticalStrut(sectionGap));

            // Section 2: Particle Sizes (denser — shows hierarchy in a different context)
            container.add(sectionHeader("Particle Sizes", headerSize));
            for (int i = 0; i < 4; i++) {
                container.add(particleRow(i, rowH, labelSize));
                if (i < 3) container.add(Box.createVerticalStrut(rowGap));
            }

            container.add(Box.createVerticalGlue());
            return container;
        }

        private JComponent sectionHeader(String text, float size) {
            JPanel p = new JPanel();
            p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
            p.setOpaque(false);
            p.setAlignmentX(Component.LEFT_ALIGNMENT);
            JLabel l = new JLabel(text);
            l.setFont(FlashTheme.body().deriveFont(Font.BOLD, size));
            l.setForeground(FlashTheme.TEXT_HEADER);
            l.setAlignmentX(Component.LEFT_ALIGNMENT);
            JSeparator sep = new JSeparator();
            sep.setForeground(FlashTheme.BORDER);
            sep.setAlignmentX(Component.LEFT_ALIGNMENT);
            p.add(l);
            p.add(Box.createVerticalStrut(4));
            p.add(sep);
            p.add(Box.createVerticalStrut(8));
            return p;
        }

        private JComponent channelRow(int i, int rowH, float labelSize, float captionSize, boolean showCaption) {
            String[] markers = {"DAPI", "Iba1", "GFAP", "GFP"};
            String[] luts = {"Grays", "Green", "Cyan", "Magenta"};
            Color[] swatches = {Color.WHITE, new Color(76, 175, 80), new Color(0, 188, 212), new Color(216, 27, 96)};
            String[] desc = {"nuclear marker", "microglial marker", "astrocytic marker", "reporter"};

            JPanel row = new JPanel(new GridBagLayout());
            row.setOpaque(false);
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, showCaption ? rowH + 18 : rowH));

            GridBagConstraints g = new GridBagConstraints();
            g.gridy = 0;
            g.insets = new Insets(0, 0, 0, 8);
            g.anchor = GridBagConstraints.WEST;

            JPanel swatch = new JPanel();
            swatch.setBackground(swatches[i]);
            swatch.setBorder(new LineBorder(FlashTheme.BORDER_STRONG, 1));
            swatch.setPreferredSize(new Dimension(14, 14));
            g.gridx = 0; row.add(swatch, g);

            JLabel chan = new JLabel("Channel " + (i + 1));
            chan.setFont(FlashTheme.body().deriveFont(Font.BOLD, labelSize));
            chan.setForeground(FlashTheme.TEXT_PRIMARY);
            g.gridx = 1; row.add(chan, g);

            JTextField field = new JTextField(markers[i], 10);
            field.setFont(FlashTheme.body().deriveFont(labelSize));
            g.gridx = 2; row.add(field, g);

            JComboBox<String> lut = new JComboBox<String>(new String[]{"Grays", "Red", "Green", "Blue", "Cyan", "Magenta", "Yellow"});
            lut.setSelectedItem(luts[i]);
            lut.setFont(FlashTheme.body().deriveFont(labelSize));
            lut.setPreferredSize(new Dimension(110, rowH - 4));
            g.gridx = 3; row.add(lut, g);

            g.gridx = 4; g.weightx = 1; row.add(Box.createHorizontalGlue(), g);

            if (showCaption) {
                JPanel wrap = new JPanel();
                wrap.setLayout(new BoxLayout(wrap, BoxLayout.Y_AXIS));
                wrap.setOpaque(false);
                wrap.setAlignmentX(Component.LEFT_ALIGNMENT);
                wrap.add(row);
                JLabel cap = new JLabel("    " + desc[i]);
                cap.setFont(FlashTheme.body().deriveFont(Font.ITALIC, captionSize));
                cap.setForeground(FlashTheme.TEXT_MUTED);
                cap.setAlignmentX(Component.LEFT_ALIGNMENT);
                cap.setBorder(new EmptyBorder(2, 24, 0, 0));
                wrap.add(cap);
                return wrap;
            }
            return row;
        }

        private JComponent particleRow(int i, int rowH, float labelSize) {
            String[] markers = {"DAPI", "Iba1", "GFAP", "GFP"};
            int[] mins = {50, 30, 80, 20};
            int[] maxs = {5000, 2000, 8000, 1500};

            JPanel row = new JPanel(new GridBagLayout());
            row.setOpaque(false);
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, rowH));

            GridBagConstraints g = new GridBagConstraints();
            g.gridy = 0;
            g.insets = new Insets(0, 0, 0, 8);
            g.anchor = GridBagConstraints.WEST;

            JLabel chan = new JLabel("Channel " + (i + 1));
            chan.setFont(FlashTheme.body().deriveFont(Font.BOLD, labelSize));
            chan.setForeground(FlashTheme.TEXT_PRIMARY);
            chan.setPreferredSize(new Dimension(80, rowH - 4));
            g.gridx = 0; row.add(chan, g);

            JLabel name = new JLabel(markers[i]);
            name.setFont(FlashTheme.body().deriveFont(labelSize));
            name.setForeground(FlashTheme.TEXT_MUTED);
            name.setPreferredSize(new Dimension(50, rowH - 4));
            g.gridx = 1; row.add(name, g);

            JTextField minF = new JTextField(String.valueOf(mins[i]), 5);
            minF.setFont(FlashTheme.mono(labelSize));
            minF.setHorizontalAlignment(SwingConstants.RIGHT);
            g.gridx = 2; row.add(minF, g);

            JLabel unit1 = new JLabel("voxels min");
            unit1.setFont(FlashTheme.body().deriveFont(labelSize));
            unit1.setForeground(FlashTheme.TEXT_MUTED);
            g.gridx = 3; row.add(unit1, g);

            JTextField maxF = new JTextField(String.valueOf(maxs[i]), 6);
            maxF.setFont(FlashTheme.mono(labelSize));
            maxF.setHorizontalAlignment(SwingConstants.RIGHT);
            g.gridx = 4; row.add(maxF, g);

            JLabel unit2 = new JLabel("voxels max");
            unit2.setFont(FlashTheme.body().deriveFont(labelSize));
            unit2.setForeground(FlashTheme.TEXT_MUTED);
            g.gridx = 5; row.add(unit2, g);

            g.gridx = 6; g.weightx = 1; row.add(Box.createHorizontalGlue(), g);

            return row;
        }
    }

    // ============================================================
    // Tab 2 — Cards
    // ============================================================

    static final class CardsTab {

        JComponent build() {
            JPanel root = new JPanel(new GridLayout(1, 3, 16, 0));
            root.setBackground(FlashTheme.SURFACE);
            root.setBorder(new EmptyBorder(16, 16, 16, 16));
            root.add(wrap("Flat",
                    "current style — JLabel header + JSeparator + content, no container",
                    flatCard()));
            root.add(wrap("Bordered",
                    "1 px line border, 6 px arc, 12 px insets, white surface",
                    borderedCard()));
            root.add(wrap("Elevated",
                    "1 px border + 4-layer alpha drop shadow, 16 px insets, white surface",
                    elevatedCard()));
            return new JScrollPane(root);
        }

        private JComponent wrap(String title, String spec, JComponent card) {
            JPanel p = new JPanel();
            p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
            p.setBackground(FlashTheme.SURFACE);
            JLabel t = new JLabel(title);
            t.setFont(FlashTheme.h1());
            t.setForeground(FlashTheme.TEXT_HEADER);
            t.setAlignmentX(Component.LEFT_ALIGNMENT);
            JLabel s = new JLabel(spec);
            s.setFont(FlashTheme.captionItalic());
            s.setForeground(FlashTheme.TEXT_MUTED);
            s.setAlignmentX(Component.LEFT_ALIGNMENT);
            s.setBorder(new EmptyBorder(2, 0, 12, 0));
            card.setAlignmentX(Component.LEFT_ALIGNMENT);
            p.add(t);
            p.add(s);
            p.add(card);
            p.add(Box.createVerticalGlue());
            return p;
        }

        private JComponent flatCard() {
            JPanel content = new JPanel();
            content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
            content.setBackground(FlashTheme.SURFACE);

            JLabel header = new JLabel("Particle Sizes");
            header.setFont(FlashTheme.h2());
            header.setForeground(FlashTheme.TEXT_HEADER);
            header.setAlignmentX(Component.LEFT_ALIGNMENT);
            JSeparator sep = new JSeparator();
            sep.setForeground(FlashTheme.BORDER);
            sep.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(header);
            content.add(Box.createVerticalStrut(4));
            content.add(sep);
            content.add(Box.createVerticalStrut(8));
            for (int i = 0; i < 3; i++) {
                content.add(sampleRow(i));
                content.add(Box.createVerticalStrut(6));
            }
            return content;
        }

        private JComponent borderedCard() {
            JPanel content = new JPanel();
            content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
            content.setBackground(FlashTheme.SURFACE_RAISED);
            content.setBorder(new CompoundBorder(
                    new RoundedLineBorder(FlashTheme.BORDER, 1, 8),
                    new EmptyBorder(12, 14, 14, 14)));

            JLabel header = new JLabel("Particle Sizes");
            header.setFont(FlashTheme.h2());
            header.setForeground(FlashTheme.TEXT_HEADER);
            header.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(header);
            content.add(Box.createVerticalStrut(8));
            for (int i = 0; i < 3; i++) {
                content.add(sampleRow(i));
                content.add(Box.createVerticalStrut(6));
            }
            return content;
        }

        private JComponent elevatedCard() {
            JPanel content = new JPanel();
            content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
            content.setBackground(FlashTheme.SURFACE_RAISED);
            content.setBorder(new CompoundBorder(
                    new ShadowBorder(8, 4),
                    new EmptyBorder(16, 18, 18, 18)));

            JLabel header = new JLabel("Particle Sizes");
            header.setFont(FlashTheme.h2());
            header.setForeground(FlashTheme.TEXT_HEADER);
            header.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(header);
            content.add(Box.createVerticalStrut(10));
            for (int i = 0; i < 3; i++) {
                content.add(sampleRow(i));
                content.add(Box.createVerticalStrut(6));
            }
            return content;
        }

        private JComponent sampleRow(int i) {
            String[] markers = {"DAPI", "Iba1", "GFAP"};
            int[] mins = {50, 30, 80};
            int[] maxs = {5000, 2000, 8000};

            JPanel row = new JPanel(new GridBagLayout());
            row.setOpaque(false);
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));

            GridBagConstraints g = new GridBagConstraints();
            g.gridy = 0;
            g.insets = new Insets(0, 0, 0, 6);
            g.anchor = GridBagConstraints.WEST;

            JLabel chan = new JLabel("Ch" + (i + 1) + " · " + markers[i]);
            chan.setFont(FlashTheme.body());
            chan.setForeground(FlashTheme.TEXT_PRIMARY);
            chan.setPreferredSize(new Dimension(110, 24));
            g.gridx = 0; row.add(chan, g);

            JTextField minF = new JTextField(String.valueOf(mins[i]), 4);
            minF.setFont(FlashTheme.mono(12f));
            minF.setHorizontalAlignment(SwingConstants.RIGHT);
            g.gridx = 1; row.add(minF, g);

            JLabel to = new JLabel("→");
            to.setForeground(FlashTheme.TEXT_MUTED);
            g.gridx = 2; row.add(to, g);

            JTextField maxF = new JTextField(String.valueOf(maxs[i]), 5);
            maxF.setFont(FlashTheme.mono(12f));
            maxF.setHorizontalAlignment(SwingConstants.RIGHT);
            g.gridx = 3; row.add(maxF, g);

            JLabel unit = new JLabel("voxels");
            unit.setFont(FlashTheme.caption());
            unit.setForeground(FlashTheme.TEXT_MUTED);
            g.gridx = 4; row.add(unit, g);

            g.gridx = 5; g.weightx = 1; row.add(Box.createHorizontalGlue(), g);
            return row;
        }
    }

    // ============================================================
    // Tab 3 — Accents (button styles)
    // ============================================================

    static final class AccentsTab {

        JComponent build() {
            JPanel root = new JPanel();
            root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
            root.setBackground(FlashTheme.SURFACE);
            root.setBorder(new EmptyBorder(16, 16, 16, 16));

            JLabel intro = new JLabel("Click the buttons to feel hover and pressed states. The same wizard footer rendered four ways:");
            intro.setFont(FlashTheme.caption());
            intro.setForeground(FlashTheme.TEXT_MUTED);
            intro.setAlignmentX(Component.LEFT_ALIGNMENT);
            intro.setBorder(new EmptyBorder(0, 4, 12, 0));
            root.add(intro);

            root.add(variant("Current FLASH",
                    "pastel-green primary, pastel-red cancel — the look you have now",
                    new Accent(new Color(235, 248, 239), new Color(37, 103, 62), new Color(111, 173, 130)),
                    new Accent(new Color(252, 240, 240), new Color(137, 44, 44), new Color(196, 108, 108))));

            root.add(Box.createVerticalStrut(12));
            root.add(variant("Slate",
                    "single muted accent for primary, neutral grey for cancel — the most conservative shift",
                    new Accent(new Color(55, 71, 79), Color.WHITE, new Color(38, 50, 56)),
                    new Accent(new Color(245, 245, 245), new Color(60, 60, 60), new Color(195, 200, 205))));

            root.add(Box.createVerticalStrut(12));
            root.add(variant("Green (GitHub-style)",
                    "saturated green primary — same colour family as current, just more decisive",
                    new Accent(new Color(31, 136, 61), Color.WHITE, new Color(26, 110, 50)),
                    new Accent(new Color(245, 245, 245), new Color(60, 60, 60), new Color(195, 200, 205))));

            root.add(Box.createVerticalStrut(12));
            root.add(variant("Blue",
                    "blue primary — strongest break from current colour, highest contrast",
                    new Accent(new Color(9, 105, 218), Color.WHITE, new Color(7, 89, 187)),
                    new Accent(new Color(245, 245, 245), new Color(60, 60, 60), new Color(195, 200, 205))));

            root.add(Box.createVerticalGlue());
            return new JScrollPane(root);
        }

        private JComponent variant(String name, String spec, Accent primary, Accent cancel) {
            JPanel container = new JPanel();
            container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));
            container.setBackground(FlashTheme.SURFACE_RAISED);
            container.setBorder(new CompoundBorder(
                    new LineBorder(FlashTheme.BORDER, 1),
                    new EmptyBorder(14, 18, 14, 18)));
            container.setAlignmentX(Component.LEFT_ALIGNMENT);
            container.setMaximumSize(new Dimension(Integer.MAX_VALUE, 130));

            JLabel t = new JLabel(name);
            t.setFont(FlashTheme.h2());
            t.setForeground(FlashTheme.TEXT_HEADER);
            t.setAlignmentX(Component.LEFT_ALIGNMENT);

            JLabel s = new JLabel(spec);
            s.setFont(FlashTheme.captionItalic());
            s.setForeground(FlashTheme.TEXT_MUTED);
            s.setAlignmentX(Component.LEFT_ALIGNMENT);
            s.setBorder(new EmptyBorder(2, 0, 12, 0));

            container.add(t);
            container.add(s);

            JPanel buttons = new JPanel(new GridBagLayout());
            buttons.setOpaque(false);
            buttons.setAlignmentX(Component.LEFT_ALIGNMENT);

            GridBagConstraints g = new GridBagConstraints();
            g.gridy = 0;
            g.insets = new Insets(0, 0, 0, 8);
            g.anchor = GridBagConstraints.WEST;

            g.gridx = 0; buttons.add(makeAccentButton("← Back", cancel, false), g);
            g.gridx = 1; g.weightx = 1; buttons.add(Box.createHorizontalGlue(), g);
            g.gridx = 2; g.weightx = 0; buttons.add(makeAccentButton("Cancel", cancel, false), g);
            g.gridx = 3; buttons.add(makeAccentButton("Lock in & Next →", primary, true), g);

            container.add(buttons);
            return container;
        }

        private JButton makeAccentButton(String text, Accent a, boolean primary) {
            AccentButton b = new AccentButton(text, a);
            b.setFont(FlashTheme.body().deriveFont(primary ? Font.BOLD : Font.PLAIN));
            return b;
        }

        static final class Accent {
            final Color bg, fg, border;
            Accent(Color bg, Color fg, Color border) {
                this.bg = bg; this.fg = fg; this.border = border;
            }
        }

        static final class AccentButton extends JButton {
            private boolean hover;
            private boolean pressed;
            private final Accent a;

            AccentButton(String text, Accent a) {
                super(text);
                this.a = a;
                setContentAreaFilled(false);
                setBorderPainted(false);
                setFocusPainted(false);
                setOpaque(false);
                setForeground(a.fg);
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                setMargin(new Insets(8, 16, 8, 16));
                addMouseListener(new MouseAdapter() {
                    @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                    @Override public void mouseExited(MouseEvent e) { hover = false; pressed = false; repaint(); }
                    @Override public void mousePressed(MouseEvent e) { pressed = true; repaint(); }
                    @Override public void mouseReleased(MouseEvent e) { pressed = false; repaint(); }
                });
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth(), h = getHeight();
                Color bg = a.bg;
                if (pressed) bg = darken(bg, 0.10f);
                else if (hover) bg = lighten(bg, 0.06f);
                g2.setColor(bg);
                g2.fill(new RoundRectangle2D.Float(0, 0, w, h, 8, 8));
                g2.setColor(a.border);
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, 8, 8));
                if (hasFocus()) {
                    g2.setColor(new Color(a.border.getRed(), a.border.getGreen(), a.border.getBlue(), 80));
                    g2.draw(new RoundRectangle2D.Float(-1.5f, -1.5f, w + 2, h + 2, 10, 10));
                }
                g2.dispose();
                super.paintComponent(g);
            }

            private static Color darken(Color c, float amt) {
                int r = Math.max(0, Math.round(c.getRed() * (1 - amt)));
                int gg = Math.max(0, Math.round(c.getGreen() * (1 - amt)));
                int b = Math.max(0, Math.round(c.getBlue() * (1 - amt)));
                return new Color(r, gg, b);
            }

            private static Color lighten(Color c, float amt) {
                int r = Math.min(255, Math.round(c.getRed() + (255 - c.getRed()) * amt));
                int gg = Math.min(255, Math.round(c.getGreen() + (255 - c.getGreen()) * amt));
                int b = Math.min(255, Math.round(c.getBlue() + (255 - c.getBlue()) * amt));
                return new Color(r, gg, b);
            }
        }
    }

    // ============================================================
    // Tab 4 — Icons (placeholder; populated after SVGs are fetched)
    // ============================================================

    static final class IconsTab {

        private static final String[] OPS = {
                "layers", "eye", "sliders", "scissors", "save", "settings",
                "help", "info", "chevron-right", "chevron-down", "plus", "trash"
        };
        private static final String[] SETS = {"tabler", "lucide", "phosphor", "material"};
        private static final String[] CURRENT_FLASH = {
                "—",   // layers
                "—",   // eye
                "—",   // sliders
                "—",   // scissors
                "—",   // save
                "—",   // settings
                "?",   // help (HelpButton uses literal "?")
                "—",   // info
                "▸",   // chevron-right (PipelineDialog beginAdvancedSection)
                "▾",   // chevron-down
                "[+]", // plus (CollapsibleSection)
                "—"    // trash
        };

        JComponent build() {
            JPanel root = new JPanel(new BorderLayout());
            root.setBackground(FlashTheme.SURFACE);
            root.setBorder(new EmptyBorder(16, 16, 16, 16));

            JPanel header = new JPanel(new BorderLayout());
            header.setOpaque(false);
            header.setBorder(new EmptyBorder(0, 0, 12, 0));

            JLabel intro = new JLabel("<html><div style='font-family:sans-serif; color:#37474F;'>"
                    + "<b>Icon set comparison.</b> Same 12 ops, four sets side-by-side, plus what FLASH currently uses (mostly ASCII or nothing). "
                    + "Click below to open a browser-rendered comparison — SVGs render natively in browsers without bundling a Swing SVG library.</div></html>");
            intro.setBorder(new EmptyBorder(0, 4, 12, 0));

            JButton openBtn = new AccentsTab.AccentButton(
                    "Open icon comparison in browser →",
                    new AccentsTab.Accent(new Color(55, 71, 79), Color.WHITE, new Color(38, 50, 56)));
            openBtn.setFont(FlashTheme.body().deriveFont(Font.BOLD));
            openBtn.addActionListener(new ActionListener() {
                @Override public void actionPerformed(ActionEvent e) { launchBrowser(); }
            });

            header.add(intro, BorderLayout.CENTER);
            header.add(openBtn, BorderLayout.EAST);
            root.add(header, BorderLayout.NORTH);

            // In-Swing summary grid (text-only — names of icons covered)
            JPanel grid = new JPanel(new GridBagLayout());
            grid.setBackground(FlashTheme.SURFACE_RAISED);
            grid.setBorder(new CompoundBorder(
                    new LineBorder(FlashTheme.BORDER, 1),
                    new EmptyBorder(12, 16, 12, 16)));

            GridBagConstraints g = new GridBagConstraints();
            g.gridy = 0;
            g.insets = new Insets(4, 8, 4, 8);
            g.anchor = GridBagConstraints.WEST;
            g.fill = GridBagConstraints.HORIZONTAL;

            String[] headers = {"Op", "Tabler", "Lucide", "Phosphor", "Material", "Current FLASH"};
            for (int c = 0; c < headers.length; c++) {
                g.gridx = c; g.weightx = c == 0 ? 0.5 : 1.0;
                JLabel h = new JLabel(headers[c]);
                h.setFont(FlashTheme.body().deriveFont(Font.BOLD));
                h.setForeground(FlashTheme.TEXT_HEADER);
                grid.add(h, g);
            }

            for (int r = 0; r < OPS.length; r++) {
                g.gridy = r + 1;
                String[] cells = {OPS[r], "✓", "✓", "✓", "✓", CURRENT_FLASH[r]};
                for (int c = 0; c < cells.length; c++) {
                    g.gridx = c;
                    JLabel cell = new JLabel(cells[c]);
                    if (c == 0) {
                        cell.setFont(FlashTheme.body().deriveFont(Font.BOLD));
                        cell.setForeground(FlashTheme.TEXT_PRIMARY);
                    } else if (c == cells.length - 1) {
                        cell.setFont(FlashTheme.mono(13f));
                        cell.setForeground(cells[c].equals("—") ? FlashTheme.TEXT_MUTED : FlashTheme.TEXT_PRIMARY);
                    } else {
                        cell.setForeground(new Color(31, 136, 61));
                        cell.setFont(FlashTheme.body().deriveFont(Font.BOLD));
                    }
                    grid.add(cell, g);
                }
            }

            root.add(new JScrollPane(grid), BorderLayout.CENTER);

            JLabel foot = new JLabel("If you want these rendered live inside Swing rather than in a browser, I can bundle jsvg (~600 KB) — say the word.");
            foot.setFont(FlashTheme.captionItalic());
            foot.setForeground(FlashTheme.TEXT_MUTED);
            foot.setBorder(new EmptyBorder(12, 4, 0, 0));
            root.add(foot, BorderLayout.SOUTH);

            return root;
        }

        private void launchBrowser() {
            try {
                File tmp = File.createTempFile("flash-icons-", ".html");
                tmp.deleteOnExit();
                PrintWriter w = new PrintWriter(new FileWriter(tmp));
                w.println(buildHtml());
                w.close();
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().browse(tmp.toURI());
                }
            } catch (Exception ex) {
                JLabel err = new JLabel("Failed to open browser: " + ex.getMessage());
                err.setForeground(FlashTheme.DANGER_FG);
            }
        }

        private String buildHtml() {
            StringBuilder html = new StringBuilder();
            html.append("<!DOCTYPE html><html><head><meta charset='utf-8'><title>FLASH Icon Comparison</title>");
            html.append("<style>");
            html.append("body{font-family:-apple-system,Segoe UI,sans-serif;background:#fafbfc;color:#24292f;padding:32px;margin:0;}");
            html.append("h1{margin:0 0 8px;font-size:22px;color:#37474F;font-weight:600;}");
            html.append("p.sub{margin:0 0 24px;color:#656d76;font-size:13px;}");
            html.append("table{border-collapse:collapse;background:#fff;border:1px solid #e1e4e8;border-radius:8px;overflow:hidden;}");
            html.append("th,td{padding:14px 18px;text-align:center;border-bottom:1px solid #f0f2f4;}");
            html.append("th{font-size:11px;font-weight:600;text-transform:uppercase;letter-spacing:.05em;color:#656d76;background:#f6f8fa;}");
            html.append("th:first-child,td:first-child{text-align:left;font-weight:600;}");
            html.append("td svg{width:24px;height:24px;color:#24292f;display:block;margin:0 auto;}");
            html.append("td.current{font-family:'JetBrains Mono',Consolas,monospace;color:#888;font-size:14px;}");
            html.append("tr:hover{background:#fafbfc;}");
            html.append("h2{margin:48px 0 12px;font-size:14px;color:#37474F;}");
            html.append(".sizes{display:flex;gap:24px;align-items:flex-end;background:#fff;border:1px solid #e1e4e8;border-radius:8px;padding:20px;}");
            html.append(".sizes div{text-align:center;color:#656d76;font-size:11px;}");
            html.append(".sizes svg{display:block;margin:0 auto 8px;color:#24292f;}");
            html.append("</style></head><body>");
            html.append("<h1>FLASH icon set comparison</h1>");
            html.append("<p class='sub'>Same 12 ops, four icon sets, plus what FLASH currently uses. ");
            html.append("All icons rendered at 24×24 px in their native SVG. ");
            html.append("Tabler / Phosphor / Material are stroke-based; Lucide is feather-derived (also stroke); current FLASH uses font glyphs.</p>");
            html.append("<table>");
            html.append("<tr><th>Op</th><th>Tabler</th><th>Lucide</th><th>Phosphor</th><th>Material (rounded)</th><th>Current FLASH</th></tr>");

            for (int r = 0; r < OPS.length; r++) {
                String op = OPS[r];
                html.append("<tr><td>").append(op).append("</td>");
                for (String set : SETS) {
                    html.append("<td>").append(loadSvg(set, op)).append("</td>");
                }
                html.append("<td class='current'>").append(escape(CURRENT_FLASH[r])).append("</td>");
                html.append("</tr>");
            }
            html.append("</table>");

            // Sizes preview using one icon (Tabler eye) at 16/20/24/32/40 px
            html.append("<h2>Same icon at different sizes (Tabler · eye)</h2>");
            html.append("<div class='sizes'>");
            String eyeSvg = loadSvg("tabler", "eye");
            for (int size : new int[]{16, 20, 24, 32, 40}) {
                String resized = eyeSvg.replaceFirst("width=\"1em\"", "width=\"" + size + "\"")
                        .replaceFirst("height=\"1em\"", "height=\"" + size + "\"");
                if (resized.equals(eyeSvg)) {
                    resized = eyeSvg.replaceFirst("<svg ", "<svg width=\"" + size + "\" height=\"" + size + "\" ");
                }
                html.append("<div>").append(resized).append(size).append(" px</div>");
            }
            html.append("</div>");

            html.append("</body></html>");
            return html.toString();
        }

        private String loadSvg(String set, String op) {
            String resource = "/flash/demo/icons/" + set + "/" + op + ".svg";
            try (InputStream in = getClass().getResourceAsStream(resource)) {
                if (in == null) return "<span style='color:#cf222e'>missing</span>";
                BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                return sb.toString();
            } catch (Exception e) {
                return "<span style='color:#cf222e'>error: " + e.getMessage() + "</span>";
            }
        }

        private String escape(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }

    // ============================================================
    // Borders used by the demo
    // ============================================================

    static final class RoundedLineBorder extends AbstractBorder {
        private final Color color;
        private final int thickness;
        private final int arc;

        RoundedLineBorder(Color color, int thickness, int arc) {
            this.color = color; this.thickness = thickness; this.arc = arc;
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            float t = thickness;
            g2.draw(new RoundRectangle2D.Float(x + t / 2f, y + t / 2f, w - t, h - t, arc, arc));
            g2.dispose();
        }

        @Override
        public Insets getBorderInsets(Component c) {
            return new Insets(thickness, thickness, thickness, thickness);
        }
    }

    static final class ShadowBorder extends AbstractBorder {
        private final int arc;
        private final int shadowSize;

        ShadowBorder(int arc, int shadowSize) {
            this.arc = arc; this.shadowSize = shadowSize;
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            for (int i = 0; i < shadowSize; i++) {
                int alpha = Math.max(4, 20 - i * 4);
                g2.setColor(new Color(0, 0, 0, alpha));
                g2.draw(new RoundRectangle2D.Float(
                        x + i, y + i + 1,
                        w - 1 - 2 * i, h - 1 - 2 * i,
                        arc, arc));
            }
            g2.setColor(new Color(225, 228, 232));
            g2.draw(new RoundRectangle2D.Float(x, y, w - 1 - shadowSize, h - 1 - shadowSize, arc, arc));
            g2.dispose();
        }

        @Override
        public Insets getBorderInsets(Component c) {
            return new Insets(1, 1, shadowSize + 1, shadowSize + 1);
        }
    }
}
