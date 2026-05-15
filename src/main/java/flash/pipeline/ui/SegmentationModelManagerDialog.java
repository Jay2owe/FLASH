package flash.pipeline.ui;

import flash.pipeline.help.AnalysisHelpCatalog;
import flash.pipeline.help.AnalysisHelpDialog;
import flash.pipeline.segmentation.catalog.ModelCatalogIO;
import flash.pipeline.segmentation.catalog.ModelEntry;
import ij.IJ;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Project-scoped manager for custom segmentation model catalog entries.
 */
public final class SegmentationModelManagerDialog extends PipelineDialog {
    private static final String ALL = "All";

    private final Path projectRoot;
    private final SegmentationModelManagerController controller;
    private final JComboBox<String> engineFilter;
    private final JComboBox<String> sourceFilter;
    private final DefaultTableModel tableModel;
    private final JTable table;
    private final JTextArea detailsArea;
    private final JButton editButton;
    private final JButton deleteButton;
    private List<ModelEntry> visibleEntries = new ArrayList<ModelEntry>();

    public SegmentationModelManagerDialog(Path projectRoot) {
        this(null, projectRoot, null);
    }

    public SegmentationModelManagerDialog(Path projectRoot, ModelEntry.Engine initialEngineFilter) {
        this(null, projectRoot, initialEngineFilter);
    }

    public SegmentationModelManagerDialog(Window owner,
                                          Path projectRoot,
                                          ModelEntry.Engine initialEngineFilter) {
        super(owner, "Segmentation Model Manager");
        if (projectRoot == null) {
            throw new IllegalArgumentException("Project root must not be null.");
        }
        this.projectRoot = projectRoot;
        this.controller = new SegmentationModelManagerController(projectRoot);

        setDefaultButtonsVisible(false);
        addHeader("Segmentation Models");
        addComponent(buildHelpRow());

        JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        filters.setOpaque(false);
        filters.add(new JLabel("Engine:"));
        engineFilter = new JComboBox<String>(new String[]{
                ALL,
                ModelEntry.Engine.STARDIST.jsonValue(),
                ModelEntry.Engine.CELLPOSE.jsonValue(),
                ModelEntry.Engine.SMILE_RF.jsonValue()
        });
        if (initialEngineFilter != null) {
            engineFilter.setSelectedItem(initialEngineFilter.jsonValue());
        }
        filters.add(engineFilter);
        filters.add(new JLabel("Source:"));
        sourceFilter = new JComboBox<String>(new String[]{
                ALL,
                ModelEntry.Source.STOCK_RESOURCE.jsonValue(),
                ModelEntry.Source.STOCK_BUILTIN.jsonValue(),
                ModelEntry.Source.USER_IMPORTED.jsonValue(),
                ModelEntry.Source.USER_TRAINED.jsonValue()
        });
        filters.add(sourceFilter);
        addComponent(filters);

        tableModel = new DefaultTableModel(new Object[]{"Name", "Engine", "Source", "Date"}, 0) {
            @Override public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(24);
        table.getColumnModel().getColumn(0).setPreferredWidth(340);
        table.getColumnModel().getColumn(1).setPreferredWidth(90);
        table.getColumnModel().getColumn(2).setPreferredWidth(150);
        table.getColumnModel().getColumn(3).setPreferredWidth(90);
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setPreferredSize(new Dimension(760, 230));
        addComponent(tableScroll);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        actions.setOpaque(false);
        JButton addStarDistButton = new JButton("Add StarDist...");
        JButton addCellposeButton = new JButton("Add Cellpose...");
        editButton = new JButton("Edit");
        deleteButton = new JButton("Delete");
        actions.add(addStarDistButton);
        actions.add(addCellposeButton);
        actions.add(editButton);
        actions.add(deleteButton);
        addComponent(actions);

        JPanel detailPanel = new JPanel(new BorderLayout(4, 4));
        detailPanel.setOpaque(false);
        detailPanel.setBorder(BorderFactory.createTitledBorder("Selected entry details"));
        detailsArea = new JTextArea(7, 70);
        detailsArea.setEditable(false);
        detailsArea.setLineWrap(true);
        detailsArea.setWrapStyleWord(true);
        detailPanel.add(new JScrollPane(detailsArea), BorderLayout.CENTER);
        addComponent(detailPanel);

        JLabel footer = new JLabel("Catalog is per-project. Most recent save wins if multiple users edit.");
        footer.setForeground(FlashTheme.TEXT_MUTED);
        footer.setFont(FlashTheme.captionItalic());
        addComponent(footer);

        JButton closeButton = addRightFooterButton("Close");
        closeButton.addActionListener(e -> closeWithAction("close"));

        engineFilter.addActionListener(e -> refreshTable(null));
        sourceFilter.addActionListener(e -> refreshTable(null));
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateSelectionState();
            }
        });
        addStarDistButton.addActionListener(e -> addStarDist());
        addCellposeButton.addActionListener(e -> addCellpose());
        editButton.addActionListener(e -> editSelected());
        deleteButton.addActionListener(e -> deleteSelected());

        refreshTable(null);
    }

    private JPanel buildHelpRow() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        JLabel label = new JLabel("Project model catalog");
        label.setForeground(FlashTheme.TEXT_MUTED);
        label.setFont(FlashTheme.captionItalic());
        row.add(label);
        row.add(javax.swing.Box.createHorizontalStrut(6));
        JButton help = HelpButton.question("About the Custom Model Manager.");
        help.addActionListener(e -> AnalysisHelpDialog.show(
                getWindow(), AnalysisHelpCatalog.CUSTOM_MODEL_MANAGER));
        row.add(help);
        return row;
    }

    public static void showManager(Window owner,
                                   Path projectRoot,
                                   ModelEntry.Engine initialEngineFilter) {
        new SegmentationModelManagerDialog(owner, projectRoot, initialEngineFilter).showDialog();
    }

    private void refreshTable(String preferredModelKey) {
        ModelEntry.Engine engine = selectedEngine();
        ModelEntry.Source source = selectedSource();
        visibleEntries = controller.list(engine, source);

        tableModel.setRowCount(0);
        for (ModelEntry entry : visibleEntries) {
            tableModel.addRow(new Object[]{
                    displayName(entry),
                    entry.engine.jsonValue(),
                    sourceLabel(entry),
                    dateLabel(entry)
            });
        }

        int select = -1;
        if (preferredModelKey != null) {
            for (int i = 0; i < visibleEntries.size(); i++) {
                if (preferredModelKey.equals(visibleEntries.get(i).modelKey)) {
                    select = i;
                    break;
                }
            }
        }
        if (select < 0 && !visibleEntries.isEmpty()) {
            select = 0;
        }
        if (select >= 0) {
            table.setRowSelectionInterval(select, select);
        } else {
            updateSelectionState();
        }
    }

    private void updateSelectionState() {
        ModelEntry entry = selectedEntry();
        boolean editable = entry != null && controller.canEdit(entry);
        editButton.setEnabled(editable);
        deleteButton.setEnabled(editable);
        detailsArea.setText(entry == null ? "" : formatDetails(entry));
        detailsArea.setCaretPosition(0);
    }

    private ModelEntry selectedEntry() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= visibleEntries.size()) {
            return null;
        }
        return visibleEntries.get(row);
    }

    private ModelEntry.Engine selectedEngine() {
        Object selected = engineFilter.getSelectedItem();
        if (selected == null || ALL.equals(selected.toString())) {
            return null;
        }
        return ModelEntry.Engine.fromJson(selected.toString());
    }

    private ModelEntry.Source selectedSource() {
        Object selected = sourceFilter.getSelectedItem();
        if (selected == null || ALL.equals(selected.toString())) {
            return null;
        }
        return ModelEntry.Source.fromJson(selected.toString());
    }

    private void addStarDist() {
        PipelineDialog dialog = new PipelineDialog(getWindow(), "Add StarDist Model");
        dialog.addHeader("Add StarDist Model");
        dialog.addMessage("Choose a Fiji-compatible StarDist TensorFlow SavedModel .zip inside this project.");
        final JTextField fileField = dialog.addStringField("ZIP file", "", 40);
        JButton browse = dialog.addFooterButton("Browse...");
        browse.addActionListener(e -> chooseFile(fileField, true));
        final JTextField nameField = dialog.addStringField("Display name", "", 32);
        final JTextField descriptionField = dialog.addStringField("Description", "", 40);
        final JTextField probField = dialog.addNumericField("Default probability threshold", 0.5, 3);
        final JTextField nmsField = dialog.addNumericField("Default NMS threshold", 0.3, 3);
        dialog.addHelpText("The copied file path is managed by FLASH and is not editable later.");
        dialog.setPrimaryButtonText("Add");

        if (!dialog.showDialog()) {
            return;
        }
        try {
            Map<String, Object> defaults = new LinkedHashMap<String, Object>();
            defaults.put("probThresh", Double.valueOf(parseDouble(probField.getText(), 0.5)));
            defaults.put("nmsThresh", Double.valueOf(parseDouble(nmsField.getText(), 0.3)));
            ModelEntry added = controller.addStarDistModel(pathFromField(fileField),
                    nameField.getText(), descriptionField.getText(), defaults);
            setTransientStatus("Added " + added.name + ".");
            refreshTable(added.modelKey);
        } catch (Exception ex) {
            showError("Could not add StarDist model", ex);
        }
    }

    private void addCellpose() {
        PipelineDialog dialog = new PipelineDialog(getWindow(), "Add Cellpose Model");
        dialog.addHeader("Add Cellpose Model");
        final JComboBox<String> mode = dialog.addChoice("Model type",
                new String[]{"Model file", "Registered name"}, "Model file");
        final JTextField fileField = dialog.addStringField("Model file", "", 40);
        JButton browse = dialog.addFooterButton("Browse...");
        browse.addActionListener(e -> chooseFile(fileField, false));
        final JTextField registeredField = dialog.addStringField("Registered name", "", 28);
        final JTextField nameField = dialog.addStringField("Display name", "", 32);
        final JTextField descriptionField = dialog.addStringField("Description", "", 40);
        final JTextField diameterField = dialog.addNumericField("Default diameter", 30.0, 1);
        final JTextField flowField = dialog.addNumericField("Default flow threshold", 0.4, 2);
        final JTextField cellprobField = dialog.addNumericField("Default cellprob threshold", 0.0, 2);
        dialog.addHelpText("Registered names are trusted as Cellpose model names and must not contain spaces.");
        dialog.setPrimaryButtonText("Add");

        if (!dialog.showDialog()) {
            return;
        }
        try {
            Map<String, Object> defaults = new LinkedHashMap<String, Object>();
            defaults.put("diameter", Double.valueOf(parseDouble(diameterField.getText(), 30.0)));
            defaults.put("flowThreshold", Double.valueOf(parseDouble(flowField.getText(), 0.4)));
            defaults.put("cellprobThreshold", Double.valueOf(parseDouble(cellprobField.getText(), 0.0)));

            ModelEntry added;
            if ("Registered name".equals(mode.getSelectedItem())) {
                added = controller.addCellposeRegisteredName(registeredField.getText(),
                        nameField.getText(), descriptionField.getText(), defaults);
            } else {
                Path file = pathFromField(fileField);
                String warning = controller.cellposeFileWarning(file);
                if (!warning.isEmpty()) {
                    IJ.showMessage("Cellpose model file", warning);
                }
                added = controller.addCellposeFileModel(file,
                        nameField.getText(), descriptionField.getText(), defaults);
            }
            setTransientStatus("Added " + added.name + ".");
            refreshTable(added.modelKey);
        } catch (Exception ex) {
            showError("Could not add Cellpose model", ex);
        }
    }

    private void editSelected() {
        ModelEntry entry = selectedEntry();
        if (entry == null || !controller.canEdit(entry)) {
            return;
        }

        PipelineDialog dialog = new PipelineDialog(getWindow(), "Edit Segmentation Model");
        dialog.addHeader("Edit Model");
        dialog.addMessage("Model key: " + entry.modelKey);
        dialog.addHelpText("The file path is not editable. Delete and re-add the model to change it.");
        final JTextField nameField = dialog.addStringField("Display name", entry.name, 36);
        final JTextField descriptionField = dialog.addStringField("Description",
                entry.description == null ? "" : entry.description, 44);
        final JTextArea defaultsArea = new JTextArea(defaultsToText(entry.defaults), 6, 44);
        defaultsArea.setLineWrap(false);
        JPanel defaultsPanel = new JPanel(new BorderLayout(4, 4));
        defaultsPanel.setOpaque(false);
        defaultsPanel.add(new JLabel("Defaults (key=value, one per line)"), BorderLayout.NORTH);
        defaultsPanel.add(new JScrollPane(defaultsArea), BorderLayout.CENTER);
        dialog.addComponent(defaultsPanel);
        dialog.setPrimaryButtonText("Save");

        if (!dialog.showDialog()) {
            return;
        }
        try {
            ModelEntry edited = controller.edit(entry.modelKey, nameField.getText(),
                    descriptionField.getText(), parseDefaults(defaultsArea.getText()));
            setTransientStatus("Saved " + edited.name + ".");
            refreshTable(edited.modelKey);
        } catch (Exception ex) {
            showError("Could not edit model", ex);
        }
    }

    private void deleteSelected() {
        ModelEntry entry = selectedEntry();
        if (entry == null || !controller.canDelete(entry)) {
            return;
        }

        PipelineDialog dialog = new PipelineDialog(getWindow(), "Delete Segmentation Model");
        dialog.addHeader("Delete Model");
        dialog.addMessage("Delete " + entry.name + " from this project catalog?");
        dialog.addHelpText("FLASH will remove the catalog row and the copied model file directory.");
        dialog.setPrimaryButtonText("Delete");
        if (!dialog.showDialog()) {
            return;
        }
        try {
            controller.delete(entry.modelKey);
            setTransientStatus("Deleted " + entry.name + ".");
            refreshTable(null);
        } catch (Exception ex) {
            showError("Could not delete model", ex);
        }
    }

    private void chooseFile(JTextField field, boolean starDistZip) {
        JFileChooser chooser = new JFileChooser(projectRoot.toFile());
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        if (starDistZip) {
            chooser.setFileFilter(new FileNameExtensionFilter("StarDist zip files", "zip"));
        }
        if (chooser.showOpenDialog(getWindow()) == JFileChooser.APPROVE_OPTION) {
            File selected = chooser.getSelectedFile();
            if (selected != null) {
                field.setText(selected.getAbsolutePath());
            }
        }
    }

    private Path pathFromField(JTextField field) throws IOException {
        String text = field == null ? "" : field.getText().trim();
        if (text.isEmpty()) {
            throw new IOException("Choose a model file first.");
        }
        return new File(text).toPath();
    }

    private String displayName(ModelEntry entry) {
        return controller.canEdit(entry) ? entry.name : entry.name + " (stock)";
    }

    private String sourceLabel(ModelEntry entry) {
        if (ModelCatalogIO.isProjectRegisteredBuiltin(entry)) {
            return entry.source.jsonValue() + " (registered)";
        }
        if (entry.isStock()) {
            return entry.source.jsonValue() + " (stock)";
        }
        return entry.source.jsonValue();
    }

    private String dateLabel(ModelEntry entry) {
        Object value = entry.metadata.get("updatedAt");
        if (value == null) value = entry.metadata.get("importedAt");
        if (value == null) value = entry.metadata.get("trainedAt");
        if (value == null) value = entry.metadata.get("createdAt");
        String text = value == null ? "" : String.valueOf(value);
        return text.length() >= 10 ? text.substring(0, 10) : "-";
    }

    private String formatDetails(ModelEntry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append("Model key: ").append(entry.modelKey).append('\n');
        sb.append("Name: ").append(entry.name).append('\n');
        sb.append("Engine: ").append(entry.engine.jsonValue()).append('\n');
        sb.append("Source: ").append(sourceLabel(entry)).append('\n');
        if (entry.description != null && !entry.description.trim().isEmpty()) {
            sb.append("Description: ").append(entry.description).append('\n');
        }
        if (entry.filePath.isPresent()) {
            sb.append("File path: ").append(entry.filePath.get()).append('\n');
        }
        if (entry.resourcePath.isPresent()) {
            sb.append("Resource path: ").append(entry.resourcePath.get()).append('\n');
        }
        if (entry.pretrainedModel.isPresent()) {
            sb.append("Pretrained model: ").append(entry.pretrainedModel.get()).append('\n');
        }
        if (!entry.defaults.isEmpty()) {
            sb.append("Defaults: ").append(entry.defaults).append('\n');
        }
        for (Map.Entry<String, Object> item : entry.metadata.entrySet()) {
            if (!ModelCatalogIO.PROJECT_REGISTERED_METADATA_KEY.equals(item.getKey())) {
                sb.append(item.getKey()).append(": ").append(item.getValue()).append('\n');
            }
        }
        if (!controller.canEdit(entry)) {
            sb.append("Read-only stock entry.");
        }
        return sb.toString().trim();
    }

    private static String defaultsToText(Map<String, Object> defaults) {
        StringBuilder sb = new StringBuilder();
        if (defaults != null) {
            for (Map.Entry<String, Object> entry : defaults.entrySet()) {
                sb.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
            }
        }
        return sb.toString();
    }

    private static Map<String, Object> parseDefaults(String text) throws IOException {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (text == null || text.trim().isEmpty()) {
            return out;
        }
        String[] lines = text.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            int equals = line.indexOf('=');
            if (equals <= 0) {
                throw new IOException("Defaults line " + (i + 1) + " must use key=value.");
            }
            String key = line.substring(0, equals).trim();
            String value = line.substring(equals + 1).trim();
            if (key.isEmpty()) {
                throw new IOException("Defaults line " + (i + 1) + " has an empty key.");
            }
            out.put(key, parseValue(value));
        }
        return out;
    }

    private static Object parseValue(String value) {
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return Boolean.valueOf(value);
        }
        try {
            if (value.indexOf('.') >= 0 || value.indexOf('e') >= 0 || value.indexOf('E') >= 0) {
                return Double.valueOf(value);
            }
            return Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return value;
        }
    }

    private static double parseDouble(String text, double fallback) {
        try {
            return Double.parseDouble(text == null ? "" : text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void showError(String title, Exception ex) {
        String message = ex == null ? "Unknown error." : ex.getMessage();
        IJ.showMessage(title, message == null ? ex.toString() : message);
    }
}
