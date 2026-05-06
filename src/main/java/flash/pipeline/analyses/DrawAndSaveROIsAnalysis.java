package flash.pipeline.analyses;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.BinConfigIO;
import flash.pipeline.bin.BinField;
import flash.pipeline.bin.BinSetupDispatcher;
import flash.pipeline.cli.CLIConfig;
import flash.pipeline.image.ImageOps;
import flash.pipeline.image.OrientationOps;
import flash.pipeline.naming.ImageOrientationResolver;
import flash.pipeline.naming.NameParts;
import flash.pipeline.naming.ResolvedImageMetadata;
import flash.pipeline.results.CsvAppend;
import flash.pipeline.runtime.DependencyId;
import flash.pipeline.runtime.FeatureDependencyGate;
import flash.pipeline.roi.RoiIO;
import flash.pipeline.roi.RoiNaming;
import flash.pipeline.roi.RoiSetValidator;
import flash.pipeline.io.DeferredImageSupplier;
import flash.pipeline.io.ImageSourceDispatcher;
import flash.pipeline.io.IoUtils;
import flash.pipeline.zslice.ZSliceOps;
import flash.pipeline.zslice.ZSliceMode;

import flash.pipeline.ui.PipelineDialog;
import flash.pipeline.ui.ToggleSwitch;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.Toolbar;
import ij.gui.WaitForUserDialog;
import ij.plugin.ZProjector;
import ij.plugin.frame.RoiManager;
import ij.measure.ResultsTable;
import ij.gui.Roi;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JTextField;
import javax.swing.UIManager;

/**
 * Migration of drawROIs().
 *
 * - Detects existing ROI zip sets in the current FLASH layout and legacy folders
 * - User chooses to create a new ROI set or append to existing
 * - Opens each series from the .lif file
 * - For each image: user draws ROIs and clicks OK to continue
 * - Saves the ROI set to FLASH/01 - Regions of Interest/ROI Sets/<name> ROIs.zip
 * - Copies the ROI zip into FLASH/01 - Regions of Interest/Attributes/
 */
public class DrawAndSaveROIsAnalysis implements Analysis {

    static final String FULL_IMAGE_SOURCE = "Full image";
    static final String CONFIGURED_SUBSET_SOURCE = "Configured analysis subset";
    private boolean suppressDialogs = false;
    private boolean headless = false;
    private CLIConfig cliConfig = null;

    @Override
    public Set<BinField> requiredBinFields() {
        return EnumSet.of(BinField.CHANNEL_NAMES, BinField.Z_SLICE);
    }

    @Override
    public boolean requiresHeadedMode() {
        return true;
    }

    @Override
    public void setSuppressDialogs(boolean suppress) {
        this.suppressDialogs = suppress;
    }

    @Override
    public void setHeadless(boolean headless) {
        this.headless = headless;
    }

    @Override
    public void setCliConfig(CLIConfig config) {
        this.cliConfig = config;
    }

    @Override
    public void execute(String directory) {
        if (GraphicsEnvironment.isHeadless()) {
            IJ.log("[" + getClass().getSimpleName() + "] is interactive only "
                    + "and cannot run headless. Skipping.");
            return;
        }
        if (headless) {
            IJ.log("[" + getClass().getSimpleName()
                    + "] needs visible windows; overriding Hide Image Windows for this analysis.");
            headless = false;
        }
        BinSetupDispatcher.Outcome outcome = BinSetupDispatcher.ensure(
                directory, "Draw and Save ROIs", requiredBinFields(),
                benefitsFromRois(), suppressDialogs, cliConfig);
        if (outcome == BinSetupDispatcher.Outcome.CANCELLED) {
            IJ.log("[FLASH] Draw and Save ROIs cancelled by user.");
            return;
        }

        if (!FeatureDependencyGate.gate(DependencyId.BIO_FORMATS_RUNTIME, "Draw and Save ROIs")) {
            return;
        }

        File projectRoot = new File(directory);
        File roiDir = RoiIO.roiSetWriteDir(projectRoot);
        try {
            IoUtils.mustMkdirs(roiDir);
        } catch (IOException e) {
            IJ.log("[FLASH] Could not create ROIs directory: " + e.getMessage());
            return;
        }

        List<File> roiFiles = RoiIO.listRoiZipFiles(projectRoot);
        List<String> roiNames = new ArrayList<>();
        for (File f : roiFiles) {
            String base = f.getName();
            base = base.replace(" ROIs.zip", "").replace("ROIs.zip", "").replace(".zip", "").trim();
            roiNames.add(base);
        }

        // ── Main dialog ────────────────────────────────────────────────
        BinConfig roiBinCfg = BinConfigIO.readPartialFromDirectory(directory);
        String[] roiChannelChoices = buildRoiChannelChoices(roiBinCfg);
        String defaultRoiChannelChoice = defaultRoiChannelChoice(roiBinCfg, roiChannelChoices);
        boolean showZSliceSourceChoice = shouldShowZSliceSourceChoice(roiBinCfg);

        boolean hasExisting = !roiNames.isEmpty();

        PipelineDialog pd = new PipelineDialog("Draw & Save ROIs", PipelineDialog.Phase.SETUP);
        pd.addHeader("ROI Set Selection");

        ToggleSwitch createNewToggle = null;
        JComboBox<String> appendChoice = null;
        if (hasExisting) {
            pd.addMessage("Existing ROI sets found.");
            createNewToggle = pd.addToggle("Create New ROI Set", false);
            appendChoice = pd.addChoice("Append to existing", roiNames.toArray(new String[0]), roiNames.get(0));
            pd.addHelpText("Toggle ON to create a new set. When OFF, the selected existing set is used.");
        } else {
            pd.addMessage("No existing ROI files found. A new set will be created.");
        }

        pd.addHeader("Line Sets");
        ToggleSwitch drawLineToggle = pd.addToggle("Draw Line Set", false);
        pd.addHelpText("Also draw a named reference line on each image (e.g., ventricle boundary). "
                + "Lines are saved to FLASH/07 - Line Distance/Line Sets/ for distance analysis.");
        JTextField lineSetNameField = pd.addStringField("Line Set Name", "Ventricle", 15);

        pd.addHeader("Settings");
        pd.addChoice("ROI Channel", roiChannelChoices, defaultRoiChannelChoice);
        pd.addChoice("Image Adjustment", new String[]{"None", "Automatic", "Manual"}, "None");
        if (showZSliceSourceChoice) {
            pd.addChoice("Draw ROIs on",
                    new String[]{FULL_IMAGE_SOURCE, CONFIGURED_SUBSET_SOURCE},
                    FULL_IMAGE_SOURCE);
            pd.addHelpText("Use the full stack for ROI drawing, or match the z-slice subset configured in the Configuration folder.");
        }
        JTextField newNameField = pd.addStringField("New ROI Set Name", "SCN", 15);

        if (hasExisting && createNewToggle != null && appendChoice != null) {
            ToggleSwitch finalCreateNewToggle = createNewToggle;
            JComboBox<String> finalAppendChoice = appendChoice;
            Runnable syncRoiSetMode = () -> {
                boolean createNewSelected = finalCreateNewToggle.isSelected();
                setFieldEnabled(finalAppendChoice, !createNewSelected);
                setFieldEnabled(newNameField, createNewSelected);
            };
            createNewToggle.addChangeListener(syncRoiSetMode);
            syncRoiSetMode.run();
        }

        Runnable syncLineSetMode = () -> setFieldEnabled(lineSetNameField, drawLineToggle.isSelected());
        drawLineToggle.addChangeListener(syncLineSetMode);
        syncLineSetMode.run();

        if (!pd.showDialog()) return;

        boolean createNew;
        String existingSelection = null;
        if (hasExisting) {
            createNew = pd.getNextBoolean();
            existingSelection = pd.getNextChoice();
        } else {
            createNew = true;
        }
        boolean drawLineSet = pd.getNextBoolean();
        String lineSetName = pd.getNextString().trim();
        int roiChannel = parseRoiChannelChoice(pd.getNextChoice());
        String imageProcessing = pd.getNextChoice();
        boolean drawOnSubset = showZSliceSourceChoice
                && CONFIGURED_SUBSET_SOURCE.equals(pd.getNextChoice());
        String newName = pd.getNextString().trim();
        String chosen = createNew ? newName : existingSelection;
        if (chosen == null || chosen.trim().isEmpty()) {
            IJ.error("ROI Analysis", "ROI set name is blank.");
            return;
        }

        File roiZip = new File(roiDir, chosen + " ROIs.zip");
        File sourceRoiZip = roiZip;
        if (!createNew) {
            int selectedIndex = roiNames.indexOf(chosen);
            if (selectedIndex >= 0 && selectedIndex < roiFiles.size()) {
                sourceRoiZip = roiFiles.get(selectedIndex);
            }
        }

        RoiManager rm = RoiManager.getInstance();
        if (rm == null) rm = new RoiManager();
        rm.reset();
        if (!createNew && sourceRoiZip.exists()) {
            rm.runCommand("Open", sourceRoiZip.getAbsolutePath());
        }

        DeferredImageSupplier supplier;
        int totalImages;
        try {
            supplier = ImageSourceDispatcher.createSupplier(directory);
            totalImages = supplier.getTotalSeries();
        } catch (Exception e) {
            showOrLog("Draw and Save ROIs", e.getMessage());
            return;
        }

        ResultsTable roiProps = new ResultsTable();

        // If appending, ROI indices continue after existing
        int startRoiIndex = 1;
        if (!createNew) {
            startRoiIndex = (rm.getCount() / 2) + 1;
        }
        final int startOffset = createNew ? 0 : (startRoiIndex - 1) * 2;

        final boolean finalDrawOnSubset = drawOnSubset;
        final BinConfig finalRoiBinCfg = roiBinCfg;
        final int lookahead = 3;
        ExecutorService prepPool = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "IHF-ROI-Prep");
            t.setDaemon(true);
            return t;
        });
        ConcurrentHashMap<Integer, Future<PreparedImage>> prepCache =
                new ConcurrentHashMap<Integer, Future<PreparedImage>>();
        for (int k = 0; k < Math.min(lookahead, totalImages); k++) {
            final int idx = k;
            prepCache.put(k, prepPool.submit(() ->
                    prepareImage(directory, supplier, idx, roiChannel, imageProcessing,
                            finalDrawOnSubset, finalRoiBinCfg)));
        }

        for (int i = 0; i < totalImages; i++) {
            IJ.log("Loading image " + (i + 1) + "/" + totalImages + "...");
            PreparedImage prep;
            try {
                Future<PreparedImage> future = prepCache.remove(i);
                if (future != null) {
                    prep = future.get();
                    IJ.log("  (prefetched)");
                } else {
                    prep = prepareImage(directory, supplier, i, roiChannel, imageProcessing,
                            finalDrawOnSubset, finalRoiBinCfg);
                }
            } catch (Exception e) {
                Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
                IJ.log("ERROR: Failed to open image " + (i + 1) + ": "
                        + (cause != null ? cause.getMessage() : e.getMessage()));
                continue;
            }
            if (prep == null) continue;

            // Queue next images for preparation
            for (int k = i + 1; k < Math.min(i + 1 + lookahead, totalImages); k++) {
                if (!prepCache.containsKey(k)) {
                    final int idx = k;
                    prepCache.put(k, prepPool.submit(() ->
                            prepareImage(directory, supplier, idx, roiChannel, imageProcessing,
                                    finalDrawOnSubset, finalRoiBinCfg)));
                }
            }

            ImagePlus imp = prep.original;
            ImagePlus max = prep.maxProjection;
            ImagePlus roiStack = prep.roiStack;
            NameParts parts = prep.parts;
            String imgTitle = imp.getTitle();
            IJ.log("  Image: " + imp.getNSlices() + " Z-slices, " + imp.getNChannels()
                    + " channels (using ch " + roiChannel + " for ROI)");

            if ("Manual".equals(imageProcessing)) {
                IJ.run(max, "Brightness/Contrast...", "");
            }
            max.show();

            // Auto-select freehand tool and open ROI Manager
            IJ.setTool(Toolbar.FREEROI);
            IJ.run("ROI Manager...");

            new WaitForUserDialog("Draw ROI",
                    "Image " + (i + 1) + "/" + totalImages + "\n" +
                            "Draw ROI for: " + imgTitle + "\n\n" +
                            "Use the freehand tool to draw, then click OK.").show();

            // Expect user has drawn a ROI on max
            Roi roi = max.getRoi();
            if (roi == null) {
                int width = max.getWidth();
                int height = max.getHeight();

                IJ.log("Warning: no ROI drawn for " + imgTitle + " in ROI set \"" + chosen + "\"");

                max.changes = false;
                max.close();
                roiStack.changes = false;
                roiStack.close();
                imp.changes = false;
                imp.close();

                int countAfter = rm.getCount();
                int expectedAfter = startOffset + (i + 1) * 2;
                int missing = expectedAfter - countAfter;
                if (missing <= 0) continue;

                if (headless || GraphicsEnvironment.isHeadless()) {
                    padPlaceholderRois(rm, i, startOffset, width, height);
                    IJ.log("[DrawROIs] image " + (i + 1) + " missing " + missing
                            + " ROI(s) — padded with placeholders (headless mode).");
                    continue;
                }

                Object[] options = {"Redo this image", "Skip and continue",
                                    "Save partial + abort"};
                int choice = JOptionPane.showOptionDialog(null,
                        "Image " + (i + 1) + " is missing " + missing + " ROI(s).\n\n"
                        + "Redo: re-open this image to draw the ROI.\n"
                        + "Skip: pad with placeholder ROI(s) and continue.\n"
                        + "Save partial + abort: write what you've drawn and stop.",
                        "Missing ROIs",
                        JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                        null, options, options[0]);

                if (choice == 0) {
                    i--;
                    continue;
                } else if (choice == 1) {
                    padPlaceholderRois(rm, i, startOffset, width, height);
                    IJ.log("[DrawROIs] image " + (i + 1)
                            + " padded with " + missing + " placeholder ROI(s).");
                    continue;
                } else {
                    prepPool.shutdownNow();
                    for (Future<PreparedImage> f : prepCache.values()) {
                        f.cancel(true);
                    }
                    prepCache.clear();
                    savePartialAndExit(directory, rm, "user-aborted at image " + (i + 1));
                    return;
                }
            }

            // Add uncropped ROI
            rm.addRoi(roi);

            // Crop and add cropped ROI
            ImagePlus cropped = max.duplicate();
            cropped.setRoi(roi);
            IJ.run(cropped, "Crop", "");
            Roi croppedRoi = cropped.getRoi();
            rm.addRoi(croppedRoi == null ? roi : croppedRoi);

            // Rename ROIs (include hemisphere)
            int count = rm.getCount();
            String base = RoiNaming.baseName(parts.animal, parts.hemisphere, parts.region);
            rm.setSelectedIndexes(new int[]{count - 2});
            rm.runCommand("Rename", base);
            rm.setSelectedIndexes(new int[]{count - 1});
            rm.runCommand("Rename", RoiNaming.croppedName(parts.animal, parts.hemisphere, parts.region));

            // Store ROI properties (Area, Volume, Width, Height)
            java.awt.Rectangle b = roi.getBounds();
            roiProps.incrementCounter();
            int row = roiProps.size() - 1;
            roiProps.setValue("Animal Name", row, parts.animal);
            String regionLabel = (parts.hemisphere.isEmpty() && parts.region.isEmpty())
                    ? "" : parts.hemisphere + parts.region;
            roiProps.setValue("Region", row, regionLabel);
            roiProps.setValue(chosen, row, startRoiIndex + i);
            ij.measure.Calibration cal = imp.getCalibration();
            boolean hasCalibration = cal != null
                    && !"pixel".equalsIgnoreCase(cal.getUnit())
                    && !"pixels".equalsIgnoreCase(cal.getUnit())
                    && cal.pixelWidth != 0 && cal.pixelHeight != 0;
            double roiArea = roi.getStatistics().area;
            // getStatistics().area returns calibrated area if image is calibrated
            if (hasCalibration) {
                double pixelArea = roiArea / (cal.pixelWidth * cal.pixelHeight);
                roiProps.setValue("Area (pixel)", row, pixelArea);
                roiProps.setValue("Area (um^2)", row, roiArea);
                double zDepth = cal.pixelDepth * imp.getNSlices();
                double volumeUm3 = roiArea * zDepth;
                double volumeMm3 = volumeUm3 / 1e9;
                roiProps.setValue("Volume (micron^3)", row, volumeUm3);
                roiProps.setValue("Volume (mm^3)", row, volumeMm3);
            } else {
                roiProps.setValue("Area (pixel)", row, roiArea);
            }
            roiProps.setValue("Width", row, b.width);
            roiProps.setValue("Height", row, b.height);

            // Save cropped image preview
            File imgAnalysisDir = new File(directory + File.separator + "Image Analysis" + File.separator + parts.animal);
            boolean imgAnalysisDirReady = true;
            try {
                IoUtils.mustMkdirs(imgAnalysisDir);
            } catch (IOException e) {
                imgAnalysisDirReady = false;
                IJ.log("[FLASH] Could not create per-animal Image Analysis directory: "
                        + e.getMessage() + " — skipping cropped preview for " + parts.animal);
            }
            if (imgAnalysisDirReady) {
                String suffix = parts.fileSuffix();
                String croppedName = suffix.isEmpty()
                        ? "Cropped.PNG"
                        : "Cropped_" + suffix + ".PNG";
                IJ.saveAs(cropped, "PNG", new File(imgAnalysisDir, croppedName).getAbsolutePath());
            }

            // Cleanup
            cropped.changes = false;
            cropped.close();
            max.changes = false;
            max.close();
            roiStack.changes = false;
            roiStack.close();
            imp.changes = false;
            imp.close();
        }
        prepPool.shutdownNow();
        for (Future<PreparedImage> f : prepCache.values()) {
            f.cancel(true);
        }
        prepCache.clear();

        // ── Save ROI Properties CSV ─────────────────────────────────────
        File attrDir = RoiIO.attributesWriteDir(projectRoot);
        try {
            IoUtils.mustMkdirs(attrDir);
        } catch (IOException e) {
            IJ.log("[FLASH] Could not create Attributes directory: " + e.getMessage()
                    + " — ROI Properties CSV will not be saved.");
            return;
        }
        File roiPropsOut = new File(attrDir, chosen + " ROI Properties.csv");

        // Use high precision so small Volume (mm^3) values are not truncated to 0.000
        roiProps.setPrecision(9);
        if (!createNew && roiPropsOut.exists()) {
            File tmp = new File(attrDir, chosen + " ROI Properties.tmp.csv");
            roiProps.save(tmp.getAbsolutePath());
            try {
                CsvAppend.append(roiPropsOut, tmp);
            } catch (Exception e) {
                IJ.log("Warning appending ROI properties: " + e.getMessage());
            } finally {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } else {
            roiProps.save(roiPropsOut.getAbsolutePath());
        }

        // ── Save partial-state zip BEFORE validation ────────────────────
        // Guarantees the user can recover in-flight work even if the
        // strict validator rejects the set.
        String partialZipPath = savePartialZip(directory, rm);

        // ── Validate ROI ordering ───────────────────────────────────────
        if (!RoiSetValidator.validateStrictWithDialog(rm, startOffset, totalImages,
                partialZipPath)) {
            rm.close();
            return;
        }

        // ── Save ROI zip ────────────────────────────────────────────────
        rm.runCommand("Save", roiZip.getAbsolutePath());

        // Copy ROI zip into the ROI Attributes folder for downstream readers.
        File roiZipCopy = new File(attrDir, roiZip.getName());
        try {
            Files.copy(roiZip.toPath(), roiZipCopy.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            IJ.log("Warning: could not copy ROI zip to Attributes: " + e.getMessage());
        }

        rm.close();

        showOrLog("Draw and Save ROIs",
                "Saved ROIs to:\n" + roiZip.getAbsolutePath() +
                        "\n\nCopy saved to:\n" + roiZipCopy.getAbsolutePath());

        closeAllNoPrompt();

        // ── Draw Line Set (optional) ─────────────────────────────────
        if (drawLineSet && lineSetName != null && !lineSetName.isEmpty()) {
            File linesDir = LineDistanceAnalysis.lineSetWriteDir(directory);
            LineDistanceAnalysis lineAnalysis = new LineDistanceAnalysis();
            lineAnalysis.drawLineSet(directory, linesDir, lineSetName);
        }
    }

    private static void showOrLog(String title, String body) {
        if (GraphicsEnvironment.isHeadless()) {
            IJ.log("[" + title + "] " + body);
        } else {
            IJ.showMessage(title, body);
        }
    }

    private void closeAllNoPrompt() {
        int[] ids = WindowManager.getIDList();
        if (ids != null) {
            for (int id : ids) {
                ImagePlus imp = WindowManager.getImage(id);
                if (imp != null) imp.changes = false;
            }
        }
        IJ.run("Close All");
    }

    /**
     * Snapshot the current ROI Manager into the ROI Partial folder as a recoverable
     * zip. Returns the absolute path of the saved zip, or null if nothing
     * was saved (empty manager or I/O failure).
     */
    private static String savePartialZip(String directory, RoiManager rm) {
        if (rm == null || rm.getCount() == 0) return null;
        File partialDir = RoiIO.partialWriteDir(new File(directory));
        try {
            IoUtils.mustMkdirs(partialDir);
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT)
                    .format(new Date());
            File partialZip = new File(partialDir, "RoiSet_partial_" + stamp + ".zip");
            rm.runCommand("Save", partialZip.getAbsolutePath());
            String path = partialZip.getAbsolutePath();
            IJ.log("[DrawROIs] Partial ROI set saved to " + path
                    + " (recoverable if validation fails).");
            return path;
        } catch (Exception ioe) {
            IJ.log("[DrawROIs] WARN: could not save partial ROI zip: "
                    + ioe.getMessage());
            return null;
        }
    }

    /**
     * Save the current ROI Manager as a partial recovery zip and surface a
     * user-facing message describing where it landed. Used when the user
     * chooses "Save partial + abort" from the missing-ROI dialog.
     */
    private void savePartialAndExit(String directory, RoiManager rm, String reason) {
        String path = savePartialZip(directory, rm);
        if (path != null) {
            showOrLog("Draw and Save ROIs",
                    "Run aborted (" + reason + ").\n\n"
                    + "Partial ROI set saved to:\n" + path + "\n\n"
                    + "To recover: re-run Draw ROIs, choose 'Append to existing',\n"
                    + "and load that zip (or copy it into the ROI Sets folder first).");
        } else {
            showOrLog("Draw and Save ROIs",
                    "Run aborted (" + reason + "). No ROIs were saved.");
        }
        rm.close();
        closeAllNoPrompt();
    }

    /**
     * Pad the ROI Manager with placeholder full-image ROIs so that image
     * {@code imageIndex} ends up with the required two ROIs (uncropped +
     * cropped). Names follow the strict-validator convention so the final
     * RoiSetValidator pass succeeds.
     */
    private static void padPlaceholderRois(RoiManager rm, int imageIndex,
                                           int startOffset, int width, int height) {
        int countAfter = rm.getCount();
        int expectedAfter = startOffset + (imageIndex + 1) * 2;
        int missing = expectedAfter - countAfter;
        for (int p = 0; p < missing; p++) {
            int slot = countAfter + p;
            int relSlot = slot - startOffset;
            boolean isCroppedSlot = (relSlot % 2) == 1;
            Roi placeholder = new Roi(0, 0, width, height);
            placeholder.setName("PLACEHOLDER_image" + (imageIndex + 1)
                    + (isCroppedSlot ? "_Cropped" : ""));
            rm.addRoi(placeholder);
        }
    }

    private static void setFieldEnabled(Component field, boolean enabled) {
        if (field == null) return;
        field.setEnabled(enabled);

        Container row = field.getParent();
        if (row == null) return;

        for (Component child : row.getComponents()) {
            if (child instanceof JLabel) {
                JLabel label = (JLabel) child;
                label.setEnabled(enabled);

                Color original = (Color) label.getClientProperty("pipeline.originalForeground");
                if (original == null) {
                    original = label.getForeground();
                    label.putClientProperty("pipeline.originalForeground", original);
                }

                Color disabled = UIManager.getColor("Label.disabledForeground");
                label.setForeground(enabled ? original : (disabled != null ? disabled : Color.GRAY));
            }
        }
    }

    static String[] buildRoiChannelChoices(BinConfig cfg) {
        int count = cfg != null && cfg.numChannels() > 0 ? cfg.numChannels() : 4;
        String[] choices = new String[count];
        for (int i = 0; i < count; i++) {
            String label = String.valueOf(i + 1);
            if (cfg != null && i < cfg.channelNames.size()) {
                String name = cfg.channelNames.get(i);
                if (name != null && !name.trim().isEmpty()) {
                    label += " (" + name.trim() + ")";
                }
            }
            choices[i] = label;
        }
        return choices;
    }

    static String defaultRoiChannelChoice(BinConfig cfg, String[] choices) {
        if (choices == null || choices.length == 0) return "1";
        int index = preferredRoiChannelIndex(cfg);
        if (index < 1 || index > choices.length) index = 1;
        return choices[index - 1];
    }

    static int preferredRoiChannelIndex(BinConfig cfg) {
        if (cfg == null || cfg.channelNames == null || cfg.channelNames.isEmpty()) return 1;
        for (int i = 0; i < cfg.channelNames.size(); i++) {
            if (isNuclearBoundaryChannel(cfg.channelNames.get(i))) {
                return i + 1;
            }
        }
        return 1;
    }

    static int parseRoiChannelChoice(String choice) {
        if (choice == null) return 1;
        String trimmed = choice.trim();
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (!Character.isDigit(ch)) break;
            digits.append(ch);
        }
        if (digits.length() == 0) return 1;
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    static boolean shouldShowZSliceSourceChoice(BinConfig cfg) {
        return cfg != null && cfg.zSliceMode != null && cfg.zSliceMode != ZSliceMode.FULL;
    }

    private static boolean isNuclearBoundaryChannel(String name) {
        if (name == null) return false;
        String normalized = name.toLowerCase(java.util.Locale.ROOT)
                .replace("-", "")
                .replace("_", "")
                .replace(" ", "");
        return normalized.contains("dapi")
                || normalized.contains("hoechst")
                || normalized.contains("topro")
                || normalized.contains("nuclei")
                || normalized.contains("nucleus")
                || normalized.contains("nuclear")
                || normalized.equals("dna")
                || normalized.contains("dnastain");
    }

    /**
     * Loads and fully prepares a single series for ROI drawing:
     * z-slice subset, orientation, channel extraction, max projection,
     * and automatic contrast adjustment.
     */
    private PreparedImage prepareImage(String directory, DeferredImageSupplier supplier, int seriesIndex,
                                       int roiChannel, String imageProcessing,
                                       boolean drawOnSubset, BinConfig roiBinCfg) throws Exception {
        // Load only the specific channel requested for the ROI (0-indexed for Bio-Formats)
        ImagePlus imp = supplier.openSeriesMaterializedChannel(seriesIndex, roiChannel - 1);
        if (imp == null) return null;

        if (drawOnSubset && roiBinCfg != null) {
            ImagePlus subset = ZSliceOps.applyConfiguredRange(imp, roiBinCfg, seriesIndex, "ROI drawing");
            if (subset != null && subset != imp) {
                imp.changes = false;
                imp.close();
                imp.flush();
                imp = subset;
            }
        }

        ResolvedImageMetadata metadata = ImageOrientationResolver.resolve(
                directory, imp.getTitle(), seriesIndex + 1);
        NameParts parts = metadata.toNameParts();
        logOrientationResolution(metadata);
        OrientationOps.applyTransform(imp, metadata);

        // Since we only loaded one channel, we duplicate from channel 1
        ImagePlus roiStack = ImageOps.duplicateThreadSafe(imp, 1, 1,
                1, imp.getNSlices(), 1, imp.getNFrames());
        roiStack.setTitle("delete");
        ImagePlus max = ZProjector.run(roiStack, "max");
        max.setTitle("MAX_delete");

        if ("Automatic".equals(imageProcessing)) {
            IJ.run(max, "Enhance Contrast", "saturated=1");
        }

        return new PreparedImage(imp, max, roiStack, parts);
    }

    private static void logOrientationResolution(ResolvedImageMetadata metadata) {
        IJ.log("  Orientation source: " + metadata.sourceLabel());
        IJ.log(metadata.hasTransform()
                ? "  Orientation transform applied."
                : "  Orientation transform skipped.");
    }

    /** Bundled result from background image preparation. */
    private static class PreparedImage {
        final ImagePlus original;
        final ImagePlus maxProjection;
        final ImagePlus roiStack;
        final NameParts parts;

        PreparedImage(ImagePlus original, ImagePlus maxProjection,
                      ImagePlus roiStack, NameParts parts) {
            this.original = original;
            this.maxProjection = maxProjection;
            this.roiStack = roiStack;
            this.parts = parts;
        }
    }
}
