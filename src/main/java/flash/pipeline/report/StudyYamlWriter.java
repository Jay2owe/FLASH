package flash.pipeline.report;

import flash.pipeline.io.DeferredImageSupplier;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.io.ImageSourceDispatcher;
import flash.pipeline.io.LifIO;
import flash.pipeline.io.SeriesMeta;
import flash.pipeline.naming.ImageNameParser;
import flash.pipeline.naming.NameParts;
import flash.pipeline.project.ProjectFile;
import flash.pipeline.project.ProjectFileIO;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Writes a REMBI-aligned, project-level bioimage metadata sidecar.
 */
public final class StudyYamlWriter {

    private static final String SCHEMA = "flash.rembi.study";

    private StudyYamlWriter() {
    }

    static ProjectMetadata capture(String directory) {
        ProjectMetadata metadata = new ProjectMetadata(directory);
        if (directory == null || directory.trim().isEmpty()) {
            metadata.warnings.add("Project directory was not set; only run metadata could be exported.");
            return metadata;
        }

        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
        metadata.project = ProjectFileIO.read(layout.configurationWriteDir());
        metadata.projectName = projectName(metadata.project, layout.projectRoot());
        metadata.files.addAll(discoverFiles(layout.projectRoot(), metadata.project));

        boolean usedSeriesMetadata = false;
        if (metadata.project != null) {
            ProjectImageCapture capture = buildProjectImages(metadata.project, metadata.projectName,
                    metadata.warnings);
            metadata.images.addAll(capture.images);
            usedSeriesMetadata = capture.usedSeriesMetadata;
        } else if (canReadMetadataWithoutPrompt(layout.projectRoot(), metadata.warnings)) {
            List<SeriesMeta> seriesMetas = Collections.emptyList();
            try {
                seriesMetas = ImageSourceDispatcher.readAllMetadata(directory);
                usedSeriesMetadata = !seriesMetas.isEmpty();
            } catch (Exception e) {
                metadata.warnings.add("Image metadata could not be read: " + e.getMessage());
            }
            metadata.images.addAll(buildImages(seriesMetas, metadata.files));
        }

        if (metadata.images.isEmpty()) {
            metadata.images.addAll(buildImagesFromItems(itemRefs(metadata.project)));
        }
        if (metadata.images.isEmpty()) {
            metadata.images.addAll(buildImagesFromFiles(metadata.files));
        }

        if (usedSeriesMetadata) {
            metadata.metadataSources.add("Bio-Formats SeriesMeta");
        }
        metadata.metadataSources.add("ImageNameParser");
        if (metadata.project != null) {
            metadata.metadataSources.add("project.json");
        }
        return metadata;
    }

    static void write(File file, QualityReport report, ProjectMetadata metadata) throws IOException {
        String yaml = render(report, metadata == null ? new ProjectMetadata(null) : metadata);
        File parent = file.getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }
        File temp = File.createTempFile(tempPrefix(file), ".tmp",
                parent == null ? new File(".") : parent);
        boolean moved = false;
        try {
            Writer writer = new OutputStreamWriter(new FileOutputStream(temp), StandardCharsets.UTF_8);
            try {
                writer.write(yaml);
            } finally {
                writer.close();
            }
            moveAtomically(temp.toPath(), file.toPath());
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temp.toPath());
            }
        }
    }

    private static String render(QualityReport report, ProjectMetadata metadata) {
        StringBuilder out = new StringBuilder(16384);
        out.append("schema: ").append(q(SCHEMA)).append('\n');
        out.append("schema_version: 1\n");
        appendGenerated(out, report, metadata);
        appendStudy(out, report, metadata);
        appendBiosample(out, metadata);
        appendSpecimen(out, metadata);
        appendAcquisition(out, metadata);
        appendImage(out, metadata);
        appendAnalysis(out, report);
        appendFile(out, metadata);
        appendProtocol(out, report);
        return out.toString();
    }

    private static void appendGenerated(StringBuilder out, QualityReport report, ProjectMetadata metadata) {
        out.append("generated:\n");
        out.append("  application: ").append(q("FLASH")).append('\n');
        out.append("  alignment: ").append(q("REMBI")).append('\n');
        out.append("  generated_at: ").append(q(formatMillis(System.currentTimeMillis()))).append('\n');
        out.append("  project_dir: ").append(q(report == null ? metadata.directory : report.getProjectDir())).append('\n');
        if (metadata.metadataSources.isEmpty()) {
            out.append("  metadata_sources: []\n");
        } else {
            out.append("  metadata_sources:\n");
            for (String source : metadata.metadataSources) {
                out.append("    - ").append(q(source)).append('\n');
            }
        }
        if (!metadata.warnings.isEmpty()) {
            out.append("  warnings:\n");
            for (String warning : metadata.warnings) {
                out.append("    - ").append(q(warning)).append('\n');
            }
        }
    }

    private static void appendStudy(StringBuilder out, QualityReport report, ProjectMetadata metadata) {
        out.append("study:\n");
        out.append("  study_id: ").append(q(studyId(metadata))).append('\n');
        out.append("  title: ").append(q(metadata.projectName)).append('\n');
        out.append("  output_root: ").append(q(metadata.directory)).append('\n');
        if (metadata.project != null && metadata.project.writtenAtMillis > 0) {
            out.append("  project_file_written_at: ")
                    .append(q(formatMillis(metadata.project.writtenAtMillis))).append('\n');
        }
        if (report != null) {
            out.append("  analysis_started_at: ").append(q(formatMillis(report.getStartTime()))).append('\n');
        }
        Set<String> conditions = new LinkedHashSet<String>();
        for (ImageRecord image : metadata.images) {
            if (!isBlank(image.condition)) conditions.add(image.condition);
        }
        if (conditions.isEmpty()) {
            out.append("  conditions: []\n");
        } else {
            out.append("  conditions:\n");
            for (String condition : conditions) {
                out.append("    - ").append(q(condition)).append('\n');
            }
        }
    }

    private static void appendBiosample(StringBuilder out, ProjectMetadata metadata) {
        out.append("biosample:\n");
        Map<String, ImageRecord> samples = new LinkedHashMap<String, ImageRecord>();
        for (ImageRecord image : metadata.images) {
            String key = biosampleId(image);
            if (!samples.containsKey(key)) samples.put(key, image);
        }
        if (samples.isEmpty()) {
            out.append("  samples: []\n");
            return;
        }
        out.append("  samples:\n");
        for (Map.Entry<String, ImageRecord> entry : samples.entrySet()) {
            ImageRecord image = entry.getValue();
            out.append("    - biosample_id: ").append(q(entry.getKey())).append('\n');
            out.append("      animal_id: ").append(q(image.animalId)).append('\n');
            out.append("      condition: ").append(q(image.condition)).append('\n');
            out.append("      metadata_source: ").append(q(image.metadataSource)).append('\n');
        }
    }

    private static void appendSpecimen(StringBuilder out, ProjectMetadata metadata) {
        out.append("specimen:\n");
        Map<String, ImageRecord> specimens = new LinkedHashMap<String, ImageRecord>();
        for (ImageRecord image : metadata.images) {
            String key = specimenId(image);
            if (!specimens.containsKey(key)) specimens.put(key, image);
        }
        if (specimens.isEmpty()) {
            out.append("  specimens: []\n");
            return;
        }
        out.append("  specimens:\n");
        for (Map.Entry<String, ImageRecord> entry : specimens.entrySet()) {
            ImageRecord image = entry.getValue();
            out.append("    - specimen_id: ").append(q(entry.getKey())).append('\n');
            out.append("      biosample_id: ").append(q(biosampleId(image))).append('\n');
            out.append("      hemisphere: ").append(q(image.hemisphere)).append('\n');
            out.append("      anatomical_region: ").append(q(image.region)).append('\n');
            out.append("      notes: ").append(q(image.notes)).append('\n');
        }
    }

    private static void appendAcquisition(StringBuilder out, ProjectMetadata metadata) {
        out.append("acquisition:\n");
        if (metadata.images.isEmpty()) {
            out.append("  acquisitions: []\n");
            return;
        }
        out.append("  acquisitions:\n");
        int index = 1;
        for (ImageRecord image : metadata.images) {
            out.append("    - acquisition_id: ").append(q("acq_" + pad3(index++))).append('\n');
            out.append("      image_id: ").append(q(image.imageId)).append('\n');
            out.append("      source_file: ").append(q(image.sourcePath)).append('\n');
            appendIntOrNull(out, "      series_index", image.seriesIndex);
            out.append("      series_name: ").append(q(image.seriesName)).append('\n');
            out.append("      dimensions:\n");
            appendIntOrNull(out, "        x_pixels", image.width);
            appendIntOrNull(out, "        y_pixels", image.height);
            appendIntOrNull(out, "        z_slices", image.nSlices);
            appendIntOrNull(out, "        channels", image.nChannels);
            out.append("      physical_pixel_size:\n");
            appendDoubleOrNull(out, "        x", image.pixelWidth);
            appendDoubleOrNull(out, "        y", image.pixelHeight);
            appendDoubleOrNull(out, "        z", image.pixelDepth);
            out.append("        unit: ").append(q(image.unit)).append('\n');
        }
    }

    private static void appendImage(StringBuilder out, ProjectMetadata metadata) {
        out.append("image:\n");
        if (metadata.images.isEmpty()) {
            out.append("  images: []\n");
            return;
        }
        out.append("  images:\n");
        for (ImageRecord image : metadata.images) {
            out.append("    - image_id: ").append(q(image.imageId)).append('\n');
            out.append("      name: ").append(q(image.displayName)).append('\n');
            out.append("      source_file: ").append(q(image.sourcePath)).append('\n');
            out.append("      biosample_id: ").append(q(biosampleId(image))).append('\n');
            out.append("      specimen_id: ").append(q(specimenId(image))).append('\n');
            out.append("      filename_parsed:\n");
            out.append("        experiment: ").append(q(image.experiment)).append('\n');
            out.append("        animal_id: ").append(q(image.animalId)).append('\n');
            out.append("        hemisphere: ").append(q(image.hemisphere)).append('\n');
            out.append("        region: ").append(q(image.region)).append('\n');
            out.append("        condition: ").append(q(image.condition)).append('\n');
            out.append("        strict_match: ").append(image.strictFilenameMatch).append('\n');
        }
    }

    private static void appendAnalysis(StringBuilder out, QualityReport report) {
        out.append("analysis:\n");
        out.append("  run:\n");
        if (report == null) {
            out.append("    started_at: \"\"\n");
            out.append("    headless: false\n");
            out.append("    parallel: false\n");
            out.append("    thread_count: 0\n");
            out.append("    overwrite_behavior: \"\"\n");
        } else {
            out.append("    started_at: ").append(q(formatMillis(report.getStartTime()))).append('\n');
            out.append("    headless: ").append(report.isHeadless()).append('\n');
            out.append("    parallel: ").append(report.isParallel()).append('\n');
            out.append("    thread_count: ").append(report.getThreadCount()).append('\n');
            out.append("    overwrite_behavior: ").append(q(report.getOverwriteBehavior())).append('\n');
        }
        List<QualityReport.AnalysisSection> sections = report == null
                ? Collections.<QualityReport.AnalysisSection>emptyList()
                : report.getSections();
        if (sections.isEmpty()) {
            out.append("  analyses: []\n");
            return;
        }
        out.append("  analyses:\n");
        for (QualityReport.AnalysisSection section : sections) {
            out.append("    - name: ").append(q(section.name)).append('\n');
            out.append("      timestamp: ").append(q(formatMillis(section.timestamp))).append('\n');
            appendParams(out, "      parameters", section.params);
        }
    }

    private static void appendFile(StringBuilder out, ProjectMetadata metadata) {
        out.append("file:\n");
        if (metadata.files.isEmpty()) {
            out.append("  files: []\n");
            return;
        }
        out.append("  files:\n");
        for (FileRecord file : metadata.files) {
            out.append("    - path: ").append(q(file.path)).append('\n');
            out.append("      name: ").append(q(file.name)).append('\n');
            out.append("      extension: ").append(q(file.extension)).append('\n');
            appendLongOrNull(out, "      size_bytes", file.sizeBytes);
            out.append("      last_modified: ").append(q(file.lastModifiedMillis > 0
                    ? formatMillis(file.lastModifiedMillis) : "")).append('\n');
            out.append("      role: \"raw_image\"\n");
        }
    }

    private static void appendProtocol(StringBuilder out, QualityReport report) {
        out.append("protocol:\n");
        List<QualityReport.AnalysisSection> sections = report == null
                ? Collections.<QualityReport.AnalysisSection>emptyList()
                : report.getSections();
        if (sections.isEmpty()) {
            out.append("  protocols: []\n");
            return;
        }
        out.append("  protocols:\n");
        int index = 1;
        for (QualityReport.AnalysisSection section : sections) {
            out.append("    - protocol_id: ").append(q("protocol_" + pad3(index++))).append('\n');
            out.append("      name: ").append(q(section.name)).append('\n');
            out.append("      type: \"computational_analysis\"\n");
            appendParams(out, "      parameters", section.params);
        }
    }

    private static void appendParams(StringBuilder out, String key, Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            out.append(key).append(": {}\n");
            return;
        }
        out.append(key).append(":\n");
        for (Map.Entry<String, String> entry : params.entrySet()) {
            out.append("        ").append(q(entry.getKey())).append(": ")
                    .append(q(entry.getValue())).append('\n');
        }
    }

    private static ProjectImageCapture buildProjectImages(ProjectFile project,
                                                          String projectName,
                                                          List<String> warnings) {
        ProjectImageCapture capture = new ProjectImageCapture();
        if (project == null || project.items == null) return capture;
        int imageIndex = 0;
        for (ProjectFile.Item item : project.items) {
            if (item == null || !item.include || isBlank(item.path)) continue;
            File source = new File(item.path);
            FileRecord file = fileRecord(source);
            List<ImageRecord> records = buildProjectItemImages(
                    item, source, file, projectName, imageIndex, warnings);
            if (records.isEmpty()) {
                records.add(toImageRecord(imageIndex, null, new ItemRef(item, -1), file));
            }
            for (ImageRecord record : records) {
                capture.images.add(record);
                if (record.width != null || record.height != null || record.nSlices != null
                        || record.nChannels != null || record.pixelWidth != null) {
                    capture.usedSeriesMetadata = true;
                }
            }
            imageIndex += records.size();
        }
        return capture;
    }

    private static List<ImageRecord> buildProjectItemImages(ProjectFile.Item item,
                                                            File source,
                                                            FileRecord file,
                                                            String projectName,
                                                            int firstImageIndex,
                                                            List<String> warnings) {
        List<ImageRecord> out = new ArrayList<ImageRecord>();
        if (source == null || !source.isFile()) return out;
        if (isContainerFileName(source.getName())) {
            try {
                List<SeriesMeta> metas = LifIO.readAllSeriesMetadata(source);
                int offset = 0;
                for (SeriesMeta meta : metas) {
                    if (!includesSeries(item, meta.index)) continue;
                    out.add(toImageRecord(firstImageIndex + offset,
                            meta, new ItemRef(item, meta.index), file));
                    offset++;
                }
            } catch (Exception e) {
                appendWarning(warnings, "Image metadata could not be read for "
                        + source.getName() + ": " + e.getMessage());
            }
            return out;
        }
        if (isBareTiffFileName(source.getName())) {
            try {
                List<SeriesMeta> metas = DeferredImageSupplier.readTiffFolderMetadata(
                        Collections.singletonList(source), projectName);
                SeriesMeta meta = metas.isEmpty() ? null : metas.get(0);
                out.add(toImageRecord(firstImageIndex, meta, new ItemRef(item, 0), file));
            } catch (Exception e) {
                appendWarning(warnings, "Image metadata could not be read for "
                        + source.getName() + ": " + e.getMessage());
            }
        }
        return out;
    }

    private static boolean includesSeries(ProjectFile.Item item, int seriesIndex) {
        if (item == null || item.series == null || item.series.isEmpty()) return true;
        return item.series.contains(Integer.valueOf(seriesIndex));
    }

    private static List<ImageRecord> buildImages(List<SeriesMeta> metas,
                                                 List<FileRecord> files) {
        List<ImageRecord> out = new ArrayList<ImageRecord>();
        if (metas == null || metas.isEmpty()) return out;
        for (int i = 0; i < metas.size(); i++) {
            SeriesMeta meta = metas.get(i);
            FileRecord file = fileFor(i, files, metas.size());
            out.add(toImageRecord(i, meta, null, file));
        }
        return out;
    }

    private static List<ImageRecord> buildImagesFromItems(List<ItemRef> refs) {
        List<ImageRecord> out = new ArrayList<ImageRecord>();
        if (refs == null || refs.isEmpty()) return out;
        for (int i = 0; i < refs.size(); i++) {
            ItemRef ref = refs.get(i);
            FileRecord file = fileRecord(new File(ref.path));
            out.add(toImageRecord(i, null, ref, file));
        }
        return out;
    }

    private static List<ImageRecord> buildImagesFromFiles(List<FileRecord> files) {
        List<ImageRecord> out = new ArrayList<ImageRecord>();
        if (files == null || files.isEmpty()) return out;
        for (int i = 0; i < files.size(); i++) {
            out.add(toImageRecord(i, null, null, files.get(i)));
        }
        return out;
    }

    private static ImageRecord toImageRecord(int zeroBasedIndex, SeriesMeta meta,
                                             ItemRef ref, FileRecord file) {
        ImageRecord image = new ImageRecord();
        image.imageId = "image_" + pad3(zeroBasedIndex + 1);
        image.sourcePath = ref != null ? ref.path : (file == null ? "" : file.path);
        image.seriesIndex = ref != null && ref.seriesIndex >= 0
                ? Integer.valueOf(ref.seriesIndex)
                : (meta == null ? null : Integer.valueOf(meta.index));
        image.seriesName = meta != null && !isBlank(meta.name)
                ? meta.name
                : sourceDisplayName(file, ref);
        image.displayName = firstNonBlank(image.seriesName, sourceDisplayName(file, ref), image.imageId);
        image.width = meta == null || meta.width <= 0 ? null : Integer.valueOf(meta.width);
        image.height = meta == null || meta.height <= 0 ? null : Integer.valueOf(meta.height);
        image.nSlices = meta == null || meta.nSlices <= 0 ? null : Integer.valueOf(meta.nSlices);
        image.nChannels = meta == null || meta.nChannels <= 0 ? null : Integer.valueOf(meta.nChannels);
        image.pixelWidth = meta == null || meta.pixelWidth <= 0 ? null : Double.valueOf(meta.pixelWidth);
        image.pixelHeight = meta == null || meta.pixelHeight <= 0 ? null : Double.valueOf(meta.pixelHeight);
        image.pixelDepth = meta == null || meta.pixelDepth <= 0 ? null : Double.valueOf(meta.pixelDepth);
        image.unit = meta == null ? "" : nullToEmpty(meta.unit);

        String parseSeed = parseSeed(image, file, ref);
        NameParts parsed = ImageNameParser.parse(parseSeed);
        image.experiment = parsed.experiment;
        image.animalId = valueFromItem(ref, "animal", parsed.animal);
        image.hemisphere = valueFromItem(ref, "hemisphere", parsed.hemisphere);
        image.region = valueFromItem(ref, "region", parsed.region);
        image.condition = valueFromItem(ref, "condition", parsed.condition);
        if (isBlank(image.condition) && !isBlank(image.sourcePath)) {
            image.condition = ImageNameParser.guessConditionFromParentFolder(new File(image.sourcePath));
        }
        image.notes = valueFromItem(ref, "notes", "");
        image.strictFilenameMatch = parsed.strictMatch;
        image.metadataSource = ref == null
                ? (parsed.strictMatch ? "filename" : "filename_fallback")
                : "project.json";
        return image;
    }

    private static String parseSeed(ImageRecord image, FileRecord file, ItemRef ref) {
        String sourceName = sourceDisplayName(file, ref);
        if (ref != null && isBareTiffFileName(ref.path)) {
            return firstNonBlank(sourceName, image.seriesName);
        }
        return firstNonBlank(image.seriesName, sourceName);
    }

    private static String valueFromItem(ItemRef ref, String field, String fallback) {
        if (ref == null || ref.item == null) return nullToEmpty(fallback);
        if ("animal".equals(field) && !isBlank(ref.item.animalId)) return ref.item.animalId;
        if ("hemisphere".equals(field) && !isBlank(ref.item.hemisphere)) return ref.item.hemisphere;
        if ("region".equals(field) && !isBlank(ref.item.region)) return ref.item.region;
        if ("condition".equals(field) && !isBlank(ref.item.condition)) return ref.item.condition;
        if ("notes".equals(field) && !isBlank(ref.item.notes)) return ref.item.notes;
        return nullToEmpty(fallback);
    }

    private static List<ItemRef> itemRefs(ProjectFile project) {
        List<ItemRef> refs = new ArrayList<ItemRef>();
        if (project == null || project.items == null) return refs;
        for (ProjectFile.Item item : project.items) {
            if (item == null || !item.include || isBlank(item.path)) continue;
            if (item.series == null || item.series.isEmpty()) {
                refs.add(new ItemRef(item, -1));
            } else {
                for (Integer series : item.series) {
                    if (series != null) refs.add(new ItemRef(item, series.intValue()));
                }
            }
        }
        return refs;
    }

    private static List<FileRecord> discoverFiles(File projectRoot, ProjectFile project) {
        Map<String, FileRecord> out = new LinkedHashMap<String, FileRecord>();
        if (project != null && project.items != null) {
            for (ProjectFile.Item item : project.items) {
                if (item == null || !item.include || isBlank(item.path)) continue;
                FileRecord record = fileRecord(new File(item.path));
                out.put(record.path, record);
            }
            return new ArrayList<FileRecord>(out.values());
        }

        if (projectRoot == null || !projectRoot.isDirectory()) return new ArrayList<FileRecord>();
        List<File> candidates = new ArrayList<File>();
        List<File> containers = ImageSourceDispatcher.listContainers(projectRoot);
        if (!containers.isEmpty()) {
            candidates.addAll(containers);
        } else {
            File input = new File(projectRoot, "input");
            List<File> inputTiffs = input.isDirectory()
                    ? ImageSourceDispatcher.listTiffs(input)
                    : Collections.<File>emptyList();
            candidates.addAll(inputTiffs.isEmpty()
                    ? ImageSourceDispatcher.listTiffs(projectRoot)
                    : inputTiffs);
        }
        for (File candidate : candidates) {
            FileRecord record = fileRecord(candidate);
            out.put(record.path, record);
        }
        return new ArrayList<FileRecord>(out.values());
    }

    private static boolean canReadMetadataWithoutPrompt(File projectRoot, List<String> warnings) {
        if (projectRoot == null || !projectRoot.isDirectory()) return false;
        List<File> containers = ImageSourceDispatcher.listContainers(projectRoot);
        if (containers.size() > 1) {
            warnings.add("Image metadata was not read because multiple container files are present and no project.json selected one.");
            return false;
        }
        return true;
    }

    private static FileRecord fileFor(int index, List<FileRecord> files, int imageCount) {
        if (files == null || files.isEmpty()) return null;
        if (files.size() == 1) return files.get(0);
        if (files.size() == imageCount && index < files.size()) return files.get(index);
        return null;
    }

    private static FileRecord fileRecord(File file) {
        FileRecord record = new FileRecord();
        record.path = file == null ? "" : file.getAbsolutePath();
        record.name = file == null ? "" : file.getName();
        record.extension = extension(record.name);
        if (file != null && file.isFile()) {
            try {
                record.sizeBytes = Long.valueOf(Files.size(file.toPath()));
            } catch (IOException ignored) {
                record.sizeBytes = null;
            }
            record.lastModifiedMillis = file.lastModified();
        }
        return record;
    }

    private static String sourceDisplayName(FileRecord file, ItemRef ref) {
        if (ref != null && !isBlank(ref.path)) return ImageNameParser.stripExtension(new File(ref.path).getName());
        if (file != null && !isBlank(file.name)) return ImageNameParser.stripExtension(file.name);
        return "";
    }

    private static boolean isContainerFileName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : ImageSourceDispatcher.CONTAINER_EXTENSIONS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    private static boolean isBareTiffFileName(String name) {
        if (name == null || isContainerFileName(name)) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : ImageSourceDispatcher.TIFF_EXTENSIONS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    private static void appendWarning(List<String> warnings, String warning) {
        if (warnings != null && !isBlank(warning)) warnings.add(warning);
    }

    private static String projectName(ProjectFile project, File projectRoot) {
        if (project != null && !isBlank(project.name)) return project.name.trim();
        return projectRoot == null ? "" : projectRoot.getName();
    }

    private static String studyId(ProjectMetadata metadata) {
        String seed = firstNonBlank(metadata.projectName, metadata.directory, "flash_study");
        return seed.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String biosampleId(ImageRecord image) {
        return firstNonBlank(image.animalId, image.imageId);
    }

    private static String specimenId(ImageRecord image) {
        StringBuilder sb = new StringBuilder();
        appendToken(sb, biosampleId(image));
        appendToken(sb, image.hemisphere);
        appendToken(sb, image.region);
        return sb.length() == 0 ? image.imageId : sb.toString();
    }

    private static void appendToken(StringBuilder sb, String token) {
        if (isBlank(token)) return;
        if (sb.length() > 0) sb.append('_');
        sb.append(token.trim().replaceAll("\\s+", "_"));
    }

    private static String extension(String name) {
        if (name == null) return "";
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".ome.tiff")) return ".ome.tiff";
        if (lower.endsWith(".ome.tif")) return ".ome.tif";
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot).toLowerCase(Locale.ROOT) : "";
    }

    private static void appendIntOrNull(StringBuilder out, String key, Integer value) {
        out.append(key).append(": ").append(value == null ? "null" : value.toString()).append('\n');
    }

    private static void appendLongOrNull(StringBuilder out, String key, Long value) {
        out.append(key).append(": ").append(value == null ? "null" : value.toString()).append('\n');
    }

    private static void appendDoubleOrNull(StringBuilder out, String key, Double value) {
        out.append(key).append(": ");
        out.append(value == null ? "null" : String.format(Locale.ROOT, "%.10g", value.doubleValue())).append('\n');
    }

    private static String q(String value) {
        String s = value == null ? "" : value;
        StringBuilder out = new StringBuilder(s.length() + 2);
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '\\': out.append("\\\\"); break;
                case '"': out.append("\\\""); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (ch < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", Integer.valueOf(ch)));
                    } else {
                        out.append(ch);
                    }
                    break;
            }
        }
        out.append('"');
        return out.toString();
    }

    private static String formatMillis(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT);
        return format.format(new Date(millis));
    }

    private static String tempPrefix(File file) {
        String name = file == null ? "study" : file.getName();
        String clean = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return clean.length() < 3 ? "tmp" + clean : clean;
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        // Retry/backoff move, then in-place rewrite if the destination stays
        // locked against rename (Windows + Dropbox/OneDrive). Safe: small YAML.
        flash.pipeline.io.IoUtils.commitReplacingSmallFile(source, target);
    }

    private static String pad3(int value) {
        return String.format(Locale.ROOT, "%03d", Integer.valueOf(value));
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String firstNonBlank(String first, String second) {
        return !isBlank(first) ? first : nullToEmpty(second);
    }

    private static String firstNonBlank(String first, String second, String third) {
        if (!isBlank(first)) return first;
        if (!isBlank(second)) return second;
        return nullToEmpty(third);
    }

    static final class ProjectMetadata {
        final String directory;
        ProjectFile project;
        String projectName = "";
        final List<ImageRecord> images = new ArrayList<ImageRecord>();
        final List<FileRecord> files = new ArrayList<FileRecord>();
        final List<String> warnings = new ArrayList<String>();
        final Set<String> metadataSources = new LinkedHashSet<String>();

        ProjectMetadata(String directory) {
            this.directory = directory == null ? "" : directory;
        }
    }

    private static final class ItemRef {
        final ProjectFile.Item item;
        final String path;
        final int seriesIndex;

        ItemRef(ProjectFile.Item item, int seriesIndex) {
            this.item = item;
            this.path = item == null ? "" : nullToEmpty(item.path);
            this.seriesIndex = seriesIndex;
        }
    }

    private static final class ProjectImageCapture {
        final List<ImageRecord> images = new ArrayList<ImageRecord>();
        boolean usedSeriesMetadata;
    }

    private static final class ImageRecord {
        String imageId = "";
        String sourcePath = "";
        Integer seriesIndex;
        String seriesName = "";
        String displayName = "";
        Integer width;
        Integer height;
        Integer nSlices;
        Integer nChannels;
        Double pixelWidth;
        Double pixelHeight;
        Double pixelDepth;
        String unit = "";
        String experiment = "";
        String animalId = "";
        String hemisphere = "";
        String region = "";
        String condition = "";
        String notes = "";
        boolean strictFilenameMatch;
        String metadataSource = "";
    }

    private static final class FileRecord {
        String path = "";
        String name = "";
        String extension = "";
        Long sizeBytes;
        long lastModifiedMillis;
    }
}
