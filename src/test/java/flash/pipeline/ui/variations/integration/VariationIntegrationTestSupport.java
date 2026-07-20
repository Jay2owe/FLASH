package flash.pipeline.ui.variations.integration;

import flash.pipeline.cellpose.Cellpose3DRunner;
import flash.pipeline.objects.ObjectsCounter3DWrapper;
import flash.pipeline.stardist.StarDist3DRunner;
import flash.pipeline.ui.config.CellposeParameterStage;
import flash.pipeline.ui.config.ClassicalSegmentationStage;
import flash.pipeline.ui.config.ConfigQcContext;
import flash.pipeline.ui.config.StarDistParameterStage;
import flash.pipeline.ui.variations.CropSpec;
import flash.pipeline.ui.variations.ParameterId;
import flash.pipeline.ui.variations.ParameterSweep;
import flash.pipeline.ui.variations.ParameterValueList;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.ResultsTable;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class VariationIntegrationTestSupport {

    private static final String BLOB_IMAGE =
            "/flash/pipeline/ui/variations/synthetic-blobs.pgm";

    private VariationIntegrationTestSupport() {
    }

    static ImagePlus loadSyntheticBlobStack() throws IOException {
        ShortProcessor plane = readAsciiPgm(BLOB_IMAGE);
        ImageStack stack = new ImageStack(plane.getWidth(), plane.getHeight());
        for (int z = 0; z < 3; z++) {
            stack.addSlice("z" + (z + 1), plane.duplicate());
        }
        ImagePlus image = new ImagePlus("synthetic blob fixture", stack);
        image.setDimensions(1, 3, 1);
        return image;
    }

    static ParameterSweep classicalNineCellSweep() {
        Map<ParameterId, ParameterValueList> values =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.THRESHOLD, ParameterValueList.ofInts(80, 140, 200));
        values.put(ParameterId.MIN_SIZE, ParameterValueList.ofInts(1, 8, 15));
        values.put(ParameterId.MAX_SIZE, ParameterValueList.ofInts(Integer.MAX_VALUE));
        return new ParameterSweep(ParameterSweep.Method.CLASSICAL, values,
                CropSpec.full(), "DAPI", "synthetic-blobs");
    }

    static ParameterSweep starDistFastNmsSixCellSweep() {
        Map<ParameterId, ParameterValueList> values =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.PROB_THRESH, ParameterValueList.ofDoubles(0.3d, 0.5d, 0.7d));
        values.put(ParameterId.NMS_THRESH, ParameterValueList.ofDoubles(0.3d, 0.5d));
        return new ParameterSweep(ParameterSweep.Method.STARDIST, values,
                CropSpec.full(), "DAPI", "synthetic-blobs");
    }

    static ParameterSweep cellposeThreeCellSweep() {
        Map<ParameterId, ParameterValueList> values =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.DIAMETER, ParameterValueList.ofDoubles(8.0d, 12.0d, 16.0d));
        values.put(ParameterId.FLOW_THRESHOLD, ParameterValueList.ofDoubles(0.4d));
        values.put(ParameterId.CELLPROB_THRESHOLD, ParameterValueList.ofDoubles(-1.0d));
        return new ParameterSweep(ParameterSweep.Method.CELLPOSE, values,
                CropSpec.full(), "DAPI", "synthetic-blobs");
    }

    static StarDistParameterStage.Parameters starDistBaseParameters() {
        return new StarDistParameterStage.Parameters(
                0.5d,
                0.3d,
                5.0d,
                5.0d,
                1,
                0.0d,
                Double.POSITIVE_INFINITY,
                0.0d,
                0.0d);
    }

    static CellposeParameterStage.Parameters cellposeBaseParameters() {
        return new CellposeParameterStage.Parameters(
                "nuclei",
                -1,
                12.0d,
                0.4d,
                -1.0d,
                false);
    }

    static final class ThresholdingPreviewAdapter
            implements ClassicalSegmentationStage.PreviewAdapter {

        @Override public ImagePlus createRawSource(ConfigQcContext context) {
            return null;
        }

        @Override public ImagePlus createFilteredSource(ConfigQcContext context) {
            return null;
        }

        @Override public ObjectsCounter3DWrapper.Result runPreview(ImagePlus filteredSource,
                                                                   int threshold,
                                                                   int minSize,
                                                                   int maxSize) {
            LabelledComponents labelled = labelComponents(filteredSource, threshold,
                    minSize, maxSize);
            return new ObjectsCounter3DWrapper.Result(labelled.statistics,
                    labelled.labelImage, null, labelled.count > 0);
        }

        @Override public int countObjects(ObjectsCounter3DWrapper.Result result) {
            return result == null || result.getStatistics() == null
                    ? 0
                    : result.getStatistics().size();
        }

        @Override public void close(ImagePlus image) {
            if (image != null) {
                image.flush();
            }
        }
    }

    static final class RealStarDistPreviewAdapter
            implements StarDistParameterStage.PreviewAdapter {

        @Override public ImagePlus createRawSource(ConfigQcContext context) {
            return null;
        }

        @Override public ImagePlus createFilteredSource(ConfigQcContext context) {
            return null;
        }

        @Override public ImagePlus runPreview(ImagePlus filteredSource,
                                              StarDistParameterStage.Parameters parameters) {
            return StarDist3DRunner.run(filteredSource,
                    parameters.probabilityThreshold,
                    parameters.nmsThreshold,
                    "DAPI",
                    parameters.linkingMaxDistance,
                    parameters.gapClosingMaxDistance,
                    parameters.maxFrameGap,
                    parameters.areaMin,
                    parameters.areaMax,
                    parameters.qualityMin,
                    parameters.intensityMin);
        }

        @Override public int countLabels(ImagePlus labelImage) {
            return labelImage == null ? 0 : StarDist3DRunner.countLabels(labelImage);
        }

        @Override public void close(ImagePlus image) {
            if (image != null) {
                image.flush();
            }
        }
    }

    static final class RealCellposePreviewAdapter
            implements CellposeParameterStage.PreviewAdapter {

        @Override public ImagePlus createRawSource(ConfigQcContext context) {
            return null;
        }

        @Override public ImagePlus createFilteredSource(ConfigQcContext context) {
            return null;
        }

        @Override public ImagePlus createFilteredCompanionSource(ConfigQcContext context,
                                                                 int channelIndex) {
            return null;
        }

        @Override public ImagePlus runPreview(ImagePlus filteredSource,
                                              ImagePlus filteredCompanionSource,
                                              CellposeParameterStage.Parameters parameters) {
            return Cellpose3DRunner.run(filteredSource,
                    filteredCompanionSource,
                    parameters.modelToken,
                    parameters.diameter,
                    parameters.flowThreshold,
                    parameters.cellprobThreshold,
                    parameters.useGpu,
                    "DAPI");
        }

        @Override public int countLabels(ImagePlus labelImage) {
            return labelImage == null ? 0 : Cellpose3DRunner.countLabels(labelImage);
        }

        @Override public void close(ImagePlus image) {
            if (image != null) {
                image.flush();
            }
        }
    }

    private static ShortProcessor readAsciiPgm(String resourcePath) throws IOException {
        List<String> tokens = pgmTokens(resourcePath);
        if (tokens.size() < 4 || !"P2".equals(tokens.get(0))) {
            throw new IOException("Expected ASCII PGM fixture at " + resourcePath);
        }
        int width = Integer.parseInt(tokens.get(1));
        int height = Integer.parseInt(tokens.get(2));
        int max = Integer.parseInt(tokens.get(3));
        if (width <= 0 || height <= 0 || max <= 0) {
            throw new IOException("Invalid PGM header for " + resourcePath);
        }
        int expected = width * height;
        if (tokens.size() - 4 < expected) {
            throw new IOException("PGM fixture has too few pixels: " + resourcePath);
        }
        ShortProcessor processor = new ShortProcessor(width, height);
        for (int i = 0; i < expected; i++) {
            processor.set(i, Integer.parseInt(tokens.get(i + 4)));
        }
        return processor;
    }

    private static List<String> pgmTokens(String resourcePath) throws IOException {
        InputStream stream = VariationIntegrationTestSupport.class
                .getResourceAsStream(resourcePath);
        if (stream == null) {
            throw new IOException("Missing test image fixture: " + resourcePath);
        }
        List<String> tokens = new ArrayList<String>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                stream, StandardCharsets.US_ASCII));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                int comment = line.indexOf('#');
                String content = comment >= 0 ? line.substring(0, comment) : line;
                String[] parts = content.trim().split("\\s+");
                for (int i = 0; i < parts.length; i++) {
                    if (!parts[i].isEmpty()) {
                        tokens.add(parts[i]);
                    }
                }
            }
        } finally {
            reader.close();
        }
        return tokens;
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
