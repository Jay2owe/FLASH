package flash.pipeline.click.training;

import flash.pipeline.image.ImageOps;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.ResultsTable;
import ij.process.ImageProcessor;
import smile.classification.RandomForest;
import smile.data.DataFrame;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ObjectClassifierScorer {
    public static final class ScoreResult {
        public final int label;
        public final double probability;
        public final boolean kept;

        public ScoreResult(int label, double probability, boolean kept) {
            this.label = label;
            this.probability = probability;
            this.kept = kept;
        }
    }

    public List<ScoreResult> score(RandomForest model,
                                   String[] modelFeatureNames,
                                   List<ObjectFeatureExtractor.FeatureRow> rows,
                                   double keepThreshold) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        if (model == null) {
            throw new IllegalArgumentException("model must not be null");
        }
        if (modelFeatureNames == null || modelFeatureNames.length == 0) {
            throw new IllegalArgumentException("modelFeatureNames must not be empty");
        }

        double threshold = Double.isFinite(keepThreshold) ? keepThreshold : 0.5;
        double[][] values = new double[rows.size()][modelFeatureNames.length];
        for (int row = 0; row < rows.size(); row++) {
            ObjectFeatureExtractor.FeatureRow featureRow = rows.get(row);
            for (int col = 0; col < modelFeatureNames.length; col++) {
                values[row][col] = ObjectFeatureExtractor.alignedValue(featureRow, modelFeatureNames[col]);
            }
        }
        DataFrame frame = DataFrame.of(values, modelFeatureNames);

        List<ScoreResult> out = new ArrayList<ScoreResult>();
        for (int row = 0; row < rows.size(); row++) {
            double[] posteriori = new double[2];
            int prediction = model.predict(frame.get(row), posteriori);
            double probability = positiveProbability(prediction, posteriori);
            out.add(new ScoreResult(rows.get(row).label, probability, probability >= threshold));
        }
        return out;
    }

    public ImagePlus filterLabelImage(ImagePlus labelImage, List<ScoreResult> results) {
        ImagePlus filtered = ImageOps.duplicateThreadSafe(labelImage);
        if (filtered == null || filtered.getStack() == null) {
            return filtered;
        }

        Map<Integer, Integer> oldToNew = relabelMap(results);
        ImageStack stack = filtered.getStack();
        for (int slice = 1; slice <= stack.getSize(); slice++) {
            ImageProcessor processor = stack.getProcessor(slice);
            if (processor == null) continue;
            for (int i = 0; i < processor.getPixelCount(); i++) {
                int oldLabel = labelFromPixel(processor.getf(i));
                Integer next = oldToNew.get(Integer.valueOf(oldLabel));
                processor.setf(i, next == null ? 0f : next.floatValue());
            }
        }
        filtered.setTitle(labelImage == null ? "Trained RF labels" : labelImage.getTitle() + " RF-filtered");
        filtered.updateAndDraw();
        return filtered;
    }

    public ResultsTable toResultsTable(List<ScoreResult> results) {
        ResultsTable table = new ResultsTable();
        if (results == null) return table;
        Map<Integer, Integer> oldToNew = relabelMap(results);
        for (int row = 0; row < results.size(); row++) {
            ScoreResult result = results.get(row);
            table.incrementCounter();
            table.setValue("Label", row, result.label);
            table.setValue("Probability", row, result.probability);
            table.setValue("Kept", row, result.kept ? 1 : 0);
            Integer newLabel = oldToNew.get(Integer.valueOf(result.label));
            table.setValue("NewLabel", row, newLabel == null ? 0 : newLabel.intValue());
        }
        return table;
    }

    private static double positiveProbability(int prediction, double[] posteriori) {
        if (posteriori != null && posteriori.length > 1 && Double.isFinite(posteriori[1])) {
            return posteriori[1];
        }
        if (posteriori != null && posteriori.length == 1 && Double.isFinite(posteriori[0])) {
            return prediction == 1 ? posteriori[0] : 1.0 - posteriori[0];
        }
        return prediction == 1 ? 1.0 : 0.0;
    }

    private static Map<Integer, Integer> relabelMap(List<ScoreResult> results) {
        if (results == null || results.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Integer> kept = new ArrayList<Integer>();
        for (ScoreResult result : results) {
            if (result != null && result.kept && result.label > 0) {
                kept.add(Integer.valueOf(result.label));
            }
        }
        Collections.sort(kept);
        Map<Integer, Integer> out = new LinkedHashMap<Integer, Integer>();
        int next = 1;
        for (Integer label : kept) {
            if (!out.containsKey(label)) {
                out.put(label, Integer.valueOf(next++));
            }
        }
        return out;
    }

    private static int labelFromPixel(float value) {
        if (!Float.isFinite(value) || value <= 0f) return 0;
        return value > Integer.MAX_VALUE ? 0 : Math.round(value);
    }
}
