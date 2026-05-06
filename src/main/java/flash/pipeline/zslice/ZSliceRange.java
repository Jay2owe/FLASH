package flash.pipeline.zslice;

import java.util.Objects;

/**
 * Inclusive 1-based Z-slice range.
 */
public final class ZSliceRange {
    public final int startSlice;
    public final int endSlice;

    public ZSliceRange(int startSlice, int endSlice) {
        if (startSlice < 1) {
            throw new IllegalArgumentException("startSlice must be >= 1");
        }
        if (endSlice < startSlice) {
            throw new IllegalArgumentException("endSlice must be >= startSlice");
        }
        this.startSlice = startSlice;
        this.endSlice = endSlice;
    }

    public static ZSliceRange parse(String token) {
        if (token == null) return null;
        String normalized = token.trim();
        if (normalized.isEmpty()) return null;
        String[] parts = normalized.split("\\s*-\\s*");
        if (parts.length != 2) return null;
        try {
            return new ZSliceRange(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static ZSliceRange fullStack(int totalSlices) {
        if (totalSlices < 1) {
            throw new IllegalArgumentException("totalSlices must be >= 1");
        }
        return new ZSliceRange(1, totalSlices);
    }

    public boolean isValidFor(int totalSlices) {
        return totalSlices >= 1 && startSlice >= 1 && endSlice <= totalSlices;
    }

    public int count() {
        return endSlice - startSlice + 1;
    }

    public boolean coversFullStack(int totalSlices) {
        return totalSlices >= 1 && startSlice == 1 && endSlice == totalSlices;
    }

    public String toToken() {
        return startSlice + "-" + endSlice;
    }

    @Override
    public String toString() {
        return toToken();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ZSliceRange)) return false;
        ZSliceRange that = (ZSliceRange) other;
        return startSlice == that.startSlice && endSlice == that.endSlice;
    }

    @Override
    public int hashCode() {
        return Objects.hash(startSlice, endSlice);
    }
}
