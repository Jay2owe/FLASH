package flash.pipeline.project;

import flash.pipeline.io.ImageSourceDispatcher;
import flash.pipeline.io.LifIO;
import flash.pipeline.io.OrientationManifestIO;
import flash.pipeline.naming.ImageNameParser;
import flash.pipeline.naming.OrientationManifestRow;
import ij.IJ;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Swing-free bridge that turns a {@link ProjectFile}'s per-series identity into
 * confirmed {@code Image Orientation.csv} rows, so the existing
 * {@link flash.pipeline.naming.ImageOrientationResolver} hands those values to
 * every downstream analysis instead of re-deriving identity from the series
 * name.
 *
 * <p>The image key is built exactly as {@code CreateBinFileAnalysis} builds it
 * when reviewing metadata — {@code "CONTAINER|<file>|<1-based series>|<series name>"}
 * (see {@link OrientationManifestRow#buildImageKey}) — so the rows seeded here
 * are picked up by both the resolver and the Set Up Configuration QC review
 * (which preserves a saved row found by key).
 *
 * <p>Scope: only multi-series container sources (LIF/CZI/ND2…) that the user
 * expanded into per-series rows are seeded. Bare-TIFF projects keep their
 * existing behaviour (file-level metadata, condition via {@code Conditions.csv},
 * identity parsed from the file name).
 */
public final class ProjectMetadataSeeder {

    private static final String DECISION_NOTE = "Assigned in Project Builder";

    private ProjectMetadataSeeder() {
    }

    /**
     * Build the orientation rows implied by a project's per-series metadata. A
     * row is produced for every <em>included</em> series of a container source
     * that carries at least one identity field (animal / hemisphere / region).
     */
    public static List<OrientationManifestRow> orientationRowsFor(ProjectFile project) {
        List<OrientationManifestRow> rows = new ArrayList<OrientationManifestRow>();
        if (project == null || project.items == null) {
            return rows;
        }
        int globalSeriesIndex = 0;
        for (ProjectFile.Item item : project.items) {
            if (item == null || !item.include) continue;
            if (item.path == null || item.path.trim().isEmpty()) continue;
            String fileName = new File(item.path).getName();
            if (!isContainerExtension(fileName)) continue;
            if (item.seriesMeta == null || item.seriesMeta.isEmpty()) {
                globalSeriesIndex += includedContainerSeriesCount(item);
                continue;
            }

            for (ProjectFile.SeriesItem series : includedSeriesInProjectOrder(item)) {
                int oneBasedSeriesIndex = globalSeriesIndex + 1;
                globalSeriesIndex++;
                if (series == null) continue;
                String animal = trimToEmpty(series.animalId);
                String region = trimToEmpty(series.region);
                OrientationManifestRow.Hemisphere hemisphere =
                        OrientationManifestRow.Hemisphere.fromCsv(series.hemisphere);
                boolean hasIdentity = !animal.isEmpty()
                        || !region.isEmpty()
                        || hemisphere != OrientationManifestRow.Hemisphere.UNKNOWN;
                if (!hasIdentity) continue;

                String originalName = metadataOriginalName(fileName, series.name);
                String imageKey = OrientationManifestRow.buildImageKey(
                        "CONTAINER", fileName, oneBasedSeriesIndex, originalName);
                String displayName = ImageNameParser.extractBioFormatsSeriesName(originalName);
                if (displayName == null || displayName.trim().isEmpty()) {
                    displayName = originalName;
                }
                OrientationDefaults defaults = orientationDefaults(hemisphere);
                rows.add(new OrientationManifestRow(
                        imageKey,
                        fileName,
                        oneBasedSeriesIndex,
                        originalName,
                        displayName,
                        animal,
                        hemisphere,
                        region,
                        defaults.rotateDegrees,
                        defaults.flipHorizontal,
                        false,
                        OrientationManifestRow.ViewPolicy.MANUAL_ONLY,
                        OrientationManifestRow.DecisionSource.MANUAL,
                        OrientationManifestRow.ConfirmationState.YES,
                        DECISION_NOTE));
            }
        }
        return rows;
    }

    private static List<ProjectFile.SeriesItem> includedSeriesInProjectOrder(ProjectFile.Item item) {
        List<ProjectFile.SeriesItem> ordered = new ArrayList<ProjectFile.SeriesItem>();
        if (item == null || item.seriesMeta == null || item.seriesMeta.isEmpty()) {
            return ordered;
        }
        if (item.series != null && !item.series.isEmpty()) {
            LinkedHashMap<Integer, ProjectFile.SeriesItem> byIndex =
                    new LinkedHashMap<Integer, ProjectFile.SeriesItem>();
            for (ProjectFile.SeriesItem series : item.seriesMeta) {
                if (series == null) continue;
                byIndex.put(Integer.valueOf(series.index), series);
            }
            for (Integer included : item.series) {
                if (included == null) continue;
                ProjectFile.SeriesItem series = byIndex.get(included);
                ordered.add(series);
            }
            return ordered;
        }
        for (ProjectFile.SeriesItem series : item.seriesMeta) {
            if (series != null && series.include) ordered.add(series);
        }
        return ordered;
    }

    private static int includedContainerSeriesCount(ProjectFile.Item item) {
        if (item == null) return 0;
        if (item.series != null && !item.series.isEmpty()) {
            return explicitIncludedSeriesCount(item.series);
        }
        File source = item.path == null ? null : new File(item.path);
        if (source == null || !source.isFile()) return 0;
        try {
            return Math.max(0, LifIO.getSeriesCount(source));
        } catch (Exception e) {
            return 0;
        }
    }

    static int explicitIncludedSeriesCount(List<Integer> series) {
        if (series == null || series.isEmpty()) return 0;
        int count = 0;
        for (Integer ignored : series) {
            if (ignored != null) count++;
        }
        return count;
    }

    private static String metadataOriginalName(String fileName, String seriesName) {
        String name = trimToEmpty(seriesName);
        if (name.isEmpty()) return trimToEmpty(fileName);
        if (name.indexOf(" - ") >= 0) return name;
        String container = trimToEmpty(fileName);
        return container.isEmpty() ? name : container + " - " + name;
    }

    /**
     * Merge the project's per-series orientation rows into the existing
     * {@code Image Orientation.csv}. Existing manual ROI decisions are
     * preserved; rows previously generated from Project Builder metadata are
     * refreshed when project identity/default orientation changes.
     */
    public static void seedOrientationManifest(File outputRoot, ProjectFile project) throws IOException {
        if (outputRoot == null) {
            return;
        }
        List<OrientationManifestRow> seeded = orientationRowsFor(project);
        if (seeded.isEmpty()) {
            return;
        }
        String directory = outputRoot.getAbsolutePath();
        LinkedHashMap<String, OrientationManifestRow> byKey =
                OrientationManifestIO.readByImageKeyIfExists(directory);
        boolean changed = false;
        for (OrientationManifestRow row : seeded) {
            OrientationManifestRow existing = byKey.get(row.imageKey);
            if (existing == null) {
                byKey.put(row.imageKey, row);
                changed = true;
            } else if (isProjectBuilderSeed(existing) && !sameRow(existing, row)) {
                byKey.put(row.imageKey, row);
                changed = true;
            }
        }
        if (!changed) {
            return;
        }
        OrientationManifestIO.saveRows(directory, new ArrayList<OrientationManifestRow>(byKey.values()));
        IJ.log("[FLASH Project] Synced " + seeded.size()
                + " project image metadata row(s) into "
                + OrientationManifestIO.getFile(directory).getAbsolutePath());
    }

    private static boolean isProjectBuilderSeed(OrientationManifestRow row) {
        return row != null
                && row.decisionSource == OrientationManifestRow.DecisionSource.MANUAL
                && DECISION_NOTE.equals(row.notes);
    }

    private static boolean sameRow(OrientationManifestRow a, OrientationManifestRow b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return same(a.imageKey, b.imageKey)
                && same(a.sourceFile, b.sourceFile)
                && a.seriesIndex == b.seriesIndex
                && same(a.originalName, b.originalName)
                && same(a.displayName, b.displayName)
                && same(a.animalName, b.animalName)
                && a.hemisphere == b.hemisphere
                && same(a.region, b.region)
                && a.rotateDegrees == b.rotateDegrees
                && a.flipHorizontal == b.flipHorizontal
                && a.flipVertical == b.flipVertical
                && a.viewPolicy == b.viewPolicy
                && a.decisionSource == b.decisionSource
                && a.confirmed == b.confirmed
                && same(a.notes, b.notes);
    }

    private static boolean same(String a, String b) {
        return trimToEmpty(a).equals(trimToEmpty(b));
    }

    private static OrientationDefaults orientationDefaults(
            OrientationManifestRow.Hemisphere hemisphere) {
        boolean known = hemisphere == OrientationManifestRow.Hemisphere.LH
                || hemisphere == OrientationManifestRow.Hemisphere.RH;
        return new OrientationDefaults(
                known ? OrientationManifestRow.RotationDegrees.DEG_270
                        : OrientationManifestRow.RotationDegrees.DEG_0,
                hemisphere == OrientationManifestRow.Hemisphere.RH);
    }

    private static final class OrientationDefaults {
        final OrientationManifestRow.RotationDegrees rotateDegrees;
        final boolean flipHorizontal;

        OrientationDefaults(OrientationManifestRow.RotationDegrees rotateDegrees,
                            boolean flipHorizontal) {
            this.rotateDegrees = rotateDegrees;
            this.flipHorizontal = flipHorizontal;
        }
    }

    private static boolean isContainerExtension(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : ImageSourceDispatcher.CONTAINER_EXTENSIONS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
