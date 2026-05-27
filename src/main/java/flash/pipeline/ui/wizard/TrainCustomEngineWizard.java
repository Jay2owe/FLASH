package flash.pipeline.ui.wizard;

import flash.pipeline.click.training.ObjectClassifierTrainer;
import flash.pipeline.click.training.cellpose.CellposeDatasetPackager;
import flash.pipeline.click.training.stardist.StarDistDatasetPackager;
import flash.pipeline.help.AnalysisHelpCatalog;
import flash.pipeline.help.AnalysisHelpDialog;
import flash.pipeline.segmentation.catalog.ModelEntry;
import flash.pipeline.ui.FlashTheme;
import flash.pipeline.ui.HelpButton;
import flash.pipeline.ui.PipelineDialog;
import flash.pipeline.ui.ToggleSwitch;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TrainCustomEngineWizard {
    private static final int STEP_BASE = 0;
    private static final int STEP_CLICKS = 1;
    private static final int STEP_TRAIN = 2;
    private static final int STEP_SAVE = 3;
    private static final int STEP_APPLY = 4;

    private TrainCustomEngineWizard() {
    }

    public static boolean show(Window owner, TrainCustomEngineWorkflow workflow) {
        return showResult(owner, workflow) == Result.APPLIED;
    }

    public static Result showResult(Window owner, TrainCustomEngineWorkflow workflow) {
        if (workflow == null) {
            return Result.CANCELLED;
        }
        if (GraphicsEnvironment.isHeadless()) {
            workflow.cancel();
            return Result.CANCELLED;
        }
        int step = STEP_BASE;
        while (step >= STEP_BASE && step <= STEP_APPLY) {
            try {
                StepResult result;
                if (step == STEP_BASE) {
                    result = showBaseStep(owner, workflow);
                } else if (step == STEP_CLICKS) {
                    result = showClickStep(owner, workflow);
                } else if (step == STEP_TRAIN) {
                    result = showTrainStep(owner, workflow);
                } else if (step == STEP_SAVE) {
                    result = showSaveStep(owner, workflow);
                } else {
                    result = showApplyStep(owner, workflow);
                }

                if (result == StepResult.CANCEL) {
                    workflow.cancel();
                    return Result.CANCELLED;
                }
                if (result == StepResult.ROUTE_TO_CLICK_PREVIEW) {
                    workflow.routeToClickPreview();
                    return Result.ROUTE_TO_CLICK_PREVIEW;
                }
                if (result == StepResult.BACK) {
                    step = Math.max(STEP_BASE, step - 1);
                } else {
                    step++;
                }
            } catch (Exception e) {
                showError(owner, e.getMessage());
                if (step <= STEP_BASE) {
                    workflow.cancel();
                    return Result.CANCELLED;
                }
            }
        }
        return workflow.step() == TrainCustomEngineWorkflow.Step.DONE
                ? Result.APPLIED
                : Result.CANCELLED;
    }

    private static StepResult showBaseStep(Window owner, TrainCustomEngineWorkflow workflow) {
        PipelineDialog dialog = new PipelineDialog(owner, "Train Custom Engine - Pick Base");
        dialog.setPrimaryButtonText("Next");
        dialog.addHeader("Pick base");
        dialog.addMessage("Choose the segmentation engine this training run should build from.");
        JComboBox<String> base = dialog.addChoice("Base engine", new String[] {
                TrainCustomEngineWorkflow.Base.CLASSICAL.label,
                TrainCustomEngineWorkflow.Base.ENHANCED_CLASSICAL.label,
                TrainCustomEngineWorkflow.Base.STARDIST_RF.label,
                TrainCustomEngineWorkflow.Base.CELLPOSE_RF.label,
                TrainCustomEngineWorkflow.Base.STARDIST.label,
                TrainCustomEngineWorkflow.Base.CELLPOSE.label
        }, workflow.selectedBase().label);
        dialog.addHelpText("Options marked RF post-filter train a Smile Random Forest inside FLASH using objects from the chosen base engine. Custom model options package datasets for external StarDist or Cellpose training.");
        JPanel helpRow = new JPanel();
        helpRow.setOpaque(false);
        helpRow.setLayout(new BoxLayout(helpRow, BoxLayout.X_AXIS));
        helpRow.add(new JLabel("Training help"));
        helpRow.add(Box.createHorizontalStrut(6));
        JButton help = HelpButton.question("About training custom segmentation models.");
        help.addActionListener(e -> AnalysisHelpDialog.show(
                dialog.getWindow(), AnalysisHelpCatalog.TRAIN_CUSTOM_SEGMENTATION_MODELS));
        helpRow.add(help);
        dialog.addComponent(helpRow);

        boolean ok = dialog.showDialog();
        if (!ok) {
            return dialog.wasBackPressed() ? StepResult.BACK : StepResult.CANCEL;
        }
        workflow.selectBase(baseFromLabel(String.valueOf(base.getSelectedItem())));
        return StepResult.NEXT;
    }

    private static StepResult showClickStep(Window owner, final TrainCustomEngineWorkflow workflow) {
        final PipelineDialog dialog = new PipelineDialog(owner, "Train Custom Engine - Review Clicks");
        dialog.enableBackButton();
        dialog.addHeader("Review clicks");
        dialog.addMessage("Click summary for this channel.");
        final JLabel gate = dialog.addMessage(workflow.clickGateMessage());
        dialog.addMessage("If more examples are needed, this hidden development workflow must wait for the redesigned click-collection flow.");

        JPanel imagePanel = new JPanel();
        imagePanel.setOpaque(false);
        imagePanel.setLayout(new BoxLayout(imagePanel, BoxLayout.Y_AXIS));
        final Map<String, ToggleSwitch> boxes = new LinkedHashMap<String, ToggleSwitch>();
        List<TrainCustomEngineWorkflow.ImageSummary> images = workflow.clickSummary().perImage;
        for (int i = 0; i < images.size(); i++) {
            final TrainCustomEngineWorkflow.ImageSummary summary = images.get(i);
            final ToggleSwitch box = new ToggleSwitch(!summary.excluded);
            box.addChangeListener(new Runnable() {
                @Override public void run() {
                    workflow.setImageExcluded(summary.imageName, !box.isSelected());
                    gate.setText(workflow.clickGateMessage());
                    updateClickStepPrimary(dialog, workflow);
                }
            });
            boxes.put(summary.imageName, box);
            imagePanel.add(clickSummaryRow(box, summary));
        }
        if (boxes.isEmpty()) {
            JLabel empty = new JLabel("No clicks have been captured for this channel.");
            empty.setForeground(FlashTheme.TEXT_HELP);
            imagePanel.add(empty);
        }
        dialog.addComponent(imagePanel);
        updateClickStepPrimary(dialog, workflow);

        boolean ok = dialog.showDialog();
        for (Map.Entry<String, ToggleSwitch> entry : boxes.entrySet()) {
            workflow.setImageExcluded(entry.getKey(), !entry.getValue().isSelected());
        }
        if (!ok) {
            return dialog.wasBackPressed() ? StepResult.BACK : StepResult.CANCEL;
        }
        return workflow.canProceedFromClicks()
                ? StepResult.NEXT
                : StepResult.ROUTE_TO_CLICK_PREVIEW;
    }

    private static void updateClickStepPrimary(PipelineDialog dialog,
                                               TrainCustomEngineWorkflow workflow) {
        boolean ready = workflow != null && workflow.canProceedFromClicks();
        dialog.setPrimaryButtonText(ready ? "Next" : "Collect clicks");
        dialog.setPrimaryButtonEnabled(true);
    }

    private static JPanel clickSummaryRow(ToggleSwitch toggle,
                                          TrainCustomEngineWorkflow.ImageSummary summary) {
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.add(toggle);
        JLabel label = new JLabel("  " + summary.imageName + ": "
                + summary.positive + " pos / " + summary.negative + " neg");
        label.setForeground(FlashTheme.TEXT_PRIMARY);
        row.add(label);
        return row;
    }

    private static StepResult showTrainStep(Window owner, TrainCustomEngineWorkflow workflow) {
        if (workflow.selectedBase().trainsRf()) {
            return showRfTrainStep(owner, workflow);
        }
        return showExternalTrainStep(owner, workflow);
    }

    private static StepResult showRfTrainStep(Window owner, final TrainCustomEngineWorkflow workflow) {
        final PipelineDialog dialog = new PipelineDialog(owner, "Train Custom Engine - Train");
        dialog.enableBackButton();
        dialog.setPrimaryButtonText("Next");
        dialog.setPrimaryButtonEnabled(false);
        dialog.addHeader("Train");
        final JLabel status = dialog.addMessage("Preparing Random Forest training...");
        final JProgressBar progress = new JProgressBar(0, 100);
        progress.setStringPainted(true);
        progress.setValue(0);
        dialog.addComponent(progress);

        if (workflow.isTrainingComplete()) {
            status.setText(rfTrainingCompleteText(workflow));
            progress.setValue(100);
            dialog.setPrimaryButtonEnabled(true);
            boolean ok = dialog.showDialog();
            if (!ok) {
                return dialog.wasBackPressed() ? StepResult.BACK : StepResult.CANCEL;
            }
            return StepResult.NEXT;
        }

        final SwingWorker<TrainCustomEngineWorkflow.TrainStepResult, ProgressUpdate> worker =
                new SwingWorker<TrainCustomEngineWorkflow.TrainStepResult, ProgressUpdate>() {
                    @Override protected TrainCustomEngineWorkflow.TrainStepResult doInBackground() throws Exception {
                        if (isCancelled() || Thread.currentThread().isInterrupted()) {
                            throw new java.util.concurrent.CancellationException("Training cancelled.");
                        }
                        return workflow.runTrainingStep(new TrainCustomEngineWorkflow.ProgressListener() {
                            @Override public void update(double fraction, String message) {
                                if (isCancelled() || Thread.currentThread().isInterrupted()) {
                                    workflow.cancelActiveTraining();
                                    throw new java.util.concurrent.CancellationException("Training cancelled.");
                                }
                                publish(new ProgressUpdate(fraction, message));
                            }
                        });
                    }

                    @Override protected void process(List<ProgressUpdate> chunks) {
                        if (chunks == null || chunks.isEmpty()) return;
                        ProgressUpdate latest = chunks.get(chunks.size() - 1);
                        progress.setValue((int) Math.round(Math.max(0.0, Math.min(1.0, latest.fraction)) * 100.0));
                        status.setText(latest.message);
                    }

                    @Override protected void done() {
                        if (isCancelled()) {
                            workflow.cancelActiveTraining();
                            status.setText("Training cancelled.");
                            dialog.setTransientStatus("Training cancelled.");
                            return;
                        }
                        try {
                            TrainCustomEngineWorkflow.TrainStepResult result = get();
                            ObjectClassifierTrainer.TrainingResult rf = result.rfResult;
                            if (rf != null) {
                                status.setText(rfTrainingCompleteText(workflow));
                            } else {
                                status.setText("Training complete.");
                            }
                            if (workflow.warningMessage() != null && !workflow.warningMessage().isEmpty()) {
                                dialog.setTransientStatus(workflow.warningMessage());
                            }
                            progress.setValue(100);
                            dialog.setPrimaryButtonEnabled(true);
                        } catch (Exception e) {
                            status.setText("Training failed: " + cleanError(e));
                            dialog.setTransientStatus("Training failed.");
                        }
                    }
                };
        dialog.getWindow().addWindowListener(new WindowAdapter() {
            @Override public void windowOpened(WindowEvent e) {
                worker.execute();
            }

            @Override public void windowClosed(WindowEvent e) {
                if (!worker.isDone()) {
                    workflow.cancelActiveTraining();
                    worker.cancel(true);
                }
            }
        });

        boolean ok = dialog.showDialog();
        if (!ok) {
            return dialog.wasBackPressed() ? StepResult.BACK : StepResult.CANCEL;
        }
        return StepResult.NEXT;
    }

    private static StepResult showExternalTrainStep(Window owner, final TrainCustomEngineWorkflow workflow) {
        final PipelineDialog dialog = new PipelineDialog(owner, "Train Custom Engine - Train");
        dialog.enableBackButton();
        dialog.setPrimaryButtonText("Next");
        dialog.setPrimaryButtonEnabled(false);
        dialog.addHeader("Package dataset");
        final JLabel status = dialog.addMessage("Preparing training dataset...");
        final JProgressBar progress = new JProgressBar(0, 100);
        progress.setStringPainted(true);
        dialog.addComponent(progress);

        JPanel buttons = new JPanel();
        buttons.setOpaque(false);
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        final JButton openFolder = new JButton("Open folder");
        final JButton helper = new JButton(workflow.selectedBase() == TrainCustomEngineWorkflow.Base.STARDIST
                ? "Open ZeroCostDL4Mic"
                : "Copy training command");
        final JButton choose = new JButton(workflow.selectedBase() == TrainCustomEngineWorkflow.Base.STARDIST
                ? "Choose .zip..."
                : "Choose model file...");
        openFolder.setEnabled(false);
        helper.setEnabled(false);
        choose.setEnabled(false);
        buttons.add(openFolder);
        buttons.add(Box.createHorizontalStrut(6));
        buttons.add(helper);
        buttons.add(Box.createHorizontalStrut(6));
        buttons.add(choose);
        dialog.addComponent(buttons);

        if (workflow.isTrainingComplete()) {
            progress.setValue(100);
            status.setText(packageSummary(workflow));
            openFolder.setEnabled(true);
            helper.setEnabled(true);
            choose.setEnabled(true);
            dialog.setPrimaryButtonEnabled(workflow.externalModelFile() != null);
        }

        openFolder.addActionListener(e -> openDatasetFolder(workflow));
        helper.addActionListener(e -> {
            if (workflow.selectedBase() == TrainCustomEngineWorkflow.Base.STARDIST) {
                browse(StarDistDatasetPackager.RECOMMENDED_NOTEBOOK);
            } else {
                copyCellposeCommand(workflow);
            }
        });
        choose.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            if (chooser.showOpenDialog(dialog.getWindow()) == JFileChooser.APPROVE_OPTION) {
                try {
                    workflow.acceptExternalModelFile(chooser.getSelectedFile().toPath());
                    status.setText("Selected model file: " + chooser.getSelectedFile().getName());
                    dialog.setPrimaryButtonEnabled(true);
                } catch (IOException ex) {
                    status.setText(ex.getMessage());
                }
            }
        });

        final SwingWorker<TrainCustomEngineWorkflow.TrainStepResult, ProgressUpdate> worker =
                new SwingWorker<TrainCustomEngineWorkflow.TrainStepResult, ProgressUpdate>() {
                    @Override protected TrainCustomEngineWorkflow.TrainStepResult doInBackground() throws Exception {
                        if (isCancelled() || Thread.currentThread().isInterrupted()) {
                            throw new java.util.concurrent.CancellationException("Training cancelled.");
                        }
                        return workflow.runTrainingStep(new TrainCustomEngineWorkflow.ProgressListener() {
                            @Override public void update(double fraction, String message) {
                                if (isCancelled() || Thread.currentThread().isInterrupted()) {
                                    workflow.cancelActiveTraining();
                                    throw new java.util.concurrent.CancellationException("Training cancelled.");
                                }
                                publish(new ProgressUpdate(fraction, message));
                            }
                        });
                    }

                    @Override protected void process(List<ProgressUpdate> chunks) {
                        if (chunks == null || chunks.isEmpty()) return;
                        ProgressUpdate latest = chunks.get(chunks.size() - 1);
                        progress.setValue((int) Math.round(Math.max(0.0, Math.min(1.0, latest.fraction)) * 100.0));
                        status.setText(latest.message);
                    }

                    @Override protected void done() {
                        if (isCancelled()) {
                            workflow.cancelActiveTraining();
                            status.setText("Dataset packaging cancelled.");
                            dialog.setTransientStatus("Dataset packaging cancelled.");
                            return;
                        }
                        try {
                            get();
                            progress.setValue(100);
                            status.setText(packageSummary(workflow));
                            openFolder.setEnabled(true);
                            helper.setEnabled(true);
                            choose.setEnabled(true);
                            dialog.setPrimaryButtonEnabled(workflow.externalModelFile() != null);
                            if (workflow.warningMessage() != null
                                    && !workflow.warningMessage().isEmpty()) {
                                dialog.setTransientStatus(workflow.warningMessage());
                            }
                        } catch (Exception e) {
                            status.setText("Dataset packaging failed: " + cleanError(e));
                        }
                    }
                };
        dialog.getWindow().addWindowListener(new WindowAdapter() {
            @Override public void windowOpened(WindowEvent e) {
                if (workflow.isTrainingComplete()) {
                    return;
                }
                worker.execute();
            }

            @Override public void windowClosed(WindowEvent e) {
                if (!worker.isDone()) {
                    workflow.cancelActiveTraining();
                    worker.cancel(true);
                }
            }
        });

        boolean ok = dialog.showDialog();
        if (!ok) {
            return dialog.wasBackPressed() ? StepResult.BACK : StepResult.CANCEL;
        }
        return StepResult.NEXT;
    }

    private static StepResult showSaveStep(Window owner, TrainCustomEngineWorkflow workflow) throws Exception {
        PipelineDialog dialog = new PipelineDialog(owner, "Train Custom Engine - Save");
        dialog.enableBackButton();
        dialog.setPrimaryButtonText("Save");
        dialog.addHeader("Save model");
        dialog.addMessage("Save the trained model to the project catalog.");
        javax.swing.JTextField name = dialog.addStringField("Name", workflow.defaultModelName(), 28);
        javax.swing.JTextField description =
                dialog.addStringField("Description", workflow.defaultDescription(), 36);
        boolean ok = dialog.showDialog();
        if (!ok) {
            return dialog.wasBackPressed() ? StepResult.BACK : StepResult.CANCEL;
        }
        workflow.saveModel(name.getText(), description.getText());
        return StepResult.NEXT;
    }

    private static StepResult showApplyStep(Window owner, TrainCustomEngineWorkflow workflow) {
        PipelineDialog dialog = new PipelineDialog(owner, "Train Custom Engine - Apply");
        dialog.setPrimaryButtonText("Done");
        dialog.addHeader("Apply");
        ModelEntry saved = workflow.savedEntry();
        dialog.addMessage("Saved '" + (saved == null ? "model" : saved.name) + "' to the project catalog.");
        JComboBox<String> choice = dialog.addChoice("Set channel method", new String[] {
                workflow.recommendedMethodToken(),
                "Keep current method (" + workflow.previousMethodToken() + ")"
        }, workflow.recommendedMethodToken());
        boolean ok = dialog.showDialog();
        if (!ok) {
            return dialog.wasBackPressed() ? StepResult.BACK : StepResult.CANCEL;
        }
        if (workflow.recommendedMethodToken().equals(choice.getSelectedItem())) {
            workflow.applyRecommended();
        } else {
            workflow.keepCurrentMethod();
        }
        return StepResult.NEXT;
    }

    private static TrainCustomEngineWorkflow.Base baseFromLabel(String label) {
        for (TrainCustomEngineWorkflow.Base base : TrainCustomEngineWorkflow.Base.values()) {
            if (base.label.equals(label)) {
                return base;
            }
        }
        return TrainCustomEngineWorkflow.Base.CLASSICAL;
    }

    private static String packageSummary(TrainCustomEngineWorkflow workflow) {
        if (workflow.selectedBase() == TrainCustomEngineWorkflow.Base.STARDIST) {
            if (workflow.starDistTraining() != null
                    && workflow.starDistTraining().outputZip != null) {
                return "Local StarDist training complete. Model zip: "
                        + workflow.starDistTraining().outputZip;
            }
            StarDistDatasetPackager.PackagingResult result = workflow.starDistPackage();
            if (result == null) return "StarDist dataset packaged.";
            return "Dataset packaged: " + result.imagesWritten + " images, "
                    + result.positiveLabelsRetained + " positive labels. Folder: "
                    + result.outputDir;
        }
        CellposeDatasetPackager.PackagingResult result = workflow.cellposePackage();
        if (workflow.cellposeTraining() != null
                && workflow.cellposeTraining().modelFile != null) {
            return "Local Cellpose training complete. Model file: "
                    + workflow.cellposeTraining().modelFile;
        }
        if (result == null) return "Cellpose dataset packaged.";
        return "Dataset packaged: " + result.imagesWritten + " images, "
                + result.slicesWritten + " slices. Folder: " + result.outputDir;
    }

    private static String rfTrainingCompleteText(TrainCustomEngineWorkflow workflow) {
        ObjectClassifierTrainer.TrainingResult rf = workflow.rfResult();
        if (rf == null) {
            return "Training complete.";
        }
        return "Training complete. Cross-val accuracy: "
                + String.format(java.util.Locale.ROOT, "%.2f",
                        Double.valueOf(rf.crossValAccuracy))
                + ". Quality flag: " + rf.quality + ".";
    }

    private static void openDatasetFolder(TrainCustomEngineWorkflow workflow) {
        Path dir = null;
        if (workflow.selectedBase() == TrainCustomEngineWorkflow.Base.STARDIST
                && workflow.starDistPackage() != null) {
            dir = workflow.starDistPackage().outputDir;
        } else if (workflow.cellposePackage() != null) {
            dir = workflow.cellposePackage().outputDir;
        }
        if (dir == null) return;
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(dir.toFile());
            }
        } catch (Exception ignored) {
        }
    }

    private static void browse(String url) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception ignored) {
        }
    }

    private static void copyCellposeCommand(TrainCustomEngineWorkflow workflow) {
        CellposeDatasetPackager.PackagingResult result = workflow.cellposePackage();
        if (result == null || result.trainCommandFile == null) return;
        try {
            String command = new String(Files.readAllBytes(result.trainCommandFile), StandardCharsets.UTF_8).trim();
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(command), null);
        } catch (Exception ignored) {
        }
    }

    private static String cleanError(Exception e) {
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private static void showError(Window owner, String message) {
        JOptionPane.showMessageDialog(owner,
                message == null || message.trim().isEmpty() ? "Training wizard failed." : message,
                "Train Custom Engine",
                JOptionPane.ERROR_MESSAGE);
    }

    private enum StepResult {
        NEXT,
        BACK,
        CANCEL,
        ROUTE_TO_CLICK_PREVIEW
    }

    public enum Result {
        APPLIED,
        CANCELLED,
        ROUTE_TO_CLICK_PREVIEW
    }

    private static final class ProgressUpdate {
        final double fraction;
        final String message;

        ProgressUpdate(double fraction, String message) {
            this.fraction = fraction;
            this.message = message == null ? "" : message;
        }
    }
}
