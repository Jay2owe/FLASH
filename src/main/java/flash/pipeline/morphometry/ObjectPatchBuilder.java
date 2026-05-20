package flash.pipeline.morphometry;

import ij.ImagePlus;
import ij.measure.Calibration;
import ij.process.ImageProcessor;
import mcib3d.geom2.BoundingBox;
import mcib3d.geom2.Object3DInt;
import mcib3d.geom2.Object3DPlane;
import mcib3d.geom2.VoxelInt;

/**
 * Builds 2D object texture patches from mcib3d objects and ImageJ stacks.
 */
public final class ObjectPatchBuilder {

    private ObjectPatchBuilder() {
    }

    public static ObjectPatch buildMIP(Object3DInt obj, ImagePlus labelImage, ImagePlus rawStack) {
        Inputs inputs = validateInputs(obj, labelImage, rawStack);
        Crop crop = crop(inputs.box, labelImage.getWidth(), labelImage.getHeight());
        int labelSlices = labelImage.getStack().getSize();
        int rawSlices = rawStack.getStack().getSize();
        int zMin = clamp(inputs.box.zmin, 0, labelSlices - 1);
        int zMax = clamp(inputs.box.zmax, 0, labelSlices - 1);
        if (zMax < zMin) {
            zMax = zMin;
        }

        float[] intensity = new float[crop.width * crop.height];
        byte[] mask = new byte[intensity.length];
        for (int i = 0; i < intensity.length; i++) {
            intensity[i] = -Float.MAX_VALUE;
        }

        for (int z = zMin; z <= zMax; z++) {
            ImageProcessor labelIp = labelImage.getStack().getProcessor(z + 1);
            ImageProcessor rawIp = rawStack.getStack().getProcessor(clamp(z, 0, rawSlices - 1) + 1);
            for (int y = crop.yMin; y <= crop.yMax; y++) {
                for (int x = crop.xMin; x <= crop.xMax; x++) {
                    int idx = crop.index(x, y);
                    float raw = rawIp.getf(x, y);
                    if (raw > intensity[idx]) {
                        intensity[idx] = raw;
                    }
                    if ((int) Math.round(labelIp.getf(x, y)) == inputs.label) {
                        mask[idx] = 1;
                    }
                }
            }
        }

        replaceUnvisitedIntensity(intensity);
        return new ObjectPatch(intensity, mask, crop.width, crop.height,
                pixelSizeMicrons(rawStack, labelImage));
    }

    public static ObjectPatch buildSlice(Object3DInt obj,
                                         ImagePlus labelImage,
                                         ImagePlus rawStack,
                                         int sliceIndex) {
        Inputs inputs = validateInputs(obj, labelImage, rawStack);
        Crop crop = crop(inputs.box, labelImage.getWidth(), labelImage.getHeight());
        int labelSlices = labelImage.getStack().getSize();
        int rawSlices = rawStack.getStack().getSize();
        int z = clamp(sliceIndex, 0, labelSlices - 1);
        ImageProcessor labelIp = labelImage.getStack().getProcessor(z + 1);
        ImageProcessor rawIp = rawStack.getStack().getProcessor(clamp(sliceIndex, 0, rawSlices - 1) + 1);

        float[] intensity = new float[crop.width * crop.height];
        byte[] mask = new byte[intensity.length];
        for (int y = crop.yMin; y <= crop.yMax; y++) {
            for (int x = crop.xMin; x <= crop.xMax; x++) {
                int idx = crop.index(x, y);
                intensity[idx] = rawIp.getf(x, y);
                if ((int) Math.round(labelIp.getf(x, y)) == inputs.label) {
                    mask[idx] = 1;
                }
            }
        }

        return new ObjectPatch(intensity, mask, crop.width, crop.height,
                pixelSizeMicrons(rawStack, labelImage));
    }

    public static ObjectPatch3D buildVolumetric(Object3DInt obj, ImagePlus rawStack) {
        if (obj == null) {
            throw new IllegalArgumentException("obj must not be null");
        }
        if (rawStack == null || rawStack.getStack() == null || rawStack.getStack().getSize() <= 0) {
            throw new IllegalArgumentException("rawStack must contain at least one slice");
        }
        BoundingBox box = obj.getBoundingBox();
        if (box == null) {
            throw new IllegalArgumentException("object bounding box must not be null");
        }
        int objectDepth = Math.max(1, box.zmax - box.zmin + 1);
        if (objectDepth < 2) {
            return null;
        }

        Crop3D crop = crop3D(box, rawStack.getWidth(), rawStack.getHeight(), rawStack.getStack().getSize());
        int slice = crop.width * crop.height;
        float[] intensity = new float[slice * crop.depth];
        byte[] mask = new byte[intensity.length];
        for (int z = crop.zMin; z <= crop.zMax; z++) {
            ImageProcessor rawIp = rawStack.getStack().getProcessor(z + 1);
            for (int y = crop.yMin; y <= crop.yMax; y++) {
                for (int x = crop.xMin; x <= crop.xMax; x++) {
                    intensity[crop.index(x, y, z)] = rawIp.getf(x, y);
                }
            }
        }

        for (Object3DPlane plane : obj.getObject3DPlanes()) {
            if (plane == null) continue;
            for (VoxelInt voxel : plane.getVoxels()) {
                if (voxel == null) continue;
                int x = voxel.getX();
                int y = voxel.getY();
                int z = voxel.getZ();
                if (x < crop.xMin || x > crop.xMax
                        || y < crop.yMin || y > crop.yMax
                        || z < crop.zMin || z > crop.zMax) {
                    continue;
                }
                mask[crop.index(x, y, z)] = 1;
            }
        }

        return new ObjectPatch3D(intensity, mask, crop.width, crop.height, crop.depth,
                pixelSizeMicrons(rawStack, rawStack), sliceSpacingMicrons(rawStack));
    }

    public static int zMin(Object3DInt obj, ImagePlus labelImage) {
        Inputs inputs = validateLabelInputs(obj, labelImage);
        return clamp(inputs.box.zmin, 0, labelImage.getStack().getSize() - 1);
    }

    public static int zMax(Object3DInt obj, ImagePlus labelImage) {
        Inputs inputs = validateLabelInputs(obj, labelImage);
        return clamp(inputs.box.zmax, 0, labelImage.getStack().getSize() - 1);
    }

    private static Inputs validateInputs(Object3DInt obj, ImagePlus labelImage, ImagePlus rawStack) {
        Inputs inputs = validateLabelInputs(obj, labelImage);
        if (rawStack == null || rawStack.getStack() == null || rawStack.getStack().getSize() <= 0) {
            throw new IllegalArgumentException("rawStack must contain at least one slice");
        }
        if (rawStack.getWidth() < labelImage.getWidth() || rawStack.getHeight() < labelImage.getHeight()) {
            throw new IllegalArgumentException("rawStack dimensions must cover labelImage dimensions");
        }
        return inputs;
    }

    private static Inputs validateLabelInputs(Object3DInt obj, ImagePlus labelImage) {
        if (obj == null) {
            throw new IllegalArgumentException("obj must not be null");
        }
        if (labelImage == null || labelImage.getStack() == null || labelImage.getStack().getSize() <= 0) {
            throw new IllegalArgumentException("labelImage must contain at least one slice");
        }
        BoundingBox box = obj.getBoundingBox();
        if (box == null) {
            throw new IllegalArgumentException("object bounding box must not be null");
        }
        return new Inputs(box, (int) obj.getLabel());
    }

    private static Crop crop(BoundingBox box, int imageWidth, int imageHeight) {
        int objectWidth = Math.max(1, box.xmax - box.xmin + 1);
        int objectHeight = Math.max(1, box.ymax - box.ymin + 1);
        int pad = (int) Math.ceil(Math.max(objectWidth, objectHeight) / 4.0);
        int xMin = clamp(box.xmin - pad, 0, imageWidth - 1);
        int xMax = clamp(box.xmax + pad, 0, imageWidth - 1);
        int yMin = clamp(box.ymin - pad, 0, imageHeight - 1);
        int yMax = clamp(box.ymax + pad, 0, imageHeight - 1);
        return new Crop(xMin, xMax, yMin, yMax);
    }

    private static Crop3D crop3D(BoundingBox box, int imageWidth, int imageHeight, int imageDepth) {
        int objectWidth = Math.max(1, box.xmax - box.xmin + 1);
        int objectHeight = Math.max(1, box.ymax - box.ymin + 1);
        int objectDepth = Math.max(1, box.zmax - box.zmin + 1);
        int pad = (int) Math.ceil(Math.max(objectWidth, Math.max(objectHeight, objectDepth)) / 4.0);
        int xMin = clamp(box.xmin - pad, 0, imageWidth - 1);
        int xMax = clamp(box.xmax + pad, 0, imageWidth - 1);
        int yMin = clamp(box.ymin - pad, 0, imageHeight - 1);
        int yMax = clamp(box.ymax + pad, 0, imageHeight - 1);
        int zMin = clamp(box.zmin - pad, 0, imageDepth - 1);
        int zMax = clamp(box.zmax + pad, 0, imageDepth - 1);
        return new Crop3D(xMin, xMax, yMin, yMax, zMin, zMax);
    }

    private static double pixelSizeMicrons(ImagePlus rawStack, ImagePlus labelImage) {
        double raw = pixelWidth(rawStack);
        if (raw > 0.0 && Double.isFinite(raw)) {
            return raw;
        }
        double label = pixelWidth(labelImage);
        return label > 0.0 && Double.isFinite(label) ? label : 1.0;
    }

    private static double pixelWidth(ImagePlus image) {
        if (image == null) return Double.NaN;
        Calibration cal = image.getCalibration();
        return cal == null ? Double.NaN : cal.pixelWidth;
    }

    private static double sliceSpacingMicrons(ImagePlus image) {
        if (image == null) return 1.0;
        Calibration cal = image.getCalibration();
        double spacing = cal == null ? Double.NaN : cal.pixelDepth;
        return spacing > 0.0 && Double.isFinite(spacing) ? spacing : 1.0;
    }

    private static void replaceUnvisitedIntensity(float[] intensity) {
        for (int i = 0; i < intensity.length; i++) {
            if (intensity[i] == -Float.MAX_VALUE) {
                intensity[i] = 0.0f;
            }
        }
    }

    private static int clamp(int value, int min, int max) {
        if (max < min) return min;
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    private static final class Inputs {
        final BoundingBox box;
        final int label;

        private Inputs(BoundingBox box, int label) {
            this.box = box;
            this.label = label;
        }
    }

    private static final class Crop {
        final int xMin;
        final int xMax;
        final int yMin;
        final int yMax;
        final int width;
        final int height;

        private Crop(int xMin, int xMax, int yMin, int yMax) {
            this.xMin = xMin;
            this.xMax = xMax;
            this.yMin = yMin;
            this.yMax = yMax;
            this.width = xMax - xMin + 1;
            this.height = yMax - yMin + 1;
        }

        int index(int x, int y) {
            return (y - yMin) * width + (x - xMin);
        }
    }

    private static final class Crop3D {
        final int xMin;
        final int xMax;
        final int yMin;
        final int yMax;
        final int zMin;
        final int zMax;
        final int width;
        final int height;
        final int depth;

        private Crop3D(int xMin, int xMax, int yMin, int yMax, int zMin, int zMax) {
            this.xMin = xMin;
            this.xMax = xMax;
            this.yMin = yMin;
            this.yMax = yMax;
            this.zMin = zMin;
            this.zMax = zMax;
            this.width = xMax - xMin + 1;
            this.height = yMax - yMin + 1;
            this.depth = zMax - zMin + 1;
        }

        int index(int x, int y, int z) {
            int zz = z - zMin;
            int yy = y - yMin;
            int xx = x - xMin;
            return zz * width * height + yy * width + xx;
        }
    }
}
