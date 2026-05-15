package flash.pipeline.spatial;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.triangulate.DelaunayTriangulationBuilder;
import org.locationtech.jts.triangulate.VoronoiDiagramBuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Voronoi tessellation and interaction analysis from 2D centroid data.
 *
 * <p>Uses JTS to build Voronoi diagrams clipped to a rectangular observation
 * window. Computes per-cell territory areas, neighbour counts, and an
 * inter-type interaction matrix with permutation significance testing.
 *
 * <p>No ImageJ dependencies — operates on raw coordinate arrays.
 */
public final class VoronoiAnalysis {

    private VoronoiAnalysis() {}

    /** Per-object Voronoi result. */
    public static final class VoronoiResult {
        public final int index;
        public final double territoryArea;
        public final int numNeighbors;
        public final int[] neighborIndices;

        public VoronoiResult(int index, double territoryArea, int numNeighbors, int[] neighborIndices) {
            this.index = index;
            this.territoryArea = territoryArea;
            this.numNeighbors = numNeighbors;
            this.neighborIndices = neighborIndices;
        }
    }

    /** Interaction matrix result with permutation p-values. */
    public static final class InteractionMatrix {
        /** Observed adjacency counts: counts[typeA][typeB]. */
        public final int[][] counts;
        /** Permutation p-values (two-tailed). */
        public final double[][] pValues;
        /** Type labels in order. */
        public final String[] types;

        public InteractionMatrix(int[][] counts, double[][] pValues, String[] types) {
            this.counts = counts;
            this.pValues = pValues;
            this.types = types;
        }
    }

    /**
     * Computes Voronoi tessellation clipped to a rectangular window.
     *
     * @param centroids 2D points [n][2] in micron coordinates
     * @param window    observation window bounds
     * @return per-object Voronoi results, or empty array if fewer than 2 points
     */
    public static VoronoiResult[] compute(double[][] centroids,
                                          SpatialStatistics.RectangularWindow window) {
        if (centroids == null || centroids.length < 2 || window == null) {
            return new VoronoiResult[0];
        }
        for (double[] pt : centroids) {
            if (!validPoint(pt)) return new VoronoiResult[0];
        }

        GeometryFactory factory = new GeometryFactory();
        Envelope clip = new Envelope(window.minX, window.maxX, window.minY, window.maxY);
        Geometry clipPoly = factory.toGeometry(clip);

        // Build Voronoi diagram
        Collection<Coordinate> sites = new ArrayList<Coordinate>(centroids.length);
        for (double[] pt : centroids) {
            sites.add(new Coordinate(pt[0], pt[1]));
        }

        VoronoiDiagramBuilder builder = new VoronoiDiagramBuilder();
        builder.setSites(sites);
        builder.setClipEnvelope(clip);
        Geometry diagram = builder.getDiagram(factory);

        // Map each Voronoi cell to its generating point
        // JTS returns cells in the same order as the sorted sites, so we need
        // to match cells back to input centroids by finding which centroid
        // falls inside each cell.
        int n = centroids.length;
        Geometry[] cells = new Geometry[n];
        double[] areas = new double[n];

        if (diagram instanceof GeometryCollection) {
            GeometryCollection gc = (GeometryCollection) diagram;
            for (int g = 0; g < gc.getNumGeometries(); g++) {
                Geometry cell = gc.getGeometryN(g);
                Geometry clipped = cell.intersection(clipPoly);
                // Find which centroid is inside this cell
                for (int i = 0; i < n; i++) {
                    if (cells[i] != null) continue;
                    Coordinate c = new Coordinate(centroids[i][0], centroids[i][1]);
                    if (cell.contains(factory.createPoint(c)) ||
                        cell.distance(factory.createPoint(c)) < 1e-6) {
                        cells[i] = clipped;
                        areas[i] = clipped.getArea();
                        break;
                    }
                }
            }
        }

        // Build Delaunay triangulation for adjacency
        DelaunayTriangulationBuilder delaunay = new DelaunayTriangulationBuilder();
        delaunay.setSites(sites);
        Geometry edges = delaunay.getEdges(factory);

        // Build adjacency lists from Delaunay edges
        Map<Integer, List<Integer>> adjacency = new HashMap<Integer, List<Integer>>();
        for (int i = 0; i < n; i++) {
            adjacency.put(i, new ArrayList<Integer>());
        }

        if (edges instanceof GeometryCollection) {
            GeometryCollection ec = (GeometryCollection) edges;
            for (int e = 0; e < ec.getNumGeometries(); e++) {
                Geometry edge = ec.getGeometryN(e);
                Coordinate[] coords = edge.getCoordinates();
                if (coords.length < 2) continue;
                int idxA = findClosestCentroid(centroids, coords[0].x, coords[0].y);
                int idxB = findClosestCentroid(centroids, coords[coords.length - 1].x, coords[coords.length - 1].y);
                if (idxA >= 0 && idxB >= 0 && idxA != idxB) {
                    if (!adjacency.get(idxA).contains(idxB)) adjacency.get(idxA).add(idxB);
                    if (!adjacency.get(idxB).contains(idxA)) adjacency.get(idxB).add(idxA);
                }
            }
        }

        VoronoiResult[] results = new VoronoiResult[n];
        for (int i = 0; i < n; i++) {
            List<Integer> neighbors = adjacency.get(i);
            int[] neighborArr = new int[neighbors.size()];
            for (int j = 0; j < neighbors.size(); j++) neighborArr[j] = neighbors.get(j);
            results[i] = new VoronoiResult(i, areas[i], neighbors.size(), neighborArr);
        }
        return results;
    }

    /**
     * Computes a cell-type interaction matrix from Voronoi adjacency,
     * with permutation testing for significance.
     *
     * @param results       Voronoi results with neighbor indices
     * @param objectTypes   type label per object (parallel to results)
     * @param nPermutations number of random label permutations (e.g. 1000)
     * @param seed          random seed for reproducibility
     * @return interaction matrix with observed counts and p-values
     */
    public static InteractionMatrix computeInteractionMatrix(VoronoiResult[] results,
                                                              String[] objectTypes,
                                                              int nPermutations,
                                                              long seed) {
        if (results == null || objectTypes == null) {
            return new InteractionMatrix(new int[0][0], new double[0][0], new String[0]);
        }
        // Collect unique types in encounter order
        List<String> typeList = new ArrayList<String>();
        Map<String, Integer> typeIndex = new LinkedHashMap<String, Integer>();
        String[] safeTypes = new String[objectTypes.length];
        for (int i = 0; i < objectTypes.length; i++) {
            String t = objectTypes[i];
            if (t == null) t = "";
            safeTypes[i] = t;
            if (!typeIndex.containsKey(t)) {
                typeIndex.put(t, typeList.size());
                typeList.add(t);
            }
        }
        int nTypes = typeList.size();
        String[] types = typeList.toArray(new String[0]);

        // Observed counts
        int[][] observed = countInteractions(results, safeTypes, typeIndex, nTypes);

        // Permutation test
        double[][] pValues = new double[nTypes][nTypes];
        if (nPermutations > 0 && results.length > 1) {
            int[][] exceedCount = new int[nTypes][nTypes];
            Random rng = new Random(seed);
            String[] shuffled = Arrays.copyOf(safeTypes, safeTypes.length);

            for (int p = 0; p < nPermutations; p++) {
                // Fisher-Yates shuffle
                for (int i = shuffled.length - 1; i > 0; i--) {
                    int j = rng.nextInt(i + 1);
                    String tmp = shuffled[i];
                    shuffled[i] = shuffled[j];
                    shuffled[j] = tmp;
                }
                int[][] perm = countInteractions(results, shuffled, typeIndex, nTypes);
                for (int a = 0; a < nTypes; a++) {
                    for (int b = a; b < nTypes; b++) {
                        if (perm[a][b] >= observed[a][b]) {
                            exceedCount[a][b]++;
                            if (a != b) exceedCount[b][a]++;
                        }
                    }
                }
            }

            for (int a = 0; a < nTypes; a++) {
                for (int b = 0; b < nTypes; b++) {
                    pValues[a][b] = (double) exceedCount[a][b] / nPermutations;
                }
            }
        }

        return new InteractionMatrix(observed, pValues, types);
    }

    private static int[][] countInteractions(VoronoiResult[] results, String[] types,
                                              Map<String, Integer> typeIndex, int nTypes) {
        int[][] counts = new int[nTypes][nTypes];
        for (VoronoiResult r : results) {
            if (r == null || r.index < 0 || r.index >= types.length) continue;
            int typeA = typeIndex.get(types[r.index]);
            for (int neighborIdx : r.neighborIndices) {
                if (neighborIdx >= types.length) continue;
                int typeB = typeIndex.get(types[neighborIdx]);
                // Count each edge once (undirected)
                if (r.index < neighborIdx) {
                    counts[typeA][typeB]++;
                    if (typeA != typeB) counts[typeB][typeA]++;
                }
            }
        }
        return counts;
    }

    private static int findClosestCentroid(double[][] centroids, double x, double y) {
        int best = -1;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < centroids.length; i++) {
            double dx = centroids[i][0] - x;
            double dy = centroids[i][1] - y;
            double d = dx * dx + dy * dy;
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    private static boolean validPoint(double[] point) {
        return point != null && point.length >= 2
                && Double.isFinite(point[0]) && Double.isFinite(point[1]);
    }
}
