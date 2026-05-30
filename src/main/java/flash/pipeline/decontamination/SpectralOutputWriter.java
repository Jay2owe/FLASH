package flash.pipeline.decontamination;

import flash.pipeline.io.CsvSupport;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.naming.ChannelFilenameCodec;
import flash.pipeline.results.RunIdCsv;
import ij.ImagePlus;
import ij.io.FileSaver;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Writes Spectral Decontamination batch outputs and reloads prior CSV rows for skip-existing runs.
 */
public final class SpectralOutputWriter {

    public static final String PER_IMAGE_SUMMARY_FILENAME = "per_image_summary.csv";
    public static final String CORRECTION_COEFFICIENTS_FILENAME = "correction_coefficients.csv";
    public static final String ANALYSIS_DETAILS_FILENAME = "analysis_details.txt";

    private static final List<String> PER_IMAGE_SUMMARY_COLUMNS = Arrays.asList(
            "SeriesIndex",
            "SeriesNumber",
            "SeriesName",
            "ImageFolder",
            "Condition",
            "ConditionRole",
            "Goal",
            "ConfigVersion",
            "ConfigId",
            "PipelinePresetId",
            "PipelineStackId",
            "PipelinePreset",
            "PipelineFeatures",
            "RunAction",
            "CorrectedImageWritten",
            "MaskWritten",
            "CorrectedImagePath",
            "MaskPath",
            "ZSliceRange",
            "FeatureSummaryCount",
            "Message");

    private static final List<String> COEFFICIENT_COLUMNS = Arrays.asList(
            "SeriesIndex",
            "SeriesNumber",
            "SeriesName",
            "Condition",
            "ConditionRole",
            "ConfigVersion",
            "ConfigId",
            "PipelinePresetId",
            "PipelineStackId",
            "FeatureId",
            "FeatureName",
            "Metric",
            "Value",
            "RunAction");

    private SpectralOutputWriter() {
    }

    public static File dataOutputDirectory(String directory) {
        return FlashProjectLayout.forDirectory(directory).tablesSpectralWriteDir();
    }

    public static File imageOutputRoot(String directory) {
        return FlashProjectLayout.forDirectory(directory).analysisImagesSpectralDir();
    }

    public static File perImageSummaryFile(String directory) {
        return new File(dataOutputDirectory(directory), PER_IMAGE_SUMMARY_FILENAME);
    }

    public static File correctionCoefficientsFile(String directory) {
        return new File(dataOutputDirectory(directory), CORRECTION_COEFFICIENTS_FILENAME);
    }

    public static File analysisDetailsFile(String directory) {
        return new File(dataOutputDirectory(directory), ANALYSIS_DETAILS_FILENAME);
    }

    public static ExpectedOutputs expectedOutputs(String directory,
                                                 int seriesIndex,
                                                 String seriesName,
                                                 String targetChannelName) {
        String imageFolderName = buildImageFolderName(seriesIndex, seriesName);
        File imageOutputDirectory = new File(imageOutputRoot(directory), imageFolderName);
        String safeTargetName = ChannelFilenameCodec.toSafe(
                targetChannelName == null || targetChannelName.trim().isEmpty()
                        ? "target"
                        : targetChannelName.trim());
        File correctedImageFile = new File(imageOutputDirectory, "corrected_" + safeTargetName + ".tif");
        File maskImageFile = new File(imageOutputDirectory, "final_mask_" + safeTargetName + ".tif");
        return new ExpectedOutputs(
                imageFolderName,
                imageOutputDirectory,
                relativePath(directory, imageOutputDirectory),
                correctedImageFile,
                maskImageFile,
                Collections.singletonList(correctedImageFile),
                Collections.singletonList(maskImageFile));
    }

    public static boolean expectedOutputsExist(ExpectedOutputs outputs,
                                              boolean needsCorrectedImage,
                                              boolean needsMaskImage) {
        if (outputs == null) {
            return false;
        }
        return (!needsCorrectedImage || firstExistingFile(outputs.correctedImageReadCandidates) != null)
                && (!needsMaskImage || firstExistingFile(outputs.maskImageReadCandidates) != null);
    }

    public static List<File> correctedImageReadCandidates(ExpectedOutputs outputs) {
        if (outputs == null) {
            return Collections.emptyList();
        }
        return outputs.correctedImageReadCandidates;
    }

    public static List<File> maskImageReadCandidates(ExpectedOutputs outputs) {
        if (outputs == null) {
            return Collections.emptyList();
        }
        return outputs.maskImageReadCandidates;
    }

    public static void saveCorrectedImage(ImagePlus image, File outputFile) throws IOException {
        saveImage(image, outputFile);
    }

    public static void saveMaskImage(ImagePlus image, File outputFile) throws IOException {
        saveImage(image, outputFile);
    }

    public static File parameterMapFile(ExpectedOutputs outputs, String parameterMapKey) {
        if (outputs == null) {
            throw new IllegalArgumentException("outputs must not be null");
        }
        String safeName = ChannelFilenameCodec.toSafe(
                parameterMapKey == null || parameterMapKey.trim().isEmpty()
                        ? "parameter_map"
                        : parameterMapKey.trim());
        return new File(outputs.imageOutputDirectory, safeName + ".tif");
    }

    public static void saveParameterMap(ImagePlus image, File outputFile) throws IOException {
        saveImage(image, outputFile);
    }

    public static Map<Integer, Map<String, String>> readPerImageSummaryRows(String directory) throws IOException {
        return readRowsBySeriesIndex(firstExistingFile(perImageSummaryReadFiles(directory)));
    }

    public static Map<Integer, List<Map<String, String>>> readCoefficientRows(String directory) throws IOException {
        return readGroupedRowsBySeriesIndex(firstExistingFile(correctionCoefficientsReadFiles(directory)));
    }

    public static Map<String, String> buildPerImageSummaryRow(int seriesIndex,
                                                              String seriesName,
                                                              String imageFolderRelativePath,
                                                              String conditionName,
                                                              String conditionRole,
                                                              String goalLabel,
                                                              RunMetadata runMetadata,
                                                              String pipelinePreset,
                                                              String pipelineDescription,
                                                              String runAction,
                                                              File correctedImageFile,
                                                              File maskImageFile,
                                                              String zSliceRange,
                                                              List<CorrectionPipeline.FeatureSummary> featureSummaries,
                                                              String message,
                                                              String directory) {
        LinkedHashMap<String, String> row = new LinkedHashMap<String, String>();
        row.put("SeriesIndex", String.valueOf(seriesIndex));
        row.put("SeriesNumber", String.valueOf(seriesIndex + 1));
        row.put("SeriesName", clean(seriesName));
        row.put("ImageFolder", clean(imageFolderRelativePath));
        row.put("Condition", clean(conditionName));
        row.put("ConditionRole", clean(conditionRole));
        row.put("Goal", clean(goalLabel));
        putRunMetadata(row, runMetadata);
        row.put("PipelinePreset", clean(pipelinePreset));
        row.put("PipelineFeatures", clean(pipelineDescription));
        row.put("RunAction", clean(runAction));
        row.put("CorrectedImageWritten", correctedImageFile == null ? "false" : "true");
        row.put("MaskWritten", maskImageFile == null ? "false" : "true");
        row.put("CorrectedImagePath", correctedImageFile == null ? "" : relativePath(directory, correctedImageFile));
        row.put("MaskPath", maskImageFile == null ? "" : relativePath(directory, maskImageFile));
        row.put("ZSliceRange", clean(zSliceRange));
        row.put("FeatureSummaryCount", String.valueOf(featureSummaries == null ? 0 : featureSummaries.size()));
        row.put("Message", clean(message));
        return row;
    }

    public static List<Map<String, String>> buildCoefficientRows(int seriesIndex,
                                                                 String seriesName,
                                                                 String conditionName,
                                                                 String conditionRole,
                                                                 RunMetadata runMetadata,
                                                                 String runAction,
                                                                 List<CorrectionPipeline.FeatureSummary> featureSummaries) {
        List<Map<String, String>> rows = new ArrayList<Map<String, String>>();
        if (featureSummaries == null) {
            return rows;
        }
        for (CorrectionPipeline.FeatureSummary summary : featureSummaries) {
            if (summary == null) {
                continue;
            }
            for (Map.Entry<String, String> entry : summary.getValues().entrySet()) {
                if (entry.getKey() == null || entry.getKey().trim().isEmpty()) {
                    continue;
                }
                if (entry.getValue() == null || entry.getValue().trim().isEmpty()) {
                    continue;
                }
                LinkedHashMap<String, String> row = new LinkedHashMap<String, String>();
                row.put("SeriesIndex", String.valueOf(seriesIndex));
                row.put("SeriesNumber", String.valueOf(seriesIndex + 1));
                row.put("SeriesName", clean(seriesName));
                row.put("Condition", clean(conditionName));
                row.put("ConditionRole", clean(conditionRole));
                putRunMetadata(row, runMetadata);
                row.put("FeatureId", clean(summary.getFeatureId()));
                row.put("FeatureName", clean(summary.getFeatureName()));
                row.put("Metric", clean(entry.getKey()));
                row.put("Value", clean(entry.getValue()));
                row.put("RunAction", clean(runAction));
                rows.add(row);
            }
        }
        return rows;
    }

    public static Map<String, String> copySummaryRow(Map<String, String> existing,
                                                     String runAction,
                                                     String message) {
        LinkedHashMap<String, String> row = new LinkedHashMap<String, String>();
        if (existing != null) {
            row.putAll(existing);
        }
        row.put("RunAction", clean(runAction));
        if (message != null && !message.trim().isEmpty()) {
            row.put("Message", message.trim());
        }
        return row;
    }

    public static List<Map<String, String>> copyCoefficientRows(List<Map<String, String>> existingRows,
                                                                String runAction) {
        List<Map<String, String>> rows = new ArrayList<Map<String, String>>();
        if (existingRows == null) {
            return rows;
        }
        for (Map<String, String> existing : existingRows) {
            LinkedHashMap<String, String> row = new LinkedHashMap<String, String>();
            row.putAll(existing);
            row.put("RunAction", clean(runAction));
            rows.add(row);
        }
        return rows;
    }

    public static void writePerImageSummary(String directory,
                                            List<Map<String, String>> rows) throws IOException {
        writePerImageSummary(directory, rows, "");
    }

    public static void writePerImageSummary(String directory,
                                            List<Map<String, String>> rows,
                                            String runId) throws IOException {
        writeCsv(perImageSummaryFile(directory), rows, PER_IMAGE_SUMMARY_COLUMNS,
                new Comparator<Map<String, String>>() {
                    @Override
                    public int compare(Map<String, String> left, Map<String, String> right) {
                        return Integer.compare(seriesIndex(left), seriesIndex(right));
                    }
                }, runId);
    }

    public static void writeCorrectionCoefficients(String directory,
                                                   List<Map<String, String>> rows) throws IOException {
        writeCorrectionCoefficients(directory, rows, "");
    }

    public static void writeCorrectionCoefficients(String directory,
                                                   List<Map<String, String>> rows,
                                                   String runId) throws IOException {
        writeCsv(correctionCoefficientsFile(directory), rows, COEFFICIENT_COLUMNS,
                new Comparator<Map<String, String>>() {
                    @Override
                    public int compare(Map<String, String> left, Map<String, String> right) {
                        int cmp = Integer.compare(seriesIndex(left), seriesIndex(right));
                        if (cmp != 0) {
                            return cmp;
                        }
                        cmp = clean(left.get("FeatureId")).compareTo(clean(right.get("FeatureId")));
                        if (cmp != 0) {
                            return cmp;
                        }
                        return clean(left.get("Metric")).compareTo(clean(right.get("Metric")));
                    }
                }, runId);
    }

    public static File writeAnalysisDetails(String directory, AnalysisDetails details) throws IOException {
        File file = analysisDetailsFile(directory);
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create " + parent.getAbsolutePath());
        }

        AtomicFileWriter.writeUtf8(file, new AtomicFileWriter.WriterAction() {
            @Override
            public void write(Writer writer) throws IOException {
                writer.write("Spectral Decontamination - Analysis Details\n");
                writer.write("=========================================\n\n");
                writer.write("Timestamp: "
                        + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date()) + "\n");
                writer.write("Goal: " + clean(details.goalLabel) + "\n");
                writer.write("Config Version: " + details.configVersion + "\n");
                writer.write("Config ID: " + clean(details.configId) + "\n");
                writer.write("Correction Preset ID: " + clean(details.pipelinePresetId) + "\n");
                writer.write("Correction Stack ID: " + clean(details.pipelineStackId) + "\n");
                writer.write("Target Channel: " + clean(details.targetChannelName) + "\n");
                writer.write("Bleed-through Channels: " + clean(details.bleedThroughChannels) + "\n");
                writer.write("Autofluorescence Channels: " + clean(details.autofluorescenceChannels) + "\n");
                writer.write("Excluded Channels: " + clean(details.excludedChannels) + "\n");
                writer.write("Condition Source: " + clean(details.conditionSourceLabel) + "\n");
                writer.write("Control Conditions: " + clean(details.controlConditions) + "\n");
                writer.write("Experimental Conditions: " + clean(details.experimentalConditions) + "\n");
                writer.write("Correction Preset: " + clean(details.pipelinePresetLabel) + "\n");
                writer.write("Correction Stack: " + clean(details.pipelineDescription) + "\n");
                writer.write("Z-Slice Subset: " + clean(details.zSliceSummary) + "\n");
                writer.write("Skip Existing: " + details.skipExisting + "\n");
                writer.write("Parallel Threads: " + details.parallelThreads + "\n");
                writer.write("Total Images: " + details.totalImages + "\n");
                writer.write("Images Processed: " + details.processedImages + "\n");
                writer.write("Images Reused From Existing Outputs: " + details.skippedImages + "\n");
                writer.write("Images Failed: " + details.failedImages + "\n");
                writer.write("Corrected Images Written: " + details.correctedImagesWritten + "\n");
                writer.write("Mask Images Written: " + details.maskImagesWritten + "\n");
                writer.write("Per-Image Summary: " + clean(details.perImageSummaryPath) + "\n");
                writer.write("Correction Coefficients: " + clean(details.correctionCoefficientsPath) + "\n");
                writer.write("Per-Object Scores: " + clean(details.perObjectScoresPath) + "\n");
                writer.write("Preview Selection: " + clean(details.previewSelectionPath) + "\n");
                writer.write("Object Score Rows: " + details.objectScoreRows + "\n");
                writer.write("Cleaned Object Maps Written: " + details.cleanedObjectMapsWritten + "\n");
                writer.write("Objects Kept: " + details.objectsKept + "\n");
                writer.write("Objects Removed: " + details.objectsRejected + "\n");
                writer.write("Runtime: " + formatDuration(details.runtimeMs) + "\n");
            }
        });

        return file;
    }

    private static void saveImage(ImagePlus image, File outputFile) throws IOException {
        if (image == null) {
            throw new IllegalArgumentException("image must not be null");
        }
        if (outputFile == null) {
            throw new IllegalArgumentException("outputFile must not be null");
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

    private static Map<Integer, Map<String, String>> readRowsBySeriesIndex(File file) throws IOException {
        LinkedHashMap<Integer, Map<String, String>> rows = new LinkedHashMap<Integer, Map<String, String>>();
        if (file == null || !file.isFile()) {
            return rows;
        }

        List<Map<String, String>> loaded = readAllRows(file);
        for (Map<String, String> row : loaded) {
            int seriesIndex = seriesIndex(row);
            if (seriesIndex >= 0 && seriesIndex != Integer.MAX_VALUE) {
                rows.put(Integer.valueOf(seriesIndex), row);
            }
        }
        return rows;
    }

    private static List<File> perImageSummaryReadFiles(String directory) {
        return Collections.singletonList(perImageSummaryFile(directory));
    }

    private static List<File> correctionCoefficientsReadFiles(String directory) {
        return Collections.singletonList(correctionCoefficientsFile(directory));
    }

    private static Map<Integer, List<Map<String, String>>> readGroupedRowsBySeriesIndex(File file) throws IOException {
        LinkedHashMap<Integer, List<Map<String, String>>> rows =
                new LinkedHashMap<Integer, List<Map<String, String>>>();
        if (file == null || !file.isFile()) {
            return rows;
        }

        List<Map<String, String>> loaded = readAllRows(file);
        for (Map<String, String> row : loaded) {
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

    private static List<Map<String, String>> readAllRows(File file) throws IOException {
        List<Map<String, String>> rows = new ArrayList<Map<String, String>>();
        try (CsvSupport.RecordReader reader = CsvSupport.openRecordReader(file)) {
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
        }
        return rows;
    }

    private static void writeCsv(File file,
                                 List<Map<String, String>> rows,
                                 List<String> fixedColumns,
                                 Comparator<Map<String, String>> comparator,
                                 String runId) throws IOException {
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
        String value = row.get("SeriesIndex");
        if (value == null || value.trim().isEmpty()) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static String buildImageFolderName(int seriesIndex, String seriesName) {
        String cleaned = sanitizeSeriesName(seriesName);
        if (cleaned.isEmpty()) {
            cleaned = "Series " + (seriesIndex + 1);
        }
        return String.format(Locale.US, "Series %03d - %s", seriesIndex + 1, cleaned);
    }

    private static String sanitizeSeriesName(String seriesName) {
        String cleaned = clean(seriesName);
        if (cleaned.isEmpty()) {
            return "";
        }
        cleaned = cleaned.replaceAll("[\\\\/:*?\"<>|]", "_");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        while (cleaned.endsWith(".") || cleaned.endsWith(" ")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }
        return cleaned;
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

    private static File firstExistingFile(List<File> files) {
        if (files == null) {
            return null;
        }
        for (File file : files) {
            if (file != null && file.isFile()) {
                return file;
            }
        }
        return null;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static void putRunMetadata(Map<String, String> row, RunMetadata runMetadata) {
        if (row == null || runMetadata == null) {
            return;
        }
        row.put("ConfigVersion", String.valueOf(runMetadata.configVersion));
        row.put("ConfigId", clean(runMetadata.configId));
        row.put("PipelinePresetId", clean(runMetadata.pipelinePresetId));
        row.put("PipelineStackId", clean(runMetadata.pipelineStackId));
    }

    private static String formatDuration(long runtimeMs) {
        long seconds = Math.max(0L, runtimeMs / 1000L);
        if (seconds < 60L) {
            return seconds + "s";
        }
        long minutes = seconds / 60L;
        long remainingSeconds = seconds % 60L;
        if (minutes < 60L) {
            return minutes + "m " + remainingSeconds + "s";
        }
        long hours = minutes / 60L;
        long remainingMinutes = minutes % 60L;
        return hours + "h " + remainingMinutes + "m";
    }

    public static final class ExpectedOutputs {
        public final String imageFolderName;
        public final File imageOutputDirectory;
        public final String imageOutputRelativePath;
        public final File correctedImageFile;
        public final File maskImageFile;
        private final List<File> correctedImageReadCandidates;
        private final List<File> maskImageReadCandidates;

        private ExpectedOutputs(String imageFolderName,
                                File imageOutputDirectory,
                                String imageOutputRelativePath,
                                File correctedImageFile,
                                File maskImageFile,
                                List<File> correctedImageReadCandidates,
                                List<File> maskImageReadCandidates) {
            this.imageFolderName = imageFolderName;
            this.imageOutputDirectory = imageOutputDirectory;
            this.imageOutputRelativePath = clean(imageOutputRelativePath);
            this.correctedImageFile = correctedImageFile;
            this.maskImageFile = maskImageFile;
            this.correctedImageReadCandidates = correctedImageReadCandidates == null
                    ? Collections.<File>emptyList()
                    : Collections.unmodifiableList(new ArrayList<File>(correctedImageReadCandidates));
            this.maskImageReadCandidates = maskImageReadCandidates == null
                    ? Collections.<File>emptyList()
                    : Collections.unmodifiableList(new ArrayList<File>(maskImageReadCandidates));
        }
    }

    public static final class AnalysisDetails {
        public String goalLabel;
        public int configVersion;
        public String configId;
        public String pipelinePresetId;
        public String pipelineStackId;
        public String targetChannelName;
        public String bleedThroughChannels;
        public String autofluorescenceChannels;
        public String excludedChannels;
        public String conditionSourceLabel;
        public String controlConditions;
        public String experimentalConditions;
        public String pipelinePresetLabel;
        public String pipelineDescription;
        public String zSliceSummary;
        public boolean skipExisting;
        public int parallelThreads;
        public int totalImages;
        public int processedImages;
        public int skippedImages;
        public int failedImages;
        public int correctedImagesWritten;
        public int maskImagesWritten;
        public int objectScoreRows;
        public int cleanedObjectMapsWritten;
        public int objectsKept;
        public int objectsRejected;
        public String perImageSummaryPath;
        public String correctionCoefficientsPath;
        public String perObjectScoresPath;
        public String previewSelectionPath;
        public long runtimeMs;
    }

    public static final class RunMetadata {
        public final int configVersion;
        public final String configId;
        public final String pipelinePresetId;
        public final String pipelinePresetLabel;
        public final String pipelineStackId;
        public final String pipelineDescription;

        private RunMetadata(int configVersion,
                            String configId,
                            String pipelinePresetId,
                            String pipelinePresetLabel,
                            String pipelineStackId,
                            String pipelineDescription) {
            this.configVersion = configVersion;
            this.configId = clean(configId);
            this.pipelinePresetId = clean(pipelinePresetId);
            this.pipelinePresetLabel = clean(pipelinePresetLabel);
            this.pipelineStackId = clean(pipelineStackId);
            this.pipelineDescription = clean(pipelineDescription);
        }

        public static RunMetadata fromConfig(SpectralDecontaminationConfig config,
                                             CorrectionFeatureRegistry registry) {
            SpectralDecontaminationConfig resolved = config == null
                    ? new SpectralDecontaminationConfig()
                    : config.copy();
            CorrectionPipeline pipeline = resolved.getCorrectionPipeline();
            String presetId = clean(pipeline.getPresetId());
            String presetLabel = presetId;
            if (registry != null) {
                CorrectionFeatureRegistry.PresetDefinition preset = registry.getPreset(presetId);
                if (preset != null) {
                    presetLabel = preset.getDisplayName();
                }
            }
            String stackId = pipelineStackId(pipeline);
            String description = pipeline.describe(registry);
            return new RunMetadata(
                    resolved.getVersion(),
                    "sha256:" + sha256Hex(canonicalConfigString(resolved)),
                    presetId,
                    presetLabel,
                    stackId,
                    description);
        }

        private static String pipelineStackId(CorrectionPipeline pipeline) {
            if (pipeline == null || pipeline.getFeatureIds().isEmpty()) {
                return "none";
            }
            StringBuilder sb = new StringBuilder();
            List<String> featureIds = pipeline.getFeatureIds();
            for (int i = 0; i < featureIds.size(); i++) {
                if (i > 0) {
                    sb.append(">");
                }
                sb.append(clean(featureIds.get(i)).toLowerCase(Locale.US));
            }
            return sb.toString();
        }

        private static String canonicalConfigString(SpectralDecontaminationConfig config) {
            StringBuilder sb = new StringBuilder();
            appendField(sb, "version", String.valueOf(config.getVersion()));
            appendField(sb, "goal", config.getGoal().getKey());
            appendField(sb, "conditionSource", config.getConditionSource().getKey());
            appendField(sb, "targetChannelIndex", String.valueOf(config.getTargetChannelIndex()));
            appendSortedIntegers(sb, "bleedThroughChannelIndexes", config.getBleedThroughChannelIndexes());
            appendSortedIntegers(sb, "autofluorescenceChannelIndexes", config.getAutofluorescenceChannelIndexes());
            appendSortedIntegers(sb, "excludedChannelIndexes", config.getExcludedChannelIndexes());
            appendSortedStrings(sb, "controlConditionNames", config.getControlConditionNames());
            appendSortedStrings(sb, "experimentalConditionNames", config.getExperimentalConditionNames());

            CorrectionPipeline pipeline = config.getCorrectionPipeline();
            appendField(sb, "correctionPresetId", pipeline.getPresetId());
            appendField(sb, "expertMode", Boolean.toString(pipeline.isExpertMode()));
            appendOrderedStrings(sb, "correctionFeatureIds", pipeline.getFeatureIds());

            TreeMap<String, CorrectionPipeline.Settings> featureSettings =
                    new TreeMap<String, CorrectionPipeline.Settings>(String.CASE_INSENSITIVE_ORDER);
            featureSettings.putAll(config.getFeatureSettingsById());
            for (Map.Entry<String, CorrectionPipeline.Settings> featureEntry : featureSettings.entrySet()) {
                String featureId = clean(featureEntry.getKey()).toLowerCase(Locale.US);
                appendField(sb, "featureId", featureId);
                TreeMap<String, String> settings =
                        new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
                if (featureEntry.getValue() != null) {
                    settings.putAll(featureEntry.getValue().getValues());
                }
                for (Map.Entry<String, String> settingEntry : settings.entrySet()) {
                    appendField(sb,
                            "featureSetting." + featureId + "." + clean(settingEntry.getKey()).toLowerCase(Locale.US),
                            clean(settingEntry.getValue()));
                }
            }
            return sb.toString();
        }

        private static void appendField(StringBuilder sb, String key, String value) {
            sb.append(clean(key)).append("=").append(clean(value)).append("\n");
        }

        private static void appendSortedIntegers(StringBuilder sb, String key, List<Integer> values) {
            List<Integer> sorted = new ArrayList<Integer>();
            if (values != null) {
                sorted.addAll(values);
            }
            Collections.sort(sorted);
            for (Integer value : sorted) {
                appendField(sb, key, value == null ? "" : String.valueOf(value.intValue()));
            }
            if (sorted.isEmpty()) {
                appendField(sb, key, "");
            }
        }

        private static void appendSortedStrings(StringBuilder sb, String key, List<String> values) {
            List<String> sorted = new ArrayList<String>();
            if (values != null) {
                for (String value : values) {
                    String cleaned = clean(value);
                    if (!cleaned.isEmpty()) {
                        sorted.add(cleaned);
                    }
                }
            }
            Collections.sort(sorted, String.CASE_INSENSITIVE_ORDER);
            for (String value : sorted) {
                appendField(sb, key, value);
            }
            if (sorted.isEmpty()) {
                appendField(sb, key, "");
            }
        }

        private static void appendOrderedStrings(StringBuilder sb, String key, List<String> values) {
            if (values == null || values.isEmpty()) {
                appendField(sb, key, "");
                return;
            }
            for (String value : values) {
                appendField(sb, key, value);
            }
        }

        private static String sha256Hex(String value) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] bytes = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder(bytes.length * 2);
                for (byte valueByte : bytes) {
                    int unsigned = valueByte & 0xff;
                    if (unsigned < 16) {
                        sb.append('0');
                    }
                    sb.append(Integer.toHexString(unsigned));
                }
                return sb.toString();
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is not available.", e);
            }
        }
    }
}
