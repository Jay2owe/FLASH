package flash.pipeline.decontamination;

import flash.pipeline.io.CsvSupport;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.naming.ChannelFilenameCodec;
import flash.pipeline.naming.ImageNameParser;
import flash.pipeline.naming.NameParts;
import flash.pipeline.results.RunIdCsv;
import ij.ImagePlus;
import ij.io.FileSaver;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.nio.file.Paths;
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
 * Locates 3D Object Analysis label maps and writes Spectral Decontamination object scores.
 */
public final class ObjectScoreWriter {

    public static final String PER_OBJECT_SCORES_FILENAME = "per_object_scores.csv";

    private static final List<String> PER_OBJECT_COLUMNS = Arrays.asList(
            "SeriesIndex",
            "SeriesNumber",
            "SeriesName",
            "Condition",
            "ConditionRole",
            "ConfigVersion",
            "ConfigId",
            "PipelinePresetId",
            "PipelineStackId",
            "TargetChannel",
            "AnimalName",
            "Hemisphere",
            "Region",
            "ROI",
            "SCN",
            "ObjectID",
            "ObjectMapPath",
            "SourceObjectCsvPath",
            "CleanedObjectMapPath",
            "VoxelCount",
            "TargetMean",
            "TargetP99",
            "MaxContaminantMean",
            "TargetToMaxContaminantRatio",
            "MaxBleedThroughMean",
            "TargetToBleedThroughRatio",
            "MaxAutofluorescenceMean",
            "TargetToAutofluorescenceRatio",
            "HighAutofluorescenceOverlapFraction",
            "HighBleedThroughOverlapFraction",
            "BrightTailScore",
            "CorrectedTargetMean",
            "CorrectedTargetP99",
            "CorrectedTargetRetentionFraction",
            "ContaminationScore",
            "KeepObject",
            "RejectReason",
            "RunAction");

    private ObjectScoreWriter() {
    }

    public static File perObjectScoresFile(String directory) {
        return new File(SpectralOutputWriter.dataOutputDirectory(directory), PER_OBJECT_SCORES_FILENAME);
    }

    public static File objectCsvFile(String directory, String channelName) {
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
        String filename = safeChannelName(channelName) + ".csv";
        return new File(layout.tablesObjectsWriteDir(), filename);
    }

    public static List<ObjectMapDescriptor> locateObjectLabelMaps(String directory,
                                                                  int seriesIndex,
                                                                  String seriesName,
                                                                  String channelName)
            throws IOException {
        NameParts parts = ImageNameParser.parse(seriesName);
        String animalName = parts.animal == null || parts.animal.trim().isEmpty()
                ? ImageNameParser.extractBioFormatsSeriesName(seriesName)
                : parts.animal.trim();
        File channelCsv = objectCsvFile(directory, channelName);
        List<ObjectMapDescriptor> fromCsv = locateFromObjectCsv(
                directory,
                seriesIndex,
                seriesName,
                channelName,
                animalName,
                channelCsv);
        if (!fromCsv.isEmpty()) {
            return fromCsv;
        }
        return scanObjectMapFiles(directory, seriesIndex, seriesName, channelName, animalName);
    }

    public static File cleanedObjectMapFile(SpectralOutputWriter.ExpectedOutputs outputs,
                                            String targetChannelName,
                                            ObjectMapDescriptor descriptor) {
        if (outputs == null) {
            throw new IllegalArgumentException("Expected outputs are required.");
        }
        String safeTarget = safeChannelName(targetChannelName == null || targetChannelName.trim().isEmpty()
                ? "target"
                : targetChannelName);
        String suffix = descriptor == null ? "" : descriptor.getOutputSuffix();
        if (suffix.isEmpty() && descriptor != null) {
            suffix = suffixFromObjectMapFile(descriptor.getFile(), safeTarget);
        }
        String safeSuffix = suffix.isEmpty() ? "" : "_" + ChannelFilenameCodec.toSafe(suffix);
        return new File(outputs.imageOutputDirectory,
                "cleaned_objects_" + safeTarget + safeSuffix + ".tif");
    }

    public static void saveCleanedObjectMap(ImagePlus image, File outputFile) throws IOException {
        if (image == null) {
            throw new IllegalArgumentException("Cleaned object map is required.");
        }
        if (outputFile == null) {
            throw new IllegalArgumentException("Output file is required.");
        }
        File parent = outputFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create " + parent.getAbsolutePath());
        }
        FileSaver saver = new FileSaver(image);
        boolean saved = image.getStackSize() > 1
                ? saver.saveAsTiffStack(outputFile.getAbsolutePath())
                : saver.saveAsTiff(outputFile.getAbsolutePath());
        if (!saved) {
            throw new IOException("Could not save image to " + outputFile.getAbsolutePath());
        }
    }

    public static List<Map<String, String>> buildRows(String directory,
                                                       int seriesIndex,
                                                       String seriesName,
                                                       String conditionName,
                                                       String conditionRole,
                                                       SpectralOutputWriter.RunMetadata runMetadata,
                                                       String targetChannelName,
                                                       ObjectMapDescriptor descriptor,
                                                       ObjectDecontaminationScorer.ScoreResult scoreResult,
                                                       File cleanedObjectMapFile,
                                                       String runAction) {
        List<Map<String, String>> rows = new ArrayList<Map<String, String>>();
        if (scoreResult == null || descriptor == null) {
            return rows;
        }
        for (ObjectDecontaminationScorer.ObjectScore score : scoreResult.getScores()) {
            if (score == null) {
                continue;
            }
            ObjectMetadata metadata = descriptor.metadataForLabel(score.getObjectId());
            LinkedHashMap<String, String> row = new LinkedHashMap<String, String>();
            row.put("SeriesIndex", String.valueOf(seriesIndex));
            row.put("SeriesNumber", String.valueOf(seriesIndex + 1));
            row.put("SeriesName", clean(seriesName));
            row.put("Condition", clean(conditionName));
            row.put("ConditionRole", clean(conditionRole));
            putRunMetadata(row, runMetadata);
            row.put("TargetChannel", clean(targetChannelName));
            row.put("AnimalName", metadata.getAnimalName());
            row.put("Hemisphere", metadata.getHemisphere());
            row.put("Region", metadata.getRegion());
            row.put("ROI", metadata.getRoi());
            row.put("SCN", metadata.getScn());
            row.put("ObjectID", String.valueOf(score.getObjectId()));
            row.put("ObjectMapPath", relativePath(directory, descriptor.getFile()));
            row.put("SourceObjectCsvPath", relativePath(directory, descriptor.getSourceObjectCsvFile()));
            row.put("CleanedObjectMapPath", relativePath(directory, cleanedObjectMapFile));
            row.put("VoxelCount", String.valueOf(score.getVoxelCount()));
            row.put("TargetMean", formatDouble(score.getTargetMean()));
            row.put("TargetP99", formatDouble(score.getTargetP99()));
            row.put("MaxContaminantMean", formatDouble(score.getMaxContaminantMean()));
            row.put("TargetToMaxContaminantRatio",
                    formatDouble(score.getTargetToMaxContaminantRatio()));
            row.put("MaxBleedThroughMean", formatDouble(score.getMaxBleedThroughMean()));
            row.put("TargetToBleedThroughRatio", formatDouble(score.getTargetToBleedThroughRatio()));
            row.put("MaxAutofluorescenceMean", formatDouble(score.getMaxAutofluorescenceMean()));
            row.put("TargetToAutofluorescenceRatio",
                    formatDouble(score.getTargetToAutofluorescenceRatio()));
            row.put("HighAutofluorescenceOverlapFraction",
                    formatDouble(score.getHighAutofluorescenceOverlapFraction()));
            row.put("HighBleedThroughOverlapFraction",
                    formatDouble(score.getHighBleedThroughOverlapFraction()));
            row.put("BrightTailScore", formatDouble(score.getBrightTailScore()));
            row.put("CorrectedTargetMean", formatDouble(score.getCorrectedTargetMean()));
            row.put("CorrectedTargetP99", formatDouble(score.getCorrectedTargetP99()));
            row.put("CorrectedTargetRetentionFraction",
                    formatDouble(score.getCorrectedTargetRetentionFraction()));
            row.put("ContaminationScore", formatDouble(score.getContaminationScore()));
            row.put("KeepObject", score.isKeepObject() ? "true" : "false");
            row.put("RejectReason", clean(score.getRejectReason()));
            row.put("RunAction", clean(runAction));
            rows.add(row);
        }
        return rows;
    }

    public static void writePerObjectScores(String directory,
                                            List<Map<String, String>> rows) throws IOException {
        writePerObjectScores(directory, rows, "");
    }

    public static void writePerObjectScores(String directory,
                                            List<Map<String, String>> rows,
                                            String runId) throws IOException {
        writeCsv(perObjectScoresFile(directory), rows, PER_OBJECT_COLUMNS,
                new Comparator<Map<String, String>>() {
                    @Override
                    public int compare(Map<String, String> left, Map<String, String> right) {
                        int cmp = Integer.compare(seriesIndex(left), seriesIndex(right));
                        if (cmp != 0) {
                            return cmp;
                        }
                        cmp = clean(left.get("ObjectMapPath")).compareTo(clean(right.get("ObjectMapPath")));
                        if (cmp != 0) {
                            return cmp;
                        }
                        return Integer.compare(parseInt(left.get("ObjectID"), Integer.MAX_VALUE),
                                parseInt(right.get("ObjectID"), Integer.MAX_VALUE));
                    }
                }, runId);
    }

    public static Map<Integer, List<Map<String, String>>> readObjectRowsBySeriesIndex(String directory)
            throws IOException {
        LinkedHashMap<Integer, List<Map<String, String>>> rows =
                new LinkedHashMap<Integer, List<Map<String, String>>>();
        File file = perObjectScoresReadFile(directory);
        if (!file.isFile()) {
            return rows;
        }
        for (Map<String, String> row : readAllRows(file)) {
            int seriesIndex = seriesIndex(row);
            if (seriesIndex < 0 || seriesIndex == Integer.MAX_VALUE) {
                continue;
            }
            Integer key = Integer.valueOf(seriesIndex);
            List<Map<String, String>> group = rows.get(key);
            if (group == null) {
                group = new ArrayList<Map<String, String>>();
                rows.put(key, group);
            }
            group.add(row);
        }
        return rows;
    }

    public static List<Map<String, String>> copyObjectRows(List<Map<String, String>> existingRows,
                                                           String runAction) {
        List<Map<String, String>> rows = new ArrayList<Map<String, String>>();
        if (existingRows == null) {
            return rows;
        }
        for (Map<String, String> existing : existingRows) {
            LinkedHashMap<String, String> row = new LinkedHashMap<String, String>();
            if (existing != null) {
                row.putAll(existing);
            }
            row.put("RunAction", clean(runAction));
            rows.add(row);
        }
        return rows;
    }

    public static boolean objectRowsReusable(String directory, List<Map<String, String>> rows) {
        if (rows == null || rows.isEmpty()) {
            return false;
        }
        boolean sawCleanedMap = false;
        Set<String> checked = new LinkedHashSet<String>();
        for (Map<String, String> row : rows) {
            String path = row == null ? "" : clean(row.get("CleanedObjectMapPath"));
            if (path.isEmpty() || !checked.add(path)) {
                continue;
            }
            sawCleanedMap = true;
            if (!new File(directory, path).isFile()) {
                return false;
            }
        }
        return sawCleanedMap;
    }

    private static File perObjectScoresReadFile(String directory) {
        return perObjectScoresFile(directory);
    }

    private static List<ObjectMapDescriptor> locateFromObjectCsv(String directory,
                                                                 int seriesIndex,
                                                                 String seriesName,
                                                                 String channelName,
                                                                 String animalName,
                                                                 File channelCsv)
            throws IOException {
        if (channelCsv == null || !channelCsv.isFile()) {
            return new ArrayList<ObjectMapDescriptor>();
        }
        List<Map<String, String>> rows = readAllRows(channelCsv);
        LinkedHashMap<String, DescriptorBuilder> builders =
                new LinkedHashMap<String, DescriptorBuilder>();
        for (Map<String, String> row : rows) {
            if (!matchesAnimal(row, animalName)) {
                continue;
            }
            int label = parseInt(firstNonBlank(row.get("Label"), row.get("ObjectID")), -1);
            if (label <= 0) {
                continue;
            }

            ObjectMetadata metadata = ObjectMetadata.fromRow(row, animalName);
            File objectMapFile = objectMapFileFromMetadata(
                    directory,
                    channelName,
                    metadata);
            String key = objectMapFile.getAbsolutePath();
            DescriptorBuilder builder = builders.get(key);
            if (builder == null) {
                builder = new DescriptorBuilder(
                        seriesIndex,
                        seriesName,
                        channelName,
                        objectMapFile,
                        channelCsv,
                        metadata);
                builders.put(key, builder);
            }
            builder.addMetadata(label, metadata);
        }

        List<ObjectMapDescriptor> descriptors = new ArrayList<ObjectMapDescriptor>();
        for (DescriptorBuilder builder : builders.values()) {
            if (builder.file.isFile()) {
                descriptors.add(builder.build());
            }
        }
        return descriptors;
    }

    private static List<ObjectMapDescriptor> scanObjectMapFiles(String directory,
                                                               int seriesIndex,
                                                               String seriesName,
                                                               String channelName,
                                                               String animalName) {
        List<ObjectMapDescriptor> descriptors = new ArrayList<ObjectMapDescriptor>();
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
        String prefix = safeChannelName(channelName) + "_objects";
        List<File> sorted = new ArrayList<File>();
        File perAnimal = new File(layout.analysisImagesSegmentationDir(), clean(animalName));
        if (perAnimal.isDirectory()) {
            File[] files = perAnimal.listFiles();
            if (files != null) {
                for (File file : files) {
                    String name = file.getName();
                    if (file.isFile()
                            && name.toLowerCase(Locale.US).endsWith(".tif")
                            && name.startsWith(prefix)) {
                        sorted.add(file);
                    }
                }
            }
        }
        Collections.sort(sorted);
        for (File file : sorted) {
            String suffix = suffixFromObjectMapFile(file, safeChannelName(channelName));
            ObjectMetadata metadata = new ObjectMetadata(
                    animalName,
                    "",
                    "",
                    suffix,
                    "",
                    suffix);
            descriptors.add(new ObjectMapDescriptor(
                    seriesIndex,
                    seriesName,
                    channelName,
                    file,
                    objectCsvFile(directory, channelName),
                    metadata,
                    new LinkedHashMap<Integer, ObjectMetadata>()));
        }
        return descriptors;
    }

    private static File objectMapFileFromMetadata(String directory,
                                                  String channelName,
                                                  ObjectMetadata metadata) {
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
        String suffix = buildFileSuffix(
                metadata.getHemisphere(),
                firstNonBlank(metadata.getRoi(), metadata.getRegion()),
                metadata.getAnimalName());
        String name = safeChannelName(channelName)
                + "_objects"
                + (suffix.isEmpty() ? "" : "_" + suffix)
                + ".tif";
        return new File(new File(layout.analysisImagesSegmentationDir(), metadata.getAnimalName()), name);
    }

    private static boolean matchesAnimal(Map<String, String> row, String animalName) {
        String rowAnimal = clean(row.get("Animal Name"));
        if (rowAnimal.isEmpty()) {
            rowAnimal = clean(row.get("AnimalName"));
        }
        return !rowAnimal.isEmpty() && rowAnimal.equals(clean(animalName));
    }

    private static List<Map<String, String>> readAllRows(File file) throws IOException {
        List<Map<String, String>> rows = new ArrayList<Map<String, String>>();
        CsvSupport.RecordReader reader = CsvSupport.openRecordReader(file);
        try {
            CsvSupport.Record headerRecord = reader.readRecord();
            if (headerRecord == null) {
                return rows;
            }
            String[] headers = CsvSupport.parseRecord(headerRecord.text);
            CsvSupport.Record record;
            while ((record = reader.readRecord()) != null) {
                if (CsvSupport.isBlankRecord(record.text)) {
                    continue;
                }
                String[] values = CsvSupport.parseRecord(record.text);
                LinkedHashMap<String, String> row = new LinkedHashMap<String, String>();
                for (int i = 0; i < headers.length; i++) {
                    row.put(headers[i], i < values.length ? values[i] : "");
                }
                rows.add(row);
            }
        } finally {
            reader.close();
        }
        return rows;
    }

    private static void writeCsv(File file,
                                 List<Map<String, String>> rows,
                                 List<String> fixedColumns,
                                 Comparator<Map<String, String>> comparator,
                                 String runId)
            throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create " + parent.getAbsolutePath());
        }

        List<Map<String, String>> sortedRows = new ArrayList<Map<String, String>>();
        if (rows != null) {
            sortedRows.addAll(rows);
        }
        if (comparator != null) {
            Collections.sort(sortedRows, comparator);
        }

        List<String> columns = RunIdCsv.withoutRunId(orderedColumns(sortedRows, fixedColumns));
        AtomicFileWriter.writeUtf8(file, new AtomicFileWriter.WriterAction() {
            @Override
            public void write(Writer writer) throws IOException {
                writer.write(CsvSupport.joinRow(RunIdCsv.appendRunIdHeader(columns)));
                writer.write("\n");
                for (Map<String, String> row : sortedRows) {
                    List<String> values = new ArrayList<String>(columns.size());
                    for (String column : columns) {
                        values.add(row == null ? "" : clean(row.get(column)));
                    }
                    writer.write(CsvSupport.joinRow(RunIdCsv.appendRunIdRow(values, runId)));
                    writer.write("\n");
                }
            }
        });
    }

    private static List<String> orderedColumns(List<Map<String, String>> rows, List<String> fixedColumns) {
        List<String> columns = new ArrayList<String>();
        Set<String> seen = new LinkedHashSet<String>();
        if (fixedColumns != null) {
            for (String fixedColumn : fixedColumns) {
                if (fixedColumn != null && !fixedColumn.trim().isEmpty() && seen.add(fixedColumn)) {
                    columns.add(fixedColumn);
                }
            }
        }
        List<String> extras = new ArrayList<String>();
        if (rows != null) {
            for (Map<String, String> row : rows) {
                if (row == null) {
                    continue;
                }
                for (String key : row.keySet()) {
                    if (key != null && !key.trim().isEmpty() && seen.add(key)) {
                        extras.add(key);
                    }
                }
            }
        }
        Collections.sort(extras);
        columns.addAll(extras);
        return columns;
    }

    private static int seriesIndex(Map<String, String> row) {
        if (row == null) {
            return Integer.MAX_VALUE;
        }
        return parseInt(row.get("SeriesIndex"), Integer.MAX_VALUE);
    }

    private static int parseInt(String value, int defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String buildFileSuffix(String hemisphere, String region, String animalName) {
        boolean hasHemisphere = hemisphere != null && !hemisphere.trim().isEmpty();
        boolean hasRegion = region != null && !region.trim().isEmpty();
        if (hasHemisphere && hasRegion) {
            return hemisphere.trim() + "_" + region.trim();
        }
        if (hasHemisphere) {
            return hemisphere.trim();
        }
        if (hasRegion) {
            return region.trim();
        }
        return clean(animalName);
    }

    private static String suffixFromObjectMapFile(File file, String safeTarget) {
        if (file == null) {
            return "";
        }
        String name = file.getName();
        if (name.toLowerCase(Locale.US).endsWith(".tif")) {
            name = name.substring(0, name.length() - 4);
        }
        String prefix = safeTarget + "_objects";
        if (name.equals(prefix)) {
            return "";
        }
        if (name.startsWith(prefix + "_")) {
            return name.substring(prefix.length() + 1);
        }
        return name;
    }

    private static String safeChannelName(String channelName) {
        String cleaned = channelName == null || channelName.trim().isEmpty()
                ? "target"
                : channelName.trim();
        return ChannelFilenameCodec.toSafe(cleaned);
    }

    private static String firstNonBlank(String first, String second) {
        String cleanFirst = clean(first);
        if (!cleanFirst.isEmpty()) {
            return cleanFirst;
        }
        return clean(second);
    }

    private static String formatDouble(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "";
        }
        return String.format(Locale.US, "%.6f", value);
    }

    private static String relativePath(String directory, File file) {
        if (directory == null || file == null) {
            return "";
        }
        try {
            Path root = Paths.get(directory).toAbsolutePath().normalize();
            Path target = file.toPath().toAbsolutePath().normalize();
            return root.relativize(target).toString();
        } catch (Exception e) {
            return file.getPath();
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static void putRunMetadata(Map<String, String> row,
                                       SpectralOutputWriter.RunMetadata runMetadata) {
        if (row == null || runMetadata == null) {
            return;
        }
        row.put("ConfigVersion", String.valueOf(runMetadata.configVersion));
        row.put("ConfigId", clean(runMetadata.configId));
        row.put("PipelinePresetId", clean(runMetadata.pipelinePresetId));
        row.put("PipelineStackId", clean(runMetadata.pipelineStackId));
    }

    private static final class DescriptorBuilder {
        private final int seriesIndex;
        private final String seriesName;
        private final String channelName;
        private final File file;
        private final File sourceObjectCsvFile;
        private final ObjectMetadata defaultMetadata;
        private final LinkedHashMap<Integer, ObjectMetadata> metadataByLabel =
                new LinkedHashMap<Integer, ObjectMetadata>();

        private DescriptorBuilder(int seriesIndex,
                                  String seriesName,
                                  String channelName,
                                  File file,
                                  File sourceObjectCsvFile,
                                  ObjectMetadata defaultMetadata) {
            this.seriesIndex = seriesIndex;
            this.seriesName = seriesName;
            this.channelName = channelName;
            this.file = file;
            this.sourceObjectCsvFile = sourceObjectCsvFile;
            this.defaultMetadata = defaultMetadata;
        }

        private void addMetadata(int label, ObjectMetadata metadata) {
            metadataByLabel.put(Integer.valueOf(label), metadata);
        }

        private ObjectMapDescriptor build() {
            return new ObjectMapDescriptor(
                    seriesIndex,
                    seriesName,
                    channelName,
                    file,
                    sourceObjectCsvFile,
                    defaultMetadata,
                    metadataByLabel);
        }
    }

    public static final class ObjectMapDescriptor {
        private final int seriesIndex;
        private final String seriesName;
        private final String channelName;
        private final File file;
        private final File sourceObjectCsvFile;
        private final ObjectMetadata defaultMetadata;
        private final LinkedHashMap<Integer, ObjectMetadata> metadataByLabel;

        private ObjectMapDescriptor(int seriesIndex,
                                    String seriesName,
                                    String channelName,
                                    File file,
                                    File sourceObjectCsvFile,
                                    ObjectMetadata defaultMetadata,
                                    Map<Integer, ObjectMetadata> metadataByLabel) {
            this.seriesIndex = seriesIndex;
            this.seriesName = clean(seriesName);
            this.channelName = clean(channelName);
            this.file = file;
            this.sourceObjectCsvFile = sourceObjectCsvFile;
            this.defaultMetadata = defaultMetadata == null
                    ? new ObjectMetadata("", "", "", "", "", "")
                    : defaultMetadata;
            this.metadataByLabel = new LinkedHashMap<Integer, ObjectMetadata>();
            if (metadataByLabel != null) {
                this.metadataByLabel.putAll(metadataByLabel);
            }
        }

        public int getSeriesIndex() {
            return seriesIndex;
        }

        public String getSeriesName() {
            return seriesName;
        }

        public String getChannelName() {
            return channelName;
        }

        public File getFile() {
            return file;
        }

        public File getSourceObjectCsvFile() {
            return sourceObjectCsvFile;
        }

        public ObjectMetadata metadataForLabel(int label) {
            ObjectMetadata metadata = metadataByLabel.get(Integer.valueOf(label));
            return metadata == null ? defaultMetadata : metadata;
        }

        public String getOutputSuffix() {
            return defaultMetadata.getOutputSuffix();
        }
    }

    public static final class ObjectMetadata {
        private final String animalName;
        private final String hemisphere;
        private final String region;
        private final String roi;
        private final String scn;
        private final String outputSuffix;

        private ObjectMetadata(String animalName,
                               String hemisphere,
                               String region,
                               String roi,
                               String scn,
                               String outputSuffix) {
            this.animalName = clean(animalName);
            this.hemisphere = clean(hemisphere);
            this.region = clean(region);
            this.roi = clean(roi);
            this.scn = clean(scn);
            this.outputSuffix = clean(outputSuffix);
        }

        private static ObjectMetadata fromRow(Map<String, String> row, String fallbackAnimalName) {
            String animal = firstNonBlank(row.get("Animal Name"), row.get("AnimalName"));
            if (animal.isEmpty()) {
                animal = fallbackAnimalName;
            }
            String hemisphere = clean(row.get("Hemisphere"));
            String region = clean(row.get("Region"));
            String roi = clean(row.get("ROI"));
            String scn = clean(row.get("SCN"));
            String suffix = buildFileSuffix(hemisphere, firstNonBlank(roi, region), animal);
            return new ObjectMetadata(animal, hemisphere, region, roi, scn, suffix);
        }

        public String getAnimalName() {
            return animalName;
        }

        public String getHemisphere() {
            return hemisphere;
        }

        public String getRegion() {
            return region;
        }

        public String getRoi() {
            return roi;
        }

        public String getScn() {
            return scn;
        }

        public String getOutputSuffix() {
            return outputSuffix;
        }
    }
}
