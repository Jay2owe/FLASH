package flash.pipeline.decontamination;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reproducible operating point selected by ROC threshold search.
 */
public final class RocSearchResult {

    private final String metricKey;
    private final String metricLabel;
    private final String searchScope;
    private final String message;
    private final double falsePositiveLimit;
    private final double thresholdMin;
    private final double thresholdMax;
    private final double thresholdStep;
    private final double selectedThreshold;
    private final double selectedFalsePositiveRate;
    private final double selectedExperimentalRetention;
    private final double selectedExperimentalMeanMetric;
    private final int selectedControlPositiveCount;
    private final int selectedExperimentalPositiveCount;
    private final int controlImageCount;
    private final int experimentalImageCount;
    private final int selectedGridIndex;
    private final List<GridPoint> gridPoints;

    public RocSearchResult(String metricKey,
                           String metricLabel,
                           String searchScope,
                           String message,
                           double falsePositiveLimit,
                           double thresholdMin,
                           double thresholdMax,
                           double thresholdStep,
                           double selectedThreshold,
                           double selectedFalsePositiveRate,
                           double selectedExperimentalRetention,
                           double selectedExperimentalMeanMetric,
                           int selectedControlPositiveCount,
                           int selectedExperimentalPositiveCount,
                           int controlImageCount,
                           int experimentalImageCount,
                           int selectedGridIndex,
                           List<GridPoint> gridPoints) {
        this.metricKey = clean(metricKey);
        this.metricLabel = clean(metricLabel);
        this.searchScope = clean(searchScope);
        this.message = clean(message);
        this.falsePositiveLimit = falsePositiveLimit;
        this.thresholdMin = thresholdMin;
        this.thresholdMax = thresholdMax;
        this.thresholdStep = thresholdStep;
        this.selectedThreshold = selectedThreshold;
        this.selectedFalsePositiveRate = selectedFalsePositiveRate;
        this.selectedExperimentalRetention = selectedExperimentalRetention;
        this.selectedExperimentalMeanMetric = selectedExperimentalMeanMetric;
        this.selectedControlPositiveCount = selectedControlPositiveCount;
        this.selectedExperimentalPositiveCount = selectedExperimentalPositiveCount;
        this.controlImageCount = controlImageCount;
        this.experimentalImageCount = experimentalImageCount;
        this.selectedGridIndex = selectedGridIndex;
        this.gridPoints = gridPoints == null
                ? new ArrayList<GridPoint>()
                : new ArrayList<GridPoint>(gridPoints);
    }

    public String getMetricKey() {
        return metricKey;
    }

    public String getMetricLabel() {
        return metricLabel;
    }

    public String getSearchScope() {
        return searchScope;
    }

    public String getMessage() {
        return message;
    }

    public double getFalsePositiveLimit() {
        return falsePositiveLimit;
    }

    public double getThresholdMin() {
        return thresholdMin;
    }

    public double getThresholdMax() {
        return thresholdMax;
    }

    public double getThresholdStep() {
        return thresholdStep;
    }

    public double getSelectedThreshold() {
        return selectedThreshold;
    }

    public double getSelectedFalsePositiveRate() {
        return selectedFalsePositiveRate;
    }

    public double getSelectedExperimentalRetention() {
        return selectedExperimentalRetention;
    }

    public double getSelectedExperimentalMeanMetric() {
        return selectedExperimentalMeanMetric;
    }

    public int getSelectedControlPositiveCount() {
        return selectedControlPositiveCount;
    }

    public int getSelectedExperimentalPositiveCount() {
        return selectedExperimentalPositiveCount;
    }

    public int getControlImageCount() {
        return controlImageCount;
    }

    public int getExperimentalImageCount() {
        return experimentalImageCount;
    }

    public int getSelectedGridIndex() {
        return selectedGridIndex;
    }

    public int getGridPointCount() {
        return gridPoints.size();
    }

    public List<GridPoint> getGridPoints() {
        return Collections.unmodifiableList(gridPoints);
    }

    public boolean hasSelectedThreshold() {
        return !Double.isNaN(selectedThreshold) && !Double.isInfinite(selectedThreshold);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    public static final class GridPoint {
        private final double threshold;
        private final double controlFalsePositiveRate;
        private final double experimentalRetention;
        private final double experimentalMeanMetric;
        private final int controlPositiveCount;
        private final int experimentalPositiveCount;
        private final int controlImageCount;
        private final int experimentalImageCount;

        public GridPoint(double threshold,
                         double controlFalsePositiveRate,
                         double experimentalRetention,
                         double experimentalMeanMetric,
                         int controlPositiveCount,
                         int experimentalPositiveCount,
                         int controlImageCount,
                         int experimentalImageCount) {
            this.threshold = threshold;
            this.controlFalsePositiveRate = controlFalsePositiveRate;
            this.experimentalRetention = experimentalRetention;
            this.experimentalMeanMetric = experimentalMeanMetric;
            this.controlPositiveCount = controlPositiveCount;
            this.experimentalPositiveCount = experimentalPositiveCount;
            this.controlImageCount = controlImageCount;
            this.experimentalImageCount = experimentalImageCount;
        }

        public double getThreshold() {
            return threshold;
        }

        public double getControlFalsePositiveRate() {
            return controlFalsePositiveRate;
        }

        public double getExperimentalRetention() {
            return experimentalRetention;
        }

        public double getExperimentalMeanMetric() {
            return experimentalMeanMetric;
        }

        public int getControlPositiveCount() {
            return controlPositiveCount;
        }

        public int getExperimentalPositiveCount() {
            return experimentalPositiveCount;
        }

        public int getControlImageCount() {
            return controlImageCount;
        }

        public int getExperimentalImageCount() {
            return experimentalImageCount;
        }
    }
}
