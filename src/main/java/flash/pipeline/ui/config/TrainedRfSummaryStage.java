package flash.pipeline.ui.config;

import flash.pipeline.help.SetupHelpCatalog;
import flash.pipeline.help.SetupHelpTopic;
import flash.pipeline.segmentation.SegmentationMethod;
import flash.pipeline.segmentation.SegmentationTokenParser;
import flash.pipeline.segmentation.catalog.ModelCatalog;
import flash.pipeline.segmentation.catalog.ModelCatalogIO;
import flash.pipeline.segmentation.catalog.ModelEntry;
import flash.pipeline.ui.FlashTheme;
import flash.pipeline.ui.preview.PreviewPairPanel;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.util.Optional;

public final class TrainedRfSummaryStage implements ConfigQcStage {
    private final SegmentationMethodStage.MethodStore methodStore;
    private ConfigQcActions actions;

    public TrainedRfSummaryStage(SegmentationMethodStage.MethodStore methodStore) {
        if (methodStore == null) {
            throw new IllegalArgumentException("methodStore must not be null");
        }
        this.methodStore = methodStore;
    }

    @Override
    public String title() {
        return "Trained RF";
    }

    @Override
    public SetupHelpTopic helpTopic() {
        return SetupHelpCatalog.SEGMENTATION_METHOD;
    }

    @Override
    public boolean showPreviewDisplayControls() {
        return false;
    }

    @Override
    public void onEnter(ConfigQcContext context, PreviewPairPanel preview) {
        if (preview != null) {
            preview.clearImages();
            preview.setAdjustedState(PreviewPairPanel.PreviewState.EMPTY,
                    "Trained RF segmentation is configured for this channel.");
        }
    }

    @Override
    public JComponent buildControls(ConfigQcContext context, ConfigQcActions actions) {
        this.actions = actions;
        Summary summary = summarize(context);

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 2));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        gbc.insets = new Insets(0, 0, 8, 0);

        JLabel header = new JLabel("Trained RF segmentation");
        header.setFont(FlashTheme.h2());
        header.setForeground(FlashTheme.TEXT_HEADER);
        panel.add(header, gbc);

        gbc.gridy++;
        panel.add(row("Model", summary.modelName), gbc);
        gbc.gridy++;
        panel.add(row("Model key", summary.modelKey), gbc);
        gbc.gridy++;
        panel.add(row("Base engine", summary.baseEngine), gbc);
        gbc.gridy++;
        panel.add(row("Base token", summary.baseToken), gbc);

        gbc.gridy++;
        gbc.insets = new Insets(8, 0, 0, 0);
        JButton change = new JButton("Change Segmentation Method");
        change.addActionListener(e -> {
            if (this.actions != null) {
                this.actions.setStatus("Choose a segmentation method for this channel.");
                this.actions.jumpToStage(SegmentationMethodStage.class.getName());
            }
        });
        panel.add(change, gbc);
        return panel;
    }

    @Override
    public boolean lockIn(ConfigQcContext context) {
        Summary summary = summarize(context);
        if (actions != null) {
            actions.setStatus("Kept Trained RF segmentation: " + summary.modelKey + ".");
        }
        return true;
    }

    private Summary summarize(ConfigQcContext context) {
        SegmentationMethod method = SegmentationTokenParser.parseLenient(methodStore.getMethodToken());
        String modelKey = method.isTrainedRf()
                ? SegmentationMethod.trainedRfModelKey(method)
                : "";
        SegmentationMethod base = method.isTrainedRf()
                ? SegmentationMethod.trainedRfBase(method)
                : SegmentationMethod.classical("classical");
        String modelName = modelName(context, modelKey);
        String baseToken = SegmentationTokenParser.format(base);
        return new Summary(modelKey, modelName, baseEngineLabel(base), baseToken);
    }

    private static String modelName(ConfigQcContext context, String modelKey) {
        String key = clean(modelKey);
        if (key.isEmpty()) {
            return "<missing>";
        }
        File projectRoot = context == null ? null : context.getProjectDirectory();
        if (projectRoot != null) {
            ModelCatalog catalog = ModelCatalogIO.read(projectRoot.toPath());
            Optional<ModelEntry> entry = catalog.get(key);
            if (entry.isPresent()) {
                String name = clean(entry.get().name);
                return name.isEmpty() ? key : name;
            }
        }
        return key;
    }

    private static String baseEngineLabel(SegmentationMethod base) {
        if (base == null) return "Classical";
        if (base.isEnhancedClassical()) return "Enhanced Classical";
        if (base.isStarDist()) return "StarDist 3D";
        if (base.isCellpose()) return "Cellpose";
        if (base.isTrainedRf()) return "Trained RF";
        return "Classical";
    }

    private static JPanel row(String label, String value) {
        JPanel row = new JPanel(new GridBagLayout());
        row.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridy = 0;
        gbc.gridx = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(0, 0, 0, 8);
        JLabel name = new JLabel(label + ":");
        name.setFont(FlashTheme.bodyMedium());
        name.setForeground(FlashTheme.TEXT_SUBHEADER);
        row.add(name, gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        JLabel content = new JLabel(clean(value));
        content.setForeground(FlashTheme.TEXT_PRIMARY);
        row.add(content, gbc);
        return row;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class Summary {
        final String modelKey;
        final String modelName;
        final String baseEngine;
        final String baseToken;

        Summary(String modelKey, String modelName, String baseEngine, String baseToken) {
            this.modelKey = clean(modelKey);
            this.modelName = clean(modelName);
            this.baseEngine = clean(baseEngine);
            this.baseToken = clean(baseToken);
        }
    }
}
