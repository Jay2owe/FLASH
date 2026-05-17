package flash.pipeline.decontamination;

import flash.pipeline.io.ConditionManifestIO;
import flash.pipeline.io.CsvSupport;
import flash.pipeline.io.LifIO;
import flash.pipeline.io.SeriesMeta;
import flash.pipeline.naming.ConditionNameParser;
import ij.ImagePlus;
import ij.process.ImageProcessor;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Selects a small condition-aware image subset for Spectral Decontamination preview.
 */
public class SpectralPreviewSelector {

    public static final String OUTPUT_FOLDER = SpectralOutputWriter.DATA_OUTPUT_FOLDER;
    public static final String PREVIEW_SELECTION_FILENAME = "preview_selection.csv";
    public static final int DEFAULT_TYPICAL_IMAGES_PER_CONDITION = 2;

    private static final int MAX_SAMPLES_PER_CHANNEL = 50000;

    private SpectralPreviewSelector() {
    }

    public static File previewSelectionFile(String directory) {
        return new File(SpectralOutputWriter.dataOutputDirectory(directory), PREVIEW_SELECTION_FILENAME);
    }

    public static List<PreviewCandidate> buildCandidates(
            String directory,
            List<SeriesMeta> metas,
            SpectralDecontaminationConfig.ConditionSource source,
            Map<String, String> manualAssignments) {
        LinkedHashSet<String> animals = new LinkedHashSet<String>();
        if (metas != null) {
            for (SeriesMeta meta : metas) {
                animals.add(animalName(meta));
            }
        }

        Map<String, String> assignments;
        if (source == SpectralDecontaminationConfig.ConditionSource.ASSIGN_MANUALLY) {
            assignments = manualAssignments == null
                    ? new LinkedHashMap<String, String>()
                    : manualAssignments;
        } else if (source == SpectralDecontaminationConfig.ConditionSource.USE_EXISTING_CONDITION_FILE
                && ConditionManifestIO.getExistingFile(directory) != null) {
            assignments = ConditionManifestIO.resolveAssignments(directory, animals);
        } else {
            assignments = inferAssignments(animals);
        }

        return buildCandidatesFromAssignments(metas, assignments);
    }

    public static List<PreviewCandidate> buildCandidatesFromAssignments(
            List<SeriesMeta> metas,
            Map<String, String> assignments) {
        List<PreviewCandidate> candidates = new ArrayList<PreviewCandidate>();
        if (metas == null) return candidates;

        for (SeriesMeta meta : metas) {
            if (meta == null) continue;
            String seriesName = seriesName(meta);
            String animalName = animalName(meta);
            String conditionName = assignments == null ? null : assignments.get(animalName);
            if (conditionName == null || conditionName.trim().isEmpty()) {
                conditionName = inferCondition(animalName);
            }
            candidates.add(new PreviewCandidate(meta.index, seriesName, animalName, conditionName));
        }
        return candidates;
    }

    public static LinkedHashMap<String, String> inferAssignments(Set<String> animals) {
        LinkedHashMap<String, String> assignments = new LinkedHashMap<String, String>();
        if (animals == null) return assignments;
        for (String animal : animals) {
            String cleaned = animal == null ? "" : animal.trim();
            if (cleaned.isEmpty() || assignments.containsKey(cleaned)) continue;
            assignments.put(cleaned, inferCondition(cleaned));
        }
        return assignments;
    }

    public static List<String> conditionNames(List<PreviewCandidate> candidates) {
        LinkedHashSet<String> names = new LinkedHashSet<String>();
        if (candidates != null) {
            for (PreviewCandidate candidate : candidates) {
                if (candidate == null) continue;
                String condition = cleanLabel(candidate.conditionName, "");
                if (!condition.isEmpty()) names.add(condition);
            }
        }
        return new ArrayList<String>(names);
    }

    public static List<PreviewSelection> selectPreviewImages(
            List<ScoredImage> scoredImages,
            List<String> controlConditions,
            List<String> experimentalConditions,
            boolean includeBleedThroughChannels) {
        LinkedHashMap<Integer, MutableSelection> selected = new LinkedHashMap<Integer, MutableSelection>();
        Map<String, List<ScoredImage>> byCondition = groupByCondition(scoredImages);
        List<String> conditionOrder = orderedConditionNames(scoredImages, controlConditions, experimentalConditions);
        Set<String> controls = cleanSet(controlConditions);
        Set<String> experimentals = cleanSet(experimentalConditions);
        boolean roleFilterActive = !controls.isEmpty() || !experimentals.isEmpty();

        for (String condition : conditionOrder) {
            boolean isControl = controls.contains(condition);
            boolean isExperimental = experimentals.contains(condition);
            if (roleFilterActive && !isControl && !isExperimental) continue;

            List<ScoredImage> group = byCondition.get(condition);
            if (group == null || group.isEmpty()) continue;

            addTypical(selected, group, DEFAULT_TYPICAL_IMAGES_PER_CONDITION,
                    conditionRole(isControl, isExperimental));
            if (isControl || (!roleFilterActive && !isExperimental)) {
                addSelection(selected, maxBy(group, Metric.TARGET),
                        "target_bright_control", conditionRole(true, isExperimental));
            }
            if (isExperimental || (!roleFilterActive && !isControl)) {
                addSelection(selected, maxBy(group, Metric.AUTOFLUORESCENCE),
                        "autofluorescence_bright_experimental", conditionRole(isControl, true));
            }
            if (includeBleedThroughChannels) {
                addSelection(selected, maxBy(group, Metric.BLEED_THROUGH),
                        "bleedthrough_bright", conditionRole(isControl, isExperimental));
            }
        }

        List<PreviewSelection> out = new ArrayList<PreviewSelection>();
        for (MutableSelection value : selected.values()) {
            out.add(value.toSelection());
        }
        return out;
    }

    public static ImageScores scoreImage(ImagePlus image, SpectralDecontaminationConfig config) {
        if (image == null || config == null) {
            return new ImageScores(Double.NaN, Double.NaN, Double.NaN, 0.0, -1);
        }
        List<Integer> target = new ArrayList<Integer>();
        target.add(Integer.valueOf(config.getTargetChannelIndex()));

        List<Integer> saturationChannels = new ArrayList<Integer>();
        saturationChannels.add(Integer.valueOf(config.getTargetChannelIndex()));
        saturationChannels.addAll(config.getBleedThroughChannelIndexes());
        saturationChannels.addAll(config.getAutofluorescenceChannelIndexes());

        double targetP99 = percentileForChannels(image, target, 99.0);
        double autofluorescenceP99 = percentileForChannels(image, config.getAutofluorescenceChannelIndexes(), 99.0);
        double bleedThroughP99 = percentileForChannels(image, config.getBleedThroughChannelIndexes(), 99.0);
        double saturatedFraction = saturatedFraction(image, saturationChannels);
        return new ImageScores(targetP99, autofluorescenceP99, bleedThroughP99, saturatedFraction, -1);
    }

    public static void writePreviewSelection(File file,
                                             SpectralOutputWriter.RunMetadata runMetadata,
                                             List<PreviewSelection> selections) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("file must not be null");
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create " + parent.getAbsolutePath());
        }

        AtomicFileWriter.writeUtf8(file, new AtomicFileWriter.WriterAction() {
            @Override
            public void write(Writer writer) throws IOException {
                writer.write(CsvSupport.joinRow(Arrays.asList(
                        "SeriesIndex",
                        "SeriesNumber",
                        "SeriesName",
                        "AnimalName",
                        "Condition",
                        "ConditionRole",
                        "SelectionRole",
                        "ConfigVersion",
                        "ConfigId",
                        "PipelinePresetId",
                        "PipelineStackId",
                        "TargetP99",
                        "AutofluorescenceP99",
                        "BleedThroughP99",
                        "SaturatedFraction",
                        "ObjectCount")));
                writer.write("\n");
                if (selections == null) return;
                for (PreviewSelection selection : selections) {
                    PreviewCandidate c = selection.candidate;
                    ImageScores s = selection.scores;
                    writer.write(CsvSupport.joinRow(Arrays.asList(
                            String.valueOf(c.seriesIndex),
                            String.valueOf(c.seriesIndex + 1),
                            c.seriesName,
                            c.animalName,
                            c.conditionName,
                            selection.conditionRole,
                            selection.selectionRole,
                            runMetadata == null ? "" : String.valueOf(runMetadata.configVersion),
                            runMetadata == null ? "" : cleanLabel(runMetadata.configId, ""),
                            runMetadata == null ? "" : cleanLabel(runMetadata.pipelinePresetId, ""),
                            runMetadata == null ? "" : cleanLabel(runMetadata.pipelineStackId, ""),
                            formatDouble(s.targetP99),
                            formatDouble(s.autofluorescenceP99),
                            formatDouble(s.bleedThroughP99),
                            formatDouble(s.saturatedFraction),
                            s.objectCount < 0 ? "" : String.valueOf(s.objectCount))));
                    writer.write("\n");
                }
            }
        });
    }

    private static void addTypical(LinkedHashMap<Integer, MutableSelection> selected,
                                   List<ScoredImage> group,
                                   int count,
                                   String conditionRole) {
        List<ScoredImage> ranked = new ArrayList<ScoredImage>(group);
        final double median = medianRiskScore(group);
        Collections.sort(ranked, new Comparator<ScoredImage>() {
            @Override
            public int compare(ScoredImage a, ScoredImage b) {
                int cmp = Double.compare(Math.abs(riskScore(a) - median), Math.abs(riskScore(b) - median));
                if (cmp != 0) return cmp;
                return Integer.compare(a.candidate.seriesIndex, b.candidate.seriesIndex);
            }
        });

        int limit = Math.min(Math.max(0, count), ranked.size());
        for (int i = 0; i < limit; i++) {
            addSelection(selected, ranked.get(i), "typical", conditionRole);
        }
    }

    private static void addSelection(LinkedHashMap<Integer, MutableSelection> selected,
                                     ScoredImage image,
                                     String role,
                                     String conditionRole) {
        if (image == null) return;
        Integer key = Integer.valueOf(image.candidate.seriesIndex);
        MutableSelection existing = selected.get(key);
        if (existing == null) {
            existing = new MutableSelection(image, conditionRole);
            selected.put(key, existing);
        } else if (conditionRole != null && conditionRole.length() > existing.conditionRole.length()) {
            existing.conditionRole = conditionRole;
        }
        existing.addRole(role);
    }

    private static Map<String, List<ScoredImage>> groupByCondition(List<ScoredImage> scoredImages) {
        LinkedHashMap<String, List<ScoredImage>> byCondition =
                new LinkedHashMap<String, List<ScoredImage>>();
        if (scoredImages == null) return byCondition;
        for (ScoredImage image : scoredImages) {
            if (image == null || image.candidate == null) continue;
            String condition = cleanLabel(image.candidate.conditionName, "Unassigned");
            List<ScoredImage> group = byCondition.get(condition);
            if (group == null) {
                group = new ArrayList<ScoredImage>();
                byCondition.put(condition, group);
            }
            group.add(image);
        }
        return byCondition;
    }

    private static List<String> orderedConditionNames(List<ScoredImage> scoredImages,
                                                      List<String> controlConditions,
                                                      List<String> experimentalConditions) {
        LinkedHashSet<String> ordered = new LinkedHashSet<String>();
        ordered.addAll(cleanSet(controlConditions));
        ordered.addAll(cleanSet(experimentalConditions));
        if (scoredImages != null) {
            for (ScoredImage image : scoredImages) {
                if (image == null || image.candidate == null) continue;
                ordered.add(cleanLabel(image.candidate.conditionName, "Unassigned"));
            }
        }
        return new ArrayList<String>(ordered);
    }

    private static ScoredImage maxBy(List<ScoredImage> group, final Metric metric) {
        if (group == null || group.isEmpty()) return null;
        List<ScoredImage> ranked = new ArrayList<ScoredImage>(group);
        Collections.sort(ranked, new Comparator<ScoredImage>() {
            @Override
            public int compare(ScoredImage a, ScoredImage b) {
                int cmp = Double.compare(metricValue(b, metric), metricValue(a, metric));
                if (cmp != 0) return cmp;
                return Integer.compare(a.candidate.seriesIndex, b.candidate.seriesIndex);
            }
        });
        return ranked.get(0);
    }

    private static double metricValue(ScoredImage image, Metric metric) {
        if (image == null || image.scores == null) return Double.NEGATIVE_INFINITY;
        double value;
        if (metric == Metric.TARGET) value = image.scores.targetP99;
        else if (metric == Metric.AUTOFLUORESCENCE) value = image.scores.autofluorescenceP99;
        else value = image.scores.bleedThroughP99;
        return Double.isNaN(value) ? Double.NEGATIVE_INFINITY : value;
    }

    private static double medianRiskScore(List<ScoredImage> group) {
        if (group == null || group.isEmpty()) return 0.0;
        double[] values = new double[group.size()];
        for (int i = 0; i < group.size(); i++) {
            values[i] = riskScore(group.get(i));
        }
        Arrays.sort(values);
        int middle = values.length / 2;
        if (values.length % 2 == 1) return values[middle];
        return (values[middle - 1] + values[middle]) / 2.0;
    }

    private static double riskScore(ScoredImage image) {
        if (image == null || image.scores == null) return 0.0;
        return safe(image.scores.targetP99)
                + safe(image.scores.autofluorescenceP99)
                + safe(image.scores.bleedThroughP99);
    }

    private static double safe(double value) {
        return Double.isNaN(value) ? 0.0 : value;
    }

    private static Set<String> cleanSet(List<String> values) {
        LinkedHashSet<String> cleaned = new LinkedHashSet<String>();
        if (values == null) return cleaned;
        for (String value : values) {
            String label = cleanLabel(value, "");
            if (!label.isEmpty()) cleaned.add(label);
        }
        return cleaned;
    }

    private static String conditionRole(boolean control, boolean experimental) {
        if (control && experimental) return "control+experimental";
        if (control) return "control";
        if (experimental) return "experimental";
        return "unassigned";
    }

    private static String formatDouble(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return "";
        return String.format(Locale.US, "%.6f", value);
    }

    private static double percentileForChannels(ImagePlus image, List<Integer> channels, double percentile) {
        double best = Double.NaN;
        if (channels == null || channels.isEmpty()) return best;
        for (Integer channel : channels) {
            if (channel == null) continue;
            double value = percentileForChannel(image, channel.intValue(), percentile);
            if (Double.isNaN(value)) continue;
            if (Double.isNaN(best) || value > best) best = value;
        }
        return best;
    }

    private static double percentileForChannel(ImagePlus image, int channelIndex, double percentile) {
        double[] sample = sampleChannel(image, channelIndex);
        if (sample.length == 0) return Double.NaN;
        Arrays.sort(sample);
        int index = (int) Math.ceil((percentile / 100.0) * sample.length) - 1;
        if (index < 0) index = 0;
        if (index >= sample.length) index = sample.length - 1;
        return sample[index];
    }

    private static double saturatedFraction(ImagePlus image, List<Integer> channels) {
        double ceiling = saturationCeiling(image);
        if (Double.isNaN(ceiling) || channels == null || channels.isEmpty()) return 0.0;
        long saturated = 0;
        long total = 0;
        LinkedHashSet<Integer> unique = new LinkedHashSet<Integer>(channels);
        for (Integer channel : unique) {
            if (channel == null) continue;
            double[] sample = sampleChannel(image, channel.intValue());
            for (double value : sample) {
                total++;
                if (value >= ceiling) saturated++;
            }
        }
        if (total == 0) return 0.0;
        return (double) saturated / (double) total;
    }

    private static double[] sampleChannel(ImagePlus image, int channelIndex) {
        if (image == null) return new double[0];
        if (channelIndex < 0 || channelIndex >= Math.max(1, image.getNChannels())) {
            return new double[0];
        }
        int width = Math.max(1, image.getWidth());
        int height = Math.max(1, image.getHeight());
        int slices = Math.max(1, image.getNSlices());
        int frames = Math.max(1, image.getNFrames());
        long totalPixels = (long) width * (long) height * (long) slices * (long) frames;
        int step = (int) Math.max(1L,
                (totalPixels + MAX_SAMPLES_PER_CHANNEL - 1L) / MAX_SAMPLES_PER_CHANNEL);
        double[] sample = new double[(int) Math.min(totalPixels, MAX_SAMPLES_PER_CHANNEL)];
        int count = 0;
        long seen = 0;

        for (int t = 1; t <= frames; t++) {
            for (int z = 1; z <= slices; z++) {
                int stackIndex = image.getStackIndex(channelIndex + 1, z, t);
                if (stackIndex < 1 || stackIndex > image.getStackSize()) continue;
                ImageProcessor processor = image.getStack().getProcessor(stackIndex);
                Object pixels = processor.getPixels();
                int length = width * height;
                for (int i = 0; i < length; i++) {
                    if (seen % step == 0 && count < sample.length) {
                        sample[count++] = pixelValue(pixels, processor, i, width);
                    }
                    seen++;
                }
            }
        }

        if (count == sample.length) return sample;
        return Arrays.copyOf(sample, count);
    }

    private static double pixelValue(Object pixels, ImageProcessor processor, int index, int width) {
        if (pixels instanceof byte[]) {
            return ((byte[]) pixels)[index] & 0xff;
        }
        if (pixels instanceof short[]) {
            return ((short[]) pixels)[index] & 0xffff;
        }
        if (pixels instanceof float[]) {
            return ((float[]) pixels)[index];
        }
        if (pixels instanceof int[]) {
            return ((int[]) pixels)[index];
        }
        int x = index % width;
        int y = index / width;
        return processor.getf(x, y);
    }

    private static double saturationCeiling(ImagePlus image) {
        if (image == null) return Double.NaN;
        int bitDepth = image.getBitDepth();
        if (bitDepth == 8) return 255.0;
        if (bitDepth == 16) return 65535.0;
        if (bitDepth == 24) return 255.0;
        return Double.NaN;
    }

    private static String seriesName(SeriesMeta meta) {
        String name = meta == null ? null : meta.name;
        if (name == null || name.trim().isEmpty()) {
            int index = meta == null ? 0 : meta.index;
            return "Series " + (index + 1);
        }
        return name.trim();
    }

    private static String animalName(SeriesMeta meta) {
        String animal = ConditionManifestIO.extractAnimalName(seriesName(meta));
        if (animal == null || animal.trim().isEmpty()) {
            int index = meta == null ? 0 : meta.index;
            return "Series " + (index + 1);
        }
        return animal.trim();
    }

    private static String inferCondition(String animalName) {
        String condition = ConditionNameParser.detectCondition(animalName);
        if (condition == null || condition.trim().isEmpty()) {
            condition = animalName;
        }
        return cleanLabel(condition, "Unassigned");
    }

    private static String cleanLabel(String value, String fallback) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.isEmpty()) return fallback == null ? "" : fallback;
        return cleaned;
    }

    private enum Metric {
        TARGET,
        AUTOFLUORESCENCE,
        BLEED_THROUGH
    }

    private static class MutableSelection {
        final ScoredImage image;
        String conditionRole;
        final LinkedHashSet<String> roles = new LinkedHashSet<String>();

        MutableSelection(ScoredImage image, String conditionRole) {
            this.image = image;
            this.conditionRole = conditionRole == null ? "unassigned" : conditionRole;
        }

        void addRole(String role) {
            if (role != null && !role.trim().isEmpty()) {
                roles.add(role.trim());
            }
        }

        PreviewSelection toSelection() {
            StringBuilder roleText = new StringBuilder();
            for (String role : roles) {
                if (roleText.length() > 0) roleText.append(';');
                roleText.append(role);
            }
            return new PreviewSelection(image.candidate, image.scores, conditionRole, roleText.toString());
        }
    }

    public static class PreviewCandidate {
        public final int seriesIndex;
        public final String seriesName;
        public final String animalName;
        public final String conditionName;

        public PreviewCandidate(int seriesIndex, String seriesName, String animalName, String conditionName) {
            this.seriesIndex = seriesIndex;
            this.seriesName = cleanLabel(seriesName, "Series " + (seriesIndex + 1));
            this.animalName = cleanLabel(animalName, this.seriesName);
            this.conditionName = cleanLabel(conditionName, "Unassigned");
        }
    }

    public static class ImageScores {
        public final double targetP99;
        public final double autofluorescenceP99;
        public final double bleedThroughP99;
        public final double saturatedFraction;
        public final int objectCount;

        public ImageScores(double targetP99,
                           double autofluorescenceP99,
                           double bleedThroughP99,
                           double saturatedFraction,
                           int objectCount) {
            this.targetP99 = targetP99;
            this.autofluorescenceP99 = autofluorescenceP99;
            this.bleedThroughP99 = bleedThroughP99;
            this.saturatedFraction = saturatedFraction;
            this.objectCount = objectCount;
        }
    }

    public static class ScoredImage {
        public final PreviewCandidate candidate;
        public final ImageScores scores;

        public ScoredImage(PreviewCandidate candidate, ImageScores scores) {
            if (candidate == null) {
                throw new IllegalArgumentException("candidate must not be null");
            }
            this.candidate = candidate;
            this.scores = scores == null
                    ? new ImageScores(Double.NaN, Double.NaN, Double.NaN, 0.0, -1)
                    : scores;
        }
    }

    public static class PreviewSelection {
        public final PreviewCandidate candidate;
        public final ImageScores scores;
        public final String conditionRole;
        public final String selectionRole;

        public PreviewSelection(PreviewCandidate candidate,
                                ImageScores scores,
                                String conditionRole,
                                String selectionRole) {
            this.candidate = candidate;
            this.scores = scores == null
                    ? new ImageScores(Double.NaN, Double.NaN, Double.NaN, 0.0, -1)
                    : scores;
            this.conditionRole = cleanLabel(conditionRole, "unassigned");
            this.selectionRole = cleanLabel(selectionRole, "typical");
        }
    }
}
