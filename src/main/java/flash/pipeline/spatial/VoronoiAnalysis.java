package flash.pipeline.spatial;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.GeometryFactory;
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
        Map<Coordinate, Integer> siteIndices = new HashMap<Coordinate, Integer>();
        for (int i = 0; i < centroids.length; i++) {
            double[] pt = centroids[i];
            Coordinate site = new Coordinate(pt[0] == 0.0 ? 0.0 : pt[0], pt[1] == 0.0 ? 0.0 : pt[1]);
            if (siteIndices.put(site, Integer.valueOf(i)) != null) {
                throw new IllegalArgumentException("Voronoi territories require distinct centroids; duplicate at object " + i);
            }
            sites.add(site);
        }

        VoronoiDiagramBuilder builder = new VoronoiDiagramBuilder();
        builder.setSites(sites);
        builder.setClipEnvelope(clip);
        Geometry diagram = builder.getDiagram(factory);

        // JTS records the exact generating Coordinate as each cell's userData.
        // Spatial proximity is not identity: nearby sites can have very different territories.
        int n = centroids.length;
        Geometry[] cells = new Geometry[n];
        double[] areas = new double[n];

        if (diagram instanceof GeometryCollection) {
            GeometryCollection gc = (GeometryCollection) diagram;
            for (int g = 0; g < gc.getNumGeometries(); g++) {
                Geometry cell = gc.getGeometryN(g);
                Geometry clipped = cell.intersection(clipPoly);
                Integer index = siteIndices.get(cell.getUserData());
                if (index == null) throw new IllegalStateException("Voronoi cell has no matching generating centroid.");
                cells[index.intValue()] = clipped;
                areas[index.intValue()] = clipped.getArea();
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
                Integer mappedA = siteIndices.get(coords[0]);
                Integer mappedB = siteIndices.get(coords[coords.length - 1]);
                int idxA = mappedA == null ? -1 : mappedA.intValue();
                int idxB = mappedB == null ? -1 : mappedB.intValue();
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
        for (double[] row : pValues) Arrays.fill(row, Double.NaN);
        if (nPermutations > 0 && results.length > 1) {
            int[][] exceedCount = new int[nTypes][nTypes];
            int[][] lowerCount = new int[nTypes][nTypes];
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
                        if (perm[a][b] <= observed[a][b]) {
                            lowerCount[a][b]++;
                            if (a != b) lowerCount[b][a]++;
                        }
                    }
                }
            }

            for (int a = 0; a < nTypes; a++) {
                for (int b = 0; b < nTypes; b++) {
                    // Equal-tail two-sided permutation test, with the observed
                    // arrangement included so random sampling never reports p=0.
                    pValues[a][b] = Math.min(1.0, 2.0
                            * (Math.min(exceedCount[a][b], lowerCount[a][b]) + 1.0)
                            / (nPermutations + 1.0));
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
                if (neighborIdx < 0 || neighborIdx >= types.length) continue;
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

    private static boolean validPoint(double[] point) {
        return point != null && point.length >= 2
                && Double.isFinite(point[0]) && Double.isFinite(point[1]);
    }
}
