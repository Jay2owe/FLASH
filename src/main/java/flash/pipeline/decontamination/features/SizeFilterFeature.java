package flash.pipeline.decontamination.features;

import flash.pipeline.decontamination.CorrectionFeature;
import flash.pipeline.decontamination.CorrectionImageOps;
import flash.pipeline.decontamination.CorrectionPipeline;
import ij.ImagePlus;

import java.util.Collections;
import java.util.Set;

/**
 * Removes connected mask components outside the configured voxel-size range.
 */
public class SizeFilterFeature implements CorrectionPipeline.ExecutableFeature {

    public static final String ID = "size_filter";

    private static final String MIN_SIZE_VOXELS = "min_size_voxels";
    private static final String MAX_SIZE_VOXELS = "max_size_voxels";

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "Size filter";
    }

    @Override
    public String getDescription() {
        return "Removes very small or very large mask components.";
    }

    @Override
    public InputType getRequiredInputType() {
        return InputType.MASK;
    }

    @Override
    public OutputType getOutputType() {
        return OutputType.MASK;
    }

    @Override
    public Set<RequiredChannel> getRequiredChannels() {
        return Collections.emptySet();
    }

    @Override
    public boolean requiresConditions() {
        return false;
    }

    @Override
    public boolean requiresControls() {
        return false;
    }

    @Override
    public boolean requiresExistingObjectMaps() {
        return false;
    }

    @Override
    public boolean canPreviewCheaply() {
        return true;
    }

    @Override
    public boolean isExpertOnly() {
        return false;
    }

    @Override
    public boolean isThresholdFeature() {
        return false;
    }

    @Override
    public boolean requiresVetoMask() {
        return false;
    }

    @Override
    public void apply(CorrectionPipeline.ExecutionState state) {
        if (state == null) {
            throw new IllegalArgumentException("Execution state is required.");
        }

        ImagePlus mask = state.getMaskImage();
        CorrectionImageOps.requireSingleChannelMask(mask, "Mask");
        Settings settings = Settings.from(state.getFeatureSettings(ID));

        int minSize = Math.max(1, settings.getMinSizeVoxels());
        int maxSize = settings.getMaxSizeVoxels() <= 0 ? Integer.MAX_VALUE : settings.getMaxSizeVoxels();
        int width = mask.getWidth();
        int height = mask.getHeight();
        int slices = Math.max(1, mask.getNSlices());
        int frames = Math.max(1, mask.getNFrames());
        int planeSize = width * height;
        int planeCount = CorrectionImageOps.planeCount(mask);
        byte[][] outputPlanes = new byte[planeCount][];
        for (int plane = 0; plane < planeCount; plane++) {
            outputPlanes[plane] = new byte[planeSize];
        }

        int keptComponents = 0;
        int removedComponents = 0;
        long keptVoxels = 0L;
        long removedVoxels = 0L;

        for (int frame = 0; frame < frames; frame++) {
            boolean[] visited = new boolean[planeSize * slices];
            IntList queue = new IntList();
            IntList component = new IntList();

            for (int slice = 0; slice < slices; slice++) {
                byte[] slicePixels = CorrectionImageOps.singleChannelMaskPlanePixels(
                        mask,
                        frame * slices + slice);
                for (int pixel = 0; pixel < planeSize; pixel++) {
                    if ((slicePixels[pixel] & 0xff) == 0) {
                        continue;
                    }

                    int startIndex = slice * planeSize + pixel;
                    if (visited[startIndex]) {
                        continue;
                    }

                    visited[startIndex] = true;
                    queue.clear();
                    component.clear();
                    queue.add(startIndex);

                    int queueIndex = 0;
                    while (queueIndex < queue.size()) {
                        int current = queue.get(queueIndex++);
                        component.add(current);

                        int currentSlice = current / planeSize;
                        int sliceOffset = current - (currentSlice * planeSize);
                        int y = sliceOffset / width;
                        int x = sliceOffset - (y * width);

                        for (int dz = -1; dz <= 1; dz++) {
                            int neighborSlice = currentSlice + dz;
                            if (neighborSlice < 0 || neighborSlice >= slices) {
                                continue;
                            }
                            byte[] neighborPixels = CorrectionImageOps.singleChannelMaskPlanePixels(
                                    mask,
                                    frame * slices + neighborSlice);
                            for (int dy = -1; dy <= 1; dy++) {
                                int ny = y + dy;
                                if (ny < 0 || ny >= height) {
                                    continue;
                                }
                                for (int dx = -1; dx <= 1; dx++) {
                                    int nx = x + dx;
                                    if (nx < 0 || nx >= width) {
                                        continue;
                                    }
                                    if (dx == 0 && dy == 0 && dz == 0) {
                                        continue;
                                    }
                                    int neighborPixel = ny * width + nx;
                                    int neighborIndex = neighborSlice * planeSize + neighborPixel;
                                    if (visited[neighborIndex]) {
                                        continue;
                                    }
                                    if ((neighborPixels[neighborPixel] & 0xff) == 0) {
                                        continue;
                                    }
                                    visited[neighborIndex] = true;
                                    queue.add(neighborIndex);
                                }
                            }
                        }
                    }

                    boolean keep = component.size() >= minSize && component.size() <= maxSize;
                    if (keep) {
                        keptComponents++;
                    } else {
                        removedComponents++;
                    }

                    for (int i = 0; i < component.size(); i++) {
                        int voxel = component.get(i);
                        int voxelSlice = voxel / planeSize;
                        int voxelPixel = voxel - (voxelSlice * planeSize);
                        if (keep) {
                            outputPlanes[frame * slices + voxelSlice][voxelPixel] = (byte) 255;
                            keptVoxels++;
                        } else {
                            removedVoxels++;
                        }
                    }
                }
            }
        }

        state.setMaskImage(CorrectionImageOps.createMaskImageLike(
                mask,
                "size_filtered_mask",
                outputPlanes));

        state.addSummary(new CorrectionPipeline.FeatureSummary(ID, getDisplayName())
                .putInt("min_size_voxels", minSize)
                .putInt("max_size_voxels", maxSize == Integer.MAX_VALUE ? 0 : maxSize)
                .putInt("kept_components", keptComponents)
                .putInt("removed_components", removedComponents)
                .putInt("kept_voxels", keptVoxels)
                .putInt("removed_voxels", removedVoxels));
    }

    public static final class Settings {
        private int minSizeVoxels = 1;
        private int maxSizeVoxels = 0;

        public int getMinSizeVoxels() {
            return minSizeVoxels;
        }

        public Settings setMinSizeVoxels(int minSizeVoxels) {
            this.minSizeVoxels = minSizeVoxels;
            return this;
        }

        public int getMaxSizeVoxels() {
            return maxSizeVoxels;
        }

        public Settings setMaxSizeVoxels(int maxSizeVoxels) {
            this.maxSizeVoxels = maxSizeVoxels;
            return this;
        }

        public CorrectionPipeline.Settings toPipelineSettings() {
            return new CorrectionPipeline.Settings()
                    .putInt(MIN_SIZE_VOXELS, minSizeVoxels)
                    .putInt(MAX_SIZE_VOXELS, maxSizeVoxels);
        }

        public static Settings from(CorrectionPipeline.Settings raw) {
            Settings settings = new Settings();
            if (raw == null) {
                return settings;
            }
            settings.setMinSizeVoxels(raw.getInt(MIN_SIZE_VOXELS, settings.minSizeVoxels));
            settings.setMaxSizeVoxels(raw.getInt(MAX_SIZE_VOXELS, settings.maxSizeVoxels));
            return settings;
        }
    }

    private static final class IntList {
        private int[] values = new int[256];
        private int size = 0;

        void clear() {
            size = 0;
        }

        void add(int value) {
            if (size >= values.length) {
                int[] expanded = new int[values.length * 2];
                System.arraycopy(values, 0, expanded, 0, values.length);
                values = expanded;
            }
            values[size++] = value;
        }

        int get(int index) {
            return values[index];
        }

        int size() {
            return size;
        }
    }
}
