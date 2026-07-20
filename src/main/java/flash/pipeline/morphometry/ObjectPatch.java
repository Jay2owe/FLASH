package flash.pipeline.morphometry;

/**
 * Plain row-major intensity and mask patch for per-object morphometry primitives.
 */
public final class ObjectPatch {
    public final float[] intensity;
    public final byte[] mask;
    public final int width;
    public final int height;
    public final double pixelSize_um;

    public ObjectPatch(float[] intensity, byte[] mask, int width, int height, double pixelSize_um) {
        if (width <= 0) {
            throw new IllegalArgumentException("width must be positive");
        }
        if (height <= 0) {
            throw new IllegalArgumentException("height must be positive");
        }
        long size = (long) width * (long) height;
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
            throw new IllegalArgumentException("intensity length must equal width * height");
        }
        if (mask.length != (int) size) {
            throw new IllegalArgumentException("mask length must equal width * height");
        }
        this.intensity = intensity;
        this.mask = mask;
        this.width = width;
        this.height = height;
        this.pixelSize_um = pixelSize_um;
    }

    public int index(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            throw new IndexOutOfBoundsException("coordinates outside patch");
        }
        return y * width + x;
    }

    public boolean containsObjectPixel(int x, int y) {
        return mask[index(x, y)] != 0;
    }

    public int objectPixelCount() {
        int count = 0;
        for (int i = 0; i < mask.length; i++) {
            if (mask[i] != 0) count++;
        }
        return count;
    }
}
