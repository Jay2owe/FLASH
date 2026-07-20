package flash.pipeline.ui.variations.strategy;

import flash.pipeline.objects.ObjectsCounter3DWrapper;
import flash.pipeline.ui.config.ClassicalSegmentationStage;
import flash.pipeline.ui.config.ConfigQcContext;
import flash.pipeline.ui.variations.CropSpec;
import flash.pipeline.ui.variations.ParameterCombo;
import flash.pipeline.ui.variations.ParameterId;
import flash.pipeline.ui.variations.ParameterSweep;
import flash.pipeline.ui.variations.ParameterValueList;
import flash.pipeline.ui.variations.VariationResult;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.ResultsTable;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ClassicalSweepTest {

    @Test
    public void thresholdSweepFindsMoreObjectsAtLowThresholdThanHighThreshold()
            throws Exception {
        ImagePlus source = syntheticBlobs();
        ClassicalSweep strategy = new ClassicalSweep(source, CropSpec.full(), null,
                new ThresholdingPreviewAdapter(), 2);
        List<VariationResult> results =
                Collections.synchronizedList(new ArrayList<VariationResult>());

        strategy.dispatch(classicalSweep(ParameterValueList.ofInts(60, 120, 180),
                        ParameterValueList.ofInts(1)),
                results::add,
                () -> false);

        assertEquals(3, results.size());
        Map<Integer, Integer> countsByThreshold = countsByThreshold(results);
        assertTrue(countsByThreshold.get(Integer.valueOf(60)).intValue()
                > countsByThreshold.get(Integer.valueOf(180)).intValue());
        assertEquals(Integer.valueOf(3), countsByThreshold.get(Integer.valueOf(60)));
        assertEquals(Integer.valueOf(1), countsByThreshold.get(Integer.valueOf(180)));
    }

    @Test
    public void fourCellSweepCompletesHeadlessAndReturnsLabels() throws Exception {
        ImagePlus source = syntheticBlobs();
        ClassicalSweep strategy = new ClassicalSweep(source, CropSpec.full(), null,
                new ThresholdingPreviewAdapter(), 2);
        List<VariationResult> results =
                Collections.synchronizedList(new ArrayList<VariationResult>());

        strategy.dispatch(classicalSweep(ParameterValueList.ofInts(60, 180),
                        ParameterValueList.ofInts(1, 500)),
                results::add,
                () -> false);

        assertEquals(4, results.size());
        for (int i = 0; i < results.size(); i++) {
            assertNotNull(results.get(i).getLabel());
        }
    }

    private static ParameterSweep classicalSweep(ParameterValueList thresholds,
                                                 ParameterValueList minSizes) {
        Map<ParameterId, ParameterValueList> values =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.THRESHOLD, thresholds);
        values.put(ParameterId.MIN_SIZE, minSizes);
        values.put(ParameterId.MAX_SIZE, ParameterValueList.ofInts(Integer.MAX_VALUE));
        return new ParameterSweep(ParameterSweep.Method.CLASSICAL,
                values, CropSpec.full(), "DAPI", "synthetic");
    }

    private static Map<Integer, Integer> countsByThreshold(List<VariationResult> results) {
        Map<Integer, Integer> counts = new LinkedHashMap<Integer, Integer>();
        for (int i = 0; i < results.size(); i++) {
            VariationResult result = results.get(i);
            counts.put((Integer) result.combo().get(ParameterId.THRESHOLD),
                    Integer.valueOf(result.nObjects()));
        }
        return counts;
    }

    private static ImagePlus syntheticBlobs() {
        ImageStack stack = new ImageStack(256, 256);
        for (int z = 0; z < 3; z++) {
            stack.addSlice("z" + (z + 1), new ShortProcessor(256, 256));
        }
        paintCube(stack, 24, 24, 10, 70);
        paintCube(stack, 96, 96, 10, 140);
        paintCube(stack, 168, 168, 10, 220);
        ImagePlus image = new ImagePlus("synthetic blobs", stack);
        image.setDimensions(1, 3, 1);
        return image;
    }

    private static void paintCube(ImageStack stack, int x0, int y0, int size, int value) {
        for (int z = 1; z <= stack.getSize(); z++) {
            ImageProcessor processor = stack.getProcessor(z);
            for (int y = y0; y < y0 + size; y++) {
                for (int x = x0; x < x0 + size; x++) {
                    processor.set(x, y, value);
                }
            }
        }
    }

    private static final class ThresholdingPreviewAdapter
            implements ClassicalSegmentationStage.PreviewAdapter {

        @Override
        public ImagePlus createRawSource(ConfigQcContext context) {
            return null;
        }

        @Override
        public ImagePlus createFilteredSource(ConfigQcContext context) {
            return null;
        }

        @Override
        public ObjectsCounter3DWrapper.Result runPreview(ImagePlus filteredSource,
                                                         int threshold,
                                                         int minSize,
                                                         int maxSize) {
            LabelledComponents labelled = labelComponents(filteredSource, threshold,
                    minSize, maxSize);
            return new ObjectsCounter3DWrapper.Result(labelled.statistics,
                    labelled.labelImage, null, labelled.count > 0);
        }

        @Override
        public int countObjects(ObjectsCounter3DWrapper.Result result) {
            return result == null || result.getStatistics() == null
                    ? 0
                    : result.getStatistics().size();
        }

        @Override
        public void close(ImagePlus image) {
            if (image != null) {
                image.flush();
            }
        }

        private static LabelledComponents labelComponents(ImagePlus source,
                                                          int threshold,
                                                          int minSize,
                                                          int maxSize) {
            int width = source.getWidth();
            int height = source.getHeight();
            int depth = source.getStackSize();
            int plane = width * height;
            boolean[] visited = new boolean[plane * depth];
            int[] queue = new int[visited.length];
            int[] component = new int[visited.length];
            ImageStack labels = new ImageStack(width, height);
            ShortProcessor[] labelProcessors = new ShortProcessor[depth];
            for (int z = 0; z < depth; z++) {
                labelProcessors[z] = new ShortProcessor(width, height);
                labels.addSlice("z" + (z + 1), labelProcessors[z]);
            }
            ResultsTable statistics = new ResultsTable();
            int label = 0;

            for (int z = 0; z < depth; z++) {
                ImageProcessor sourceProcessor = source.getStack().getProcessor(z + 1);
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int start = flat(x, y, z, width, height);
                        if (visited[start] || sourceProcessor.get(x, y) < threshold) {
                            continue;
                        }
                        int size = floodFill(source, threshold, width, height, depth,
                                start, visited, queue, component);
                        if (size < minSize || size > maxSize) {
                            continue;
                        }
                        label++;
                        for (int i = 0; i < size; i++) {
                            int index = component[i];
                            int componentZ = index / plane;
                            int withinPlane = index % plane;
                            int componentY = withinPlane / width;
                            int componentX = withinPlane % width;
                            labelProcessors[componentZ].set(componentX, componentY, label);
                        }
                        statistics.incrementCounter();
                        int row = statistics.size() - 1;
                        statistics.setValue("Label", row, label);
                        statistics.setValue("Volume (pixel^3)", row, size);
                    }
                }
            }
            ImagePlus labelImage = new ImagePlus("labels", labels);
            labelImage.setDimensions(1, depth, 1);
            return new LabelledComponents(labelImage, statistics, label);
        }

        private static int floodFill(ImagePlus source,
                                     int threshold,
                                     int width,
                                     int height,
                                     int depth,
                                     int start,
                                     boolean[] visited,
                                     int[] queue,
                                     int[] component) {
            int head = 0;
            int tail = 0;
            int size = 0;
            queue[tail++] = start;
            visited[start] = true;
            while (head < tail) {
                int index = queue[head++];
                component[size++] = index;
                int plane = width * height;
                int z = index / plane;
                int withinPlane = index % plane;
                int y = withinPlane / width;
                int x = withinPlane % width;
                tail = enqueueIfForeground(source, threshold, width, height, depth,
                        x - 1, y, z, visited, queue, tail);
                tail = enqueueIfForeground(source, threshold, width, height, depth,
                        x + 1, y, z, visited, queue, tail);
                tail = enqueueIfForeground(source, threshold, width, height, depth,
                        x, y - 1, z, visited, queue, tail);
                tail = enqueueIfForeground(source, threshold, width, height, depth,
                        x, y + 1, z, visited, queue, tail);
                tail = enqueueIfForeground(source, threshold, width, height, depth,
                        x, y, z - 1, visited, queue, tail);
                tail = enqueueIfForeground(source, threshold, width, height, depth,
                        x, y, z + 1, visited, queue, tail);
            }
            return size;
        }

        private static int enqueueIfForeground(ImagePlus source,
                                               int threshold,
                                               int width,
                                               int height,
                                               int depth,
                                               int x,
                                               int y,
                                               int z,
                                               boolean[] visited,
                                               int[] queue,
                                               int tail) {
            if (x < 0 || y < 0 || z < 0 || x >= width || y >= height || z >= depth) {
                return tail;
            }
            int index = flat(x, y, z, width, height);
            if (visited[index]) {
                return tail;
            }
            ImageProcessor processor = source.getStack().getProcessor(z + 1);
            if (processor.get(x, y) < threshold) {
                return tail;
            }
            visited[index] = true;
            queue[tail++] = index;
            return tail;
        }

        private static int flat(int x, int y, int z, int width, int height) {
            return z * width * height + y * width + x;
        }
    }

    private static final class LabelledComponents {
        final ImagePlus labelImage;
        final ResultsTable statistics;
        final int count;

        LabelledComponents(ImagePlus labelImage, ResultsTable statistics, int count) {
            this.labelImage = labelImage;
            this.statistics = statistics;
            this.count = count;
        }
    }
}
