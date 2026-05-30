package flash.pipeline.analyses;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.BinConfigIO;
import flash.pipeline.bin.BinField;
import flash.pipeline.bin.BinSetupDispatcher;
import flash.pipeline.cli.CLIConfig;
import flash.pipeline.image.OrientationOps;
import flash.pipeline.io.CalibrationIO;
import flash.pipeline.io.CsvTableIO;
import flash.pipeline.io.CsvTableIO.ChannelData;
import flash.pipeline.io.DeferredImageSupplier;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.io.ImageSourceDispatcher;
import flash.pipeline.io.IoUtils;
import flash.pipeline.lines.LineVocab;
import flash.pipeline.naming.ChannelFilenameCodec;
import flash.pipeline.naming.ImageOrientationResolver;
import flash.pipeline.naming.NameParts;
import flash.pipeline.naming.ResolvedImageMetadata;
import flash.pipeline.results.ObjectCsvColumnOrder;
import flash.pipeline.results.RunIdCsv;
import flash.pipeline.roi.RoiIO;
import flash.pipeline.runrecord.AnalysisRunContext;
import flash.pipeline.runrecord.RunRecordAware;
import flash.pipeline.runtime.DependencyId;
import flash.pipeline.runtime.FeatureDependencyGate;
import flash.pipeline.ui.PipelineDialog;
import flash.pipeline.ui.ToggleSwitch;
import flash.pipeline.zslice.ZSliceMode;
import flash.pipeline.zslice.ZSliceOps;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.gui.Toolbar;
import ij.gui.WaitForUserDialog;
import ij.plugin.ZProjector;
import ij.plugin.frame.RoiManager;

import javax.swing.JComboBox;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.GraphicsEnvironment;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Line Distance Analysis for the FLASH pipeline.
 *
 * <p>Lets users draw named reference lines on images and computes the
 * perpendicular distance from every 3D object to those lines. Distances
 * are written as updated per-channel CSV copies in
 * {@code FLASH/Results/Tables/Line Distance/}.
 */
public class LineDistanceAnalysis implements Analysis, RunRecordAware {

    private boolean headless = false;
    private boolean verboseLogging = false;
    private boolean skipExisting = false;
    private boolean suppressDialogs = false;
    private CLIConfig cliConfig = null;
    private boolean commandMode = false;
    private List<String> commandLineSets = Collections.emptyList();
    private AnalysisRunContext runRecordContext = null;

    @Override
    public Set<BinField> requiredBinFields() {
        return EnumSet.of(BinField.Z_SLICE);
    }

    @Override
    public boolean benefitsFromRois() {
        return true;
    }

    @Override
    public boolean requiresHeadedMode() {
        return true;
    }

    @Override
    public void setHeadless(boolean headless) {
        this.headless = headless;
    }

    @Override
    public void setVerboseLogging(boolean verbose) {
        this.verboseLogging = verbose;
    }

    @Override
    public void setSkipExisting(boolean skip) {
        this.skipExisting = skip;
    }

    @Override
    public void setSuppressDialogs(boolean suppress) {
        this.suppressDialogs = suppress;
    }

    @Override
    public void setCliConfig(CLIConfig config) {
        this.cliConfig = config;
    }

    public void setCommandMode(boolean commandMode) {
        this.commandMode = commandMode;
    }

    void setCommandLineSets(List<String> lineSets) {
        this.commandLineSets = normaliseLineSetSelection(lineSets);
    }

    @Override
    public void setRunRecordContext(AnalysisRunContext context) {
        this.runRecordContext = context;
    }

    private AnalysisRunContext.InputHandle recordInputStart(File file, int seriesIndex) {
        if (runRecordContext == null) {
            return null;
        }
        return runRecordContext.recordInputStart(file, seriesIndex, null);
    }

    private void recordInputEnd(AnalysisRunContext.InputHandle inputHandle,
                                String status,
                                long startedMillis) {
        if (runRecordContext != null && inputHandle != null) {
            runRecordContext.recordInputEnd(inputHandle, status,
                    Math.max(0L, System.currentTimeMillis() - startedMillis));
        }
    }

    private void recordOutput(File file, String kind) {
        if (runRecordContext != null && file != null) {
            runRecordContext.recordOutput(file, kind);
        }
    }

    private String currentRunId() {
        return RunIdCsv.runId(runRecordContext);
    }

    private void recordWarn(String message) {
        if (runRecordContext != null) {
            runRecordContext.warn(message);
        }
    }

    private void recordError(String message, Throwable t) {
        if (runRecordContext != null) {
            runRecordContext.error(message, t);
        }
    }

    @Override
    public void execute(String directory) {
        if (commandMode) {
            executeCommandMode(directory);
            return;
        }
        if (GraphicsEnvironment.isHeadless()) {
            String message = "[" + getClass().getSimpleName() + "] is interactive only "
                    + "and cannot run headless. Skipping.";
            IJ.log(message);
            recordWarn("Line Distance Analysis is headed-only unless existing line-set zips are reused.");
            return;
        }
        if (headless) {
            IJ.log("[" + getClass().getSimpleName()
                    + "] needs visible windows; overriding Hide Image Windows for this analysis.");
            headless = false;
        }
        IJ.log("");
        IJ.log("╔══════════════════════════════════════════════════════════╗");
        IJ.log("║             LINE DISTANCE ANALYSIS                     ║");
        IJ.log("╚══════════════════════════════════════════════════════════╝");

        BinSetupDispatcher.Outcome outcome = BinSetupDispatcher.ensure(
                directory, "Line Distance Analysis", requiredBinFields(),
                benefitsFromRois(), suppressDialogs, cliConfig);
        if (outcome == BinSetupDispatcher.Outcome.CANCELLED) {
            String message = "[FLASH] Line Distance Analysis cancelled by user.";
            IJ.log(message);
            recordWarn(message);
            return;
        }

        File linesDir = lineSetWriteDir(directory);
        BinConfig dialogBinConfig = readBinConfigForLineDrawing(directory);

        // ── Step 0: Line Set Selection Dialog ─────────────────────────
        IJ.log("--- Line Set Selection ---");

        List<String> existingSetNames = lineSetNames(directory);

        boolean hasExisting = !existingSetNames.isEmpty();

        // Load shared landmark vocabulary; fall back to an empty vocab if the
        // resource is missing for any reason.
        final LineVocab vocab = loadVocabSafely();
        final List<String> vocabLabels = vocab.labels();
        final String defaultLabel = vocabLabels.isEmpty() ? LineVocab.CUSTOM_LABEL : vocabLabels.get(0);
        final String defaultCustom = "Ventricle";

        PipelineDialog pd = new PipelineDialog("Line Distance Analysis", PipelineDialog.Phase.ANALYSE);
        pd.addHeader("Line Set Selection");

        final List<ToggleSwitch> existingToggles = new ArrayList<ToggleSwitch>();
        if (hasExisting) {
            pd.addMessage("Existing line sets found:");
            for (String name : existingSetNames) {
                existingToggles.add(pd.addToggle(name, true));
            }
            pd.addToggle("Draw New Line Set", false);
        } else {
            pd.addMessage("No existing line sets found. A new set will be drawn.");
        }

        pd.addHeader("New Line Set Name");
        final JComboBox<String> vocabCombo = pd.addChoice(
                "Landmark",
                vocabLabels.toArray(new String[vocabLabels.size()]),
                defaultLabel);
        final JTextField customField = pd.addStringField("Custom name", defaultCustom, 15);
        pd.addHelpText("Pick a common landmark or choose 'Custom' to type a unique name. "
                + "Names that match an existing set above will auto-select it.");

        final JComboBox<String> zSliceSourceChoice;
        if (dialogBinConfig != null && dialogBinConfig.zSliceMode != ZSliceMode.FULL && !headless) {
            pd.addHeader("Line Drawing Source");
            pd.addMessage("A z-slice subset is configured in the current Configuration folder.");
            zSliceSourceChoice = pd.addChoice("Draw lines on",
                    new String[]{"Full image", "Configured analysis subset"}, "Full image");
            pd.addHelpText("Choose whether the max projection used for line drawing should include every slice "
                    + "or only the subset that downstream analyses use.");
        } else {
            zSliceSourceChoice = null;
        }

        // When the chosen name matches an existing line-set zip, re-tick that
        // toggle so the user does not end up overwriting it by accident.
        if (hasExisting) {
            final List<String> existingNamesFinal = existingSetNames;
            final ActionListener selectMatching = new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    String candidate = resolveTargetName(vocabCombo, customField);
                    String match = vocab.matchExistingSetName(candidate, existingNamesFinal);
                    if (match == null) return;
                    for (int i = 0; i < existingNamesFinal.size(); i++) {
                        if (existingNamesFinal.get(i).equalsIgnoreCase(match)) {
                            existingToggles.get(i).setSelected(true);
                        }
                    }
                }
            };
            vocabCombo.addActionListener(selectMatching);
            customField.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e) { selectMatching.actionPerformed(null); }
                @Override public void removeUpdate(DocumentEvent e) { selectMatching.actionPerformed(null); }
                @Override public void changedUpdate(DocumentEvent e) { selectMatching.actionPerformed(null); }
            });
        }

        if (!pd.showDialog()) {
            IJ.log("  Cancelled by user.");
            recordWarn("Line Distance Analysis cancelled by user.");
            return;
        }

        // Collect selections
        List<String> selectedSets = new ArrayList<String>();
        boolean drawNew;
        if (hasExisting) {
            for (String name : existingSetNames) {
                if (pd.getNextBoolean()) {
                    selectedSets.add(name);
                }
            }
            drawNew = pd.getNextBoolean();
        } else {
            drawNew = true;
        }
        String resolvedName = resolveTargetName(vocabCombo, customField);
        boolean drawOnSubset = zSliceSourceChoice != null
                && "Configured analysis subset".equals(zSliceSourceChoice.getSelectedItem());

        // ── Step 1: Draw Lines (if toggled on) ───────────────────────
        if (drawNew) {
            IJ.log("--- Drawing New Line Set ---");
            String newLineName = resolvedName;
            if (newLineName == null || newLineName.trim().isEmpty()) {
                String message = "Line set name is blank. Aborting draw.";
                IJ.log("  " + message);
                recordWarn(message);
                if (selectedSets.isEmpty()) return;
            } else {
                newLineName = newLineName.trim();
                boolean success = drawLineSet(directory, linesDir, newLineName,
                        dialogBinConfig, drawOnSubset);
                if (success) {
                    if (!containsIgnoreCase(selectedSets, newLineName)) {
                        selectedSets.add(newLineName);
                    }
                    IJ.log("  New line set '" + newLineName + "' saved.");
                } else {
                    String message = "Line drawing was cancelled or failed.";
                    IJ.log("  " + message);
                    recordWarn(message);
                    if (selectedSets.isEmpty()) return;
                }
            }
        }

        if (selectedSets.isEmpty()) {
            String message = "No line sets selected. Nothing to compute.";
            IJ.log("  " + message);
            recordWarn(message);
            return;
        }

        // ── Step 2: Compute Distances ────────────────────────────────
        IJ.log("--- Computing Line Distances ---");
        computeDistances(directory, linesDir, selectedSets);

        IJ.log("");
        IJ.log("╔══════════════════════════════════════════════════════════╗");
        IJ.log("║         LINE DISTANCE ANALYSIS COMPLETE                ║");
        IJ.log("╚══════════════════════════════════════════════════════════╝");
    }

    private void executeCommandMode(String directory) {
        File linesDir = lineSetWriteDir(directory);
        List<String> selectedSets = commandLineSets == null || commandLineSets.isEmpty()
                ? lineSetNames(directory)
                : new ArrayList<String>(commandLineSets);
        if (selectedSets.isEmpty()) {
            String message = "Line Distance Analysis command mode cannot draw new lines; "
                    + "no existing line-set zips were found to recompute.";
            IJ.log("[LineDistanceAnalysis] " + message);
            recordWarn(message);
            return;
        }
        IJ.log("--- Computing Line Distances from existing line sets ---");
        computeDistances(directory, linesDir, selectedSets);
    }

    // ================================================================
    //  Step 1 helpers
    // ================================================================

    private static LineVocab loadVocabSafely() {
        try {
            return LineVocab.loadBundled();
        } catch (Exception e) {
            IJ.log("  Warning: could not load line vocabulary (" + e.getMessage() + "); "
                    + "falling back to custom-only entry.");
            List<LineVocab.Entry> fallback = new ArrayList<LineVocab.Entry>();
            fallback.add(new LineVocab.Entry(LineVocab.CUSTOM_LABEL, new ArrayList<String>()));
            return new LineVocab(0, fallback);
        }
    }

    /**
     * Resolves the user's chosen name from the inline landmark dropdown and
     * the adjacent custom-name text field. When the dropdown selection is
     * the {@code Custom} sentinel, the text field's value wins; otherwise
     * the dropdown label is used directly.
     */
    static String resolveTargetName(JComboBox<String> combo, JTextField field) {
        String selected = combo == null ? "" : (String) combo.getSelectedItem();
        if (selected == null) selected = "";
        if (LineVocab.CUSTOM_LABEL.equalsIgnoreCase(selected.trim())) {
            String text = field == null ? "" : field.getText();
            return text == null ? "" : text.trim();
        }
        return selected.trim();
    }

    private static boolean containsIgnoreCase(List<String> names, String candidate) {
        if (candidate == null) return false;
        for (String name : names) {
            if (name != null && name.equalsIgnoreCase(candidate)) return true;
        }
        return false;
    }

    private static BinConfig readBinConfigForLineDrawing(String directory) {
        return BinConfigIO.readPartialFromDirectory(directory);
    }

    static File lineDistanceOutputDir(String directory) {
        return FlashProjectLayout.forDirectory(directory).tablesLineDistanceWriteDir();
    }

    public static File lineSetWriteDir(String directory) {
        return new File(
                FlashProjectLayout.forDirectory(directory).tablesLineDistanceWriteDir(),
                "Line Sets");
    }

    static List<String> lineSetNames(String directory) {
        return lineSetNames(Collections.singletonList(lineSetWriteDir(directory)));
    }

    static List<String> lineSetNames(List<File> lineSetDirs) {
        LinkedHashMap<String, String> namesByKey = new LinkedHashMap<String, String>();
        if (lineSetDirs == null) return new ArrayList<String>();
        for (File dir : lineSetDirs) {
            addLineSetNames(namesByKey, dir);
        }
        return new ArrayList<String>(namesByKey.values());
    }

    private static void addLineSetNames(LinkedHashMap<String, String> namesByKey, File linesDir) {
        if (linesDir == null || !linesDir.isDirectory()) return;
        File[] zipFiles = linesDir.listFiles(new java.io.FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name != null && name.toLowerCase(Locale.ROOT).endsWith(".zip");
            }
        });
        if (zipFiles == null) return;

        Arrays.sort(zipFiles);
        for (File f : zipFiles) {
            String base = lineSetNameFromZip(f.getName());
            String key = base.toLowerCase(Locale.ROOT);
            if (!namesByKey.containsKey(key)) {
                namesByKey.put(key, base);
            }
        }
    }

    private static String lineSetNameFromZip(String fileName) {
        String base = fileName == null ? "" : fileName;
        if (base.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            base = base.substring(0, base.length() - 4);
        }
        return base;
    }

    /**
     * Opens each image, lets the user draw one line per image, saves
     * all lines to a single zip in FLASH/Results/Tables/Line Distance/Line Sets/.
     * Public so it can be called from DrawAndSaveROIsAnalysis.
     *
     * @return true if at least one line was drawn and saved
     */
    public boolean drawLineSet(String directory, File linesDir, String lineName) {
        return drawLineSet(directory, linesDir, lineName, null, false);
    }

    private boolean drawLineSet(String directory,
                                File linesDir,
                                String lineName,
                                BinConfig configuredBinConfig,
                                boolean drawOnSubset) {
        if (directory == null || directory.trim().isEmpty()) {
            String message = "Cannot draw line set: directory is empty.";
            IJ.log("[FLASH] " + message);
            recordWarn(message);
            return false;
        }
        String safeLineName = lineName == null ? "" : lineName.trim();
        if (safeLineName.isEmpty()) {
            String message = "Cannot draw line set: line set name is empty.";
            IJ.log("[FLASH] " + message);
            recordWarn(message);
            return false;
        }
        File targetLinesDir = linesDir == null ? lineSetWriteDir(directory) : linesDir;

        if (!FeatureDependencyGate.gate(DependencyId.BIO_FORMATS_RUNTIME,
                "Line Distance Analysis", "Bio-Formats image loading for drawing a new line set")) {
            recordWarn("Line Distance Analysis line drawing blocked by missing Bio-Formats runtime dependency.");
            return false;
        }

        // Ensure directory exists
        try {
            IoUtils.mustMkdirs(targetLinesDir);
        } catch (IOException e) {
            String message = "[FLASH] Could not create line set directory: " + e.getMessage();
            IJ.log(message);
            recordError(message, e);
            return false;
        }

        DeferredImageSupplier supplier;
        int totalImages;
        try {
            supplier = ImageSourceDispatcher.createSupplier(directory);
            totalImages = supplier.getTotalSeries();
        } catch (Exception e) {
            recordError("Line Distance Analysis could not create image supplier", e);
            IJ.showMessage("Line Distance Analysis", e.getMessage());
            return false;
        }

        if (totalImages == 0) {
            String message = "No images found for line drawing.";
            IJ.log("  " + message);
            recordWarn(message);
            return false;
        }

        BinConfig binConfig = configuredBinConfig == null
                ? readBinConfigForLineDrawing(directory)
                : configuredBinConfig;

        RoiManager rm = new RoiManager(false);

        int drawn = 0;
        for (int i = 0; i < totalImages; i++) {
            IJ.log("Loading image " + (i + 1) + "/" + totalImages + "...");
            ImagePlus imp;
            AnalysisRunContext.InputHandle input = recordInputStart(supplier.getContainerFileForSeries(i), i + 1);
            long started = System.currentTimeMillis();
            try {
                imp = supplier.openSeries(i);
                recordInputEnd(input, imp == null ? "skipped" : "processed", started);
            } catch (Exception e) {
                recordInputEnd(input, "failed", started);
                String message = "ERROR: Failed to open image " + (i + 1) + ": " + e.getMessage();
                IJ.log(message);
                recordError(message, e);
                continue;
            }
            if (imp == null) continue;

            if (drawOnSubset && binConfig != null) {
                ImagePlus subset = ZSliceOps.applyConfiguredRange(imp, binConfig, i, "Line drawing");
                if (subset != null && subset != imp) {
                    imp.changes = false;
                    imp.close();
                    imp = subset;
                }
            }

            String imgTitle = imp.getTitle();

            ResolvedImageMetadata metadata = ImageOrientationResolver.resolve(
                    directory, imgTitle, i + 1);
            NameParts parts = metadata.toNameParts();
            if (verboseLogging) {
                IJ.log("  Parsed: Animal=" + parts.animal
                        + ", Hemisphere=" + (parts.hemisphere.isEmpty() ? "N/A" : parts.hemisphere)
                        + ", Region=" + (parts.region.isEmpty() ? "N/A" : parts.region));
            }
            logOrientationResolution(metadata);
            OrientationOps.applyTransform(imp, metadata);

            // Max-project for display
            ImagePlus maxProj = ZProjector.run(imp, "max");
            maxProj.setTitle("Line_" + imgTitle);
            maxProj.show();

            // Set polyline tool (allows multi-segment lines; distance code already handles them)
            IJ.setTool("polyline");

            // Wait for user to draw line
            WaitForUserDialog wfud = new WaitForUserDialog(
                    "Draw Polyline",
                    "Image " + (i + 1) + "/" + totalImages + "\n"
                    + "Draw a polyline on the image for '" + safeLineName + "'.\n"
                    + "(Click to add vertices, double-click to finish)\n"
                    + "Image: " + imgTitle + "\n\n"
                    + "Click OK when done.");
            wfud.show();

            Roi roi = maxProj.getRoi();
            if (roi == null) {
                String message = "No line drawn for " + imgTitle + "; skipping.";
                IJ.log("  Warning: " + message);
                recordWarn(message);
            } else {
                rm.addRoi(roi);
                drawn++;
                if (verboseLogging) {
                    IJ.log("    Line drawn for SCN " + (i + 1) + " (" + imgTitle + ")");
                }
            }

            // Cleanup
            maxProj.changes = false;
            maxProj.close();
            imp.changes = false;
            imp.close();
        }

        if (drawn == 0) {
            String message = "No lines were drawn.";
            IJ.log("  " + message);
            recordWarn(message);
            rm.close();
            return false;
        }

        // Save ROI zip
        File zipFile = new File(targetLinesDir, safeLineName + ".zip");
        rm.runCommand("Save", zipFile.getAbsolutePath());
        recordOutput(zipFile, "zip");
        rm.close();

        IJ.log("  Saved " + drawn + " line(s) to: " + zipFile.getName());
        return true;
    }

    private static void logOrientationResolution(ResolvedImageMetadata metadata) {
        IJ.log("  Orientation source: " + metadata.sourceLabel());
        IJ.log(metadata.hasTransform()
                ? "  Orientation transform applied."
                : "  Orientation transform skipped.");
    }

    // ================================================================
    //  Step 2: Distance computation
    // ================================================================

    /**
     * Computes distances from all objects to the specified line sets.
     * Public so it can be called from ThreeDObjectAnalysis after object counting.
     */
    public void computeDistances(String directory, File linesDir,
                                 List<String> selectedSets) {
        List<String> safeSelectedSets = normaliseLineSetSelection(selectedSets);
        if (safeSelectedSets.isEmpty()) {
            String message = "No line sets selected.";
            IJ.log("  " + message);
            recordWarn(message);
            return;
        }

        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
        File objectsDir = layout.tablesObjectsWriteDir();
        if (!objectsDir.isDirectory()) {
            String message = "Objects directory not found: " + objectsDir.getAbsolutePath();
            IJ.log("  " + message);
            recordWarn(message);
            return;
        }

        // Read pixel calibration from file written by 3D Object Analysis
        CalibrationIO.PixelCalibration cal = CalibrationIO.read(objectsDir);
        double pixelSize;
        if (cal != null && cal.isCalibrated()) {
            pixelSize = cal.pixelWidth;
            IJ.log("  Using calibration: " + pixelSize + " " + cal.unit + "/pixel");
        } else {
            pixelSize = 1.0;
            recordWarn("No calibration found; line distances will be in pixels.");
            IJ.log("  Warning: no calibration found — distances will be in pixels.");
        }

        // Load object CSVs
        File[] csvFiles = objectsDir.listFiles(new java.io.FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                if (!name.toLowerCase(Locale.ROOT).endsWith(".csv")) return false;
                if (name.startsWith("temp_")) return false;
                if (name.contains("Analysis Details")) return false;
                if (name.equals("Spatial_Distances.csv")) return false;
                return true;
            }
        });

        if (csvFiles == null || csvFiles.length == 0) {
            String message = "No object CSV files found in: " + objectsDir.getAbsolutePath();
            IJ.log("  " + message);
            recordWarn(message);
            return;
        }

        Arrays.sort(csvFiles);

        // Load all channels
        LinkedHashMap<String, ChannelData> channels =
                new LinkedHashMap<String, ChannelData>();
        for (File csvFile : csvFiles) {
            String safeChannelName = csvFile.getName();
            if (safeChannelName.toLowerCase(Locale.ROOT).endsWith(".csv")) {
                safeChannelName = safeChannelName.substring(0, safeChannelName.length() - 4);
            }
            String channelName = ChannelFilenameCodec.toRaw(safeChannelName);
            ChannelData cd = loadChannelCsvRecorded(csvFile, channelName);
            if (cd != null) {
                channels.put(channelName, cd);
                IJ.log("  Loaded channel: " + channelName
                        + " (" + cd.rows.size() + " objects)");
            }
        }

        if (channels.isEmpty()) {
            String message = "No valid channel data loaded.";
            IJ.log("  " + message);
            recordWarn(message);
            return;
        }

        File outputDir = lineDistanceOutputDir(directory);
        try {
            IoUtils.mustMkdirs(outputDir);
        } catch (IOException e) {
            String message = "[FLASH] Could not create line distance output directory: " + e.getMessage();
            IJ.log(message);
            recordError(message, e);
            return;
        }

        // Process each selected line set
        for (String lineName : safeSelectedSets) {
            File zipFile = resolveLineSetZip(directory, linesDir, lineName);
            if (!zipFile.exists()) {
                String message = "Line set zip not found: " + lineName + ".zip";
                IJ.log("  " + message);
                recordWarn(message);
                continue;
            }

            IJ.log("  Processing line set: " + lineName);

            // Load line ROIs from zip
            // Each ROI index = SCN number (index 0 -> SCN "1", etc.)
            AnalysisRunContext.InputHandle lineInput = recordInputStart(zipFile, 0);
            long lineStarted = System.currentTimeMillis();
            List<Roi> loadedRois = RoiIO.loadRoisFromZip(zipFile);
            recordInputEnd(lineInput, loadedRois == null || loadedRois.isEmpty()
                    ? "skipped" : "processed", lineStarted);
            Roi[] lineRois = loadedRois.toArray(new Roi[loadedRois.size()]);

            if (lineRois == null || lineRois.length == 0) {
                String message = "No ROIs found in: " + zipFile.getName();
                IJ.log("    " + message);
                recordWarn(message);
                continue;
            }

            IJ.log("    Loaded " + lineRois.length + " line ROI(s).");

            // For each channel, compute distance to this line set
            for (Map.Entry<String, ChannelData> entry : channels.entrySet()) {
                String channelName = entry.getKey();
                ChannelData cd = entry.getValue();

                String distCol = channelName + "_DistTo_" + lineName;
                cd.addColumn(distCol);

                if (!cd.colIdx.containsKey("XM")
                        || !cd.colIdx.containsKey("YM")) {
                    String message = "Channel " + channelName
                            + " missing Region/XM/YM columns, skipping.";
                    IJ.log("    " + message);
                    recordWarn(message);
                    continue;
                }

                int computed = 0;
                int skipped = 0;
                for (int r = 0; r < cd.rows.size(); r++) {
                    int sectionIndex = resolveSectionIndex(cd, r);
                    if (sectionIndex < 1) {
                        cd.set(r, distCol, "Inf");
                        skipped++;
                        continue;
                    }

                    int roiIdx = sectionIndex - 1;
                    if (roiIdx < 0 || roiIdx >= lineRois.length) {
                        cd.set(r, distCol, "Inf");
                        skipped++;
                        continue;
                    }

                    Roi lineRoi = lineRois[roiIdx];
                    if (lineRoi == null) {
                        cd.set(r, distCol, "Inf");
                        skipped++;
                        continue;
                    }
                    java.awt.Polygon polygon = lineRoi.getPolygon();
                    if (polygon == null || polygon.npoints < 2) {
                        cd.set(r, distCol, "Inf");
                        skipped++;
                        continue;
                    }

                    // Object coords (pixel units from CSV)
                    double objXPx = cd.getDouble(r, "XM");
                    double objYPx = cd.getDouble(r, "YM");
                    if (!Double.isFinite(objXPx) || !Double.isFinite(objYPx)) {
                        cd.set(r, distCol, "Inf");
                        skipped++;
                        continue;
                    }

                    // Compute min perpendicular distance to line segments
                    double minDistPx = minDistToPolyline(
                            objXPx, objYPx,
                            polygon.xpoints, polygon.ypoints,
                            polygon.npoints);

                    double distMicrons = minDistPx * pixelSize;
                    cd.set(r, distCol, CsvTableIO.formatDist(distMicrons));
                    computed++;
                }

                IJ.log("    " + channelName + ": distance to '"
                        + lineName + "' computed for " + computed
                        + " objects" + (skipped > 0
                                ? " (" + skipped + " skipped)" : ""));
            }
        }

        // Write updated CSVs back
        IJ.log("--- Writing updated CSVs ---");
        List<String> channelNames = new ArrayList<String>(channels.keySet());
        for (Map.Entry<String, ChannelData> entry : channels.entrySet()) {
            String channelName = entry.getKey();
            ChannelData cd = entry.getValue();
            ObjectCsvColumnOrder.reorder(cd, channelNames);
            File outFile = new File(outputDir, ChannelFilenameCodec.toSafe(channelName) + ".csv");
            CsvTableIO.writeChannelCsv(outFile, cd, currentRunId());
            recordOutput(outFile, "csv");
            IJ.log("  Updated: " + outFile.getName());
        }
    }

    private ChannelData loadChannelCsvRecorded(File csvFile, String channelName) {
        AnalysisRunContext.InputHandle input = recordInputStart(csvFile, 0);
        long started = System.currentTimeMillis();
        try {
            ChannelData data = CsvTableIO.loadChannelCsv(csvFile, channelName);
            recordInputEnd(input, data == null ? "skipped" : "processed", started);
            return data;
        } catch (RuntimeException e) {
            recordInputEnd(input, "failed", started);
            recordError("Failed to load object CSV "
                    + (csvFile == null ? "" : csvFile.getAbsolutePath()), e);
            throw e;
        }
    }

    private static List<String> normaliseLineSetSelection(List<String> selectedSets) {
        List<String> safe = new ArrayList<String>();
        if (selectedSets == null) return safe;
        for (String selectedSet : selectedSets) {
            String trimmed = selectedSet == null ? "" : selectedSet.trim();
            if (!trimmed.isEmpty() && !containsIgnoreCase(safe, trimmed)) {
                safe.add(trimmed);
            }
        }
        return safe;
    }

    private static File resolveLineSetZip(String directory, File preferredDir, String lineName) {
        String fileName = lineName + ".zip";
        for (File dir : lineSetSearchDirs(directory, preferredDir)) {
            File candidate = new File(dir, fileName);
            if (candidate.isFile()) {
                return candidate;
            }
        }
        return new File(preferredDir == null ? lineSetWriteDir(directory) : preferredDir, fileName);
    }

    private static List<File> lineSetSearchDirs(String directory, File preferredDir) {
        List<File> dirs = new ArrayList<File>();
        addDirectoryIfDistinct(dirs, lineSetWriteDir(directory));
        addDirectoryIfDistinct(dirs, preferredDir);
        return dirs;
    }

    private static void addDirectoryIfDistinct(List<File> dirs, File candidate) {
        if (candidate == null) return;
        String path = candidate.getAbsolutePath();
        for (File dir : dirs) {
            if (dir.getAbsolutePath().equals(path)) return;
        }
        dirs.add(candidate);
    }

    private int resolveSectionIndex(ChannelData cd, int row) {
        int fromRegion = parseTrailingInteger(safeValue(cd, row, "Region"));
        if (fromRegion > 0) return fromRegion;

        int fromScn = parseTrailingInteger(safeValue(cd, row, "SCN"));
        if (fromScn > 0) return fromScn;

        return parseTrailingInteger(safeValue(cd, row, "ROI"));
    }

    private String safeValue(ChannelData cd, int row, String column) {
        if (!cd.colIdx.containsKey(column)) return "";
        String value = cd.get(row, column);
        return value == null ? "" : value.trim();
    }

    private int parseTrailingInteger(String value) {
        if (value == null) return -1;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return -1;

        int end = trimmed.length() - 1;
        while (end >= 0 && Character.isDigit(trimmed.charAt(end))) {
            end--;
        }
        if (end == trimmed.length() - 1) return -1;

        try {
            return Integer.parseInt(trimmed.substring(end + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ================================================================
    //  Geometry
    // ================================================================

    /**
     * Computes the minimum perpendicular distance from a point to a
     * polyline defined by arrays of x/y coordinates.
     *
     * <p>Projects the point onto each line segment, clamps the
     * projection parameter t to [0,1], and returns the minimum
     * distance across all segments.
     */
    private static double minDistToPolyline(double px, double py,
                                            int[] xpoints, int[] ypoints,
                                            int npoints) {
        double minDist = Double.MAX_VALUE;

        for (int i = 0; i < npoints - 1; i++) {
            double x1 = xpoints[i];
            double y1 = ypoints[i];
            double x2 = xpoints[i + 1];
            double y2 = ypoints[i + 1];

            double segDx = x2 - x1;
            double segDy = y2 - y1;
            double segLenSq = segDx * segDx + segDy * segDy;

            double dist;
            if (segLenSq == 0) {
                double dx = px - x1;
                double dy = py - y1;
                dist = Math.sqrt(dx * dx + dy * dy);
            } else {
                double t = ((px - x1) * segDx + (py - y1) * segDy) / segLenSq;
                if (t < 0) t = 0;
                if (t > 1) t = 1;

                double projX = x1 + t * segDx;
                double projY = y1 + t * segDy;

                double dx = px - projX;
                double dy = py - projY;
                dist = Math.sqrt(dx * dx + dy * dy);
            }

            if (dist < minDist) {
                minDist = dist;
            }
        }

        return minDist;
    }
}
