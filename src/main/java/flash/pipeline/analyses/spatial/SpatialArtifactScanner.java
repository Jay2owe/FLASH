package flash.pipeline.analyses.spatial;

import flash.pipeline.io.CsvSupport;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.naming.ChannelFilenameCodec;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class SpatialArtifactScanner {
    private static final Pattern CPC_COLUMN = Pattern.compile(
            "^(?:CPC_.*|.+_CPC(?:Coloc|Contains|TargetsHit|Pattern).*)$");
    private static final Pattern MORPH_2D_COLUMN = Pattern.compile(
            "^(?:.+_)?Morph_(?:2D_.*|Area_um2|Perimeter_um|Circularity|Solidity|AspectRatio|Feret_um|Extent|ConvexHullArea_um2)$");
    private static final Pattern MORPH_3D_COLUMN = Pattern.compile(
            "^(?:.+_)?Morph_(?:3D_.*|Sphericity|Compactness|Elongation|Flatness|Spareness|MajorRadius_um|Feret3D_um|Moment[1-5]|DistCenter_.*|SRI|PB)$");
    private static final Pattern DISTANCE_COLUMN = Pattern.compile(
            "^(?:Distance_To_.*|NearestNeighbor_.*|.+_DistToClosest_.+|.+_ClosestTo_.+)$");
    private static final Pattern LINE_DISTANCE_COLUMN = Pattern.compile(
            "^(?:Line_Distance_.*|.+_DistTo_.+)$");

    public SpatialArtifactStatus scan(String directory,
                                      List<String> channelNames,
                                      List<SectionKey> sections) {
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
        List<String> channels = safeChannels(channelNames);
        List<SectionKey> sectionKeys = safeSections(sections);
        Map<SpatialArtifactStatus.SectionChannelKey, EnumSet<SubAnalysis>> done =
                new LinkedHashMap<SpatialArtifactStatus.SectionChannelKey, EnumSet<SubAnalysis>>();

        for (String channel : channels) {
            Set<String> columns = readObjectCsvHeader(layout.tablesObjectsWriteDir(), channel);
            EnumSet<SubAnalysis> channelDone = detectColumnSignals(columns);
            flagChannelPairs(done, channel, sectionKeys, channelDone);

            Set<String> lineColumns = readObjectCsvHeader(layout.tablesLineDistanceWriteDir(), channel);
            if (matchesAny(lineColumns, LINE_DISTANCE_COLUMN)) {
                flagChannelPairs(done, channel, sectionKeys, EnumSet.of(SubAnalysis.LINE_DISTANCE));
            }
        }

        scanDensityHeatmaps(done, layout.analysisImagesSpatialHeatmapsDir(), channels, sectionKeys);
        scanSpatialSidecars(done, layout.tablesSpatialWriteDir(), channels, sectionKeys);
        scanPhenotypingSidecars(done, new File(layout.tablesSpatialWriteDir(), "Phenotyping"), channels, sectionKeys);
        scanLineDistanceSidecars(done, layout.tablesLineDistanceWriteDir(), channels, sectionKeys);

        return new SpatialArtifactStatus(done, channels, sectionKeys);
    }

    private static EnumSet<SubAnalysis> detectColumnSignals(Set<String> columns) {
        EnumSet<SubAnalysis> out = EnumSet.noneOf(SubAnalysis.class);
        if (matchesAny(columns, CPC_COLUMN)) {
            out.add(SubAnalysis.CPC);
        }
        if (matchesAny(columns, MORPH_2D_COLUMN)) {
            out.add(SubAnalysis.MORPHOLOGY_2D);
        }
        if (matchesAny(columns, MORPH_3D_COLUMN)) {
            out.add(SubAnalysis.SHAPE_FEATURES_3D);
        }
        if (matchesAny(columns, DISTANCE_COLUMN)) {
            out.add(SubAnalysis.INTER_MARKER_DISTANCES);
        }
        if (containsAny(columns, "Voronoi_TerritoryArea_um2", "Voronoi_NumNeighbors")) {
            out.add(SubAnalysis.VORONOI);
        }
        if (containsAny(columns, "Cluster")) {
            out.add(SubAnalysis.PHENOTYPING);
        }
        if (containsAll(columns, "Morph_CMS", "Morph_SMSD", "Morph_IMDI")) {
            out.add(SubAnalysis.POPULATION_MORPHO);
        }
        if (containsAny(columns, "Morph_TDR", "Morph_FEV_Mag")) {
            out.add(SubAnalysis.SPATIAL_MORPHO);
        }
        if (matchesAny(columns, LINE_DISTANCE_COLUMN)) {
            out.add(SubAnalysis.LINE_DISTANCE);
        }
        return out;
    }

    private static void scanDensityHeatmaps(
            Map<SpatialArtifactStatus.SectionChannelKey, EnumSet<SubAnalysis>> done,
            File spatialImageRoot,
            List<String> channels,
            List<SectionKey> sections) {
        List<File> heatmaps = new ArrayList<File>();
        collectFiles(spatialImageRoot, heatmaps, new FileMatcher() {
            @Override
            public boolean matches(File file) {
                String name = file.getName();
                return name.startsWith("Density_")
                        && name.toLowerCase(Locale.ROOT).endsWith(".tif")
                        && parentName(file, 1).equals("Heatmaps");
            }
        });

        Map<String, String> safeToRaw = safeChannelMap(channels);
        List<String> safeChannels = new ArrayList<String>(safeToRaw.keySet());
        Collections.sort(safeChannels, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return b.length() - a.length();
            }
        });

        for (File tif : heatmaps) {
            String body = tif.getName().substring("Density_".length(),
                    tif.getName().length() - ".tif".length());
            String channel = null;
            String suffix = null;
            for (String safeChannel : safeChannels) {
                if (body.equals(safeChannel)) {
                    channel = safeToRaw.get(safeChannel);
                    suffix = "";
                    break;
                }
                String prefix = safeChannel + "_";
                if (body.startsWith(prefix)) {
                    channel = safeToRaw.get(safeChannel);
                    suffix = body.substring(prefix.length());
                    break;
                }
            }
            if (channel == null || suffix == null) {
                continue;
            }

            String animalFolder = parentName(tif, 2);
            for (SectionKey section : sections) {
                if (!suffix.equals(section.labelSuffix())) {
                    continue;
                }
                if (!animalFolder.isEmpty() && !section.animalName().isEmpty()
                        && !animalFolder.equals(section.animalName())) {
                    continue;
                }
                flagPair(done, section, channel, SubAnalysis.DENSITY_HEATMAPS);
            }
        }
    }

    private static void scanSpatialSidecars(
            Map<SpatialArtifactStatus.SectionChannelKey, EnumSet<SubAnalysis>> done,
            File spatialDir,
            List<String> channels,
            List<SectionKey> sections) {
        File[] files = listFiles(spatialDir);
        for (File file : files) {
            String name = file.getName();
            if (!name.toLowerCase(Locale.ROOT).endsWith(".csv")) {
                continue;
            }

            if (name.startsWith("Spatial_CPC_") || name.startsWith("CPC_")) {
                flagSidecarByFilename(done, name, channels, sections, SubAnalysis.CPC, true);
            } else if (name.startsWith("Voronoi_")) {
                flagSidecarByFilename(done, stripCsv(name.substring("Voronoi_".length())),
                        channels, sections, SubAnalysis.VORONOI, false);
            } else if (name.startsWith("SpatialStats_")) {
                flagSidecarByFilename(done, stripCsv(name.substring("SpatialStats_".length())),
                        channels, sections, SubAnalysis.RIPLEY, false);
            } else if (name.startsWith("Spatial_Statistics_")) {
                flagSidecarByFilename(done, stripCsv(name.substring("Spatial_Statistics_".length())),
                        channels, sections, SubAnalysis.RIPLEY, false);
            } else if (name.startsWith("SpatialMorphometrics_")) {
                flagSidecarByFilename(done, stripCsv(name.substring("SpatialMorphometrics_".length())),
                        channels, sections, SubAnalysis.SPATIAL_MORPHO, false);
            }
        }
    }

    private static void scanPhenotypingSidecars(
            Map<SpatialArtifactStatus.SectionChannelKey, EnumSet<SubAnalysis>> done,
            File phenotypeDir,
            List<String> channels,
            List<SectionKey> sections) {
        File[] files = listFiles(phenotypeDir);
        for (File file : files) {
            String name = file.getName();
            if (name.startsWith("Clusters_") && name.toLowerCase(Locale.ROOT).endsWith(".csv")) {
                flagSidecarByFilename(done, stripCsv(name.substring("Clusters_".length())),
                        channels, sections, SubAnalysis.PHENOTYPING, false);
            }
        }
    }

    private static void scanLineDistanceSidecars(
            Map<SpatialArtifactStatus.SectionChannelKey, EnumSet<SubAnalysis>> done,
            File lineDistanceDir,
            List<String> channels,
            List<SectionKey> sections) {
        File[] files = listFiles(lineDistanceDir);
        for (File file : files) {
            String name = file.getName();
            if (name.startsWith("LineDistance_") && name.toLowerCase(Locale.ROOT).endsWith(".csv")) {
                flagSidecarByFilename(done, stripCsv(name.substring("LineDistance_".length())),
                        channels, sections, SubAnalysis.LINE_DISTANCE, false);
            }
        }
    }

    private static Set<String> readObjectCsvHeader(File dir, String channel) {
        File file = channelCsv(dir, channel);
        if (file == null || !file.isFile()) {
            return Collections.emptySet();
        }

        CsvSupport.RecordReader reader = null;
        try {
            reader = CsvSupport.openRecordReader(file);
            CsvSupport.Record record = reader.readRecord();
            if (record == null || CsvSupport.isBlankRecord(record.text)) {
                return Collections.emptySet();
            }
            String header = stripBom(record.text);
            String[] columns = header.indexOf('\t') >= 0 && header.indexOf(',') < 0
                    ? header.split("\t", -1)
                    : CsvSupport.parseRecord(header);
            Set<String> out = new HashSet<String>();
            for (String column : columns) {
                String trimmed = column == null ? "" : stripBom(column).trim();
                if (!trimmed.isEmpty()) {
                    out.add(trimmed);
                }
            }
            return out;
        } catch (IOException e) {
            return Collections.emptySet();
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                    // Safe-empty scanner: one bad CSV must not abort the scan.
                }
            }
        }
    }

    private static File channelCsv(File dir, String channel) {
        if (dir == null || channel == null) {
            return null;
        }
        File safe = new File(dir, ChannelFilenameCodec.toSafe(channel) + ".csv");
        if (safe.isFile()) {
            return safe;
        }
        File raw = new File(dir, channel + ".csv");
        return raw.isFile() ? raw : safe;
    }

    private static void flagSidecarByFilename(
            Map<SpatialArtifactStatus.SectionChannelKey, EnumSet<SubAnalysis>> done,
            String filenameToken,
            List<String> channels,
            List<SectionKey> sections,
            SubAnalysis sub,
            boolean allChannelsIfUnmatched) {
        List<String> matchedChannels = channelsInToken(filenameToken, channels);
        if (matchedChannels.isEmpty() && allChannelsIfUnmatched) {
            matchedChannels = channels;
        }
        if (matchedChannels.isEmpty()) {
            return;
        }

        List<SectionKey> matchedSections = sectionsInToken(filenameToken, sections);
        if (matchedSections.isEmpty()) {
            matchedSections = sections;
        }
        for (String channel : matchedChannels) {
            for (SectionKey section : matchedSections) {
                flagPair(done, section, channel, sub);
            }
        }
    }

    private static List<String> channelsInToken(String token, List<String> channels) {
        List<String> out = new ArrayList<String>();
        if (token == null) {
            return out;
        }
        for (String channel : channels) {
            String safe = ChannelFilenameCodec.toSafe(channel);
            if (token.equals(safe) || token.indexOf(safe) >= 0 || token.indexOf(channel) >= 0) {
                out.add(channel);
            }
        }
        return out;
    }

    private static List<SectionKey> sectionsInToken(String token, List<SectionKey> sections) {
        List<SectionKey> out = new ArrayList<SectionKey>();
        if (token == null) {
            return out;
        }
        for (SectionKey section : sections) {
            String suffix = section.labelSuffix();
            if (!suffix.isEmpty() && token.indexOf(suffix) >= 0) {
                out.add(section);
            }
        }
        return out;
    }

    private static void flagChannelPairs(
            Map<SpatialArtifactStatus.SectionChannelKey, EnumSet<SubAnalysis>> done,
            String channel,
            List<SectionKey> sections,
            EnumSet<SubAnalysis> subs) {
        if (subs == null || subs.isEmpty()) {
            return;
        }
        for (SectionKey section : sections) {
            for (SubAnalysis sub : subs) {
                flagPair(done, section, channel, sub);
            }
        }
    }

    private static void flagPair(
            Map<SpatialArtifactStatus.SectionChannelKey, EnumSet<SubAnalysis>> done,
            SectionKey section,
            String channel,
            SubAnalysis sub) {
        if (section == null || channel == null || sub == null) {
            return;
        }
        SpatialArtifactStatus.SectionChannelKey key =
                new SpatialArtifactStatus.SectionChannelKey(section, channel);
        EnumSet<SubAnalysis> subs = done.get(key);
        if (subs == null) {
            subs = EnumSet.noneOf(SubAnalysis.class);
            done.put(key, subs);
        }
        subs.add(sub);
    }

    private static Map<String, String> safeChannelMap(List<String> channels) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        for (String channel : channels) {
            out.put(ChannelFilenameCodec.toSafe(channel), channel);
        }
        return out;
    }

    private static boolean matchesAny(Set<String> columns, Pattern pattern) {
        if (columns == null || columns.isEmpty()) {
            return false;
        }
        for (String column : columns) {
            if (pattern.matcher(column).matches()) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(Set<String> columns, String... names) {
        for (int i = 0; i < names.length; i++) {
            if (columns.contains(names[i])) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAll(Set<String> columns, String... names) {
        for (int i = 0; i < names.length; i++) {
            if (!columns.contains(names[i])) {
                return false;
            }
        }
        return true;
    }

    private static File[] listFiles(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return new File[0];
        }
        File[] files = dir.listFiles();
        if (files != null) {
            java.util.Arrays.sort(files);
        }
        return files == null ? new File[0] : files;
    }

    private static void collectFiles(File root, List<File> out, FileMatcher matcher) {
        File[] files = listFiles(root);
        for (File file : files) {
            if (file.isDirectory()) {
                collectFiles(file, out, matcher);
            } else if (matcher.matches(file)) {
                out.add(file);
            }
        }
    }

    private static String parentName(File file, int levels) {
        File current = file;
        for (int i = 0; i < levels; i++) {
            if (current == null) {
                return "";
            }
            current = current.getParentFile();
        }
        return current == null ? "" : current.getName();
    }

    private static String stripCsv(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".csv")
                ? name.substring(0, name.length() - 4)
                : name;
    }

    private static String stripBom(String value) {
        if (value != null && value.length() > 0 && value.charAt(0) == '\uFEFF') {
            return value.substring(1);
        }
        return value;
    }

    private static List<String> safeChannels(List<String> channelNames) {
        List<String> out = new ArrayList<String>();
        if (channelNames != null) {
            for (String channelName : channelNames) {
                if (channelName != null) {
                    out.add(channelName);
                }
            }
        }
        return out;
    }

    private static List<SectionKey> safeSections(List<SectionKey> sections) {
        List<SectionKey> out = new ArrayList<SectionKey>();
        if (sections != null) {
            for (SectionKey section : sections) {
                if (section != null) {
                    out.add(section);
                }
            }
        }
        return out;
    }

    private interface FileMatcher {
        boolean matches(File file);
    }
}
