package flash.pipeline.analyses;

import ij.IJ;

import flash.pipeline.analyses.wizard.AggregationConfig;
import flash.pipeline.analyses.wizard.AggregationPreset;
import flash.pipeline.analyses.wizard.AggregationPresetIO;
import flash.pipeline.analyses.wizard.SpatialPreset;
import flash.pipeline.analyses.wizard.SpatialPresetIO;
import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.BinConfigIO;
import flash.pipeline.cli.CLIConfig;
import flash.pipeline.intelligence.AnalysisStatusScanner;
import flash.pipeline.intensity.spatial.IntensitySpatialOutputMode;
import flash.pipeline.io.CalibrationIO;
import flash.pipeline.io.ConditionManifestIO;
import flash.pipeline.io.CsvSupport;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.io.ImageSourceDispatcher;
import flash.pipeline.io.IoUtils;
import flash.pipeline.io.SeriesMeta;
import flash.pipeline.naming.ChannelFilenameCodec;
import flash.pipeline.results.StartHereWriter;
import flash.pipeline.roi.RoiIO;
import flash.pipeline.runtime.PluginInstallGuard;
import flash.pipeline.ui.PipelineDialog;
import flash.pipeline.ui.wizard.ConditionManifestPanel;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;

import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Aggregates per-object and per-intensity CSV data across all channels into
 * master summary files saved to
 * {@code FLASH/Results/Tables/Project Summary/}:
 * <ul>
 *   <li>{@code 3D Objects.csv} - object and spatial per-animal totals/means</li>
 *   <li>{@code Image Intensities.csv} - intensity per-animal means</li>
 * </ul>
 */
public class MasterAggregationAnalysis implements Analysis {

    private boolean headless = false;
    private boolean suppressDialogs = false;
    private static final double COLOC_THRESHOLD = 30.0;
    private static final int MAX_TEXTURE_CLASS_FRACTION_COLUMNS = 16;
    private static final String MASTER_INTENSITIES_MIP_FILENAME = "Image Intensities_MIP.csv";
    private static final String MASTER_INTENSITIES_3D_FILENAME = "Image Intensities_3D.csv";

    /**
     * Channel-agnostic Morph_* columns emitted by SpatialAnalysis. These names
     * carry NO channel identity (every channel's CSV writes "Morph_Sphericity",
     * not "Morph_GFAP_Sphericity"), so the master aggregator must add a channel
     * prefix when writing them to the project-level CSV.
     *
     * <p>If you add a new channel-agnostic Morph_ column, list it here AND add a
     * regression test (see MasterAggregationAnalysisMorphPrefixTest). Columns
     * whose names already encode channel identity (e.g. a hypothetical
     * "Morph_PairwiseSpread_GFAP_Iba1") MUST NOT be added here, otherwise the
     * channel namespace would be doubled to
     * "<channel>_Morph_PairwiseSpread_GFAP_Iba1".
     */
    static final Set<String> CHANNEL_AGNOSTIC_MORPH_COLS;
    static {
        Set<String> cols = new HashSet<String>();
        // Tier 1 — 2D morphology (SpatialAnalysis lines 2593-2600)
        cols.add("Morph_Area_um2");
        cols.add("Morph_Perimeter_um");
        cols.add("Morph_Circularity");
        cols.add("Morph_Solidity");
        cols.add("Morph_AspectRatio");
        cols.add("Morph_Feret_um");
        cols.add("Morph_Extent");
        cols.add("Morph_ConvexHullArea_um2");
        // Tier 2 — 3D shape features (SpatialAnalysis lines 2741-2752)
        cols.add("Morph_Sphericity");
        cols.add("Morph_Compactness");
        cols.add("Morph_Elongation");
        cols.add("Morph_Flatness");
        cols.add("Morph_Spareness");
        cols.add("Morph_MajorRadius_um");
        cols.add("Morph_Feret3D_um");
        for (int m = 1; m <= 5; m++) cols.add("Morph_Moment" + m);
        cols.add("Morph_DistCenter_Min_um");
        cols.add("Morph_DistCenter_Max_um");
        cols.add("Morph_DistCenter_Mean_um");
        cols.add("Morph_DistCenter_SD_um");
        // Tier 2b — radial/protrusion descriptors (SpatialAnalysis lines 2755-2759)
        cols.add("Morph_RI");
        cols.add("Morph_SRI");
        cols.add("Morph_PB");
        cols.add("Morph_MP");
        cols.add("Morph_VSD");
        // Tier 3 — composite indices (SpatialAnalysis lines 3029-3031)
        cols.add("Morph_CMS");
        cols.add("Morph_SMSD");
        cols.add("Morph_IMDI");
        // Tier 4 — texture/distance ratio (SpatialAnalysis line 3113)
        cols.add("Morph_TDR");
        // Tier 5 — spatial-morphometric (SpatialAnalysis line 3136)
        cols.add("Morph_FEV_Mag");
        CHANNEL_AGNOSTIC_MORPH_COLS = java.util.Collections.unmodifiableSet(cols);
    }

    /**
     * Should this header receive a per-channel prefix when written to the
     * master CSV? True only for column names known to be channel-agnostic.
     * Package-private for unit testing.
     */
    static boolean needsChannelPrefix(String header) {
        return CHANNEL_AGNOSTIC_MORPH_COLS.contains(header)
                || header.startsWith("MorphTexture_")
                || header.startsWith("Voronoi_")
                || "Cluster".equals(header);
    }

    /** Pixel-to-physical-unit conversion, read from calibration file at runtime. */
    private double pixelSize = 1.0;
    private double calibrationPixelWidth = 1.0;
    private double calibrationPixelHeight = 1.0;
    private double calibrationPixelDepth = 1.0;
    /** Full-stack depth persisted in calibration.properties for fallback volume derivation. */
    private double fallbackStackDepth = Double.NaN;
    private boolean calibrationLoaded = false;
    private String calibrationUnit = "pixel";

    /** Tracks which volume sources were actually used for the analysis details report. */
    private final Set<String> volumeSourcesUsed = new LinkedHashSet<String>();
    private boolean legacyRoiCsvUpgraded = false;
    private boolean roiPropertiesRegenerated = false;
    private boolean skippedUnsafeVolumeRows = false;

    /** User-chosen config for grouping granularity and output columns. */
    private AggregationConfig aggregationConfig = new AggregationConfig();
    /** True when aggregationConfig was populated from CLI (skip interactive dialog). */
    private boolean configFromCli = false;
    /** Advanced export toggle: emit one texture-class fraction column per observed class. */
    private boolean textureClassFractions = false;

    /** Composite group key -> parent animal name. Rebuilt per execute(). */
    private final LinkedHashMap<String, String> groupKeyToAnimal =
            new LinkedHashMap<String, String>();

    @Override
    public void setHeadless(boolean headless) {
        this.headless = headless;
    }

    @Override
    public void setSuppressDialogs(boolean suppress) {
        this.suppressDialogs = suppress;
    }

    public AggregationConfig getAggregationConfig() {
        return aggregationConfig;
    }

    public void setAggregationConfig(AggregationConfig config) {
        this.aggregationConfig = config == null ? new AggregationConfig() : config;
        this.configFromCli = false;
    }

    @Override
    public void setCliConfig(CLIConfig config) {
        if (config == null) {
            return;
        }
        this.textureClassFractions = false;
        applyCliSpatialAggregationOptions(config);
        if (config.getAggregate() == null) {
            return;
        }
        CLIConfig.AggregateConfig src = config.getAggregate();
        AggregationConfig target = new AggregationConfig();
        if (src.getPresetName() != null && !src.getPresetName().trim().isEmpty()) {
            try {
                File projectRoot = config.getDirectory() == null
                        ? new File(System.getProperty("user.dir", "."))
                        : new File(config.getDirectory());
                AggregationPreset preset = new AggregationPresetIO(projectRoot)
                        .load(src.getPresetName().trim());
                target.applyPreset(preset);
            } catch (IOException e) {
                IJ.log("[CLI] Warning: Could not load aggregate.preset '"
                        + src.getPresetName() + "': " + e.getMessage());
            }
        }
        if (src.getGranularity() != null) {
            target.setGranularity(src.getGranularity());
        }
        if (src.getOutputMode() != null) {
            target.setOutputMode(src.getOutputMode());
        }
        this.aggregationConfig = target;
        this.configFromCli = true;
    }

    private void applyCliSpatialAggregationOptions(CLIConfig config) {
        if (config == null || config.getSpatial() == null) {
            return;
        }
        CLIConfig.SpatialConfig spatial = config.getSpatial();
        Boolean classFractions = null;
        if (spatial.getPresetName() != null && !spatial.getPresetName().trim().isEmpty()) {
            try {
                File projectRoot = config.getDirectory() == null
                        ? new File(System.getProperty("user.dir", "."))
                        : new File(config.getDirectory());
                SpatialPreset preset = new SpatialPresetIO(projectRoot)
                        .load(spatial.getPresetName().trim());
                classFractions = Boolean.valueOf(preset.isDoObjectTextureClassFractions());
            } catch (IOException e) {
                IJ.log("[CLI] Warning: Could not load spatial.preset '"
                        + spatial.getPresetName() + "' for aggregation options: "
                        + e.getMessage());
            }
        }
        if (spatial.getTextureClassFractions() != null) {
            classFractions = spatial.getTextureClassFractions();
        }
        if (classFractions != null) {
            this.textureClassFractions = classFractions.booleanValue();
        }
    }

    /**
     * True iff the project directory looks like it already has calibrated
     * ROI volume data (so the per-mm^3 output toggle is meaningful).
     */
    static boolean detectHasVolumeData(String directory, Set<String> animals) {
        if (directory == null) return false;
        if (RoiIO.listRoiPropertiesCsvFiles(new File(directory)).isEmpty()) return false;
        // Presence of the CSV (whose volume column is the trigger) is enough
        // for the dialog — fine-grained gating happens inside the aggregation loop.
        return true;
    }

    /**
     * Main-panel configuration dialog. Shows:
     * <ul>
     *   <li>A preset dropdown populated from {@code Aggregation Presets/}.</li>
     *   <li>The shared condition-assignment review table.</li>
     *   <li>Grouping granularity dropdown.</li>
     *   <li>Output radio group with a per-mm^3 availability explanation.</li>
     * </ul>
     * Returns the chosen config on OK, or {@code null} on Cancel.
     */
    public AggregationConfig showConfigDialog(String directory,
                                              File projectRoot,
                                              Set<String> animals,
                                              boolean hasVolumeData,
                                              AggregationConfig current) {
        final AggregationConfig config = current == null ? new AggregationConfig() : current;
        final AggregationPresetIO presetIO = new AggregationPresetIO(
                projectRoot == null ? new File(".") : projectRoot);
        Set<String> safeAnimals = animals == null ? new LinkedHashSet<String>() : animals;

        PipelineDialog dialog = new PipelineDialog("Master Data Aggregation", PipelineDialog.Phase.EXPORT);

        JPanel main = new JPanel(new GridBagLayout());
        main.setBorder(BorderFactory.createEmptyBorder(12, 16, 8, 16));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 4, 2, 4);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;

        row = addSectionHeader(main, gbc, row, "Preset");
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 1;
        main.add(new JLabel("Preset:"), gbc);
        final JComboBox<String> presetCombo = new JComboBox<String>();
        populatePresetChoice(presetCombo, presetIO);
        gbc.gridx = 1; gbc.gridwidth = 2;
        main.add(presetCombo, gbc);
        row++;

        row = addSectionHeader(main, gbc, row, "Conditions");
        Map<String, String> prefill = ConditionManifestIO.resolveAssignments(directory, safeAnimals);
        final ConditionManifestPanel conditionPanel =
                new ConditionManifestPanel(safeAnimals, prefill);
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 3;
        main.add(conditionPanel.getComponent(), gbc);
        row++;

        row = addSectionHeader(main, gbc, row, "Grouping Granularity");
        final String[] granularityLabels = {
                "Per-animal average (default)",
                "Per-animal per-hemisphere",
                "Per-animal per-region",
                "Per-section raw"
        };
        final AggregationConfig.Granularity[] granularityValues = {
                AggregationConfig.Granularity.ANIMAL,
                AggregationConfig.Granularity.HEMISPHERE,
                AggregationConfig.Granularity.REGION,
                AggregationConfig.Granularity.SECTION
        };
        final JComboBox<String> granularityCombo = new JComboBox<String>(granularityLabels);
        granularityCombo.setSelectedIndex(indexOfEnum(granularityValues, config.getGranularity()));
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 1;
        main.add(new JLabel("Granularity:"), gbc);
        gbc.gridx = 1; gbc.gridwidth = 2;
        main.add(granularityCombo, gbc);
        row++;

        row = addSectionHeader(main, gbc, row, "Output");
        final JRadioButton bothRadio = new JRadioButton("Raw + per-mm^3 (default)");
        final JRadioButton rawRadio = new JRadioButton("Raw only");
        final JRadioButton permm3Radio = new JRadioButton("Per-mm^3 only");
        ButtonGroup outputGroup = new ButtonGroup();
        outputGroup.add(bothRadio);
        outputGroup.add(rawRadio);
        outputGroup.add(permm3Radio);

        switch (config.getOutputMode()) {
            case RAW_ONLY: rawRadio.setSelected(true); break;
            case PERMM3_ONLY: permm3Radio.setSelected(true); break;
            case RAW_AND_PERMM3:
            default: bothRadio.setSelected(true); break;
        }

        if (!hasVolumeData) {
            bothRadio.setEnabled(false);
            permm3Radio.setEnabled(false);
            rawRadio.setSelected(true);
        }

        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 3;
        main.add(bothRadio, gbc);
        row++;
        gbc.gridy = row;
        main.add(rawRadio, gbc);
        row++;
        gbc.gridy = row;
        main.add(permm3Radio, gbc);
        row++;

        if (!hasVolumeData) {
            JLabel explanation = new JLabel(
                    "<html><body style='width:420px;color:#7a4a00'>"
                    + "<i>Per-mm^3 requires ROI volume data &mdash; re-run Draw &amp; Save ROIs"
                    + " or 3D Object Analysis first.</i></body></html>");
            gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 3;
            main.add(explanation, gbc);
            row++;
        }

        // Preset change -> apply to controls
        presetCombo.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                Object selected = presetCombo.getSelectedItem();
                if (selected == null) return;
                String name = selected.toString();
                if (name.isEmpty() || "(none)".equals(name)) return;
                try {
                    AggregationPreset preset = presetIO.load(name);
                    granularityCombo.setSelectedIndex(indexOfEnum(granularityValues, preset.getGranularity()));
                    switch (preset.getOutputMode()) {
                        case RAW_ONLY: rawRadio.setSelected(true); break;
                        case PERMM3_ONLY:
                            if (permm3Radio.isEnabled()) {
                                permm3Radio.setSelected(true);
                            } else {
                                rawRadio.setSelected(true);
                            }
                            break;
                        case RAW_AND_PERMM3:
                        default:
                            if (bothRadio.isEnabled()) {
                                bothRadio.setSelected(true);
                            } else {
                                rawRadio.setSelected(true);
                            }
                            break;
                    }
                } catch (IOException ex) {
                    IJ.log("Could not load preset '" + name + "': " + ex.getMessage());
                }
            }
        });

        dialog.addComponent(main);
        if (!dialog.showDialog()) return null;

        // Persist condition assignments
        Map<String, String> assignments = conditionPanel.collectAssignments();
        try {
            ConditionManifestIO.saveAssignments(directory, assignments);
        } catch (Exception e) {
            IJ.log("Warning: could not save condition assignments: " + e.getMessage());
        }

        AggregationConfig result = new AggregationConfig();
        result.setGranularity(granularityValues[granularityCombo.getSelectedIndex()]);
        if (rawRadio.isSelected()) {
            result.setOutputMode(AggregationConfig.OutputMode.RAW_ONLY);
        } else if (permm3Radio.isSelected()) {
            result.setOutputMode(AggregationConfig.OutputMode.PERMM3_ONLY);
        } else {
            result.setOutputMode(AggregationConfig.OutputMode.RAW_AND_PERMM3);
        }
        Object selectedPreset = presetCombo.getSelectedItem();
        if (selectedPreset != null && !selectedPreset.toString().isEmpty()
                && !"(none)".equals(selectedPreset.toString())) {
            result.setPresetName(selectedPreset.toString());
        }
        return result;
    }

    private static int indexOfEnum(AggregationConfig.Granularity[] values,
                                   AggregationConfig.Granularity target) {
        if (target == null) return 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == target) return i;
        }
        return 0;
    }

    private static int addSectionHeader(JPanel panel, GridBagConstraints gbc, int row, String text) {
        JLabel header = new JLabel(text);
        header.setFont(header.getFont().deriveFont(Font.BOLD, 13f));
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 3;
        gbc.insets = new Insets(10, 4, 4, 4);
        panel.add(header, gbc);
        gbc.insets = new Insets(2, 4, 2, 4);
        return row + 1;
    }

    private static void populatePresetChoice(JComboBox<String> combo, AggregationPresetIO presetIO) {
        combo.removeAllItems();
        combo.addItem("(none)");
        try {
            for (AggregationPreset preset : presetIO.listAll()) {
                combo.addItem(preset.getName());
            }
        } catch (IOException e) {
            IJ.log("Could not list aggregation presets: " + e.getMessage());
        }
    }

    @Override
    public void execute(String directory) {
        IJ.log("=== Master Aggregation Analysis ===");
        resetVolumeTracking();
        groupKeyToAnimal.clear();

        File projectRoot = new File(directory);

        // Interactive configuration dialog when GUI is available.
        if (!headless && !suppressDialogs && !configFromCli) {
            Set<String> animalsForDialog = loadObjectAnimalNames(directory);
            boolean hasVolumeData = detectHasVolumeData(directory, animalsForDialog);
            AggregationConfig chosen = showConfigDialog(
                    directory, projectRoot, animalsForDialog, hasVolumeData, aggregationConfig);
            if (chosen == null) {
                IJ.log("Master Aggregation Analysis cancelled by user.");
                return;
            }
            aggregationConfig = chosen;
        }
        if (aggregationConfig == null) {
            aggregationConfig = new AggregationConfig();
        }
        IJ.log("Aggregation granularity: " + aggregationConfig.getGranularity().token()
                + "; output mode: " + aggregationConfig.getOutputMode().token()
                + (aggregationConfig.getPresetName() != null
                    ? " (preset: " + aggregationConfig.getPresetName() + ")" : ""));

        // Load pixel calibration from file written by 3D Object Analysis
        CalibrationIO.PixelCalibration cal = CalibrationIO.readFromDirectory(directory);
        if (cal != null && cal.isCalibrated()) {
            pixelSize = cal.pixelWidth;
            calibrationPixelWidth = cal.pixelWidth;
            calibrationPixelHeight = cal.pixelHeight;
            calibrationPixelDepth = cal.pixelDepth;
            fallbackStackDepth = cal.hasStackDepth() ? cal.stackDepth : Double.NaN;
            calibrationLoaded = true;
            calibrationUnit = cal.unit;
            IJ.log("Calibration: " + pixelSize + " " + cal.unit + "/pixel, pixel depth: "
                    + calibrationPixelDepth + " " + cal.unit
                    + (cal.hasStackDepth()
                    ? ", fallback stack depth: " + fallbackStackDepth + " " + cal.unit
                    : ", fallback stack depth unavailable"));
        } else {
            pixelSize = 1.0;
            calibrationPixelWidth = 1.0;
            calibrationPixelHeight = 1.0;
            calibrationPixelDepth = 1.0;
            fallbackStackDepth = Double.NaN;
            calibrationLoaded = false;
            calibrationUnit = "pixel";
            IJ.log("Warning: no calibration found — XM/YM will be in pixels, volume fallback disabled.");
        }

        // Create output directory
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
        File exportDir = layout.tablesProjectSummaryWriteDir();
        try {
            IoUtils.mustMkdirs(exportDir);
        } catch (IOException e) {
            IJ.log("[FLASH] Could not create project summary directory: " + e.getMessage());
            return;
        }

        // Load ROI volume for per-mm³ normalization
        Set<String> objectAnimals = loadObjectAnimalNames(directory);
        File roiPropertiesFile = chooseRoiPropertiesFile(directory, objectAnimals);

        Map<String, Double> volumeMm3PerAnimal = loadRoiVolumeMm3Mean(directory, roiPropertiesFile);
        Map<String, Integer> numSectionsPerAnimal = loadNumSections(directory, roiPropertiesFile);

        boolean didObjects = aggregateObjects(directory, exportDir, layout.analysisDetailsWriteDir(),
                volumeMm3PerAnimal, numSectionsPerAnimal);
        boolean didIntensities = aggregateIntensities(directory, exportDir, numSectionsPerAnimal);

        if (didObjects || didIntensities) {
            try {
                AnalysisStatusScanner.writeSidecar(directory,
                        AnalysisStatusScanner.AGGREGATION_ID,
                        AnalysisStatusScanner.estimateImageCount(directory));
            } catch (IOException e) {
                IJ.log("Warning: could not write aggregation status sidecar: " + e.getMessage());
            }
            try {
                StartHereWriter.write(layout);
            } catch (IOException e) {
                IJ.log("Warning: could not write START_HERE.html: " + e.getMessage());
            }
            if (!suppressDialogs) {
                IJ.showMessage("Master Aggregation Analysis",
                        "Aggregation complete.\n"
                        + (didObjects ? "Objects master saved.\n" : "")
                        + (didIntensities ? "Intensities master saved." : ""));
            }
        } else {
            IJ.log("Master Aggregation Analysis: No data directories found to aggregate.");
            if (!headless && !suppressDialogs) {
                IJ.showMessage("Master Aggregation Analysis",
                        "No data directories found to aggregate.");
            }
        }
    }

    // -------------------------------------------------- ROI Properties loading

    /**
     * Loads ROI volume data from ROI Properties tables for per-mm³ normalization.
     * Prefers "Volume (mm^3)" column directly; falls back to computing from
     * calibrated area × full stack depth when the volume column is absent.
     * Returns mean volume (mm³) per animal name.
     */
    private Map<String, Double> loadRoiVolumeMm3Mean(String directory, File selectedRoiFile) {
        Map<String, Double> result = new HashMap<String, Double>();
        if (selectedRoiFile == null) return result;
        File attribDir = selectedRoiFile.getParentFile();
        if (!attribDir.isDirectory()) {
            IJ.log("No ROI tables directory found — volume normalization will be skipped.");
            return result;
        }

        File[] csvFiles = attribDir.listFiles(new java.io.FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.toLowerCase(Locale.ROOT).endsWith(".csv")
                        && name.toLowerCase(Locale.ROOT).contains("roi properties");
            }
        });

        if (csvFiles == null || csvFiles.length == 0) {
            IJ.log("No ROI Properties CSV found in ROI tables — volume normalization will be skipped.");
            return result;
        }
        Arrays.sort(csvFiles, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return String.CASE_INSENSITIVE_ORDER.compare(a.getName(), b.getName());
            }
        });

        // Use the first matching file
        File roiFile = selectedRoiFile != null ? selectedRoiFile : csvFiles[0];
        IJ.log("Loading ROI Properties from: " + roiFile.getName());

        // animal -> list of volume (mm³) values
        Map<String, List<Double>> volAccum = new LinkedHashMap<String, List<Double>>();

        try {
            // Read all rows into memory so we can detect legacy format and upgrade
            CsvSupport.RecordReader csv = CsvSupport.openRecordReader(roiFile);
            String headerLine;
            List<String[]> allRows = new ArrayList<String[]>();
            try {
                CsvSupport.Record headerRecord = csv.readRecord();
                headerLine = headerRecord == null ? null : headerRecord.text;
                if (headerRecord != null) {
                    CsvSupport.Record record;
                    while ((record = csv.readRecord()) != null) {
                        if (CsvSupport.isBlankRecord(record.text)) continue;
                        allRows.add(CsvSupport.parseRecord(record.text));
                    }
                }
            } finally {
                csv.close();
            }

            if (headerLine == null || allRows.isEmpty()) {
                volAccum = null;
            } else {
                String[] header = CsvSupport.parseRecord(headerLine);
                Map<String, Integer> colIdx = new HashMap<String, Integer>();
                for (int i = 0; i < header.length; i++) {
                    colIdx.put(header[i].trim(), i);
                }

                Integer animalCol = colIdx.get("Animal Name");
                Integer volMm3Col = colIdx.get("Volume (mm^3)");
                Integer areaUmCol = colIdx.get("Area (um^2)");
                Integer areaPixCol = colIdx.get("Area (pixel)");
                Integer legacyAreaCol = colIdx.get("Area");
                List<SeriesMeta> seriesMetas = volMm3Col == null
                        ? readSeriesMetadataSafely(directory) : null;
                int rowsMissingPhysicalArea = 0;
                int rowsMissingStackDepth = 0;

                if (animalCol == null) {
                    volAccum = null;
                } else if (volMm3Col != null) {
                    // New format with Volume column — read directly
                    recordVolumeSource("csv_direct");
                    IJ.log("  Using Volume (mm^3) column directly.");
                    for (String[] row : allRows) {
                        String animal = safeGet(row, animalCol).trim();
                        if (animal.isEmpty()) continue;
                        double volMm3 = parseDouble(safeGet(row, volMm3Col));
                        if (!(volMm3 > 0)) continue; // also skips NaN
                        addAnimalVolume(volAccum, animal, volMm3);
                    }
                } else if (legacyAreaCol != null && areaUmCol == null) {
                    // Legacy format: "Area" column contains calibrated µm² values.
                    // Upgrade the CSV with new columns and compute volumes.
                    IJ.log("  Legacy ROI Properties detected — upgrading with calibrated columns...");
                    legacyRoiCsvUpgraded = true;

                    Integer regionCol = colIdx.get("Region");
                    Integer scnCol = colIdx.get("SCN");
                    Integer widthCol = colIdx.get("Width");
                    Integer heightCol = colIdx.get("Height");

                    // Determine ROI set name: try a dedicated column first, otherwise
                    // derive from the CSV filename (e.g. "SCN ROI Properties.csv" → "SCN")
                    Integer roiSetCol = colIdx.get("ROI Set");
                    String roiSetFromFilename = roiFile.getName()
                            .replaceAll("(?i)\\s*ROI Properties\\.csv$", "").trim();
                    if (roiSetFromFilename.isEmpty()) roiSetFromFilename = "ROI Set";

                    List<String[]> upgradedRows = new ArrayList<String[]>();

                    for (int r = 0; r < allRows.size(); r++) {
                        String[] row = allRows.get(r);
                        String animal = safeGet(row, animalCol).trim();
                        if (animal.isEmpty()) continue;
                        double areaUm2 = parseDouble(safeGet(row, legacyAreaCol));
                        if (!(areaUm2 > 0)) continue; // also skips NaN

                        VolumeResolution resolution = resolvePhysicalVolume(r, areaUm2, null, seriesMetas);
                        if (resolution.hasVolume()) {
                            addAnimalVolume(volAccum, animal, resolution.volumeMm3);
                            recordVolumeSource(resolution.source);
                        } else if ("missing_stack_depth".equals(resolution.failureReason)) {
                            rowsMissingStackDepth++;
                        }

                        upgradedRows.add(new String[]{
                                animal,
                                regionCol != null ? safeGet(row, regionCol) : "",
                                roiSetCol != null ? safeGet(row, roiSetCol) : roiSetFromFilename,
                                scnCol != null ? safeGet(row, scnCol) : "",
                                resolution.areaPixel != null ? fmt(resolution.areaPixel) : "",
                                fmt(areaUm2),
                                resolution.hasVolume() ? fmt(resolution.volumeUm3) : "",
                                resolution.hasVolume() ? fmt(resolution.volumeMm3) : "",
                                widthCol != null ? safeGet(row, widthCol) : "",
                                heightCol != null ? safeGet(row, heightCol) : ""
                        });
                    }

                    // Rewrite the CSV with upgraded columns
                    try {
                        PrintWriter pw = CsvSupport.newWriter(roiFile);
                        try {
                            pw.println(CsvSupport.joinRow(Arrays.asList(
                                    "Animal Name",
                                    "Region",
                                    "ROI Set",
                                    "SCN",
                                    "Area (pixel)",
                                    "Area (um^2)",
                                    "Volume (micron^3)",
                                    "Volume (mm^3)",
                                    "Width",
                                    "Height")));
                            for (String[] row : upgradedRows) {
                                pw.println(CsvSupport.joinRow(Arrays.asList(row)));
                            }
                        } finally {
                            pw.close();
                        }
                        IJ.log("  Upgraded legacy CSV: " + upgradedRows.size() + " rows rewritten to " + roiFile.getName());
                    } catch (IOException e2) {
                        IJ.log("  Warning: could not rewrite legacy CSV: " + e2.getMessage());
                    }
                    logSkippedVolumeRows(rowsMissingPhysicalArea, rowsMissingStackDepth);
                } else if (areaUmCol != null || areaPixCol != null) {
                    // New format but no Volume column — compute from calibrated area and full stack depth
                    IJ.log("  No Volume (mm^3) column found — deriving ROI volume from calibrated area and full stack depth.");
                    for (int r = 0; r < allRows.size(); r++) {
                        String[] row = allRows.get(r);
                        String animal = safeGet(row, animalCol).trim();
                        if (animal.isEmpty()) continue;
                        Double areaUm2 = parsePositiveDoubleOrNull(safeGet(row, areaUmCol));
                        Double areaPixel = parsePositiveDoubleOrNull(safeGet(row, areaPixCol));

                        VolumeResolution resolution = resolvePhysicalVolume(r, areaUm2, areaPixel, seriesMetas);
                        if (resolution.hasVolume()) {
                            addAnimalVolume(volAccum, animal, resolution.volumeMm3);
                            recordVolumeSource(resolution.source);
                        } else if ("missing_physical_area".equals(resolution.failureReason)) {
                            rowsMissingPhysicalArea++;
                        } else if ("missing_stack_depth".equals(resolution.failureReason)) {
                            rowsMissingStackDepth++;
                        }
                    }
                    logSkippedVolumeRows(rowsMissingPhysicalArea, rowsMissingStackDepth);
                } else {
                    volAccum = null;
                }
            }
        } catch (IOException e) {
            IJ.log("  Error reading ROI Properties: " + e.getMessage());
            volAccum = null;
        }

        // If CSV was empty/invalid, try regenerating from ROI zip files
        if (volAccum == null || volAccum.isEmpty()) {
            IJ.log("  ROI Properties CSV has no data — attempting to regenerate from ROI zips...");
            Map<String, List<Double>> regenVols = regenerateRoiPropertiesFromZips(directory, roiFile);
            if (regenVols != null && !regenVols.isEmpty()) {
                roiPropertiesRegenerated = true;
                volAccum = regenVols;
            }
        }

        if (volAccum == null || volAccum.isEmpty()) {
            IJ.log("  No ROI volume data available — volume normalization will be skipped.");
            return result;
        }

        for (Map.Entry<String, List<Double>> e : volAccum.entrySet()) {
            double sum = 0;
            for (double v : e.getValue()) sum += v;
            result.put(e.getKey(), sum / e.getValue().size());
        }

        IJ.log("  Loaded ROI volume data for " + result.size() + " animals.");
        return result;
    }

    /**
     * Regenerates ROI Properties from ROI zip files when the CSV is empty.
     * Reads per-series metadata from the .lif (no pixel data) to obtain
     * calibration and Z-slice count for each series, then measures every
     * uncropped ROI identically to {@code DrawAndSaveROIsAnalysis}.
     */
    private Map<String, List<Double>> regenerateRoiPropertiesFromZips(String directory, File csvOutFile) {
        List<File> roiZips;
        try {
            roiZips = RoiIO.listRoiZipFiles(new File(directory));
        } catch (NoClassDefFoundError e) {
            if (PluginInstallGuard.reportMissingInternalClass("Master Aggregation Analysis", e)) return null;
            throw e;
        }
        if (roiZips.isEmpty()) {
            IJ.log("  No ROI zip files found in the ROI analysis-image folder.");
            return null;
        }

        // Read per-series metadata from .lif (calibration + nSlices, no pixel data)
        List<SeriesMeta> seriesMetas = readSeriesMetadataSafely(directory);

        Map<String, List<Double>> volAccum = new LinkedHashMap<String, List<Double>>();
        List<String[]> csvRows = new ArrayList<String[]>();
        int rowsMissingPhysicalArea = 0;
        int rowsMissingStackDepth = 0;

        for (File zip : roiZips) {
            String zipName = zip.getName();
            String roiSetName = zipName.endsWith(" ROIs.zip")
                    ? zipName.substring(0, zipName.length() - " ROIs.zip".length())
                    : zipName.replace(".zip", "");

            List<ij.gui.Roi> rois;
            try {
                rois = RoiIO.loadRoisFromZip(zip);
            } catch (NoClassDefFoundError e) {
                if (PluginInstallGuard.reportMissingInternalClass("Master Aggregation Analysis", e)) return null;
                throw e;
            }
            if (rois.isEmpty()) continue;

            IJ.log("  Regenerating from: " + zip.getName() + " (" + rois.size() + " ROIs)");

            // ROIs come in pairs: [uncropped, cropped, uncropped, cropped, ...]
            for (int i = 0; i < rois.size(); i += 2) {
                ij.gui.Roi roi = rois.get(i);
                String roiName = roi.getName();
                if (roiName == null) roiName = "";

                // Parse animal name from ROI name (format: Animal_Hemisphere_Region)
                String[] nameToks = roiName.split("_");
                int hemiIdx = -1;
                for (int t = 0; t < nameToks.length; t++) {
                    String tok = nameToks[t].trim();
                    if ("LH".equals(tok) || "RH".equals(tok)) {
                        hemiIdx = t;
                        break;
                    }
                }
                String animal;
                String regionLabel = "";
                if (hemiIdx > 0) {
                    StringBuilder ab = new StringBuilder();
                    for (int t = 0; t < hemiIdx; t++) {
                        if (t > 0) ab.append("_");
                        ab.append(nameToks[t].trim());
                    }
                    animal = ab.toString();
                    regionLabel = nameToks[hemiIdx].trim();
                    if (hemiIdx + 1 < nameToks.length) {
                        regionLabel += nameToks[hemiIdx + 1].trim();
                    }
                } else {
                    animal = nameToks[0].trim();
                    if (nameToks.length > 1) {
                        regionLabel = nameToks[nameToks.length - 1].trim();
                    }
                }
                if (animal.isEmpty()) continue;

                // ROI pixel area (no image associated, so getStatistics().area is in pixels)
                double pixelArea = roi.getStatistics().area;
                java.awt.Rectangle b = roi.getBounds();
                int seriesIndex = i / 2;

                String areaPixelStr = fmt(pixelArea);
                String areaUmStr = "";
                String volUm3Str = "";
                String volMm3Str = "";
                VolumeResolution resolution = resolvePhysicalVolume(seriesIndex, null, pixelArea, seriesMetas);

                if (resolution.hasVolume()) {
                    addAnimalVolume(volAccum, animal, resolution.volumeMm3);
                    recordVolumeSource(resolution.source);
                    areaUmStr = fmt(resolution.areaUm2);
                    volUm3Str = fmt(resolution.volumeUm3);
                    volMm3Str = fmt(resolution.volumeMm3);
                } else if ("missing_physical_area".equals(resolution.failureReason)) {
                    rowsMissingPhysicalArea++;
                } else if ("missing_stack_depth".equals(resolution.failureReason)) {
                    rowsMissingStackDepth++;
                }

                csvRows.add(new String[]{
                        animal,
                        regionLabel,
                        roiSetName,
                        String.valueOf(seriesIndex),
                        areaPixelStr,
                        areaUmStr,
                        volUm3Str,
                        volMm3Str,
                        String.valueOf(b.width),
                        String.valueOf(b.height)
                });
            }
        }

        if (csvRows.isEmpty()) {
            IJ.log("  No valid ROIs found to regenerate properties from.");
            return null;
        }

        logSkippedVolumeRows(rowsMissingPhysicalArea, rowsMissingStackDepth);

        // Write regenerated CSV
        try {
            PrintWriter pw = CsvSupport.newWriter(csvOutFile);
            try {
                pw.println(CsvSupport.joinRow(Arrays.asList(
                        "Animal Name",
                        "Region",
                        "ROI Set",
                        "SCN",
                        "Area (pixel)",
                        "Area (um^2)",
                        "Volume (micron^3)",
                        "Volume (mm^3)",
                        "Width",
                        "Height")));
                for (String[] row : csvRows) {
                    pw.println(CsvSupport.joinRow(Arrays.asList(row)));
                }
            } finally {
                pw.close();
            }
            IJ.log("  Regenerated ROI Properties: " + csvRows.size() + " entries saved to " + csvOutFile.getName());
        } catch (IOException e) {
            IJ.log("  Error writing regenerated ROI Properties: " + e.getMessage());
        }

        return volAccum;
    }

    /**
     * Counts unique section identifiers per animal from ROI Properties or Objects CSVs.
     */
    private Map<String, Integer> loadNumSections(String directory, File selectedRoiFile) {
        Map<String, Integer> result = new HashMap<String, Integer>();

        // Try ROI Properties tables first (most accurate)
        File attribDir = firstExistingRoiTablesDir(directory);
        if (attribDir.isDirectory()) {
            File[] csvFiles = attribDir.listFiles(new java.io.FilenameFilter() {
                @Override
                public boolean accept(File dir, String name) {
                    return name.toLowerCase(Locale.ROOT).endsWith(".csv")
                            && name.toLowerCase(Locale.ROOT).contains("roi properties");
                }
            });
            if (csvFiles != null && csvFiles.length > 0) {
                Arrays.sort(csvFiles, new Comparator<File>() {
                    @Override
                    public int compare(File a, File b) {
                        return String.CASE_INSENSITIVE_ORDER.compare(a.getName(), b.getName());
                    }
                });
                result = countUniqueSCNs(selectedRoiFile != null ? selectedRoiFile : csvFiles[0]);
                if (!result.isEmpty()) return result;
            }
        }

        // Fallback: count unique SCNs from first Objects CSV
        List<File> objectCsvFiles = objectCsvFiles(directory);
        if (!objectCsvFiles.isEmpty()) {
            result = countUniqueSCNs(objectCsvFiles.get(0));
        }

        return result;
    }

    private Map<String, Integer> countUniqueSCNs(File csvFile) {
        Map<String, Set<String>> sectionSets = new LinkedHashMap<String, Set<String>>();
        try {
            CsvSupport.RecordReader csv = CsvSupport.openRecordReader(csvFile);
            try {
                CsvSupport.Record headerRecord = csv.readRecord();
                if (headerRecord == null) return new HashMap<String, Integer>();
                String[] header = CsvSupport.parseRecord(headerRecord.text);

                Map<String, Integer> colIdx = new HashMap<String, Integer>();
                for (int i = 0; i < header.length; i++) {
                    colIdx.put(header[i].trim(), i);
                }

                Integer animalCol = colIdx.get("Animal Name");
                Integer roiCol = colIdx.get("ROI");
                Integer regionCol = colIdx.get("Region");
                Integer scnCol = colIdx.get("SCN");
                if (animalCol == null) return new HashMap<String, Integer>();
                if (scnCol == null && roiCol == null && regionCol == null) return new HashMap<String, Integer>();

                CsvSupport.Record record;
                while ((record = csv.readRecord()) != null) {
                    if (CsvSupport.isBlankRecord(record.text)) continue;
                    String[] row = CsvSupport.parseRecord(record.text);
                    String animal = safeGet(row, animalCol).trim();
                    String section = resolveSectionKey(row, scnCol, roiCol, regionCol);
                    if (!animal.isEmpty() && !section.isEmpty()) {
                        Set<String> set = sectionSets.get(animal);
                        if (set == null) {
                            set = new HashSet<String>();
                            sectionSets.put(animal, set);
                        }
                        set.add(section);
                    }
                }
            } finally {
                csv.close();
            }
        } catch (IOException e) {
            return new HashMap<String, Integer>();
        }

        Map<String, Integer> result = new LinkedHashMap<String, Integer>();
        for (Map.Entry<String, Set<String>> e : sectionSets.entrySet()) {
            result.put(e.getKey(), e.getValue().size());
        }
        return result;
    }

    // ------------------------------------------------------------------ Objects

    private boolean aggregateObjects(String directory, File exportDir, File detailsDir,
                                     Map<String, Double> volumeMm3PerAnimal,
                                     Map<String, Integer> numSectionsPerAnimal) {
        List<File> csvFiles = aggregationObjectCsvFiles(directory);
        if (csvFiles.isEmpty()) {
            FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
            IJ.log("No object CSV files found in: " + layout.tablesObjectsWriteDir().getAbsolutePath());
            return false;
        }

        Set<String> allAnimals = new LinkedHashSet<String>();
        // channel -> (animal -> metrics map)  — raw values
        LinkedHashMap<String, Map<String, LinkedHashMap<String, Double>>> channelRawData =
                new LinkedHashMap<String, Map<String, LinkedHashMap<String, Double>>>();
        // Track which columns are "summable" (totals/counts) vs "mean" for normalization
        Set<String> summableColumns = new LinkedHashSet<String>();

        for (File csvFile : csvFiles) {
            String channelName = aggregationChannelName(csvFile);
            IJ.log("Processing objects channel: " + channelName);

            List<String[]> rows = new ArrayList<String[]>();
            String[] header;
            try {
                CsvSupport.RecordReader csv = CsvSupport.openRecordReader(csvFile);
                try {
                    CsvSupport.Record headerRecord = csv.readRecord();
                    if (headerRecord == null) {
                        IJ.log("  Empty CSV, skipping: " + csvFile.getName());
                        continue;
                    }
                    header = CsvSupport.parseRecord(headerRecord.text);

                    CsvSupport.Record record;
                    while ((record = csv.readRecord()) != null) {
                        if (CsvSupport.isBlankRecord(record.text)) continue;
                        rows.add(CsvSupport.parseRecord(record.text));
                    }
                } finally {
                    csv.close();
                }
            } catch (IOException e) {
                IJ.log("  Error reading " + csvFile.getName() + ": " + e.getMessage());
                continue;
            }

            if (rows.isEmpty()) {
                IJ.log("  No data rows in " + csvFile.getName());
                continue;
            }

            Map<String, Integer> colIdx = new HashMap<String, Integer>();
            for (int i = 0; i < header.length; i++) {
                colIdx.put(header[i].trim(), i);
            }
            Set<String> classFractionCapWarnings = new HashSet<String>();

            // Find volumetric overlap columns (SOURCE_VolOverlapN_PARTNER),
            // falling back to legacy names if needed.
            List<String> colocPartners = new ArrayList<String>();
            Map<String, String> colocPartnerToCol = new LinkedHashMap<String, String>();
            for (String h : header) {
                String ht = h.trim();
                if (ht.startsWith("Colocalisation with ")) {
                    String partner = ht.substring("Colocalisation with ".length());
                    if (!colocPartnerToCol.containsKey(partner)) {
                        colocPartners.add(partner);
                        colocPartnerToCol.put(partner, ht);
                    }
                } else {
                    String partner = extractVolumetricPartner(ht, "_VolOverlap");
                    if (partner != null && !colocPartnerToCol.containsKey(partner)) {
                        colocPartners.add(partner);
                        colocPartnerToCol.put(partner, ht);
                    }
                }
            }
            for (String h : header) {
                String ht = h.trim();
                String partner = extractVolumetricPartner(ht, "_VolColoc");
                if (partner != null && !colocPartnerToCol.containsKey(partner)) {
                    colocPartners.add(partner);
                    colocPartnerToCol.put(partner, ht);
                }
            }

            // Group rows by composite group key (depends on granularity), tracking unique section values
            LinkedHashMap<String, List<String[]>> grouped = new LinkedHashMap<String, List<String[]>>();
            Map<String, Set<String>> animalScns = new LinkedHashMap<String, Set<String>>();
            Integer animalCol = colIdx.get("Animal Name");
            Integer roiCol = colIdx.get("ROI");
            Integer regionCol = colIdx.get("Region");
            Integer scnCol = colIdx.get("SCN");
            Integer hemisphereCol = colIdx.get("Hemisphere");
            if (animalCol == null) {
                IJ.log("  'Animal Name' column not found in " + csvFile.getName() + ", skipping");
                continue;
            }
            AggregationConfig.Granularity granularity = aggregationConfig.getGranularity();
            for (String[] row : rows) {
                String animal = safeGet(row, animalCol).trim();
                if (animal.isEmpty()) continue;
                String groupKey = composeGroupKey(animal, granularity,
                        row, hemisphereCol, regionCol, scnCol, roiCol);
                List<String[]> list = grouped.get(groupKey);
                if (list == null) {
                    list = new ArrayList<String[]>();
                    grouped.put(groupKey, list);
                }
                list.add(row);
                groupKeyToAnimal.put(groupKey, animal);

                String sectionKey = resolveSectionKey(row, scnCol, roiCol, regionCol);
                if (!sectionKey.isEmpty()) {
                    Set<String> scns = animalScns.get(animal);
                    if (scns == null) {
                        scns = new HashSet<String>();
                        animalScns.put(animal, scns);
                    }
                    scns.add(sectionKey);
                }
            }

            // Update numSections from this channel data if not already set (keyed by parent animal)
            for (Map.Entry<String, Set<String>> e : animalScns.entrySet()) {
                if (!numSectionsPerAnimal.containsKey(e.getKey())) {
                    numSectionsPerAnimal.put(e.getKey(), e.getValue().size());
                }
            }

            // Compute per-animal metrics
            Map<String, LinkedHashMap<String, Double>> animalMetrics =
                    new LinkedHashMap<String, LinkedHashMap<String, Double>>();

            for (Map.Entry<String, List<String[]>> entry : grouped.entrySet()) {
                String groupKey = entry.getKey();
                String parentAnimal = groupKeyToAnimal.containsKey(groupKey)
                        ? groupKeyToAnimal.get(groupKey) : groupKey;
                allAnimals.add(groupKey);
                List<String[]> animalRows = entry.getValue();
                int count = animalRows.size();

                double volumeSum = 0, surfaceSum = 0, intDenSum = 0, meanSum = 0;
                double saToVolRatioSum = 0, xmSum = 0, ymSum = 0;
                int volumeN = 0, surfaceN = 0, intDenN = 0, meanN = 0;
                int saToVolRatioN = 0, xmN = 0, ymN = 0;

                Map<String, Double> colocSum = new HashMap<String, Double>();
                Map<String, Integer> colocSumN = new HashMap<String, Integer>();
                Map<String, Integer> colocCount = new HashMap<String, Integer>();
                for (String p : colocPartners) {
                    colocSum.put(p, 0.0);
                    colocSumN.put(p, 0);
                    colocCount.put(p, 0);
                }

                for (String[] row : animalRows) {
                    double vol = parseDouble(safeGet(row, colIdx.get("Volume (micron^3)")));
                    double surf = parseDouble(safeGet(row, colIdx.get("Surface (micron^2)")));
                    double intDen = parseDouble(safeGet(row, colIdx.get("IntDen")));
                    double mean = parseDouble(safeGet(row, colIdx.get("Mean")));
                    double xm = parseDouble(safeGet(row, colIdx.get("XM")));
                    double ym = parseDouble(safeGet(row, colIdx.get("YM")));

                    if (!Double.isNaN(vol))    { volumeSum  += vol;  volumeN++;  }
                    if (!Double.isNaN(surf))   { surfaceSum += surf; surfaceN++; }
                    if (!Double.isNaN(intDen)) { intDenSum  += intDen; intDenN++; }
                    if (!Double.isNaN(mean))   { meanSum    += mean; meanN++;   }
                    if (!Double.isNaN(xm))     { xmSum += xm * pixelSize; xmN++; }
                    if (!Double.isNaN(ym))     { ymSum += ym * pixelSize; ymN++; }

                    if (!Double.isNaN(vol) && !Double.isNaN(surf) && vol != 0) {
                        saToVolRatioSum += surf / vol;
                        saToVolRatioN++;
                    }

                    for (String p : colocPartners) {
                        String fullColName = colocPartnerToCol.get(p);
                        Integer ci = (fullColName != null) ? colIdx.get(fullColName) : null;
                        if (ci != null) {
                            double cv = parseDouble(safeGet(row, ci));
                            if (Double.isNaN(cv)) continue;
                            colocSum.put(p, colocSum.get(p) + cv);
                            colocSumN.put(p, colocSumN.get(p) + 1);
                            if (cv > COLOC_THRESHOLD) {
                                colocCount.put(p, colocCount.get(p) + 1);
                            }
                        }
                    }
                }

                LinkedHashMap<String, Double> metrics = new LinkedHashMap<String, Double>();
                String prefix = channelName + "_";

                int numSections = numSectionsPerAnimal.containsKey(parentAnimal)
                        ? numSectionsPerAnimal.get(parentAnimal) : 1;

                // Summable columns (totals/counts — will be volume-normalized)
                metrics.put(prefix + "Count", (double) count);
                summableColumns.add(prefix + "Count");

                metrics.put(prefix + "VolumeTotal", volumeSum);
                summableColumns.add(prefix + "VolumeTotal");

                metrics.put(prefix + "SurfaceTotal", surfaceSum);
                summableColumns.add(prefix + "SurfaceTotal");

                metrics.put(prefix + "IntDenTotal", intDenSum);
                summableColumns.add(prefix + "IntDenTotal");

                // Mean columns (NOT volume-normalized) — divide by per-metric
                // finite count, not the row count, so NaN/Inf cells don't dilute the mean.
                metrics.put(prefix + "VolumeMean", volumeN > 0 ? volumeSum / volumeN : Double.NaN);
                metrics.put(prefix + "SurfaceMean", surfaceN > 0 ? surfaceSum / surfaceN : Double.NaN);
                metrics.put(prefix + "IntDenMean", intDenN > 0 ? intDenSum / intDenN : Double.NaN);
                metrics.put(prefix + "MeanIntDenMean", meanN > 0 ? meanSum / meanN : Double.NaN);
                metrics.put(prefix + "SAtoVolumeRatioMean",
                        saToVolRatioN > 0 ? saToVolRatioSum / saToVolRatioN : Double.NaN);
                metrics.put(prefix + "RawXMMean", xmN > 0 ? xmSum / xmN : Double.NaN);
                metrics.put(prefix + "RawYMMean", ymN > 0 ? ymSum / ymN : Double.NaN);

                // Colocalisation
                for (String p : colocPartners) {
                    int cn = colocSumN.get(p);
                    double cMean = cn > 0 ? colocSum.get(p) / cn : Double.NaN;
                    int cc = colocCount.get(p);
                    double ccPct = ((double) cc / count) * 100.0;
                    int nonColoc = count - cc;

                    metrics.put(prefix + "VolColoc" + p + "Mean", cMean);

                    metrics.put(prefix + "VolColocCount" + p, (double) cc);
                    summableColumns.add(prefix + "VolColocCount" + p);

                    metrics.put(prefix + "VolColocCount" + p + "%", ccPct);

                    metrics.put(prefix + "VolNonColocCount" + p, (double) nonColoc);
                    summableColumns.add(prefix + "VolNonColocCount" + p);
                }

                // Distance / spatial / intensity-coloc metrics.
                // Scan header for columns matching known generated patterns.
                for (int hi = 0; hi < header.length; hi++) {
                    String h = header[hi].trim();
                    String normalizedHeader = normalizeAggregationMetricHeader(h);
                    boolean isTextureCol = normalizedHeader.startsWith("MorphTexture_");
                    boolean isSpatialCol = normalizedHeader.contains("_DistToClosest_")
                            || h.contains("_DistTo_")
                            || normalizedHeader.contains("_VolContains")
                            || normalizedHeader.startsWith("Voronoi_")
                            || CHANNEL_AGNOSTIC_MORPH_COLS.contains(normalizedHeader)
                            || isTextureCol
                            || normalizedHeader.equals("Cluster")
                            || normalizedHeader.startsWith(channelName + "_Pearson_")
                            || normalizedHeader.startsWith(channelName + "_Manders_M1_")
                            || normalizedHeader.startsWith(channelName + "_Manders_M2_")
                            || normalizedHeader.startsWith(channelName + "_Costes_Ta_")
                            || normalizedHeader.startsWith(channelName + "_Costes_Tb_")
                            || normalizedHeader.startsWith(channelName + "_Costes_p_");
                    if (!isSpatialCol) continue;

                    // Output column name: strip threshold digits from VolContains for clean aggregated names
                    String cleanH = normalizedHeader.replaceAll("(_VolContains)\\d+(_)", "$1$2");
                    boolean modal = "MorphTexture_ClassLabel".equals(cleanH)
                            || "MorphTexture_Class3DLabel".equals(cleanH);
                    // Add channel prefix for columns that don't already contain it
                    // (channel-agnostic Morph_, MorphTexture_, Voronoi_, Cluster - see needsChannelPrefix).
                    boolean needsPrefix = needsChannelPrefix(cleanH);
                    String outCol = (needsPrefix ? prefix : "") + cleanH + (modal ? "Mode" : "Mean");
                    metrics.put(outCol, modal
                            ? modalIntegerValue(animalRows, hi)
                            : meanFiniteValue(animalRows, hi));
                    if (textureClassFractions && modal) {
                        ClassFractionAggregation fractions = classFractionValues(
                                animalRows, hi, (needsPrefix ? prefix : "") + cleanH + "_Fraction_");
                        metrics.putAll(fractions.values);
                        if (fractions.capped
                                && classFractionCapWarnings.add(channelName + "|" + cleanH)) {
                            IJ.log("  WARNING: Texture class fraction aggregation for "
                                    + channelName + " " + cleanH + " observed class label "
                                    + fractions.maxLabel + " and is capped at "
                                    + MAX_TEXTURE_CLASS_FRACTION_COLUMNS + " fraction columns.");
                        }
                    }
                }

                // numSections for this group (stored once, not per-channel)
                metrics.put("numSections", (double) numSections);

                animalMetrics.put(groupKey, metrics);
            }

            mergeChannelMetrics(channelRawData, channelName, animalMetrics);
        }

        if (allAnimals.isEmpty()) {
            IJ.log("No animal data found across object CSVs.");
            return false;
        }

        // Build ordered column list, inserting _permm3 columns after each summable column
        AggregationConfig.OutputMode outputMode = aggregationConfig.getOutputMode();
        boolean canNormalize = !volumeMm3PerAnimal.isEmpty()
                && outputMode != AggregationConfig.OutputMode.RAW_ONLY;
        boolean keepRawSummables =
                outputMode != AggregationConfig.OutputMode.PERMM3_ONLY
                || volumeMm3PerAnimal.isEmpty();
        boolean emitPerMm3 = canNormalize;

        List<String> columns = new ArrayList<String>();
        columns.add("AnimalName");
        columns.add("numSections");
        Set<String> seenMetricColumns = new LinkedHashSet<String>();
        seenMetricColumns.add("AnimalName");
        seenMetricColumns.add("numSections");
        for (Map.Entry<String, Map<String, LinkedHashMap<String, Double>>> chEntry : channelRawData.entrySet()) {
            for (LinkedHashMap<String, Double> metrics : chEntry.getValue().values()) {
                for (String key : metrics.keySet()) {
                    if (seenMetricColumns.contains(key)) continue;
                    seenMetricColumns.add(key);
                    boolean isSummable = summableColumns.contains(key);
                    if (!isSummable || keepRawSummables) {
                        columns.add(key);
                    }
                    if (emitPerMm3 && isSummable) {
                        columns.add(key + "_permm3");
                    }
                }
            }
        }

        // Collect data into flat structure, computing _permm3 values inline
        LinkedHashMap<String, LinkedHashMap<String, Double>> table =
                new LinkedHashMap<String, LinkedHashMap<String, Double>>();
        for (String groupKey : allAnimals) {
            String parentAnimal = groupKeyToAnimal.containsKey(groupKey)
                    ? groupKeyToAnimal.get(groupKey) : groupKey;
            LinkedHashMap<String, Double> row = new LinkedHashMap<String, Double>();
            for (Map<String, LinkedHashMap<String, Double>> animalMap : channelRawData.values()) {
                LinkedHashMap<String, Double> m = animalMap.get(groupKey);
                if (m != null) {
                    row.putAll(m);
                }
            }

            // Compute _permm3 columns
            if (emitPerMm3) {
                Double volumeMm3 = volumeMm3PerAnimal.get(parentAnimal);
                int numSections = numSectionsPerAnimal.containsKey(parentAnimal)
                        ? numSectionsPerAnimal.get(parentAnimal) : 1;

                if (volumeMm3 != null && volumeMm3 > 0) {
                    for (String col : summableColumns) {
                        Double val = row.get(col);
                        if (val != null) {
                            double perSection = val / numSections;
                            row.put(col + "_permm3", perSection / volumeMm3);
                        }
                    }
                } else {
                    IJ.log("  WARNING: No ROI volume data for animal '" + parentAnimal
                            + "' — _permm3 columns left empty for this group.");
                }
            }

            table.put(groupKey, row);
        }

        File outFile = new File(exportDir, FlashProjectLayout.MASTER_OBJECTS_FILENAME);
        writeMasterCsv(outFile, columns, allAnimals, table);

        // Write aggregation analysis details
        if (canNormalize) {
            writeAggregationDetails(detailsDir, volumeMm3PerAnimal, numSectionsPerAnimal);
        }

        return true;
    }

    File chooseRoiPropertiesFile(String directory, Set<String> preferredAnimals) {
        File attribDir = firstExistingRoiTablesDir(directory);
        if (!attribDir.isDirectory()) {
            IJ.log("No ROI tables directory found â€” volume normalization will be skipped.");
            return null;
        }

        File[] csvFiles = attribDir.listFiles(new java.io.FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.toLowerCase(Locale.ROOT).endsWith(".csv")
                        && name.toLowerCase(Locale.ROOT).contains("roi properties");
            }
        });

        if (csvFiles == null || csvFiles.length == 0) {
            IJ.log("No ROI Properties CSV found in ROI tables â€” volume normalization will be skipped.");
            return null;
        }

        Arrays.sort(csvFiles, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return String.CASE_INSENSITIVE_ORDER.compare(a.getName(), b.getName());
            }
        });

        if (csvFiles.length == 1 || preferredAnimals == null || preferredAnimals.isEmpty()) {
            return csvFiles[0];
        }

        File bestFile = null;
        int bestOverlap = -1;
        for (File csvFile : csvFiles) {
            int overlap = countOverlap(loadAnimalNames(csvFile), preferredAnimals);
            if (overlap > bestOverlap) {
                bestOverlap = overlap;
                bestFile = csvFile;
            }
        }

        if (bestFile != null && bestOverlap > 0) {
            IJ.log("Multiple ROI Properties CSVs found â€” selected " + bestFile.getName()
                    + " (" + bestOverlap + " matching animals).");
            return bestFile;
        }

        return csvFiles[0];
    }

    private void resetVolumeTracking() {
        volumeSourcesUsed.clear();
        legacyRoiCsvUpgraded = false;
        roiPropertiesRegenerated = false;
        skippedUnsafeVolumeRows = false;
    }

    private void recordVolumeSource(String source) {
        if (source != null && !source.isEmpty()) {
            volumeSourcesUsed.add(source);
        }
    }

    private void addAnimalVolume(Map<String, List<Double>> volAccum, String animal, double volMm3) {
        if (animal == null || animal.trim().isEmpty() || volMm3 <= 0) return;
        List<Double> list = volAccum.get(animal);
        if (list == null) {
            list = new ArrayList<Double>();
            volAccum.put(animal, list);
        }
        list.add(volMm3);
    }

    private List<SeriesMeta> readSeriesMetadataSafely(String directory) {
        try {
            List<SeriesMeta> seriesMetas = ImageSourceDispatcher.readAllMetadata(directory);
            IJ.log("  Read metadata for " + seriesMetas.size() + " series");
            return seriesMetas;
        } catch (Exception e) {
            IJ.log("  Warning: could not read series metadata: " + e.getMessage());
            return null;
        }
    }

    private void logSkippedVolumeRows(int rowsMissingPhysicalArea, int rowsMissingStackDepth) {
        if (rowsMissingPhysicalArea > 0) {
            skippedUnsafeVolumeRows = true;
            IJ.log("  WARNING: Skipped " + rowsMissingPhysicalArea
                    + " ROI row(s) for volume normalization because only pixel area was available"
                    + " without trustworthy physical XY calibration.");
        }
        if (rowsMissingStackDepth > 0) {
            skippedUnsafeVolumeRows = true;
            IJ.log("  WARNING: Skipped " + rowsMissingStackDepth
                    + " ROI row(s) for volume normalization because no trustworthy full-stack depth was available.");
        }
    }

    private VolumeResolution resolvePhysicalVolume(int rowIndex,
                                                   Double areaUm2,
                                                   Double areaPixel,
                                                   List<SeriesMeta> seriesMetas) {
        AreaResolution area = resolvePhysicalArea(rowIndex, areaUm2, areaPixel, seriesMetas);
        if (!area.hasPhysicalArea()) {
            return VolumeResolution.unresolved(area.areaUm2, area.areaPixel, area.failureReason);
        }

        StackDepthResolution stackDepth = resolveStackDepth(rowIndex, seriesMetas);
        if (stackDepth == null) {
            return VolumeResolution.unresolved(area.areaUm2, area.areaPixel, "missing_stack_depth");
        }

        double volumeUm3 = area.areaUm2 * stackDepth.stackDepth;
        if (volumeUm3 <= 0) {
            return VolumeResolution.unresolved(area.areaUm2, area.areaPixel, "missing_stack_depth");
        }

        return VolumeResolution.resolved(
                area.areaUm2,
                area.areaPixel,
                volumeUm3,
                volumeUm3 / 1e9,
                stackDepth.source);
    }

    private AreaResolution resolvePhysicalArea(int rowIndex,
                                               Double knownAreaUm2,
                                               Double knownAreaPixel,
                                               List<SeriesMeta> seriesMetas) {
        if (knownAreaUm2 != null && knownAreaUm2 > 0) {
            Double resolvedPixelArea = knownAreaPixel != null && knownAreaPixel > 0
                    ? knownAreaPixel
                    : derivePixelArea(rowIndex, knownAreaUm2, seriesMetas);
            return new AreaResolution(knownAreaUm2, resolvedPixelArea, null);
        }

        if (knownAreaPixel != null && knownAreaPixel > 0) {
            Double resolvedAreaUm2 = convertPixelAreaToUm2(rowIndex, knownAreaPixel, seriesMetas);
            if (resolvedAreaUm2 != null && resolvedAreaUm2 > 0) {
                return new AreaResolution(resolvedAreaUm2, knownAreaPixel, null);
            }
            return new AreaResolution(null, knownAreaPixel, "missing_physical_area");
        }

        return new AreaResolution(null, knownAreaPixel, "missing_area");
    }

    private Double derivePixelArea(int rowIndex, double areaUm2, List<SeriesMeta> seriesMetas) {
        SeriesMeta meta = getSeriesMeta(seriesMetas, rowIndex);
        if (hasUsablePixelCalibration(meta)) {
            return areaUm2 / (meta.pixelWidth * meta.pixelHeight);
        }
        if (hasPersistedPhysicalCalibration()) {
            return areaUm2 / (calibrationPixelWidth * calibrationPixelHeight);
        }
        return null;
    }

    private Double convertPixelAreaToUm2(int rowIndex, double areaPixel, List<SeriesMeta> seriesMetas) {
        SeriesMeta meta = getSeriesMeta(seriesMetas, rowIndex);
        if (hasUsablePixelCalibration(meta)) {
            return areaPixel * meta.pixelWidth * meta.pixelHeight;
        }
        if (hasPersistedPhysicalCalibration()) {
            return areaPixel * calibrationPixelWidth * calibrationPixelHeight;
        }
        return null;
    }

    private StackDepthResolution resolveStackDepth(int rowIndex, List<SeriesMeta> seriesMetas) {
        SeriesMeta meta = getSeriesMeta(seriesMetas, rowIndex);
        if (hasUsableSeriesStackDepth(meta)) {
            return new StackDepthResolution(meta.pixelDepth * meta.nSlices, "area_x_series_stack_depth");
        }
        if (!Double.isNaN(fallbackStackDepth) && fallbackStackDepth > 0) {
            return new StackDepthResolution(fallbackStackDepth, "area_x_persisted_stack_depth");
        }
        return null;
    }

    private boolean hasPersistedPhysicalCalibration() {
        return calibrationLoaded
                && calibrationPixelWidth > 0
                && calibrationPixelHeight > 0;
    }

    private static SeriesMeta getSeriesMeta(List<SeriesMeta> seriesMetas, int rowIndex) {
        if (seriesMetas == null || rowIndex < 0 || rowIndex >= seriesMetas.size()) return null;
        return seriesMetas.get(rowIndex);
    }

    private static boolean hasUsablePixelCalibration(SeriesMeta meta) {
        return meta != null
                && meta.isCalibrated()
                && meta.pixelWidth > 0
                && meta.pixelHeight > 0;
    }

    private static boolean hasUsableSeriesStackDepth(SeriesMeta meta) {
        return meta != null
                && meta.isCalibrated()
                && meta.pixelDepth > 0
                && meta.nSlices > 0;
    }

    private static Double parsePositiveDoubleOrNull(String s) {
        double value = parseDouble(s);
        return value > 0 ? value : null;
    }

    private static final class AreaResolution {
        final Double areaUm2;
        final Double areaPixel;
        final String failureReason;

        AreaResolution(Double areaUm2, Double areaPixel, String failureReason) {
            this.areaUm2 = areaUm2;
            this.areaPixel = areaPixel;
            this.failureReason = failureReason;
        }

        boolean hasPhysicalArea() {
            return areaUm2 != null && areaUm2 > 0;
        }
    }

    private static final class StackDepthResolution {
        final double stackDepth;
        final String source;

        StackDepthResolution(double stackDepth, String source) {
            this.stackDepth = stackDepth;
            this.source = source;
        }
    }

    private static final class VolumeResolution {
        final Double areaUm2;
        final Double areaPixel;
        final Double volumeUm3;
        final Double volumeMm3;
        final String source;
        final String failureReason;

        private VolumeResolution(Double areaUm2,
                                 Double areaPixel,
                                 Double volumeUm3,
                                 Double volumeMm3,
                                 String source,
                                 String failureReason) {
            this.areaUm2 = areaUm2;
            this.areaPixel = areaPixel;
            this.volumeUm3 = volumeUm3;
            this.volumeMm3 = volumeMm3;
            this.source = source;
            this.failureReason = failureReason;
        }

        static VolumeResolution resolved(Double areaUm2,
                                         Double areaPixel,
                                         Double volumeUm3,
                                         Double volumeMm3,
                                         String source) {
            return new VolumeResolution(areaUm2, areaPixel, volumeUm3, volumeMm3, source, null);
        }

        static VolumeResolution unresolved(Double areaUm2,
                                           Double areaPixel,
                                           String failureReason) {
            return new VolumeResolution(areaUm2, areaPixel, null, null, null, failureReason);
        }

        boolean hasVolume() {
            return volumeMm3 != null && volumeMm3 > 0;
        }
    }

    private void writeAggregationDetails(File detailsDir,
                                          Map<String, Double> volumeMm3PerAnimal,
                                          Map<String, Integer> numSectionsPerAnimal) {
        File detailsFile = new File(detailsDir, "Aggregation_Analysis_Details.txt");
        try {
            IoUtils.mustMkdirs(detailsDir);
            PrintWriter pw = new PrintWriter(detailsFile);
            try {
                pw.println("=== Master Data Aggregation — Analysis Details ===");
                pw.println();

                // Calibration
                pw.println("<Calibration>");
                if (calibrationLoaded) {
                    pw.println("Pixel calibration loaded from calibration.properties.");
                    pw.println("  pixelWidth  = " + calibrationPixelWidth + " " + calibrationUnit);
                    pw.println("  pixelHeight = " + calibrationPixelHeight + " " + calibrationUnit);
                    pw.println("  pixelDepth  = " + calibrationPixelDepth + " " + calibrationUnit);
                    if (!Double.isNaN(fallbackStackDepth) && fallbackStackDepth > 0) {
                        pw.println("  stackDepth  = " + fallbackStackDepth + " " + calibrationUnit
                                + " (persisted fallback)");
                    } else {
                        pw.println("  stackDepth  = unavailable in calibration.properties");
                    }
                } else {
                    pw.println("No image calibration was available.");
                    pw.println("  XM/YM coordinates are in pixel units.");
                }
                pw.println("</Calibration>");
                pw.println();

                // Volume computation — only describe what actually happened
                pw.println("<Volume Computation>");
                pw.println("Fallback source precedence:");
                pw.println("  1. Direct 'Volume (mm^3)' from ROI Properties CSV");
                pw.println("  2. Calibrated area x per-series stack depth from .lif metadata");
                pw.println("  3. Calibrated area x persisted fallback stack depth from calibration.properties");
                pw.println("  4. Skip normalization when physical volume cannot be derived safely");
                pw.println();
                if (legacyRoiCsvUpgraded) {
                    pw.println("Legacy ROI Properties CSV detected (had 'Area' column in µm²).");
                    pw.println("CSV was upgraded with new columns: Area (pixel), Area (um^2), Volume (micron^3), Volume (mm^3).");
                }
                if (roiPropertiesRegenerated) {
                    pw.println("ROI Properties CSV was empty or invalid.");
                    pw.println("Properties were regenerated from ROI zip files.");
                }
                if (volumeSourcesUsed.contains("csv_direct")) {
                    pw.println("Direct CSV volume was used where 'Volume (mm^3)' was present.");
                }
                if (volumeSourcesUsed.contains("area_x_series_stack_depth")) {
                    pw.println("Fallback volume was derived from calibrated area and per-series .lif stack depth:");
                    pw.println("  Volume (micron^3) = Area (um^2) x (pixelDepth x nSlices)");
                    pw.println("  Volume (mm^3) = Volume (micron^3) / 1,000,000,000");
                }
                if (volumeSourcesUsed.contains("area_x_persisted_stack_depth")) {
                    pw.println("Fallback volume was derived from calibrated area and persisted fallback stack depth:");
                    pw.println("  Volume (micron^3) = Area (um^2) x stackDepth");
                    pw.println("  Volume (mm^3) = Volume (micron^3) / 1,000,000,000");
                }
                if (skippedUnsafeVolumeRows) {
                    pw.println("Rows without trustworthy physical area or full-stack depth were skipped.");
                }
                pw.println("</Volume Computation>");
                pw.println();

                pw.println("<Per mm^3 Normalisation>");
                pw.println("For each summable metric (counts, totals), a normalised column was computed:");
                pw.println("  value_permm3 = (raw_value / numSections) / Volume_mm3");
                pw.println("Where:");
                pw.println("  raw_value   = summed metric across all objects for this animal");
                pw.println("  numSections = number of unique tissue sections analysed for this animal");
                pw.println("  Volume_mm3  = mean ROI volume in mm^3 across all sections for this animal");
                pw.println("</Per mm^3 Normalisation>");
                pw.println();

                pw.println("<Per-Animal Volume Data>");
                for (Map.Entry<String, Double> e : volumeMm3PerAnimal.entrySet()) {
                    String animal = e.getKey();
                    int sections = numSectionsPerAnimal.containsKey(animal)
                            ? numSectionsPerAnimal.get(animal) : 1;
                    pw.println("  " + animal + ": Volume=" + e.getValue()
                            + " mm^3, Sections=" + sections);
                }
                pw.println("</Per-Animal Volume Data>");
            } finally {
                pw.close();
            }
            IJ.log("  Aggregation details written: " + detailsFile.getName());
        } catch (IOException e) {
            IJ.log("  WARNING: Failed to write aggregation details: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------- Intensities

    private boolean aggregateIntensities(String directory, File exportDir,
                                         Map<String, Integer> numSectionsPerAnimal) {
        List<File> csvFiles = intensityCsvFiles(directory);
        if (csvFiles.isEmpty()) {
            FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
            IJ.log("No intensity CSV files found in: " + layout.tablesIntensityWriteDir().getAbsolutePath());
            return false;
        }

        Set<String> knownChannelNames = knownIntensityChannels(directory);
        List<ClassifiedIntensityCsv> classifiedFiles = new ArrayList<ClassifiedIntensityCsv>();
        for (File csvFile : csvFiles) {
            ClassifiedIntensityCsv classified = classifyIntensityCsv(csvFile, knownChannelNames);
            classifiedFiles.add(classified);
            if (!classified.channelRoiMask) {
                knownChannelNames.add(classified.channelName);
            }
        }

        LinkedHashMap<IntensitySpatialOutputMode, IntensityAggregationBucket> buckets =
                new LinkedHashMap<IntensitySpatialOutputMode, IntensityAggregationBucket>();
        buckets.put(IntensitySpatialOutputMode.BASE, new IntensityAggregationBucket());
        buckets.put(IntensitySpatialOutputMode.MIP, new IntensityAggregationBucket());
        buckets.put(IntensitySpatialOutputMode.NATIVE_3D, new IntensityAggregationBucket());

        boolean loggedLegacyRawIntDen = false;

        for (ClassifiedIntensityCsv classified : classifiedFiles) {
            File csvFile = classified.file;
            String channelName = classified.channelName;
            IJ.log("Processing intensities channel: " + channelName
                    + intensityModeLogSuffix(classified.mode)
                    + (classified.channelRoiMask ? " (Channel ROI Mask)" : ""));

            List<String[]> rows = new ArrayList<String[]>();
            String[] header;
            try {
                CsvSupport.RecordReader csv = CsvSupport.openRecordReader(csvFile);
                try {
                    CsvSupport.Record headerRecord = csv.readRecord();
                    if (headerRecord == null) {
                        IJ.log("  Empty CSV, skipping: " + csvFile.getName());
                        continue;
                    }
                    header = CsvSupport.parseRecord(headerRecord.text);

                    CsvSupport.Record record;
                    while ((record = csv.readRecord()) != null) {
                        if (CsvSupport.isBlankRecord(record.text)) continue;
                        rows.add(CsvSupport.parseRecord(record.text));
                    }
                } finally {
                    csv.close();
                }
            } catch (IOException e) {
                IJ.log("  Error reading " + csvFile.getName() + ": " + e.getMessage());
                continue;
            }

            if (rows.isEmpty()) {
                IJ.log("  No data rows in " + csvFile.getName());
                continue;
            }

            Map<String, Integer> colIdx = new HashMap<String, Integer>();
            for (int i = 0; i < header.length; i++) {
                colIdx.put(header[i].trim(), i);
            }
            List<DynamicIntensityColumn> dynamicColumns = dynamicIntensityColumns(
                    header, colIdx, channelName, knownChannelNames, classified.channelRoiMask);

            Integer animalCol = colIdx.get("Animal Name");
            Integer roiCol = colIdx.get("ROI");
            Integer regionCol = colIdx.get("Region");
            Integer scnCol = colIdx.get("SCN");
            Integer hemisphereCol = colIdx.get("Hemisphere");
            if (animalCol == null) {
                IJ.log("  'Animal Name' column not found in " + csvFile.getName() + ", skipping");
                continue;
            }

            LinkedHashMap<String, List<String[]>> grouped = new LinkedHashMap<String, List<String[]>>();
            AggregationConfig.Granularity granularity = aggregationConfig.getGranularity();
            for (String[] row : rows) {
                String animal = safeGet(row, animalCol).trim();
                if (animal.isEmpty()) continue;
                String groupKey = composeGroupKey(animal, granularity,
                        row, hemisphereCol, regionCol, scnCol, roiCol);
                List<String[]> list = grouped.get(groupKey);
                if (list == null) {
                    list = new ArrayList<String[]>();
                    grouped.put(groupKey, list);
                }
                list.add(row);
                groupKeyToAnimal.put(groupKey, animal);
            }

            Map<String, LinkedHashMap<String, Double>> animalMetrics =
                    new LinkedHashMap<String, LinkedHashMap<String, Double>>();

            for (Map.Entry<String, List<String[]>> entry : grouped.entrySet()) {
                String groupKey = entry.getKey();
                String parentAnimal = groupKeyToAnimal.containsKey(groupKey)
                        ? groupKeyToAnimal.get(groupKey) : groupKey;
                IntensityAggregationBucket bucket = buckets.get(classified.mode);
                bucket.allAnimals.add(groupKey);
                List<String[]> animalRows = entry.getValue();

                double intDenSum = 0, intDenBinarizedSum = 0, areaSum = 0,
                        areaBinarizedSum = 0, intDenUnfilteredSum = 0;
                int intDenN = 0, intDenBinarizedN = 0, areaN = 0,
                        areaBinarizedN = 0, intDenUnfilteredN = 0;

                int intDenCol = columnIndex(colIdx, "IntDen", null);
                int intDenBinarizedCol = columnIndex(colIdx, "IntDen_binarized", null);
                int areaCol = columnIndex(colIdx, "%Area", null);
                int areaBinarizedCol = columnIndex(colIdx, "%Area_binarized", null);
                int intDenUnfilteredCol = columnIndex(colIdx, "IntDen_Unfiltered", "RawIntDen");

                boolean usesLegacyRawIntDen = !colIdx.containsKey("IntDen_Unfiltered")
                        && colIdx.containsKey("RawIntDen");
                if (usesLegacyRawIntDen && !loggedLegacyRawIntDen) {
                    IJ.log("  Legacy intensity column RawIntDen detected; aggregating as IntDen_Unfiltered.");
                    loggedLegacyRawIntDen = true;
                }

                for (String[] row : animalRows) {
                    double intDen = parseDouble(safeGet(row, intDenCol));
                    double intDenBinarized = parseDouble(safeGet(row, intDenBinarizedCol));
                    double area = parseDouble(safeGet(row, areaCol));
                    double areaBinarized = parseDouble(safeGet(row, areaBinarizedCol));
                    double intDenUnfiltered = parseDouble(safeGet(row, intDenUnfilteredCol));
                    if (!Double.isNaN(intDen)) { intDenSum += intDen; intDenN++; }
                    if (!Double.isNaN(intDenBinarized)) {
                        intDenBinarizedSum += intDenBinarized;
                        intDenBinarizedN++;
                    }
                    if (!Double.isNaN(area))   { areaSum   += area;   areaN++;   }
                    if (!Double.isNaN(areaBinarized)) {
                        areaBinarizedSum += areaBinarized;
                        areaBinarizedN++;
                    }
                    if (!Double.isNaN(intDenUnfiltered)) {
                        intDenUnfilteredSum += intDenUnfiltered;
                        intDenUnfilteredN++;
                    }
                }

                String prefix = channelName + "_";
                LinkedHashMap<String, Double> metrics = new LinkedHashMap<String, Double>();
                if (intDenCol >= 0) {
                    metrics.put(prefix + "ROI_IntDenMean",
                            intDenN > 0 ? intDenSum / intDenN : Double.NaN);
                }
                if (intDenBinarizedCol >= 0) {
                    metrics.put(prefix + "ROI_IntDen_binarizedMean",
                            intDenBinarizedN > 0 ? intDenBinarizedSum / intDenBinarizedN : Double.NaN);
                }
                if (areaCol >= 0) {
                    metrics.put(prefix + "ROI_%AreaMean",
                            areaN > 0 ? areaSum / areaN : Double.NaN);
                }
                if (areaBinarizedCol >= 0) {
                    metrics.put(prefix + "ROI_%Area_binarizedMean",
                            areaBinarizedN > 0 ? areaBinarizedSum / areaBinarizedN : Double.NaN);
                }
                if (intDenUnfilteredCol >= 0) {
                    metrics.put(prefix + "ROI_IntDen_UnfilteredMean",
                            intDenUnfilteredN > 0 ? intDenUnfilteredSum / intDenUnfilteredN : Double.NaN);
                }
                for (DynamicIntensityColumn dynamicColumn : dynamicColumns) {
                    metrics.put(dynamicColumn.outputName,
                            meanColumn(animalRows, dynamicColumn.index));
                }

                int numSections = numSectionsPerAnimal.containsKey(parentAnimal)
                        ? numSectionsPerAnimal.get(parentAnimal) : 1;
                metrics.put("numSections", (double) numSections);

                animalMetrics.put(groupKey, metrics);
            }

            mergeChannelMetrics(buckets.get(classified.mode).channelData, channelName, animalMetrics);
        }

        boolean wroteAny = false;
        for (Map.Entry<IntensitySpatialOutputMode, IntensityAggregationBucket> entry : buckets.entrySet()) {
            IntensityAggregationBucket bucket = entry.getValue();
            if (bucket.allAnimals.isEmpty()) {
                continue;
            }

            List<String> columns = new ArrayList<String>();
            columns.add("AnimalName");
            columns.add("numSections");
            Set<String> seenColumns = new LinkedHashSet<String>();
            seenColumns.add("AnimalName");
            seenColumns.add("numSections");
            for (Map.Entry<String, Map<String, LinkedHashMap<String, Double>>> chEntry : bucket.channelData.entrySet()) {
                for (LinkedHashMap<String, Double> metrics : chEntry.getValue().values()) {
                    for (String key : metrics.keySet()) {
                        if (seenColumns.add(key)) {
                            columns.add(key);
                        }
                    }
                }
            }

            LinkedHashMap<String, LinkedHashMap<String, Double>> table =
                    new LinkedHashMap<String, LinkedHashMap<String, Double>>();
            for (String groupKey : bucket.allAnimals) {
                LinkedHashMap<String, Double> row = new LinkedHashMap<String, Double>();
                for (Map<String, LinkedHashMap<String, Double>> animalMap : bucket.channelData.values()) {
                    LinkedHashMap<String, Double> m = animalMap.get(groupKey);
                    if (m != null) row.putAll(m);
                }
                table.put(groupKey, row);
            }

            File outFile = new File(exportDir, intensityMasterFilename(entry.getKey()));
            writeMasterCsv(outFile, columns, bucket.allAnimals, table);
            wroteAny = true;
        }

        if (!wroteAny) {
            IJ.log("No animal data found across intensity CSVs.");
            return false;
        }

        return true;
    }

    private static Set<String> knownIntensityChannels(String directory) {
        Set<String> out = new LinkedHashSet<String>();
        BinConfig cfg = BinConfigIO.readPartialFromDirectory(directory);
        if (cfg != null && cfg.channelNames != null) {
            for (String channelName : cfg.channelNames) {
                if (channelName != null && !channelName.trim().isEmpty()) {
                    out.add(channelName.trim());
                }
            }
        }
        return out;
    }

    private static ClassifiedIntensityCsv classifyIntensityCsv(File csvFile,
                                                              Set<String> knownChannelNames) {
        String stem = csvStem(csvFile);
        if (isChannelRoiMaskStem(stem)) {
            return new ClassifiedIntensityCsv(csvFile, ChannelFilenameCodec.toRaw(stem),
                    IntensitySpatialOutputMode.BASE, true);
        }

        String lower = stem.toLowerCase(Locale.ROOT);
        if (lower.endsWith("_mip")) {
            String channelStem = stem.substring(0, stem.length() - "_MIP".length());
            String fullRaw = ChannelFilenameCodec.toRaw(stem);
            String channelRaw = ChannelFilenameCodec.toRaw(channelStem);
            if (shouldTreatModeSuffixAsMode(fullRaw, channelRaw, knownChannelNames)) {
                return new ClassifiedIntensityCsv(csvFile, channelRaw,
                        IntensitySpatialOutputMode.MIP, false);
            }
        }
        if (lower.endsWith("_3d")) {
            String channelStem = stem.substring(0, stem.length() - "_3D".length());
            String fullRaw = ChannelFilenameCodec.toRaw(stem);
            String channelRaw = ChannelFilenameCodec.toRaw(channelStem);
            if (shouldTreatModeSuffixAsMode(fullRaw, channelRaw, knownChannelNames)) {
                return new ClassifiedIntensityCsv(csvFile, channelRaw,
                        IntensitySpatialOutputMode.NATIVE_3D, false);
            }
        }
        return new ClassifiedIntensityCsv(csvFile, ChannelFilenameCodec.toRaw(stem),
                IntensitySpatialOutputMode.BASE, false);
    }

    private static boolean shouldTreatModeSuffixAsMode(String fullRaw,
                                                       String channelRaw,
                                                       Set<String> knownChannelNames) {
        if (knownChannelNames == null || knownChannelNames.isEmpty()) {
            return true;
        }
        if (knownChannelNames.contains(channelRaw)) {
            return true;
        }
        return !knownChannelNames.contains(fullRaw);
    }

    private static String csvStem(File csvFile) {
        String stem = csvFile == null ? "" : csvFile.getName();
        if (stem.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            stem = stem.substring(0, stem.length() - 4);
        }
        return stem;
    }

    private static boolean isChannelRoiMaskStem(String stem) {
        if (stem == null) return false;
        return stem.contains(" in ") && stem.endsWith(" ROI");
    }

    private static String intensityModeLogSuffix(IntensitySpatialOutputMode mode) {
        if (mode == IntensitySpatialOutputMode.MIP) return " (MIP)";
        if (mode == IntensitySpatialOutputMode.NATIVE_3D) return " (native 3D)";
        return "";
    }

    private static String intensityMasterFilename(IntensitySpatialOutputMode mode) {
        if (mode == IntensitySpatialOutputMode.MIP) return MASTER_INTENSITIES_MIP_FILENAME;
        if (mode == IntensitySpatialOutputMode.NATIVE_3D) return MASTER_INTENSITIES_3D_FILENAME;
        return FlashProjectLayout.MASTER_INTENSITIES_FILENAME;
    }

    private static List<DynamicIntensityColumn> dynamicIntensityColumns(
            String[] header,
            Map<String, Integer> colIdx,
            String channelName,
            Set<String> knownChannelNames,
            boolean skipSpatialColumns) {
        List<DynamicIntensityColumn> out = new ArrayList<DynamicIntensityColumn>();
        if (skipSpatialColumns || header == null || channelName == null) {
            return out;
        }
        Set<String> seen = new LinkedHashSet<String>();
        for (String rawHeader : header) {
            String column = rawHeader == null ? "" : rawHeader.trim();
            if (column.isEmpty() || seen.contains(column)) continue;
            boolean sameChannelSpatial = column.startsWith("Intensity_");
            boolean pairSpatial = isPairIntensityColumn(column, channelName, knownChannelNames);
            if (!sameChannelSpatial && !pairSpatial) continue;

            Integer index = colIdx.get(column);
            if (index == null) continue;
            out.add(new DynamicIntensityColumn(index.intValue(),
                    channelName + "_ROI_" + column + "Mean"));
            seen.add(column);
        }
        return out;
    }

    private static boolean isPairIntensityColumn(String column,
                                                 String sourceChannel,
                                                 Set<String> knownChannelNames) {
        if (column == null || sourceChannel == null
                || knownChannelNames == null || knownChannelNames.isEmpty()) {
            return false;
        }
        String base = column.endsWith("_binarized")
                ? column.substring(0, column.length() - "_binarized".length())
                : column;
        String prefix = sourceChannel + "_";
        if (!base.startsWith(prefix)) {
            return false;
        }
        for (String partner : knownChannelNames) {
            if (partner == null || partner.equals(sourceChannel)) continue;
            String suffix = "_" + partner;
            if (base.endsWith(suffix)
                    && base.length() > prefix.length() + suffix.length()) {
                return true;
            }
        }
        return false;
    }

    private static double meanColumn(List<String[]> rows, int columnIndex) {
        double sum = 0.0;
        int n = 0;
        for (String[] row : rows) {
            double value = parseDouble(safeGet(row, columnIndex));
            if (!Double.isNaN(value)) {
                sum += value;
                n++;
            }
        }
        return n > 0 ? sum / n : Double.NaN;
    }

    private static double meanFiniteValue(List<String[]> rows, int columnIndex) {
        double sum = 0.0;
        int n = 0;
        for (String[] row : rows) {
            double value = parseDouble(safeGet(row, columnIndex));
            if (!Double.isNaN(value)) {
                sum += value;
                n++;
            }
        }
        return n > 0 ? sum / n : 0.0;
    }

    private static double modalIntegerValue(List<String[]> rows, int columnIndex) {
        Map<Integer, Integer> counts = new TreeMap<Integer, Integer>();
        for (String[] row : rows) {
            double parsed = parseDouble(safeGet(row, columnIndex));
            if (Double.isNaN(parsed)) continue;
            Integer value = Integer.valueOf((int) Math.round(parsed));
            Integer count = counts.get(value);
            counts.put(value, Integer.valueOf(count == null ? 1 : count.intValue() + 1));
        }

        int bestValue = 0;
        int bestCount = -1;
        for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
            if (entry.getValue().intValue() > bestCount) {
                bestValue = entry.getKey().intValue();
                bestCount = entry.getValue().intValue();
            }
        }
        return bestCount < 0 ? 0.0 : (double) bestValue;
    }

    private static ClassFractionAggregation classFractionValues(List<String[]> rows,
                                                                int columnIndex,
                                                                String outputPrefix) {
        LinkedHashMap<String, Double> values = new LinkedHashMap<String, Double>();
        if (rows == null || rows.isEmpty()) {
            return new ClassFractionAggregation(values, false, -1);
        }

        int[] counts = new int[MAX_TEXTURE_CLASS_FRACTION_COLUMNS];
        int valid = 0;
        int maxLabel = -1;
        boolean capped = false;

        for (String[] row : rows) {
            double parsed = parseDouble(safeGet(row, columnIndex));
            if (Double.isNaN(parsed)) continue;
            int label = (int) Math.round(parsed);
            if (label < 0) continue;
            valid++;
            if (label > maxLabel) {
                maxLabel = label;
            }
            if (label >= MAX_TEXTURE_CLASS_FRACTION_COLUMNS) {
                capped = true;
                continue;
            }
            counts[label]++;
        }

        if (valid == 0 || maxLabel < 0) {
            return new ClassFractionAggregation(values, false, maxLabel);
        }
        int k = maxLabel + 1;
        if (k > MAX_TEXTURE_CLASS_FRACTION_COLUMNS) {
            k = MAX_TEXTURE_CLASS_FRACTION_COLUMNS;
            capped = true;
        }
        for (int i = 0; i < k; i++) {
            values.put(outputPrefix + i, Double.valueOf(((double) counts[i]) / valid));
        }
        return new ClassFractionAggregation(values, capped, maxLabel);
    }

    private static final class ClassFractionAggregation {
        final LinkedHashMap<String, Double> values;
        final boolean capped;
        final int maxLabel;

        ClassFractionAggregation(LinkedHashMap<String, Double> values,
                                 boolean capped,
                                 int maxLabel) {
            this.values = values;
            this.capped = capped;
            this.maxLabel = maxLabel;
        }
    }

    private static final class ClassifiedIntensityCsv {
        final File file;
        final String channelName;
        final IntensitySpatialOutputMode mode;
        final boolean channelRoiMask;

        ClassifiedIntensityCsv(File file,
                               String channelName,
                               IntensitySpatialOutputMode mode,
                               boolean channelRoiMask) {
            this.file = file;
            this.channelName = channelName;
            this.mode = mode == null ? IntensitySpatialOutputMode.BASE : mode;
            this.channelRoiMask = channelRoiMask;
        }
    }

    private static final class IntensityAggregationBucket {
        final Set<String> allAnimals = new LinkedHashSet<String>();
        final LinkedHashMap<String, Map<String, LinkedHashMap<String, Double>>> channelData =
                new LinkedHashMap<String, Map<String, LinkedHashMap<String, Double>>>();
    }

    private static final class DynamicIntensityColumn {
        final int index;
        final String outputName;

        DynamicIntensityColumn(int index, String outputName) {
            this.index = index;
            this.outputName = outputName;
        }
    }

    // ------------------------------------------------------------ CSV writing

    private void writeMasterCsv(File outFile, List<String> columns,
                                Set<String> allAnimals,
                                LinkedHashMap<String, LinkedHashMap<String, Double>> table) {
        File tmpFile = null;
        boolean moved = false;
        try {
            File parent = outFile.getParentFile();
            if (parent != null) {
                IoUtils.mustMkdirs(parent);
            }
            tmpFile = File.createTempFile(outFile.getName() + ".", ".tmp", parent);
            PrintWriter pw = CsvSupport.newWriter(tmpFile);
            try {
                pw.println(CsvSupport.joinRow(csvSafeValues(columns)));

                for (String animal : allAnimals) {
                    List<String> vals = new ArrayList<String>();
                    vals.add(csvSafeCell(animal));

                    LinkedHashMap<String, Double> row = table.get(animal);
                    for (int c = 1; c < columns.size(); c++) {
                        String col = columns.get(c);
                        Double val = (row != null) ? row.get(col) : null;
                        vals.add(val != null ? fmt(val) : "");
                    }
                    pw.println(CsvSupport.joinRow(vals));
                }
            } finally {
                pw.close();
            }
            try {
                Files.move(tmpFile.toPath(), outFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmpFile.toPath(), outFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
            IJ.log("Saved: " + outFile.getAbsolutePath());
        } catch (IOException e) {
            IJ.log("Error writing " + outFile.getName() + ": " + e.getMessage());
        } finally {
            if (!moved && tmpFile != null) {
                try {
                    Files.deleteIfExists(tmpFile.toPath());
                } catch (IOException ignored) {
                }
            }
        }
    }

    // ----------------------------------------------------------------- Helpers

    private static List<String> csvSafeValues(List<String> values) {
        List<String> safe = new ArrayList<String>();
        if (values == null) return safe;
        for (String value : values) {
            safe.add(csvSafeCell(value));
        }
        return safe;
    }

    private static String csvSafeCell(String value) {
        if (value == null || value.isEmpty()) return value == null ? "" : value;
        char first = value.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@') {
            return "'" + value;
        }
        return value;
    }

    private static String safeGet(String[] arr, Integer idx) {
        if (idx == null || idx < 0 || idx >= arr.length) return "";
        return arr[idx];
    }

    private static int columnIndex(Map<String, Integer> colIdx, String preferred, String legacy) {
        Integer preferredIdx = colIdx.get(preferred);
        if (preferredIdx != null) return preferredIdx;
        if (legacy == null) return -1;
        Integer legacyIdx = colIdx.get(legacy);
        return legacyIdx == null ? -1 : legacyIdx;
    }

    /**
     * Parses a CSV cell as a double, returning {@link Double#NaN} for any
     * non-finite value (NaN, +Inf, -Inf) or unparseable text. Callers must
     * skip NaN before accumulating, otherwise sums and means are poisoned.
     */
    private static double parseDouble(String s) {
        if (s == null || s.trim().isEmpty()) return Double.NaN;
        try {
            double v = Double.parseDouble(s.trim());
            return Double.isFinite(v) ? v : Double.NaN;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private Set<String> loadObjectAnimalNames(String directory) {
        Set<String> animals = new LinkedHashSet<String>();
        for (File csvFile : objectCsvFiles(directory)) {
            animals.addAll(loadAnimalNames(csvFile));
        }
        return animals;
    }

    private static List<File> aggregationObjectCsvFiles(String directory) {
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
        List<File> files = new ArrayList<File>();
        Set<String> seenCanonicalPaths = new HashSet<String>();
        appendFirstNamedCsvs(files, seenCanonicalPaths,
                java.util.Collections.singletonList(layout.tablesObjectsWriteDir()), objectCsvFilter());
        appendFirstNamedCsvs(files, seenCanonicalPaths,
                java.util.Collections.singletonList(layout.tablesMorphometryWriteDir()), objectCsvFilter());
        appendFirstNamedCsvs(files, seenCanonicalPaths,
                java.util.Collections.singletonList(layout.tablesLineDistanceWriteDir()),
                objectCsvFilter());
        return files;
    }

    private static List<File> objectCsvFiles(String directory) {
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
        List<File> files = new ArrayList<File>();
        appendFirstNamedCsvs(files, new HashSet<String>(),
                java.util.Collections.singletonList(layout.tablesObjectsWriteDir()), objectCsvFilter());
        return files;
    }

    private static List<File> intensityCsvFiles(String directory) {
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
        List<File> files = new ArrayList<File>();
        appendFirstNamedCsvs(files, new HashSet<String>(),
                java.util.Collections.singletonList(layout.tablesIntensityWriteDir()), intensityCsvFilter());
        return files;
    }

    private static java.io.FilenameFilter objectCsvFilter() {
        return new java.io.FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
                if (!lower.endsWith(".csv")) return false;
                if (lower.startsWith("temp_") || lower.contains("temp_")) return false;
                if (lower.contains("analysis details")) return false;
                if (lower.contains("details")) return false;
                if (lower.contains("calibration")) return false;
                return true;
            }
        };
    }

    private static java.io.FilenameFilter intensityCsvFilter() {
        return new java.io.FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
                if (!lower.endsWith(".csv")) return false;
                if (lower.contains("analysis details")) return false;
                if (lower.contains("details")) return false;
                return true;
            }
        };
    }

    private static void appendFirstNamedCsvs(List<File> out,
                                            Set<String> seenCanonicalPaths,
                                            List<File> dirs,
                                            java.io.FilenameFilter filter) {
        Set<String> seenNamesForThisSource = new HashSet<String>();
        for (File dir : dirs) {
            if (dir == null || !dir.isDirectory()) continue;
            File[] csvFiles = dir.listFiles(filter);
            if (csvFiles == null || csvFiles.length == 0) continue;
            Arrays.sort(csvFiles, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return String.CASE_INSENSITIVE_ORDER.compare(a.getName(), b.getName());
                }
            });
            for (File csvFile : csvFiles) {
                String canonical = canonicalPath(csvFile);
                if (seenCanonicalPaths.contains(canonical)) continue;
                String nameKey = csvFile.getName().toLowerCase(Locale.ROOT);
                if (seenNamesForThisSource.contains(nameKey)) continue;
                seenCanonicalPaths.add(canonical);
                seenNamesForThisSource.add(nameKey);
                out.add(csvFile);
            }
        }
    }

    private static String aggregationChannelName(File csvFile) {
        String safeName = csvFile.getName();
        if (safeName.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            safeName = safeName.substring(0, safeName.length() - 4);
        }
        if (safeName.toLowerCase(Locale.ROOT).endsWith("_morphology")) {
            safeName = safeName.substring(0, safeName.length() - "_Morphology".length());
        }
        return ChannelFilenameCodec.toRaw(safeName);
    }

    private static String normalizeAggregationMetricHeader(String header) {
        if ("Area_um2".equals(header)) return "Morph_Area_um2";
        if ("Perimeter_um".equals(header)) return "Morph_Perimeter_um";
        if ("Circularity".equals(header)) return "Morph_Circularity";
        if ("Solidity".equals(header)) return "Morph_Solidity";
        if ("AspectRatio".equals(header)) return "Morph_AspectRatio";
        if ("Feret_um".equals(header)) return "Morph_Feret_um";
        if ("Extent".equals(header)) return "Morph_Extent";
        if ("ConvexHullArea_um2".equals(header)) return "Morph_ConvexHullArea_um2";
        return header;
    }

    private static void mergeChannelMetrics(
            LinkedHashMap<String, Map<String, LinkedHashMap<String, Double>>> channelData,
            String channelName,
            Map<String, LinkedHashMap<String, Double>> animalMetrics) {
        Map<String, LinkedHashMap<String, Double>> existing = channelData.get(channelName);
        if (existing == null) {
            channelData.put(channelName, animalMetrics);
            return;
        }
        for (Map.Entry<String, LinkedHashMap<String, Double>> entry : animalMetrics.entrySet()) {
            LinkedHashMap<String, Double> row = existing.get(entry.getKey());
            if (row == null) {
                existing.put(entry.getKey(), entry.getValue());
            } else {
                row.putAll(entry.getValue());
            }
        }
    }

    private static String canonicalPath(File file) {
        try {
            return file.getCanonicalPath().toLowerCase(Locale.ROOT);
        } catch (IOException e) {
            return file.getAbsolutePath().toLowerCase(Locale.ROOT);
        }
    }

    private Set<String> loadAnimalNames(File csvFile) {
        Set<String> animals = new LinkedHashSet<String>();
        if (csvFile == null) return animals;

        try {
            CsvSupport.RecordReader csv = CsvSupport.openRecordReader(csvFile);
            try {
                CsvSupport.Record headerRecord = csv.readRecord();
                if (headerRecord == null) return animals;

                String[] header = CsvSupport.parseRecord(headerRecord.text);
                Integer animalCol = null;
                for (int i = 0; i < header.length; i++) {
                    if ("Animal Name".equals(header[i].trim())) {
                        animalCol = i;
                        break;
                    }
                }
                if (animalCol == null) return animals;

                CsvSupport.Record record;
                while ((record = csv.readRecord()) != null) {
                    if (CsvSupport.isBlankRecord(record.text)) continue;
                    String animal = safeGet(CsvSupport.parseRecord(record.text), animalCol).trim();
                    if (!animal.isEmpty()) animals.add(animal);
                }
            } finally {
                csv.close();
            }
        } catch (IOException ignored) {
        }

        return animals;
    }

    private static int countOverlap(Set<String> left, Set<String> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) return 0;
        int overlap = 0;
        for (String value : left) {
            if (right.contains(value)) overlap++;
        }
        return overlap;
    }

    private static File firstExistingRoiTablesDir(String directory) {
        File root = new File(directory);
        for (File dir : RoiIO.attributesReadDirs(root)) {
            if (hasRoiPropertiesCsv(dir)) return dir;
        }
        for (File dir : RoiIO.attributesReadDirs(root)) {
            if (dir.isDirectory()) return dir;
        }
        return RoiIO.attributesWriteDir(root);
    }

    private static boolean hasRoiPropertiesCsv(File dir) {
        if (dir == null || !dir.isDirectory()) return false;
        File[] files = dir.listFiles(new java.io.FilenameFilter() {
            @Override
            public boolean accept(File parent, String name) {
                return name != null
                        && name.toLowerCase(Locale.ROOT).endsWith(".csv")
                        && name.toLowerCase(Locale.ROOT).contains("roi properties");
            }
        });
        return files != null && files.length > 0;
    }

    private static String resolveSectionKey(String[] row, Integer scnCol, Integer roiCol, Integer regionCol) {
        String scn = safeGet(row, scnCol).trim();
        if (!scn.isEmpty()) return scn;

        String roi = safeGet(row, roiCol).trim();
        if (!roi.isEmpty()) return roi;

        return safeGet(row, regionCol).trim();
    }

    /**
     * Returns the composite grouping key for a row given the requested
     * granularity. ANIMAL is a no-op; the other modes append a dash-separated
     * suffix derived from the row's Hemisphere / Region / SCN columns.
     */
    static String composeGroupKey(String animal,
                                  AggregationConfig.Granularity granularity,
                                  String[] row,
                                  Integer hemisphereCol,
                                  Integer regionCol,
                                  Integer scnCol,
                                  Integer roiCol) {
        if (granularity == null || granularity == AggregationConfig.Granularity.ANIMAL) {
            return animal;
        }
        String suffix;
        switch (granularity) {
            case HEMISPHERE:
                suffix = safeGet(row, hemisphereCol).trim();
                if (suffix.isEmpty()) {
                    suffix = parseHemisphereFromRoi(safeGet(row, roiCol));
                }
                if (suffix.isEmpty()) {
                    suffix = "Unknown";
                }
                break;
            case REGION:
                suffix = safeGet(row, regionCol).trim();
                if (suffix.isEmpty()) suffix = "UnknownRegion";
                break;
            case SECTION:
                suffix = resolveSectionKey(row, scnCol, roiCol, regionCol);
                if (suffix == null || suffix.isEmpty()) suffix = "UnknownSection";
                break;
            default:
                return animal;
        }
        return animal + "-" + suffix;
    }

    static String parseHemisphereFromRoi(String roi) {
        if (roi == null) return "";
        String trimmed = roi.trim();
        if (trimmed.isEmpty()) return "";
        String[] tokens = trimmed.split("[_\\s]+");
        for (int i = 0; i < tokens.length; i++) {
            String tok = tokens[i].trim().toUpperCase(Locale.ROOT);
            if ("LH".equals(tok)) return "LH";
            if ("RH".equals(tok)) return "RH";
        }
        String upper = trimmed.toUpperCase(Locale.ROOT);
        if (upper.startsWith("LH")) return "LH";
        if (upper.startsWith("RH")) return "RH";
        return "";
    }

    private static String extractVolumetricPartner(String header, String token) {
        int tokenIndex = header.indexOf(token);
        if (tokenIndex <= 0) return null;
        int rest = tokenIndex + token.length();
        while (rest < header.length() && Character.isDigit(header.charAt(rest))) rest++;
        if (rest >= header.length() || header.charAt(rest) != '_') return null;
        return header.substring(rest + 1);
    }

    private static String fmt(double val) {
        if (Double.isNaN(val) || Double.isInfinite(val)) return "0";
        if (val == Math.floor(val) && !Double.isInfinite(val) && Math.abs(val) < Long.MAX_VALUE) {
            return String.valueOf((long) val);
        }
        // Full precision — avoids truncating small values like Volume (mm^3)
        return Double.toString(val);
    }

}
