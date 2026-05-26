package flash.pipeline.analyses;

import flash.pipeline.io.ImageSourceDispatcher;
import flash.pipeline.io.DeferredImageSupplier;
import flash.pipeline.io.OrientationAliasIO;
import flash.pipeline.io.OrientationManifestIO;
import flash.pipeline.io.SeriesMeta;
import flash.pipeline.image.OrientationOps;
import flash.pipeline.naming.HemisphereAliasMatcher;
import flash.pipeline.naming.ImageNameParser;
import flash.pipeline.naming.NameParts;
import flash.pipeline.naming.OrientationManifestRow;
import flash.pipeline.orientation.OrientationImageIdentity;
import flash.pipeline.orientation.OrientationTransformState;
import flash.pipeline.ui.PipelineDialog;
import ij.IJ;
import ij.ImagePlus;
import ij.process.ImageProcessor;

import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.TableModelEvent;
import javax.swing.event.TableModelListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Optional setup workflow for project-level image orientation metadata.
 */
public class ImageOrientationSetupAnalysis implements Analysis {

    private static final int COL_IMAGE = 0;
    private static final int COL_ANIMAL = 1;
    private static final int COL_HEMISPHERE = 2;
    private static final int COL_REGION = 3;
    private static final int COL_ROTATE = 4;
    private static final int COL_FLIP_H = 5;
    private static final int COL_FLIP_V = 6;
    private static final int COL_POLICY = 7;
    private static final int COL_STATUS = 8;
    private static final int COL_CONFIRMED = 9;
    private static final int COL_NOTES = 10;

    private static final String STATUS_SAVED = "Saved";
    private static final String STATUS_STRICT = "Filename convention";
    private static final String STATUS_ALIAS = "Suggested alias";
    private static final String STATUS_MANUAL = "Manual edit";
    private static final String STATUS_UNKNOWN = "Unknown";
    private static final String STATUS_INCOMPLETE = "Incomplete";

    private boolean headless = false;
    private boolean suppressDialogs = false;

    @Override
    public void setHeadless(boolean headless) {
        this.headless = headless;
    }

    @Override
    public void setSuppressDialogs(boolean suppress) {
        this.suppressDialogs = suppress;
    }

    @Override
    public boolean requiresHeadedMode() {
        return true;
    }

    @Override
    public void execute(String directory) {
        if (directory == null || directory.trim().isEmpty()) {
            logOrShow("Image Orientation Setup", "Pick a project directory before running orientation setup.");
            return;
        }
        if (headless || GraphicsEnvironment.isHeadless()) {
            IJ.log("[Image Orientation Setup] This setup step needs the table UI. Run it from Fiji with a display.");
            return;
        }

        final List<SetupRow> rows;
        final LinkedHashMap<OrientationManifestRow.Hemisphere, List<String>> aliases;
        try {
            aliases = OrientationAliasIO.readIfExists(directory);
            rows = buildSetupRows(directory, aliases);
        } catch (Exception e) {
            logOrShow("Image Orientation Setup", "Could not scan image metadata: " + e.getMessage());
            return;
        }
        if (rows.isEmpty()) {
            logOrShow("Image Orientation Setup", "No image series were found in this project.");
            return;
        }

        final SetupPanel panel = new SetupPanel(directory, rows, aliases);
        PipelineDialog dialog = new PipelineDialog("Image Orientation Setup", PipelineDialog.Phase.SETUP);
        dialog.setPrimaryButtonText("Save");
        dialog.addComponent(panel.getComponent());

        if (!dialog.showDialog()) {
            IJ.log("[Image Orientation Setup] Cancelled.");
            return;
        }

        try {
            OrientationAliasIO.save(directory, panel.collectAliases());
            List<OrientationManifestRow> manifestRows = panel.collectRows();
            saveRows(directory, manifestRows);
            logOrShow("Image Orientation Setup",
                    "Saved " + manifestRows.size() + " orientation rows to "
                    + OrientationManifestIO.getFile(directory).getAbsolutePath());
        } catch (IOException e) {
            logOrShow("Image Orientation Setup", "Could not save orientation manifest: " + e.getMessage());
        }
    }

    public List<OrientationManifestRow> buildRowsForTests(
            String directory,
            Map<OrientationManifestRow.Hemisphere, List<String>> aliases) throws Exception {
        List<SetupRow> setupRows = buildSetupRows(directory, aliases);
        List<OrientationManifestRow> rows = new ArrayList<OrientationManifestRow>();
        for (SetupRow row : setupRows) {
            rows.add(row.toManifestRow());
        }
        return rows;
    }

    public List<OrientationManifestRow> loadRows(String directory) throws IOException {
        return OrientationManifestIO.read(OrientationManifestIO.getFile(directory));
    }

    public void saveRows(String directory, List<OrientationManifestRow> rows) throws IOException {
        OrientationManifestIO.saveRows(directory, rows);
    }

    private void logOrShow(String title, String message) {
        IJ.log("[" + title + "] " + message);
        if (!headless && !suppressDialogs) {
            IJ.showMessage(title, message);
        }
    }

    private List<SetupRow> buildSetupRows(
            String directory,
            Map<OrientationManifestRow.Hemisphere, List<String>> aliases) throws Exception {
        List<SeriesMeta> metas = ImageSourceDispatcher.readAllMetadata(directory);
        if (metas == null || metas.isEmpty()) return Collections.emptyList();

        OrientationImageIdentity.SourceContext source =
                OrientationImageIdentity.SourceContext.resolve(directory);
        LinkedHashMap<String, OrientationManifestRow> saved =
                OrientationManifestIO.readByImageKeyIfExists(directory);

        ArrayList<SetupRow> rows = new ArrayList<SetupRow>();
        for (int i = 0; i < metas.size(); i++) {
            SeriesMeta meta = metas.get(i);
            if (meta == null) continue;
            OrientationImageIdentity identity = source.identityFor(
                    meta.index < 0 ? i : meta.index,
                    meta.name);
            OrientationManifestRow existing = saved.get(identity.imageKey);
            if (existing != null) {
                rows.add(SetupRow.fromExisting(existing));
            } else {
                rows.add(suggestRow(
                        identity.imageKey,
                        identity.sourceFile,
                        identity.seriesIndex,
                        identity.originalName,
                        aliases));
            }
        }
        return rows;
    }

    private static SetupRow suggestRow(String imageKey,
                                       String sourceFile,
                                       int seriesIndex,
                                       String originalName,
                                       Map<OrientationManifestRow.Hemisphere, List<String>> aliases) {
        String displayName = displayNameFor(originalName);
        NameParts strict = ImageNameParser.parseStrict(originalName);
        if (!strict.animal.isEmpty() && strict.hasKnownHemisphere()) {
            return new SetupRow(
                    imageKey,
                    sourceFile,
                    seriesIndex,
                    originalName,
                    displayName,
                    strict.animal,
                    OrientationManifestRow.Hemisphere.fromCsv(strict.hemisphere),
                    strict.region,
                    OrientationManifestRow.RotationDegrees.DEG_0,
                    false,
                    false,
                    OrientationManifestRow.ViewPolicy.STANDARDIZE_TO_LEFT,
                    OrientationManifestRow.DecisionSource.STRICT_FILENAME,
                    OrientationManifestRow.ConfirmationState.YES,
                    "",
                    STATUS_STRICT);
        }

        HemisphereAliasMatcher.Suggestion suggestion =
                HemisphereAliasMatcher.match(originalName, aliases);
        AliasParts parts = aliasParts(originalName, suggestion.hemisphere, aliases);
        if (suggestion.hasHemisphere()) {
            return new SetupRow(
                    imageKey,
                    sourceFile,
                    seriesIndex,
                    originalName,
                    displayName,
                    parts.animalName,
                    suggestion.hemisphere,
                    parts.region,
                    OrientationManifestRow.RotationDegrees.DEG_0,
                    false,
                    false,
                    OrientationManifestRow.ViewPolicy.STANDARDIZE_TO_LEFT,
                    OrientationManifestRow.DecisionSource.FILENAME_ALIAS,
                    OrientationManifestRow.ConfirmationState.NO,
                    "Suggested from alias: " + suggestion.matchedAlias,
                    STATUS_ALIAS);
        }

        return new SetupRow(
                imageKey,
                sourceFile,
                seriesIndex,
                originalName,
                displayName,
                fallbackAnimalName(originalName),
                OrientationManifestRow.Hemisphere.UNKNOWN,
                "",
                OrientationManifestRow.RotationDegrees.DEG_0,
                false,
                false,
                OrientationManifestRow.ViewPolicy.MANUAL_ONLY,
                OrientationManifestRow.DecisionSource.UNKNOWN,
                OrientationManifestRow.ConfirmationState.NO,
                suggestion.ambiguous ? "Ambiguous aliases: " + suggestion.matchedAlias : "",
                STATUS_UNKNOWN);
    }

    private static String displayNameFor(String originalName) {
        String display = ImageNameParser.extractBioFormatsSeriesName(originalName);
        if (display == null || display.trim().isEmpty()) display = originalName;
        return display == null ? "" : display.trim();
    }

    private static String fallbackAnimalName(String originalName) {
        String value = displayNameFor(originalName);
        value = ImageNameParser.stripExtension(value);
        if (value == null) return "";
        value = value.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", " ").trim();
        return value;
    }

    private static AliasParts aliasParts(String originalName,
                                         OrientationManifestRow.Hemisphere hemisphere,
                                         Map<OrientationManifestRow.Hemisphere, List<String>> aliases) {
        String base = fallbackAnimalName(originalName);
        String[] tokens = base.split("[_\\-\\s]+");
        int aliasIndex = -1;
        for (int i = 0; i < tokens.length; i++) {
            if (HemisphereAliasMatcher.matchesAliasToken(tokens[i], hemisphere, aliases)) {
                aliasIndex = i;
                break;
            }
        }
        if (aliasIndex < 0) return new AliasParts(base, "");

        String animal = joinTokens(tokens, 0, aliasIndex);
        String region = joinTokens(tokens, aliasIndex + 1, tokens.length);
        if (animal.isEmpty()) animal = base;
        return new AliasParts(animal, region);
    }

    private static String joinTokens(String[] tokens, int startInclusive, int endExclusive) {
        StringBuilder sb = new StringBuilder();
        for (int i = startInclusive; i < endExclusive && i < tokens.length; i++) {
            String token = tokens[i] == null ? "" : tokens[i].trim();
            if (token.isEmpty()) continue;
            if (sb.length() > 0) sb.append('_');
            sb.append(token);
        }
        return sb.toString();
    }

    private static String csvHemisphere(OrientationManifestRow.Hemisphere hemisphere) {
        return hemisphere == null ? OrientationManifestRow.Hemisphere.UNKNOWN.toCsv() : hemisphere.toCsv();
    }

    private static String csvPolicy(OrientationManifestRow.ViewPolicy policy) {
        return policy == null ? OrientationManifestRow.ViewPolicy.MANUAL_ONLY.toCsv() : policy.toCsv();
    }

    private static String csvRotate(OrientationManifestRow.RotationDegrees rotate) {
        return rotate == null ? OrientationManifestRow.RotationDegrees.DEG_0.toCsv() : rotate.toCsv();
    }

    static TransformState transformFrom(String rotateDegrees, boolean flipHorizontal, boolean flipVertical) {
        return new TransformState(OrientationTransformState.fromCsv(
                rotateDegrees, flipHorizontal, flipVertical));
    }

    static List<AlternatingAssignment> buildAlternatingAssignments(
            int rowCount,
            boolean firstRowIsLeftHemisphere,
            boolean pairRowsAsSameAnimal,
            String animalPrefix,
            int firstAnimalNumber,
            String region) {
        ArrayList<AlternatingAssignment> assignments = new ArrayList<AlternatingAssignment>();
        if (rowCount <= 0) return assignments;
        String prefix = animalPrefix == null || animalPrefix.trim().isEmpty()
                ? "Mouse" : animalPrefix.trim();
        int start = firstAnimalNumber < 1 ? 1 : firstAnimalNumber;
        String safeRegion = region == null ? "" : region.trim();
        for (int i = 0; i < rowCount; i++) {
            boolean even = i % 2 == 0;
            OrientationManifestRow.Hemisphere hemisphere = (even == firstRowIsLeftHemisphere)
                    ? OrientationManifestRow.Hemisphere.LH
                    : OrientationManifestRow.Hemisphere.RH;
            int animalOffset = pairRowsAsSameAnimal ? i / 2 : i;
            assignments.add(new AlternatingAssignment(
                    prefix + (start + animalOffset),
                    hemisphere,
                    safeRegion));
        }
        return assignments;
    }

    static final class TransformState {
        final OrientationManifestRow.RotationDegrees rotateDegrees;
        final boolean flipHorizontal;
        final boolean flipVertical;
        private final OrientationTransformState delegate;

        TransformState(OrientationManifestRow.RotationDegrees rotateDegrees,
                       boolean flipHorizontal,
                       boolean flipVertical) {
            this(new OrientationTransformState(rotateDegrees, flipHorizontal, flipVertical));
        }

        private TransformState(OrientationTransformState delegate) {
            this.delegate = delegate == null ? OrientationTransformState.identity() : delegate;
            this.rotateDegrees = this.delegate.rotateDegrees;
            this.flipHorizontal = this.delegate.flipHorizontal;
            this.flipVertical = this.delegate.flipVertical;
        }

        TransformState rotateLeft() {
            return new TransformState(delegate.rotateLeft());
        }

        TransformState rotateRight() {
            return new TransformState(delegate.rotateRight());
        }

        TransformState flipHorizontal() {
            return new TransformState(delegate.flipHorizontal());
        }

        TransformState flipVertical() {
            return new TransformState(delegate.flipVertical());
        }

        TransformState reset() {
            return new TransformState(delegate.reset());
        }
    }

    static final class AlternatingAssignment {
        final String animalName;
        final OrientationManifestRow.Hemisphere hemisphere;
        final String region;

        AlternatingAssignment(String animalName,
                              OrientationManifestRow.Hemisphere hemisphere,
                              String region) {
            this.animalName = animalName == null ? "" : animalName;
            this.hemisphere = hemisphere == null
                    ? OrientationManifestRow.Hemisphere.UNKNOWN : hemisphere;
            this.region = region == null ? "" : region;
        }
    }

    private static final class AliasParts {
        final String animalName;
        final String region;

        AliasParts(String animalName, String region) {
            this.animalName = animalName == null ? "" : animalName;
            this.region = region == null ? "" : region;
        }
    }

    private static final class SetupRow {
        final String imageKey;
        final String sourceFile;
        final int seriesIndex;
        final String originalName;
        final String displayName;
        final String animalName;
        final OrientationManifestRow.Hemisphere hemisphere;
        final String region;
        final OrientationManifestRow.RotationDegrees rotateDegrees;
        final boolean flipHorizontal;
        final boolean flipVertical;
        final OrientationManifestRow.ViewPolicy viewPolicy;
        final OrientationManifestRow.DecisionSource decisionSource;
        final OrientationManifestRow.ConfirmationState confirmed;
        final String notes;
        final String status;

        SetupRow(String imageKey,
                 String sourceFile,
                 int seriesIndex,
                 String originalName,
                 String displayName,
                 String animalName,
                 OrientationManifestRow.Hemisphere hemisphere,
                 String region,
                 OrientationManifestRow.RotationDegrees rotateDegrees,
                 boolean flipHorizontal,
                 boolean flipVertical,
                 OrientationManifestRow.ViewPolicy viewPolicy,
                 OrientationManifestRow.DecisionSource decisionSource,
                 OrientationManifestRow.ConfirmationState confirmed,
                 String notes,
                 String status) {
            this.imageKey = imageKey == null ? "" : imageKey;
            this.sourceFile = sourceFile == null ? "" : sourceFile;
            this.seriesIndex = seriesIndex < 1 ? 1 : seriesIndex;
            this.originalName = originalName == null ? "" : originalName;
            this.displayName = displayName == null ? "" : displayName;
            this.animalName = animalName == null ? "" : animalName;
            this.hemisphere = hemisphere == null ? OrientationManifestRow.Hemisphere.UNKNOWN : hemisphere;
            this.region = region == null ? "" : region;
            this.rotateDegrees = rotateDegrees == null ? OrientationManifestRow.RotationDegrees.DEG_0 : rotateDegrees;
            this.flipHorizontal = flipHorizontal;
            this.flipVertical = flipVertical;
            this.viewPolicy = viewPolicy == null ? OrientationManifestRow.ViewPolicy.MANUAL_ONLY : viewPolicy;
            this.decisionSource = decisionSource == null ? OrientationManifestRow.DecisionSource.UNKNOWN : decisionSource;
            this.confirmed = confirmed == null ? OrientationManifestRow.ConfirmationState.NO : confirmed;
            this.notes = notes == null ? "" : notes;
            this.status = status == null ? STATUS_UNKNOWN : status;
        }

        static SetupRow fromExisting(OrientationManifestRow row) {
            return new SetupRow(
                    row.imageKey,
                    row.sourceFile,
                    row.seriesIndex,
                    row.originalName,
                    row.displayName,
                    row.animalName,
                    row.hemisphere,
                    row.region,
                    row.rotateDegrees,
                    row.flipHorizontal,
                    row.flipVertical,
                    row.viewPolicy,
                    OrientationManifestRow.DecisionSource.SAVED_MANIFEST,
                    row.confirmed,
                    row.notes,
                    row.isConfirmed() ? STATUS_SAVED : STATUS_MANUAL);
        }

        OrientationManifestRow toManifestRow() {
            return new OrientationManifestRow(
                    imageKey,
                    sourceFile,
                    seriesIndex,
                    originalName,
                    displayName,
                    animalName,
                    hemisphere,
                    region,
                    rotateDegrees,
                    flipHorizontal,
                    flipVertical,
                    viewPolicy,
                    decisionSource,
                    confirmed,
                    notes);
        }
    }

    private static final class SetupPanel {
        private final String directory;
        private final List<SetupRow> seedRows;
        private final DefaultTableModel model;
        private final JTable table;
        private final JPanel component;
        private final JTextArea lhAliases;
        private final JTextArea rhAliases;
        private final JLabel previewTitle;
        private final JLabel previewImage;
        private final JLabel previewStatus;
        private boolean internalUpdate = false;
        private int previewRequest = 0;
        private DeferredImageSupplier previewSupplier;

        SetupPanel(String directory,
                   List<SetupRow> rows,
                   Map<OrientationManifestRow.Hemisphere, List<String>> aliases) {
            this.directory = directory == null ? "" : directory;
            this.seedRows = rows == null ? Collections.<SetupRow>emptyList() : rows;

            String[] columnNames = {
                    "Image / Series", "Animal Name", "Hemisphere", "Region",
                    "Rotate", "Flip H", "Flip V", "View Policy", "Status",
                    "Confirmed", "Notes"
            };
            this.model = new DefaultTableModel(toData(seedRows), columnNames) {
                @Override
                public boolean isCellEditable(int row, int column) {
                    return column != COL_IMAGE && column != COL_STATUS;
                }

                @Override
                public Class<?> getColumnClass(int columnIndex) {
                    if (columnIndex == COL_FLIP_H || columnIndex == COL_FLIP_V
                            || columnIndex == COL_CONFIRMED) {
                        return Boolean.class;
                    }
                    return String.class;
                }
            };

            this.table = new JTable(model);
            configureTable();

            this.lhAliases = new JTextArea(aliasText(aliases, OrientationManifestRow.Hemisphere.LH), 2, 28);
            this.rhAliases = new JTextArea(aliasText(aliases, OrientationManifestRow.Hemisphere.RH), 2, 28);
            this.previewTitle = new JLabel("Select an image row");
            this.previewImage = new JLabel("No preview", SwingConstants.CENTER);
            this.previewStatus = new JLabel(" ");

            JScrollPane tableScroll = new JScrollPane(table);
            tableScroll.setPreferredSize(new Dimension(820, Math.min(420, 80 + seedRows.size() * 26)));
            JSplitPane split = new JSplitPane(
                    JSplitPane.HORIZONTAL_SPLIT,
                    tableScroll,
                    previewPanel());
            split.setResizeWeight(1.0);
            split.setBorder(null);

            this.component = new JPanel(new BorderLayout(8, 8));
            component.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
            component.add(instructionLabel(), BorderLayout.NORTH);
            component.add(split, BorderLayout.CENTER);
            component.add(aliasPanel(), BorderLayout.SOUTH);

            model.addTableModelListener(new TableModelListener() {
                @Override
                public void tableChanged(TableModelEvent e) {
                    if (internalUpdate || e.getFirstRow() < 0) return;
                    int column = e.getColumn();
                    if (column == TableModelEvent.ALL_COLUMNS || column == COL_STATUS) return;
                    for (int row = e.getFirstRow(); row <= e.getLastRow() && row < model.getRowCount(); row++) {
                        markManualOrIncomplete(row);
                    }
                    if (affectsPreview(e)) {
                        updatePreviewForSelection();
                    }
                }
            });
            table.getSelectionModel().addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting()) updatePreviewForSelection();
            });
            if (model.getRowCount() > 0) {
                table.setRowSelectionInterval(0, 0);
            }
        }

        JComponent getComponent() {
            return component;
        }

        List<OrientationManifestRow> collectRows() {
            if (table.isEditing()) table.getCellEditor().stopCellEditing();
            ArrayList<OrientationManifestRow> rows = new ArrayList<OrientationManifestRow>();
            for (int r = 0; r < model.getRowCount(); r++) {
                SetupRow seed = seedRows.get(r);
                OrientationManifestRow.Hemisphere hemisphere =
                        OrientationManifestRow.Hemisphere.fromCsv(valueAt(r, COL_HEMISPHERE));
                OrientationManifestRow.ConfirmationState confirmed =
                        booleanAt(r, COL_CONFIRMED)
                                ? OrientationManifestRow.ConfirmationState.YES
                                : OrientationManifestRow.ConfirmationState.NO;
                OrientationManifestRow.DecisionSource source = sourceForStatus(valueAt(r, COL_STATUS), seed.decisionSource);
                rows.add(new OrientationManifestRow(
                        seed.imageKey,
                        seed.sourceFile,
                        seed.seriesIndex,
                        seed.originalName,
                        seed.displayName,
                        valueAt(r, COL_ANIMAL),
                        hemisphere,
                        valueAt(r, COL_REGION),
                        OrientationManifestRow.RotationDegrees.fromCsv(valueAt(r, COL_ROTATE)),
                        booleanAt(r, COL_FLIP_H),
                        booleanAt(r, COL_FLIP_V),
                        OrientationManifestRow.ViewPolicy.fromCsv(valueAt(r, COL_POLICY)),
                        source,
                        confirmed,
                        valueAt(r, COL_NOTES)));
            }
            return rows;
        }

        LinkedHashMap<OrientationManifestRow.Hemisphere, List<String>> collectAliases() {
            LinkedHashMap<OrientationManifestRow.Hemisphere, List<String>> aliases =
                    new LinkedHashMap<OrientationManifestRow.Hemisphere, List<String>>();
            aliases.put(OrientationManifestRow.Hemisphere.LH, parseAliasText(lhAliases.getText()));
            aliases.put(OrientationManifestRow.Hemisphere.RH, parseAliasText(rhAliases.getText()));
            return aliases;
        }

        private void configureTable() {
            table.setRowHeight(24);
            table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
            table.setDefaultRenderer(Object.class, new StatusRenderer());
            table.setDefaultRenderer(Boolean.class, new StatusRenderer());
            table.getColumnModel().getColumn(COL_IMAGE).setPreferredWidth(240);
            table.getColumnModel().getColumn(COL_ANIMAL).setPreferredWidth(130);
            table.getColumnModel().getColumn(COL_HEMISPHERE).setPreferredWidth(90);
            table.getColumnModel().getColumn(COL_REGION).setPreferredWidth(90);
            table.getColumnModel().getColumn(COL_ROTATE).setPreferredWidth(70);
            table.getColumnModel().getColumn(COL_POLICY).setPreferredWidth(150);
            table.getColumnModel().getColumn(COL_NOTES).setPreferredWidth(220);

            table.getColumnModel().getColumn(COL_HEMISPHERE).setCellEditor(
                    new DefaultCellEditor(new JComboBox<String>(new String[]{"LH", "RH", "Unknown"})));
            table.getColumnModel().getColumn(COL_ROTATE).setCellEditor(
                    new DefaultCellEditor(new JComboBox<String>(new String[]{"0", "90", "180", "270"})));
            table.getColumnModel().getColumn(COL_POLICY).setCellEditor(
                    new DefaultCellEditor(new JComboBox<String>(new String[]{
                            "ManualOnly", "KeepAsAcquired", "StandardizeToLeft", "StandardizeToRight"})));
            table.getColumnModel().getColumn(COL_FLIP_H).setCellEditor(new DefaultCellEditor(new JCheckBox()));
            table.getColumnModel().getColumn(COL_FLIP_V).setCellEditor(new DefaultCellEditor(new JCheckBox()));
            table.getColumnModel().getColumn(COL_CONFIRMED).setCellEditor(new DefaultCellEditor(new JCheckBox()));
        }

        private JPanel previewPanel() {
            JPanel panel = new JPanel(new BorderLayout(6, 6));
            panel.setBorder(BorderFactory.createTitledBorder("Preview"));
            panel.setPreferredSize(new Dimension(340, 420));

            previewTitle.setHorizontalAlignment(SwingConstants.CENTER);
            panel.add(previewTitle, BorderLayout.NORTH);

            previewImage.setPreferredSize(new Dimension(320, 300));
            previewImage.setOpaque(true);
            previewImage.setBackground(Color.WHITE);
            previewImage.setBorder(BorderFactory.createLineBorder(new Color(210, 210, 210)));
            panel.add(previewImage, BorderLayout.CENTER);

            JPanel controls = new JPanel(new GridLayout(0, 2, 4, 4));
            JButton rotateLeft = new JButton("Rotate left");
            rotateLeft.addActionListener(e -> updateSelectedTransform(currentTransform().rotateLeft()));
            controls.add(rotateLeft);

            JButton rotateRight = new JButton("Rotate right");
            rotateRight.addActionListener(e -> updateSelectedTransform(currentTransform().rotateRight()));
            controls.add(rotateRight);

            JButton flipHorizontal = new JButton("Flip horizontal");
            flipHorizontal.addActionListener(e -> updateSelectedTransform(currentTransform().flipHorizontal()));
            controls.add(flipHorizontal);

            JButton flipVertical = new JButton("Flip vertical");
            flipVertical.addActionListener(e -> updateSelectedTransform(currentTransform().flipVertical()));
            controls.add(flipVertical);

            JButton reset = new JButton("Reset");
            reset.addActionListener(e -> updateSelectedTransform(currentTransform().reset()));
            controls.add(reset);

            JButton apply = new JButton("Apply current transform to selected rows");
            apply.addActionListener(e -> applyCurrentTransformToSelectedRows());
            controls.add(apply);

            JButton alternatingPairs = new JButton("Assign alternating pairs...");
            alternatingPairs.addActionListener(e -> showAlternatingPairsDialog());

            JPanel south = new JPanel(new BorderLayout(4, 4));
            south.add(controls, BorderLayout.CENTER);
            south.add(alternatingPairs, BorderLayout.NORTH);
            south.add(previewStatus, BorderLayout.SOUTH);
            panel.add(south, BorderLayout.SOUTH);
            return panel;
        }

        private JLabel instructionLabel() {
            return new JLabel(
                    "<html><body style='width:780px'>"
                    + "<b>Image orientation setup</b><br>"
                    + "Review one row per image or series. Yellow rows are filename-alias suggestions and need confirmation. "
                    + "Red rows are incomplete. Saving writes Image Orientation.csv to the project summary tables folder."
                    + "</body></html>");
        }

        private JPanel aliasPanel() {
            JPanel panel = new JPanel(new BorderLayout(6, 6));
            panel.setBorder(BorderFactory.createTitledBorder("Hemisphere aliases"));

            JPanel editors = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
            editors.add(new JLabel("LH"));
            editors.add(new JScrollPane(lhAliases));
            editors.add(new JLabel("RH"));
            editors.add(new JScrollPane(rhAliases));
            panel.add(editors, BorderLayout.CENTER);

            JButton apply = new JButton("Apply aliases to suggestions");
            apply.addActionListener(e -> applyAliases());
            panel.add(apply, BorderLayout.EAST);
            return panel;
        }

        private void applyAliases() {
            final LinkedHashMap<OrientationManifestRow.Hemisphere, List<String>> aliases = collectAliases();
            internalUpdate = true;
            try {
                for (int r = 0; r < seedRows.size(); r++) {
                    String status = valueAt(r, COL_STATUS);
                    if (STATUS_SAVED.equals(status) || STATUS_MANUAL.equals(status)
                            || booleanAt(r, COL_CONFIRMED)) {
                        continue;
                    }
                    SetupRow seed = seedRows.get(r);
                    SetupRow suggested = suggestRow(
                            seed.imageKey, seed.sourceFile, seed.seriesIndex, seed.originalName, aliases);
                    model.setValueAt(suggested.animalName, r, COL_ANIMAL);
                    model.setValueAt(csvHemisphere(suggested.hemisphere), r, COL_HEMISPHERE);
                    model.setValueAt(suggested.region, r, COL_REGION);
                    model.setValueAt(csvRotate(suggested.rotateDegrees), r, COL_ROTATE);
                    model.setValueAt(Boolean.valueOf(suggested.flipHorizontal), r, COL_FLIP_H);
                    model.setValueAt(Boolean.valueOf(suggested.flipVertical), r, COL_FLIP_V);
                    model.setValueAt(csvPolicy(suggested.viewPolicy), r, COL_POLICY);
                    model.setValueAt(suggested.status, r, COL_STATUS);
                    model.setValueAt(Boolean.valueOf(suggested.confirmed == OrientationManifestRow.ConfirmationState.YES), r, COL_CONFIRMED);
                    model.setValueAt(suggested.notes, r, COL_NOTES);
                }
            } finally {
                internalUpdate = false;
            }
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    table.repaint();
                    updatePreviewForSelection();
                }
            });
        }

        private TransformState currentTransform() {
            int row = selectedModelRow();
            if (row < 0) {
                return new TransformState(OrientationManifestRow.RotationDegrees.DEG_0, false, false);
            }
            return transformFrom(valueAt(row, COL_ROTATE), booleanAt(row, COL_FLIP_H), booleanAt(row, COL_FLIP_V));
        }

        private void updateSelectedTransform(TransformState state) {
            int row = selectedModelRow();
            if (row < 0 || state == null) return;
            stopEditing();
            internalUpdate = true;
            try {
                applyTransformState(row, state);
            } finally {
                internalUpdate = false;
            }
            markManualOrIncomplete(row);
            table.repaint();
            updatePreviewForSelection();
        }

        private void applyCurrentTransformToSelectedRows() {
            int current = selectedModelRow();
            int[] selected = selectedModelRows();
            if (current < 0 || selected.length == 0) return;
            stopEditing();
            TransformState state = currentTransform();
            internalUpdate = true;
            try {
                for (int row : selected) {
                    applyTransformState(row, state);
                }
            } finally {
                internalUpdate = false;
            }
            for (int row : selected) {
                markManualOrIncomplete(row);
            }
            table.repaint();
            updatePreviewForSelection();
        }

        private void applyTransformState(int row, TransformState state) {
            model.setValueAt(csvRotate(state.rotateDegrees), row, COL_ROTATE);
            model.setValueAt(Boolean.valueOf(state.flipHorizontal), row, COL_FLIP_H);
            model.setValueAt(Boolean.valueOf(state.flipVertical), row, COL_FLIP_V);
        }

        private void showAlternatingPairsDialog() {
            int[] targetRows = selectedModelRows();
            if (targetRows.length < 2) {
                targetRows = allModelRows();
            }
            if (targetRows.length < 2) {
                previewStatus.setText("Need at least two rows for alternating pairs.");
                return;
            }

            JCheckBox pairRows = new JCheckBox("Pair rows as same animal", true);
            JComboBox<String> pattern = new JComboBox<String>(new String[]{
                    "Row 1 = LH, Row 2 = RH",
                    "Row 1 = RH, Row 2 = LH"
            });
            JTextField animalPrefix = new JTextField("Mouse", 12);
            JSpinner firstNumber = new JSpinner(new SpinnerNumberModel(1, 1, 9999, 1));
            JTextField region = new JTextField("SCN", 12);

            JPanel form = new JPanel(new GridLayout(0, 2, 6, 6));
            form.add(new JLabel("Pairing"));
            form.add(pairRows);
            form.add(new JLabel("Pattern"));
            form.add(pattern);
            form.add(new JLabel("Animal names"));
            form.add(animalPrefix);
            form.add(new JLabel("First number"));
            form.add(firstNumber);
            form.add(new JLabel("Region"));
            form.add(region);

            int result = JOptionPane.showConfirmDialog(
                    component,
                    form,
                    "Assign alternating pairs",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION) return;

            boolean firstIsLeft = pattern.getSelectedIndex() == 0;
            int start = ((Number) firstNumber.getValue()).intValue();
            List<AlternatingAssignment> assignments = buildAlternatingAssignments(
                    targetRows.length,
                    firstIsLeft,
                    pairRows.isSelected(),
                    animalPrefix.getText(),
                    start,
                    region.getText());
            stopEditing();
            internalUpdate = true;
            try {
                for (int i = 0; i < assignments.size(); i++) {
                    int row = targetRows[i];
                    AlternatingAssignment assignment = assignments.get(i);
                    model.setValueAt(assignment.animalName, row, COL_ANIMAL);
                    model.setValueAt(csvHemisphere(assignment.hemisphere), row, COL_HEMISPHERE);
                    model.setValueAt(assignment.region, row, COL_REGION);
                    model.setValueAt(csvPolicy(OrientationManifestRow.ViewPolicy.STANDARDIZE_TO_LEFT), row, COL_POLICY);
                    model.setValueAt(Boolean.TRUE, row, COL_CONFIRMED);
                    model.setValueAt(STATUS_MANUAL, row, COL_STATUS);
                }
            } finally {
                internalUpdate = false;
            }
            table.repaint();
            updatePreviewForSelection();
        }

        private boolean affectsPreview(TableModelEvent e) {
            int column = e.getColumn();
            return column == TableModelEvent.ALL_COLUMNS
                    || column == COL_ROTATE
                    || column == COL_FLIP_H
                    || column == COL_FLIP_V
                    || column == COL_HEMISPHERE
                    || column == COL_POLICY;
        }

        private void updatePreviewForSelection() {
            final int row = selectedModelRow();
            final int request = ++previewRequest;
            if (row < 0 || row >= seedRows.size()) {
                previewTitle.setText("Select an image row");
                previewImage.setIcon(null);
                previewImage.setText("No preview");
                previewStatus.setText(" ");
                return;
            }
            final SetupRow seed = seedRows.get(row);
            previewTitle.setText(seed.displayName);
            previewImage.setIcon(null);
            previewImage.setText("Loading preview...");
            previewStatus.setText(transformStatus(row));
            final int rotateDegrees = OrientationManifestRow.RotationDegrees.fromCsv(valueAt(row, COL_ROTATE)).degrees();
            final boolean flipHorizontal = booleanAt(row, COL_FLIP_H);
            final boolean flipVertical = booleanAt(row, COL_FLIP_V);
            final String hemisphere = valueAt(row, COL_HEMISPHERE);
            final OrientationManifestRow.ViewPolicy viewPolicy =
                    OrientationManifestRow.ViewPolicy.fromCsv(valueAt(row, COL_POLICY));

            new SwingWorker<ImageIcon, Void>() {
                @Override
                protected ImageIcon doInBackground() throws Exception {
                    return createPreviewIcon(row, 320, 300,
                            rotateDegrees, flipHorizontal, flipVertical, hemisphere, viewPolicy);
                }

                @Override
                protected void done() {
                    if (request != previewRequest) return;
                    try {
                        ImageIcon icon = get();
                        previewImage.setIcon(icon);
                        previewImage.setText(icon == null ? "No preview available" : "");
                    } catch (Exception e) {
                        previewImage.setIcon(null);
                        previewImage.setText("Preview unavailable");
                        previewStatus.setText(e.getCause() == null
                                ? e.getMessage()
                                : e.getCause().getMessage());
                    }
                }
            }.execute();
        }

        private ImageIcon createPreviewIcon(int row,
                                            int maxWidth,
                                            int maxHeight,
                                            int rotateDegrees,
                                            boolean flipHorizontal,
                                            boolean flipVertical,
                                            String hemisphere,
                                            OrientationManifestRow.ViewPolicy viewPolicy) throws Exception {
            SetupRow seed = seedRows.get(row);
            ImagePlus source = null;
            ImagePlus preview = null;
            try {
                source = getPreviewSupplier().openSeries(seed.seriesIndex - 1);
                preview = middleSliceDuplicate(source);
                if (preview == null) return null;
                OrientationOps.applyTransform(
                        preview,
                        rotateDegrees,
                        flipHorizontal,
                        flipVertical,
                        hemisphere,
                        viewPolicy);
                return scaledIcon(preview, maxWidth, maxHeight);
            } finally {
                closeImage(preview);
                closeImage(source);
            }
        }

        private synchronized DeferredImageSupplier getPreviewSupplier() throws Exception {
            if (previewSupplier == null) {
                previewSupplier = createPreviewSupplier(directory);
            }
            return previewSupplier;
        }

        private DeferredImageSupplier createPreviewSupplier(String directory) throws Exception {
            File dir = new File(directory);
            ImageSourceDispatcher.SourceMode mode = ImageSourceDispatcher.detectMode(directory);
            if (mode == ImageSourceDispatcher.SourceMode.CONTAINER) {
                return new DeferredImageSupplier(ImageSourceDispatcher.selectContainer(dir));
            }
            if (mode == ImageSourceDispatcher.SourceMode.TIFF_INPUT_SUBFOLDER) {
                return new DeferredImageSupplier(
                        ImageSourceDispatcher.listTiffs(new File(dir, "input")), "input");
            }
            return new DeferredImageSupplier(ImageSourceDispatcher.listTiffs(dir), dir.getName());
        }

        private ImagePlus middleSliceDuplicate(ImagePlus source) {
            if (source == null || source.getStack() == null || source.getStackSize() < 1) return null;
            int slices = Math.max(1, source.getNSlices());
            int z = Math.max(1, (slices + 1) / 2);
            int stackIndex = source.getStackIndex(1, z, 1);
            if (stackIndex < 1 || stackIndex > source.getStackSize()) {
                stackIndex = Math.min(source.getStackSize(), Math.max(1, source.getStackSize() / 2));
            }
            ImageProcessor processor = source.getStack().getProcessor(stackIndex).duplicate();
            return new ImagePlus(source.getTitle() + " preview", processor);
        }

        private ImageIcon scaledIcon(ImagePlus preview, int maxWidth, int maxHeight) {
            BufferedImage image = preview.getProcessor().getBufferedImage();
            int width = Math.max(1, image.getWidth());
            int height = Math.max(1, image.getHeight());
            double scale = Math.min(
                    maxWidth / (double) width,
                    maxHeight / (double) height);
            scale = Math.min(1.0, Math.max(0.05, scale));
            int scaledWidth = Math.max(1, (int) Math.round(width * scale));
            int scaledHeight = Math.max(1, (int) Math.round(height * scale));
            Image scaled = image.getScaledInstance(scaledWidth, scaledHeight, Image.SCALE_SMOOTH);
            BufferedImage buffered = new BufferedImage(scaledWidth, scaledHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = buffered.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(scaled, 0, 0, null);
            } finally {
                g.dispose();
            }
            return new ImageIcon(buffered);
        }

        private void closeImage(ImagePlus image) {
            if (image == null) return;
            image.changes = false;
            image.close();
        }

        private String transformStatus(int row) {
            return "Rotate " + valueAt(row, COL_ROTATE)
                    + " | Flip H " + OrientationManifestRow.yesNo(booleanAt(row, COL_FLIP_H))
                    + " | Flip V " + OrientationManifestRow.yesNo(booleanAt(row, COL_FLIP_V));
        }

        private int selectedModelRow() {
            int viewRow = table.getSelectionModel().getLeadSelectionIndex();
            if (viewRow < 0 || !table.getSelectionModel().isSelectedIndex(viewRow)) {
                viewRow = table.getSelectedRow();
            }
            return viewRow < 0 ? -1 : table.convertRowIndexToModel(viewRow);
        }

        private int[] selectedModelRows() {
            int[] viewRows = table.getSelectedRows();
            int[] modelRows = new int[viewRows.length];
            for (int i = 0; i < viewRows.length; i++) {
                modelRows[i] = table.convertRowIndexToModel(viewRows[i]);
            }
            Arrays.sort(modelRows);
            return modelRows;
        }

        private int[] allModelRows() {
            int[] rows = new int[model.getRowCount()];
            for (int i = 0; i < rows.length; i++) {
                rows[i] = i;
            }
            return rows;
        }

        private void stopEditing() {
            if (table.isEditing()) table.getCellEditor().stopCellEditing();
        }

        private void markManualOrIncomplete(int row) {
            String animal = valueAt(row, COL_ANIMAL);
            OrientationManifestRow.Hemisphere hemi =
                    OrientationManifestRow.Hemisphere.fromCsv(valueAt(row, COL_HEMISPHERE));
            String status = animal.trim().isEmpty()
                    || hemi == OrientationManifestRow.Hemisphere.UNKNOWN
                    ? STATUS_INCOMPLETE : STATUS_MANUAL;
            internalUpdate = true;
            try {
                model.setValueAt(status, row, COL_STATUS);
            } finally {
                internalUpdate = false;
            }
        }

        private OrientationManifestRow.DecisionSource sourceForStatus(
                String status,
                OrientationManifestRow.DecisionSource seedSource) {
            if (STATUS_STRICT.equals(status)) return OrientationManifestRow.DecisionSource.STRICT_FILENAME;
            if (STATUS_ALIAS.equals(status)) return OrientationManifestRow.DecisionSource.FILENAME_ALIAS;
            if (STATUS_SAVED.equals(status)) return OrientationManifestRow.DecisionSource.SAVED_MANIFEST;
            if (STATUS_MANUAL.equals(status) || STATUS_INCOMPLETE.equals(status)) {
                return OrientationManifestRow.DecisionSource.MANUAL;
            }
            return seedSource == null ? OrientationManifestRow.DecisionSource.UNKNOWN : seedSource;
        }

        private String valueAt(int row, int column) {
            Object value = model.getValueAt(row, column);
            return value == null ? "" : value.toString().trim();
        }

        private boolean booleanAt(int row, int column) {
            Object value = model.getValueAt(row, column);
            if (value instanceof Boolean) return ((Boolean) value).booleanValue();
            return OrientationManifestRow.parseYesNo(value == null ? "" : value.toString());
        }

        private Object[][] toData(List<SetupRow> rows) {
            Object[][] data = new Object[rows.size()][11];
            for (int i = 0; i < rows.size(); i++) {
                SetupRow row = rows.get(i);
                data[i][COL_IMAGE] = row.displayName;
                data[i][COL_ANIMAL] = row.animalName;
                data[i][COL_HEMISPHERE] = csvHemisphere(row.hemisphere);
                data[i][COL_REGION] = row.region;
                data[i][COL_ROTATE] = csvRotate(row.rotateDegrees);
                data[i][COL_FLIP_H] = Boolean.valueOf(row.flipHorizontal);
                data[i][COL_FLIP_V] = Boolean.valueOf(row.flipVertical);
                data[i][COL_POLICY] = csvPolicy(row.viewPolicy);
                data[i][COL_STATUS] = row.status;
                data[i][COL_CONFIRMED] = Boolean.valueOf(row.confirmed == OrientationManifestRow.ConfirmationState.YES);
                data[i][COL_NOTES] = row.notes;
            }
            return data;
        }

        private String aliasText(Map<OrientationManifestRow.Hemisphere, List<String>> aliases,
                                 OrientationManifestRow.Hemisphere hemisphere) {
            if (aliases == null || aliases.get(hemisphere) == null) return "";
            StringBuilder sb = new StringBuilder();
            for (String alias : aliases.get(hemisphere)) {
                if (alias == null || alias.trim().isEmpty()) continue;
                if (sb.length() > 0) sb.append(", ");
                sb.append(alias.trim());
            }
            return sb.toString();
        }

        private List<String> parseAliasText(String text) {
            ArrayList<String> aliases = new ArrayList<String>();
            if (text == null) return aliases;
            String[] parts = text.split("[,\\n\\r]+");
            for (String part : parts) {
                String alias = part == null ? "" : part.trim();
                if (!alias.isEmpty()) aliases.add(alias);
            }
            return aliases;
        }
    }

    private static final class StatusRenderer extends DefaultTableCellRenderer {
        private final Color saved = new Color(222, 245, 226);
        private final Color strict = new Color(236, 236, 236);
        private final Color alias = new Color(255, 246, 203);
        private final Color unknown = new Color(255, 226, 226);

        @Override
        public Component getTableCellRendererComponent(JTable table,
                                                       Object value,
                                                       boolean isSelected,
                                                       boolean hasFocus,
                                                       int row,
                                                       int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            Object statusValue = table.getModel().getValueAt(row, COL_STATUS);
            String status = statusValue == null ? "" : statusValue.toString();
            if (!isSelected) {
                if (STATUS_SAVED.equals(status) || STATUS_MANUAL.equals(status)) {
                    c.setBackground(saved);
                } else if (STATUS_STRICT.equals(status)) {
                    c.setBackground(strict);
                } else if (STATUS_ALIAS.equals(status)) {
                    c.setBackground(alias);
                } else {
                    c.setBackground(unknown);
                }
            }
            if (column == COL_STATUS) {
                setToolTipText(tooltipFor(status));
            } else {
                setToolTipText(null);
            }
            return c;
        }

        private String tooltipFor(String status) {
            if (STATUS_SAVED.equals(status)) return "Confirmed row loaded from the saved manifest.";
            if (STATUS_STRICT.equals(status)) return "Recognised from the standard filename convention.";
            if (STATUS_ALIAS.equals(status)) return "Suggested from a filename alias; confirm before use.";
            if (STATUS_MANUAL.equals(status)) return "Edited in this table.";
            return "Needs a hemisphere and animal name before downstream use.";
        }
    }
}
