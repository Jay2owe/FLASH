package flash.pipeline.qc;

import ij.IJ;
import flash.pipeline.io.ConditionManifestIO;
import flash.pipeline.io.CsvSupport;
import flash.pipeline.io.DeferredImageSupplier;
import flash.pipeline.io.LifIO;
import flash.pipeline.io.OrientationManifestIO;
import flash.pipeline.io.SeriesMeta;
import flash.pipeline.naming.ConditionNameParser;
import flash.pipeline.naming.ImageNameParser;
import flash.pipeline.zslice.ZSliceConfig;
import flash.pipeline.zslice.ZSliceConfigIO;
import flash.pipeline.zslice.ZSliceRange;
import loci.formats.FormatTools;
import loci.formats.ImageReader;
import loci.formats.Memoizer;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Selects one lowest-intensity and one highest-intensity series per condition
 * for Set Up Configuration QC using sampled Z-planes from the QC-active channels.
 */
public final class QcMinMaxPerConditionSelector {

    public static final String SCORES_FILE_NAME = "QC_MinMaxPerCondition_Selection.csv";
    public static final String CACHE_FILE_NAME = "QC_MinMaxPerCondition_Cache.properties";

    private static final String CACHE_VERSION = "1";
    private static final String SCORE_METHOD = "sampled_max_projection_brightest_1_percent_mean_v1";
    private static final int MAX_SAMPLED_PLANES = 10;
    private static final double BRIGHTEST_FRACTION = 0.01;
    private static final long MIN_FREE_BYTES = 500L * 1024 * 1024;

    private static final String KEY_CACHE_VERSION = "cache.version";
    private static final String KEY_SCORE_METHOD = "score.method";
    private static final String KEY_MAX_SAMPLED_PLANES = "sampling.max_planes";
    private static final String KEY_LIF_NAME = "lif.name";
    private static final String KEY_LIF_LENGTH = "lif.length";
    private static final String KEY_LIF_MODIFIED = "lif.modified";
    private static final String KEY_CONDITIONS_EXISTS = "conditions.exists";
    private static final String KEY_CONDITIONS_LENGTH = "conditions.length";
    private static final String KEY_CONDITIONS_MODIFIED = "conditions.modified";
    private static final String KEY_ORIENTATION_EXISTS = "orientation.exists";
    private static final String KEY_ORIENTATION_LENGTH = "orientation.length";
    private static final String KEY_ORIENTATION_MODIFIED = "orientation.modified";
    private static final String KEY_QC_CHANNEL_SIGNATURE = "qc.channels";
    private static final String KEY_Z_SLICE_SIGNATURE = "zslice.signature";
    private static final String KEY_SOURCE_SERIES_SIGNATURE = "source.series.signature";
    private static final String KEY_SELECTION_SCOPE = "selection.scope";
    private static final String SELECTION_SCOPE_PER_CONDITION = "per_condition";
    private static final String SELECTION_SCOPE_OVERALL = "overall";

    private QcMinMaxPerConditionSelector() {}

    public static final class SelectionResult {
        public final List<Integer> selectedSeriesIndexes;
        public final List<SelectedSeries> selectedSeries;
        public final File scoresFile;
        public final File cacheFile;
        public final boolean usedCache;
        public final boolean recomputed;
        public final String message;

        SelectionResult(List<Integer> selectedSeriesIndexes, File scoresFile, File cacheFile,
                        boolean usedCache, boolean recomputed, String message) {
            this(selectedSeriesIndexes, null, scoresFile, cacheFile, usedCache, recomputed, message);
        }

        SelectionResult(List<Integer> selectedSeriesIndexes, List<SelectedSeries> selectedSeries,
                        File scoresFile, File cacheFile,
                        boolean usedCache, boolean recomputed, String message) {
            this.selectedSeriesIndexes = selectedSeriesIndexes == null
                    ? new ArrayList<Integer>()
                    : new ArrayList<Integer>(selectedSeriesIndexes);
            this.selectedSeries = selectedSeries == null
                    ? new ArrayList<SelectedSeries>()
                    : new ArrayList<SelectedSeries>(selectedSeries);
            this.scoresFile = scoresFile;
            this.cacheFile = cacheFile;
            this.usedCache = usedCache;
            this.recomputed = recomputed;
            this.message = message;
        }
    }

    public static final class SelectedSeries {
        public final int seriesIndex;
        public final int seriesNumber;
        public final String seriesName;
        public final String animalName;
        public final String conditionName;
        public final String selectedRole;

        SelectedSeries(ScoreRecord record) {
            this(record, null);
        }

        SelectedSeries(ScoreRecord record, String selectedRoleOverride) {
            QcSelectionCandidate candidate = record == null ? null : record.candidate;
            this.seriesIndex = candidate == null ? -1 : candidate.seriesIndex;
            this.seriesNumber = candidate == null ? 0 : candidate.seriesNumber;
            this.seriesName = candidate == null ? "" : safe(candidate.seriesName);
            this.animalName = candidate == null ? "" : safe(candidate.animalName);
            this.conditionName = candidate == null ? "" : safe(candidate.conditionName);
            this.selectedRole = selectedRoleOverride == null
                    ? (record == null ? "" : safe(record.selectedRole))
                    : safe(selectedRoleOverride);
        }
    }

    static final class ScoreRecord {
        final QcSelectionCandidate candidate;
        final LinkedHashMap<Integer, Double> channelScores = new LinkedHashMap<Integer, Double>();
        double compositeRank = Double.NaN;
        String selectedRole = "";
        double rankSum = 0.0;
        int rankCount = 0;

        ScoreRecord(QcSelectionCandidate candidate) {
            this.candidate = candidate;
        }
    }

    public static final class MetadataAssignment {
        public final String animalName;
        public final String conditionName;

        public MetadataAssignment(String animalName, String conditionName) {
            this.animalName = animalName == null ? "" : animalName.trim();
            this.conditionName = conditionName == null ? "" : conditionName.trim();
        }
    }

    private static final class LoadedCandidate {
        final QcSelectionCandidate candidate;
        final LinkedHashMap<Integer, float[]> projections;

        LoadedCandidate(QcSelectionCandidate candidate, LinkedHashMap<Integer, float[]> projections) {
            this.candidate = candidate;
            this.projections = projections;
        }
    }

    private static final class ConditionSelection {
        final String conditionName;
        final ScoreRecord min;
        final ScoreRecord max;
        final double maxBrightness;

        ConditionSelection(String conditionName, ScoreRecord min, ScoreRecord max) {
            this.conditionName = safe(conditionName);
            this.min = min;
            this.max = max;
            this.maxBrightness = brightestFiniteChannelScore(max == null ? min : max);
        }
    }

    private enum SelectionScope {
        PER_CONDITION(SELECTION_SCOPE_PER_CONDITION, "min/max per condition"),
        OVERALL(SELECTION_SCOPE_OVERALL, "overall min/max");

        final String cacheValue;
        final String logLabel;

        SelectionScope(String cacheValue, String logLabel) {
            this.cacheValue = cacheValue;
            this.logLabel = logLabel;
        }
    }

    public static SelectionResult selectMinMaxPerCondition(String directory, File binFolder,
                                                           File lifFile,
                                                           List<QcSelectionChannel> qcChannels,
                                                           ZSliceConfig zSliceConfig,
                                                           boolean forceRecompute,
                                                           int parallelThreads) throws Exception {
        return selectMinMaxPerCondition(directory, binFolder, lifFile, qcChannels, zSliceConfig,
                forceRecompute, parallelThreads, null);
    }

    public static SelectionResult selectMinMaxPerCondition(String directory, File binFolder,
                                                           File lifFile,
                                                           List<QcSelectionChannel> qcChannels,
                                                           ZSliceConfig zSliceConfig,
                                                           boolean forceRecompute,
                                                           int parallelThreads,
                                                           Map<Integer, MetadataAssignment> reviewedMetadata)
            throws Exception {
        List<SeriesMeta> metas = LifIO.readAllSeriesMetadata(lifFile);
        return selectMinMaxPerCondition(
                directory, binFolder, lifFile, null, metas, qcChannels, zSliceConfig,
                forceRecompute, parallelThreads, reviewedMetadata);
    }

    public static SelectionResult selectMinMaxPerCondition(String directory, File binFolder,
                                                           File lifFile,
                                                           DeferredImageSupplier supplier,
                                                           List<SeriesMeta> metas,
                                                           List<QcSelectionChannel> qcChannels,
                                                           ZSliceConfig zSliceConfig,
                                                           boolean forceRecompute,
                                                           int parallelThreads,
                                                           Map<Integer, MetadataAssignment> reviewedMetadata)
            throws Exception {
        return selectMinMax(
                SelectionScope.PER_CONDITION, directory, binFolder, lifFile, supplier, metas,
                qcChannels, zSliceConfig, forceRecompute, parallelThreads, reviewedMetadata);
    }

    public static SelectionResult selectMinMaxOverall(String directory, File binFolder,
                                                      File lifFile,
                                                      List<QcSelectionChannel> qcChannels,
                                                      ZSliceConfig zSliceConfig,
                                                      boolean forceRecompute,
                                                      int parallelThreads) throws Exception {
        return selectMinMaxOverall(directory, binFolder, lifFile, qcChannels, zSliceConfig,
                forceRecompute, parallelThreads, null);
    }

    public static SelectionResult selectMinMaxOverall(String directory, File binFolder,
                                                      File lifFile,
                                                      List<QcSelectionChannel> qcChannels,
                                                      ZSliceConfig zSliceConfig,
                                                      boolean forceRecompute,
                                                      int parallelThreads,
                                                      Map<Integer, MetadataAssignment> reviewedMetadata)
            throws Exception {
        List<SeriesMeta> metas = LifIO.readAllSeriesMetadata(lifFile);
        return selectMinMaxOverall(
                directory, binFolder, lifFile, null, metas, qcChannels, zSliceConfig,
                forceRecompute, parallelThreads, reviewedMetadata);
    }

    public static SelectionResult selectMinMaxOverall(String directory, File binFolder,
                                                      File lifFile,
                                                      DeferredImageSupplier supplier,
                                                      List<SeriesMeta> metas,
                                                      List<QcSelectionChannel> qcChannels,
                                                      ZSliceConfig zSliceConfig,
                                                      boolean forceRecompute,
                                                      int parallelThreads,
                                                      Map<Integer, MetadataAssignment> reviewedMetadata)
            throws Exception {
        return selectMinMax(
                SelectionScope.OVERALL, directory, binFolder, lifFile, supplier, metas,
                qcChannels, zSliceConfig, forceRecompute, parallelThreads, reviewedMetadata);
    }

    private static SelectionResult selectMinMax(SelectionScope scope,
                                                String directory, File binFolder,
                                                File lifFile,
                                                DeferredImageSupplier supplier,
                                                List<SeriesMeta> metas,
                                                List<QcSelectionChannel> qcChannels,
                                                ZSliceConfig zSliceConfig,
                                                boolean forceRecompute,
                                                int parallelThreads,
                                                Map<Integer, MetadataAssignment> reviewedMetadata)
            throws Exception {
        if (scope == null) scope = SelectionScope.PER_CONDITION;
        if (binFolder != null && !binFolder.isDirectory()) {
            flash.pipeline.io.IoUtils.mustMkdirs(binFolder);
        }

        File scoresFile = new File(binFolder, SCORES_FILE_NAME);
        File cacheFile = new File(binFolder, CACHE_FILE_NAME);
        if (metas == null) {
            metas = LifIO.readAllSeriesMetadata(lifFile);
        }
        String sourceSeriesSignature = sourceSeriesSignature(lifFile, supplier, metas);
        List<QcSelectionCandidate> candidates = buildCandidates(directory, metas, reviewedMetadata);
        if (candidates.isEmpty()) {
            return new SelectionResult(new ArrayList<Integer>(), scoresFile, cacheFile,
                    false, false,
                    "Min/max QC could not find any usable image series.\n"
                            + "No QC images will be opened.");
        }

        List<QcSelectionChannel> activeChannels = new ArrayList<QcSelectionChannel>();
        if (qcChannels != null) activeChannels.addAll(qcChannels);
        if (activeChannels.isEmpty()) {
            return new SelectionResult(new ArrayList<Integer>(), scoresFile, cacheFile,
                    false, false,
                    "No QC-active channels were available for min/max assessment.");
        }

        if (!forceRecompute && isCacheValid(directory, lifFile, activeChannels, zSliceConfig,
                sourceSeriesSignature, scope.cacheValue, cacheFile, scoresFile)) {
            List<ScoreRecord> cached = readScoresCsv(scoresFile);
            if (!cached.isEmpty()) {
                List<SelectedSeries> selectedSeries = assignAndSelectSeries(cached, activeChannels, scope);
                List<Integer> selected = selectedSeriesIndexesFromSelection(selectedSeries);
                if (!selected.isEmpty()) {
                    logSelectionSummary(cached, activeChannels, true, scope);
                    String message = "Using cached " + scope.logLabel + " selection.\n"
                            + "Cache: " + scoresFile.getAbsolutePath();
                    return new SelectionResult(selected, selectedSeries, scoresFile, cacheFile,
                            true, false, message);
                }
            }
            IJ.log("QC min/max cache was present but unusable. Recomputing.");
        }

        IJ.log("QC " + scope.logLabel + ": scanning sampled planes from .lif");
        List<ScoreRecord> scored = scoreCandidates(
                lifFile, supplier, candidates, activeChannels, zSliceConfig, parallelThreads);
        List<SelectedSeries> selectedSeries = assignAndSelectSeries(scored, activeChannels, scope);
        logSelectionSummary(scored, activeChannels, false, scope);
        writeScoresCsv(scoresFile, scored, activeChannels);
        writeCacheProperties(directory, lifFile, activeChannels, zSliceConfig,
                sourceSeriesSignature, scope.cacheValue, cacheFile);

        List<Integer> selected = selectedSeriesIndexesFromSelection(selectedSeries);
        String message;
        if (scope == SelectionScope.OVERALL) {
            message = "Computed overall min/max selection (" + selected.size() + " image"
                    + (selected.size() == 1 ? "" : "s") + ").\n"
                    + "Saved cache: " + scoresFile.getAbsolutePath();
        } else {
            int nConditions = countSelectedConditions(scored);
            message = "Computed min/max per condition for " + nConditions + " condition"
                    + (nConditions == 1 ? "" : "s") + ".\n"
                    + "Saved cache: " + scoresFile.getAbsolutePath();
        }
        return new SelectionResult(selected, selectedSeries, scoresFile, cacheFile,
                false, true, message);
    }

    public static List<QcSelectionCandidate> buildCandidates(String directory,
                                                             List<SeriesMeta> metas) {
        return buildCandidates(directory, metas, null);
    }

    public static List<QcSelectionCandidate> buildCandidates(
            String directory,
            List<SeriesMeta> metas,
            Map<Integer, MetadataAssignment> reviewedMetadata) {
        List<QcSelectionCandidate> candidates = new ArrayList<QcSelectionCandidate>();
        if (metas == null || metas.isEmpty()) return candidates;

        List<SeriesMeta> usableMetas = new ArrayList<SeriesMeta>();
        Set<String> animals = new LinkedHashSet<String>();
        List<String> animalNames = new ArrayList<String>();
        for (SeriesMeta meta : metas) {
            if (ImageNameParser.isPreviewSeriesName(meta == null ? null : meta.name)) {
                continue;
            }
            usableMetas.add(meta);
            String animalName = extractAnimalName(meta);
            animalNames.add(animalName);
            animals.add(animalName);
        }

        Map<String, String> assignments = ConditionManifestIO.resolveAssignments(directory, animals);
        for (int i = 0; i < usableMetas.size(); i++) {
            SeriesMeta meta = usableMetas.get(i);
            String animalName = animalNames.get(i);
            String conditionName = assignments.get(animalName);
            MetadataAssignment reviewed = reviewedMetadata == null ? null : reviewedMetadata.get(meta.index);
            if (reviewed != null && !reviewed.animalName.isEmpty()) {
                animalName = reviewed.animalName;
                conditionName = reviewed.conditionName;
            }
            if (conditionName == null || conditionName.trim().isEmpty()) {
                conditionName = ConditionNameParser.detectCondition(animalName);
            }
            if (conditionName == null || conditionName.trim().isEmpty()) {
                conditionName = animalName;
            }

            candidates.add(new QcSelectionCandidate(
                    meta.index,
                    safeSeriesName(meta),
                    animalName,
                    conditionName));
        }

        return candidates;
    }

    static List<Integer> selectSampledZIndices(int nSlices, int maxPlanes) {
        List<Integer> indices = new ArrayList<Integer>();
        if (nSlices <= 0 || maxPlanes <= 0) return indices;

        int target = Math.min(nSlices, maxPlanes);
        if (target == nSlices) {
            for (int z = 0; z < nSlices; z++) indices.add(z);
            return indices;
        }

        LinkedHashSet<Integer> unique = new LinkedHashSet<Integer>();
        if (target == 1) {
            unique.add(Math.max(0, nSlices / 2));
        } else {
            double step = (double) (nSlices - 1) / (double) (target - 1);
            for (int i = 0; i < target; i++) {
                int z = (int) Math.round(i * step);
                if (z < 0) z = 0;
                if (z >= nSlices) z = nSlices - 1;
                unique.add(z);
            }
        }

        indices.addAll(unique);
        Collections.sort(indices);
        return indices;
    }

    static List<Integer> resolveSampledZIndices(ZSliceConfig zSliceConfig, int seriesIndex, int totalSlices) {
        ZSliceRange range = (zSliceConfig == null) ? null : zSliceConfig.getRange(seriesIndex);
        if (range == null || !range.isValidFor(totalSlices)) {
            return selectSampledZIndices(totalSlices, MAX_SAMPLED_PLANES);
        }

        List<Integer> relative = selectSampledZIndices(range.count(), MAX_SAMPLED_PLANES);
        List<Integer> absolute = new ArrayList<Integer>(relative.size());
        int offset = range.startSlice - 1;
        for (Integer z : relative) {
            if (z == null) continue;
            absolute.add(offset + z);
        }
        return absolute;
    }

    static void assignSelectionRoles(List<ScoreRecord> records, List<QcSelectionChannel> qcChannels) {
        if (records == null) return;
        resetSelectionRoles(records);

        LinkedHashMap<String, List<ScoreRecord>> byCondition = new LinkedHashMap<String, List<ScoreRecord>>();
        for (ScoreRecord record : records) {
            if (record == null || record.candidate == null) continue;
            List<ScoreRecord> group = byCondition.get(record.candidate.conditionName);
            if (group == null) {
                group = new ArrayList<ScoreRecord>();
                byCondition.put(record.candidate.conditionName, group);
            }
            group.add(record);
        }

        assignSelectionRolesForGroups(new ArrayList<List<ScoreRecord>>(byCondition.values()), qcChannels);
    }

    static void assignOverallSelectionRoles(List<ScoreRecord> records, List<QcSelectionChannel> qcChannels) {
        if (records == null) return;
        resetSelectionRoles(records);
        List<ScoreRecord> group = new ArrayList<ScoreRecord>();
        for (ScoreRecord record : records) {
            if (record != null && record.candidate != null) group.add(record);
        }
        assignSelectionRolesForGroups(
                Collections.singletonList(group),
                qcChannels);
    }

    private static void resetSelectionRoles(List<ScoreRecord> records) {
        if (records == null) return;
        for (ScoreRecord record : records) {
            if (record == null) continue;
            record.compositeRank = Double.NaN;
            record.selectedRole = "";
            record.rankSum = 0.0;
            record.rankCount = 0;
        }
    }

    private static void assignSelectionRolesForGroups(List<List<ScoreRecord>> groups,
                                                      List<QcSelectionChannel> qcChannels) {
        if (groups == null || qcChannels == null) return;
        for (List<ScoreRecord> group : groups) {
            if (group == null || group.isEmpty()) continue;

            for (QcSelectionChannel channel : qcChannels) {
                final int channelNumber = channel.channelNumber;
                List<ScoreRecord> valid = new ArrayList<ScoreRecord>();
                for (ScoreRecord record : group) {
                    Double score = record.channelScores.get(channelNumber);
                    if (score != null && !score.isNaN()) valid.add(record);
                }

                Collections.sort(valid, new Comparator<ScoreRecord>() {
                    @Override
                    public int compare(ScoreRecord a, ScoreRecord b) {
                        Double sa = a.channelScores.get(channelNumber);
                        Double sb = b.channelScores.get(channelNumber);
                        int cmp = Double.compare(sa, sb);
                        if (cmp != 0) return cmp;
                        return Integer.compare(a.candidate.seriesIndex, b.candidate.seriesIndex);
                    }
                });

                for (int i = 0; i < valid.size(); i++) {
                    ScoreRecord record = valid.get(i);
                    record.rankSum += (i + 1);
                    record.rankCount++;
                }
            }

            List<ScoreRecord> ranked = new ArrayList<ScoreRecord>();
            for (ScoreRecord record : group) {
                if (record.rankCount > 0) {
                    record.compositeRank = record.rankSum / record.rankCount;
                    ranked.add(record);
                }
            }
            if (ranked.isEmpty()) continue;

            Collections.sort(ranked, new Comparator<ScoreRecord>() {
                @Override
                public int compare(ScoreRecord a, ScoreRecord b) {
                    int cmp = Double.compare(a.compositeRank, b.compositeRank);
                    if (cmp != 0) return cmp;
                    return Integer.compare(a.candidate.seriesIndex, b.candidate.seriesIndex);
                }
            });

            ScoreRecord min = ranked.get(0);
            ScoreRecord max = ranked.get(ranked.size() - 1);
            if (min == max) {
                min.selectedRole = "MIN_MAX";
            } else {
                min.selectedRole = "MIN";
                max.selectedRole = "MAX";
            }
        }
    }

    static List<Integer> selectedSeriesIndexes(List<ScoreRecord> records) {
        return selectedSeriesIndexesFromSelection(selectedSeries(records));
    }

    static List<SelectedSeries> selectedSeries(List<ScoreRecord> records) {
        List<ConditionSelection> orderedConditions = orderedConditionSelections(records);
        List<SelectedSeries> selected = new ArrayList<SelectedSeries>();
        LinkedHashSet<Integer> seen = new LinkedHashSet<Integer>();
        for (ConditionSelection condition : orderedConditions) {
            addSelectedSeries(selected, seen, condition.max);
            addSelectedSeries(selected, seen, condition.min);
        }
        return selected;
    }

    static List<SelectedSeries> selectedSeriesOverall(List<ScoreRecord> records,
                                                      List<QcSelectionChannel> qcChannels) {
        assignOverallSelectionRoles(records, qcChannels);
        return overallSelectedSeriesFromAssignedRoles(records);
    }

    private static List<SelectedSeries> assignAndSelectSeries(List<ScoreRecord> records,
                                                              List<QcSelectionChannel> qcChannels,
                                                              SelectionScope scope) {
        if (scope == SelectionScope.OVERALL) {
            return selectedSeriesOverall(records, qcChannels);
        }
        assignSelectionRoles(records, qcChannels);
        return selectedSeries(records);
    }

    private static List<SelectedSeries> overallSelectedSeriesFromAssignedRoles(List<ScoreRecord> records) {
        ScoreRecord min = null;
        ScoreRecord max = null;
        if (records != null) {
            for (ScoreRecord record : records) {
                if (record == null || record.candidate == null) continue;
                if ("MIN_MAX".equals(record.selectedRole)) {
                    min = record;
                    max = record;
                } else if ("MIN".equals(record.selectedRole)) {
                    min = record;
                } else if ("MAX".equals(record.selectedRole)) {
                    max = record;
                }
            }
        }

        List<SelectedSeries> selected = new ArrayList<SelectedSeries>();
        LinkedHashSet<Integer> seen = new LinkedHashSet<Integer>();
        if (min != null && min == max) {
            addSelectedSeries(selected, seen, max, "OVERALL_MIN_MAX");
        } else {
            addSelectedSeries(selected, seen, max, "OVERALL_MAX");
            addSelectedSeries(selected, seen, min, "OVERALL_MIN");
        }
        return selected;
    }

    private static List<ConditionSelection> orderedConditionSelections(List<ScoreRecord> records) {
        LinkedHashMap<String, List<ScoreRecord>> byCondition = new LinkedHashMap<String, List<ScoreRecord>>();
        for (ScoreRecord record : records) {
            if (record == null || record.candidate == null) continue;
            List<ScoreRecord> group = byCondition.get(record.candidate.conditionName);
            if (group == null) {
                group = new ArrayList<ScoreRecord>();
                byCondition.put(record.candidate.conditionName, group);
            }
            group.add(record);
        }

        List<ConditionSelection> selections = new ArrayList<ConditionSelection>();
        for (Map.Entry<String, List<ScoreRecord>> entry : byCondition.entrySet()) {
            ScoreRecord min = null;
            ScoreRecord max = null;
            for (ScoreRecord record : entry.getValue()) {
                if ("MIN".equals(record.selectedRole) || "MIN_MAX".equals(record.selectedRole)) min = record;
                if ("MAX".equals(record.selectedRole) || "MIN_MAX".equals(record.selectedRole)) max = record;
            }
            if (min != null || max != null) {
                selections.add(new ConditionSelection(entry.getKey(), min, max));
            }
        }
        Collections.sort(selections, new Comparator<ConditionSelection>() {
            @Override
            public int compare(ConditionSelection a, ConditionSelection b) {
                int brightness = compareBrightnessDescending(a.maxBrightness, b.maxBrightness);
                if (brightness != 0) return brightness;
                int condition = a.conditionName.compareToIgnoreCase(b.conditionName);
                if (condition != 0) return condition;
                return Integer.compare(selectionSeriesIndex(a), selectionSeriesIndex(b));
            }
        });
        return selections;
    }

    private static int compareBrightnessDescending(double left, double right) {
        boolean leftValid = !Double.isNaN(left) && !Double.isInfinite(left);
        boolean rightValid = !Double.isNaN(right) && !Double.isInfinite(right);
        if (leftValid && rightValid) return Double.compare(right, left);
        if (leftValid) return -1;
        if (rightValid) return 1;
        return 0;
    }

    private static int selectionSeriesIndex(ConditionSelection selection) {
        if (selection == null) return Integer.MAX_VALUE;
        ScoreRecord record = selection.max == null ? selection.min : selection.max;
        return record == null || record.candidate == null
                ? Integer.MAX_VALUE
                : record.candidate.seriesIndex;
    }

    private static double brightestFiniteChannelScore(ScoreRecord record) {
        if (record == null || record.channelScores == null || record.channelScores.isEmpty()) {
            return Double.NaN;
        }
        double brightest = Double.NaN;
        for (Double score : record.channelScores.values()) {
            if (score == null || score.isNaN() || score.isInfinite()) continue;
            if (Double.isNaN(brightest) || score.doubleValue() > brightest) {
                brightest = score.doubleValue();
            }
        }
        return brightest;
    }

    private static void addSelectedSeries(List<SelectedSeries> selected,
                                          Set<Integer> seen,
                                          ScoreRecord record) {
        addSelectedSeries(selected, seen, record, null);
    }

    private static void addSelectedSeries(List<SelectedSeries> selected,
                                          Set<Integer> seen,
                                          ScoreRecord record,
                                          String selectedRoleOverride) {
        if (record == null || record.candidate == null || seen == null) return;
        Integer key = Integer.valueOf(record.candidate.seriesIndex);
        if (seen.add(key)) {
            selected.add(new SelectedSeries(record, selectedRoleOverride));
        }
    }

    private static List<Integer> selectedSeriesIndexesFromSelection(List<SelectedSeries> selectedSeries) {
        List<Integer> selected = new ArrayList<Integer>();
        if (selectedSeries == null) return selected;
        for (SelectedSeries selectedItem : selectedSeries) {
            if (selectedItem != null && selectedItem.seriesIndex >= 0) {
                selected.add(Integer.valueOf(selectedItem.seriesIndex));
            }
        }
        return selected;
    }

    static double meanOfBrightestFraction(float[] values, double fraction) {
        if (values == null || values.length == 0) return Double.NaN;
        int topCount = Math.max(1, (int) Math.round(values.length * fraction));
        int kth = Math.max(0, values.length - topCount);
        quickSelect(values, 0, values.length - 1, kth);

        double sum = 0.0;
        for (int i = kth; i < values.length; i++) sum += values[i];
        return sum / topCount;
    }

    static List<ScoreRecord> readScoresCsv(File scoresFile) throws IOException {
        List<ScoreRecord> records = new ArrayList<ScoreRecord>();
        CsvSupport.RecordReader csv = CsvSupport.openRecordReader(scoresFile);
        try {
            CsvSupport.Record headerRecord = csv.readRecord();
            if (headerRecord == null) return records;

            String[] header = CsvSupport.parseRecord(headerRecord.text);
            LinkedHashMap<Integer, Integer> channelCols = new LinkedHashMap<Integer, Integer>();
            int idxCondition = -1;
            int idxSeriesIndex = -1;
            int idxSeriesName = -1;
            int idxAnimal = -1;
            int idxComposite = -1;
            int idxRole = -1;
            for (int i = 0; i < header.length; i++) {
                String col = header[i];
                if ("Condition".equals(col)) idxCondition = i;
                else if ("SeriesIndex".equals(col)) idxSeriesIndex = i;
                else if ("SeriesName".equals(col)) idxSeriesName = i;
                else if ("AnimalName".equals(col)) idxAnimal = i;
                else if ("CompositeRank".equals(col)) idxComposite = i;
                else if ("SelectedRole".equals(col)) idxRole = i;
                else if (col.startsWith("Channel") && col.endsWith("Score")) {
                    String num = col.substring("Channel".length(), col.length() - "Score".length());
                    try {
                        channelCols.put(Integer.parseInt(num), i);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }

            CsvSupport.Record recordLine;
            while ((recordLine = csv.readRecord()) != null) {
                if (CsvSupport.isBlankRecord(recordLine.text)) continue;
                String[] row = CsvSupport.parseRecord(recordLine.text);
                int seriesIndex = parseInt(cell(row, idxSeriesIndex), -1);
                if (seriesIndex < 0) continue;
                QcSelectionCandidate candidate = new QcSelectionCandidate(
                        seriesIndex,
                        cell(row, idxSeriesName),
                        cell(row, idxAnimal),
                        cell(row, idxCondition));
                ScoreRecord record = new ScoreRecord(candidate);
                record.compositeRank = parseDouble(cell(row, idxComposite));
                record.selectedRole = cell(row, idxRole);
                for (Map.Entry<Integer, Integer> entry : channelCols.entrySet()) {
                    String cell = cell(row, entry.getValue());
                    if (cell == null || cell.trim().isEmpty()) continue;
                    record.channelScores.put(entry.getKey(), parseDouble(cell));
                }
                records.add(record);
            }
        } finally {
            csv.close();
        }
        return records;
    }

    private static int countSelectedConditions(List<ScoreRecord> records) {
        LinkedHashSet<String> selected = new LinkedHashSet<String>();
        for (ScoreRecord record : records) {
            if (record != null && record.candidate != null
                    && record.selectedRole != null && !record.selectedRole.isEmpty()) {
                selected.add(record.candidate.conditionName);
            }
        }
        return selected.size();
    }

    static List<String> buildSelectionLogLines(List<ScoreRecord> records,
                                               List<QcSelectionChannel> qcChannels) {
        List<String> lines = new ArrayList<String>();
        for (ConditionSelection selection : orderedConditionSelections(records)) {
            ScoreRecord min = selection.min;
            ScoreRecord max = selection.max;
            if (min == null && max == null) continue;

            StringBuilder line = new StringBuilder();
            line.append("  ").append(selection.conditionName).append(" -> ");
            if (min != null && max != null && min == max) {
                line.append("MIN/MAX: ").append(describeRecord(min, qcChannels));
            } else {
                if (max != null) {
                    line.append("MAX: ").append(describeRecord(max, qcChannels));
                }
                if (min != null) {
                    if (max != null) line.append(" | ");
                    line.append("MIN: ").append(describeRecord(min, qcChannels));
                }
            }
            lines.add(line.toString());
        }
        return lines;
    }

    static List<String> buildOverallSelectionLogLines(List<ScoreRecord> records,
                                                      List<QcSelectionChannel> qcChannels) {
        List<String> lines = new ArrayList<String>();
        ScoreRecord min = null;
        ScoreRecord max = null;
        if (records != null) {
            for (ScoreRecord record : records) {
                if (record == null || record.candidate == null) continue;
                if ("MIN_MAX".equals(record.selectedRole)) {
                    min = record;
                    max = record;
                } else if ("MIN".equals(record.selectedRole)) {
                    min = record;
                } else if ("MAX".equals(record.selectedRole)) {
                    max = record;
                }
            }
        }
        if (min == null && max == null) return lines;

        StringBuilder line = new StringBuilder();
        line.append("  Overall -> ");
        if (min != null && max != null && min == max) {
            line.append("MIN/MAX: ").append(describeRecordWithCondition(min, qcChannels));
        } else {
            if (max != null) {
                line.append("MAX: ").append(describeRecordWithCondition(max, qcChannels));
            }
            if (min != null) {
                if (max != null) line.append(" | ");
                line.append("MIN: ").append(describeRecordWithCondition(min, qcChannels));
            }
        }
        lines.add(line.toString());
        return lines;
    }

    private static void logSelectionSummary(List<ScoreRecord> records,
                                            List<QcSelectionChannel> qcChannels,
                                            boolean fromCache) {
        logSelectionSummary(records, qcChannels, fromCache, SelectionScope.PER_CONDITION);
    }

    private static void logSelectionSummary(List<ScoreRecord> records,
                                            List<QcSelectionChannel> qcChannels,
                                            boolean fromCache,
                                            SelectionScope scope) {
        List<String> lines = scope == SelectionScope.OVERALL
                ? buildOverallSelectionLogLines(records, qcChannels)
                : buildSelectionLogLines(records, qcChannels);
        if (lines.isEmpty()) return;
        String label = scope == SelectionScope.OVERALL ? "overall min/max" : "min/max per condition";
        IJ.log("QC " + label + ": "
                + (fromCache ? "cached" : "computed")
                + " selection summary");
        for (String line : lines) {
            IJ.log(line);
        }
    }

    private static String describeRecord(ScoreRecord record, List<QcSelectionChannel> qcChannels) {
        StringBuilder sb = new StringBuilder();
        sb.append("Series ").append(record.candidate.seriesNumber)
                .append(" (").append(record.candidate.animalName).append(")");
        if (record.candidate.seriesName != null && !record.candidate.seriesName.trim().isEmpty()) {
            sb.append(" [").append(record.candidate.seriesName).append("]");
        }
        if (!Double.isNaN(record.compositeRank)) {
            sb.append(", rank=").append(formatDouble(record.compositeRank));
        }
        for (QcSelectionChannel channel : qcChannels) {
            Double score = record.channelScores.get(channel.channelNumber);
            if (score == null || Double.isNaN(score) || Double.isInfinite(score)) continue;
            sb.append(", C").append(channel.channelNumber).append("=")
                    .append(formatDouble(score));
        }
        return sb.toString();
    }

    private static String describeRecordWithCondition(ScoreRecord record, List<QcSelectionChannel> qcChannels) {
        String text = describeRecord(record, qcChannels);
        String condition = record == null || record.candidate == null
                ? ""
                : safe(record.candidate.conditionName).trim();
        return condition.isEmpty() ? text : text + ", condition=" + condition;
    }

    private static List<ScoreRecord> scoreCandidates(File lifFile,
                                                     DeferredImageSupplier supplier,
                                                     List<QcSelectionCandidate> candidates,
                                                     List<QcSelectionChannel> qcChannels,
                                                     ZSliceConfig zSliceConfig,
                                                     int parallelThreads) throws Exception {
        final Map<Integer, Integer> localSeriesIndexes =
                localSeriesIndexMap(lifFile, supplier, candidates);
        int requestedThreads = Math.max(1, parallelThreads);
        requestedThreads = Math.min(requestedThreads, Math.max(1, candidates.size()));
        int safeThreads = computeSafeTotalThreads(
                lifFile, qcChannels, requestedThreads,
                firstLocalSeriesIndex(candidates, localSeriesIndexes));

        if (safeThreads <= 1 || candidates.size() <= 1) {
            IJ.log("QC min/max selector: sequential scan");
            return scoreSequential(lifFile, localSeriesIndexes, candidates, qcChannels, zSliceConfig);
        }

        int loaderThreads = Math.max(1, (int) Math.round(safeThreads * 0.8));
        int workerThreads = Math.max(1, safeThreads - loaderThreads);
        if (loaderThreads + workerThreads > safeThreads) {
            loaderThreads = Math.max(1, safeThreads - workerThreads);
        }
        final int effectiveLoaders = loaderThreads;
        final int effectiveWorkers = workerThreads;
        int bufferSize = Math.min(4, Math.max(2, workerThreads));
        IJ.log("QC min/max selector thread split: "
                + effectiveLoaders + " loaders, " + effectiveWorkers + " workers");

        final ArrayBlockingQueue<LoadedCandidate> queue =
                new ArrayBlockingQueue<LoadedCandidate>(bufferSize);
        final AtomicInteger nextCandidate = new AtomicInteger(0);
        final AtomicInteger loadersFinished = new AtomicInteger(0);
        final AtomicBoolean allLoadersDone = new AtomicBoolean(false);
        final ConcurrentHashMap<Integer, ScoreRecord> scored =
                new ConcurrentHashMap<Integer, ScoreRecord>();

        ExecutorService loaderPool = Executors.newFixedThreadPool(effectiveLoaders);
        for (int t = 0; t < effectiveLoaders; t++) {
            final int loaderNum = t + 1;
            loaderPool.submit(new Runnable() {
                @Override
                public void run() {
                    try (Memoizer reader = new Memoizer(new ImageReader())) {
                        reader.setId(lifFile.getAbsolutePath());
                        while (true) {
                            int idx = nextCandidate.getAndIncrement();
                            if (idx >= candidates.size()) break;

                            QcSelectionCandidate candidate = candidates.get(idx);
                            try {
                                IJ.log("QC Loader " + loaderNum + ": sampling " + candidate.seriesName
                                        + " [" + (idx + 1) + "/" + candidates.size() + "]");
                                queue.put(loadCandidate(reader, candidate,
                                        localSeriesIndex(localSeriesIndexes, candidate.seriesIndex),
                                        qcChannels, zSliceConfig));
                            } catch (Exception e) {
                                IJ.log("Warning: QC loader failed for " + candidate.seriesName
                                        + ": " + e.getMessage());
                                try {
                                    queue.put(new LoadedCandidate(candidate,
                                            new LinkedHashMap<Integer, float[]>()));
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                    break;
                                }
                            }
                        }
                    } catch (Exception e) {
                        IJ.log("Warning: QC loader thread failed: " + e.getMessage());
                    } finally {
                        if (loadersFinished.incrementAndGet() >= effectiveLoaders) {
                            allLoadersDone.set(true);
                        }
                    }
                }
            });
        }
        loaderPool.shutdown();

        ExecutorService workerPool = Executors.newFixedThreadPool(effectiveWorkers);
        for (int t = 0; t < effectiveWorkers; t++) {
            workerPool.submit(new Runnable() {
                @Override
                public void run() {
                    while (true) {
                        LoadedCandidate loaded = null;
                        try {
                            loaded = queue.poll(200, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        if (loaded == null) {
                            if (allLoadersDone.get() && queue.isEmpty()) break;
                            continue;
                        }
                        ScoreRecord record = scoreLoadedCandidate(loaded);
                        scored.put(loaded.candidate.seriesIndex, record);
                    }
                }
            });
        }
        workerPool.shutdown();

        loaderPool.awaitTermination(1, TimeUnit.HOURS);
        workerPool.awaitTermination(1, TimeUnit.HOURS);

        List<ScoreRecord> ordered = new ArrayList<ScoreRecord>();
        for (QcSelectionCandidate candidate : candidates) {
            ScoreRecord record = scored.get(candidate.seriesIndex);
            if (record == null) record = new ScoreRecord(candidate);
            ordered.add(record);
        }
        return ordered;
    }

    private static Map<Integer, Integer> localSeriesIndexMap(
            File lifFile,
            DeferredImageSupplier supplier,
            List<QcSelectionCandidate> candidates) {
        LinkedHashMap<Integer, Integer> indexes =
                new LinkedHashMap<Integer, Integer>();
        if (supplier == null || candidates == null) {
            return indexes;
        }
        for (QcSelectionCandidate candidate : candidates) {
            if (candidate == null) continue;
            int globalIndex = candidate.seriesIndex;
            File source = supplier.getContainerFileForSeries(globalIndex);
            if (lifFile != null && source != null && !sameFile(lifFile, source)) {
                throw new IllegalArgumentException(
                        "Min/max QC currently supports one container source at a time. "
                                + "Series " + (globalIndex + 1) + " maps to "
                                + source.getName() + " instead of " + lifFile.getName() + ".");
            }
            indexes.put(Integer.valueOf(globalIndex),
                    Integer.valueOf(supplier.getLocalSeriesIndexForSeries(globalIndex)));
        }
        return indexes;
    }

    private static int localSeriesIndex(Map<Integer, Integer> localSeriesIndexes,
                                        int globalSeriesIndex) {
        if (localSeriesIndexes == null) return globalSeriesIndex;
        Integer local = localSeriesIndexes.get(Integer.valueOf(globalSeriesIndex));
        return local == null ? globalSeriesIndex : local.intValue();
    }

    private static int firstLocalSeriesIndex(List<QcSelectionCandidate> candidates,
                                             Map<Integer, Integer> localSeriesIndexes) {
        if (candidates == null || candidates.isEmpty()) return 0;
        QcSelectionCandidate first = candidates.get(0);
        return first == null ? 0 : localSeriesIndex(localSeriesIndexes, first.seriesIndex);
    }

    private static boolean sameFile(File left, File right) {
        if (left == right) return true;
        if (left == null || right == null) return false;
        try {
            return left.getCanonicalFile().equals(right.getCanonicalFile());
        } catch (IOException e) {
            return left.getAbsoluteFile().equals(right.getAbsoluteFile());
        }
    }

    private static List<ScoreRecord> scoreSequential(File lifFile,
                                                     Map<Integer, Integer> localSeriesIndexes,
                                                     List<QcSelectionCandidate> candidates,
                                                     List<QcSelectionChannel> qcChannels,
                                                     ZSliceConfig zSliceConfig) throws Exception {
        List<ScoreRecord> records = new ArrayList<ScoreRecord>();
        try (Memoizer reader = new Memoizer(new ImageReader())) {
            reader.setId(lifFile.getAbsolutePath());
            for (QcSelectionCandidate candidate : candidates) {
                LoadedCandidate loaded = loadCandidate(reader, candidate,
                        localSeriesIndex(localSeriesIndexes, candidate.seriesIndex),
                        qcChannels, zSliceConfig);
                records.add(scoreLoadedCandidate(loaded));
            }
        }
        return records;
    }

    private static LoadedCandidate loadCandidate(Memoizer reader,
                                                 QcSelectionCandidate candidate,
                                                 int localSeriesIndex,
                                                 List<QcSelectionChannel> qcChannels,
                                                 ZSliceConfig zSliceConfig) throws Exception {
        LinkedHashMap<Integer, float[]> projections = new LinkedHashMap<Integer, float[]>();
        reader.setSeries(localSeriesIndex);
        int sizeZ = Math.max(1, reader.getSizeZ());
        int sizeC = Math.max(1, reader.getSizeC());
        List<Integer> sampledZ = resolveSampledZIndices(zSliceConfig, candidate.seriesIndex, sizeZ);

        for (QcSelectionChannel channel : qcChannels) {
            if (channel.channelIndex < 0 || channel.channelIndex >= sizeC) continue;
            float[] projection = loadSparseMaxProjection(reader, channel.channelIndex, sampledZ);
            if (projection != null) projections.put(channel.channelNumber, projection);
        }
        return new LoadedCandidate(candidate, projections);
    }

    private static ScoreRecord scoreLoadedCandidate(LoadedCandidate loaded) {
        ScoreRecord record = new ScoreRecord(loaded.candidate);
        for (Map.Entry<Integer, float[]> entry : loaded.projections.entrySet()) {
            double score = meanOfBrightestFraction(entry.getValue(), BRIGHTEST_FRACTION);
            record.channelScores.put(entry.getKey(), score);
        }
        return record;
    }

    private static float[] loadSparseMaxProjection(Memoizer reader, int channelIndex,
                                                   List<Integer> sampledZ) throws Exception {
        int width = reader.getSizeX();
        int height = reader.getSizeY();
        if (width <= 0 || height <= 0 || sampledZ.isEmpty()) return null;

        float[] projection = new float[width * height];
        Arrays.fill(projection, Float.NEGATIVE_INFINITY);

        int pixelType = reader.getPixelType();
        boolean littleEndian = reader.isLittleEndian();
        for (Integer z : sampledZ) {
            int planeIndex = reader.getIndex(z, channelIndex, 0);
            byte[] plane = reader.openBytes(planeIndex);
            updateProjection(projection, plane, pixelType, littleEndian);
        }
        return projection;
    }

    private static void updateProjection(float[] projection, byte[] plane,
                                         int pixelType, boolean littleEndian) {
        switch (pixelType) {
            case FormatTools.UINT8:
                for (int i = 0; i < projection.length; i++) {
                    int value = plane[i] & 0xff;
                    if (value > projection[i]) projection[i] = value;
                }
                return;
            case FormatTools.INT8:
                for (int i = 0; i < projection.length; i++) {
                    int value = plane[i];
                    if (value > projection[i]) projection[i] = value;
                }
                return;
            case FormatTools.UINT16:
            case FormatTools.INT16:
                for (int i = 0, p = 0; i < projection.length; i++, p += 2) {
                    int value = readShort(plane, p, littleEndian);
                    if (pixelType == FormatTools.UINT16) value &= 0xffff;
                    else value = (short) value;
                    if (value > projection[i]) projection[i] = value;
                }
                return;
            case FormatTools.UINT32:
            case FormatTools.INT32:
                for (int i = 0, p = 0; i < projection.length; i++, p += 4) {
                    long raw = readInt(plane, p, littleEndian);
                    float value = pixelType == FormatTools.UINT32
                            ? (float) (raw & 0xffffffffL)
                            : (int) raw;
                    if (value > projection[i]) projection[i] = value;
                }
                return;
            case FormatTools.FLOAT:
                for (int i = 0, p = 0; i < projection.length; i++, p += 4) {
                    float value = Float.intBitsToFloat((int) readInt(plane, p, littleEndian));
                    if (value > projection[i]) projection[i] = value;
                }
                return;
            case FormatTools.DOUBLE:
                for (int i = 0, p = 0; i < projection.length; i++, p += 8) {
                    float value = (float) Double.longBitsToDouble(readLong(plane, p, littleEndian));
                    if (value > projection[i]) projection[i] = value;
                }
                return;
            default:
                throw new IllegalArgumentException("Unsupported pixel type for QC scoring: " + pixelType);
        }
    }

    private static void quickSelect(float[] values, int left, int right, int kth) {
        while (left < right) {
            int pivotIndex = partition(values, left, right, (left + right) >>> 1);
            if (kth == pivotIndex) return;
            if (kth < pivotIndex) right = pivotIndex - 1;
            else left = pivotIndex + 1;
        }
    }

    private static int partition(float[] values, int left, int right, int pivotIndex) {
        float pivotValue = values[pivotIndex];
        swap(values, pivotIndex, right);
        int store = left;
        for (int i = left; i < right; i++) {
            if (values[i] < pivotValue) {
                swap(values, store, i);
                store++;
            }
        }
        swap(values, right, store);
        return store;
    }

    private static void swap(float[] values, int a, int b) {
        float tmp = values[a];
        values[a] = values[b];
        values[b] = tmp;
    }

    private static int computeSafeTotalThreads(File lifFile,
                                               List<QcSelectionChannel> qcChannels,
                                               int requestedThreads) {
        return computeSafeTotalThreads(lifFile, qcChannels, requestedThreads, 0);
    }

    private static int computeSafeTotalThreads(File lifFile,
                                               List<QcSelectionChannel> qcChannels,
                                               int requestedThreads,
                                               int estimateSeriesIndex) {
        if (requestedThreads <= 1) return 1;

        long bytesPerTask;
        try (Memoizer reader = new Memoizer(new ImageReader())) {
            reader.setId(lifFile.getAbsolutePath());
            reader.setSeries(Math.max(0, estimateSeriesIndex));
            int sizeX = Math.max(1, reader.getSizeX());
            int sizeY = Math.max(1, reader.getSizeY());
            bytesPerTask = (long) sizeX * sizeY * 4L * Math.max(1, qcChannels.size());
        } catch (Exception e) {
            IJ.log("QC min/max selector: could not estimate memory, using requested threads.");
            return requestedThreads;
        }

        long maxMem = Runtime.getRuntime().maxMemory();
        long usedMem = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long availMem = maxMem - usedMem - MIN_FREE_BYTES;
        if (availMem <= 0 || bytesPerTask <= 0) return 1;

        int bufferSize = Math.min(4, Math.max(2, Math.max(1, requestedThreads / 5)));
        int maxThreads = (int) Math.max(1, (availMem / bytesPerTask) - bufferSize);
        int safe = Math.max(1, Math.min(requestedThreads, maxThreads));
        if (safe < requestedThreads) {
            IJ.log("QC min/max selector: reduced threads from " + requestedThreads
                    + " to " + safe + " based on available memory.");
        }
        return safe;
    }

    private static boolean isCacheValid(String directory, File lifFile,
                                        List<QcSelectionChannel> qcChannels,
                                        ZSliceConfig zSliceConfig,
                                        File cacheFile, File scoresFile) {
        return isCacheValid(directory, lifFile, qcChannels, zSliceConfig,
                "", SELECTION_SCOPE_PER_CONDITION, cacheFile, scoresFile);
    }

    private static boolean isCacheValid(String directory, File lifFile,
                                        List<QcSelectionChannel> qcChannels,
                                        ZSliceConfig zSliceConfig,
                                        String sourceSeriesSignature,
                                        File cacheFile, File scoresFile) {
        return isCacheValid(directory, lifFile, qcChannels, zSliceConfig,
                sourceSeriesSignature, SELECTION_SCOPE_PER_CONDITION, cacheFile, scoresFile);
    }

    private static boolean isCacheValid(String directory, File lifFile,
                                        List<QcSelectionChannel> qcChannels,
                                        ZSliceConfig zSliceConfig,
                                        String sourceSeriesSignature,
                                        String selectionScope,
                                        File cacheFile, File scoresFile) {
        if (cacheFile == null || !cacheFile.isFile()) return false;
        if (scoresFile == null || !scoresFile.isFile()) return false;

        Properties props = new Properties();
        try (BufferedReader br = java.nio.file.Files.newBufferedReader(
                cacheFile.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
            props.load(br);
        } catch (IOException e) {
            IJ.log("Warning: could not read QC min/max cache properties: " + e.getMessage());
            return false;
        }

        if (!CACHE_VERSION.equals(props.getProperty(KEY_CACHE_VERSION))) return false;
        if (!SCORE_METHOD.equals(props.getProperty(KEY_SCORE_METHOD))) return false;
        if (!String.valueOf(MAX_SAMPLED_PLANES).equals(props.getProperty(KEY_MAX_SAMPLED_PLANES))) return false;
        if (!lifFile.getName().equals(props.getProperty(KEY_LIF_NAME))) return false;
        if (!String.valueOf(lifFile.length()).equals(props.getProperty(KEY_LIF_LENGTH))) return false;
        if (!String.valueOf(lifFile.lastModified()).equals(props.getProperty(KEY_LIF_MODIFIED))) return false;
        if (!channelSignature(qcChannels).equals(props.getProperty(KEY_QC_CHANNEL_SIGNATURE))) return false;
        if (!ZSliceConfigIO.signature(zSliceConfig).equals(props.getProperty(KEY_Z_SLICE_SIGNATURE, "zslice:full"))) return false;
        if (!safe(sourceSeriesSignature).equals(props.getProperty(KEY_SOURCE_SERIES_SIGNATURE, ""))) return false;
        if (!selectionScopeOrDefault(selectionScope).equals(
                props.getProperty(KEY_SELECTION_SCOPE, SELECTION_SCOPE_PER_CONDITION))) return false;

        File conditionFile = ConditionManifestIO.getExistingFile(directory);
        boolean conditionExists = conditionFile != null && conditionFile.isFile();
        if (!String.valueOf(conditionExists).equals(props.getProperty(KEY_CONDITIONS_EXISTS))) return false;
        if (conditionExists) {
            if (!String.valueOf(conditionFile.length()).equals(props.getProperty(KEY_CONDITIONS_LENGTH))) return false;
            if (!String.valueOf(conditionFile.lastModified()).equals(props.getProperty(KEY_CONDITIONS_MODIFIED))) return false;
        }

        File orientationFile = OrientationManifestIO.getExistingFile(directory);
        boolean orientationExists = orientationFile != null && orientationFile.isFile();
        if (!String.valueOf(orientationExists).equals(props.getProperty(KEY_ORIENTATION_EXISTS))) return false;
        if (orientationExists) {
            if (!String.valueOf(orientationFile.length()).equals(props.getProperty(KEY_ORIENTATION_LENGTH))) return false;
            if (!String.valueOf(orientationFile.lastModified()).equals(props.getProperty(KEY_ORIENTATION_MODIFIED))) return false;
        }
        return true;
    }

    private static void writeCacheProperties(String directory, File lifFile,
                                             List<QcSelectionChannel> qcChannels,
                                             ZSliceConfig zSliceConfig,
                                             File cacheFile) throws IOException {
        writeCacheProperties(directory, lifFile, qcChannels, zSliceConfig,
                "", SELECTION_SCOPE_PER_CONDITION, cacheFile);
    }

    private static void writeCacheProperties(String directory, File lifFile,
                                             List<QcSelectionChannel> qcChannels,
                                             ZSliceConfig zSliceConfig,
                                             String sourceSeriesSignature,
                                             File cacheFile) throws IOException {
        writeCacheProperties(directory, lifFile, qcChannels, zSliceConfig,
                sourceSeriesSignature, SELECTION_SCOPE_PER_CONDITION, cacheFile);
    }

    private static void writeCacheProperties(String directory, File lifFile,
                                             List<QcSelectionChannel> qcChannels,
                                             ZSliceConfig zSliceConfig,
                                             String sourceSeriesSignature,
                                             String selectionScope,
                                             File cacheFile) throws IOException {
        Properties props = new Properties();
        props.setProperty(KEY_CACHE_VERSION, CACHE_VERSION);
        props.setProperty(KEY_SCORE_METHOD, SCORE_METHOD);
        props.setProperty(KEY_MAX_SAMPLED_PLANES, String.valueOf(MAX_SAMPLED_PLANES));
        props.setProperty(KEY_LIF_NAME, lifFile.getName());
        props.setProperty(KEY_LIF_LENGTH, String.valueOf(lifFile.length()));
        props.setProperty(KEY_LIF_MODIFIED, String.valueOf(lifFile.lastModified()));
        props.setProperty(KEY_QC_CHANNEL_SIGNATURE, channelSignature(qcChannels));
        props.setProperty(KEY_Z_SLICE_SIGNATURE, ZSliceConfigIO.signature(zSliceConfig));
        props.setProperty(KEY_SOURCE_SERIES_SIGNATURE, safe(sourceSeriesSignature));
        props.setProperty(KEY_SELECTION_SCOPE, selectionScopeOrDefault(selectionScope));

        File conditionFile = ConditionManifestIO.getExistingFile(directory);
        boolean conditionExists = conditionFile != null && conditionFile.isFile();
        props.setProperty(KEY_CONDITIONS_EXISTS, String.valueOf(conditionExists));
        if (conditionExists) {
            props.setProperty(KEY_CONDITIONS_LENGTH, String.valueOf(conditionFile.length()));
            props.setProperty(KEY_CONDITIONS_MODIFIED, String.valueOf(conditionFile.lastModified()));
        }

        File orientationFile = OrientationManifestIO.getExistingFile(directory);
        boolean orientationExists = orientationFile != null && orientationFile.isFile();
        props.setProperty(KEY_ORIENTATION_EXISTS, String.valueOf(orientationExists));
        if (orientationExists) {
            props.setProperty(KEY_ORIENTATION_LENGTH, String.valueOf(orientationFile.length()));
            props.setProperty(KEY_ORIENTATION_MODIFIED, String.valueOf(orientationFile.lastModified()));
        }

        File temp = tempFileFor(cacheFile);
        boolean moved = false;
        try {
            PrintWriter pw = new PrintWriter(temp, StandardCharsets.UTF_8.name());
            try {
                props.store(pw, "QC min/max cache");
            } finally {
                pw.close();
            }
            moveAtomically(temp.toPath(), cacheFile.toPath());
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temp.toPath());
            }
        }
    }

    private static String channelSignature(List<QcSelectionChannel> qcChannels) {
        List<String> parts = new ArrayList<String>();
        for (QcSelectionChannel channel : qcChannels) {
            parts.add(channel.channelNumber + ":" + channel.channelName);
        }
        return String.join("|", parts);
    }

    private static String selectionScopeOrDefault(String selectionScope) {
        String value = safe(selectionScope).trim();
        return value.isEmpty() ? SELECTION_SCOPE_PER_CONDITION : value;
    }

    private static String sourceSeriesSignature(File lifFile,
                                                DeferredImageSupplier supplier,
                                                List<SeriesMeta> metas) {
        StringBuilder sb = new StringBuilder("source-series:v1");
        if (metas == null) return sb.toString();
        for (SeriesMeta meta : metas) {
            if (meta == null) continue;
            int globalIndex = meta.index;
            int localIndex = globalIndex;
            String sourceName = lifFile == null ? "" : lifFile.getName();
            if (supplier != null) {
                try {
                    localIndex = supplier.getLocalSeriesIndexForSeries(globalIndex);
                    File source = supplier.getContainerFileForSeries(globalIndex);
                    if (source != null) {
                        sourceName = source.getName();
                    }
                } catch (RuntimeException e) {
                    sourceName = sourceName + "?";
                }
            }
            sb.append('|')
                    .append(globalIndex)
                    .append(':')
                    .append(localIndex)
                    .append(':')
                    .append(cacheToken(sourceName))
                    .append(':')
                    .append(cacheToken(meta.name))
                    .append(':')
                    .append(meta.width)
                    .append('x')
                    .append(meta.height)
                    .append('z')
                    .append(meta.nSlices)
                    .append('c')
                    .append(meta.nChannels);
        }
        return sb.toString();
    }

    private static String cacheToken(String value) {
        return safe(value).replace("\\", "\\\\")
                .replace("|", "\\p")
                .replace(":", "\\c");
    }

    private static void writeScoresCsv(File scoresFile, List<ScoreRecord> records,
                                       List<QcSelectionChannel> qcChannels) throws IOException {
        File temp = tempFileFor(scoresFile);
        boolean moved = false;
        try {
            PrintWriter pw = CsvSupport.newWriter(temp);
            try {
                List<String> header = new ArrayList<String>();
                header.add("Condition");
                header.add("SeriesIndex");
                header.add("SeriesNumber");
                header.add("SeriesName");
                header.add("AnimalName");
                header.add("CompositeRank");
                header.add("SelectedRole");
                for (QcSelectionChannel channel : qcChannels) {
                    header.add("Channel" + channel.channelNumber + "Score");
                }
                pw.println(CsvSupport.joinRow(header));

                for (ScoreRecord record : records) {
                    List<String> row = new ArrayList<String>();
                    row.add(record.candidate.conditionName);
                    row.add(String.valueOf(record.candidate.seriesIndex));
                    row.add(String.valueOf(record.candidate.seriesNumber));
                    row.add(record.candidate.seriesName);
                    row.add(record.candidate.animalName);
                    row.add(formatDouble(record.compositeRank));
                    row.add(record.selectedRole == null ? "" : record.selectedRole);
                    for (QcSelectionChannel channel : qcChannels) {
                        Double score = record.channelScores.get(channel.channelNumber);
                        row.add(score == null ? "" : formatDouble(score));
                    }
                    pw.println(CsvSupport.joinRow(row));
                }
            } finally {
                pw.close();
            }
            moveAtomically(temp.toPath(), scoresFile.toPath());
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temp.toPath());
            }
        }
    }

    private static File tempFileFor(File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null) flash.pipeline.io.IoUtils.mustMkdirs(parent);
        return File.createTempFile(tempPrefix(target), ".tmp",
                parent == null ? new File(".") : parent);
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        // Retry/backoff move, then in-place rewrite if the destination stays
        // locked against rename (Windows + Dropbox/OneDrive). Safe: small scores.
        flash.pipeline.io.IoUtils.commitReplacingSmallFile(source, target);
    }

    private static String tempPrefix(File target) {
        String name = target == null ? "qc" : target.getName();
        String clean = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return clean.length() < 3 ? "tmp" + clean : clean;
    }

    private static String cell(String[] row, int index) {
        return index >= 0 && index < row.length ? row[index] : "";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static double parseDouble(String text) {
        try {
            return Double.parseDouble(text.trim());
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private static String formatDouble(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return "";
        return String.format(Locale.US, "%.6f", value);
    }

    private static String extractAnimalName(SeriesMeta meta) {
        String animalName = ConditionManifestIO.extractAnimalName(meta == null ? null : meta.name);
        if (animalName != null && !animalName.trim().isEmpty()) {
            return animalName.trim();
        }
        String seriesName = safeSeriesName(meta);
        if (!seriesName.isEmpty()) return seriesName;
        return "Series" + (meta == null ? 0 : meta.index + 1);
    }

    private static String safeSeriesName(SeriesMeta meta) {
        if (meta == null || meta.name == null) return "";
        return meta.name.trim();
    }

    private static int readShort(byte[] buf, int offset, boolean littleEndian) {
        int b0 = buf[offset] & 0xff;
        int b1 = buf[offset + 1] & 0xff;
        return littleEndian ? (b0 | (b1 << 8)) : ((b0 << 8) | b1);
    }

    private static long readInt(byte[] buf, int offset, boolean littleEndian) {
        long b0 = buf[offset] & 0xffL;
        long b1 = buf[offset + 1] & 0xffL;
        long b2 = buf[offset + 2] & 0xffL;
        long b3 = buf[offset + 3] & 0xffL;
        if (littleEndian) return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
        return (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
    }

    private static long readLong(byte[] buf, int offset, boolean littleEndian) {
        long out = 0L;
        if (littleEndian) {
            for (int i = 7; i >= 0; i--) out = (out << 8) | (buf[offset + i] & 0xffL);
        } else {
            for (int i = 0; i < 8; i++) out = (out << 8) | (buf[offset + i] & 0xffL);
        }
        return out;
    }
}
