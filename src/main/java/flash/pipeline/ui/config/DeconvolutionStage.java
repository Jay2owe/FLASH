package flash.pipeline.ui.config;

import flash.pipeline.deconv.engine.Algorithm;
import flash.pipeline.deconv.engine.DeconvParams;
import flash.pipeline.deconv.engine.DeconvSettings;
import flash.pipeline.deconv.engine.DeconvolutionEngine;
import flash.pipeline.deconv.engine.EdgeHandling;
import flash.pipeline.deconv.engine.EngineRegistry;
import flash.pipeline.deconv.psf.PsfModel;
import flash.pipeline.deconv.psf.ScopeModality;
import flash.pipeline.deconv.wizard.DeconvPreset;
import flash.pipeline.help.SetupHelpTopic;
import flash.pipeline.ui.FlashTheme;
import flash.pipeline.ui.ToggleSwitch;
import flash.pipeline.ui.preview.PreviewPairPanel;
import flash.pipeline.ui.variations.DeconvVariationsDialog;
import flash.pipeline.ui.variations.DeconvolutionPreviewAdapter;

import ij.ImagePlus;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The single per-channel deconvolution screen, implemented as a {@link ConfigQcStage} so the
 * embedded Set&nbsp;Up&nbsp;Configuration step and the rebuilt standalone Deconvolution analysis
 * share one surface and cannot drift (plan 00_overview.md &sect;C4).
 *
 * <p>The dialog frame ({@code ConfigQcDialog}) owns the shared {@link PreviewPairPanel} (Original vs
 * Deconvolved) on top; this stage supplies only {@link #buildControls} below it: a grey help label
 * (NORTH), the controls (CENTER) and a feedback label (SOUTH), styled with {@link FlashTheme},
 * {@code buildCard}-style bordered cards, {@link ToggleSwitch} and a {@link CollapsibleSection}
 * &mdash; visually indistinguishable from {@code DisplayRangeStage} and its siblings.</p>
 *
 * <p>The deconvolution compute is slow, so it runs off the EDT (a {@link SwingWorker}) only when the
 * user presses <b>Run Preview</b>; it is never auto-run on enter. Persistence flows through the
 * injected {@link DeconvStore}; the raw crop and the deconvolved crop come from the injected
 * {@link DeconvPreviewSource} (the Stage&nbsp;06 {@code ChannelDeconvPreviewer}/
 * {@code PreviewImageSource} seam) so this stage never touches {@code ResolvedSeriesSettings} or any
 * directory.</p>
 */
public final class DeconvolutionStage implements ConfigQcStage {

    /** Everything this stage edits, bundled for {@link DeconvStore} persistence. */
    public static final class Value {
        /** Engine key, algorithm, PSF model, iterations and regularization. */
        public final DeconvSettings settings;
        /** Per-channel emission wavelength in nm, or {@code null} when derived from metadata. */
        public final Double emissionWavelengthNm;
        /** True to route the deconvolved pixels into the Analysis group (segmentation/intensity). */
        public final boolean useDeconvAnalysis;
        /** True to route the deconvolved pixels into the Display group (range/LUT/figures). */
        public final boolean useDeconvDisplay;
        /** Advanced: FFT edge handling for the engine call. */
        public final EdgeHandling edgeHandling;
        /** Advanced: enforce strict-Nyquist sampling when synthesising the PSF. */
        public final boolean strictNyquist;
        /** Advanced: cache the deconvolved output so repeated runs reuse it. */
        public final boolean cacheDeconvolved;

        /** Convenience ctor: sensible advanced defaults (REFLECT edges, strict Nyquist + cache on). */
        public Value(DeconvSettings settings, Double emissionWavelengthNm,
                     boolean useDeconvAnalysis, boolean useDeconvDisplay) {
            this(settings, emissionWavelengthNm, useDeconvAnalysis, useDeconvDisplay,
                    DeconvParams.DEFAULT_EDGE_HANDLING, true, true);
        }

        public Value(DeconvSettings settings, Double emissionWavelengthNm,
                     boolean useDeconvAnalysis, boolean useDeconvDisplay,
                     EdgeHandling edgeHandling, boolean strictNyquist, boolean cacheDeconvolved) {
            this.settings = settings;
            this.emissionWavelengthNm = emissionWavelengthNm;
            this.useDeconvAnalysis = useDeconvAnalysis;
            this.useDeconvDisplay = useDeconvDisplay;
            this.edgeHandling = edgeHandling == null ? DeconvParams.DEFAULT_EDGE_HANDLING : edgeHandling;
            this.strictNyquist = strictNyquist;
            this.cacheDeconvolved = cacheDeconvolved;
        }
    }

    /**
     * Persistence seam. {@link #onEnter} reads current values via {@link #get()};
     * {@link #lockIn} writes the live values via {@link #set(Value)}. Implementations should default
     * both routing booleans to {@code true} for an opted-in channel.
     */
    public interface DeconvStore {
        Value get();
        void set(Value value);
    }

    /**
     * Preview compute seam over the Stage&nbsp;06 {@code ChannelDeconvPreviewer}/
     * {@code PreviewImageSource}. The stage never learns where the pixels live.
     */
    public interface DeconvPreviewSource {
        /** Fast raw centre-crop for {@code preview.setOriginal} in {@link #onEnter}. */
        ImagePlus openRawCrop() throws Exception;

        /**
         * Deconvolve the raw centre-crop using {@code liveValue.settings} (engine/algorithm/PSF/
         * iterations/regularization), {@code liveValue.emissionWavelengthNm} and the advanced flags.
         * Returns a new image the caller owns, or {@code null} when PSF synthesis fails. Called off
         * the EDT.
         */
        ImagePlus deconvolveCrop(Value liveValue) throws Exception;

        /**
         * Deconvolve the supplied caller-owned crop. Implementations must use exactly these pixels:
         * they must not fall back to a cached crop, mutate or close {@code rawCrop}, or retain it after
         * this method physically returns. The default deliberately fails closed so an older preview
         * source cannot silently violate the variations-dialog ownership boundary.
         */
        default ImagePlus deconvolveCrop(ImagePlus rawCrop, Value liveValue) throws Exception {
            throw new UnsupportedOperationException(
                    "This preview source cannot deconvolve a caller-supplied crop.");
        }

        /** Release an image obtained from this source (no-op for {@code null}). */
        void close(ImagePlus image);
    }

    /** Package-visible modal seam for exact-once image-ownership regressions. */
    interface VariationsDialogAction {
        void open(ImagePlus ownedCrop) throws Throwable;
    }

    private final DeconvStore store;
    private final DeconvPreviewSource previewSource;
    private final int channelIndex;
    private final String channelLabel;

    private ConfigQcActions actions;
    private ConfigQcContext activeContext;
    private PreviewPairPanel preview;

    private JComboBox<PresetItem> presetChoice;
    private JComboBox<EngineItem> engineChoice;
    private JComboBox<Algorithm> algorithmChoice;
    private JSpinner iterationsSpinner;
    private JTextField wavelengthField;
    private JComboBox<PsfModel> psfChoice;
    private JSpinner regularizationSpinner;
    private JComboBox<EdgeHandling> edgeChoice;
    private ToggleSwitch strictNyquistToggle;
    private ToggleSwitch cacheToggle;
    private ToggleSwitch analysisToggle;
    private ToggleSwitch displayToggle;
    private JButton runPreviewButton;
    private JButton variationsButton;
    private JLabel feedbackLabel;

    private ImagePlus rawCrop;
    private ImagePlus deconvolvedPreview;
    private boolean suppressStale;
    private int previewSeq;

    public DeconvolutionStage(DeconvStore store, DeconvPreviewSource previewSource,
                              int channelIndex, String channelLabel) {
        if (store == null) {
            throw new IllegalArgumentException("store must not be null");
        }
        if (previewSource == null) {
            throw new IllegalArgumentException("previewSource must not be null");
        }
        this.store = store;
        this.previewSource = previewSource;
        this.channelIndex = Math.max(0, channelIndex);
        this.channelLabel = channelLabel == null ? "" : channelLabel;
    }

    @Override
    public String title() {
        return "3D Deconvolution";
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
        return true;
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

        JLabel help = new JLabel("Deconvolve this channel before measuring and displaying it.");
        help.setForeground(FlashTheme.TEXT_HELP);
        panel.add(help, BorderLayout.NORTH);

        JPanel center = new JPanel();
        center.setOpaque(false);
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.add(buildPresetCard());
        center.add(Box.createVerticalStrut(6));
        center.add(buildCommonCard());
        center.add(Box.createVerticalStrut(6));
        center.add(buildAdvancedSection());
        center.add(Box.createVerticalStrut(6));
        center.add(buildActionRow());
        center.add(Box.createVerticalStrut(6));
        center.add(buildRoutingCard());
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
        try {
            rawCrop = previewSource.openRawCrop();
            if (rawCrop == null) {
                throw new IllegalStateException("No raw channel crop is available.");
            }
            if (preview != null) {
                preview.setOriginal(rawCrop);
                preview.setAdjusted(null);
                preview.setAdjustedState(PreviewPairPanel.PreviewState.STALE,
                        "Press Run Preview to deconvolve this channel.");
            }
            if (actions != null) {
                actions.setPreviewButtonStale(true);
            }
            setStatus("Loaded " + channelDescriptor()
                    + ". Press Run Preview to deconvolve (it is slow).");
        } catch (Exception e) {
            setError("Could not load channel for deconvolution: " + rootMessage(e));
        }
    }

    @Override
    public boolean lockIn(ConfigQcContext context) {
        if (engineChoice == null || previewSource == null) {
            setError("No deconvolution source is available.");
            return false;
        }
        Value value = liveValue();
        store.set(value);
        setStatus("Locked deconvolution for " + channelDescriptor() + ": "
                + settingsSummary(value) + ".");
        return true;
    }

    @Override
    public void skipCurrentImage(ConfigQcContext context) {
        setStatus("Skipped this image; saved deconvolution settings are unchanged.");
    }

    @Override
    public void onLeave(ConfigQcContext context) {
        closeImages();
        preview = null;
        activeContext = null;
    }

    // ---- control construction -------------------------------------------------------------

    private void createControls() {
        presetChoice = new JComboBox<PresetItem>();
        for (PresetItem item : builtInPresets()) {
            presetChoice.addItem(item);
        }
        constrain(presetChoice, 240);

        engineChoice = new JComboBox<EngineItem>();
        for (EngineItem item : availableEngineItems()) {
            engineChoice.addItem(item);
        }
        constrain(engineChoice, 220);

        algorithmChoice = new JComboBox<Algorithm>(Algorithm.values());
        installRenderer(algorithmChoice, new Labeler<Algorithm>() {
            @Override public String label(Algorithm value) { return value.displayName(); }
        });
        constrain(algorithmChoice, 220);

        iterationsSpinner = new JSpinner(new SpinnerNumberModel(
                DeconvParams.DEFAULT_ITERATIONS, 1, 100, 1));
        constrain(iterationsSpinner, 80);

        wavelengthField = new JTextField(6);
        constrain(wavelengthField, 90);

        psfChoice = new JComboBox<PsfModel>(PsfModel.values());
        installRenderer(psfChoice, new Labeler<PsfModel>() {
            @Override public String label(PsfModel value) { return value.displayName(); }
        });
        constrain(psfChoice, 220);

        regularizationSpinner = new JSpinner(new SpinnerNumberModel(
                DeconvParams.DEFAULT_REGULARIZATION, 0.0, 0.1, 0.001));
        regularizationSpinner.setEditor(new JSpinner.NumberEditor(regularizationSpinner, "0.###"));
        constrain(regularizationSpinner, 90);

        edgeChoice = new JComboBox<EdgeHandling>(EdgeHandling.values());
        installRenderer(edgeChoice, new Labeler<EdgeHandling>() {
            @Override public String label(EdgeHandling value) { return edgeLabel(value); }
        });
        constrain(edgeChoice, 160);

        strictNyquistToggle = new ToggleSwitch(true);
        cacheToggle = new ToggleSwitch(true);
        analysisToggle = new ToggleSwitch(true);
        displayToggle = new ToggleSwitch(true);

        presetChoice.addActionListener(e -> onPresetSelected());
        engineChoice.addActionListener(e -> markStale());
        algorithmChoice.addActionListener(e -> markStale());
        iterationsSpinner.addChangeListener(e -> markStale());
        psfChoice.addActionListener(e -> markStale());
        regularizationSpinner.addChangeListener(e -> markStale());
        edgeChoice.addActionListener(e -> markStale());
        strictNyquistToggle.addChangeListener(new Runnable() {
            @Override public void run() { markStale(); }
        });
        wavelengthField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { markStale(); }
            @Override public void removeUpdate(DocumentEvent e) { markStale(); }
            @Override public void changedUpdate(DocumentEvent e) { markStale(); }
        });
    }

    private JPanel buildPresetCard() {
        JPanel body = formPanel();
        body.add(fieldRow("Preset", presetChoice));
        return buildCard("Preset", body);
    }

    private JPanel buildCommonCard() {
        JPanel body = formPanel();
        body.add(fieldRow("Engine", engineChoice));
        body.add(fieldRow("Algorithm", algorithmChoice));
        body.add(fieldRow("Iterations", iterationsSpinner));
        body.add(fieldRow("Emission wavelength (nm)", wavelengthField));
        return buildCard("Deconvolution", body);
    }

    private CollapsibleSection buildAdvancedSection() {
        CollapsibleSection section = new CollapsibleSection("Advanced", false);
        JComponent body = section.getBody();
        body.add(fieldRow("PSF model", psfChoice));
        body.add(fieldRow("Regularization", regularizationSpinner));
        body.add(fieldRow("Edge handling", edgeChoice));
        body.add(toggleRow("Strict Nyquist sampling", strictNyquistToggle));
        body.add(toggleRow("Cache deconvolved output", cacheToggle));
        section.setAlignmentX(Component.LEFT_ALIGNMENT);
        return section;
    }

    private JPanel buildActionRow() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));

        runPreviewButton = new JButton("Run Preview");
        runPreviewButton.addActionListener(e -> runPreview());
        if (actions != null) {
            actions.registerPreviewButton(runPreviewButton);
        }

        variationsButton = new JButton("Parameter variations...");
        variationsButton.addActionListener(e -> openVariations());

        row.add(runPreviewButton);
        row.add(variationsButton);
        return row;
    }

    private JPanel buildRoutingCard() {
        JPanel body = formPanel();
        body.add(toggleRow("Analysis (segmentation, thresholds, intensity)", analysisToggle));
        body.add(Box.createVerticalStrut(4));
        body.add(toggleRow("Display (display range, LUT, figures)", displayToggle));
        return buildCard("Use deconvolved pixels for", body);
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

    // ---- preview + variations -------------------------------------------------------------

    private void runPreview() {
        if (previewSource == null) {
            setError("No deconvolution source is available.");
            return;
        }
        final Value live = liveValue();
        final int seq = ++previewSeq;
        if (actions != null) {
            actions.setPreviewButtonStale(false);
            actions.setPreviewButtonRunning(true);
        }
        if (preview != null) {
            preview.setAdjustedState(PreviewPairPanel.PreviewState.RUNNING, "Deconvolving preview...");
        }
        setStatus("Deconvolving " + channelDescriptor() + "...");
        SwingWorker<ImagePlus, Void> worker = new SwingWorker<ImagePlus, Void>() {
            @Override protected ImagePlus doInBackground() throws Exception {
                return previewSource.deconvolveCrop(live);
            }

            @Override protected void done() {
                if (seq != previewSeq) {
                    // A newer request superseded this one; drop the stale result.
                    try {
                        ImagePlus stale = get();
                        if (stale != null) {
                            previewSource.close(stale);
                        }
                    } catch (Exception ignored) {
                        // nothing to clean up
                    }
                    return;
                }
                if (actions != null) {
                    actions.setPreviewButtonRunning(false);
                }
                ImagePlus result;
                try {
                    result = get();
                } catch (Exception e) {
                    setError("Deconvolution preview failed: " + rootMessage(e));
                    return;
                }
                if (result == null) {
                    setError("Deconvolution produced no output for the current settings.");
                    return;
                }
                ImagePlus previous = deconvolvedPreview;
                deconvolvedPreview = result;
                if (previous != null && previous != rawCrop) {
                    previewSource.close(previous);
                }
                String text = "Deconvolved preview - " + settingsSummary(live) + ".";
                if (actions != null) {
                    actions.setAdjustedPreview(result, text);
                } else if (preview != null) {
                    preview.setAdjusted(result);
                    preview.setAdjustedState(PreviewPairPanel.PreviewState.READY, text);
                }
                setStatus(text);
            }
        };
        worker.execute();
    }

    private void openVariations() {
        if (rawCrop == null) {
            setError("Load the channel first (Run Preview) before opening parameter variations.");
            return;
        }
        final Value live = liveValue();
        DeconvSettings base = live.settings;

        // The variations adapter must consume the sweep's owned input. Falling back to the stage's
        // cached crop would let modal cancellation close pixels while a physical engine call is live.
        final DeconvolutionPreviewAdapter adapter = variationsAdapter(previewSource, live);

        try {
            withOwnedVariationsCrop(rawCrop, previewSource, new VariationsDialogAction() {
                @Override public void open(ImagePlus cropForDialog) {
                    Window owner = variationsButton == null
                            ? null : SwingUtilities.getWindowAncestor(variationsButton);
                    DeconvVariationsDialog dialog = new DeconvVariationsDialog(
                            owner,
                            channelLabel,
                            cropForDialog,
                            base,
                            adapter,
                            availableEngineKeys(),
                            algorithmNames(),
                            psfModelNames(),
                            new Consumer<DeconvSettings>() {
                                @Override public void accept(DeconvSettings chosen) {
                                    applySweepChoice(chosen);
                                }
                            });
                    dialog.setVisible(true);
                }
            });
        } catch (Throwable failure) {
            rethrowVariationsFailure(failure);
        }
    }

    /** Package-visible construction seam for the supplied-pixel ownership regression. */
    static DeconvolutionPreviewAdapter variationsAdapter(final DeconvPreviewSource previewSource,
                                                          final Value live) {
        if (previewSource == null) {
            throw new IllegalArgumentException("previewSource must not be null");
        }
        if (live == null) {
            throw new IllegalArgumentException("live must not be null");
        }
        return new DeconvolutionPreviewAdapter() {
            @Override public ImagePlus deconvolvePreview(ImagePlus crop, DeconvSettings settings)
                    throws Exception {
                if (crop == null) {
                    throw new IllegalArgumentException("crop must not be null");
                }
                Value sweepValue = new Value(settings, live.emissionWavelengthNm,
                        live.useDeconvAnalysis, live.useDeconvDisplay,
                        live.edgeHandling, live.strictNyquist, live.cacheDeconvolved);
                return previewSource.deconvolveCrop(crop, sweepValue);
            }

            @Override public void close(ImagePlus image) {
                previewSource.close(image);
            }
        };
    }

    /**
     * Runs the modal variations lifecycle with caller-owned pixels. Dialog cells only borrow the
     * duplicate, so the caller releases exactly that image after cancel, accept, or any failure.
     */
    static void withOwnedVariationsCrop(ImagePlus sharedRawCrop,
                                        DeconvPreviewSource previewSource,
                                        VariationsDialogAction action) throws Throwable {
        if (sharedRawCrop == null) {
            throw new IllegalArgumentException("sharedRawCrop must not be null");
        }
        if (previewSource == null) {
            throw new IllegalArgumentException("previewSource must not be null");
        }
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }

        ImagePlus ownedCrop = null;
        Throwable primaryFailure = null;
        boolean interrupted = false;
        try {
            ownedCrop = sharedRawCrop.duplicate();
            if (ownedCrop == null) {
                throw new IllegalStateException("Could not duplicate the deconvolution crop.");
            }
            ownedCrop.setTitle(sharedRawCrop.getTitle());
            action.open(ownedCrop);
        } catch (Throwable failure) {
            primaryFailure = failure;
            interrupted = failure instanceof InterruptedException;
        }

        Throwable cleanupFailure = null;
        // ImageJ cleanup may touch AWT lazily; clear cancellation while closing and restore it
        // after the owned duplicate has been released.
        interrupted = Thread.interrupted() || interrupted;
        if (ownedCrop != null) {
            try {
                previewSource.close(ownedCrop);
            } catch (Throwable failure) {
                cleanupFailure = failure;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }

        Throwable outcome = combineVariationsFailures(primaryFailure, cleanupFailure);
        if (outcome != null) {
            throw outcome;
        }
    }

    private static Throwable combineVariationsFailures(Throwable primaryFailure,
                                                       Throwable cleanupFailure) {
        if (primaryFailure == null) {
            return cleanupFailure;
        }
        if (cleanupFailure == null || cleanupFailure == primaryFailure) {
            return primaryFailure;
        }
        if (isVmFatal(cleanupFailure) && !isVmFatal(primaryFailure)) {
            cleanupFailure.addSuppressed(primaryFailure);
            return cleanupFailure;
        }
        primaryFailure.addSuppressed(cleanupFailure);
        return primaryFailure;
    }

    private static boolean isVmFatal(Throwable failure) {
        return failure instanceof VirtualMachineError
                || failure instanceof ThreadDeath;
    }

    private static void rethrowVariationsFailure(Throwable failure) {
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        throw new IllegalStateException("Could not open deconvolution variations: "
                + rootMessage(failure), failure);
    }

    private void applySweepChoice(DeconvSettings chosen) {
        if (chosen == null) {
            return;
        }
        suppressStale = true;
        try {
            selectEngineKey(chosen.engineKey());
            algorithmChoice.setSelectedItem(chosen.algorithm());
            psfChoice.setSelectedItem(chosen.psfModel());
            iterationsSpinner.setValue(clampIterations(chosen.iterations()));
            regularizationSpinner.setValue(clampRegularization(chosen.regularization()));
            presetChoice.setSelectedIndex(0);
        } finally {
            suppressStale = false;
        }
        setStatus("Applied variation: " + settingsSummary(liveValue()) + ".");
        runPreview();
    }

    private void onPresetSelected() {
        if (suppressStale) {
            return;
        }
        Object selected = presetChoice.getSelectedItem();
        if (selected instanceof PresetItem && ((PresetItem) selected).preset != null) {
            applyPreset(((PresetItem) selected).preset);
        }
    }

    private void applyPreset(DeconvPreset preset) {
        suppressStale = true;
        try {
            selectEngineKey(preset.getEngineKey());
            algorithmChoice.setSelectedItem(preset.getAlgorithm());
            psfChoice.setSelectedItem(preset.getPsfModel());
            iterationsSpinner.setValue(clampIterations(preset.getIterations()));
            regularizationSpinner.setValue(clampRegularization(preset.getRegularization()));
        } finally {
            suppressStale = false;
        }
        setStatus("Applied preset: " + preset.getName() + ".");
        markStale();
    }

    // ---- value <-> controls ---------------------------------------------------------------

    private Value currentStoredValue() {
        Value value = store == null ? null : store.get();
        return value != null ? value : defaultValue();
    }

    private Value defaultValue() {
        DeconvSettings settings = new DeconvSettings(defaultEngineKey(), Algorithm.RL,
                PsfModel.GIBSON_LANNI, DeconvParams.DEFAULT_ITERATIONS,
                DeconvParams.DEFAULT_REGULARIZATION);
        return new Value(settings, null, true, true,
                DeconvParams.DEFAULT_EDGE_HANDLING, true, true);
    }

    private void applyValue(Value value) {
        if (value == null) {
            value = defaultValue();
        }
        DeconvSettings settings = value.settings != null ? value.settings : defaultValue().settings;
        suppressStale = true;
        try {
            selectEngineKey(settings.engineKey());
            if (settings.algorithm() != null) {
                algorithmChoice.setSelectedItem(settings.algorithm());
            }
            if (settings.psfModel() != null) {
                psfChoice.setSelectedItem(settings.psfModel());
            }
            iterationsSpinner.setValue(clampIterations(settings.iterations()));
            regularizationSpinner.setValue(clampRegularization(settings.regularization()));
            wavelengthField.setText(value.emissionWavelengthNm == null
                    ? "" : formatWavelength(value.emissionWavelengthNm.doubleValue()));
            edgeChoice.setSelectedItem(value.edgeHandling == null
                    ? DeconvParams.DEFAULT_EDGE_HANDLING : value.edgeHandling);
            strictNyquistToggle.setSelected(value.strictNyquist);
            cacheToggle.setSelected(value.cacheDeconvolved);
            analysisToggle.setSelected(value.useDeconvAnalysis);
            displayToggle.setSelected(value.useDeconvDisplay);
            presetChoice.setSelectedIndex(0);
        } finally {
            suppressStale = false;
        }
    }

    private Value liveValue() {
        DeconvSettings settings = new DeconvSettings(
                selectedEngineKey(),
                selectedAlgorithm(),
                selectedPsfModel(),
                iterationsValue(),
                regularizationValue());
        return new Value(settings, wavelengthValue(),
                analysisToggle.isSelected(), displayToggle.isSelected(),
                selectedEdgeHandling(), strictNyquistToggle.isSelected(), cacheToggle.isSelected());
    }

    // ---- feedback -------------------------------------------------------------------------

    private void markStale() {
        if (suppressStale) {
            return;
        }
        String text = "Deconvolution settings changed - run preview to update.";
        if (actions != null) {
            actions.setPreviewButtonStale(true);
            actions.markPreviewStale(text);
        } else if (preview != null) {
            preview.setAdjustedState(PreviewPairPanel.PreviewState.STALE, text);
        }
    }

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
        ImagePlus deconvolved = deconvolvedPreview;
        ImagePlus raw = rawCrop;
        deconvolvedPreview = null;
        rawCrop = null;
        if (deconvolved != null && deconvolved != raw) {
            previewSource.close(deconvolved);
        }
        if (raw != null) {
            previewSource.close(raw);
        }
    }

    // ---- selection helpers ----------------------------------------------------------------

    private String selectedEngineKey() {
        Object item = engineChoice.getSelectedItem();
        if (item instanceof EngineItem) {
            return ((EngineItem) item).key;
        }
        return defaultEngineKey();
    }

    private void selectEngineKey(String key) {
        if (key == null) {
            return;
        }
        for (int i = 0; i < engineChoice.getItemCount(); i++) {
            EngineItem item = engineChoice.getItemAt(i);
            if (item != null && key.equals(item.key)) {
                engineChoice.setSelectedIndex(i);
                return;
            }
        }
    }

    private Algorithm selectedAlgorithm() {
        Object item = algorithmChoice.getSelectedItem();
        return item instanceof Algorithm ? (Algorithm) item : Algorithm.RL;
    }

    private PsfModel selectedPsfModel() {
        Object item = psfChoice.getSelectedItem();
        return item instanceof PsfModel ? (PsfModel) item : PsfModel.GIBSON_LANNI;
    }

    private EdgeHandling selectedEdgeHandling() {
        Object item = edgeChoice.getSelectedItem();
        return item instanceof EdgeHandling ? (EdgeHandling) item : DeconvParams.DEFAULT_EDGE_HANDLING;
    }

    private int iterationsValue() {
        Object value = iterationsSpinner.getValue();
        return value instanceof Number
                ? clampIterations(((Number) value).intValue())
                : DeconvParams.DEFAULT_ITERATIONS;
    }

    private double regularizationValue() {
        Object value = regularizationSpinner.getValue();
        return value instanceof Number
                ? clampRegularization(((Number) value).doubleValue())
                : DeconvParams.DEFAULT_REGULARIZATION;
    }

    private Double wavelengthValue() {
        String text = wavelengthField == null ? null : wavelengthField.getText();
        if (text == null) {
            return null;
        }
        text = text.trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            double parsed = Double.parseDouble(text);
            return parsed > 0.0 ? Double.valueOf(parsed) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private List<String> availableEngineKeys() {
        List<String> keys = new ArrayList<String>();
        for (int i = 0; i < engineChoice.getItemCount(); i++) {
            EngineItem item = engineChoice.getItemAt(i);
            if (item != null) {
                keys.add(item.key);
            }
        }
        return keys;
    }

    private static List<String> algorithmNames() {
        List<String> names = new ArrayList<String>();
        for (Algorithm algorithm : Algorithm.values()) {
            names.add(algorithm.name());
        }
        return names;
    }

    private static List<String> psfModelNames() {
        List<String> names = new ArrayList<String>();
        for (PsfModel model : PsfModel.values()) {
            names.add(model.name());
        }
        return names;
    }

    private static List<EngineItem> availableEngineItems() {
        List<DeconvolutionEngine> engines = EngineRegistry.available();
        if (engines.isEmpty()) {
            engines = EngineRegistry.all();
        }
        List<EngineItem> items = new ArrayList<EngineItem>();
        for (DeconvolutionEngine engine : engines) {
            items.add(new EngineItem(engine.key(), engine.displayName()));
        }
        return items;
    }

    private static String defaultEngineKey() {
        List<EngineItem> items = availableEngineItems();
        return items.isEmpty() ? "DL2" : items.get(0).key;
    }

    private String settingsSummary(Value value) {
        DeconvSettings settings = value.settings;
        if (settings == null) {
            return "no settings";
        }
        String algorithm = settings.algorithm() == null ? "?" : settings.algorithm().displayName();
        return engineLabel(settings.engineKey()) + " / " + algorithm
                + ", " + settings.iterations() + " it";
    }

    private String engineLabel(String key) {
        for (int i = 0; i < engineChoice.getItemCount(); i++) {
            EngineItem item = engineChoice.getItemAt(i);
            if (item != null && item.key.equals(key)) {
                return item.label;
            }
        }
        return key == null ? "?" : key;
    }

    private String channelDescriptor() {
        return channelLabel.trim().isEmpty()
                ? "channel " + (channelIndex + 1) : channelLabel;
    }

    private static int clampIterations(int value) {
        return Math.max(1, Math.min(100, value));
    }

    private static double clampRegularization(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return DeconvParams.DEFAULT_REGULARIZATION;
        }
        return Math.max(0.0, Math.min(0.1, value));
    }

    private static String formatWavelength(double nm) {
        if (nm == Math.rint(nm)) {
            return String.valueOf((long) nm);
        }
        return String.valueOf(nm);
    }

    private static String edgeLabel(EdgeHandling edgeHandling) {
        if (edgeHandling == EdgeHandling.ZERO_PAD) {
            return "Zero pad";
        }
        return "Reflect";
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

    private static <T> void installRenderer(JComboBox<T> combo, final Labeler<T> labeler) {
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                Component component = super.getListCellRendererComponent(
                        list, value, index, isSelected, cellHasFocus);
                if (value != null && component instanceof JLabel) {
                    @SuppressWarnings("unchecked")
                    T typed = (T) value;
                    ((JLabel) component).setText(labeler.label(typed));
                }
                return component;
            }
        });
    }

    private static List<PresetItem> builtInPresets() {
        List<PresetItem> items = new ArrayList<PresetItem>();
        items.add(new PresetItem("Custom", null));
        items.add(new PresetItem(preset("Confocal - Richardson-Lucy",
                "DL2", Algorithm.RL, PsfModel.GIBSON_LANNI, 15, 0.0, ScopeModality.CONFOCAL)));
        items.add(new PresetItem(preset("Confocal - RL + TV (denoised)",
                "DL2", Algorithm.RL_TV, PsfModel.GIBSON_LANNI, 20, 0.01, ScopeModality.CONFOCAL)));
        items.add(new PresetItem(preset("Widefield - Richardson-Lucy",
                "DL2", Algorithm.RL, PsfModel.BORN_WOLF, 25, 0.0, ScopeModality.WIDEFIELD)));
        items.add(new PresetItem(preset("Fast GPU (CLIJ2)",
                "CLIJ2", Algorithm.RL, PsfModel.GIBSON_LANNI, 10, 0.0, ScopeModality.CONFOCAL)));
        return items;
    }

    private static DeconvPreset preset(String name, String engineKey, Algorithm algorithm,
                                       PsfModel psfModel, int iterations, double regularization,
                                       ScopeModality modality) {
        return new DeconvPreset(name, null, engineKey, algorithm, psfModel,
                iterations, regularization, modality, null, null);
    }

    private interface Labeler<T> {
        String label(T value);
    }

    private static final class EngineItem {
        final String key;
        final String label;

        EngineItem(String key, String label) {
            this.key = key;
            this.label = label == null || label.trim().isEmpty() ? key : label;
        }

        @Override public String toString() {
            return label;
        }
    }

    private static final class PresetItem {
        final String label;
        final DeconvPreset preset;

        PresetItem(String label, DeconvPreset preset) {
            this.label = label;
            this.preset = preset;
        }

        PresetItem(DeconvPreset preset) {
            this(preset.getName(), preset);
        }

        @Override public String toString() {
            return label;
        }
    }
}
