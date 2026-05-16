package flash.pipeline.segmentation;

import flash.pipeline.click.training.ObjectClassifierTrainer;
import flash.pipeline.click.training.ObjectFeatureExtractor;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class TrainedRfRunnerTestSupport {
    private TrainedRfRunnerTestSupport() {
    }

    static ObjectClassifierTrainer.TrainingResult trainKeepingHighValues(String featureName) {
        return new ObjectClassifierTrainer().train(
                rows(featureName, 30, true),
                rows(featureName, 30, false),
                23);
    }

    static ImagePlus fiveLabelImage() {
        int width = 20;
        ByteProcessor bp = new ByteProcessor(width, 1);
        int x = 0;
        for (int label = 1; label <= 5; label++) {
            for (int i = 0; i < label; i++) {
                bp.set(x++, 0, label);
            }
            x++;
        }
        ImageStack stack = new ImageStack(width, 1);
        stack.addSlice(bp);
        return new ImagePlus("base-labels", stack);
    }

    static ImagePlus rawLike(ImagePlus labels) {
        FloatProcessor fp = new FloatProcessor(labels.getWidth(), labels.getHeight());
        for (int i = 0; i < fp.getPixelCount(); i++) {
            fp.setf(i, 100.0f);
        }
        ImageStack stack = new ImageStack(labels.getWidth(), labels.getHeight());
        stack.addSlice(fp);
        return new ImagePlus("raw", stack);
    }

    static ImagePlus valueImageByLabel(ImagePlus labels) {
        FloatProcessor fp = new FloatProcessor(labels.getWidth(), labels.getHeight());
        ImageProcessor labelProcessor = labels.getProcessor();
        for (int i = 0; i < fp.getPixelCount(); i++) {
            fp.setf(i, labelProcessor.getf(i));
        }
        ImageStack stack = new ImageStack(labels.getWidth(), labels.getHeight());
        stack.addSlice(fp);
        return new ImagePlus("values", stack);
    }

    static Set<Integer> labelsIn(ImagePlus image) {
        Set<Integer> out = new HashSet<Integer>();
        ImageProcessor ip = image.getProcessor();
        for (int i = 0; i < ip.getPixelCount(); i++) {
            int label = Math.round(ip.getf(i));
            if (label > 0) out.add(Integer.valueOf(label));
        }
        return out;
    }

    private static List<ObjectFeatureExtractor.FeatureRow> rows(
            String featureName,
            int count,
            boolean positive) {
        List<ObjectFeatureExtractor.FeatureRow> rows = new ArrayList<ObjectFeatureExtractor.FeatureRow>();
        for (int i = 0; i < count; i++) {
            double value = positive ? 3 + (i % 5) : 1 + (i % 2);
            rows.add(new ObjectFeatureExtractor.FeatureRow(
                    i + 1,
                    new double[] {value},
                    new String[] {featureName}));
        }
        return rows;
    }
}
