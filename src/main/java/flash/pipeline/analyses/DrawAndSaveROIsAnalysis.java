package flash.pipeline.analyses;

import flash.pipeline.FLASH_Pipeline;
import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.BinConfigIO;
import flash.pipeline.bin.BinField;
import flash.pipeline.bin.BinSetupDispatcher;
import flash.pipeline.cli.CLIConfig;
import flash.pipeline.image.ImageOps;
import flash.pipeline.image.OrientationOps;
import flash.pipeline.naming.ImageOrientationResolver;
import flash.pipeline.naming.NameParts;
import flash.pipeline.naming.OrientationManifestRow;
import flash.pipeline.naming.ResolvedImageMetadata;
import flash.pipeline.orientation.OrientationBatchController;
import flash.pipeline.orientation.OrientationImageIdentity;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.project.ProjectFile;
import flash.pipeline.project.ProjectFileIO;
import flash.pipeline.project.ProjectMetadataSeeder;
import flash.pipeline.project.ProjectRegionEditor;
import flash.pipeline.orientation.OrientationPresetStore;
import flash.pipeline.orientation.OrientationTransformState;
import flash.pipeline.orientation.RoiOrientationManifestService;
import flash.pipeline.results.CsvAppend;
import flash.pipeline.results.RunIdCsv;
import flash.pipeline.runrecord.AnalysisRunContext;
import flash.pipeline.runrecord.RunRecordAware;
import flash.pipeline.runtime.DependencyId;
import flash.pipeline.runtime.FeatureDependencyGate;
import flash.pipeline.roi.RegionDrawSpec;
import flash.pipeline.roi.RoiIO;
import flash.pipeline.roi.RegionImageSelectionDialog;
import flash.pipeline.roi.RegionSetupPanel;
import flash.pipeline.roi.RoiSetImageBinding;
import flash.pipeline.roi.RoiSetValidator;
import flash.pipeline.io.DeferredImageSupplier;
import flash.pipeline.io.ImageSourceDispatcher;
import flash.pipeline.io.IoUtils;
import flash.pipeline.zslice.ZSliceOps;
import flash.pipeline.zslice.ZSliceMode;

import flash.pipeline.ui.PipelineDialog;
import flash.pipeline.ui.FlashTheme;
import flash.pipeline.ui.NextStepLabels;
import flash.pipeline.ui.RoiOrientationPanel;
import flash.pipeline.ui.ToggleSwitch;
import flash.pipeline.ui.wizard.RegionTextFieldSupport;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.Toolbar;
import ij.plugin.ZProjector;
import ij.plugin.frame.RoiManager;
import ij.measure.ResultsTable;
import ij.gui.Roi;
import ij.process.LUT;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.io.File;
import java.io.IOException;
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

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JDialog;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * Migration of drawROIs().
 *
 * - Detects existing ROI zip sets in the current FLASH layout and legacy folders
 * - User chooses to create a new ROI set or append to existing
 * - Opens each series from the .lif file
 * - For each image: user draws ROIs and confirms the labelled primary action
 * - Saves the ROI set through the central ROI analysis-image layout.
 * - Saves ROI property tables through the central ROI table layout.
 */
public class DrawAndSaveROIsAnalysis implements Analysis, RunRecordAware {

    static final String FULL_IMAGE_SOURCE = "Full image";
    static final String CONFIGURED_SUBSET_SOURCE = "Configured analysis subset";
    private static final String[] BRIGHTNESS_CONTRAST_WINDOW_TITLES =
            new String[]{"B&C", "Brightness/Contrast"};
    private boolean suppressDialogs = false;
    private boolean headless = false;
    private boolean commandMode = false;
    private CLIConfig cliConfig = null;
    private AnalysisRunContext runRecordContext = null;

    private enum ImportPromptChoice {
        DRAW,
        IMPORT,
        CANCEL
    }

    enum ImportedRoiLayout {
        ONE_PER_IMAGE,
        FLASH_PAIRS
    }

    private enum ImportPreviewDecision {
        CONTINUE,
        SKIP_REMAINING,
        CANCEL
    }

    private static final class RoiImportOptions {
        final String roiSetName;
        final boolean previewBeforeSaving;
        final int roiChannel;
        final String imageProcessing;
        final boolean drawOnSubset;

        RoiImportOptions(String roiSetName, boolean previewBeforeSaving,
                         int roiChannel, String imageProcessing,
                         boolean drawOnSubset) {
            this.roiSetName = roiSetName;
            this.previewBeforeSaving = previewBeforeSaving;
            this.roiChannel = roiChannel;
            this.imageProcessing = imageProcessing == null ? "None" : imageProcessing;
            this.drawOnSubset = drawOnSubset;
        }
    }

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

    public void setCommandMode(boolean commandMode) {
        this.commandMode = commandMode;
    }

    @Override
    public void setCliConfig(CLIConfig config) {
        this.cliConfig = config;
    }

    @Override
    public void setRunRecordContext(AnalysisRunContext context) {
        this.runRecordContext = context;
    }

    @Override
    public void execute(String directory) {
        if (GraphicsEnvironment.isHeadless() || (commandMode && headless)) {
            String message = "Draw ROIs and Orientate Images is headed-only; command/headless "
                    + "invocation cannot drive interactive ROI drawing. Run this workflow "
                    + "in the FLASH GUI, then reuse the saved ROI artifacts in downstream analyses.";
            IJ.log("[" + getClass().getSimpleName() + "] " + message + " Skipping.");
            recordWarn(message);
            return;
        }
        if (headless) {
            IJ.log("[" + getClass().getSimpleName()
                    + "] needs visible windows; overriding Hide Image Windows for this analysis.");
            headless = false;
        }
        BinSetupDispatcher.Outcome outcome = BinSetupDispatcher.ensure(
                directory, "Draw ROIs and Orientate Images", requiredBinFields(),
                benefitsFromRois(), suppressDialogs, cliConfig);
        if (outcome == BinSetupDispatcher.Outcome.CANCELLED) {
            IJ.log("[FLASH] Draw ROIs and Orientate Images cancelled by user.");
            return;
        }

        if (!FeatureDependencyGate.gate(DependencyId.BIO_FORMATS_RUNTIME,
                "Draw ROIs and Orientate Images", "Bio-Formats image loading")) {
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

        BinConfig roiBinCfg = loadBinConfig(directory);
        if (!suppressDialogs) {
            ImportPromptChoice importChoice = promptForRoiImport();
            if (importChoice == ImportPromptChoice.CANCEL) {
                IJ.log("[FLASH] Draw ROIs and Orientate Images cancelled by user.");
                return;
            }
            if (importChoice == ImportPromptChoice.IMPORT) {
                File importZip = chooseImportedRoiZip(projectRoot);
                if (importZip == null) {
                    IJ.log("[FLASH] ROI import cancelled before selecting a zip.");
                    return;
                }
                RoiImportOptions importOptions =
                        promptForRoiImportOptions(importZip, roiBinCfg);
                if (importOptions == null) {
                    IJ.log("[FLASH] ROI import cancelled before saving.");
                    return;
                }
                importRoiSet(directory, projectRoot, importZip, importOptions, roiBinCfg);
                return;
            }
        }

        List<File> roiFiles = RoiIO.listRoiZipFiles(projectRoot);
        List<String> roiNames = new ArrayList<>();
        for (File f : roiFiles) {
            String base = f.getName();
            base = base.replace(" ROIs.zip", "").replace("ROIs.zip", "").replace(".zip", "").trim();
            roiNames.add(base);
        }

        DeferredImageSupplier supplier;
        int totalImages;
        try {
            supplier = ImageSourceDispatcher.createSupplier(directory);
            totalImages = supplier.getTotalSeries();
        } catch (Exception e) {
            showOrLog("Draw ROIs and Orientate Images", e.getMessage());
            return;
        }

        // ── Main dialog ────────────────────────────────────────────────
        String[] roiChannelChoices = buildRoiChannelChoices(roiBinCfg);
        String defaultRoiChannelChoice = defaultRoiChannelChoice(roiBinCfg, roiChannelChoices);
        boolean showZSliceSourceChoice = shouldShowZSliceSourceChoice(roiBinCfg);

        boolean hasExisting = !roiNames.isEmpty();

        PipelineDialog pd = new PipelineDialog("Draw ROIs & Orientate Images", PipelineDialog.Phase.SETUP);
        pd.setWorkflowTracker(new String[]{"Setup", "Choose Images", "Draw ROIs/Orientate", "Save"}, 0);
        pd.addAnalysisHelpHeader("Draw ROIs and Orientate Images", FLASH_Pipeline.IDX_DRAW_ROIS);
        pd.addSubHeader("ROI Set Selection");

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
                + "Lines are saved to FLASH/Results/Tables/Line Distance/Line Sets/ for distance analysis.");
        JTextField lineSetNameField = pd.addStringField("Line Set Name", "Ventricle", 15);

        pd.addHeader("Settings");
        JComboBox<String> roiChannelCombo =
                pd.addChoice("ROI Channel", roiChannelChoices, defaultRoiChannelChoice);
        pd.addChoice("Image Adjustment", new String[]{"None", "Automatic", "Manual"}, "None");
        if (showZSliceSourceChoice) {
            pd.addChoice("Draw ROIs on",
                    new String[]{FULL_IMAGE_SOURCE, CONFIGURED_SUBSET_SOURCE},
                    FULL_IMAGE_SOURCE);
            pd.addHelpText("Use the full stack for ROI drawing, or match the z-slice subset configured in the Configuration folder.");
        }

        // Regions to draw (new set): a list, each with its own drawing channel. Image
        // Adjustment above is session-wide (display-only, never affects saved geometry).
        // Append mode draws one existing region using the ROI Channel above.
        pd.addSubHeader("Regions to draw (new set)");
        final RegionSetupPanel regionPanel =
                new RegionSetupPanel(roiChannelChoices, defaultRoiChannelChoice, "SCN");
        pd.addComponent(regionPanel);
        pd.addHelpText("One row per region; each region picks its own channel. Use 'Add region' "
                + "to draw several regions in one pass. (Append mode uses the existing set + ROI Channel.)");
        final int totalImagesForLabel = totalImages;

        if (hasExisting && createNewToggle != null && appendChoice != null) {
            ToggleSwitch finalCreateNewToggle = createNewToggle;
            JComboBox<String> finalAppendChoice = appendChoice;
            Runnable syncRoiSetMode = () -> {
                boolean createNewSelected = finalCreateNewToggle.isSelected();
                setFieldEnabled(finalAppendChoice, !createNewSelected);
                setFieldEnabled(roiChannelCombo, !createNewSelected);
                regionPanel.setRegionEditingEnabled(createNewSelected);
                pd.setPrimaryButtonText(NextStepLabels.afterRoiSetup(
                        createNewSelected,
                        existingRoiCountForSelection(finalAppendChoice, roiNames, roiFiles),
                        totalImagesForLabel));
            };
            createNewToggle.addChangeListener(syncRoiSetMode);
            appendChoice.addActionListener(e -> syncRoiSetMode.run());
            syncRoiSetMode.run();
        } else {
            // No existing sets: always create-new, so the region table drives channels.
            setFieldEnabled(roiChannelCombo, false);
            pd.setPrimaryButtonText(NextStepLabels.afterRoiSetup(true, 0, totalImagesForLabel));
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
        int appendChannel = parseRoiChannelChoice(pd.getNextChoice());   // ROI Channel (append mode)
        String imageProcessing = pd.getNextChoice();                      // Image Adjustment (session-wide)
        boolean drawOnSubset = showZSliceSourceChoice
                && CONFIGURED_SUBSET_SOURCE.equals(pd.getNextChoice());

        // Build the region list: from the table for a new set (one row per region, each with
        // its own channel), or the single selected existing set for append.
        List<RegionDrawSpec> regionSpecs;
        if (createNew) {
            regionSpecs = RegionSetupPanel.toSpecs(regionPanel.rows(),
                    DrawAndSaveROIsAnalysis::parseRoiChannelChoice, imageProcessing);
            if (regionSpecs.isEmpty()) {
                IJ.error("ROI Analysis", "Enter at least one region name.");
                return;
            }
        } else {
            if (existingSelection == null || existingSelection.trim().isEmpty()) {
                IJ.error("ROI Analysis", "ROI set name is blank.");
                return;
            }
            regionSpecs = new ArrayList<RegionDrawSpec>();
            regionSpecs.add(new RegionDrawSpec(existingSelection.trim(), appendChannel, imageProcessing));
        }

        // Image picker (Dialog 2): one matrix with a checkbox column per region. Returns the
        // images each region's ROI set is drawn on (zero-based series indices) plus any edit to
        // the per-image Region label. Shown once for all regions; runs for new AND append.
        java.util.LinkedHashMap<String, java.util.LinkedHashSet<Integer>> perRegionSelection;
        {
            // Seed the picker's Region column from the project's authoritative region where set,
            // falling back to the filename-parsed region.
            java.util.Map<Integer, String> currentRegions =
                    loadProjectRegions(directory, supplier, totalImages);
            List<RegionImageSelectionDialog.Row> pickerRows =
                    RegionImageSelectionDialog.buildRows(supplier, totalImages, currentRegions);
            List<String> regionNames = new ArrayList<String>();
            for (RegionDrawSpec s : regionSpecs) regionNames.add(s.regionName);
            boolean autoSelectOnly = headless || GraphicsEnvironment.isHeadless();
            RegionImageSelectionDialog.Result picked =
                    RegionImageSelectionDialog.choose(pickerRows, regionNames, autoSelectOnly);
            if (picked == null) {
                IJ.log("[FLASH] Draw ROIs cancelled at image selection.");
                return;
            }
            perRegionSelection = picked.perRegion;

            // Persist any corrected region labels back to project.json. The baseline is exactly
            // what each row displayed (override-or-parsed), so only genuine edits are written.
            java.util.Map<Integer, String> shownByIndex =
                    new java.util.LinkedHashMap<Integer, String>();
            for (RegionImageSelectionDialog.Row row : pickerRows) {
                shownByIndex.put(Integer.valueOf(row.seriesIndex), row.parsedRegion);
            }
            persistRegionEdits(directory, supplier, picked.editedRegions, shownByIndex);
        }

        // Draw each region in turn (region-by-region): each opens its picked images, draws with
        // its own channel, and saves its own zip + ROI Properties CSV. A user cancel/abort in any
        // region stops the whole pass (partial work is saved by drawOneRegion).
        for (RegionDrawSpec spec : regionSpecs) {
            DrawRegionResult result = drawOneRegion(directory, projectRoot, roiDir, supplier,
                    totalImages, roiBinCfg, drawOnSubset, spec,
                    perRegionSelection.get(spec.regionName), createNew, roiFiles, roiNames,
                    drawLineSet);
            if (result == DrawRegionResult.ABORT) {
                return;
            }
        }

        // ── Draw Line Set (optional), once after all regions ─────────────
        // REGRESSION GUARD: selecting "Draw Line Set" must start the line-drawing handoff.
        // The fix: keep this explicit call after ROI saving so the toggle cannot silently skip line setup.
        if (drawLineSet && lineSetName != null && !lineSetName.isEmpty()) {
            File linesDir = LineDistanceAnalysis.lineSetWriteDir(directory);
            LineDistanceAnalysis lineAnalysis = new LineDistanceAnalysis();
            lineAnalysis.drawLineSet(directory, linesDir, lineSetName);
        }
    }

    private enum DrawRegionResult { CONTINUE, ABORT }

    /**
     * Draw one region's ROIs over its selected images and save its zip + ROI Properties CSV.
     * Reuses the per-image orientation/draw flow; each ROI pair is named by its durable
     * image-identity token (see {@link RoiSetImageBinding}). Returns
     * {@link DrawRegionResult#ABORT} when the user cancels/aborts (partial work is saved here),
     * otherwise {@link DrawRegionResult#CONTINUE}.
     */
    private DrawRegionResult drawOneRegion(String directory, File projectRoot, File roiDir,
            DeferredImageSupplier supplier, int totalImages, BinConfig roiBinCfg,
            boolean drawOnSubset, RegionDrawSpec spec, java.util.Set<Integer> selectedSeries,
            boolean createNew, List<File> roiFiles, List<String> roiNames, boolean drawLineSet) {
        String chosen = spec.regionName;
        int roiChannel = spec.drawChannel;
        String imageProcessing = spec.displayMode;
        if (selectedSeries == null || selectedSeries.isEmpty()) {
            IJ.log("[FLASH] No images selected for region \"" + chosen + "\"; skipping.");
            return DrawRegionResult.CONTINUE;
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

        // Images already covered by the existing region zip (append): skip them by token so we
        // neither redraw covered images nor duplicate/skip by position.
        java.util.Set<String> coveredTokens = new java.util.HashSet<String>();
        if (!createNew) {
            coveredTokens.addAll(RoiSetImageBinding.indexByToken(
                    java.util.Arrays.asList(rm.getRoisAsArray())).keySet());
        }

        ResultsTable roiProps = new ResultsTable();
        RoiOrientationManifestService orientationManifestService =
                new RoiOrientationManifestService(directory);
        OrientationPresetStore presetStore = new OrientationPresetStore(directory);
        OrientationBatchController orientationController =
                new OrientationBatchController(presetStore, totalImages);

        if (createNew && totalImages == 0) {
            rm.close();
            showOrLog("Draw ROIs and Orientate Images", "No images were found to draw ROIs.");
            return DrawRegionResult.CONTINUE;
        }

        final boolean finalDrawOnSubset = drawOnSubset;
        final BinConfig finalRoiBinCfg = roiBinCfg;
        final int lookahead = 3;
        ExecutorService prepPool = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "FLASH-ROI-Prep");
            t.setDaemon(true);
            return t;
        });
        ConcurrentHashMap<Integer, Future<PreparedImage>> prepCache =
                new ConcurrentHashMap<Integer, Future<PreparedImage>>();
        for (int k = 0; k < Math.min(lookahead, totalImages); k++) {
            if (!selectedSeries.contains(Integer.valueOf(k))) continue;
            final int idx = k;
            prepCache.put(k, prepPool.submit(() ->
                    prepareImage(directory, supplier, idx, roiChannel, imageProcessing,
                            finalDrawOnSubset, finalRoiBinCfg)));
        }

        for (int i = 0; i < totalImages; i++) {
            if (!selectedSeries.contains(Integer.valueOf(i))) {
                continue; // not selected for this region
            }
            if (!createNew && !coveredTokens.isEmpty()) {
                String covTok = null;
                try {
                    String ik = OrientationImageIdentity.fromProjectSeries(
                            directory, i, supplier.getSeriesName(i)).imageKey;
                    if (ik != null && !ik.trim().isEmpty()) covTok = RoiSetImageBinding.token(ik);
                } catch (Exception ignore) {
                    // identity unresolved; the draw-time identity check will handle it
                }
                if (covTok != null && coveredTokens.contains(covTok)) {
                    Future<PreparedImage> coveredFuture = prepCache.remove(i);
                    if (coveredFuture != null) {
                        try { closePreparedImages(coveredFuture.get()); } catch (Exception ignore) { }
                    }
                    IJ.log("[FLASH] Image " + (i + 1) + " already in region \"" + chosen
                            + "\"; skipping (append).");
                    continue;
                }
            }
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
                String message = "Failed to open image " + (i + 1) + ": "
                        + (cause != null ? cause.getMessage() : e.getMessage());
                IJ.log("ERROR: " + message);
                recordWarn(message);
                continue;
            }
            if (prep == null) continue;
            orientationController.bindCurrent(currentImageFor(prep), i);
            orientationController.applyActiveRuleOnOpen();

            // Queue next selected images for preparation
            for (int k = i + 1; k < Math.min(i + 1 + lookahead, totalImages); k++) {
                if (selectedSeries.contains(Integer.valueOf(k)) && !prepCache.containsKey(k)) {
                    final int idx = k;
                    prepCache.put(k, prepPool.submit(() ->
                            prepareImage(directory, supplier, idx, roiChannel, imageProcessing,
                                    finalDrawOnSubset, finalRoiBinCfg)));
                }
            }

            ImagePlus imp = prep.original;
            NameParts parts = prep.parts;
            String imgTitle = imp.getTitle();
            IJ.log("  Image: " + imp.getNSlices() + " Z-slices, " + imp.getNChannels()
                    + " channels (using ch " + roiChannel + " for ROI)");

            if ("Manual".equals(imageProcessing)) {
                IJ.run(prep.maxProjection, "Brightness/Contrast...", "");
            }
            Window imageJWindow = imageJMainWindow();
            prep.maxProjection.show();
            placeRoiImageWindowNearImageJ(prep.maxProjection, imageJWindow);
            RoiOrientationPanel drawDialog =
                    new RoiOrientationPanel(imageJWindow, createOrientationTarget(prep),
                            "Image " + (i + 1) + "/" + totalImages, imgTitle,
                            orientationController, i, totalImages,
                            NextStepLabels.roiFinalDestination(drawLineSet));

            try {
                // Auto-select freehand tool and open ROI Manager
                IJ.setTool(Toolbar.FREEROI);
                IJ.run("ROI Manager...");

                RoiOrientationPanel.DrawDialogResult drawResult =
                        drawDialog.showNearAndWait(prep.maxProjection);
                if (drawResult != RoiOrientationPanel.DrawDialogResult.CONFIRMED) {
                    prepPool.shutdownNow();
                    for (Future<PreparedImage> f : prepCache.values()) {
                        f.cancel(true);
                    }
                    prepCache.clear();
                    closePreparedImages(prep);
                    savePartialAndExit(directory, rm, "user-cancelled at image " + (i + 1));
                    return DrawRegionResult.ABORT;
                }
            } finally {
                drawDialog.close();
            }

            // Expect user has drawn a ROI on max
            ImagePlus max = prep.maxProjection;
            Roi roi = max.getRoi();
            if (roi == null) {
                String message = "No ROI drawn for " + imgTitle + " in region \"" + chosen + "\"";
                IJ.log("Warning: " + message);
                recordWarn(message);
                closePreparedImages(prep);

                if (headless || GraphicsEnvironment.isHeadless()) {
                    saveOrientationDecision(orientationManifestService, prep,
                            "Skipped during Draw ROIs (no ROI); image left uncovered for this region");
                    IJ.log("[DrawROIs] image " + (i + 1)
                            + " has no ROI — left uncovered for region \"" + chosen + "\" (headless).");
                    continue;
                }

                Object[] options = {"Redo this image", "Skip image",
                                    "Save partial + abort"};
                int choice = JOptionPane.showOptionDialog(imageJMainWindow(),
                        "Image " + (i + 1) + " has no ROI drawn.\n\n"
                        + "Redo: re-open this image to draw the ROI.\n"
                        + "Skip image: leave it out of region \"" + chosen + "\".\n"
                        + "Save partial + abort: write what you've drawn and stop.",
                        "No ROI drawn",
                        JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                        null, options, options[0]);

                if (choice == 0) {
                    i--;
                    continue;
                } else if (choice == 1) {
                    saveOrientationDecision(orientationManifestService, prep,
                            "Skipped during Draw ROIs; image left uncovered for this region");
                    continue;
                } else {
                    prepPool.shutdownNow();
                    for (Future<PreparedImage> f : prepCache.values()) {
                        f.cancel(true);
                    }
                    prepCache.clear();
                    savePartialAndExit(directory, rm, "user-aborted at image " + (i + 1));
                    return DrawRegionResult.ABORT;
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

            // Rename ROIs with the durable image-identity token so a region zip can cover any
            // subset of images and analyses bind by image identity, not by position in the zip.
            int count = rm.getCount();
            String imageKey;
            try {
                imageKey = OrientationImageIdentity.fromProjectSeries(
                        directory, i, supplier.getSeriesName(i)).imageKey;
            } catch (Exception identityEx) {
                imageKey = "";
                IJ.log("[FLASH] Could not resolve image identity for " + imgTitle + ": "
                        + identityEx.getMessage());
            }
            if (imageKey == null || imageKey.trim().isEmpty()) {
                // Cannot bind this image (e.g. multi-container project, unsupported by the
                // orientation identity layer); drop the just-added pair and skip so the zip
                // stays valid for the images that can be bound.
                rm.setSelectedIndexes(new int[]{count - 2, count - 1});
                rm.runCommand("Delete");
                IJ.log("[FLASH] Skipped " + imgTitle + " for region \"" + chosen
                        + "\": no durable image identity to bind the ROI.");
                cropped.changes = false;
                cropped.close();
                closePreparedImages(prep);
                continue;
            }
            rm.setSelectedIndexes(new int[]{count - 2});
            rm.runCommand("Rename", RoiSetImageBinding.drawnRoiName(imageKey));
            rm.setSelectedIndexes(new int[]{count - 1});
            rm.runCommand("Rename", RoiSetImageBinding.croppedRoiName(imageKey));

            // Store ROI properties (Area, Volume, Width, Height)
            java.awt.Rectangle b = roi.getBounds();
            roiProps.incrementCounter();
            int row = roiProps.size() - 1;
            roiProps.setValue("Animal Name", row, parts.animal);
            String regionLabel = (parts.hemisphere.isEmpty() && parts.region.isEmpty())
                    ? "" : parts.hemisphere + parts.region;
            roiProps.setValue("Region", row, regionLabel);
            roiProps.setValue(chosen, row, i + 1);
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
            File imgAnalysisDir = RoiIO.imageOutputsWriteDir(projectRoot);
            boolean imgAnalysisDirReady = true;
            try {
                IoUtils.mustMkdirs(imgAnalysisDir);
            } catch (IOException e) {
                imgAnalysisDirReady = false;
                IJ.log("[FLASH] Could not create ROI image output directory: "
                        + e.getMessage() + " — skipping cropped preview for " + parts.animal);
            }
            if (imgAnalysisDirReady) {
                String suffix = parts.fileSuffix();
                // Region-qualify the preview so a multi-region image (drawn for several regions
                // in one pass) does not overwrite another region's preview, matching the
                // per-region zip/CSV namespacing.
                String croppedName = suffix.isEmpty()
                        ? chosen + "_Cropped.PNG"
                        : chosen + "_Cropped_" + suffix + ".PNG";
                File croppedOutput = new File(imgAnalysisDir, croppedName);
                IJ.saveAs(cropped, "PNG", croppedOutput.getAbsolutePath());
                recordOutput(croppedOutput, "png");
            }

            saveOrientationDecision(orientationManifestService, prep,
                    "Saved during Draw ROIs and Orientate Images");

            // Cleanup
            cropped.changes = false;
            cropped.close();
            closePreparedImages(prep);
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
            IJ.log("[FLASH] Could not create ROI tables directory: " + e.getMessage()
                    + " — ROI Properties CSV will not be saved.");
            rm.close();
            return DrawRegionResult.CONTINUE;
        }
        File roiPropsOut = new File(attrDir, chosen + " ROI Properties.csv");

        // Use high precision so small Volume (mm^3) values are not truncated to 0.000
        roiProps.setPrecision(9);
        addRunIdColumn(roiProps, currentRunId());
        if (!createNew && roiPropsOut.exists()) {
            File tmp = new File(attrDir, chosen + " ROI Properties.tmp.csv");
            roiProps.save(tmp.getAbsolutePath());
            try {
                CsvAppend.append(roiPropsOut, tmp);
            } catch (Exception e) {
                String message = "Warning appending ROI properties: " + e.getMessage();
                IJ.log(message);
                recordWarn(message);
            } finally {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } else {
            roiProps.save(roiPropsOut.getAbsolutePath());
        }
        recordOutput(roiPropsOut, "csv");

        // ── Save partial-state zip BEFORE validation ────────────────────
        // Guarantees the user can recover in-flight work even if the
        // strict validator rejects the set.
        String partialZipPath = savePartialZip(directory, rm);
        if (partialZipPath != null) {
            recordOutput(new File(partialZipPath), "zip");
        }

        // ── Validate ROI ordering ───────────────────────────────────────
        if (!RoiSetValidator.validateStructuralWithDialog(rm, partialZipPath)) {
            rm.close();
            return DrawRegionResult.CONTINUE;
        }

        // ── Save ROI zip ────────────────────────────────────────────────
        rm.runCommand("Save", roiZip.getAbsolutePath());
        recordOutput(roiZip, "zip");

        rm.close();

        showOrLog("Draw ROIs and Orientate Images",
                "Saved ROIs for region \"" + chosen + "\" to:\n" + roiZip.getAbsolutePath() +
                        "\n\nROI properties saved to:\n" + roiPropsOut.getAbsolutePath());

        closeAllNoPrompt();
        return DrawRegionResult.CONTINUE;
    }

    /**
     * Read the authoritative per-series region from {@code project.json} (keyed by zero-based
     * series index) so the picker shows the project's region where one is set rather than the
     * filename-parsed value. Empty map when there is no project file or identity is unresolved.
     */
    private java.util.Map<Integer, String> loadProjectRegions(
            String directory, DeferredImageSupplier supplier, int totalImages) {
        java.util.Map<Integer, String> out = new java.util.LinkedHashMap<Integer, String>();
        File settingsDir = FlashProjectLayout.forDirectory(directory).configurationWriteDir();
        ProjectFile project = ProjectFileIO.read(settingsDir);
        if (project == null) return out;
        for (int i = 0; i < totalImages; i++) {
            try {
                OrientationImageIdentity id = OrientationImageIdentity.fromProjectSeries(
                        directory, i, supplier.getSeriesName(i));
                ProjectRegionEditor.Target target =
                        ProjectRegionEditor.locate(project, id.sourceFile, id.seriesIndex);
                if (target != null && target.region() != null && !target.region().trim().isEmpty()) {
                    out.put(Integer.valueOf(i), target.region().trim());
                }
            } catch (Exception ignore) {
                // identity unresolved (e.g. multi-container) -> fall back to parsed region
            }
        }
        return out;
    }

    /**
     * Write region labels the user changed in the picker back to {@code project.json}
     * (SeriesItem.region for expanded containers, Item.region otherwise) and re-seed the
     * orientation manifest so downstream analyses honour the correction. No-op when there is no
     * project file or nothing changed. {@code shownByIndex} is the baseline each row displayed,
     * so only genuine edits are written (never the parsed fallback as if it were an edit).
     */
    private void persistRegionEdits(String directory, DeferredImageSupplier supplier,
                                    java.util.Map<Integer, String> editedByIndex,
                                    java.util.Map<Integer, String> shownByIndex) {
        if (editedByIndex == null || editedByIndex.isEmpty()) return;
        File settingsDir = FlashProjectLayout.forDirectory(directory).configurationWriteDir();
        if (!ProjectFileIO.exists(settingsDir)) return;
        ProjectFile project = ProjectFileIO.read(settingsDir);
        if (project == null) return;

        // Only genuinely-edited rows are written (see ProjectRegionEditor.changedRegions); the
        // headless path echoes the displayed regions, so this is empty and nothing is written.
        java.util.Map<Integer, String> toApply =
                ProjectRegionEditor.changedRegions(editedByIndex, shownByIndex);
        boolean changed = false;
        for (java.util.Map.Entry<Integer, String> e : toApply.entrySet()) {
            int i = e.getKey().intValue();
            String edited = e.getValue();
            try {
                OrientationImageIdentity id = OrientationImageIdentity.fromProjectSeries(
                        directory, i, supplier.getSeriesName(i));
                ProjectRegionEditor.Target target =
                        ProjectRegionEditor.locate(project, id.sourceFile, id.seriesIndex);
                if (target == null) {
                    IJ.log("[FLASH] Region edit for image " + (i + 1)
                            + " could not be matched to the project file; not persisted.");
                    continue;
                }
                target.setRegion(edited);
                changed = true;
            } catch (Exception ex) {
                IJ.log("[FLASH] Region write-back skipped for image " + (i + 1)
                        + ": " + ex.getMessage());
            }
        }
        if (!changed) return;
        try {
            ProjectFileIO.write(settingsDir, project);
            ProjectMetadataSeeder.seedOrientationManifest(new File(directory), project);
            IJ.log("[FLASH] Saved corrected region label(s) to project.json.");
        } catch (Exception ex) {
            IJ.log("[FLASH] Could not save region edits to project.json: " + ex.getMessage());
        }
    }

    BinConfig loadBinConfig(String directory) {
        return BinConfigIO.readPartialFromDirectory(directory);
    }

    private ImportPromptChoice promptForRoiImport() {
        PipelineDialog dialog = new PipelineDialog(
                "Draw ROIs & Orientate Images", PipelineDialog.Phase.SETUP);
        dialog.setWorkflowTracker(new String[]{"Setup", "Choose Images", "Draw ROIs/Orientate", "Save"}, 0);
        dialog.addAnalysisHelpHeader("Draw ROIs and Orientate Images",
                FLASH_Pipeline.IDX_DRAW_ROIS);
        dialog.addMessage("Draw a new ROI set as usual, or import an existing ImageJ ROI zip "
                + "and convert it into FLASH's paired ROI format.");
        dialog.addHelpText("Imported zips must contain one ROI per image, or an existing "
                + "FLASH-compatible pair set with uncropped and _Cropped ROIs. ROI order "
                + "must match the image series order.");
        dialog.setPrimaryButtonText(NextStepLabels.ROI_SETUP_OPTIONS);
        JButton importButton = dialog.addRightFooterButton("Import...");
        importButton.addActionListener(e -> dialog.closeWithAction("import"));

        if (dialog.showDialog()) {
            return ImportPromptChoice.DRAW;
        }
        return "import".equals(dialog.getActionCommand())
                ? ImportPromptChoice.IMPORT
                : ImportPromptChoice.CANCEL;
    }

    private File chooseImportedRoiZip(File projectRoot) {
        JFileChooser chooser = new JFileChooser(
                projectRoot == null ? new File(".") : projectRoot);
        chooser.setDialogTitle("Import ROI zip");
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setFileFilter(new FileNameExtensionFilter("ROI zip files", "zip"));
        if (chooser.showOpenDialog(imageJMainWindow()) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        File selected = chooser.getSelectedFile();
        if (!isZipFile(selected)) {
            IJ.error("Import ROI Set", "Please choose a .zip file exported by ImageJ ROI Manager.");
            return null;
        }
        return selected;
    }

    private RoiImportOptions promptForRoiImportOptions(File importZip, BinConfig roiBinCfg) {
        String[] roiChannelChoices = buildRoiChannelChoices(roiBinCfg);
        String defaultRoiChannelChoice = defaultRoiChannelChoice(roiBinCfg, roiChannelChoices);
        boolean showZSliceSourceChoice = shouldShowZSliceSourceChoice(roiBinCfg);

        PipelineDialog dialog = new PipelineDialog("Import ROI Set", PipelineDialog.Phase.SETUP);
        dialog.setWorkflowTracker(new String[]{"Setup", "Import", "Preview", "Save"}, 1);
        dialog.addHeader("Import ROI Zip");
        dialog.addMessage("Selected file:<br>" + htmlEscape(importZip.getName()));
        JTextField roiSetNameField =
                dialog.addStringField("ROI Set Name", importSetNameFromZip(importZip), 18);
        RegionTextFieldSupport.Handle roiSetNameSupport =
                RegionTextFieldSupport.install(roiSetNameField, null);
        ToggleSwitch previewToggle = dialog.addToggle("Preview ROIs before saving", true);
        dialog.addChoice("ROI Channel", roiChannelChoices, defaultRoiChannelChoice);
        dialog.addChoice("Image Adjustment", new String[]{"None", "Automatic", "Manual"}, "None");
        if (showZSliceSourceChoice) {
            dialog.addChoice("Import ROIs on",
                    new String[]{FULL_IMAGE_SOURCE, CONFIGURED_SUBSET_SOURCE},
                    FULL_IMAGE_SOURCE);
        }
        dialog.addHelpText("The import checks ROI count and ROI bounds against the prepared image "
                + "series, then saves a FLASH-compatible ROI zip, cropped previews, and ROI "
                + "properties. ROI order must match the image series order.");
        Runnable syncPrimaryLabel = () -> dialog.setPrimaryButtonText(
                NextStepLabels.importRoiPrimaryLabel(previewToggle.isSelected()));
        Runnable syncImportTracker = () -> dialog.setWorkflowTracker(
                previewToggle.isSelected()
                        ? new String[]{"Setup", "Import", "Preview", "Save"}
                        : new String[]{"Setup", "Import", "Save"},
                1);
        previewToggle.addChangeListener(syncPrimaryLabel);
        previewToggle.addChangeListener(syncImportTracker);
        syncPrimaryLabel.run();
        syncImportTracker.run();

        try {
            if (!dialog.showDialog()) {
                return null;
            }

            // Advance the PipelineDialog text-field cursor, but use the atlas-aware
            // support so exact Allen Brain Atlas matches save as canonical acronyms.
            dialog.getNextString();
            String roiSetName = sanitizeRoiSetName(roiSetNameSupport.canonicalText());
            boolean preview = dialog.getNextBoolean();
            int roiChannel = parseRoiChannelChoice(dialog.getNextChoice());
            String imageProcessing = dialog.getNextChoice();
            boolean drawOnSubset = showZSliceSourceChoice
                    && CONFIGURED_SUBSET_SOURCE.equals(dialog.getNextChoice());
            return new RoiImportOptions(roiSetName, preview, roiChannel,
                    imageProcessing, drawOnSubset);
        } finally {
            roiSetNameSupport.dispose();
        }
    }

    private boolean importRoiSet(String directory,
                                 File projectRoot,
                                 File importZip,
                                 RoiImportOptions options,
                                 BinConfig roiBinCfg) {
        AnalysisRunContext.InputHandle zipInput = null;
        long started = System.currentTimeMillis();
        String inputStatus = "failed";
        RoiManager rm = null;
        try {
            validateImportZipFile(importZip);
            if (runRecordContext != null) {
                zipInput = runRecordContext.recordInputStart(importZip, 0, null);
            }

            List<Roi> importedRois = RoiIO.loadRoisFromZip(importZip);
            DeferredImageSupplier supplier = ImageSourceDispatcher.createSupplier(directory);
            int totalImages = supplier.getTotalSeries();
            ImportedRoiLayout layout = resolveImportedRoiLayout(importedRois.size(), totalImages);
            if (layout == ImportedRoiLayout.FLASH_PAIRS) {
                validateImportedFlashPairNames(importedRois, totalImages);
            }

            File attrDir = RoiIO.attributesWriteDir(projectRoot);
            File imageOutputDir = RoiIO.imageOutputsWriteDir(projectRoot);
            IoUtils.mustMkdirs(attrDir);
            IoUtils.mustMkdirs(imageOutputDir);

            File roiZip = new File(RoiIO.roiSetWriteDir(projectRoot),
                    options.roiSetName + " ROIs.zip");
            File roiPropsOut = new File(attrDir,
                    options.roiSetName + " ROI Properties.csv");
            if (!confirmOverwriteImportOutputs(roiZip, roiPropsOut)) {
                inputStatus = "cancelled";
                return false;
            }

            IJ.log("[DrawROIs] Importing " + importedRois.size() + " ROI(s) from "
                    + importZip.getName() + " for " + totalImages + " image(s).");
            rm = RoiManager.getInstance();
            if (rm == null) rm = new RoiManager();
            rm.reset();

            ResultsTable roiProps = new ResultsTable();
            RoiOrientationManifestService orientationManifestService =
                    new RoiOrientationManifestService(directory);
            boolean preview = options.previewBeforeSaving;

            for (int i = 0; i < totalImages; i++) {
                PreparedImage prep = null;
                try {
                    prep = prepareImage(directory, supplier, i, options.roiChannel,
                            options.imageProcessing, options.drawOnSubset, roiBinCfg);
                    if (prep == null || prep.maxProjection == null) {
                        throw new IllegalStateException(
                                "Could not prepare image " + (i + 1) + " for ROI import.");
                    }

                    Roi imported = duplicateRoi(sourceRoiForImport(importedRois, layout, i));
                    validateImportedRoiBounds(imported, prep.maxProjection.getWidth(),
                            prep.maxProjection.getHeight(), i);

                    if (preview) {
                        ImportPreviewDecision decision =
                                previewImportedRoi(prep, imported, i, totalImages);
                        if (decision == ImportPreviewDecision.CANCEL) {
                            inputStatus = "cancelled";
                            closePreparedImages(prep);
                            closeAllNoPrompt();
                            return false;
                        }
                        if (decision == ImportPreviewDecision.SKIP_REMAINING) {
                            preview = false;
                        }
                    }

                    String importImageKey;
                    try {
                        importImageKey = OrientationImageIdentity.fromProjectSeries(
                                directory, i, supplier.getSeriesName(i)).imageKey;
                    } catch (Exception idEx) {
                        importImageKey = "";
                    }
                    if (importImageKey == null || importImageKey.trim().isEmpty()) {
                        IJ.log("[FLASH] Import: no durable identity for image " + (i + 1)
                                + "; skipping (cannot bind ROI to image).");
                        continue;
                    }
                    addImportedFlashRoiPair(rm, roiProps, options.roiSetName,
                            prep, imported, i, imageOutputDir, importImageKey);
                    saveOrientationDecision(orientationManifestService, prep,
                            "Saved during ROI zip import");
                } finally {
                    closePreparedImages(prep);
                }
            }

            roiProps.setPrecision(9);
            addRunIdColumn(roiProps, currentRunId());
            roiProps.save(roiPropsOut.getAbsolutePath());
            recordOutput(roiPropsOut, "csv");

            if (!RoiSetValidator.validateStructuralWithDialog(rm, null)) {
                return false;
            }

            rm.runCommand("Save", roiZip.getAbsolutePath());
            recordOutput(roiZip, "zip");
            rm.close();
            rm = null;
            inputStatus = "processed";

            showOrLog("Import ROI Set",
                    "Imported ROIs to:\n" + roiZip.getAbsolutePath()
                    + "\n\nROI properties saved to:\n" + roiPropsOut.getAbsolutePath());
            closeAllNoPrompt();
            return true;
        } catch (Exception e) {
            String message = e.getMessage() == null ? String.valueOf(e) : e.getMessage();
            IJ.log("[DrawROIs] ROI import failed: " + message);
            recordWarn("ROI import failed: " + message);
            showOrLog("Import ROI Set", "Could not import ROI zip:\n" + message);
            return false;
        } finally {
            if (runRecordContext != null && zipInput != null) {
                runRecordContext.recordInputEnd(zipInput, inputStatus,
                        System.currentTimeMillis() - started);
            }
            if (rm != null) {
                rm.close();
            }
        }
    }

    private static void validateImportZipFile(File importZip) {
        if (importZip == null) {
            throw new IllegalArgumentException("No ROI zip was selected.");
        }
        if (!importZip.isFile()) {
            throw new IllegalArgumentException(
                    "ROI zip does not exist: " + importZip.getAbsolutePath());
        }
        if (!isZipFile(importZip)) {
            throw new IllegalArgumentException(
                    "ROI import requires a .zip file: " + importZip.getName());
        }
    }

    static ImportedRoiLayout resolveImportedRoiLayout(int roiCount, int totalImages) {
        if (totalImages <= 0) {
            throw new IllegalArgumentException("No image series were found for this project.");
        }
        if (roiCount == totalImages) {
            return ImportedRoiLayout.ONE_PER_IMAGE;
        }
        long expectedPairs = (long) totalImages * 2L;
        if (roiCount == expectedPairs) {
            return ImportedRoiLayout.FLASH_PAIRS;
        }
        throw new IllegalArgumentException("ROI zip contains " + roiCount
                + " ROI(s), but this image series has " + totalImages
                + " image(s). Expected either " + totalImages
                + " ROI(s) (one per image) or " + expectedPairs
                + " ROI(s) (FLASH uncropped/_Cropped pairs).");
    }

    static Roi sourceRoiForImport(List<Roi> importedRois,
                                  ImportedRoiLayout layout,
                                  int imageIndex) {
        if (importedRois == null) {
            throw new IllegalArgumentException("No imported ROIs were loaded.");
        }
        if (layout == null) {
            throw new IllegalArgumentException("Imported ROI layout is not known.");
        }
        int sourceIndex = layout == ImportedRoiLayout.FLASH_PAIRS
                ? imageIndex * 2
                : imageIndex;
        if (sourceIndex < 0 || sourceIndex >= importedRois.size()) {
            throw new IllegalArgumentException("Imported ROI set is missing ROI "
                    + (sourceIndex + 1) + ".");
        }
        Roi roi = importedRois.get(sourceIndex);
        if (roi == null) {
            throw new IllegalArgumentException("Imported ROI " + (sourceIndex + 1)
                    + " could not be read.");
        }
        return roi;
    }

    static void validateImportedFlashPairNames(List<Roi> importedRois, int totalImages) {
        for (int i = 0; i < totalImages; i++) {
            Roi uncropped = sourceRoiForImport(importedRois,
                    ImportedRoiLayout.ONE_PER_IMAGE, i * 2);
            Roi cropped = sourceRoiForImport(importedRois,
                    ImportedRoiLayout.ONE_PER_IMAGE, i * 2 + 1);
            String uncroppedName = uncropped.getName();
            String croppedName = cropped.getName();
            if (uncroppedName != null && uncroppedName.endsWith("_Cropped")) {
                throw new IllegalArgumentException("Imported FLASH pair " + (i + 1)
                        + " has a cropped ROI in the uncropped slot.");
            }
            if (croppedName == null || !croppedName.endsWith("_Cropped")) {
                throw new IllegalArgumentException("Imported FLASH pair " + (i + 1)
                        + " is missing a _Cropped ROI in the cropped slot.");
            }
        }
    }

    static boolean importedRoiBoundsWithinImage(Roi roi, int width, int height) {
        if (roi == null || width <= 0 || height <= 0) return false;
        Rectangle bounds = roi.getBounds();
        if (bounds == null || bounds.width <= 0 || bounds.height <= 0) return false;
        return bounds.x >= 0
                && bounds.y >= 0
                && bounds.x + bounds.width <= width
                && bounds.y + bounds.height <= height;
    }

    private static void validateImportedRoiBounds(Roi roi, int width, int height, int imageIndex) {
        if (importedRoiBoundsWithinImage(roi, width, height)) {
            return;
        }
        Rectangle bounds = roi == null ? null : roi.getBounds();
        String boundsText = bounds == null
                ? "no bounds"
                : bounds.x + "," + bounds.y + " " + bounds.width + "x" + bounds.height;
        throw new IllegalArgumentException("Imported ROI " + (imageIndex + 1)
                + " does not fit image " + (imageIndex + 1) + " (image "
                + width + "x" + height + ", ROI bounds " + boundsText + ").");
    }

    private boolean confirmOverwriteImportOutputs(File roiZip, File roiPropsOut) {
        boolean roiExists = roiZip != null && roiZip.exists();
        boolean propsExists = roiPropsOut != null && roiPropsOut.exists();
        if (!roiExists && !propsExists) {
            return true;
        }
        StringBuilder body = new StringBuilder();
        body.append("Importing this ROI set will overwrite existing output:\n\n");
        if (roiExists) body.append(roiZip.getAbsolutePath()).append('\n');
        if (propsExists) body.append(roiPropsOut.getAbsolutePath()).append('\n');
        body.append("\nContinue?");
        return JOptionPane.showConfirmDialog(imageJMainWindow(), body.toString(),
                "Overwrite ROI Import Outputs", JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    private ImportPreviewDecision previewImportedRoi(PreparedImage prep,
                                                     Roi imported,
                                                     int imageIndex,
                                                     int totalImages) {
        if (prep == null || prep.maxProjection == null) {
            return ImportPreviewDecision.CANCEL;
        }
        Roi previewRoi = duplicateRoi(imported);
        prep.maxProjection.setRoi(previewRoi);
        prep.maxProjection.show();
        placeRoiImageWindowNearImageJ(prep.maxProjection, imageJMainWindow());

        Object[] options = new Object[]{
                "Next ROI",
                "Import without more previews",
                "Cancel import"
        };
        int choice = showImportPreviewOptionDialog(prep.maxProjection, imageJMainWindow(),
                "Previewing imported ROI " + (imageIndex + 1) + "/" + totalImages
                        + ".\n\nCheck that the ROI overlay matches the displayed image.",
                options);
        if (choice == 0) return ImportPreviewDecision.CONTINUE;
        if (choice == 1) return ImportPreviewDecision.SKIP_REMAINING;
        return ImportPreviewDecision.CANCEL;
    }

    private static int showImportPreviewOptionDialog(ImagePlus image,
                                                     Window owner,
                                                     String message,
                                                     Object[] options) {
        JOptionPane pane = new JOptionPane(importPreviewMessage(message),
                JOptionPane.QUESTION_MESSAGE,
                JOptionPane.DEFAULT_OPTION,
                null,
                options,
                options == null || options.length == 0 ? null : options[0]);
        JDialog dialog = pane.createDialog(owner, "Import ROI Set Preview");
        positionImportPreviewDialogBesideImage(dialog, image, owner);
        dialog.setVisible(true);
        Object selected = pane.getValue();
        dialog.dispose();
        if (selected == null || selected == JOptionPane.UNINITIALIZED_VALUE) {
            return JOptionPane.CLOSED_OPTION;
        }
        if (options != null) {
            for (int i = 0; i < options.length; i++) {
                if (selected.equals(options[i])) {
                    return i;
                }
            }
        }
        return selected instanceof Integer
                ? ((Integer) selected).intValue()
                : JOptionPane.CLOSED_OPTION;
    }

    private static JComponent importPreviewMessage(String message) {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setOpaque(false);
        panel.add(importPreviewWorkflowRow(), BorderLayout.NORTH);
        JLabel body = new JLabel("<html><body style='width:320px'>"
                + htmlEscape(message).replace("\n", "<br>")
                + "</body></html>");
        panel.add(body, BorderLayout.CENTER);
        return panel;
    }

    private static JComponent importPreviewWorkflowRow() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row.setOpaque(false);
        row.add(workflowChip("Setup", false));
        row.add(workflowSeparator());
        row.add(workflowChip("Import", false));
        row.add(workflowSeparator());
        row.add(workflowChip("Preview", true));
        row.add(workflowSeparator());
        row.add(workflowChip("Save", false));
        return row;
    }

    private static JLabel workflowChip(String text, boolean active) {
        JLabel chip = new JLabel(" " + text + " ");
        chip.setOpaque(true);
        chip.setFont(chip.getFont().deriveFont(active ? Font.BOLD : Font.PLAIN, 11f));
        chip.setBorder(BorderFactory.createLineBorder(FlashTheme.TEXT_HEADER, 1, true));
        chip.setBackground(active ? FlashTheme.TEXT_HEADER : FlashTheme.SURFACE);
        chip.setForeground(active ? FlashTheme.TEXT_ON_DARK : FlashTheme.TEXT_HEADER);
        return chip;
    }

    private static JLabel workflowSeparator() {
        JLabel label = new JLabel(">");
        label.setForeground(FlashTheme.TEXT_MUTED);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 11f));
        return label;
    }

    private static void positionImportPreviewDialogBesideImage(JDialog dialog,
                                                               ImagePlus image,
                                                               Window owner) {
        if (dialog == null) return;
        Window imageWindow = image == null ? null : image.getWindow();
        if (imageWindow != null) {
            Rectangle bounds = imageWindow.getBounds();
            Rectangle screenBounds = usableScreenBounds(imageWindow.getGraphicsConfiguration());
            dialog.setLocation(importPreviewDialogLocationNearImage(
                    bounds, dialog.getSize(), screenBounds));
        } else if (owner != null) {
            dialog.setLocationRelativeTo(owner);
        } else {
            dialog.setLocationByPlatform(true);
        }
    }

    private void addImportedFlashRoiPair(RoiManager rm,
                                         ResultsTable roiProps,
                                         String roiSetName,
                                         PreparedImage prep,
                                         Roi imported,
                                         int imageIndex,
                                         File imageOutputDir,
                                         String imageKey) {
        if (rm == null) throw new IllegalStateException("ROI Manager is not available.");
        if (prep == null || prep.maxProjection == null) {
            throw new IllegalStateException("Prepared ROI image is not available.");
        }

        NameParts parts = prep.parts;
        // Bind imported ROIs to the image by durable identity token (region-scoped format),
        // so imported sets are consumable by the token-based analyses.
        String base = RoiSetImageBinding.drawnRoiName(imageKey);
        String croppedName = RoiSetImageBinding.croppedRoiName(imageKey);

        Roi uncropped = duplicateRoi(imported);
        uncropped.setName(base);
        rm.addRoi(uncropped);

        ImagePlus cropped = prep.maxProjection.duplicate();
        try {
            Roi cropSource = duplicateRoi(imported);
            cropped.setRoi(cropSource);
            IJ.run(cropped, "Crop", "");
            Roi croppedRoi = cropped.getRoi();
            Roi managerCropped = croppedRoi == null
                    ? duplicateRoi(imported)
                    : duplicateRoi(croppedRoi);
            managerCropped.setName(croppedName);
            rm.addRoi(managerCropped);

            appendImportedRoiProperties(roiProps, roiSetName, prep, imported, imageIndex);
            saveImportedCroppedPreview(imageOutputDir, parts, cropped);
        } finally {
            closeImageNoPrompt(cropped);
        }
    }

    private void saveImportedCroppedPreview(File imageOutputDir,
                                            NameParts parts,
                                            ImagePlus cropped) {
        if (imageOutputDir == null || cropped == null) return;
        String suffix = parts == null ? "" : parts.fileSuffix();
        String croppedName = suffix.isEmpty()
                ? "Cropped.PNG"
                : "Cropped_" + suffix + ".PNG";
        File croppedOutput = new File(imageOutputDir, croppedName);
        IJ.saveAs(cropped, "PNG", croppedOutput.getAbsolutePath());
        recordOutput(croppedOutput, "png");
    }

    private static void appendImportedRoiProperties(ResultsTable roiProps,
                                                    String roiSetName,
                                                    PreparedImage prep,
                                                    Roi roi,
                                                    int imageIndex) {
        if (roiProps == null || prep == null || roi == null) return;
        NameParts parts = prep.parts;
        String animal = parts == null ? "" : parts.animal;
        String hemisphere = parts == null ? "" : parts.hemisphere;
        String region = parts == null ? "" : parts.region;

        Rectangle b = roi.getBounds();
        roiProps.incrementCounter();
        int row = roiProps.size() - 1;
        roiProps.setValue("Animal Name", row, animal);
        String regionLabel = (hemisphere.isEmpty() && region.isEmpty())
                ? "" : hemisphere + region;
        roiProps.setValue("Region", row, regionLabel);
        roiProps.setValue(roiSetName, row, imageIndex + 1);

        ImagePlus imp = prep.original;
        ij.measure.Calibration cal = imp == null ? null : imp.getCalibration();
        boolean hasCalibration = cal != null
                && !"pixel".equalsIgnoreCase(cal.getUnit())
                && !"pixels".equalsIgnoreCase(cal.getUnit())
                && cal.pixelWidth != 0 && cal.pixelHeight != 0;
        double roiArea = roi.getStatistics().area;
        if (hasCalibration) {
            double pixelArea = roiArea / (cal.pixelWidth * cal.pixelHeight);
            roiProps.setValue("Area (pixel)", row, pixelArea);
            roiProps.setValue("Area (um^2)", row, roiArea);
            int slices = imp == null ? 1 : Math.max(1, imp.getNSlices());
            double zDepth = cal.pixelDepth * slices;
            double volumeUm3 = roiArea * zDepth;
            double volumeMm3 = volumeUm3 / 1e9;
            roiProps.setValue("Volume (micron^3)", row, volumeUm3);
            roiProps.setValue("Volume (mm^3)", row, volumeMm3);
        } else {
            roiProps.setValue("Area (pixel)", row, roiArea);
        }
        roiProps.setValue("Width", row, b == null ? 0 : b.width);
        roiProps.setValue("Height", row, b == null ? 0 : b.height);
    }

    static String importSetNameFromZip(File zip) {
        String name = zip == null ? "" : zip.getName();
        name = name.replaceAll("(?i)\\s*ROIs\\.zip$", "");
        name = name.replaceAll("(?i)\\.zip$", "");
        return sanitizeRoiSetName(name);
    }

    private static int existingRoiCountForSelection(JComboBox<String> appendChoice,
                                                    List<String> roiNames,
                                                    List<File> roiFiles) {
        if (appendChoice == null || roiNames == null || roiFiles == null) {
            return 0;
        }
        Object selected = appendChoice.getSelectedItem();
        if (selected == null) {
            return 0;
        }
        int selectedIndex = roiNames.indexOf(selected.toString());
        if (selectedIndex < 0 || selectedIndex >= roiFiles.size()) {
            return 0;
        }
        File selectedZip = roiFiles.get(selectedIndex);
        return RoiIO.loadRoisFromZip(selectedZip).size();
    }

    static String sanitizeRoiSetName(String value) {
        String name = value == null ? "" : value.trim();
        name = name.replaceAll("[\\\\/:*?\"<>|]+", "_").trim();
        return name.isEmpty() ? "Imported ROIs" : name;
    }

    private static boolean isZipFile(File file) {
        return file != null
                && file.getName() != null
                && file.getName().toLowerCase(Locale.ROOT).endsWith(".zip");
    }

    private static Roi duplicateRoi(Roi roi) {
        if (roi == null) return null;
        return (Roi) roi.clone();
    }

    private static String htmlEscape(String value) {
        if (value == null) return "";
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '&':
                    escaped.append("&amp;");
                    break;
                case '<':
                    escaped.append("&lt;");
                    break;
                case '>':
                    escaped.append("&gt;");
                    break;
                case '"':
                    escaped.append("&quot;");
                    break;
                case '\'':
                    escaped.append("&#39;");
                    break;
                default:
                    escaped.append(ch);
                    break;
            }
        }
        return escaped.toString();
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

    OrientationBatchController.CurrentImage currentImageFor(final PreparedImage prep) {
        return new OrientationBatchController.CurrentImage() {
            @Override
            public OrientationManifestRow.Hemisphere hemisphere() {
                return hemisphereFor(prep);
            }

            @Override
            public OrientationTransformState state() {
                return prep == null || prep.transformState == null
                        ? OrientationTransformState.identity()
                        : prep.transformState;
            }

            @Override
            public void applyState(OrientationTransformState next) {
                if (prep == null) return;
                OrientationTransformState safeNext = next == null
                        ? OrientationTransformState.identity()
                        : next;
                if (sameTransformState(prep.transformState, safeNext)) {
                    return;
                }
                clearUnsavedRoiAfterOrientationChange(prep);
                prep.transformState = safeNext;
                if (isPreparedImageShown(prep)) {
                    rebuildDisplayedImages(prep);
                } else {
                    renderPreparedForState(prep, safeNext);
                }
            }
        };
    }

    private RoiOrientationPanel.OrientationActionTarget createOrientationTarget(
            final PreparedImage prep) {
        return new RoiOrientationPanel.OrientationActionTarget() {
            @Override
            public OrientationTransformState getState() {
                return prep == null ? OrientationTransformState.identity() : prep.transformState;
            }

            @Override
            public void setState(OrientationTransformState state) {
                if (prep != null) {
                    prep.transformState = state == null
                            ? OrientationTransformState.identity()
                            : state;
                }
            }

            @Override
            public void redrawFromState() {
                rebuildDisplayedImages(prep);
            }

            @Override
            public void clearUnsavedRoiAfterOrientationChange() {
                DrawAndSaveROIsAnalysis.clearUnsavedRoiAfterOrientationChange(prep);
            }

            @Override
            public String statusText() {
                return orientationStatusText(getState());
            }

            @Override
            public boolean displayControlsAvailable() {
                return prep != null && prep.maxProjection != null;
            }

            @Override
            public String lutToggleButtonText() {
                return roiLutToggleButtonText(prep);
            }

            @Override
            public String lutToggleButtonToolTipText() {
                return roiLutToggleToolTipText(prep);
            }

            @Override
            public void toggleDisplayLut() {
                toggleRoiDisplayLut(prep);
            }

            @Override
            public void adjustBrightnessContrast() {
                adjustRoiBrightnessContrast(prep);
            }
        };
    }

    private void rebuildDisplayedImages(PreparedImage prep) {
        if (prep == null) return;

        ImagePlus oldMax = prep.maxProjection;
        Point windowLocation = imageWindowLocation(oldMax);
        if (!renderPreparedForState(prep, prep.transformState)) {
            return;
        }

        prep.maxProjection.show();
        if (windowLocation != null && prep.maxProjection.getWindow() != null) {
            prep.maxProjection.getWindow().setLocation(windowLocation);
        }
        IJ.setTool(Toolbar.FREEROI);
    }

    static boolean renderPreparedForState(PreparedImage prep,
                                          OrientationTransformState state) {
        if (prep == null || prep.original == null) return false;
        ImagePlus oldMax = prep.maxProjection;
        ImagePlus oldStack = prep.roiStack;
        double displayMin = oldMax == null ? Double.NaN : oldMax.getDisplayRangeMin();
        double displayMax = oldMax == null ? Double.NaN : oldMax.getDisplayRangeMax();

        OrientationTransformState safeState = state == null
                ? OrientationTransformState.identity()
                : state;
        ImagePlus nextStack = ImageOps.duplicateThreadSafe(prep.original, 1, 1,
                1, Math.max(1, prep.original.getNSlices()),
                1, Math.max(1, prep.original.getNFrames()));
        if (nextStack == null) return false;
        nextStack.setTitle("delete");
        prep.transformState = safeState;
        prep.applyCurrentTransformTo(nextStack);
        ImagePlus nextMax = ZProjector.run(nextStack, "max");
        nextMax.setTitle("MAX_delete");

        if ("Automatic".equals(prep.imageProcessing)) {
            IJ.run(nextMax, "Enhance Contrast", "saturated=1");
        } else if (!Double.isNaN(displayMin) && !Double.isNaN(displayMax)
                && displayMax > displayMin) {
            nextMax.setDisplayRange(displayMin, displayMax);
        }
        applyRoiDisplayLut(nextMax, effectiveRoiLutName(prep));

        prep.roiStack = nextStack;
        prep.maxProjection = nextMax;
        closeImageNoPrompt(oldMax);
        if (oldStack != null && oldStack != prep.original) {
            closeImageNoPrompt(oldStack);
        }
        return true;
    }

    static OrientationManifestRow.Hemisphere hemisphereFor(PreparedImage prep) {
        if (prep == null) return OrientationManifestRow.Hemisphere.UNKNOWN;
        String value = "";
        if (prep.parts != null && prep.parts.hemisphere != null
                && !prep.parts.hemisphere.trim().isEmpty()) {
            value = prep.parts.hemisphere;
        } else if (prep.seedMetadata != null) {
            value = prep.seedMetadata.hemisphere;
        }
        return OrientationManifestRow.Hemisphere.fromCsv(value);
    }

    private static boolean isPreparedImageShown(PreparedImage prep) {
        return prep != null
                && prep.maxProjection != null
                && prep.maxProjection.getWindow() != null;
    }

    private static void clearUnsavedRoiAfterOrientationChange(PreparedImage prep) {
        ImagePlus max = prep == null ? null : prep.maxProjection;
        if (max != null && max.getRoi() != null) {
            max.deleteRoi();
            max.updateAndDraw();
            IJ.log("[DrawROIs] Orientation changed after ROI drawing; "
                    + "cleared unsaved ROI so it can be redrawn.");
        }
    }

    private static boolean sameTransformState(OrientationTransformState a,
                                              OrientationTransformState b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.rotateDegrees == b.rotateDegrees
                && a.flipHorizontal == b.flipHorizontal
                && a.flipVertical == b.flipVertical;
    }

    static OrientationManifestRow saveOrientationDecision(
            RoiOrientationManifestService service,
            PreparedImage prep,
            String notes) {
        if (service == null || prep == null || prep.identity == null) return null;
        try {
            NameParts parts = prep.parts;
            String animal = parts == null ? "" : parts.animal;
            String hemisphere = parts == null ? "" : parts.hemisphere;
            String region = parts == null ? "" : parts.region;
            OrientationManifestRow row = service.upsertDecision(
                    prep.identity,
                    prep.seedMetadata,
                    prep.transformState,
                    animal,
                    OrientationManifestRow.Hemisphere.fromCsv(hemisphere),
                    region,
                    notes);
            IJ.log("[DrawROIs] Saved orientation decision for "
                    + prep.identity.displayName + ".");
            return row;
        } catch (Exception e) {
            String label = prep.identity == null ? "image" : prep.identity.displayName;
            IJ.log("[DrawROIs] WARN: could not save orientation decision for "
                    + label + ": " + e.getMessage());
            return null;
        }
    }

    private static void closePreparedImages(PreparedImage prep) {
        if (prep == null) return;
        closeImageNoPrompt(prep.maxProjection);
        if (prep.roiStack != prep.original) {
            closeImageNoPrompt(prep.roiStack);
        }
        closeImageNoPrompt(prep.original);
    }

    private static void closeImageNoPrompt(ImagePlus image) {
        if (image == null) return;
        image.changes = false;
        image.close();
        image.flush();
    }

    private static Point imageWindowLocation(ImagePlus image) {
        return image == null || image.getWindow() == null
                ? null
                : image.getWindow().getLocation();
    }

    private static Window imageJMainWindow() {
        if (GraphicsEnvironment.isHeadless()) return null;
        try {
            return IJ.getInstance();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void placeRoiImageWindowNearImageJ(ImagePlus image, Window imageJWindow) {
        if (image == null || image.getWindow() == null || imageJWindow == null) return;
        Dimension imageSize = image.getWindow().getSize();
        Rectangle screenBounds = usableScreenBounds(imageJWindow.getGraphicsConfiguration());
        Point target = roiImageWindowLocationNearAnchor(
                imageJWindow.getBounds(), imageSize, screenBounds);
        image.getWindow().setLocation(target);
    }

    static Point roiImageWindowLocationNearAnchor(Rectangle anchorBounds,
                                                  Dimension imageSize,
                                                  Rectangle screenBounds) {
        Rectangle safeScreen = screenBounds == null
                ? new Rectangle(Toolkit.getDefaultToolkit().getScreenSize())
                : screenBounds;
        Dimension safeSize = imageSize == null
                ? new Dimension(1, 1)
                : imageSize;
        int gap = 12;
        int x = anchorBounds == null ? safeScreen.x : anchorBounds.x;
        int y = anchorBounds == null
                ? safeScreen.y
                : anchorBounds.y + anchorBounds.height + gap;
        if (y + safeSize.height > safeScreen.y + safeScreen.height
                && anchorBounds != null) {
            y = anchorBounds.y - safeSize.height - gap;
        }
        return new Point(
                clamp(x, safeScreen.x, safeScreen.x + safeScreen.width - safeSize.width),
                clamp(y, safeScreen.y, safeScreen.y + safeScreen.height - safeSize.height));
    }

    static Point importPreviewDialogLocationNearImage(Rectangle imageBounds,
                                                      Dimension dialogSize,
                                                      Rectangle screenBounds) {
        // Match RoiOrientationPanel placement so import previews use the same side-of-image handoff.
        Rectangle safeScreen = screenBounds == null
                ? new Rectangle(Toolkit.getDefaultToolkit().getScreenSize())
                : screenBounds;
        Dimension safeSize = dialogSize == null
                ? new Dimension(1, 1)
                : dialogSize;
        if (imageBounds == null) {
            return new Point(safeScreen.x, safeScreen.y);
        }

        int gap = 12;
        int x = imageBounds.x + imageBounds.width + gap;
        if (x + safeSize.width > safeScreen.x + safeScreen.width) {
            x = imageBounds.x - safeSize.width - gap;
        }
        if (x < safeScreen.x) {
            x = imageBounds.x;
        }

        int y = imageBounds.y;
        return new Point(
                clamp(x, safeScreen.x, safeScreen.x + safeScreen.width - safeSize.width),
                clamp(y, safeScreen.y, safeScreen.y + safeScreen.height - safeSize.height));
    }

    private static Rectangle usableScreenBounds(GraphicsConfiguration gc) {
        if (gc == null) {
            return new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        }
        Rectangle bounds = gc.getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        return new Rectangle(
                bounds.x + insets.left,
                bounds.y + insets.top,
                bounds.width - insets.left - insets.right,
                bounds.height - insets.top - insets.bottom);
    }

    private static int clamp(int value, int min, int max) {
        if (max < min) return min;
        return Math.max(min, Math.min(max, value));
    }

    private static String orientationStatusText(OrientationTransformState state) {
        OrientationTransformState safe = state == null
                ? OrientationTransformState.identity()
                : state;
        return "Rotate: " + safe.rotateDegrees.degrees()
                + " deg; Flip H: " + yesNo(safe.flipHorizontal)
                + "; Flip V: " + yesNo(safe.flipVertical);
    }

    private static String yesNo(boolean value) {
        return value ? "Yes" : "No";
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
            recordOutput(new File(path), "zip");
        }
        if (path != null) {
            showOrLog("Draw ROIs and Orientate Images",
                    "Run aborted (" + reason + ").\n\n"
                    + "Partial ROI set saved to:\n" + path + "\n\n"
                    + "To recover: re-run Draw ROIs, choose 'Append to existing',\n"
                    + "and load that zip (or copy it into the ROI output folder first).");
        } else {
            showOrLog("Draw ROIs and Orientate Images",
                    "Run aborted (" + reason + "). No ROIs were saved.");
        }
        rm.close();
        closeAllNoPrompt();
    }

    private void recordOutput(File file, String kind) {
        if (runRecordContext != null && file != null) {
            runRecordContext.recordOutput(file, kind);
        }
    }

    private String currentRunId() {
        return RunIdCsv.runId(runRecordContext);
    }

    private static void addRunIdColumn(ResultsTable table, String runId) {
        if (table == null) {
            return;
        }
        for (int row = 0; row < table.size(); row++) {
            table.setValue(RunIdCsv.RUN_ID_COLUMN, row, runId == null ? "" : runId);
        }
    }

    private void recordWarn(String message) {
        if (runRecordContext != null) {
            runRecordContext.warn(message);
        }
    }

    /**
     * Pad the ROI Manager with placeholder full-image ROIs so that image
     * {@code imageIndex} ends up with the required two ROIs (uncropped +
     * cropped). Names follow the strict-validator convention so the final
     * RoiSetValidator pass succeeds.
     */
    private static void padPlaceholderRois(RoiManager rm, int processingIndex,
                                           int sourceSeriesIndex, int startOffset,
                                           int width, int height) {
        int countAfter = rm.getCount();
        int expectedAfter = startOffset + (processingIndex + 1) * 2;
        int missing = expectedAfter - countAfter;
        for (int p = 0; p < missing; p++) {
            int slot = countAfter + p;
            int relSlot = slot - startOffset;
            boolean isCroppedSlot = (relSlot % 2) == 1;
            Roi placeholder = new Roi(0, 0, width, height);
            placeholder.setName("PLACEHOLDER_image" + (sourceSeriesIndex + 1)
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

    static String roiChannelLutName(BinConfig cfg, int roiChannel) {
        int index = Math.max(1, roiChannel) - 1;
        if (cfg != null && cfg.channelColors != null
                && index >= 0 && index < cfg.channelColors.size()) {
            return normalizeLutName(cfg.channelColors.get(index));
        }
        return "Grays";
    }

    static String normalizeLutName(String color) {
        if (color == null) return "Grays";
        String normalized = color.trim().toUpperCase(Locale.ROOT);
        if ("RED".equals(normalized)) return "Red";
        if ("GREEN".equals(normalized)) return "Green";
        if ("BLUE".equals(normalized)) return "Blue";
        if ("CYAN".equals(normalized)) return "Cyan";
        if ("MAGENTA".equals(normalized)) return "Magenta";
        if ("YELLOW".equals(normalized)) return "Yellow";
        if ("GRAY".equals(normalized) || "GREY".equals(normalized)
                || "GRAYS".equals(normalized) || "GREYS".equals(normalized)) {
            return "Grays";
        }
        return "Grays";
    }

    static void applyRoiDisplayLut(ImagePlus imp, String colorName) {
        if (imp == null) return;
        String normalized = normalizeLutName(colorName);
        byte[] r = new byte[256];
        byte[] g = new byte[256];
        byte[] b = new byte[256];
        boolean addR = "Red".equals(normalized) || "Magenta".equals(normalized)
                || "Yellow".equals(normalized) || "Grays".equals(normalized);
        boolean addG = "Green".equals(normalized) || "Cyan".equals(normalized)
                || "Yellow".equals(normalized) || "Grays".equals(normalized);
        boolean addB = "Blue".equals(normalized) || "Cyan".equals(normalized)
                || "Magenta".equals(normalized) || "Grays".equals(normalized);
        for (int i = 0; i < 256; i++) {
            if (addR) r[i] = (byte) i;
            if (addG) g[i] = (byte) i;
            if (addB) b[i] = (byte) i;
        }
        imp.getProcessor().setLut(new LUT(r, g, b));
        imp.updateAndDraw();
    }

    static String effectiveRoiLutName(PreparedImage prep) {
        return prep == null || prep.usingGreyLut ? "Grays" : prep.roiLutName;
    }

    static String roiLutToggleButtonText(PreparedImage prep) {
        if (prep == null) return "Grey LUT";
        return prep.usingGreyLut ? normalizeLutName(prep.roiLutName) + " LUT" : "Grey LUT";
    }

    static String roiLutToggleToolTipText(PreparedImage prep) {
        if (prep != null && prep.usingGreyLut) {
            return "Show ROI image with the selected channel LUT.";
        }
        return "Show ROI image in grey.";
    }

    static void toggleRoiDisplayLut(PreparedImage prep) {
        if (prep == null) return;
        prep.usingGreyLut = !prep.usingGreyLut;
        applyRoiDisplayLut(prep.maxProjection, effectiveRoiLutName(prep));
    }

    private void adjustRoiBrightnessContrast(PreparedImage prep) {
        if (prep == null || prep.maxProjection == null) return;
        ImagePlus image = prep.maxProjection;
        if (image.getWindow() != null) {
            image.getWindow().toFront();
        }
        IJ.run(image, "Brightness/Contrast...", "");
        positionToolWindowNextToImage(image, BRIGHTNESS_CONTRAST_WINDOW_TITLES);
    }

    private static void positionToolWindowNextToImage(ImagePlus image, String... toolWindowTitles) {
        java.awt.Frame frame = findToolWindow(toolWindowTitles);
        if (frame == null) return;
        Window imageWindow = image == null ? null : image.getWindow();
        if (imageWindow != null) {
            frame.setLocation(imageWindow.getX() + imageWindow.getWidth() + 10,
                    imageWindow.getY() + 210);
        } else {
            Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            frame.setLocation(screen.width - frame.getWidth() - 20, 260);
        }
    }

    private static java.awt.Frame findToolWindow(String... toolWindowTitles) {
        java.awt.Frame[] frames = WindowManager.getNonImageWindows();
        if (frames == null) return null;
        for (java.awt.Frame frame : frames) {
            if (frame != null && matchesToolWindowTitle(frame.getTitle(), toolWindowTitles)) {
                return frame;
            }
        }
        return null;
    }

    private static boolean matchesToolWindowTitle(String actualTitle, String... candidateTitles) {
        if (actualTitle == null || candidateTitles == null) return false;
        String normalizedActual = actualTitle.trim();
        for (String candidate : candidateTitles) {
            if (candidate == null) continue;
            String normalizedCandidate = candidate.trim();
            if (normalizedActual.equalsIgnoreCase(normalizedCandidate)
                    || normalizedActual.startsWith(normalizedCandidate + " ")) {
                return true;
            }
        }
        return false;
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
        AnalysisRunContext.InputHandle inputHandle = null;
        long started = System.currentTimeMillis();
        if (runRecordContext != null && supplier != null) {
            try {
                inputHandle = runRecordContext.recordInputStart(
                        supplier.getContainerFileForSeries(seriesIndex), seriesIndex + 1, null);
            } catch (Exception ignored) {
                inputHandle = runRecordContext.recordInputStart(null, seriesIndex + 1, null);
            }
        }

        ImagePlus imp = null;
        try {
            // Load only the specific channel requested for the ROI (0-indexed for Bio-Formats)
            imp = supplier.openSeriesMaterializedChannel(seriesIndex, roiChannel - 1);
            if (imp == null) {
                recordInputEnd(inputHandle, "skipped", started);
                return null;
            }

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
            OrientationTransformState transformState =
                    OrientationTransformState.fromMetadata(metadata);

            String roiLutName = roiChannelLutName(roiBinCfg, roiChannel);
            PreparedImage prepared = buildPreparedImage(directory, seriesIndex, imp,
                    null, null, parts, metadata,
                    imageProcessing, roiLutName);
            renderPreparedForState(prepared, transformState);
            recordInputEnd(inputHandle, "processed", started);
            return prepared;
        } catch (Exception e) {
            recordInputEnd(inputHandle, "failed", started);
            throw e;
        }
    }

    private void recordInputEnd(AnalysisRunContext.InputHandle inputHandle,
                                String status,
                                long startedMillis) {
        if (runRecordContext != null && inputHandle != null) {
            runRecordContext.recordInputEnd(inputHandle, status,
                    System.currentTimeMillis() - startedMillis);
        }
    }

    private static void logOrientationResolution(ResolvedImageMetadata metadata) {
        IJ.log("  Orientation source: " + metadata.sourceLabel());
        IJ.log(metadata.hasTransform()
                ? "  Orientation transform applied."
                : "  Orientation transform skipped.");
    }

    static PreparedImage buildPreparedImage(String directory,
                                            int seriesIndex,
                                            ImagePlus original,
                                            ImagePlus maxProjection,
                                            ImagePlus roiStack,
                                            NameParts parts,
                                            ResolvedImageMetadata seedMetadata)
            throws Exception {
        return buildPreparedImage(directory, seriesIndex, original, maxProjection, roiStack,
                parts, seedMetadata, "");
    }

    static PreparedImage buildPreparedImage(String directory,
                                            int seriesIndex,
                                            ImagePlus original,
                                            ImagePlus maxProjection,
                                            ImagePlus roiStack,
                                            NameParts parts,
                                            ResolvedImageMetadata seedMetadata,
                                            String imageProcessing)
            throws Exception {
        return buildPreparedImage(directory, seriesIndex, original, maxProjection, roiStack,
                parts, seedMetadata, imageProcessing, "Grays");
    }

    static PreparedImage buildPreparedImage(String directory,
                                            int seriesIndex,
                                            ImagePlus original,
                                            ImagePlus maxProjection,
                                            ImagePlus roiStack,
                                            NameParts parts,
                                            ResolvedImageMetadata seedMetadata,
                                            String imageProcessing,
                                            String roiLutName)
            throws Exception {
        OrientationImageIdentity identity =
                OrientationImageIdentity.fromProjectSeries(
                        directory, seriesIndex, original == null ? "" : original.getTitle());
        OrientationTransformState transformState =
                OrientationTransformState.fromMetadata(seedMetadata);
        return new PreparedImage(
                seriesIndex, original, maxProjection, roiStack, parts,
                identity, seedMetadata, transformState, imageProcessing, roiLutName);
    }

    static final class RoiSeriesRange {
        final int firstSeriesIndexInclusive;
        final int totalSeries;
        final int imageCountToProcess;

        private RoiSeriesRange(int firstSeriesIndexInclusive,
                               int totalSeries,
                               int imageCountToProcess) {
            this.firstSeriesIndexInclusive = firstSeriesIndexInclusive;
            this.totalSeries = totalSeries;
            this.imageCountToProcess = imageCountToProcess;
        }

        static RoiSeriesRange forMode(boolean createNew, int existingRoiCount, int totalSeries) {
            int normalizedTotal = Math.max(0, totalSeries);
            int first = createNew ? 0 : Math.max(0, existingRoiCount / 2);
            if (first > normalizedTotal) first = normalizedTotal;
            return new RoiSeriesRange(first, normalizedTotal, normalizedTotal - first);
        }

        int processingIndexFor(int sourceSeriesIndex) {
            return sourceSeriesIndex - firstSeriesIndexInclusive;
        }
    }

    /** Bundled result from background image preparation. */
    static class PreparedImage {
        final int seriesIndex;
        final ImagePlus original;
        ImagePlus maxProjection;
        ImagePlus roiStack;
        final NameParts parts;
        final OrientationImageIdentity identity;
        final ResolvedImageMetadata seedMetadata;
        final String imageProcessing;
        final String roiLutName;
        boolean usingGreyLut;
        OrientationTransformState transformState;

        PreparedImage(int seriesIndex,
                      ImagePlus original,
                      ImagePlus maxProjection,
                      ImagePlus roiStack,
                      NameParts parts,
                      OrientationImageIdentity identity,
                      ResolvedImageMetadata seedMetadata,
                      OrientationTransformState transformState) {
            this(seriesIndex, original, maxProjection, roiStack, parts, identity,
                    seedMetadata, transformState, "");
        }

        PreparedImage(int seriesIndex,
                      ImagePlus original,
                      ImagePlus maxProjection,
                      ImagePlus roiStack,
                      NameParts parts,
                      OrientationImageIdentity identity,
                      ResolvedImageMetadata seedMetadata,
                      OrientationTransformState transformState,
                      String imageProcessing) {
            this(seriesIndex, original, maxProjection, roiStack, parts, identity,
                    seedMetadata, transformState, imageProcessing, "Grays");
        }

        PreparedImage(int seriesIndex,
                      ImagePlus original,
                      ImagePlus maxProjection,
                      ImagePlus roiStack,
                      NameParts parts,
                      OrientationImageIdentity identity,
                      ResolvedImageMetadata seedMetadata,
                      OrientationTransformState transformState,
                      String imageProcessing,
                      String roiLutName) {
            this.seriesIndex = seriesIndex;
            this.original = original;
            this.maxProjection = maxProjection;
            this.roiStack = roiStack;
            this.parts = parts;
            this.identity = identity;
            this.seedMetadata = seedMetadata;
            this.imageProcessing = imageProcessing == null ? "" : imageProcessing;
            this.roiLutName = normalizeLutName(roiLutName);
            this.transformState = transformState == null
                    ? OrientationTransformState.identity()
                    : transformState;
        }

        void applyCurrentTransformTo(ImagePlus imp) {
            OrientationTransformState state = transformState == null
                    ? OrientationTransformState.identity()
                    : transformState;
            OrientationOps.applyTransform(
                    imp,
                    state.rotateDegrees.degrees(),
                    state.flipHorizontal,
                    state.flipVertical,
                    seedMetadata == null ? "" : seedMetadata.hemisphere,
                    OrientationManifestRow.ViewPolicy.MANUAL_ONLY);
        }
    }
}
