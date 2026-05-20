package flash.pipeline.morphometry;

/**
 * Plain slice-major 3D intensity and mask patch for per-object texture primitives.
 *
 * <p>Voxel layout is {@code z * width * height + y * width + x}.
 */
public final class ObjectPatch3D {
    public final float[] intensity;
    public final byte[] mask;
    public final int width;
    public final int height;
    public final int depth;
    public final double pixelSize_um;
    public final double sliceSpacing_um;

    public ObjectPatch3D(float[] intensity,
                         byte[] mask,
                         int width,
                         int height,
                         int depth,
                         double pixelSize_um,
                         double sliceSpacing_um) {
        if (width <= 0) {
            throw new IllegalArgumentException("width must be positive");
        }
        if (height <= 0) {
            throw new IllegalArgumentException("height must be positive");
        }
        if (depth <= 0) {
            throw new IllegalArgumentException("depth must be positive");
        }
        long size = (long) width * (long) height * (long) depth;
        if (size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("patch is too large");
        }
        if (intensity == null) {
            throw new IllegalArgumentException("intensity must not be null");
        }
        if (mask == null) {
            throw new IllegalArgumentException("mask must not be null");
        }
        if (intensity.length != (int) size) {
            throw new IllegalArgumentException("intensity length must equal width * height * depth");
        }
        if (mask.length != (int) size) {
            throw new IllegalArgumentException("mask length must equal width * height * depth");
        }
        this.intensity = intensity;
        this.mask = mask;
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.pixelSize_um = pixelSize_um;
        this.sliceSpacing_um = sliceSpacing_um;
    }

    public int index(int x, int y, int z) {
        if (x < 0 || x >= width || y < 0 || y >= height || z < 0 || z >= depth) {
            throw new IndexOutOfBoundsException("coordinates outside patch");
        }
        return z * width * height + y * width + x;
    }

    public boolean containsObjectVoxel(int x, int y, int z) {
        return mask[index(x, y, z)] != 0;
    }

    public int objectVoxelCount() {
        int count = 0;
        for (int i = 0; i < mask.length; i++) {
            if (mask[i] != 0) count++;
        }
        return count;
    }
}
