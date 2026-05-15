package flash.pipeline.ui.wizard;

import flash.pipeline.click.training.ObjectClassifierTrainer;
import flash.pipeline.click.training.cellpose.CellposeDatasetPackager;
import flash.pipeline.click.training.stardist.StarDistDatasetPackager;
import flash.pipeline.segmentation.catalog.ModelEntry;
import flash.pipeline.ui.FlashTheme;
import flash.pipeline.ui.PipelineDialog;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.Desktop;
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
        if (workflow == null) {
            return false;
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
                    return false;
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
                    return false;
                }
            }
        }
        return workflow.step() == TrainCustomEngineWorkflow.Step.DONE;
    }

    private static StepResult showBaseStep(Window owner, TrainCustomEngineWorkflow workflow) {
        PipelineDialog dialog = new PipelineDialog(owner, "Train Custom Engine - Pick Base");
        dialog.setPrimaryButtonText("Next");
        dialog.addHeader("Pick base");
        dialog.addMessage("Choose the segmentation engine this training run should build from.");
        JComboBox<String> base = dialog.addChoice("Base engine", new String[] {
                TrainCustomEngineWorkflow.Base.CLASSICAL.label,
                TrainCustomEngineWorkflow.Base.ENHANCED_CLASSICAL.label,
                TrainCustomEngineWorkflow.Base.STARDIST.label,
                TrainCustomEngineWorkflow.Base.CELLPOSE.label
        }, workflow.selectedBase().label);
        dialog.addHelpText("Classical and Enhanced Classical train an in-process Random Forest. StarDist and Cellpose package datasets for external training.");
        JButton help = dialog.addButton("Learn more about training models");
        help.setEnabled(false);
        help.setToolTipText("Training help will be linked in a later setup-help stage.");

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
        dialog.setPrimaryButtonText("Next");
        dialog.addHeader("Review clicks");
        dialog.addMessage("Click summary for this channel.");
        final JLabel gate = dialog.addMessage(workflow.clickGateMessage());

        JPanel imagePanel = new JPanel();
        imagePanel.setOpaque(false);
        imagePanel.setLayout(new BoxLayout(imagePanel, BoxLayout.Y_AXIS));
        final Map<String, JCheckBox> boxes = new LinkedHashMap<String, JCheckBox>();
        List<TrainCustomEngineWorkflow.ImageSummary> images = workflow.clickSummary().perImage;
        for (int i = 0; i < images.size(); i++) {
            final TrainCustomEngineWorkflow.ImageSummary summary = images.get(i);
            final JCheckBox box = new JCheckBox(summary.imageName + ": "
                    + summary.positive + " pos / " + summary.negative + " neg",
                    !summary.excluded);
            box.setOpaque(false);
            box.addActionListener(e -> {
                workflow.setImageExcluded(summary.imageName, !box.isSelected());
                gate.setText(workflow.clickGateMessage());
                dialog.setPrimaryButtonEnabled(workflow.canProceedFromClicks());
            });
            boxes.put(summary.imageName, box);
            imagePanel.add(box);
        }
        if (boxes.isEmpty()) {
            JLabel empty = new JLabel("No clicks have been captured for this channel.");
            empty.setForeground(FlashTheme.TEXT_HELP);
            imagePanel.add(empty);
        }
        dialog.addComponent(imagePanel);
        dialog.setPrimaryButtonEnabled(workflow.canProceedFromClicks());

        boolean ok = dialog.showDialog();
        for (Map.Entry<String, JCheckBox> entry : boxes.entrySet()) {
            workflow.setImageExcluded(entry.getKey(), !entry.getValue().isSelected());
        }
        if (!ok) {
            return dialog.wasBackPressed() ? StepResult.BACK : StepResult.CANCEL;
        }
        return workflow.canProceedFromClicks() ? StepResult.NEXT : StepResult.BACK;
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

        final SwingWorker<TrainCustomEngineWorkflow.TrainStepResult, ProgressUpdate> worker =
                new SwingWorker<TrainCustomEngineWorkflow.TrainStepResult, ProgressUpdate>() {
                    @Override protected TrainCustomEngineWorkflow.TrainStepResult doInBackground() throws Exception {
                        return workflow.runTrainingStep(new TrainCustomEngineWorkflow.ProgressListener() {
                            @Override public void update(double fraction, String message) {
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
                        try {
                            TrainCustomEngineWorkflow.TrainStepResult result = get();
                            ObjectClassifierTrainer.TrainingResult rf = result.rfResult;
                            if (rf != null) {
                                status.setText("Training complete. Cross-val accuracy: "
                                        + String.format(java.util.Locale.ROOT, "%.2f", Double.valueOf(rf.crossValAccuracy))
                                        + ". Quality flag: " + rf.quality + ".");
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
                        return workflow.runTrainingStep(new TrainCustomEngineWorkflow.ProgressListener() {
                            @Override public void update(double fraction, String message) {
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
                        try {
                            get();
                            progress.setValue(100);
                            status.setText(packageSummary(workflow));
                            openFolder.setEnabled(true);
                            helper.setEnabled(true);
                            choose.setEnabled(true);
                        } catch (Exception e) {
                            status.setText("Dataset packaging failed: " + cleanError(e));
                        }
                    }
                };
        dialog.getWindow().addWindowListener(new WindowAdapter() {
            @Override public void windowOpened(WindowEvent e) {
                worker.execute();
            }

            @Override public void windowClosed(WindowEvent e) {
                if (!worker.isDone()) {
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
            StarDistDatasetPackager.PackagingResult result = workflow.starDistPackage();
            if (result == null) return "StarDist dataset packaged.";
            return "Dataset packaged: " + result.imagesWritten + " images, "
                    + result.positiveLabelsRetained + " positive labels. Folder: "
                    + result.outputDir;
        }
        CellposeDatasetPackager.PackagingResult result = workflow.cellposePackage();
        if (result == null) return "Cellpose dataset packaged.";
        return "Dataset packaged: " + result.imagesWritten + " images, "
                + result.slicesWritten + " slices. Folder: " + result.outputDir;
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
        CANCEL
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
