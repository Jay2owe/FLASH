package flash.pipeline.ui.config;

import flash.pipeline.deconv.psf.ScopeModality;
import flash.pipeline.help.SetupHelpTopic;
import flash.pipeline.ui.FlashTheme;
import flash.pipeline.ui.ToggleSwitch;
import flash.pipeline.ui.preview.PreviewPairPanel;

import ij.ImagePlus;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * The one-off <b>Microscope &amp; Sample</b> optics screen shown once at the front of the
 * deconvolution sequence (only when deconvolution is opted in), implemented as a
 * {@link ConfigQcStage} so the embedded Set&nbsp;Up&nbsp;Configuration flow and the rebuilt
 * standalone Deconvolution analysis share one surface and cannot drift
 * (plan {@code 00_overview.md} &sect;C4).
 *
 * <p>It collects only <b>scope/sample-level</b> optics &mdash; numerical aperture, immersion and
 * sample refractive index, {@link ScopeModality scope modality}, the confocal pinhole (Airy units)
 * and the set of channels to deconvolve. Pixel size and Z-step vary <em>per acquisition</em> across
 * the QC set, so they are shown as a <b>read-only per-image summary ("from metadata")</b> and are
 * never editable shared fields (plan &sect;C5 / Stage 03 forbids folding them into the shared optics
 * block).</p>
 *
 * <p>The dialog frame ({@code ConfigQcDialog}) owns the shared {@link PreviewPairPanel}; this stage
 * supplies only {@link #buildControls}: a grey help label (NORTH), the optics form as
 * {@code buildCard}-style bordered cards (CENTER) and a feedback label (SOUTH), styled with
 * {@link FlashTheme} and {@link ToggleSwitch} &mdash; visually indistinguishable from
 * {@code DisplayRangeStage}/{@code DeconvolutionStage}. There is no live deconvolution preview here;
 * the (optional) representative Original is shown for context only.</p>
 *
 * <p>On {@link #lockIn} the live values are validated by attempting a representative {@code PsfSpec}
 * build via {@link OpticsSupport#validate(Value)}; a non-null error message keeps the user on the
 * stage (feedback + preview ERROR state) and returns {@code false} rather than letting an
 * {@code IllegalArgumentException} escape the wizard.</p>
 */
public final class DeconvOpticsStage implements ConfigQcStage {

    /** Everything this stage edits, bundled for {@link OpticsStore} persistence. */
    public static final class Value {
        /** Numerical aperture, or {@code null} when not entered. */
        public final Double na;
        /** Immersion refractive index, or {@code null} when not entered. */
        public final Double immersionRi;
        /** Sample refractive index, or {@code null} when not entered. */
        public final Double sampleRi;
        /** Confocal pinhole in Airy units, or {@code null} (only meaningful for CONFOCAL). */
        public final Double pinholeAiryUnits;
        /** Scope modality; never {@code null} once locked in. */
        public final ScopeModality modality;
        /** Opt-in flags per channel, indexed like {@link OpticsSupport#channelNames()}. */
        public final boolean[] selectedChannels;

        public Value(Double na, Double immersionRi, Double sampleRi, Double pinholeAiryUnits,
                     ScopeModality modality, boolean[] selectedChannels) {
            this.na = na;
            this.immersionRi = immersionRi;
            this.sampleRi = sampleRi;
            this.pinholeAiryUnits = pinholeAiryUnits;
            this.modality = modality;
            this.selectedChannels = selectedChannels == null
                    ? new boolean[0] : selectedChannels.clone();
        }
    }

    /**
     * Persistence seam. {@link #onEnter} reads current values via {@link #get()};
     * {@link #lockIn} writes the live values via {@link #set(Value)} only after validation passes.
     */
    public interface OpticsStore {
        Value get();
        void set(Value value);
    }

    /**
     * Metadata/support seam. Supplies channel names for the opt-in switches, metadata pre-fill
     * (including {@code RefractiveIndexEstimator} inference), the per-image read-only geometry
     * summary, an optional representative Original, and validation that attempts a representative
     * {@code PsfSpec} build.
     */
    public interface OpticsSupport {
        /** Channel display names for the opt-in switches; never contributes editable optics. */
        String[] channelNames();

        /** Pre-fill values from metadata / {@code RefractiveIndexEstimator}; may return {@code null}. */
        Value metadataDefaults();

        /** Per-image pixel-size / Z-step summary text ("from metadata"); may return {@code null}. */
        String readOnlyGeometrySummary();

        /** Optional Original image for the preview pane; may return {@code null}. */
        ImagePlus representativePreview();

        /**
         * Attempt a representative {@code PsfSpec} build from {@code value}; return an error message
         * to display inline, or {@code null} when the optics are acceptable. Implementations catch
         * {@code IllegalArgumentException} internally &mdash; this method must not throw.
         */
        String validate(Value value);

        /** Release an image obtained from {@link #representativePreview()} (no-op for {@code null}). */
        void close(ImagePlus image);
    }

    private final OpticsStore store;
    private final OpticsSupport support;

    private ConfigQcActions actions;
    private ConfigQcContext activeContext;
    private PreviewPairPanel preview;

    private JTextField naField;
    private JTextField immersionField;
    private JTextField sampleField;
    private JTextField pinholeField;
    private JComboBox<ScopeModality> modalityChoice;
    private JPanel pinholeRow;
    private final List<ToggleSwitch> channelToggles = new ArrayList<ToggleSwitch>();
    private String[] channelNames = new String[0];
    private JLabel geometryLabel;
    private JLabel feedbackLabel;

    private ImagePlus previewImage;

    public DeconvOpticsStage(OpticsStore store, OpticsSupport support) {
        if (store == null) {
            throw new IllegalArgumentException("store must not be null");
        }
        if (support == null) {
            throw new IllegalArgumentException("support must not be null");
        }
        this.store = store;
        this.support = support;
    }

    @Override
    public String title() {
        return "Microscope & Sample";
    }

    @Override
    public SetupHelpTopic helpTopic() {
        // No matching SetupHelpTopic exists yet; the frame renders the generic help.
        return null;
    }

    @Override
    public boolean showPreviewDisplayControls() {
        return false;
    }

    @Override
    public boolean showPreviewLutToggle() {
        return false;
    }

    @Override
    public boolean controlsCanExpand() {
        return true;
    }

    @Override
    public JComponent buildControls(ConfigQcContext context, ConfigQcActions actions) {
        this.actions = actions;
        this.activeContext = context;
        createControls();

        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setOpaque(false);

        JLabel help = new JLabel("Set the microscope and sample optics shared by every "
                + "deconvolved channel.");
        help.setForeground(FlashTheme.TEXT_HELP);
        panel.add(help, BorderLayout.NORTH);

        JPanel center = new JPanel();
        center.setOpaque(false);
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.add(buildOpticsCard());
        center.add(Box.createVerticalStrut(6));
        center.add(buildChannelsCard());
        center.add(Box.createVerticalStrut(6));
        center.add(buildGeometryCard());
        panel.add(center, BorderLayout.CENTER);

        feedbackLabel = new JLabel(" ");
        feedbackLabel.setForeground(FlashTheme.TEXT_HELP);
        panel.add(feedbackLabel, BorderLayout.SOUTH);

        applyValue(currentStoredValue());
        return panel;
    }

    @Override
    public void onEnter(ConfigQcContext context, PreviewPairPanel preview) {
        closeImages();
        this.activeContext = context;
        this.preview = preview;
        applyValue(currentStoredValue());
        refreshGeometrySummary();
        try {
            previewImage = support.representativePreview();
            if (preview != null) {
                preview.setOriginal(previewImage);
                preview.setAdjusted(null);
            }
            setStatus("Set the microscope and sample optics shared by every deconvolved channel.");
        } catch (Exception e) {
            setError("Could not load the representative preview: " + rootMessage(e));
        }
    }

    @Override
    public boolean lockIn(ConfigQcContext context) {
        if (naField == null || modalityChoice == null) {
            setError("Optics controls are not available.");
            return false;
        }
        Value value = liveValue();
        String error;
        try {
            error = support.validate(value);
        } catch (RuntimeException e) {
            // Defensive: the contract says validate() must not throw, but never let an
            // IllegalArgumentException from a representative PsfSpec build escape the wizard.
            error = rootMessage(e);
        }
        if (error != null && !error.trim().isEmpty()) {
            setError(error);
            return false;
        }
        store.set(value);
        setStatus("Locked microscope & sample optics: " + summary(value) + ".");
        return true;
    }

    @Override
    public void skipCurrentImage(ConfigQcContext context) {
        setStatus("Skipped this image; saved optics are unchanged.");
    }

    @Override
    public void onLeave(ConfigQcContext context) {
        closeImages();
        preview = null;
        activeContext = null;
    }

    // ---- control construction -------------------------------------------------------------

    private void createControls() {
        naField = new JTextField(6);
        constrain(naField, 90);
        immersionField = new JTextField(6);
        constrain(immersionField, 90);
        sampleField = new JTextField(6);
        constrain(sampleField, 90);
        pinholeField = new JTextField("1.0", 6);
        constrain(pinholeField, 90);

        modalityChoice = new JComboBox<ScopeModality>(ScopeModality.values());
        installModalityRenderer(modalityChoice);
        constrain(modalityChoice, 200);
        modalityChoice.addActionListener(e -> refreshPinholeVisibility());

        channelNames = safeChannelNames(support.channelNames());
        channelToggles.clear();
        for (int i = 0; i < channelNames.length; i++) {
            channelToggles.add(new ToggleSwitch(true));
        }
    }

    private JPanel buildOpticsCard() {
        JPanel body = formPanel();
        body.add(fieldRow("Numerical Aperture (NA)", naField));
        body.add(fieldRow("Immersion RI", immersionField));
        body.add(fieldRow("Sample RI", sampleField));
        body.add(fieldRow("Scope modality", modalityChoice));
        pinholeRow = fieldRow("Pinhole (Airy units)", pinholeField);
        body.add(pinholeRow);
        refreshPinholeVisibility();
        return buildCard("Microscope & sample", body);
    }

    private JPanel buildChannelsCard() {
        JPanel body = formPanel();
        if (channelToggles.isEmpty()) {
            JLabel empty = new JLabel("No channels are available to deconvolve.");
            empty.setForeground(FlashTheme.TEXT_MUTED);
            body.add(empty);
        } else {
            for (int i = 0; i < channelToggles.size(); i++) {
                body.add(toggleRow(channelLabel(i), channelToggles.get(i)));
                if (i < channelToggles.size() - 1) {
                    body.add(Box.createVerticalStrut(4));
                }
            }
        }
        return buildCard("Channels to deconvolve", body);
    }

    private JPanel buildGeometryCard() {
        JPanel body = formPanel();
        geometryLabel = new JLabel(geometryHtml(support.readOnlyGeometrySummary()));
        geometryLabel.setForeground(FlashTheme.TEXT_MUTED);
        body.add(geometryLabel);
        return buildCard("Per-image geometry (from metadata)", body);
    }

    private JPanel buildCard(String title, JComponent body) {
        JPanel card = new JPanel(new BorderLayout(0, 4));
        card.setOpaque(true);
        card.setBackground(FlashTheme.SURFACE_RAISED);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(FlashTheme.BORDER),
                FlashTheme.pad(6, 8, 8, 8)));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (title != null) {
            JLabel header = new JLabel(title);
            header.setFont(FlashTheme.bodyMedium());
            header.setForeground(FlashTheme.TEXT_HEADER);
            card.add(header, BorderLayout.NORTH);
        }
        card.add(body, BorderLayout.CENTER);
        return card;
    }

    private JPanel formPanel() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    private JPanel fieldRow(String label, JComponent field) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel l = new JLabel(label);
        l.setForeground(FlashTheme.TEXT_PRIMARY);
        l.setPreferredSize(new Dimension(170, 22));
        row.add(l, BorderLayout.WEST);
        JPanel wrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        wrap.setOpaque(false);
        wrap.add(field);
        row.add(wrap, BorderLayout.CENTER);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        return row;
    }

    private JPanel toggleRow(String label, ToggleSwitch toggle) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel wrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        wrap.setOpaque(false);
        wrap.add(toggle);
        row.add(wrap, BorderLayout.WEST);
        JLabel l = new JLabel(label);
        l.setForeground(FlashTheme.TEXT_PRIMARY);
        row.add(l, BorderLayout.CENTER);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        return row;
    }

    private void refreshPinholeVisibility() {
        if (pinholeRow != null) {
            pinholeRow.setVisible(selectedModality() == ScopeModality.CONFOCAL);
        }
    }

    private void refreshGeometrySummary() {
        if (geometryLabel != null) {
            geometryLabel.setText(geometryHtml(support.readOnlyGeometrySummary()));
        }
    }

    // ---- value <-> controls ---------------------------------------------------------------

    private Value currentStoredValue() {
        Value value = store.get();
        return value != null ? value : support.metadataDefaults();
    }

    private void applyValue(Value value) {
        if (value == null) {
            value = support.metadataDefaults();
        }
        naField.setText(value == null ? "" : formatDouble(value.na));
        immersionField.setText(value == null ? "" : formatDouble(value.immersionRi));
        sampleField.setText(value == null ? "" : formatDouble(value.sampleRi));
        pinholeField.setText(value == null || value.pinholeAiryUnits == null
                ? "1.0" : formatDouble(value.pinholeAiryUnits));
        if (value != null && value.modality != null) {
            modalityChoice.setSelectedItem(value.modality);
        }
        boolean[] selected = value == null ? null : value.selectedChannels;
        for (int i = 0; i < channelToggles.size(); i++) {
            boolean on = selected == null || i >= selected.length || selected[i];
            channelToggles.get(i).setSelected(on);
        }
        refreshPinholeVisibility();
    }

    private Value liveValue() {
        boolean[] selected = new boolean[channelToggles.size()];
        for (int i = 0; i < selected.length; i++) {
            selected[i] = channelToggles.get(i).isSelected();
        }
        return new Value(
                parseNullableDouble(naField.getText()),
                parseNullableDouble(immersionField.getText()),
                parseNullableDouble(sampleField.getText()),
                parseNullableDouble(pinholeField.getText()),
                selectedModality(),
                selected);
    }

    private ScopeModality selectedModality() {
        Object item = modalityChoice == null ? null : modalityChoice.getSelectedItem();
        return item instanceof ScopeModality ? (ScopeModality) item : ScopeModality.WIDEFIELD;
    }

    private String channelLabel(int index) {
        String name = index >= 0 && index < channelNames.length ? channelNames[index] : null;
        if (name == null || name.trim().isEmpty()) {
            return "Channel " + (index + 1);
        }
        return name;
    }

    // ---- feedback -------------------------------------------------------------------------

    private void setStatus(String text) {
        if (feedbackLabel != null) {
            feedbackLabel.setText(text == null || text.trim().isEmpty() ? " " : text);
        }
        if (actions != null) {
            actions.setStatus(text);
        }
    }

    private void setError(String text) {
        if (preview != null) {
            preview.setAdjustedState(PreviewPairPanel.PreviewState.ERROR, text);
        }
        setStatus(text);
    }

    private void closeImages() {
        ImagePlus image = previewImage;
        previewImage = null;
        if (image != null) {
            support.close(image);
        }
    }

    // ---- helpers --------------------------------------------------------------------------

    private String summary(Value value) {
        int selectedCount = 0;
        if (value.selectedChannels != null) {
            for (int i = 0; i < value.selectedChannels.length; i++) {
                if (value.selectedChannels[i]) {
                    selectedCount++;
                }
            }
        }
        String modality = value.modality == null ? "?" : value.modality.displayName();
        return modality + ", " + selectedCount + " channel" + (selectedCount == 1 ? "" : "s");
    }

    private static String[] safeChannelNames(String[] names) {
        return names == null ? new String[0] : names.clone();
    }

    private static Double parseNullableDouble(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return Double.valueOf(Double.parseDouble(trimmed));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String formatDouble(Double value) {
        if (value == null) {
            return "";
        }
        double d = value.doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return "";
        }
        if (d == Math.rint(d)) {
            return String.valueOf((long) d);
        }
        return String.valueOf(d);
    }

    private static String geometryHtml(String summary) {
        String text = summary == null ? "" : summary.trim();
        if (text.isEmpty()) {
            text = "Pixel size and Z-step are read per image from metadata.";
        }
        String escaped = text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .replace("\n", "<br>");
        return "<html>" + escaped + "</html>";
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.trim().isEmpty()
                ? current.getClass().getSimpleName() : message;
    }

    private static void constrain(JComponent component, int preferredWidth) {
        Dimension size = new Dimension(preferredWidth, 24);
        component.setPreferredSize(size);
        component.setMaximumSize(size);
    }

    private static void installModalityRenderer(JComboBox<ScopeModality> combo) {
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                Component component = super.getListCellRendererComponent(
                        list, value, index, isSelected, cellHasFocus);
                if (value instanceof ScopeModality && component instanceof JLabel) {
                    ((JLabel) component).setText(((ScopeModality) value).displayName());
                }
                return component;
            }
        });
    }
}
