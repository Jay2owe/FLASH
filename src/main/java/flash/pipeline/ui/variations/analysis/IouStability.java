package flash.pipeline.ui.variations.analysis;

import flash.pipeline.ui.variations.ParameterCombo;
import flash.pipeline.ui.variations.ParameterId;

import ij.ImagePlus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;

public final class IouStability {

    private static final long TIME_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(5L);

    private IouStability() {
    }

    public static OptionalInt findMostStable(List<ParameterCombo> combos,
                                             List<ImagePlus> labels) {
        ScoreSummary summary = score(combos, labels, -1);
        if (summary.aborted) {
            System.err.println("Skipping IoU stability hint: computation exceeded 5 seconds.");
        }
        if (summary.bestIndex < 0 || summary.bestMean <= 0.0d) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(summary.bestIndex);
    }

    public static double meanNeighbourIou(List<ParameterCombo> combos,
                                          List<ImagePlus> labels,
                                          int index) {
        ScoreSummary summary = score(combos, labels, index);
        return summary.targetMean;
    }

    private static ScoreSummary score(List<ParameterCombo> combos,
                                      List<ImagePlus> labels,
                                      int targetIndex) {
        if (combos == null || labels == null
                || combos.size() < 3
                || combos.size() != labels.size()) {
            return ScoreSummary.empty();
        }
        if (targetIndex >= combos.size()) {
            return ScoreSummary.empty();
        }
        Topology topology = Topology.from(combos);
        if (topology == null) {
            return ScoreSummary.empty();
        }

        long started = System.nanoTime();
        Map<Long, Double> pairCache = new HashMap<Long, Double>();
        int bestIndex = -1;
        double bestMean = Double.NEGATIVE_INFINITY;
        double targetMean = Double.NaN;
        for (int i = 0; i < combos.size(); i++) {
            if (targetIndex >= 0 && i != targetIndex) {
                continue;
            }
            if (labels.get(i) == null) {
                continue;
            }
            List<Integer> neighbours = topology.neighboursOf(i);
            double total = 0.0d;
            int count = 0;
            for (int n = 0; n < neighbours.size(); n++) {
                if (System.nanoTime() - started > TIME_BUDGET_NANOS) {
                    return ScoreSummary.aborted();
                }
                int neighbourIndex = neighbours.get(n).intValue();
                if (labels.get(neighbourIndex) == null) {
                    continue;
                }
                Double cached = pairCache.get(pairKey(i, neighbourIndex));
                double value;
                if (cached == null) {
                    try {
                        value = LabelIou.iou(labels.get(i), labels.get(neighbourIndex));
                    } catch (IllegalArgumentException e) {
                        continue;
                    }
                    pairCache.put(pairKey(i, neighbourIndex), Double.valueOf(value));
                } else {
                    value = cached.doubleValue();
                }
                total += value;
                count++;
            }
            if (count == 0) {
                continue;
            }
            double mean = total / count;
            if (targetIndex >= 0) {
                targetMean = mean;
                break;
            }
            if (mean > bestMean) {
                bestMean = mean;
                bestIndex = i;
            }
        }
        return new ScoreSummary(false, bestIndex, bestMean, targetMean);
    }

    private static long pairKey(int a, int b) {
        int low = Math.min(a, b);
        int high = Math.max(a, b);
        return (((long) low) << 32) ^ (high & 0xffffffffL);
    }

    private static final class ScoreSummary {
        final boolean aborted;
        final int bestIndex;
        final double bestMean;
        final double targetMean;

        ScoreSummary(boolean aborted,
                     int bestIndex,
                     double bestMean,
                     double targetMean) {
            this.aborted = aborted;
            this.bestIndex = bestIndex;
            this.bestMean = bestMean;
            this.targetMean = targetMean;
        }

        static ScoreSummary empty() {
            return new ScoreSummary(false, -1, Double.NEGATIVE_INFINITY, Double.NaN);
        }

        static ScoreSummary aborted() {
            return new ScoreSummary(true, -1, Double.NEGATIVE_INFINITY, Double.NaN);
        }
    }

    private static final class Topology {
        private final int[][] coordinates;
        private final Map<Coordinate, Integer> indexesByCoordinate;

        private Topology(int[][] coordinates,
                         Map<Coordinate, Integer> indexesByCoordinate) {
            this.coordinates = coordinates;
            this.indexesByCoordinate = indexesByCoordinate;
        }

        static Topology from(List<ParameterCombo> combos) {
            LinkedHashMap<ParameterId, List<Object>> valuesById =
                    new LinkedHashMap<ParameterId, List<Object>>();
            for (int i = 0; i < combos.size(); i++) {
                ParameterCombo combo = combos.get(i);
                if (combo == null) {
                    return null;
                }
                for (Map.Entry<ParameterId, Object> entry : combo.values().entrySet()) {
                    List<Object> values = valuesById.get(entry.getKey());
                    if (values == null) {
                        values = new ArrayList<Object>();
                        valuesById.put(entry.getKey(), values);
                    }
                    if (!values.contains(entry.getValue())) {
                        values.add(entry.getValue());
                    }
                }
            }

            List<Axis> axes = new ArrayList<Axis>();
            for (Map.Entry<ParameterId, List<Object>> entry : valuesById.entrySet()) {
                if (entry.getValue().size() > 1) {
                    axes.add(new Axis(entry.getKey(), entry.getValue()));
                }
            }
            if (axes.isEmpty() || axes.size() > 2) {
                return null;
            }

            int[][] coordinates = new int[combos.size()][axes.size()];
            Map<Coordinate, Integer> indexesByCoordinate =
                    new HashMap<Coordinate, Integer>();
            for (int i = 0; i < combos.size(); i++) {
                ParameterCombo combo = combos.get(i);
                for (int axisIndex = 0; axisIndex < axes.size(); axisIndex++) {
                    Axis axis = axes.get(axisIndex);
                    int valueIndex = axis.indexOf(combo.get(axis.id));
                    if (valueIndex < 0) {
                        return null;
                    }
                    coordinates[i][axisIndex] = valueIndex;
                }
                Coordinate coordinate = new Coordinate(coordinates[i]);
                if (indexesByCoordinate.put(coordinate, Integer.valueOf(i)) != null) {
                    return null;
                }
            }
            return new Topology(coordinates, indexesByCoordinate);
        }

        List<Integer> neighboursOf(int index) {
            List<Integer> out = new ArrayList<Integer>();
            if (index < 0 || index >= coordinates.length) {
                return out;
            }
            collectNeighbours(coordinates[index], new int[coordinates[index].length],
                    0, out);
            return out;
        }

        private void collectNeighbours(int[] origin,
                                       int[] offsets,
                                       int dimension,
                                       List<Integer> out) {
            if (dimension >= offsets.length) {
                boolean allZero = true;
                int[] coordinate = new int[origin.length];
                for (int i = 0; i < offsets.length; i++) {
                    if (offsets[i] != 0) {
                        allZero = false;
                    }
                    int value = origin[i] + offsets[i];
                    if (value < 0) {
                        return;
                    }
                    coordinate[i] = value;
                }
                if (allZero) {
                    return;
                }
                Integer neighbour = indexesByCoordinate.get(new Coordinate(coordinate));
                if (neighbour != null) {
                    out.add(neighbour);
                }
                return;
            }
            for (int offset = -1; offset <= 1; offset++) {
                offsets[dimension] = offset;
                collectNeighbours(origin, offsets, dimension + 1, out);
            }
        }
    }

    private static final class Axis {
        final ParameterId id;
        final List<Object> values;

        Axis(ParameterId id, List<Object> values) {
            this.id = id;
            this.values = values;
        }

        int indexOf(Object value) {
            return values.indexOf(value);
        }
    }

    private static final class Coordinate {
        final int[] values;

        Coordinate(int[] values) {
            this.values = values.clone();
        }

        @Override public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof Coordinate)) {
                return false;
            }
            Coordinate other = (Coordinate) obj;
            return Arrays.equals(values, other.values);
        }

        @Override public int hashCode() {
            return Arrays.hashCode(values);
        }
    }
}
