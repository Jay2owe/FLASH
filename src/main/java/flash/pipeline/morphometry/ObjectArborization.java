package flash.pipeline.morphometry;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import mcib3d.geom2.BoundingBox;
import mcib3d.geom2.Object3DInt;
import mcib3d.geom2.Object3DPlane;
import mcib3d.geom2.VoxelInt;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-object 3D arborization measurements: Sholl profile summaries and
 * AnalyzeSkeleton-style graph counts.
 */
public final class ObjectArborization {

    public static final double DEFAULT_SHOLL_STEP_UM = 5.0;

    private ObjectArborization() {
    }

    public static Result compute(Object3DInt object, ImagePlus labelImage) {
        return compute(object, labelImage, DEFAULT_SHOLL_STEP_UM);
    }

    public static Result compute(Object3DInt object, ImagePlus labelImage, double preferredStepUm) {
        MaskVolume volume = MaskVolume.fromObject(object, labelImage);
        if (volume == null || volume.voxelCount <= 0) {
            return Result.invalid();
        }

        ImagePlus skeletonImage = null;
        try {
            skeletonImage = skeletonize(volume);
            boolean[] skeleton = readMask(skeletonImage);
            int skeletonVoxels = countTrue(skeleton);
            if (skeletonVoxels == 0) {
                skeleton = volume.mask.clone();
                skeletonVoxels = countTrue(skeleton);
            }

            SkeletonSummary summary = analyzeSkeletonWithPlugin(skeletonImage);
            if (summary == null) {
                summary = SkeletonGraph.analyze(skeleton, volume.width, volume.height, volume.depth);
            }
            ShollProfile profile = ShollProfile.compute(skeleton, volume, preferredStepUm);

            return new Result(
                    profile.criticalRadiusUm,
                    profile.criticalIntersections,
                    profile.schoenenIndex,
                    profile.primaryBranches,
                    summary.branches,
                    summary.junctions,
                    summary.endpoints,
                    skeletonVoxels,
                    profile.points,
                    true,
                    summary.source);
        } catch (Throwable t) {
            return Result.invalid();
        } finally {
            if (skeletonImage != null) {
                skeletonImage.changes = false;
                skeletonImage.close();
                skeletonImage.flush();
            }
        }
    }

    private static ImagePlus skeletonize(MaskVolume volume) {
        ImagePlus image = volume.toImagePlus();
        if (!trySkeletonize3D(image)) {
            boolean[] skeleton = thin3D(volume.mask, volume.width, volume.height, volume.depth);
            writeMask(image, skeleton);
        }
        return image;
    }

    private static boolean trySkeletonize3D(ImagePlus image) {
        try {
            Class<?> clazz = Class.forName("sc.fiji.skeletonize3D.Skeletonize3D_");
            Object plugin = clazz.getDeclaredConstructor().newInstance();
            Method setup = clazz.getMethod("setup", String.class, ImagePlus.class);
            setup.invoke(plugin, "", image);
            Method run = clazz.getMethod("run", ImageProcessor.class);
            run.invoke(plugin, image.getProcessor());
            image.updateAndDraw();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void writeMask(ImagePlus image, boolean[] mask) {
        ImageStack stack = image.getStack();
        int width = image.getWidth();
        int height = image.getHeight();
        int depth = stack.getSize();
        for (int z = 0; z < depth; z++) {
            ImageProcessor ip = stack.getProcessor(z + 1);
            if (ip instanceof ByteProcessor) {
                ip.setValue(0.0);
                ip.fill();
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        if (mask[index(x, y, z, width, height)]) {
                            ip.set(x, y, 255);
                        }
                    }
                }
            }
        }
        image.updateAndDraw();
    }

    /**
     * Topology-preserving 3D thinning fallback for environments where Fiji's
     * Skeletonize3D plugin is not available. It uses 26-connected foreground and
     * 6-connected background simple-point checks over six directional border
     * passes, preserving endpoints while iteratively peeling surface voxels.
     */
    private static boolean[] thin3D(boolean[] source, int width, int height, int depth) {
        boolean[] skeleton = source == null ? new boolean[0] : source.clone();
        int maxIterations = Math.max(1, countTrue(skeleton));
        boolean changed = true;
        int iteration = 0;
        while (changed && iteration++ < maxIterations) {
            changed = false;
            for (int direction = 0; direction < 6; direction++) {
                for (int idx = 0; idx < skeleton.length; idx++) {
                    if (!skeleton[idx]) continue;
                    if (!isDirectionalBorderVoxel(skeleton, idx, width, height, depth, direction)) {
                        continue;
                    }
                    if (isSimpleEndpointPreservingPoint(skeleton, idx, width, height, depth)) {
                        skeleton[idx] = false;
                        changed = true;
                    }
                }
            }
        }
        return skeleton;
    }

    private static boolean isDirectionalBorderVoxel(boolean[] mask, int idx,
                                                    int width, int height, int depth,
                                                    int direction) {
        int x = xOf(idx, width);
        int y = yOf(idx, width, height);
        int z = zOf(idx, width, height);
        switch (direction) {
            case 0:
                return x == 0 || !mask[index(x - 1, y, z, width, height)];
            case 1:
                return x == width - 1 || !mask[index(x + 1, y, z, width, height)];
            case 2:
                return y == 0 || !mask[index(x, y - 1, z, width, height)];
            case 3:
                return y == height - 1 || !mask[index(x, y + 1, z, width, height)];
            case 4:
                return z == 0 || !mask[index(x, y, z - 1, width, height)];
            case 5:
                return z == depth - 1 || !mask[index(x, y, z + 1, width, height)];
            default:
                return false;
        }
    }

    private static boolean isSimpleEndpointPreservingPoint(boolean[] mask, int idx,
                                                          int width, int height, int depth) {
        int[] foregroundNeighbors = neighborIndices(mask, idx, width, height, depth);
        if (foregroundNeighbors.length <= 1) {
            return false;
        }
        if (foregroundComponentCountAfterRemoval(mask, idx, width, height, depth) != 1) {
            return false;
        }
        return backgroundComponentCount(mask, idx, width, height, depth, false)
                == backgroundComponentCount(mask, idx, width, height, depth, true);
    }

    private static int foregroundComponentCountAfterRemoval(boolean[] mask, int idx,
                                                           int width, int height, int depth) {
        int[] neighbors = neighborIndices(mask, idx, width, height, depth);
        if (neighbors.length == 0) return 0;
        Set<Integer> remaining = new HashSet<Integer>();
        for (int n : neighbors) remaining.add(Integer.valueOf(n));
        int components = 0;
        ArrayDeque<Integer> queue = new ArrayDeque<Integer>();
        while (!remaining.isEmpty()) {
            Integer seed = remaining.iterator().next();
            remaining.remove(seed);
            queue.add(seed);
            components++;
            while (!queue.isEmpty()) {
                int current = queue.removeFirst().intValue();
                int[] ns = neighborIndices(mask, current, width, height, depth);
                for (int n : ns) {
                    Integer key = Integer.valueOf(n);
                    if (n != idx && remaining.remove(key)) {
                        queue.add(key);
                    }
                }
            }
        }
        return components;
    }

    private static int backgroundComponentCount(boolean[] mask, int idx,
                                                int width, int height, int depth,
                                                boolean centerAsBackground) {
        int centerX = xOf(idx, width);
        int centerY = yOf(idx, width, height);
        int centerZ = zOf(idx, width, height);
        boolean[] local = new boolean[27];
        int[] globalByLocal = new int[27];
        Arrays.fill(globalByLocal, -1);
        for (int dz = -1; dz <= 1; dz++) {
            int z = centerZ + dz;
            if (z < 0 || z >= depth) continue;
            for (int dy = -1; dy <= 1; dy++) {
                int y = centerY + dy;
                if (y < 0 || y >= height) continue;
                for (int dx = -1; dx <= 1; dx++) {
                    int x = centerX + dx;
                    if (x < 0 || x >= width) continue;
                    int localIdx = localIndex(dx, dy, dz);
                    int global = index(x, y, z, width, height);
                    globalByLocal[localIdx] = global;
                    local[localIdx] = global == idx ? centerAsBackground : !mask[global];
                }
            }
        }

        boolean[] visited = new boolean[27];
        int components = 0;
        ArrayDeque<Integer> queue = new ArrayDeque<Integer>();
        for (int i = 0; i < local.length; i++) {
            if (!local[i] || visited[i] || globalByLocal[i] < 0) continue;
            visited[i] = true;
            queue.add(Integer.valueOf(i));
            components++;
            while (!queue.isEmpty()) {
                int current = queue.removeFirst().intValue();
                int dx = localDx(current);
                int dy = localDy(current);
                int dz = localDz(current);
                int[][] n6 = {
                        {dx - 1, dy, dz}, {dx + 1, dy, dz},
                        {dx, dy - 1, dz}, {dx, dy + 1, dz},
                        {dx, dy, dz - 1}, {dx, dy, dz + 1}
                };
                for (int[] n : n6) {
                    if (n[0] < -1 || n[0] > 1 || n[1] < -1 || n[1] > 1
                            || n[2] < -1 || n[2] > 1) {
                        continue;
                    }
                    int li = localIndex(n[0], n[1], n[2]);
                    if (globalByLocal[li] >= 0 && local[li] && !visited[li]) {
                        visited[li] = true;
                        queue.add(Integer.valueOf(li));
                    }
                }
            }
        }
        return components;
    }

    private static int localIndex(int dx, int dy, int dz) {
        return (dz + 1) * 9 + (dy + 1) * 3 + (dx + 1);
    }

    private static int localDx(int localIndex) {
        return localIndex % 3 - 1;
    }

    private static int localDy(int localIndex) {
        return (localIndex / 3) % 3 - 1;
    }

    private static int localDz(int localIndex) {
        return localIndex / 9 - 1;
    }

    private static SkeletonSummary analyzeSkeletonWithPlugin(ImagePlus skeletonImage) {
        try {
            Class<?> clazz = Class.forName("sc.fiji.analyzeSkeleton.AnalyzeSkeleton_");
            Object plugin = clazz.getDeclaredConstructor().newInstance();
            try {
                Field display = clazz.getField("displaySkeletons");
                display.setBoolean(null, false);
            } catch (Throwable ignored) {
                // Older AnalyzeSkeleton builds may not expose this field.
            }
            Method setup = clazz.getMethod("setup", String.class, ImagePlus.class);
            setup.invoke(plugin, "", skeletonImage);
            int none = clazz.getField("NONE").getInt(null);
            Method run = clazz.getMethod("run", int.class, boolean.class, boolean.class,
                    ImagePlus.class, boolean.class, boolean.class);
            Object result = run.invoke(plugin, Integer.valueOf(none), Boolean.FALSE, Boolean.FALSE,
                    skeletonImage, Boolean.TRUE, Boolean.FALSE);
            if (result == null) return null;
            int branches = sumIntArray(invokeIntArray(result, "getBranches"));
            int junctions = sumIntArray(invokeIntArray(result, "getJunctions"));
            int endpoints = sumIntArray(invokeIntArray(result, "getEndPoints"));
            return new SkeletonSummary(branches, junctions, endpoints, "AnalyzeSkeleton");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int[] invokeIntArray(Object target, String methodName) throws Exception {
        Method method = target.getClass().getMethod(methodName);
        Object value = method.invoke(target);
        return value instanceof int[] ? (int[]) value : new int[0];
    }

    private static int sumIntArray(int[] values) {
        if (values == null) return 0;
        int sum = 0;
        for (int value : values) sum += value;
        return sum;
    }

    private static boolean[] readMask(ImagePlus image) {
        ImageStack stack = image.getStack();
        int width = image.getWidth();
        int height = image.getHeight();
        int depth = stack.getSize();
        boolean[] out = new boolean[width * height * depth];
        for (int z = 0; z < depth; z++) {
            ImageProcessor ip = stack.getProcessor(z + 1);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    out[index(x, y, z, width, height)] = ip.get(x, y) > 0;
                }
            }
        }
        return out;
    }

    private static int countTrue(boolean[] mask) {
        int count = 0;
        if (mask != null) {
            for (boolean value : mask) {
                if (value) count++;
            }
        }
        return count;
    }

    private static int index(int x, int y, int z, int width, int height) {
        return z * width * height + y * width + x;
    }

    private static int xOf(int index, int width) {
        return index % width;
    }

    private static int yOf(int index, int width, int height) {
        return (index / width) % height;
    }

    private static int zOf(int index, int width, int height) {
        return index / (width * height);
    }

    private static long edgeKey(int a, int b) {
        int min = Math.min(a, b);
        int max = Math.max(a, b);
        return (((long) min) << 32) ^ (max & 0xffffffffL);
    }

    private static int[] neighborIndices(boolean[] mask, int idx, int width, int height, int depth) {
        int x = xOf(idx, width);
        int y = yOf(idx, width, height);
        int z = zOf(idx, width, height);
        int[] tmp = new int[26];
        int n = 0;
        for (int dz = -1; dz <= 1; dz++) {
            int zz = z + dz;
            if (zz < 0 || zz >= depth) continue;
            for (int dy = -1; dy <= 1; dy++) {
                int yy = y + dy;
                if (yy < 0 || yy >= height) continue;
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    int xx = x + dx;
                    if (xx < 0 || xx >= width) continue;
                    int ni = index(xx, yy, zz, width, height);
                    if (mask[ni]) tmp[n++] = ni;
                }
            }
        }
        return Arrays.copyOf(tmp, n);
    }

    public static final class Result {
        public final double shollCriticalRadiusUm;
        public final double shollCriticalIntersections;
        public final double shollSchoenenIndex;
        public final double shollPrimaryBranches;
        public final double skeletonBranches;
        public final double skeletonJunctions;
        public final double skeletonEndpoints;
        public final double skeletonVoxels;
        public final List<ShollPoint> shollProfile;
        public final boolean valid;
        public final String skeletonSource;

        private Result(double shollCriticalRadiusUm,
                       double shollCriticalIntersections,
                       double shollSchoenenIndex,
                       double shollPrimaryBranches,
                       double skeletonBranches,
                       double skeletonJunctions,
                       double skeletonEndpoints,
                       double skeletonVoxels,
                       List<ShollPoint> shollProfile,
                       boolean valid,
                       String skeletonSource) {
            this.shollCriticalRadiusUm = shollCriticalRadiusUm;
            this.shollCriticalIntersections = shollCriticalIntersections;
            this.shollSchoenenIndex = shollSchoenenIndex;
            this.shollPrimaryBranches = shollPrimaryBranches;
            this.skeletonBranches = skeletonBranches;
            this.skeletonJunctions = skeletonJunctions;
            this.skeletonEndpoints = skeletonEndpoints;
            this.skeletonVoxels = skeletonVoxels;
            this.shollProfile = shollProfile == null
                    ? Collections.<ShollPoint>emptyList()
                    : Collections.unmodifiableList(new ArrayList<ShollPoint>(shollProfile));
            this.valid = valid;
            this.skeletonSource = skeletonSource == null ? "" : skeletonSource;
        }

        public static Result invalid() {
            return new Result(Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    Collections.<ShollPoint>emptyList(), false, "");
        }
    }

    public static final class ShollPoint {
        public final double radiusUm;
        public final int intersections;

        private ShollPoint(double radiusUm, int intersections) {
            this.radiusUm = radiusUm;
            this.intersections = intersections;
        }
    }

    private static final class SkeletonSummary {
        final int branches;
        final int junctions;
        final int endpoints;
        final String source;

        SkeletonSummary(int branches, int junctions, int endpoints, String source) {
            this.branches = branches;
            this.junctions = junctions;
            this.endpoints = endpoints;
            this.source = source;
        }
    }

    private static final class MaskVolume {
        final boolean[] mask;
        final int width;
        final int height;
        final int depth;
        final int voxelCount;
        final double centerX;
        final double centerY;
        final double centerZ;
        final double pixelWidth;
        final double pixelHeight;
        final double pixelDepth;

        private MaskVolume(boolean[] mask, int width, int height, int depth, int voxelCount,
                           double centerX, double centerY, double centerZ,
                           double pixelWidth, double pixelHeight, double pixelDepth) {
            this.mask = mask;
            this.width = width;
            this.height = height;
            this.depth = depth;
            this.voxelCount = voxelCount;
            this.centerX = centerX;
            this.centerY = centerY;
            this.centerZ = centerZ;
            this.pixelWidth = pixelWidth;
            this.pixelHeight = pixelHeight;
            this.pixelDepth = pixelDepth;
        }

        static MaskVolume fromObject(Object3DInt object, ImagePlus labelImage) {
            if (object == null || labelImage == null || labelImage.getStack() == null) return null;
            BoundingBox box = object.getBoundingBox();
            if (box == null) return null;
            int imageWidth = labelImage.getWidth();
            int imageHeight = labelImage.getHeight();
            int imageDepth = labelImage.getStack().getSize();
            int xMin = clamp(box.xmin - 1, 0, imageWidth - 1);
            int xMax = clamp(box.xmax + 1, 0, imageWidth - 1);
            int yMin = clamp(box.ymin - 1, 0, imageHeight - 1);
            int yMax = clamp(box.ymax + 1, 0, imageHeight - 1);
            int zMin = clamp(box.zmin - 1, 0, imageDepth - 1);
            int zMax = clamp(box.zmax + 1, 0, imageDepth - 1);
            int width = Math.max(1, xMax - xMin + 1);
            int height = Math.max(1, yMax - yMin + 1);
            int depth = Math.max(1, zMax - zMin + 1);
            boolean[] mask = new boolean[width * height * depth];
            int count = 0;
            double sumX = 0.0;
            double sumY = 0.0;
            double sumZ = 0.0;

            for (Object3DPlane plane : object.getObject3DPlanes()) {
                if (plane == null) continue;
                for (VoxelInt voxel : plane.getVoxels()) {
                    if (voxel == null) continue;
                    int x = voxel.getX();
                    int y = voxel.getY();
                    int z = voxel.getZ();
                    if (x < xMin || x > xMax || y < yMin || y > yMax || z < zMin || z > zMax) {
                        continue;
                    }
                    int lx = x - xMin;
                    int ly = y - yMin;
                    int lz = z - zMin;
                    int idx = index(lx, ly, lz, width, height);
                    if (!mask[idx]) {
                        mask[idx] = true;
                        count++;
                        sumX += lx;
                        sumY += ly;
                        sumZ += lz;
                    }
                }
            }

            if (count == 0) return null;
            Calibration cal = labelImage.getCalibration();
            double pw = positiveOr(cal == null ? Double.NaN : cal.pixelWidth, 1.0);
            double ph = positiveOr(cal == null ? Double.NaN : cal.pixelHeight, pw);
            double pd = positiveOr(cal == null ? Double.NaN : cal.pixelDepth, 1.0);
            return new MaskVolume(mask, width, height, depth, count,
                    sumX / count, sumY / count, sumZ / count, pw, ph, pd);
        }

        ImagePlus toImagePlus() {
            ImageStack stack = new ImageStack(width, height);
            for (int z = 0; z < depth; z++) {
                ByteProcessor bp = new ByteProcessor(width, height);
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        if (mask[index(x, y, z, width, height)]) {
                            bp.set(x, y, 255);
                        }
                    }
                }
                stack.addSlice(bp);
            }
            ImagePlus image = new ImagePlus("FLASH_object_skeleton_source", stack);
            Calibration cal = new Calibration(image);
            cal.pixelWidth = pixelWidth;
            cal.pixelHeight = pixelHeight;
            cal.pixelDepth = pixelDepth;
            cal.setUnit("um");
            image.setCalibration(cal);
            return image;
        }
    }

    private static final class ShollProfile {
        final double criticalRadiusUm;
        final double criticalIntersections;
        final double schoenenIndex;
        final double primaryBranches;
        final List<ShollPoint> points;

        private ShollProfile(double criticalRadiusUm, double criticalIntersections,
                             double schoenenIndex, double primaryBranches,
                             List<ShollPoint> points) {
            this.criticalRadiusUm = criticalRadiusUm;
            this.criticalIntersections = criticalIntersections;
            this.schoenenIndex = schoenenIndex;
            this.primaryBranches = primaryBranches;
            this.points = points;
        }

        static ShollProfile compute(boolean[] skeleton, MaskVolume volume, double preferredStepUm) {
            List<Integer> voxels = skeletonVoxels(skeleton);
            if (voxels.isEmpty()) {
                return invalidProfile();
            }
            double[] distances = new double[skeleton.length];
            double maxRadius = 0.0;
            for (int idx : voxels) {
                double distance = distanceUm(idx, volume);
                distances[idx] = distance;
                if (distance > maxRadius) maxRadius = distance;
            }
            if (!(maxRadius > 0.0) || !Double.isFinite(maxRadius)) {
                return invalidProfile();
            }

            double step = chooseRadiusStep(volume, maxRadius, preferredStepUm);
            List<int[]> edges = uniqueEdges(skeleton, volume.width, volume.height, volume.depth);
            if (edges.isEmpty()) {
                return invalidProfile();
            }

            List<ShollPoint> points = new ArrayList<ShollPoint>();
            int critical = 0;
            double criticalRadius = Double.NaN;
            int primary = 0;
            double eps = 1.0e-9;
            for (double radius = step; radius <= maxRadius + eps; radius += step) {
                int intersections = 0;
                for (int[] edge : edges) {
                    double a = distances[edge[0]];
                    double b = distances[edge[1]];
                    if ((a < radius && b >= radius) || (b < radius && a >= radius)) {
                        intersections++;
                    }
                }
                points.add(new ShollPoint(radius, intersections));
                if (primary == 0 && intersections > 0) {
                    primary = intersections;
                }
                if (intersections > critical) {
                    critical = intersections;
                    criticalRadius = radius;
                }
            }

            double primaryValue = primary > 0 ? primary : Double.NaN;
            double criticalValue = critical > 0 ? critical : Double.NaN;
            double index = primary > 0 ? ((double) critical) / primary : Double.NaN;
            if (critical <= 0) criticalRadius = Double.NaN;
            return new ShollProfile(criticalRadius, criticalValue, index, primaryValue, points);
        }

        private static ShollProfile invalidProfile() {
            return new ShollProfile(Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    Collections.<ShollPoint>emptyList());
        }

        private static double distanceUm(int idx, MaskVolume volume) {
            double dx = (xOf(idx, volume.width) - volume.centerX) * volume.pixelWidth;
            double dy = (yOf(idx, volume.width, volume.height) - volume.centerY) * volume.pixelHeight;
            double dz = (zOf(idx, volume.width, volume.height) - volume.centerZ) * volume.pixelDepth;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        private static double chooseRadiusStep(MaskVolume volume, double maxRadius, double preferredStepUm) {
            double voxelStep = Math.max(1.0e-6, Math.min(volume.pixelWidth,
                    Math.min(volume.pixelHeight, volume.pixelDepth)));
            double preferred = preferredStepUm > 0.0 && Double.isFinite(preferredStepUm)
                    ? preferredStepUm : DEFAULT_SHOLL_STEP_UM;
            if (maxRadius <= preferred * 1.5) {
                return voxelStep;
            }
            return Math.max(voxelStep, preferred);
        }
    }

    private static final class SkeletonGraph {
        static SkeletonSummary analyze(boolean[] skeleton, int width, int height, int depth) {
            List<Integer> voxels = skeletonVoxels(skeleton);
            if (voxels.isEmpty()) {
                return new SkeletonSummary(0, 0, 0, "Internal graph");
            }

            Map<Integer, int[]> neighbors = new HashMap<Integer, int[]>();
            Map<Integer, Integer> degree = new HashMap<Integer, Integer>();
            int endpoints = 0;
            for (int idx : voxels) {
                int[] ns = neighborIndices(skeleton, idx, width, height, depth);
                neighbors.put(Integer.valueOf(idx), ns);
                degree.put(Integer.valueOf(idx), Integer.valueOf(ns.length));
                if (ns.length == 1) endpoints++;
            }

            Map<Integer, Integer> junctionNodeByVoxel =
                    labelJunctionComponents(voxels, degree, neighbors);
            int junctions = uniqueNodeCount(junctionNodeByVoxel);
            int branches = countBranches(voxels, degree, neighbors, junctionNodeByVoxel);
            return new SkeletonSummary(branches, junctions, endpoints, "Internal graph");
        }

        private static Map<Integer, Integer> labelJunctionComponents(List<Integer> voxels,
                                                                     Map<Integer, Integer> degree,
                                                                     Map<Integer, int[]> neighbors) {
            Map<Integer, Integer> out = new HashMap<Integer, Integer>();
            int nextNode = 1;
            for (int idx : voxels) {
                if (degree.get(Integer.valueOf(idx)).intValue() < 3 || out.containsKey(Integer.valueOf(idx))) {
                    continue;
                }
                ArrayDeque<Integer> queue = new ArrayDeque<Integer>();
                queue.add(Integer.valueOf(idx));
                out.put(Integer.valueOf(idx), Integer.valueOf(nextNode));
                while (!queue.isEmpty()) {
                    int current = queue.removeFirst().intValue();
                    int[] ns = neighbors.get(Integer.valueOf(current));
                    for (int n : ns) {
                        Integer key = Integer.valueOf(n);
                        if (degree.get(key).intValue() < 3 || out.containsKey(key)) continue;
                        out.put(key, Integer.valueOf(nextNode));
                        queue.add(key);
                    }
                }
                nextNode++;
            }
            return out;
        }

        private static int uniqueNodeCount(Map<Integer, Integer> nodeByVoxel) {
            Set<Integer> nodes = new HashSet<Integer>(nodeByVoxel.values());
            return nodes.size();
        }

        private static int countBranches(List<Integer> voxels,
                                         Map<Integer, Integer> degree,
                                         Map<Integer, int[]> neighbors,
                                         Map<Integer, Integer> junctionNodeByVoxel) {
            Set<Long> visitedEdges = new HashSet<Long>();
            int branches = 0;
            for (int idx : voxels) {
                int deg = degree.get(Integer.valueOf(idx)).intValue();
                if (deg == 2 && !junctionNodeByVoxel.containsKey(Integer.valueOf(idx))) continue;
                int[] ns = neighbors.get(Integer.valueOf(idx));
                for (int n : ns) {
                    if (sameJunction(idx, n, junctionNodeByVoxel)) continue;
                    if (traceBranch(idx, n, degree, neighbors, junctionNodeByVoxel, visitedEdges)) {
                        branches++;
                    }
                }
            }

            for (int idx : voxels) {
                int[] ns = neighbors.get(Integer.valueOf(idx));
                for (int n : ns) {
                    long key = edgeKey(idx, n);
                    if (visitedEdges.contains(Long.valueOf(key))) continue;
                    traceRemainingComponent(idx, n, neighbors, visitedEdges);
                    branches++;
                }
            }
            return branches;
        }

        private static boolean traceBranch(int start,
                                           int next,
                                           Map<Integer, Integer> degree,
                                           Map<Integer, int[]> neighbors,
                                           Map<Integer, Integer> junctionNodeByVoxel,
                                           Set<Long> visitedEdges) {
            long first = edgeKey(start, next);
            if (!visitedEdges.add(Long.valueOf(first))) return false;

            int previous = start;
            int current = next;
            while (degree.get(Integer.valueOf(current)).intValue() == 2
                    && !junctionNodeByVoxel.containsKey(Integer.valueOf(current))) {
                int[] ns = neighbors.get(Integer.valueOf(current));
                int candidate = ns[0] == previous ? ns[1] : ns[0];
                long key = edgeKey(current, candidate);
                if (!visitedEdges.add(Long.valueOf(key))) break;
                previous = current;
                current = candidate;
            }
            return true;
        }

        private static void traceRemainingComponent(int start,
                                                    int next,
                                                    Map<Integer, int[]> neighbors,
                                                    Set<Long> visitedEdges) {
            ArrayDeque<int[]> queue = new ArrayDeque<int[]>();
            queue.add(new int[]{start, next});
            while (!queue.isEmpty()) {
                int[] edge = queue.removeFirst();
                long key = edgeKey(edge[0], edge[1]);
                if (!visitedEdges.add(Long.valueOf(key))) continue;
                int[] ns = neighbors.get(Integer.valueOf(edge[1]));
                for (int n : ns) {
                    long nk = edgeKey(edge[1], n);
                    if (!visitedEdges.contains(Long.valueOf(nk))) {
                        queue.add(new int[]{edge[1], n});
                    }
                }
            }
        }

        private static boolean sameJunction(int a, int b, Map<Integer, Integer> junctionNodeByVoxel) {
            Integer ja = junctionNodeByVoxel.get(Integer.valueOf(a));
            Integer jb = junctionNodeByVoxel.get(Integer.valueOf(b));
            return ja != null && ja.equals(jb);
        }
    }

    private static List<Integer> skeletonVoxels(boolean[] skeleton) {
        List<Integer> voxels = new ArrayList<Integer>();
        if (skeleton != null) {
            for (int i = 0; i < skeleton.length; i++) {
                if (skeleton[i]) voxels.add(Integer.valueOf(i));
            }
        }
        return voxels;
    }

    private static List<int[]> uniqueEdges(boolean[] skeleton, int width, int height, int depth) {
        List<int[]> out = new ArrayList<int[]>();
        for (int idx = 0; idx < skeleton.length; idx++) {
            if (!skeleton[idx]) continue;
            int[] ns = neighborIndices(skeleton, idx, width, height, depth);
            for (int n : ns) {
                if (n > idx) out.add(new int[]{idx, n});
            }
        }
        return out;
    }

    private static int clamp(int value, int min, int max) {
        if (max < min) return min;
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    private static double positiveOr(double value, double fallback) {
        return value > 0.0 && Double.isFinite(value) ? value : fallback;
    }
}
