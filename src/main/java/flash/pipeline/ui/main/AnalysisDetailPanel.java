package flash.pipeline.ui.main;

import flash.pipeline.runtime.DependencyId;
import flash.pipeline.runtime.DependencyRegistry;
import flash.pipeline.runtime.DependencySpec;
import flash.pipeline.ui.FlashTheme;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Side panel of the main dialog: what the focused analysis does, its status on
 * this project, where it writes and whether the runtimes it can use are present.
 */
public final class AnalysisDetailPanel extends JPanel {

    /** Actions the panel's buttons hand back to the dialog owner. */
    public interface Actions {
        void openHelp(int analysisIndex);
        void openDependencies();
    }

    /** Supplies the scanned status text for an analysis, or null before the scan finishes. */
    public interface StatusSource {
        String statusFor(int analysisIndex);
    }

    static final int PANEL_WIDTH = 270;
    private static final int TEXT_WIDTH = PANEL_WIDTH - 34;

    private final String[] labels;
    private final String[] descriptions;
    private final JLabel title = new JLabel();
    private final JLabel description = wrapped("");
    private final JLabel status = wrapped("");
    private final JLabel writes = wrapped("");
    private final JLabel uses = wrapped("");
    private final JButton helpButton = new JButton("Help");
    private final JButton fixButton = new JButton("Fix dependencies...");

    private String projectDirectory;
    private StatusSource statusSource;
    private Map<DependencyId, String> attention;
    private int shown = -1;

    public AnalysisDetailPanel(String[] labels, String[] descriptions, final Actions actions) {
        this.labels = labels;
        this.descriptions = descriptions;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(FlashTheme.SURFACE_RAISED);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 1, 0, 0, FlashTheme.BORDER),
                FlashTheme.pad(10, 12, 10, 12)));

        title.setFont(FlashTheme.h2());
        title.setForeground(FlashTheme.TEXT_HEADER);
        description.setForeground(FlashTheme.TEXT_HELP);

        add(left(title));
        add(Box.createVerticalStrut(4));
        add(left(description));
        add(Box.createVerticalStrut(8));
        addField("Status", status);
        addField("Writes", writes);
        addField("Uses", uses);

        helpButton.setToolTipText("Open the help page for this analysis.");
        fixButton.setToolTipText("Open Dependencies to install or repair what this analysis can use.");
        helpButton.addActionListener(e -> {
            if (actions != null && shown >= 0) actions.openHelp(shown);
        });
        fixButton.addActionListener(e -> {
            if (actions != null) actions.openDependencies();
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        buttons.setOpaque(false);
        buttons.add(helpButton);
        buttons.add(Box.createHorizontalStrut(6));
        buttons.add(fixButton);
        add(Box.createVerticalStrut(4));
        add(left(buttons));
        add(Box.createVerticalGlue());
    }

    @Override public Dimension getPreferredSize() {
        Dimension pref = super.getPreferredSize();
        return new Dimension(PANEL_WIDTH, pref.height);
    }

    @Override public Dimension getMinimumSize() {
        return new Dimension(PANEL_WIDTH, super.getMinimumSize().height);
    }

    public void setProjectDirectory(String directory) {
        projectDirectory = directory;
        refresh();
    }

    public void setStatusSource(StatusSource source) {
        statusSource = source;
        refresh();
    }

    /**
     * Records which runtimes need attention, keyed by id with their status label.
     * Pass an empty map when every runtime is present.
     */
    public void setDependencyAttention(Map<DependencyId, String> needingAttention) {
        Map<DependencyId, String> copy = new EnumMap<DependencyId, String>(DependencyId.class);
        if (needingAttention != null) copy.putAll(needingAttention);
        attention = copy;
        refresh();
    }

    /** Shows the given analysis. */
    public void show(int analysisIndex) {
        shown = analysisIndex;
        refresh();
    }

    public int getShownAnalysis() {
        return shown;
    }

    String statusTextForTests() { return plain(status.getText()); }
    String writesTextForTests() { return plain(writes.getText()); }
    String usesTextForTests() { return plain(uses.getText()); }
    boolean fixVisibleForTests() { return fixButton.isVisible(); }
    String titleForTests() { return title.getText(); }

    private void refresh() {
        if (shown < 0 || shown >= labels.length) {
            title.setText("");
            description.setText("");
            return;
        }
        title.setText(labels[shown]);
        setWrapped(description, shown < descriptions.length ? descriptions[shown] : "");

        String statusText = statusSource == null ? null : statusSource.statusFor(shown);
        setWrapped(status, statusText == null ? "Checking..." : statusText);

        String out = AnalysisFacts.outputForDisplay(projectDirectory, shown);
        setWrapped(writes, out.isEmpty() ? "No project folder selected" : out);

        List<String> problems = new ArrayList<String>();
        List<DependencyId> ids = AnalysisFacts.uses(shown);
        if (attention != null) {
            for (DependencyId id : ids) {
                if (attention.containsKey(id)) problems.add(problemLine(id, attention.get(id)));
            }
        }
        if (ids.isEmpty()) {
            setWrapped(uses, "No optional add-ons");
        } else if (attention == null) {
            setWrapped(uses, "Checking add-ons...");
        } else if (problems.isEmpty()) {
            setWrapped(uses, ids.size() == 1 ? "1 add-on, installed" : "All " + ids.size() + " add-ons installed");
        } else {
            StringBuilder sb = new StringBuilder();
            for (String line : problems) sb.append(line).append("<br>");
            int fine = ids.size() - problems.size();
            if (fine > 0) sb.append(fine).append(fine == 1 ? " other installed" : " others installed");
            setWrappedHtml(uses, sb.toString());
        }
        fixButton.setVisible(!problems.isEmpty());
        revalidate();
        repaint();
    }

    private static String problemLine(DependencyId id, String statusLabel) {
        DependencySpec spec = DependencyRegistry.get(id);
        String name = spec == null ? id.name() : spec.getDisplayName();
        String label = statusLabel == null || statusLabel.trim().isEmpty() ? "needs attention" : statusLabel.trim();
        StringBuilder sb = new StringBuilder("<b>").append(escape(name)).append("</b>: ")
                .append(escape(label.toLowerCase(java.util.Locale.ROOT)));
        if (spec != null && !spec.getAffectedFeatures().isEmpty()) {
            sb.append(" (needed for ").append(escape(join(spec.getAffectedFeatures()))).append(")");
        }
        return sb.toString();
    }

    private void addField(String name, JLabel value) {
        JLabel key = new JLabel(name);
        key.setFont(FlashTheme.caption().deriveFont(java.awt.Font.BOLD));
        key.setForeground(FlashTheme.TEXT_MUTED);
        add(left(key));
        add(Box.createVerticalStrut(1));
        add(left(value));
        add(Box.createVerticalStrut(7));
    }

    private static JLabel wrapped(String text) {
        JLabel label = new JLabel();
        label.setFont(FlashTheme.caption());
        label.setForeground(FlashTheme.TEXT_PRIMARY);
        setWrapped(label, text);
        return label;
    }

    private static void setWrapped(JLabel label, String text) {
        setWrappedHtml(label, escape(text == null ? "" : text));
    }

    private static void setWrappedHtml(JLabel label, String html) {
        // The width attribute is in screen pixels; a CSS px width is scaled up by Swing and overflows.
        label.setText("<html><body width='" + TEXT_WIDTH + "'>" + html + "</body></html>");
    }

    private static <T extends Component> T left(T component) {
        if (component instanceof javax.swing.JComponent) {
            ((javax.swing.JComponent) component).setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        return component;
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String plain(String html) {
        return html == null ? "" : html.replaceAll("<br>", "\n").replaceAll("<[^>]+>", "")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").trim();
    }
}
