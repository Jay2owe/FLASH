package flash.pipeline.intelligence;

import flash.pipeline.deconv.RefractiveIndexEstimator;
import flash.pipeline.deconv.psf.ScopeModality;
import flash.pipeline.io.ConditionManifestIO;
import flash.pipeline.naming.ConditionNameParser;
import flash.pipeline.naming.ImageNameParser;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Hashtable;
import java.util.TreeMap;

import loci.common.services.ServiceFactory;
import loci.formats.ImageReader;
import loci.formats.Memoizer;
import loci.formats.meta.IMetadata;
import loci.formats.meta.MetadataRetrieve;
import loci.formats.services.OMEXMLService;

/**
 * Bio-Formats metadata-only diagnostics. Reads OME-XML without opening pixel
 * planes, so each file costs tens of milliseconds at most.
 *
 * Implements:
 *   P-01 Objective Check
 *   P-04 Staining-Date Grouping
 *   P-05 Z-Stack Depth Summary
 *   P-06 Mixed-Format / Bit-Depth / Channel-Count Check
 */
public final class MetadataDiagnostics {

    private static final Set<String> IMG_EXTS = new HashSet<String>(
            Arrays.asList("lif", "czi", "nd2", "tif", "tiff"));
    private static final Set<String> RAW_CONTAINER_EXTS = new HashSet<String>(
            Arrays.asList("lif", "czi", "nd2"));

    private MetadataDiagnostics() {}

    /** One summary row per series found in the directory. */
    public static final class SeriesInfo {
        public String file;
        public int seriesIndex;
        public String imageName;
        public String pixelType; // e.g. "uint16"
        public int sizeX, sizeY, sizeZ, sizeC, sizeT;
        public Double pixelSizeXUm;
        public Double pixelSizeZUm;
        public String microscopeModel;
        public String objectiveModel;
        public Double objectiveMag;
        public Double objectiveNA;
        public String objectiveImmersion;
        public double[] emissionWavelengthNm;
        public Double sampleRefractiveIndex;
        public String detectorIds;
        public String lightSourceIds;
        public String acquisitionSoftware;
        public String acquisitionSoftwareVersion;
        public String acquisitionDate; // raw ISO string or null
        public String extension;       // lif, czi, nd2, tif, ...
    }

    /** Collects metadata for every file/series in the directory (fast: no pixels). */
    public static List<SeriesInfo> scanDirectory(String directory) {
        List<SeriesInfo> out = new ArrayList<SeriesInfo>();
        File dir = new File(directory == null ? "" : directory);
        if (!dir.isDirectory()) return out;

        for (File f : listCandidateFilesForMetadataScan(dir)) {
            try {
                readOne(f, out);
            } catch (Exception e) {
                SeriesInfo s = new SeriesInfo();
                s.file = f.getName();
                s.seriesIndex = 0;
                s.imageName = "(failed: " + e.getClass().getSimpleName() + ")";
                s.extension = extension(f.getName());
                out.add(s);
            }
        }
        return out;
    }

    /**
     * Reads one metadata row for one requested series. Preview/thumbnail helper
     * series return {@code null} so callers can skip them consistently.
     */
    public static SeriesInfo readOneSeriesInfo(File imageFile, int seriesIndex) throws Exception {
        if (imageFile == null) {
            throw new IllegalArgumentException("imageFile is required.");
        }
        if (seriesIndex < 0) {
            throw new IllegalArgumentException("seriesIndex must be >= 0.");
        }

        OMEXMLService service = new ServiceFactory().getInstance(OMEXMLService.class);
        IMetadata meta = service.createOMEXMLMetadata();
        Memoizer reader = new Memoizer(new ImageReader());
        try {
            reader.setMetadataStore(meta);
            reader.setId(imageFile.getAbsolutePath());
            if (seriesIndex >= reader.getSeriesCount()) {
                throw new IllegalArgumentException("Series index " + seriesIndex
                        + " out of range for " + imageFile.getName());
            }

            MetadataRetrieve retrieve = (MetadataRetrieve) meta;
            String imageName = null;
            try { imageName = retrieve.getImageName(seriesIndex); } catch (Exception ignored) {}
            if (ImageNameParser.isPreviewSeriesName(imageName)) {
                return null;
            }

            SeriesInfo info = new SeriesInfo();
            info.file = imageFile.getName();
            info.extension = extension(imageFile.getName());
            info.seriesIndex = seriesIndex;
            info.imageName = imageName;

            reader.setSeries(seriesIndex);
            try { info.sizeX = reader.getSizeX(); } catch (Exception ignored) {}
            try { info.sizeY = reader.getSizeY(); } catch (Exception ignored) {}
            try { info.sizeZ = reader.getSizeZ(); } catch (Exception ignored) {}
            try { info.sizeC = reader.getSizeC(); } catch (Exception ignored) {}
            try { info.sizeT = reader.getSizeT(); } catch (Exception ignored) {}
            try {
                info.pixelType = reader.getPixelType() >= 0
                        ? loci.formats.FormatTools.getPixelTypeString(reader.getPixelType())
                        : null;
            } catch (Exception ignored) {}

            info.pixelSizeXUm = readLengthMicrons(retrieve, "getPixelsPhysicalSizeX", seriesIndex);
            info.pixelSizeZUm = readLengthMicrons(retrieve, "getPixelsPhysicalSizeZ", seriesIndex);

            int instrumentIndex = resolveInstrumentIndex(retrieve, seriesIndex);
            int objectiveIndex = resolveObjectiveIndex(retrieve, seriesIndex, instrumentIndex);
            if (instrumentIndex >= 0) {
                try { info.microscopeModel = safeTrim(retrieve.getMicroscopeModel(instrumentIndex)); } catch (Exception ignored) {}
            }
            if (instrumentIndex >= 0 && objectiveIndex >= 0) {
                try { info.objectiveMag = retrieve.getObjectiveNominalMagnification(instrumentIndex, objectiveIndex); } catch (Exception ignored) {}
                try { info.objectiveNA = retrieve.getObjectiveLensNA(instrumentIndex, objectiveIndex); } catch (Exception ignored) {}
                try { info.objectiveModel = safeTrim(retrieve.getObjectiveModel(instrumentIndex, objectiveIndex)); } catch (Exception ignored) {}
                try {
                    Object immersion = retrieve.getObjectiveImmersion(instrumentIndex, objectiveIndex);
                    info.objectiveImmersion = normalizeEnumLabel(immersion);
                } catch (Exception ignored) {}
            }

            populateDeconvolutionMetadata(info, retrieve, seriesIndex);
            info.detectorIds = readDetectorIdentifiers(retrieve, seriesIndex, instrumentIndex, info.sizeC);
            info.lightSourceIds = readLightSourceIdentifiers(retrieve, seriesIndex, instrumentIndex, info.sizeC);
            SoftwareInfo software = readSoftwareInfo(reader.getGlobalMetadata(), reader.getSeriesMetadata());
            info.acquisitionSoftware = software.name;
            info.acquisitionSoftwareVersion = software.version;
            try {
                Object ts = retrieve.getImageAcquisitionDate(seriesIndex);
                if (ts != null) info.acquisitionDate = ts.toString();
            } catch (Exception ignored) {}
            return info;
        } finally {
            try { reader.close(); } catch (Exception ignored) {}
        }
    }

    public static SeriesInfo readOneSeriesInfo(String directory, int seriesIndex) throws Exception {
        return readOneSeriesInfo(flash.pipeline.io.LifIO.requireSingleLifFile(directory), seriesIndex);
    }

    static List<File> listCandidateFilesForMetadataScan(File dir) {
        List<File> rawContainers = new ArrayList<File>();
        List<File> flatImages = new ArrayList<File>();
        if (dir == null || !dir.isDirectory()) return rawContainers;

        File[] entries = JunkFileFilter.listCleanFiles(dir);
        Arrays.sort(entries, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                String an = a == null ? "" : a.getName();
                String bn = b == null ? "" : b.getName();
                return an.compareToIgnoreCase(bn);
            }
        });

        for (File entry : entries) {
            if (!isRecognizedImageFile(entry.getName())) continue;

            String ext = extension(entry.getName());
            if (RAW_CONTAINER_EXTS.contains(ext)) rawContainers.add(entry);
            else                                 flatImages.add(entry);
        }

        return rawContainers.isEmpty() ? flatImages : rawContainers;
    }

    private static void readOne(File f, List<SeriesInfo> out) throws Exception {
        OMEXMLService service = new ServiceFactory().getInstance(OMEXMLService.class);
        IMetadata meta = service.createOMEXMLMetadata();
        Memoizer reader = new Memoizer(new ImageReader());
        try {
            reader.setMetadataStore(meta);
            reader.setId(f.getAbsolutePath());
            int total = reader.getSeriesCount();
            MetadataRetrieve retrieve = (MetadataRetrieve) meta;
            for (int s = 0; s < total; s++) {
                SeriesInfo si = new SeriesInfo();
                si.file = f.getName();
                si.extension = extension(f.getName());
                si.seriesIndex = s;

                try { si.imageName = retrieve.getImageName(s); } catch (Exception ignored) {}
                if (ImageNameParser.isPreviewSeriesName(si.imageName)) {
                    continue;
                }
                reader.setSeries(s);
                try { si.sizeX = reader.getSizeX(); } catch (Exception ignored) {}
                try { si.sizeY = reader.getSizeY(); } catch (Exception ignored) {}
                try { si.sizeZ = reader.getSizeZ(); } catch (Exception ignored) {}
                try { si.sizeC = reader.getSizeC(); } catch (Exception ignored) {}
                try { si.sizeT = reader.getSizeT(); } catch (Exception ignored) {}
                try { si.pixelType = reader.getPixelType() >= 0
                        ? loci.formats.FormatTools.getPixelTypeString(reader.getPixelType())
                        : null; } catch (Exception ignored) {}

                si.pixelSizeXUm = readLengthMicrons(retrieve, "getPixelsPhysicalSizeX", s);
                si.pixelSizeZUm = readLengthMicrons(retrieve, "getPixelsPhysicalSizeZ", s);

                int instrumentIndex = resolveInstrumentIndex(retrieve, s);
                int objectiveIndex = resolveObjectiveIndex(retrieve, s, instrumentIndex);
                if (instrumentIndex >= 0) {
                    try { si.microscopeModel = safeTrim(retrieve.getMicroscopeModel(instrumentIndex)); } catch (Exception ignored) {}
                }
                if (instrumentIndex >= 0 && objectiveIndex >= 0) {
                    try { si.objectiveMag = retrieve.getObjectiveNominalMagnification(instrumentIndex, objectiveIndex); } catch (Exception ignored) {}
                    try { si.objectiveNA  = retrieve.getObjectiveLensNA(instrumentIndex, objectiveIndex); } catch (Exception ignored) {}
                    try { si.objectiveModel = safeTrim(retrieve.getObjectiveModel(instrumentIndex, objectiveIndex)); } catch (Exception ignored) {}
                    try {
                        Object immersion = retrieve.getObjectiveImmersion(instrumentIndex, objectiveIndex);
                        si.objectiveImmersion = normalizeEnumLabel(immersion);
                    } catch (Exception ignored) {}
                }
                populateDeconvolutionMetadata(si, retrieve, s);

                si.detectorIds = readDetectorIdentifiers(retrieve, s, instrumentIndex, si.sizeC);
                si.lightSourceIds = readLightSourceIdentifiers(retrieve, s, instrumentIndex, si.sizeC);
                SoftwareInfo software = readSoftwareInfo(reader.getGlobalMetadata(), reader.getSeriesMetadata());
                si.acquisitionSoftware = software.name;
                si.acquisitionSoftwareVersion = software.version;

                try {
                    Object ts = retrieve.getImageAcquisitionDate(s);
                    if (ts != null) si.acquisitionDate = ts.toString();
                } catch (Exception ignored) {}

                out.add(si);
            }
        } finally {
            try { reader.close(); } catch (Exception ignored) {}
        }
    }

    static void populateDeconvolutionMetadata(SeriesInfo info, MetadataRetrieve retrieve, int imageIndex) {
        if (info == null) return;

        int channelCount = Math.max(0, info.sizeC);
        info.emissionWavelengthNm = new double[channelCount];
        Arrays.fill(info.emissionWavelengthNm, Double.NaN);
        for (int c = 0; c < channelCount; c++) {
            try {
                Double wavelength = readLengthNanometers(retrieve,
                        "getChannelEmissionWavelength", imageIndex, c);
                if (wavelength != null) {
                    info.emissionWavelengthNm[c] = wavelength.doubleValue();
                }
            } catch (Exception ignored) {}
        }

        info.sampleRefractiveIndex = Double.valueOf(
                RefractiveIndexEstimator.inferSampleRI(info.objectiveImmersion, null));
    }

    /** Uses reflection to pull a Length value in micrometres, tolerating Bio-Formats version drift. */
    private static Double readLengthMicrons(MetadataRetrieve retrieve, String method, int series) {
        return readLength(retrieve, method, "MICROMETER", new Class<?>[]{int.class}, series);
    }

    private static Double readLengthNanometers(MetadataRetrieve retrieve, String method,
                                               int imageIndex, int channelIndex) {
        return readLength(retrieve, method, "NANOMETER",
                new Class<?>[]{int.class, int.class}, imageIndex, channelIndex);
    }

    private static Double readLength(MetadataRetrieve retrieve, String method,
                                     String unitField,
                                     Class<?>[] argTypes,
                                     Object... args) {
        try {
            java.lang.reflect.Method m = retrieve.getClass().getMethod(method, argTypes);
            Object len = m.invoke(retrieve, args);
            if (len == null) return null;
            // Length#value(Unit)
            Class<?> unitsClass = Class.forName("ome.units.UNITS");
            Object unit = unitsClass.getField(unitField).get(null);
            java.lang.reflect.Method valueM = len.getClass().getMethod("value", Class.forName("ome.units.unit.Unit"));
            Object v = valueM.invoke(len, unit);
            if (v instanceof Number) return ((Number) v).doubleValue();
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int resolveInstrumentIndex(MetadataRetrieve retrieve, int imageIndex) {
        try {
            int instrumentCount = retrieve.getInstrumentCount();
            if (instrumentCount <= 0) return -1;

            String instrumentRef = trimToEmpty(retrieve.getImageInstrumentRef(imageIndex));
            if (!instrumentRef.isEmpty()) {
                for (int i = 0; i < instrumentCount; i++) {
                    if (instrumentRef.equals(trimToEmpty(retrieve.getInstrumentID(i)))) {
                        return i;
                    }
                }
            }
            return 0;
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static int resolveObjectiveIndex(MetadataRetrieve retrieve, int imageIndex, int instrumentIndex) {
        if (instrumentIndex < 0) return -1;
        try {
            int objectiveCount = retrieve.getObjectiveCount(instrumentIndex);
            if (objectiveCount <= 0) return -1;

            String objectiveRef = trimToEmpty(retrieve.getObjectiveSettingsID(imageIndex));
            if (!objectiveRef.isEmpty()) {
                for (int i = 0; i < objectiveCount; i++) {
                    if (objectiveRef.equals(trimToEmpty(retrieve.getObjectiveID(instrumentIndex, i)))) {
                        return i;
                    }
                }
            }
            return 0;
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static String readDetectorIdentifiers(MetadataRetrieve retrieve, int imageIndex,
                                                  int instrumentIndex, int channelCount) {
        LinkedHashSet<String> ids = new LinkedHashSet<String>();
        int channels = Math.max(1, channelCount);
        for (int c = 0; c < channels; c++) {
            try {
                tryAdd(ids, safeTrim(retrieve.getDetectorSettingsID(imageIndex, c)));
            } catch (Exception ignored) {}
        }

        if (ids.isEmpty() && instrumentIndex >= 0) {
            try {
                if (retrieve.getDetectorCount(instrumentIndex) == 1) {
                    tryAdd(ids, safeTrim(retrieve.getDetectorID(instrumentIndex, 0)));
                }
            } catch (Exception ignored) {}
        }
        return joinIdentifiers(ids);
    }

    private static String readLightSourceIdentifiers(MetadataRetrieve retrieve, int imageIndex,
                                                     int instrumentIndex, int channelCount) {
        LinkedHashSet<String> ids = new LinkedHashSet<String>();
        int channels = Math.max(1, channelCount);
        for (int c = 0; c < channels; c++) {
            String id = null;
            try {
                id = safeTrim(retrieve.getChannelLightSourceSettingsID(imageIndex, c));
            } catch (Exception ignored) {}
            if (id != null) {
                ids.add(id);
                continue;
            }

            Double nm = readLengthNanometers(retrieve,
                    "getChannelLightSourceSettingsWavelength", imageIndex, c);
            if (nm != null) {
                ids.add(formatLaserLine(nm.doubleValue()));
            }
        }

        if (ids.isEmpty() && instrumentIndex >= 0) {
            try {
                if (retrieve.getLightSourceCount(instrumentIndex) == 1) {
                    String laserId = safeTrim(retrieve.getLaserID(instrumentIndex, 0));
                    if (laserId != null) {
                        ids.add(laserId);
                    } else {
                        tryAdd(ids, safeTrim(retrieve.getLightSourceType(instrumentIndex, 0)));
                    }
                }
            } catch (Exception ignored) {}
        }
        return joinIdentifiers(ids);
    }

    private static String formatLaserLine(double nanometers) {
        double rounded = Math.rint(nanometers);
        if (Math.abs(rounded - nanometers) < 0.05) {
            return String.format(Locale.ROOT, "%.0f nm", rounded);
        }
        return String.format(Locale.ROOT, "%.1f nm", nanometers);
    }

    private static void tryAdd(Set<String> values, String value) {
        if (value != null && !value.isEmpty()) values.add(value);
    }

    private static String joinIdentifiers(Set<String> values) {
        if (values == null || values.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(value);
        }
        return sb.toString();
    }

    private static String normalizeEnumLabel(Object value) {
        if (value == null) return null;
        String text = trimToEmpty(String.valueOf(value));
        if (text.isEmpty()) return null;
        String lower = text.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static SoftwareInfo readSoftwareInfo(Hashtable<String, Object> globalMetadata,
                                                 Hashtable<String, Object> seriesMetadata) {
        String name = firstMetadataValue(seriesMetadata,
                new String[]{"application", "software", "acquisitionsoftware", "microscopesoftware"},
                new String[]{"application", "software"},
                true);
        if (name == null) {
            name = firstMetadataValue(globalMetadata,
                    new String[]{"application", "software", "acquisitionsoftware", "microscopesoftware"},
                    new String[]{"application", "software"},
                    true);
        }

        String version = firstMetadataValue(seriesMetadata,
                new String[]{"applicationversion", "softwareversion", "acquisitionsoftwareversion", "microscopesoftwareversion"},
                new String[]{"applicationversion", "softwareversion", "version"},
                false);
        if (version == null) {
            version = firstMetadataValue(globalMetadata,
                    new String[]{"applicationversion", "softwareversion", "acquisitionsoftwareversion", "microscopesoftwareversion"},
                    new String[]{"applicationversion", "softwareversion", "version"},
                    false);
        }

        return new SoftwareInfo(safeTrim(name), safeTrim(version));
    }

    private static String firstMetadataValue(Hashtable<String, Object> metadata,
                                             String[] exactKeys,
                                             String[] containsKeys,
                                             boolean excludeVersionKeys) {
        if (metadata == null || metadata.isEmpty()) return null;

        for (String wanted : exactKeys) {
            String matched = findMetadataValue(metadata, wanted, true, excludeVersionKeys);
            if (matched != null) return matched;
        }
        for (String wanted : containsKeys) {
            String matched = findMetadataValue(metadata, wanted, false, excludeVersionKeys);
            if (matched != null) return matched;
        }
        return null;
    }

    private static String findMetadataValue(Hashtable<String, Object> metadata,
                                            String wantedKey,
                                            boolean exactMatch,
                                            boolean excludeVersionKeys) {
        String wanted = normalizeMetadataKey(wantedKey);
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            String key = normalizeMetadataKey(entry.getKey());
            if (key.isEmpty()) continue;
            if (excludeVersionKeys && key.contains("version")) continue;

            boolean matches = exactMatch ? key.equals(wanted) : key.contains(wanted);
            if (!matches) continue;

            String value = safeTrim(entry.getValue() == null ? null : String.valueOf(entry.getValue()));
            if (value == null) continue;
            if ("null".equalsIgnoreCase(value)) continue;
            return value;
        }
        return null;
    }

    private static String normalizeMetadataKey(String key) {
        if (key == null) return "";
        return key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    // ── Check routines ────────────────────────────────

    /** P-01: objective variety. */
    public static void checkObjective(List<SeriesInfo> series, DiagnosticsReport.Section section) {
        if (series == null || series.isEmpty()) { section.info("No files scanned."); return; }
        Map<String, Integer> counts = new TreeMap<String, Integer>();
        int unknown = 0;
        for (SeriesInfo s : series) {
            String key = formatObjective(s);
            if (key == null) { unknown++; continue; }
            Integer c = counts.get(key);
            counts.put(key, c == null ? 1 : c + 1);
        }
        if (counts.isEmpty()) {
            section.info("Objective metadata not present in any file.");
            return;
        }
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            section.info(e.getValue() + " × " + e.getKey());
        }
        if (unknown > 0) section.info(unknown + " file(s) have no objective metadata.");
        if (counts.size() > 1) section.warn("More than one objective detected in this cohort.");
        else                    section.ok("All files use the same objective.");
    }

    /** P-02: groups files by available instrument identifiers without making a judgement. */
    public static void checkInstrumentFingerprint(List<SeriesInfo> series, DiagnosticsReport.Section section) {
        if (series == null || series.isEmpty()) { section.info("No files scanned."); return; }

        LinkedHashMap<String, List<String>> grouped = new LinkedHashMap<String, List<String>>();
        List<String> unknown = new ArrayList<String>();

        for (SeriesInfo s : series) {
            String fingerprint = formatInstrumentFingerprint(s);
            String displayName = displaySeriesLabel(s);
            if (fingerprint == null) {
                unknown.add(displayName);
                continue;
            }
            List<String> bucket = grouped.get(fingerprint);
            if (bucket == null) {
                bucket = new ArrayList<String>();
                grouped.put(fingerprint, bucket);
            }
            bucket.add(displayName);
        }

        for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
            section.info(entry.getValue().size() + " file" + (entry.getValue().size() == 1 ? "" : "s")
                    + ": " + entry.getKey() + ": " + formatFileList(entry.getValue()));
        }
        if (!unknown.isEmpty()) {
            section.info(unknown.size() + " file" + (unknown.size() == 1 ? "" : "s")
                    + ": unknown instrument - check acquisition: " + formatFileList(unknown));
        }
        if (grouped.isEmpty() && unknown.isEmpty()) {
            section.info("No files scanned.");
        }
    }

    /** P-04: staining-date grouping. */
    public static void checkAcquisitionDates(List<SeriesInfo> series, DiagnosticsReport.Section section) {
        if (series == null || series.isEmpty()) { section.info("No files scanned."); return; }
        Map<String, Integer> byDate = new TreeMap<String, Integer>();
        Map<String, Map<String, Integer>> countsByCondition = new TreeMap<String, Map<String, Integer>>();
        int unknown = 0;
        int unknownCondition = 0;
        for (SeriesInfo s : series) {
            if (s.acquisitionDate == null || s.acquisitionDate.isEmpty()) { unknown++; continue; }
            // Take the first 10 chars as YYYY-MM-DD, fallback to full string.
            String day = s.acquisitionDate.length() >= 10
                    ? s.acquisitionDate.substring(0, 10)
                    : s.acquisitionDate;
            Integer c = byDate.get(day);
            byDate.put(day, c == null ? 1 : c + 1);

            String condition = detectCondition(s);
            if (condition.isEmpty()) {
                unknownCondition++;
                continue;
            }
            Map<String, Integer> perCondition = countsByCondition.get(condition);
            if (perCondition == null) {
                perCondition = new TreeMap<String, Integer>();
                countsByCondition.put(condition, perCondition);
            }
            Integer cc = perCondition.get(day);
            perCondition.put(day, cc == null ? 1 : cc + 1);
        }
        if (byDate.isEmpty()) {
            section.info("No acquisition-date metadata available.");
            return;
        }
        for (Map.Entry<String, Integer> e : byDate.entrySet()) {
            section.info(e.getKey() + ": " + e.getValue() + " image(s)");
        }
        if (unknown > 0) section.info(unknown + " image(s) have no date metadata.");

        if (!countsByCondition.isEmpty()) {
            for (Map.Entry<String, Map<String, Integer>> e : countsByCondition.entrySet()) {
                section.info("Condition " + e.getKey() + ": " + formatDateCounts(e.getValue()));
            }
        }
        if (unknownCondition > 0) {
            section.info(unknownCondition + " dated image(s) could not be assigned to a condition.");
        }

        if (byDate.size() == 1) {
            section.ok("All dated images were acquired on the same day.");
            return;
        }

        if (countsByCondition.size() < 2) {
            section.warn("Multiple acquisition dates detected, but fewer than two conditions could be inferred from the image names.");
            return;
        }

        Set<String> signatures = new HashSet<String>();
        for (Map<String, Integer> perCondition : countsByCondition.values()) {
            signatures.add(formatDateSignature(perCondition));
        }
        if (signatures.size() > 1) {
            section.warn("Acquisition-date distribution differs across conditions. This can confound condition-level comparisons.");
        } else {
            section.ok("Acquisition dates look balanced across the parsed conditions.");
        }
    }

    /** P-05: Z-stack depth summary. */
    public static void checkZDepth(List<SeriesInfo> series, DiagnosticsReport.Section section) {
        if (series == null || series.isEmpty()) { section.info("No files scanned."); return; }
        Map<Integer, Integer> bySlices = new TreeMap<Integer, Integer>();
        for (SeriesInfo s : series) {
            Integer c = bySlices.get(s.sizeZ);
            bySlices.put(s.sizeZ, c == null ? 1 : c + 1);
        }
        for (Map.Entry<Integer, Integer> e : bySlices.entrySet()) {
            section.info(e.getValue() + " file(s) with " + e.getKey() + " Z-slice"
                    + (e.getKey() == 1 ? "" : "s"));
        }
        if (bySlices.size() > 1) {
            int mode = 0, modeCount = 0;
            for (Map.Entry<Integer, Integer> e : bySlices.entrySet()) {
                if (e.getValue() > modeCount) { mode = e.getKey(); modeCount = e.getValue(); }
            }
            // Flag any file more than 2× away from the mode.
            for (SeriesInfo s : series) {
                if (mode > 0 && (s.sizeZ < mode / 2 || s.sizeZ > mode * 2)) {
                    section.warn(s.file + (s.seriesIndex > 0 ? " [series " + s.seriesIndex + "]" : "")
                            + " has " + s.sizeZ + " slices (cohort mode " + mode + ")");
                }
            }
        } else {
            section.ok("All files have the same Z-depth.");
        }
    }

    /** L-03: per-objective Nyquist sampling check. Advisory. */
    public static void checkNyquist(List<SeriesInfo> series, DiagnosticsReport.Section section) {
        if (series == null || series.isEmpty()) { section.info("No files scanned."); return; }
        int reported = 0;
        for (SeriesInfo s : series) {
            NyquistCheckResult result = checkNyquist(s, null, null, Double.NaN, null, null, null, null);
            if (result == null || !result.hasWarning()) continue;
            section.info(s.file + " -- " + result.getMessage());
            reported++;
            if (reported >= 10) { section.info("(showing first 10 only)"); break; }
        }
        if (reported == 0) section.ok("Pixel sizes look consistent with objective NA.");
    }

    /** L-02: scope sniffer — simple guess from model name and objective. */
    public static void checkScopeClass(List<SeriesInfo> series, DiagnosticsReport.Section section) {
        if (series == null || series.isEmpty()) { section.info("No files scanned."); return; }
        // We don't reliably have pinhole info via the generic MetadataRetrieve surface used here,
        // so classification is best-effort based on objective magnification + model name.
        Map<String, Integer> byClass = new TreeMap<String, Integer>();
        for (SeriesInfo s : series) {
            String clazz = guessScopeClass(s);
            Integer c = byClass.get(clazz);
            byClass.put(clazz, c == null ? 1 : c + 1);
        }
        for (Map.Entry<String, Integer> e : byClass.entrySet()) {
            section.info(e.getValue() + " × " + e.getKey());
        }
    }

    public static String guessScopeClass(SeriesInfo s) {
        if (s == null) return "unclassified";
        String model = s.objectiveModel == null ? "" : s.objectiveModel.toLowerCase(Locale.ROOT);
        if (model.contains("csu") || model.contains("yokogawa") || model.contains("spinning")) return "spinning disk";
        if (s.objectiveMag != null && s.objectiveMag >= 40) return "likely confocal";
        if (s.objectiveMag != null && s.objectiveMag <= 20) return "likely widefield / tilescan";
        return "unclassified";
    }

    public static ScopeModality guessScopeModality(SeriesInfo s) {
        String guessed = guessScopeClass(s);
        if ("spinning disk".equals(guessed)) return ScopeModality.SPINNING_DISK;
        if ("likely confocal".equals(guessed)) return ScopeModality.CONFOCAL;
        if ("likely widefield / tilescan".equals(guessed)) return ScopeModality.WIDEFIELD;
        return null;
    }

    public static NyquistCheckResult checkNyquist(SeriesInfo info,
                                                  ScopeModality modality,
                                                  double[] emissionOverrideNm,
                                                  double sampleRi,
                                                  Double objectiveNaOverride,
                                                  Double pixelSizeXOverrideUm,
                                                  Double pixelSizeZOverrideUm,
                                                  boolean[] selectedChannels) {
        if (info == null) {
            return NyquistCheckResult.missing("series metadata is unavailable.");
        }

        Double naObject = objectiveNaOverride != null ? objectiveNaOverride : info.objectiveNA;
        Double pixelXObject = pixelSizeXOverrideUm != null ? pixelSizeXOverrideUm : info.pixelSizeXUm;
        Double pixelZObject = pixelSizeZOverrideUm != null ? pixelSizeZOverrideUm : info.pixelSizeZUm;
        if (naObject == null || pixelXObject == null || pixelZObject == null) {
            return NyquistCheckResult.missing("NA or pixel-size metadata is missing.");
        }

        double na = naObject.doubleValue();
        double pixelSizeXUm = pixelXObject.doubleValue();
        double pixelSizeZUm = pixelZObject.doubleValue();
        if (na <= 0.0 || pixelSizeXUm <= 0.0 || pixelSizeZUm <= 0.0) {
            return NyquistCheckResult.missing("NA or pixel-size metadata is invalid.");
        }

        double wavelengthNm = resolveNyquistWavelength(info, emissionOverrideNm, selectedChannels);
        if (Double.isNaN(wavelengthNm) || wavelengthNm <= 0.0) {
            return NyquistCheckResult.missing("emission wavelength is missing.");
        }

        double sampleRiResolved = (!Double.isNaN(sampleRi) && sampleRi > 0.0)
                ? sampleRi
                : (info.sampleRefractiveIndex == null ? Double.NaN : info.sampleRefractiveIndex.doubleValue());
        if (Double.isNaN(sampleRiResolved) || sampleRiResolved <= 0.0) {
            sampleRiResolved = RefractiveIndexEstimator.inferSampleRI(info.objectiveImmersion, null);
        }

        double emissionUm = wavelengthNm / 1000.0;
        double lateralTargetUm = emissionUm / (4.0 * na);
        double axialScale = modality == ScopeModality.CONFOCAL ? 0.5 : 1.0;
        double axialTargetUm = axialScale * emissionUm * sampleRiResolved / (na * na);
        double lateralRatio = pixelSizeXUm / lateralTargetUm;
        double axialRatio = pixelSizeZUm / axialTargetUm;

        if (lateralRatio > 1.5 || axialRatio > 1.5) {
            return NyquistCheckResult.underSampled(
                    "under-sampled: xy " + fmt(pixelSizeXUm, 3) + " um vs target ~"
                            + fmt(lateralTargetUm, 3) + " um; z " + fmt(pixelSizeZUm, 3)
                            + " um vs target ~" + fmt(axialTargetUm, 3) + " um");
        }
        if (lateralRatio < 0.3 || axialRatio < 0.3) {
            return NyquistCheckResult.overSampled(
                    "over-sampled: xy " + fmt(pixelSizeXUm, 3) + " um vs target ~"
                            + fmt(lateralTargetUm, 3) + " um; z " + fmt(pixelSizeZUm, 3)
                            + " um vs target ~" + fmt(axialTargetUm, 3) + " um");
        }
        return NyquistCheckResult.ok(
                "sampling looks reasonable for emission " + fmt(wavelengthNm, 0) + " nm");
    }

    private static double resolveNyquistWavelength(SeriesInfo info,
                                                   double[] emissionOverrideNm,
                                                   boolean[] selectedChannels) {
        double[] source = emissionOverrideNm;
        if (source == null || source.length == 0) {
            source = info.emissionWavelengthNm;
        }
        if (source == null || source.length == 0) return Double.NaN;

        double best = Double.NaN;
        for (int i = 0; i < source.length; i++) {
            if (selectedChannels != null && i < selectedChannels.length && !selectedChannels[i]) continue;
            double value = source[i];
            if (Double.isNaN(value) || Double.isInfinite(value) || value <= 0.0) continue;
            if (Double.isNaN(best) || value < best) {
                best = value;
            }
        }
        return best;
    }

    public static final class NyquistCheckResult {
        private final String state;
        private final String message;

        private NyquistCheckResult(String state, String message) {
            this.state = state;
            this.message = message;
        }

        public boolean hasWarning() {
            return isUnderSampled() || isOverSampled();
        }

        public boolean isUnderSampled() {
            return "under".equals(state);
        }

        public boolean isOverSampled() {
            return "over".equals(state);
        }

        public boolean isOk() {
            return "ok".equals(state);
        }

        public boolean isMissing() {
            return "missing".equals(state);
        }

        public String getMessage() {
            return message;
        }

        static NyquistCheckResult ok(String message) {
            return new NyquistCheckResult("ok", message);
        }

        static NyquistCheckResult underSampled(String message) {
            return new NyquistCheckResult("under", message);
        }

        static NyquistCheckResult overSampled(String message) {
            return new NyquistCheckResult("over", message);
        }

        static NyquistCheckResult missing(String message) {
            return new NyquistCheckResult("missing", message);
        }
    }

    private static String fmt(double v, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", v);
    }

    /** P-06: mixed format / bit-depth / channel count. */
    public static void checkFormatHomogeneity(List<SeriesInfo> series, DiagnosticsReport.Section section) {
        if (series == null || series.isEmpty()) { section.info("No files scanned."); return; }
        Set<String> exts = new TreeSetOrdered();
        Map<String, Integer> byPixelType = new TreeMap<String, Integer>();
        Map<Integer, Integer> byChannels = new TreeMap<Integer, Integer>();
        for (SeriesInfo s : series) {
            if (s.extension != null) exts.add(s.extension);
            String pt = s.pixelType == null ? "unknown" : s.pixelType;
            Integer cpt = byPixelType.get(pt);
            byPixelType.put(pt, cpt == null ? 1 : cpt + 1);
            Integer cc = byChannels.get(s.sizeC);
            byChannels.put(s.sizeC, cc == null ? 1 : cc + 1);
        }
        if (exts.size() > 1) {
            section.warn("Mixed file formats: " + String.join(", ", exts));
        } else if (exts.size() == 1) {
            section.ok("All files use ." + exts.iterator().next());
        }
        if (byPixelType.size() > 1) {
            StringBuilder sb = new StringBuilder("Mixed pixel types: ");
            boolean first = true;
            for (Map.Entry<String, Integer> e : byPixelType.entrySet()) {
                if (!first) sb.append(", ");
                sb.append(e.getValue()).append(" × ").append(e.getKey());
                first = false;
            }
            section.warn(sb.toString());
        } else {
            section.info("Pixel type: " + byPixelType.keySet().iterator().next());
        }
        if (byChannels.size() > 1) {
            StringBuilder sb = new StringBuilder("Mixed channel counts: ");
            boolean first = true;
            for (Map.Entry<Integer, Integer> e : byChannels.entrySet()) {
                if (!first) sb.append(", ");
                sb.append(e.getValue()).append(" × ").append(e.getKey()).append("ch");
                first = false;
            }
            section.warn(sb.toString());
        } else {
            section.info("Channel count: " + byChannels.keySet().iterator().next());
        }
    }

    // ── Helpers ───────────────────────────────────────

    private static String formatObjective(SeriesInfo s) {
        if (s.objectiveMag == null && s.objectiveNA == null && s.objectiveModel == null) return null;
        StringBuilder sb = new StringBuilder();
        if (s.objectiveMag != null) {
            sb.append(String.format(Locale.ROOT, "%.0f×", s.objectiveMag));
        }
        if (s.objectiveNA != null) {
            if (sb.length() > 0) sb.append("/");
            sb.append(String.format(Locale.ROOT, "%.2f NA", s.objectiveNA));
        }
        if (s.objectiveModel != null && !s.objectiveModel.isEmpty()) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(s.objectiveModel);
        }
        return sb.toString();
    }

    private static String formatObjectiveFingerprint(SeriesInfo s) {
        if (s == null) return null;
        if (s.objectiveMag == null && s.objectiveNA == null
                && trimToEmpty(s.objectiveModel).isEmpty()
                && trimToEmpty(s.objectiveImmersion).isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        if (!trimToEmpty(s.objectiveModel).isEmpty()) {
            sb.append(s.objectiveModel.trim());
        }
        if (s.objectiveMag != null) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(String.format(Locale.ROOT, "%.0fx", s.objectiveMag));
        }
        if (s.objectiveNA != null) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(trimTrailingZeros(s.objectiveNA.doubleValue()));
        }
        if (!trimToEmpty(s.objectiveImmersion).isEmpty()) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(s.objectiveImmersion.trim());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static String formatInstrumentFingerprint(SeriesInfo s) {
        List<String> parts = new ArrayList<String>();
        addFingerprintPart(parts, safeTrim(s == null ? null : s.microscopeModel));
        addFingerprintPart(parts, formatObjectiveFingerprint(s));
        addFingerprintPart(parts, prefixValue("detector ", safeTrim(s == null ? null : s.detectorIds)));
        addFingerprintPart(parts, prefixValue("line ", safeTrim(s == null ? null : s.lightSourceIds)));
        addFingerprintPart(parts, formatSoftwareLabel(s));
        if (parts.isEmpty()) return null;
        return joinWithSlash(parts);
    }

    private static void addFingerprintPart(List<String> parts, String value) {
        String trimmed = safeTrim(value);
        if (trimmed != null) parts.add(trimmed);
    }

    private static String prefixValue(String prefix, String value) {
        String trimmed = safeTrim(value);
        return trimmed == null ? null : prefix + trimmed;
    }

    private static String formatSoftwareLabel(SeriesInfo s) {
        if (s == null) return null;
        String software = safeTrim(s.acquisitionSoftware);
        String version = safeTrim(s.acquisitionSoftwareVersion);
        if (software == null && version == null) return null;
        if (software == null) return "software " + version;
        if (version == null || software.equals(version) || software.endsWith(version)) {
            return "software " + software;
        }
        return "software " + software + " " + version;
    }

    private static String joinWithSlash(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" / ");
            sb.append(value);
        }
        return sb.toString();
    }

    static String displaySeriesLabel(SeriesInfo info) {
        if (info == null) return "";
        String file = trimToEmpty(info.file);
        String imageName = trimToEmpty(info.imageName);
        if (imageName.isEmpty()) {
            return file.isEmpty() ? "series " + info.seriesIndex : file;
        }
        if (file.isEmpty()) return imageName;
        return ImageNameParser.buildMultiSeriesDisplayLabel(file, imageName);
    }

    private static String formatFileList(List<String> names) {
        if (names == null || names.isEmpty()) return "(none)";
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(8, names.size());
        for (int i = 0; i < limit; i++) {
            if (i > 0) sb.append(", ");
            sb.append(names.get(i));
        }
        if (names.size() > limit) {
            sb.append(", ... (").append(names.size()).append(" total)");
        }
        return sb.toString();
    }

    private static String trimTrailingZeros(double value) {
        if (Math.rint(value) == value) {
            return String.format(Locale.ROOT, "%.0f", value);
        }
        if (Math.rint(value * 10.0) == value * 10.0) {
            return String.format(Locale.ROOT, "%.1f", value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String extension(String name) {
        if (name == null) return "";
        String lower = name.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        return dot < 0 ? "" : lower.substring(dot + 1);
    }

    private static boolean isRecognizedImageFile(String name) {
        if (name == null || name.isEmpty()) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot < 0) return false;
        return IMG_EXTS.contains(lower.substring(dot + 1))
                || lower.endsWith(".ome.tif") || lower.endsWith(".ome.tiff");
    }

    private static String safeTrim(String value) {
        String trimmed = trimToEmpty(value);
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String detectCondition(SeriesInfo s) {
        String animal = extractAnimalName(s);
        if (animal.isEmpty()) return "";
        String condition = ConditionNameParser.detectCondition(animal);
        return condition == null ? "" : condition.trim();
    }

    private static String extractAnimalName(SeriesInfo s) {
        if (s == null) return "";
        if (s.imageName != null && !s.imageName.trim().isEmpty()) {
            String animal = ConditionManifestIO.extractAnimalName(s.imageName);
            if (!animal.isEmpty()) return animal;
        }
        if (s.file != null && !s.file.trim().isEmpty()) {
            return ConditionManifestIO.extractAnimalName(stripKnownImageExtension(s.file));
        }
        return "";
    }

    private static String stripKnownImageExtension(String name) {
        if (name == null) return "";
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".ome.tiff")) return name.substring(0, name.length() - 9);
        if (lower.endsWith(".ome.tif")) return name.substring(0, name.length() - 8);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String formatDateCounts(Map<String, Integer> counts) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(e.getKey()).append(" (").append(e.getValue()).append(")");
            first = false;
        }
        return sb.toString();
    }

    private static String formatDateSignature(Map<String, Integer> counts) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (!first) sb.append("|");
            sb.append(e.getKey()).append("=").append(e.getValue());
            first = false;
        }
        return sb.toString();
    }

    /** Simple wrapper to keep insertion order for extensions. */
    private static final class TreeSetOrdered extends java.util.LinkedHashSet<String> {
        private static final long serialVersionUID = 1L;
    }

    private static final class SoftwareInfo {
        final String name;
        final String version;

        SoftwareInfo(String name, String version) {
            this.name = name;
            this.version = version;
        }
    }
}
