package flash.pipeline.analyses;

import ij.IJ;
import flash.pipeline.analyses.wizard.StatisticsPreset;
import flash.pipeline.analyses.wizard.StatisticsPresetIO;
import flash.pipeline.cli.CLIConfig;
import flash.pipeline.io.ConditionManifestIO;
import flash.pipeline.naming.ConditionAssignments;
import flash.pipeline.naming.ConditionAxis;
import flash.pipeline.io.CsvSupport;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.io.IoUtils;
import flash.pipeline.results.RunIdCsv;
import flash.pipeline.runrecord.AnalysisRunContext;
import flash.pipeline.runrecord.RunRecordAware;
import flash.pipeline.stats.MetricStatisticsEngine;
import flash.pipeline.stats.StatisticRow;
import flash.pipeline.ui.PipelineDialog;
import flash.pipeline.ui.wizard.ConditionManifestPanel;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Performs statistical testing on the aggregated master data produced by
 * {@link MasterAggregationAnalysis}.
 * <p>
 * For each numeric metric column the analysis:
 * <ol>
 *   <li>Tests normality per condition group (D'Agostino-Pearson omnibus)</li>
 *   <li>Runs a global test (ANOVA / Welch's t / Kruskal-Wallis / Mann-Whitney)</li>
 *   <li>Performs pairwise post-hoc comparisons with Bonferroni correction</li>
 * </ol>
 * Results are written to
 * {@code FLASH/Results/Tables/Project Summary/Statistics.csv}.
 * <p>
 * Parametric tests (Welch's t, ANOVA) are backed by Apache Commons Math.
 * Compatible with Java 8.
 */
public class StatisticalAnalysis implements Analysis, RunRecordAware {

    static final String SUPERPLOT_FILENAME = "SuperPlot.csv";
    private static final String SOURCE_OBJECTS = "3D Objects";
    private static final String SOURCE_INTENSITIES = "Image Intensities";

    private boolean headless = false;
    private boolean suppressDialogs = false;
    private AnalysisRunContext runRecordContext = null;
    private StatisticsConfig statisticsConfig = new StatisticsConfig();
    private CLIConfig cliConfig = null;

    @Override
    public void setHeadless(boolean headless) {
        this.headless = headless;
    }

    @Override
    public void setSuppressDialogs(boolean suppress) {
        this.suppressDialogs = suppress;
    }

    @Override
    public void setRunRecordContext(AnalysisRunContext context) {
        this.runRecordContext = context;
    }

    /**
     * Sets the routing configuration for tests. {@code null} resets to defaults
     * (unpaired, automatic distribution detection, Bonferroni post-hoc).
     */
    public void setStatisticsConfig(StatisticsConfig config) {
        this.statisticsConfig = config == null ? new StatisticsConfig() : config;
    }

    public StatisticsConfig getStatisticsConfig() {
        return statisticsConfig;
    }

    @Override
    public void setCliConfig(CLIConfig config) {
        this.cliConfig = config;
        if (config == null || config.getStats() == null) {
            return;
        }
        CLIConfig.StatsConfig src = config.getStats();
        if (!src.hasConfiguration()) {
            return;
        }
        StatisticsConfig resolved = resolveCliStatisticsConfig(config, src);
        if (resolved != null) {
            this.statisticsConfig = resolved;
        }
    }

    private static StatisticsConfig resolveCliStatisticsConfig(CLIConfig config,
                                                               CLIConfig.StatsConfig src) {
        StatisticsConfig target = new StatisticsConfig();
        String presetName = src.getPresetName();
        if (presetName != null && !presetName.trim().isEmpty()) {
            try {
                File projectRoot = config.getDirectory() == null
                        ? new File(System.getProperty("user.dir", "."))
                        : new File(config.getDirectory());
                StatisticsPreset preset = new StatisticsPresetIO(projectRoot)
                        .load(presetName.trim());
                target = preset.toConfig();
            } catch (IOException e) {
                IJ.log("[CLI] Warning: Could not load stats.preset '"
                        + presetName + "': " + e.getMessage());
            }
        }
        if (src.getPairedMode() != null) {
            target.pairedMode = src.getPairedMode();
        }
        if (src.getDistMode() != null) {
            target.distributionMode = src.getDistMode();
        }
        if (src.getPostHoc() != null) {
            target.postHocMethod = src.getPostHoc();
        }
        if (src.getMetrics() != null && !src.getMetrics().isEmpty()) {
            target.metricFilter = new ArrayList<String>(src.getMetrics());
        }
        if (src.getMetricAggregations() != null && !src.getMetricAggregations().isEmpty()) {
            for (Map.Entry<String, StatisticsConfig.MetricAggregation> entry
                    : src.getMetricAggregations().entrySet()) {
                target.putMetricAggregationOverride(entry.getKey(), entry.getValue());
            }
        }
        return target;
    }

    // ================================================================
    //  Analysis interface
    // ================================================================

    @Override
    public void execute(String directory) {
        IJ.log("=== Statistical Analysis ===");

        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);

        // 1. Load master CSVs
        File objectsCsv = existingProjectSummaryFile(layout, FlashProjectLayout.MASTER_OBJECTS_FILENAME);
        File intensitiesCsv = existingProjectSummaryFile(layout, FlashProjectLayout.MASTER_INTENSITIES_FILENAME);

        if (objectsCsv == null && intensitiesCsv == null) {
            notifyUser("Statistical Analysis",
                    "No master CSV files found in FLASH/Results/Tables/Project Summary/.\n"
                            + "Run Master Data Aggregation first.");
            return;
        }

        CsvData objData = objectsCsv != null ? parseMasterCsv(objectsCsv) : null;
        CsvData intData = intensitiesCsv != null ? parseMasterCsv(intensitiesCsv) : null;

        // Collect all unique source row names, then infer the parent animal unit.
        Set<String> allRowNames = new LinkedHashSet<String>();
        if (objData != null) allRowNames.addAll(objData.animals);
        if (intData != null) allRowNames.addAll(intData.animals);

        if (allRowNames.isEmpty()) {
            notifyUser("Statistical Analysis", "No animal data found in master CSVs.");
            return;
        }

        LinkedHashMap<String, String> rowToAnimal =
                inferAnimalUnits(allRowNames,
                        readPersistedConditionAssignments(directory),
                        shouldCollapseToAnimalUnit());
        Set<String> allAnimals = new LinkedHashSet<String>();
        allAnimals.addAll(rowToAnimal.values());

        // 2. Resolve condition assignments — interactive or unattended. In GUI mode,
        //    re-prompt the review dialog until at least two conditions exist instead
        //    of failing outright on a single collapsed group.
        Map<String, String> animalToCondition;
        List<String> conditionOrder;
        while (true) {
            animalToCondition = resolveConditionAssignments(directory, allAnimals);
            if (animalToCondition == null) {
                IJ.log("Statistical analysis cancelled by user.");
                recordWarn("Statistical analysis cancelled by user.");
                return;
            }
            // Re-read the manifest each pass so a just-saved review is reflected.
            applyParentConditionsFromNestedRows(animalToCondition,
                    readPersistedConditionAssignments(directory), rowToAnimal);

            // Multi-axis: optionally collapse to a single chosen condition axis so the
            // comparison groups by (say) Genotype alone instead of the full composite.
            // No-op for single-axis / "combined" projects (composite preserved).
            remapToConditionAxis(directory, animalToCondition, allAnimals,
                    statisticsConfig == null ? null : statisticsConfig.conditionAxisId);

            conditionOrder = orderedConditions(allAnimals, animalToCondition);
            if (conditionOrder.size() >= 2) {
                break;
            }
            if (!offerConditionReviewRetry(conditionOrder.size())) {
                return;
            }
        }

        // 3. Merge data from both CSVs
        LinkedHashMap<String, Map<String, Double>> mergedData =
                new LinkedHashMap<String, Map<String, Double>>();
        LinkedHashMap<String, Map<String, Double>> mergedRows =
                new LinkedHashMap<String, Map<String, Double>>();
        List<String> metricColumns = new ArrayList<String>();
        LinkedHashMap<String, String> metricSources = new LinkedHashMap<String, String>();

        if (objData != null) {
            for (String col : objData.columns) {
                if (isMetricColumn(col)) {
                    addMetricColumn(metricColumns, metricSources, col, SOURCE_OBJECTS);
                }
            }
        }
        if (intData != null) {
            for (String col : intData.columns) {
                if (isMetricColumn(col)) {
                    addMetricColumn(metricColumns, metricSources, col, SOURCE_INTENSITIES);
                }
            }
        }

        for (String animal : allRowNames) {
            Map<String, Double> row = new LinkedHashMap<String, Double>();
            if (objData != null && objData.data.containsKey(animal)) {
                row.putAll(objData.data.get(animal));
            }
            if (intData != null && intData.data.containsKey(animal)) {
                row.putAll(intData.data.get(animal));
            }
            mergedRows.put(animal, row);
        }

        AnimalMetricTable animalMetricTable =
                collapseRowsToAnimalMetrics(mergedRows, rowToAnimal, metricColumns,
                        statisticsConfig);
        mergedData.putAll(animalMetricTable.valuesByAnimal);
        LinkedHashMap<String, String> objectRunIdsByAnimal =
                collapseSourceRunIdsToAnimals(objData, rowToAnimal);
        LinkedHashMap<String, String> intensityRunIdsByAnimal =
                collapseSourceRunIdsToAnimals(intData, rowToAnimal);

        if (statisticsConfig != null
                && statisticsConfig.metricFilter != null
                && !statisticsConfig.metricFilter.isEmpty()) {
            Set<String> allowed = new LinkedHashSet<String>(statisticsConfig.metricFilter);
            List<String> filtered = new ArrayList<String>();
            for (String col : metricColumns) {
                if (allowed.contains(col)) filtered.add(col);
            }
            if (filtered.isEmpty()) {
                IJ.log("Warning: stats.metrics filter matched no columns; testing all metrics.");
                recordWarn("stats.metrics filter matched no columns; testing all metrics.");
            } else {
                metricColumns = filtered;
            }
        }
        LinkedHashMap<String, String> metricSourceRunIds =
                buildMetricSourceRunIds(metricColumns, metricSources,
                        objectRunIdsByAnimal, intensityRunIdsByAnimal);

        // 4. Run statistical tests for each metric
        List<StatisticRow> results = new ArrayList<StatisticRow>();
        LinkedHashMap<String, Boolean> metricIncludedInTest =
                new LinkedHashMap<String, Boolean>();
        LinkedHashMap<String, Set<String>> metricIncludedSuperPlotRows =
                new LinkedHashMap<String, Set<String>>();
        boolean pairedMode = isPairedMode(statisticsConfig);
        int tested = 0;
        int skipped = 0;

        for (String metric : metricColumns) {
            // Build per-condition value arrays
            LinkedHashMap<String, List<Double>> groups = pairedMode
                    ? buildPairedMetricGroups(metric, conditionOrder, allAnimals,
                            animalToCondition, mergedData, statisticsConfig)
                    : buildUnpairedMetricGroups(metric, conditionOrder, allAnimals,
                            animalToCondition, mergedData);

            // Check minimum group size (n >= 3 for every group)
            boolean tooSmall = false;
            StringBuilder skipReason = new StringBuilder();
            for (Map.Entry<String, List<Double>> ge : groups.entrySet()) {
                if (ge.getValue().size() < 3) {
                    tooSmall = true;
                    if (skipReason.length() > 0) skipReason.append("; ");
                    skipReason.append(ge.getKey()).append(" n=").append(ge.getValue().size());
                }
            }
            if (tooSmall) {
                skipped++;
                results.add(MetricStatisticsEngine.skippedRow(metric, skipReason.toString()));
                metricIncludedInTest.put(metric, Boolean.FALSE);
                continue;
            }

            tested++;
            try {
                results.addAll(MetricStatisticsEngine.analyseMetric(
                        metric, conditionOrder, groups, statisticsConfig));
                metricIncludedInTest.put(metric, Boolean.TRUE);
                if (pairedMode) {
                    metricIncludedSuperPlotRows.put(metric,
                            pairedIncludedSuperPlotRows(metric, conditionOrder, allAnimals,
                                    animalToCondition, mergedData, statisticsConfig));
                }
            } catch (IllegalArgumentException pairingMismatch) {
                tested--;
                skipped++;
                results.add(MetricStatisticsEngine.skippedRow(metric,
                        "paired pairs unequal: " + pairingMismatch.getMessage()));
                metricIncludedInTest.put(metric, Boolean.FALSE);
            }
        }

        // 5. Write output CSVs
        File outFile = layout.projectSummaryWriteFile(FlashProjectLayout.STATISTICS_FILENAME);
        writeStatisticsCsv(outFile, results, metricSourceRunIds);
        File superPlotFile = layout.projectSummaryWriteFile(SUPERPLOT_FILENAME);
        AnimalMetricTable superPlotMetricTable = animalMetricTable;
        Set<String> superPlotRows = allAnimals;
        Map<String, String> superPlotConditions = animalToCondition;
        Map<String, String> superPlotObjectRunIds = objectRunIdsByAnimal;
        Map<String, String> superPlotIntensityRunIds = intensityRunIdsByAnimal;
        if (pairedMode) {
            superPlotMetricTable = buildPairedSuperPlotTable(metricColumns, allAnimals,
                    animalToCondition, mergedData, statisticsConfig);
            superPlotRows = superPlotMetricTable.valuesByAnimal.keySet();
            superPlotConditions = superPlotMetricTable.conditionsByAnimal;
            superPlotObjectRunIds = buildPairedSourceRunIds(objData, allRowNames,
                    animalToCondition, statisticsConfig);
            superPlotIntensityRunIds = buildPairedSourceRunIds(intData, allRowNames,
                    animalToCondition, statisticsConfig);
        }
        writeSuperPlotCsv(superPlotFile, metricColumns, superPlotRows, superPlotConditions,
                superPlotMetricTable, metricSources, metricIncludedInTest,
                pairedMode ? metricIncludedSuperPlotRows : null,
                superPlotObjectRunIds, superPlotIntensityRunIds);

        IJ.log("Statistical analysis complete: " + tested + " metrics tested, "
                + skipped + " skipped (insufficient group size).");
        // Non-blocking completion signal. A modal dialog here would stall
        // unattended batch runs until a human clicked OK.
        IJ.log("Statistical Analysis saved: " + outFile.getName() + " and " + superPlotFile.getName());
        IJ.showStatus("Statistical Analysis finished.");
    }

    // (Statistical computation delegated to MetricStatisticsEngine)

    // ================================================================
    //  Animal-unit reshaping
    // ================================================================

    private boolean shouldCollapseToAnimalUnit() {
        StatisticsConfig cfg = statisticsConfig == null ? new StatisticsConfig() : statisticsConfig;
        return !isPairedMode(cfg);
    }

    private static boolean isPairedMode(StatisticsConfig cfg) {
        return cfg != null
                && cfg.pairedMode != null
                && cfg.pairedMode != StatisticsConfig.PairedMode.OFF;
    }

    /**
     * Collapse composite condition labels to a single chosen axis for per-axis
     * statistics. A no-op when {@code axisId} is blank or the project has no such
     * axis, so single-axis / "combined" comparisons are byte-identical to before.
     * Reads the N-axis model via {@link ConditionManifestIO#resolveAssignmentsModel}
     * — never parses the composite string.
     */
    static void remapToConditionAxis(String directory,
                                     Map<String, String> animalToCondition,
                                     Set<String> animals,
                                     String axisId) {
        if (axisId == null || axisId.trim().isEmpty()) return;
        if (animalToCondition == null || animals == null) return;
        ConditionAssignments model =
                ConditionManifestIO.resolveAssignmentsModel(directory, animals);
        if (model.axisById(axisId) == null) return;   // unknown axis -> keep composite
        for (String animal : animals) {
            String value = model.get(animal, axisId);
            animalToCondition.put(animal, value == null ? "" : value.trim());
        }
    }

    private static LinkedHashMap<String, List<Double>> buildUnpairedMetricGroups(
            String metric,
            List<String> conditionOrder,
            Set<String> allAnimals,
            Map<String, String> animalToCondition,
            LinkedHashMap<String, Map<String, Double>> mergedData) {
        LinkedHashMap<String, List<Double>> groups =
                new LinkedHashMap<String, List<Double>>();
        for (String cond : conditionOrder) {
            groups.put(cond, new ArrayList<Double>());
        }

        for (String animal : allAnimals) {
            String cond = animalToCondition.get(animal);
            if (cond == null || !groups.containsKey(cond)) continue;
            Map<String, Double> row = mergedData.get(animal);
            if (row == null) continue;
            Double val = row.get(metric);
            if (val != null && Double.isFinite(val.doubleValue())) {
                groups.get(cond).add(val);
            }
        }
        return groups;
    }

    private static LinkedHashMap<String, List<Double>> buildPairedMetricGroups(
            String metric,
            List<String> conditionOrder,
            Set<String> allRows,
            Map<String, String> rowToCondition,
            LinkedHashMap<String, Map<String, Double>> mergedData,
            StatisticsConfig cfg) {
        NameSuffixContext suffixContext = buildNameSuffixContext(allRows);
        LinkedHashMap<String, LinkedHashMap<String, MetricAccumulator>> byCondition =
                new LinkedHashMap<String, LinkedHashMap<String, MetricAccumulator>>();
        for (String cond : conditionOrder) {
            byCondition.put(cond, new LinkedHashMap<String, MetricAccumulator>());
        }

        List<String> subjectOrder = new ArrayList<String>();
        Set<String> seenSubjects = new LinkedHashSet<String>();
        for (String rowName : allRows) {
            String cond = rowToCondition.get(rowName);
            LinkedHashMap<String, MetricAccumulator> subjectValues = byCondition.get(cond);
            if (subjectValues == null) continue;
            Map<String, Double> row = mergedData.get(rowName);
            if (row == null) continue;
            Double val = row.get(metric);
            if (val == null || !Double.isFinite(val.doubleValue())) continue;

            String subject = pairedSubjectId(rowName,
                    cfg == null ? null : cfg.pairedMode, suffixContext);
            if (subject.isEmpty()) subject = rowName;
            if (seenSubjects.add(subject)) {
                subjectOrder.add(subject);
            }
            MetricAccumulator acc = subjectValues.get(subject);
            if (acc == null) {
                acc = new MetricAccumulator();
                subjectValues.put(subject, acc);
            }
            acc.add(val.doubleValue());
        }

        LinkedHashMap<String, List<Double>> groups =
                new LinkedHashMap<String, List<Double>>();
        for (String cond : conditionOrder) {
            groups.put(cond, new ArrayList<Double>());
        }

        for (String subject : subjectOrder) {
            boolean complete = true;
            for (String cond : conditionOrder) {
                LinkedHashMap<String, MetricAccumulator> subjectValues = byCondition.get(cond);
                MetricAccumulator acc = subjectValues == null ? null : subjectValues.get(subject);
                if (acc == null || acc.n <= 0) {
                    complete = false;
                    break;
                }
            }
            if (!complete) continue;

            for (String cond : conditionOrder) {
                groups.get(cond).add(byCondition.get(cond).get(subject)
                        .value(isSummedAnimalMetric(metric, cfg)));
            }
        }
        return groups;
    }

    private static Set<String> pairedIncludedSuperPlotRows(
            String metric,
            List<String> conditionOrder,
            Set<String> allRows,
            Map<String, String> rowToCondition,
            LinkedHashMap<String, Map<String, Double>> mergedData,
            StatisticsConfig cfg) {
        NameSuffixContext suffixContext = buildNameSuffixContext(allRows);
        LinkedHashMap<String, Set<String>> conditionsBySubject =
                new LinkedHashMap<String, Set<String>>();
        List<String> subjectOrder = new ArrayList<String>();
        Set<String> seenSubjects = new LinkedHashSet<String>();

        for (String rowName : allRows) {
            String condition = rowToCondition.get(rowName);
            if (condition == null || !conditionOrder.contains(condition)) continue;
            Map<String, Double> row = mergedData.get(rowName);
            if (row == null) continue;
            Double value = row.get(metric);
            if (value == null || !Double.isFinite(value.doubleValue())) continue;

            String subject = pairedSubjectId(rowName,
                    cfg == null ? null : cfg.pairedMode, suffixContext);
            if (subject.isEmpty()) subject = rowName;
            if (seenSubjects.add(subject)) {
                subjectOrder.add(subject);
            }
            Set<String> conditions = conditionsBySubject.get(subject);
            if (conditions == null) {
                conditions = new LinkedHashSet<String>();
                conditionsBySubject.put(subject, conditions);
            }
            conditions.add(condition);
        }

        Set<String> included = new LinkedHashSet<String>();
        for (String subject : subjectOrder) {
            Set<String> conditions = conditionsBySubject.get(subject);
            if (conditions == null) continue;
            boolean complete = true;
            for (String condition : conditionOrder) {
                if (!conditions.contains(condition)) {
                    complete = false;
                    break;
                }
            }
            if (!complete) continue;
            for (String condition : conditionOrder) {
                included.add(superPlotRowKey(subject, condition));
            }
        }
        return included;
    }

    private static String pairedSubjectId(String rowName,
                                          StatisticsConfig.PairedMode mode,
                                          NameSuffixContext suffixContext) {
        String key = rowName == null ? "" : rowName.trim();
        if (key.isEmpty()) return "";

        String extracted = ConditionManifestIO.extractAnimalName(key);
        if (!extracted.isEmpty() && !extracted.equals(key)
                && isUnderscoreHemisphereKey(key)) {
            return extracted;
        }

        int dash = key.lastIndexOf('-');
        if (dash > 0 && dash + 1 < key.length()) {
            String suffix = key.substring(dash + 1).trim();
            if (mode == StatisticsConfig.PairedMode.HEMISPHERE
                    && ("LH".equalsIgnoreCase(suffix) || "RH".equalsIgnoreCase(suffix))) {
                return key.substring(0, dash).trim();
            }
            if ((mode == StatisticsConfig.PairedMode.REGION
                    || mode == StatisticsConfig.PairedMode.SESSION)
                    && (isKnownAggregationSuffix(suffix)
                    || isRepeatedAggregateSuffix(suffix, suffixContext))) {
                return key.substring(0, dash).trim();
            }
        }
        return key;
    }

    private static void addMetricColumn(List<String> metricColumns,
                                        LinkedHashMap<String, String> metricSources,
                                        String column,
                                        String source) {
        if (!metricColumns.contains(column)) {
            metricColumns.add(column);
            metricSources.put(column, source);
            return;
        }
        String existing = metricSources.get(column);
        if (existing != null && !existing.equals(source)) {
            metricSources.put(column, "Multiple");
        }
    }

    private static LinkedHashMap<String, String> inferAnimalUnits(
            Set<String> rowNames,
            Map<String, String> persistedConditions,
            boolean collapseNestedRows) {
        NameSuffixContext suffixContext = buildNameSuffixContext(rowNames);
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        for (String rowName : rowNames) {
            out.put(rowName, inferAnimalUnit(rowName, persistedConditions,
                    collapseNestedRows, suffixContext));
        }
        return out;
    }

    private static String inferAnimalUnit(String rowName,
                                          Map<String, String> persistedConditions,
                                          boolean collapseNestedRows,
                                          NameSuffixContext suffixContext) {
        String key = rowName == null ? "" : rowName.trim();
        if (key.isEmpty() || !collapseNestedRows) return key;

        String extracted = ConditionManifestIO.extractAnimalName(key);
        if (!extracted.isEmpty() && !extracted.equals(key)
                && isUnderscoreHemisphereKey(key)) {
            return extracted;
        }

        int dash = key.lastIndexOf('-');
        if (dash > 0 && dash + 1 < key.length()) {
            String prefix = key.substring(0, dash).trim();
            String suffix = key.substring(dash + 1).trim();
            if (!prefix.isEmpty()) {
                if (persistedConditions != null && persistedConditions.containsKey(prefix)) {
                    return prefix;
                }
                if (isKnownAggregationSuffix(suffix)) {
                    return prefix;
                }
                if (isRepeatedAggregateSuffix(suffix, suffixContext)) {
                    return prefix;
                }
            }
        }

        return key;
    }

    private static boolean isUnderscoreHemisphereKey(String key) {
        if (key == null) return false;
        String[] tokens = key.split("_");
        return tokens.length >= 2
                && ("LH".equalsIgnoreCase(tokens[1].trim())
                || "RH".equalsIgnoreCase(tokens[1].trim()));
    }

    private static boolean isKnownAggregationSuffix(String suffix) {
        if (suffix == null) return false;
        String upper = suffix.trim().toUpperCase(Locale.ROOT);
        if (upper.isEmpty()) return false;
        if ("LH".equals(upper) || "RH".equals(upper)) return true;
        if ("UNKNOWN".equals(upper) || "UNKNOWNREGION".equals(upper)
                || "UNKNOWNSECTION".equals(upper)) return true;
        return upper.startsWith("SCN");
    }

    private static NameSuffixContext buildNameSuffixContext(Set<String> rowNames) {
        NameSuffixContext context = new NameSuffixContext();
        if (rowNames == null) return context;

        LinkedHashMap<String, Set<String>> prefixesBySuffix =
                new LinkedHashMap<String, Set<String>>();
        for (String rowName : rowNames) {
            String key = rowName == null ? "" : rowName.trim();
            int dash = key.lastIndexOf('-');
            if (dash <= 0 || dash + 1 >= key.length()) continue;
            String prefix = key.substring(0, dash).trim();
            String suffix = key.substring(dash + 1).trim();
            if (prefix.isEmpty() || suffix.isEmpty()) continue;
            String normalized = normalizeSuffix(suffix);
            Set<String> prefixes = prefixesBySuffix.get(normalized);
            if (prefixes == null) {
                prefixes = new LinkedHashSet<String>();
                prefixesBySuffix.put(normalized, prefixes);
            }
            prefixes.add(prefix);
        }

        for (Map.Entry<String, Set<String>> entry : prefixesBySuffix.entrySet()) {
            context.prefixCountBySuffix.put(entry.getKey(),
                    Integer.valueOf(entry.getValue().size()));
        }
        return context;
    }

    private static boolean isRepeatedAggregateSuffix(String suffix,
                                                     NameSuffixContext suffixContext) {
        if (!isAggregateSuffixShape(suffix) || suffixContext == null) return false;
        Integer count = suffixContext.prefixCountBySuffix.get(normalizeSuffix(suffix));
        return count != null && count.intValue() >= 2;
    }

    private static boolean isAggregateSuffixShape(String suffix) {
        if (suffix == null) return false;
        String trimmed = suffix.trim();
        if (trimmed.length() < 2) return false;
        boolean hasLetter = false;
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (Character.isLetter(ch)) hasLetter = true;
        }
        return hasLetter;
    }

    private static String normalizeSuffix(String suffix) {
        return suffix == null ? "" : suffix.trim().toUpperCase(Locale.ROOT);
    }

    private static void applyParentConditionsFromNestedRows(
            Map<String, String> animalToCondition,
            Map<String, String> persistedConditions,
            Map<String, String> rowToAnimal) {
        if (animalToCondition == null || persistedConditions == null
                || persistedConditions.isEmpty() || rowToAnimal == null) {
            return;
        }

        LinkedHashMap<String, String> inferred = new LinkedHashMap<String, String>();
        Set<String> conflicts = new LinkedHashSet<String>();
        for (Map.Entry<String, String> entry : rowToAnimal.entrySet()) {
            String rowName = entry.getKey();
            String animal = entry.getValue();
            if (rowName == null || animal == null || rowName.equals(animal)) continue;
            String condition = persistedConditions.get(rowName);
            if (condition == null || condition.trim().isEmpty()) continue;
            String existing = inferred.get(animal);
            if (existing == null) {
                inferred.put(animal, condition.trim());
            } else if (!existing.equals(condition.trim())) {
                conflicts.add(animal);
            }
        }

        for (String conflict : conflicts) {
            inferred.remove(conflict);
            IJ.log("Warning: nested condition rows disagree for animal '" + conflict
                    + "'; using existing condition resolution.");
        }

        for (Map.Entry<String, String> entry : inferred.entrySet()) {
            if (!persistedConditions.containsKey(entry.getKey())) {
                animalToCondition.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private static AnimalMetricTable collapseRowsToAnimalMetrics(
            LinkedHashMap<String, Map<String, Double>> rowsByName,
            Map<String, String> rowToAnimal,
            List<String> metricColumns,
            StatisticsConfig cfg) {
        LinkedHashMap<String, Map<String, MetricAccumulator>> accumulators =
                new LinkedHashMap<String, Map<String, MetricAccumulator>>();

        for (Map.Entry<String, Map<String, Double>> entry : rowsByName.entrySet()) {
            String rowName = entry.getKey();
            String animal = rowToAnimal.get(rowName);
            if (animal == null || animal.trim().isEmpty()) {
                animal = rowName;
            }
            Map<String, MetricAccumulator> animalAcc = accumulators.get(animal);
            if (animalAcc == null) {
                animalAcc = new LinkedHashMap<String, MetricAccumulator>();
                accumulators.put(animal, animalAcc);
            }

            Map<String, Double> values = entry.getValue();
            if (values == null) continue;
            for (String metric : metricColumns) {
                Double value = values.get(metric);
                if (value == null || !Double.isFinite(value.doubleValue())) continue;
                MetricAccumulator acc = animalAcc.get(metric);
                if (acc == null) {
                    acc = new MetricAccumulator();
                    animalAcc.put(metric, acc);
                }
                acc.add(value.doubleValue());
            }
        }

        AnimalMetricTable table = new AnimalMetricTable();
        for (Map.Entry<String, Map<String, MetricAccumulator>> animalEntry : accumulators.entrySet()) {
            String animal = animalEntry.getKey();
            LinkedHashMap<String, Double> values = new LinkedHashMap<String, Double>();
            LinkedHashMap<String, Integer> nestedCounts = new LinkedHashMap<String, Integer>();
            for (String metric : metricColumns) {
                MetricAccumulator acc = animalEntry.getValue().get(metric);
                if (acc == null || acc.n <= 0) continue;
                values.put(metric, Double.valueOf(acc.value(isSummedAnimalMetric(metric, cfg))));
                nestedCounts.put(metric, Integer.valueOf(acc.n));
            }
            table.valuesByAnimal.put(animal, values);
            table.nestedCountsByAnimal.put(animal, nestedCounts);
            table.displayNameByAnimal.put(animal, animal);
        }
        return table;
    }

    private static AnimalMetricTable buildPairedSuperPlotTable(
            List<String> metricColumns,
            Set<String> allRows,
            Map<String, String> rowToCondition,
            LinkedHashMap<String, Map<String, Double>> mergedData,
            StatisticsConfig cfg) {
        NameSuffixContext suffixContext = buildNameSuffixContext(allRows);
        LinkedHashMap<String, Map<String, MetricAccumulator>> accumulators =
                new LinkedHashMap<String, Map<String, MetricAccumulator>>();
        AnimalMetricTable table = new AnimalMetricTable();

        for (String rowName : allRows) {
            String condition = rowToCondition.get(rowName);
            if (condition == null || condition.trim().isEmpty()) continue;
            Map<String, Double> row = mergedData.get(rowName);
            if (row == null) continue;

            String subject = pairedSubjectId(rowName,
                    cfg == null ? null : cfg.pairedMode, suffixContext);
            if (subject.isEmpty()) subject = rowName;
            String rowKey = superPlotRowKey(subject, condition);

            Map<String, MetricAccumulator> rowAcc = accumulators.get(rowKey);
            if (rowAcc == null) {
                rowAcc = new LinkedHashMap<String, MetricAccumulator>();
                accumulators.put(rowKey, rowAcc);
                table.displayNameByAnimal.put(rowKey, subject);
                table.conditionsByAnimal.put(rowKey, condition);
            }

            for (String metric : metricColumns) {
                Double value = row.get(metric);
                if (value == null || !Double.isFinite(value.doubleValue())) continue;
                MetricAccumulator acc = rowAcc.get(metric);
                if (acc == null) {
                    acc = new MetricAccumulator();
                    rowAcc.put(metric, acc);
                }
                acc.add(value.doubleValue());
            }
        }

        for (Map.Entry<String, Map<String, MetricAccumulator>> entry : accumulators.entrySet()) {
            LinkedHashMap<String, Double> values = new LinkedHashMap<String, Double>();
            LinkedHashMap<String, Integer> nestedCounts = new LinkedHashMap<String, Integer>();
            for (String metric : metricColumns) {
                MetricAccumulator acc = entry.getValue().get(metric);
                if (acc == null || acc.n <= 0) continue;
                values.put(metric, Double.valueOf(acc.value(isSummedAnimalMetric(metric, cfg))));
                nestedCounts.put(metric, Integer.valueOf(acc.n));
            }
            table.valuesByAnimal.put(entry.getKey(), values);
            table.nestedCountsByAnimal.put(entry.getKey(), nestedCounts);
        }
        return table;
    }

    private static String superPlotRowKey(String subject, String condition) {
        return (subject == null ? "" : subject) + "\t" + (condition == null ? "" : condition);
    }

    private static LinkedHashMap<String, String> collapseSourceRunIdsToAnimals(
            CsvData data,
            Map<String, String> rowToAnimal) {
        LinkedHashMap<String, LinkedHashSet<String>> grouped =
                new LinkedHashMap<String, LinkedHashSet<String>>();
        if (data == null || data.runIdByAnimal == null) {
            return new LinkedHashMap<String, String>();
        }
        for (Map.Entry<String, String> entry : data.runIdByAnimal.entrySet()) {
            String rowName = entry.getKey();
            String animal = rowToAnimal == null ? rowName : rowToAnimal.get(rowName);
            if (animal == null || animal.trim().isEmpty()) {
                animal = rowName;
            }
            addRunId(grouped, animal, entry.getValue());
        }
        return joinRunIds(grouped);
    }

    private static LinkedHashMap<String, String> buildPairedSourceRunIds(
            CsvData data,
            Set<String> allRows,
            Map<String, String> rowToCondition,
            StatisticsConfig cfg) {
        LinkedHashMap<String, LinkedHashSet<String>> grouped =
                new LinkedHashMap<String, LinkedHashSet<String>>();
        if (data == null || data.runIdByAnimal == null || allRows == null) {
            return new LinkedHashMap<String, String>();
        }
        NameSuffixContext suffixContext = buildNameSuffixContext(allRows);
        for (String rowName : allRows) {
            if (!data.runIdByAnimal.containsKey(rowName)) continue;
            String condition = rowToCondition == null ? "" : rowToCondition.get(rowName);
            if (condition == null || condition.trim().isEmpty()) continue;
            String subject = pairedSubjectId(rowName,
                    cfg == null ? null : cfg.pairedMode, suffixContext);
            if (subject.isEmpty()) subject = rowName;
            addRunId(grouped, superPlotRowKey(subject, condition), data.runIdByAnimal.get(rowName));
        }
        return joinRunIds(grouped);
    }

    private static LinkedHashMap<String, String> buildMetricSourceRunIds(
            List<String> metricColumns,
            Map<String, String> metricSources,
            Map<String, String> objectRunIdsByAnimal,
            Map<String, String> intensityRunIdsByAnimal) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        if (metricColumns == null) {
            return out;
        }
        for (String metric : metricColumns) {
            LinkedHashSet<String> values = new LinkedHashSet<String>();
            String source = metricSources == null ? "" : metricSources.get(metric);
            if (sourceUsesObjects(source)) {
                collectRunIds(values, objectRunIdsByAnimal);
            }
            if (sourceUsesIntensities(source)) {
                collectRunIds(values, intensityRunIdsByAnimal);
            }
            out.put(metric, joinRunIds(values));
        }
        return out;
    }

    private static String sourceRunIdForMetric(Map<String, String> metricSourceRunIds, String metric) {
        if (metricSourceRunIds == null || metric == null) {
            return "";
        }
        String value = metricSourceRunIds.get(metric);
        return value == null ? "" : value;
    }

    private static String sourceRunIdForMetricAnimal(
            String metric,
            String animal,
            Map<String, String> metricSources,
            Map<String, String> objectRunIdsByAnimal,
            Map<String, String> intensityRunIdsByAnimal) {
        LinkedHashSet<String> values = new LinkedHashSet<String>();
        String source = metricSources == null ? "" : metricSources.get(metric);
        if (sourceUsesObjects(source)) {
            addRunId(values, objectRunIdsByAnimal == null ? "" : objectRunIdsByAnimal.get(animal));
        }
        if (sourceUsesIntensities(source)) {
            addRunId(values, intensityRunIdsByAnimal == null ? "" : intensityRunIdsByAnimal.get(animal));
        }
        return joinRunIds(values);
    }

    private static boolean sourceUsesObjects(String source) {
        return SOURCE_OBJECTS.equals(source) || "Multiple".equals(source);
    }

    private static boolean sourceUsesIntensities(String source) {
        return SOURCE_INTENSITIES.equals(source) || "Multiple".equals(source);
    }

    private static void collectRunIds(LinkedHashSet<String> out, Map<String, String> idsByRow) {
        if (out == null || idsByRow == null) {
            return;
        }
        for (String value : idsByRow.values()) {
            addRunId(out, value);
        }
    }

    private static void addRunId(Map<String, LinkedHashSet<String>> grouped,
                                 String key,
                                 String runId) {
        if (grouped == null || key == null || key.trim().isEmpty()) {
            return;
        }
        LinkedHashSet<String> values = grouped.get(key);
        if (values == null) {
            values = new LinkedHashSet<String>();
            grouped.put(key, values);
        }
        addRunId(values, runId);
    }

    private static void addRunId(LinkedHashSet<String> values, String runId) {
        if (values == null || runId == null || runId.trim().isEmpty()) {
            return;
        }
        String[] parts = runId.split(";");
        for (String part : parts) {
            String cleaned = part == null ? "" : part.trim();
            if (!cleaned.isEmpty()) {
                values.add(cleaned);
            }
        }
    }

    private static LinkedHashMap<String, String> joinRunIds(
            LinkedHashMap<String, LinkedHashSet<String>> grouped) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        if (grouped == null) {
            return out;
        }
        for (Map.Entry<String, LinkedHashSet<String>> entry : grouped.entrySet()) {
            out.put(entry.getKey(), joinRunIds(entry.getValue()));
        }
        return out;
    }

    private static String joinRunIds(LinkedHashSet<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) continue;
            if (out.length() > 0) out.append(';');
            out.append(value.trim());
        }
        return out.toString();
    }

    private static boolean isSummedAnimalMetric(String metric) {
        if (metric == null) return false;
        String lower = metric.toLowerCase(Locale.ROOT);
        if (lower.endsWith("_permm3")) return false;
        if (lower.indexOf('%') >= 0 || lower.contains("fraction")
                || lower.contains("ratio") || lower.contains("mean")) {
            return false;
        }
        return lower.endsWith("total") || lower.contains("count");
    }

    private static boolean isSummedAnimalMetric(String metric, StatisticsConfig cfg) {
        StatisticsConfig.MetricAggregation aggregation = cfg == null
                ? StatisticsConfig.MetricAggregation.AUTO
                : cfg.metricAggregationFor(metric);
        if (aggregation == StatisticsConfig.MetricAggregation.SUM) return true;
        if (aggregation == StatisticsConfig.MetricAggregation.MEAN) return false;
        return isSummedAnimalMetric(metric);
    }

    private static LinkedHashMap<String, String> readPersistedConditionAssignments(String directory) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        File manifest = ConditionManifestIO.getExistingFile(directory);
        if (manifest == null) return out;
        try {
            CsvSupport.RecordReader csv = CsvSupport.openRecordReader(manifest);
            try {
                CsvSupport.Record header = csv.readRecord();
                if (header == null) return out;
                CsvSupport.Record record;
                while ((record = csv.readRecord()) != null) {
                    if (CsvSupport.isBlankRecord(record.text)) continue;
                    String[] row = CsvSupport.parseRecord(record.text);
                    String animal = row.length > 0 ? row[0].trim() : "";
                    String condition = row.length > 1 ? row[1].trim() : "";
                    if (!animal.isEmpty() && !condition.isEmpty()) {
                        out.put(animal, condition);
                    }
                }
            } finally {
                csv.close();
            }
        } catch (IOException e) {
            IJ.log("Warning: could not read condition assignments for animal-unit inference: "
                    + e.getMessage());
        }
        return out;
    }

    private static final class AnimalMetricTable {
        final LinkedHashMap<String, Map<String, Double>> valuesByAnimal =
                new LinkedHashMap<String, Map<String, Double>>();
        final LinkedHashMap<String, Map<String, Integer>> nestedCountsByAnimal =
                new LinkedHashMap<String, Map<String, Integer>>();
        final LinkedHashMap<String, String> displayNameByAnimal =
                new LinkedHashMap<String, String>();
        final LinkedHashMap<String, String> conditionsByAnimal =
                new LinkedHashMap<String, String>();
    }

    private static final class NameSuffixContext {
        final LinkedHashMap<String, Integer> prefixCountBySuffix =
                new LinkedHashMap<String, Integer>();
    }

    private static final class MetricAccumulator {
        double sum = 0.0;
        int n = 0;

        void add(double value) {
            sum += value;
            n++;
        }

        double value(boolean summed) {
            if (n <= 0) return Double.NaN;
            return summed ? sum : sum / n;
        }
    }

    // ================================================================
    //  Condition resolution — interactive or unattended
    // ================================================================

    /**
     * Resolves condition assignments. In headless/suppressDialogs mode the
     * manifest (or auto-detection) is used directly. In interactive GUI mode
     * the editable table dialog is shown, and accepted edits are persisted
     * back to {@code Conditions.csv} for future CLI runs.
     */
    static List<String> orderedConditions(Set<String> allAnimals,
                                          Map<String, String> animalToCondition) {
        List<String> conditionOrder = new ArrayList<String>();
        Set<String> seen = new LinkedHashSet<String>();
        for (String animal : allAnimals) {
            String cond = animalToCondition.get(animal);
            if (cond != null && !cond.isEmpty() && seen.add(cond)) {
                conditionOrder.add(cond);
            }
        }
        return conditionOrder;
    }

    /**
     * When fewer than two conditions are present, decide whether to retry the
     * review. Unattended mode reports and gives up; GUI mode offers to reopen the
     * condition review so the user can split animals into at least two groups.
     *
     * @return {@code true} to retry condition review, {@code false} to abort.
     */
    private boolean offerConditionReviewRetry(int conditionCount) {
        String msg = "Statistics needs at least two conditions. Found: " + conditionCount + ".";
        if (isUnattendedMode(suppressDialogs, cliConfig, GraphicsEnvironment.isHeadless())) {
            notifyUser("Statistical Analysis", msg
                    + " Edit FLASH/Results/Tables/Project Summary/Conditions.csv to assign conditions.");
            return false;
        }
        String[] options = {"Review conditions", "Cancel statistics"};
        int choice = JOptionPane.showOptionDialog(
                null,
                "<html><body style='width:340px'>" + msg
                        + "<br><br>Assign animals to at least two experimental groups to run"
                        + " statistics.</body></html>",
                "Statistics needs at least two conditions",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                options,
                options[0]);
        return choice == 0;
    }

    Map<String, String> resolveConditionAssignments(String directory, Set<String> animals) {
        Map<String, String> resolved = ConditionManifestIO.resolveAssignments(directory, animals);

        if (isUnattendedMode(suppressDialogs, cliConfig, GraphicsEnvironment.isHeadless())) {
            IJ.log("Using " + (ConditionManifestIO.getExistingFile(directory) != null
                    ? "manifest" : "auto-detected") + " condition assignments (unattended mode).");
            return resolved;
        }

        return showConditionDialog(directory, animals, resolved);
    }

    private Map<String, String> showConditionDialog(final String directory,
                                                     final Set<String> animals,
                                                     final Map<String, String> prefill) {
        if (GraphicsEnvironment.isHeadless()) {
            return prefill;
        }
        final ConditionManifestPanel manifest = new ConditionManifestPanel(animals, prefill);

        PipelineDialog pd = new PipelineDialog("Statistical Analysis \u2014 Condition Assignment", PipelineDialog.Phase.EXPORT);
        pd.addComponent(buildStatisticsPresetRow(directory));
        final JComboBox<StatsAxisChoice> axisCombo = buildConditionAxisCombo(directory);
        if (axisCombo != null) {
            javax.swing.JPanel axisRow =
                    new javax.swing.JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
            axisRow.setOpaque(false);
            axisRow.add(new javax.swing.JLabel("Group statistics by:"));
            axisRow.add(axisCombo);
            pd.addComponent(axisRow);
        }
        pd.addComponent(manifest.getComponent());

        if (!pd.showDialog()) return null;

        if (axisCombo != null) {
            Object selected = axisCombo.getSelectedItem();
            if (statisticsConfig != null) {
                statisticsConfig.conditionAxisId =
                        selected instanceof StatsAxisChoice ? ((StatsAxisChoice) selected).axisId : null;
            }
        }

        LinkedHashMap<String, String> assignments = manifest.collectAssignments();
        // The panel only edits the composite "Condition"; preserve a per-axis
        // manifest rather than collapsing it. Single-axis projects persist as before.
        try {
            ConditionManifestIO.saveAssignmentsPreservingMultiAxis(directory, assignments);
        } catch (Exception e) {
            IJ.log("Warning: could not save condition assignments: " + e.getMessage());
        }
        return assignments;
    }

    /**
     * Combo listing the condition axes for a multi-axis project (first entry
     * "(combined)" = group by the full composite). Returns {@code null} for
     * single-axis projects, where there is nothing to choose.
     */
    private JComboBox<StatsAxisChoice> buildConditionAxisCombo(String directory) {
        ConditionAssignments model = ConditionManifestIO.readAssignmentsModel(directory);
        List<ConditionAxis> axes = model.axes();
        if (axes.size() < 2) return null;
        List<StatsAxisChoice> choices = new ArrayList<StatsAxisChoice>();
        choices.add(new StatsAxisChoice(null, "(combined \u2014 all axes)"));
        for (ConditionAxis a : axes) {
            choices.add(new StatsAxisChoice(a.id,
                    a.label == null || a.label.trim().isEmpty() ? a.id : a.label));
        }
        JComboBox<StatsAxisChoice> combo =
                new JComboBox<StatsAxisChoice>(choices.toArray(new StatsAxisChoice[choices.size()]));
        String current = statisticsConfig == null ? null : statisticsConfig.conditionAxisId;
        if (current != null && !current.trim().isEmpty()) {
            for (StatsAxisChoice c : choices) {
                if (current.equals(c.axisId)) {
                    combo.setSelectedItem(c);
                    break;
                }
            }
        }
        combo.setMaximumSize(new Dimension(260, 24));
        combo.setToolTipText(
                "Multi-axis projects: compare by a single condition axis, or the full combination.");
        return combo;
    }

    /** Combo entry for the per-axis statistics chooser ({@code axisId == null} = combined). */
    private static final class StatsAxisChoice {
        final String axisId;
        final String label;

        StatsAxisChoice(String axisId, String label) {
            this.axisId = axisId;
            this.label = label;
        }

        @Override public String toString() {
            return label;
        }
    }

    private JComponent buildStatisticsPresetRow(final String directory) {
        final JComboBox<String> presetCombo =
                new JComboBox<String>(listStatisticsPresetNames(directory));
        presetCombo.setMaximumSize(new Dimension(260, 24));
        presetCombo.setToolTipText(
                "Apply a saved Statistics preset to populate paired/distribution/post-hoc choices.");

        final JButton savePreset = new JButton("Save as preset...");
        flash.pipeline.ui.FlashIcons.apply(savePreset, flash.pipeline.ui.FlashIcons.save());
        savePreset.setToolTipText("Save the current statistics configuration as a named preset.");
        savePreset.addActionListener(e -> handleSaveAsPreset(directory, presetCombo));

        javax.swing.JPanel row =
                new javax.swing.JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
        row.setOpaque(false);
        row.add(presetCombo);
        row.add(savePreset);

        presetCombo.addActionListener(e -> {
            Object selected = presetCombo.getSelectedItem();
            if (selected != null && !"(choose preset)".equals(String.valueOf(selected))) {
                applyPresetByName(directory, String.valueOf(selected));
            }
        });
        return row;
    }

    private String[] listStatisticsPresetNames(String directory) {
        List<String> labels = new ArrayList<String>();
        labels.add("(choose preset)");
        try {
            List<StatisticsPreset> presets =
                    new StatisticsPresetIO(new File(directory)).listAll();
            for (StatisticsPreset preset : presets) {
                labels.add(preset.getName());
            }
        } catch (IOException e) {
            IJ.log("WARNING: Could not list Statistics presets: " + e.getMessage());
        }
        return labels.toArray(new String[labels.size()]);
    }

    private void applyPresetByName(String directory, String presetName) {
        try {
            StatisticsPreset preset = new StatisticsPresetIO(new File(directory)).load(presetName);
            this.statisticsConfig = preset.toConfig();
            logHelperApplied("preset '" + presetName + "'", this.statisticsConfig);
        } catch (IOException e) {
            IJ.showMessage("Statistical Analysis",
                    "Could not load Statistics preset '" + presetName + "': " + e.getMessage());
        }
    }

    private void handleSaveAsPreset(String directory, JComboBox<String> presetCombo) {
        if (!canShowGuiDialog(suppressDialogs, cliConfig, GraphicsEnvironment.isHeadless())) return;
        String defaultName = "My Statistics Preset";
        String name = JOptionPane.showInputDialog(null,
                "Preset name:",
                "Save Statistics Preset",
                JOptionPane.PLAIN_MESSAGE);
        if (name == null) return;
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            trimmed = defaultName;
        }
        StatisticsConfig cfg = this.statisticsConfig == null
                ? new StatisticsConfig() : this.statisticsConfig;
        StatisticsPreset preset = new StatisticsPreset(
                trimmed,
                "Saved from Statistical Analysis dialog",
                cfg.pairedMode,
                cfg.distributionMode,
                cfg.postHocMethod,
                cfg.metricFilter,
                cfg.metricAggregationOverrides,
                cfg.conditionAxisId);
        try {
            new StatisticsPresetIO(new File(directory)).save(preset);
            IJ.log("Saved Statistics preset: " + trimmed);
            String[] refreshed = listStatisticsPresetNames(directory);
            presetCombo.removeAllItems();
            for (String label : refreshed) {
                presetCombo.addItem(label);
            }
            presetCombo.setSelectedItem(trimmed);
        } catch (IOException e) {
            IJ.showMessage("Statistical Analysis",
                    "Could not save preset: " + e.getMessage());
        }
    }

    private void logHelperApplied(String source, StatisticsConfig cfg) {
        StringBuilder sb = new StringBuilder();
        sb.append("Helper applied (").append(source).append("): paired=")
          .append(cfg.pairedMode == null ? "OFF" : cfg.pairedMode.name())
          .append(", distribution=")
          .append(cfg.distributionMode == null ? "AUTO" : cfg.distributionMode.name())
          .append(", posthoc=")
          .append(cfg.postHocMethod == null ? "BONFERRONI" : cfg.postHocMethod.name());
        if (cfg.metricFilter != null && !cfg.metricFilter.isEmpty()) {
            sb.append(", metrics=").append(cfg.metricFilter);
        }
        if (cfg.metricAggregationOverrides != null
                && !cfg.metricAggregationOverrides.isEmpty()) {
            sb.append(", metricAggregation=").append(cfg.metricAggregationOverrides);
        }
        IJ.log(sb.toString());
    }

    /**
     * Logs the message always. Shows a modal dialog only when not headless
     * and not suppressing dialogs.
     */
    private void notifyUser(String title, String message) {
        IJ.log(message.replace('\n', ' '));
        recordWarn(message.replace('\n', ' '));
        if (canShowGuiDialog(suppressDialogs, cliConfig, GraphicsEnvironment.isHeadless())) {
            IJ.showMessage(title, message);
        }
    }

    static boolean canShowGuiDialog(boolean suppressDialogs,
                                    CLIConfig cliConfig,
                                    boolean runtimeHeadless) {
        return !suppressDialogs && cliConfig == null && !runtimeHeadless;
    }

    static boolean isUnattendedMode(boolean suppressDialogs,
                                    CLIConfig cliConfig,
                                    boolean runtimeHeadless) {
        return !canShowGuiDialog(suppressDialogs, cliConfig, runtimeHeadless);
    }

    private static File existingProjectSummaryFile(FlashProjectLayout layout, String fileName) {
        File file = layout.projectSummaryWriteFile(fileName);
        return file.isFile() ? file : null;
    }

    // ================================================================
    //  CSV output
    // ================================================================

    private void writeStatisticsCsv(File outFile, List<StatisticRow> results,
                                    Map<String, String> metricSourceRunIds) {
        try {
            File parent = outFile.getParentFile();
            if (parent != null) {
                IoUtils.mustMkdirs(parent);
            }
            PrintWriter pw = CsvSupport.newWriter(outFile);
            try {
                pw.println(CsvSupport.joinRow(RunIdCsv.appendSourceAndRunIdHeader(Arrays.asList(
                        "Metric",
                        "Test",
                        "Statistic",
                        "p-value",
                        "Significant",
                        "NormalityResult",
                        "Group1",
                        "Group2",
                        "PairwiseTest",
                        "PairwiseStatistic",
                        "PairwisePValue",
                        "CorrectedPValue",
                        "Significance",
                        "Notes",
                        "Paired",
                        "PostHocMethod",
                        "InferentialUnit",
                        "Group1NAnimals",
                        "Group2NAnimals",
                        "TotalNAnimals",
                        "Group1Mean",
                        "Group2Mean",
                        "EffectSizeType",
                        "EffectSize",
                        "EffectCI95Low",
                        "EffectCI95High"))));

                for (StatisticRow r : results) {
                    List<String> row = new ArrayList<String>();
                    row.add(r.metric);
                    row.add(r.test);
                    row.add(Double.isNaN(r.statistic) ? "" : fmtStat(r.statistic));
                    row.add(Double.isNaN(r.pValue) ? "" : fmtP(r.pValue));
                    row.add(r.significant);
                    row.add(r.normalityResult);
                    row.add(r.group1);
                    row.add(r.group2);
                    row.add(r.pairwiseTest);
                    row.add(Double.isNaN(r.pairwiseStatistic) ? "" : fmtStat(r.pairwiseStatistic));
                    row.add(Double.isNaN(r.pairwisePValue) ? "" : fmtP(r.pairwisePValue));
                    row.add(Double.isNaN(r.correctedPValue) ? "" : fmtP(r.correctedPValue));
                    row.add(r.significance);
                    row.add(r.notes);
                    row.add("Skipped".equals(r.test) ? "" : (r.paired ? "Yes" : "No"));
                    row.add(r.postHocMethod == null ? "" : r.postHocMethod);
                    row.add(r.inferentialUnit == null ? "" : r.inferentialUnit);
                    row.add(r.group1NAnimals > 0 ? String.valueOf(r.group1NAnimals) : "");
                    row.add(r.group2NAnimals > 0 ? String.valueOf(r.group2NAnimals) : "");
                    row.add(r.totalNAnimals > 0 ? String.valueOf(r.totalNAnimals) : "");
                    row.add(Double.isNaN(r.group1Mean) ? "" : fmtStat(r.group1Mean));
                    row.add(Double.isNaN(r.group2Mean) ? "" : fmtStat(r.group2Mean));
                    row.add(r.effectSizeType == null ? "" : r.effectSizeType);
                    row.add(Double.isNaN(r.effectSize) ? "" : fmtStat(r.effectSize));
                    row.add(Double.isNaN(r.effectCI95Low) ? "" : fmtStat(r.effectCI95Low));
                    row.add(Double.isNaN(r.effectCI95High) ? "" : fmtStat(r.effectCI95High));
                    pw.println(CsvSupport.joinRow(RunIdCsv.appendSourceAndRunIdRow(row,
                            sourceRunIdForMetric(metricSourceRunIds, r.metric), currentRunId())));
                }
            } finally {
                pw.close();
            }
            IJ.log("Saved: " + outFile.getAbsolutePath());
            recordOutput(outFile, "csv");
        } catch (IOException e) {
            IJ.log("Error writing " + outFile.getName() + ": " + e.getMessage());
            recordError("Error writing " + outFile.getName(), e);
        }
    }

    private void writeSuperPlotCsv(File outFile,
                                   List<String> metricColumns,
                                   Set<String> allAnimals,
                                   Map<String, String> animalToCondition,
                                   AnimalMetricTable animalMetricTable,
                                   Map<String, String> metricSources,
                                   Map<String, Boolean> metricIncludedInTest,
                                   Map<String, Set<String>> metricIncludedRows,
                                   Map<String, String> objectRunIdsByAnimal,
                                   Map<String, String> intensityRunIdsByAnimal) {
        try {
            File parent = outFile.getParentFile();
            if (parent != null) {
                IoUtils.mustMkdirs(parent);
            }
            PrintWriter pw = CsvSupport.newWriter(outFile);
            try {
                pw.println(CsvSupport.joinRow(RunIdCsv.appendSourceAndRunIdHeader(Arrays.asList(
                        "Metric",
                        "SourceTable",
                        "AnimalName",
                        "Condition",
                        "Value",
                        "InferentialUnit",
                        "NestedRowCount",
                        "IncludedInTest",
                        "GroupNAnimals",
                        "GroupMean",
                        "GroupSD",
                        "GroupSEM"))));

                Map<String, Map<String, GroupSummary>> summaries =
                        buildSuperPlotSummaries(metricColumns, allAnimals,
                                animalToCondition, animalMetricTable);

                for (String metric : metricColumns) {
                    String source = metricSources == null ? "" : metricSources.get(metric);
                    Boolean included = metricIncludedInTest == null
                            ? Boolean.FALSE : metricIncludedInTest.get(metric);
                    Set<String> includedRows = metricIncludedRows == null
                            ? null : metricIncludedRows.get(metric);
                    for (String animal : allAnimals) {
                        Map<String, Double> values = animalMetricTable.valuesByAnimal.get(animal);
                        if (values == null) continue;
                        Double value = values.get(metric);
                        if (value == null || !Double.isFinite(value.doubleValue())) continue;

                        String condition = animalToCondition.get(animal);
                        Map<String, Integer> counts =
                                animalMetricTable.nestedCountsByAnimal.get(animal);
                        Integer nested = counts == null ? null : counts.get(metric);
                        String displayName = animalMetricTable.displayNameByAnimal.get(animal);
                        if (displayName == null || displayName.trim().isEmpty()) {
                            displayName = animal;
                        }
                        GroupSummary summary = null;
                        Map<String, GroupSummary> metricSummaries = summaries.get(metric);
                        if (metricSummaries != null) {
                            summary = metricSummaries.get(condition);
                        }

                        List<String> row = new ArrayList<String>();
                        row.add(metric);
                        row.add(source == null ? "" : source);
                        row.add(displayName);
                        row.add(condition == null ? "" : condition);
                        row.add(fmtStat(value.doubleValue()));
                        row.add("Animal");
                        row.add(nested == null ? "" : String.valueOf(nested.intValue()));
                        boolean includedInTest = includedRows == null
                                ? Boolean.TRUE.equals(included)
                                : includedRows.contains(animal);
                        row.add(includedInTest ? "Yes" : "No");
                        row.add(summary == null ? "" : String.valueOf(summary.n));
                        row.add(summary == null || Double.isNaN(summary.mean) ? "" : fmtStat(summary.mean));
                        row.add(summary == null || Double.isNaN(summary.sd) ? "" : fmtStat(summary.sd));
                        row.add(summary == null || Double.isNaN(summary.sem) ? "" : fmtStat(summary.sem));
                        pw.println(CsvSupport.joinRow(RunIdCsv.appendSourceAndRunIdRow(row,
                                sourceRunIdForMetricAnimal(metric, animal, metricSources,
                                        objectRunIdsByAnimal, intensityRunIdsByAnimal),
                                currentRunId())));
                    }
                }
            } finally {
                pw.close();
            }
            IJ.log("Saved: " + outFile.getAbsolutePath());
            recordOutput(outFile, "csv");
        } catch (IOException e) {
            IJ.log("Error writing " + outFile.getName() + ": " + e.getMessage());
            recordError("Error writing " + outFile.getName(), e);
        }
    }

    private static Map<String, Map<String, GroupSummary>> buildSuperPlotSummaries(
            List<String> metricColumns,
            Set<String> allAnimals,
            Map<String, String> animalToCondition,
            AnimalMetricTable animalMetricTable) {
        LinkedHashMap<String, Map<String, GroupSummary>> out =
                new LinkedHashMap<String, Map<String, GroupSummary>>();
        for (String metric : metricColumns) {
            LinkedHashMap<String, List<Double>> valuesByCondition =
                    new LinkedHashMap<String, List<Double>>();
            for (String animal : allAnimals) {
                String condition = animalToCondition.get(animal);
                if (condition == null || condition.trim().isEmpty()) continue;
                Map<String, Double> values = animalMetricTable.valuesByAnimal.get(animal);
                if (values == null) continue;
                Double value = values.get(metric);
                if (value == null || !Double.isFinite(value.doubleValue())) continue;
                List<Double> valuesForCondition = valuesByCondition.get(condition);
                if (valuesForCondition == null) {
                    valuesForCondition = new ArrayList<Double>();
                    valuesByCondition.put(condition, valuesForCondition);
                }
                valuesForCondition.add(value);
            }
            LinkedHashMap<String, GroupSummary> summaries =
                    new LinkedHashMap<String, GroupSummary>();
            for (Map.Entry<String, List<Double>> entry : valuesByCondition.entrySet()) {
                summaries.put(entry.getKey(), GroupSummary.from(entry.getValue()));
            }
            out.put(metric, summaries);
        }
        return out;
    }

    private static final class GroupSummary {
        final int n;
        final double mean;
        final double sd;
        final double sem;

        private GroupSummary(int n, double mean, double sd, double sem) {
            this.n = n;
            this.mean = mean;
            this.sd = sd;
            this.sem = sem;
        }

        static GroupSummary from(List<Double> values) {
            if (values == null || values.isEmpty()) {
                return new GroupSummary(0, Double.NaN, Double.NaN, Double.NaN);
            }
            double sum = 0.0;
            for (Double value : values) {
                sum += value.doubleValue();
            }
            double mean = sum / values.size();
            double sd = Double.NaN;
            double sem = Double.NaN;
            if (values.size() > 1) {
                double ss = 0.0;
                for (Double value : values) {
                    double diff = value.doubleValue() - mean;
                    ss += diff * diff;
                }
                sd = Math.sqrt(ss / (values.size() - 1));
                sem = sd / Math.sqrt(values.size());
            }
            return new GroupSummary(values.size(), mean, sd, sem);
        }
    }

    // ================================================================
    //  CSV parsing (same pattern as MasterAggregationAnalysis)
    // ================================================================

    private static class CsvData {
        List<String> columns = new ArrayList<String>();
        List<String> animals = new ArrayList<String>();
        LinkedHashMap<String, Map<String, Double>> data =
                new LinkedHashMap<String, Map<String, Double>>();
        LinkedHashMap<String, String> runIdByAnimal =
                new LinkedHashMap<String, String>();
    }

    private CsvData parseMasterCsv(File csvFile) {
        CsvData result = new CsvData();
        AnalysisRunContext.InputHandle inputHandle = recordInputStart(csvFile);
        long inputStarted = System.currentTimeMillis();
        String inputStatus = "processed";
        try {
            CsvSupport.RecordReader csv = CsvSupport.openRecordReader(csvFile);
            try {
                CsvSupport.Record headerRecord = csv.readRecord();
                if (headerRecord == null) {
                    inputStatus = "skipped";
                    return result;
                }
                String[] header = CsvSupport.parseRecord(headerRecord.text);
                for (String h : header) {
                    result.columns.add(h.trim());
                }

                int animalIdx = result.columns.indexOf("AnimalName");
                if (animalIdx < 0) {
                    IJ.log("  'AnimalName' column not found in " + csvFile.getName());
                    recordWarn("'AnimalName' column not found in " + csvFile.getName());
                    inputStatus = "skipped";
                    return result;
                }
                int runIdIdx = result.columns.indexOf(RunIdCsv.RUN_ID_COLUMN);
                if (runIdIdx < 0) {
                    IJ.log("  Source CSV lacks run_id; source_run_id will be empty for rows from "
                            + csvFile.getName());
                }

                CsvSupport.Record record;
                while ((record = csv.readRecord()) != null) {
                    if (CsvSupport.isBlankRecord(record.text)) continue;
                    String[] row = CsvSupport.parseRecord(record.text);

                    String animal = safeGet(row, animalIdx).trim();
                    if (animal.isEmpty()) continue;
                    result.animals.add(animal);
                    result.runIdByAnimal.put(animal, runIdIdx >= 0 ? safeGet(row, runIdIdx).trim() : "");

                    Map<String, Double> rowData = new LinkedHashMap<String, Double>();
                    for (int c = 0; c < result.columns.size(); c++) {
                        if (c == animalIdx) continue;
                        if (isRunLineageColumn(result.columns.get(c))) continue;
                        String val = safeGet(row, c).trim();
                        if (!val.isEmpty()) {
                            try {
                                double d = Double.parseDouble(val);
                                if (!Double.isNaN(d) && !Double.isInfinite(d)) {
                                    rowData.put(result.columns.get(c), d);
                                }
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                    result.data.put(animal, rowData);
                }
            } finally {
                csv.close();
            }
        } catch (IOException e) {
            IJ.log("Error reading " + csvFile.getName() + ": " + e.getMessage());
            inputStatus = "failed";
            recordError("Error reading " + csvFile.getName(), e);
        } finally {
            recordInputEnd(inputHandle, inputStatus, inputStarted);
        }
        return result;
    }

    // ================================================================
    //  Helpers
    // ================================================================

    private AnalysisRunContext.InputHandle recordInputStart(File file) {
        if (runRecordContext == null || file == null) {
            return null;
        }
        return runRecordContext.recordInputStart(file, -1, null);
    }

    private void recordInputEnd(AnalysisRunContext.InputHandle inputHandle,
                                String status,
                                long startedAtMillis) {
        if (runRecordContext != null && inputHandle != null) {
            runRecordContext.recordInputEnd(inputHandle, status,
                    Math.max(0L, System.currentTimeMillis() - startedAtMillis));
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

    private static boolean isMetricColumn(String col) {
        if (col == null) return false;
        if (isRunLineageColumn(col)) return false;
        if (col.equals("AnimalName")) return false;
        if (col.equals("Condition")) return false;
        if (col.equals("numSections")) return false;
        if (col.contains("numSections")) return false;
        if (col.equals("numZSlices")) return false;
        if (col.contains("numZSlices")) return false;
        return true;
    }

    private static boolean isRunLineageColumn(String col) {
        if (col == null) return false;
        String cleaned = col.trim();
        return RunIdCsv.RUN_ID_COLUMN.equals(cleaned)
                || RunIdCsv.SOURCE_RUN_ID_COLUMN.equals(cleaned);
    }

    private static String safeGet(String[] arr, int idx) {
        if (idx < 0 || idx >= arr.length) return "";
        return arr[idx];
    }

    private static String fmtStat(double val) {
        if (Double.isNaN(val) || Double.isInfinite(val)) return "";
        return String.format(Locale.US, "%.6f", val);
    }

    private static String fmtP(double val) {
        if (Double.isNaN(val) || Double.isInfinite(val)) return "";
        if (val < 0.0001) return String.format(Locale.US, "%.2e", val);
        return String.format(Locale.US, "%.6f", val);
    }
}
