package flash.pipeline.segmentation;

import flash.pipeline.objects.ObjectsCounter3DWrapper;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.ResultsTable;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class EnhancedClassicalRunnerTest {

    @Before
    public void requireMcib3d() {
        Assume.assumeTrue(ObjectsCounter3DWrapper.isMcib3dAvailable());
    }

    @Test
    public void noMorphPredicatesMatchesPlainClassical() {
        ImagePlus image = twoObjectImage();
        ObjectsCounter3DWrapper.Result plain = new ObjectsCounter3DWrapper().runNative(
                image, 100, 1, Integer.MAX_VALUE, false, image, true, false);

        ImagePlus enhanced = new EnhancedClassicalRunner().run(
                image,
                new EnhancedClassicalParameters(100, 1, Integer.MAX_VALUE,
                        java.util.Collections.<MorphPredicate>emptyList(), image, null));

        assertSamePixels(plain.getObjectsMap(), enhanced);
        assertEquals(countLabels(plain.getObjectsMap()), countLabels(enhanced));
    }

    @Test
    public void sphericityFilterDropsElongatedObjects() {
        ImagePlus image = sphereAndRodImage();
        ImagePlus result = new EnhancedClassicalRunner().run(
                image,
                params(image, MorphPredicate.parse("sphericity>=0.6")));

        assertEquals(1, countLabels(result));
    }

    @Test
    public void volumeFilterAppliedAfterDetection() {
        ImagePlus image = cubeImage(3, 200);
        ImagePlus result = new EnhancedClassicalRunner().run(
                image,
                params(image, MorphPredicate.parse("volume>=100")));

        assertEquals(0, countLabels(result));
    }

    @Test
    public void compoundPredicatesAreANDed() {
        ImagePlus image = sphereAndSmallCubeImage();
        ImagePlus result = new EnhancedClassicalRunner().run(
                image,
                params(image,
                        MorphPredicate.parse("volume>=50"),
                        MorphPredicate.parse("sphericity>=0.45")));

        assertEquals(1, countLabels(result));
    }

    @Test
    public void unknownFeatureNameLogsWarningAndIsTreatedAsTrue() {
        ImagePlus image = twoObjectImage();
        final List<String> warnings = new ArrayList<String>();
        ImagePlus result = new EnhancedClassicalRunner().run(
                image,
                new EnhancedClassicalParameters(100, 1, Integer.MAX_VALUE,
                        Arrays.asList(MorphPredicate.parse("future_metric>999")),
                        image,
                        new EnhancedClassicalParameters.WarningSink() {
                            @Override public void warn(String message) {
                                warnings.add(message);
                            }
                        }));

        assertEquals(2, countLabels(result));
        assertFalse(warnings.isEmpty());
        assertTrue(warnings.get(0).contains("future_metric"));
    }

    @Test
    public void objectStatsPropertyAttachedToReturnedImage() {
        ImagePlus image = sphereAndRodImage();
        ImagePlus result = new EnhancedClassicalRunner().run(
                image,
                params(image, MorphPredicate.parse("sphericity>=0.0")));

        Object property = result.getProperty(EnhancedClassicalRunner.OBJECT_STATS_PROPERTY);
        assertTrue(property instanceof ResultsTable);
        ResultsTable table = (ResultsTable) property;
        assertEquals(countLabels(result), table.size());
        assertTrue(hasHeading(table, "Morph_Sphericity"));
    }

    private static EnhancedClassicalParameters params(ImagePlus image, MorphPredicate... predicates) {
        return new EnhancedClassicalParameters(100, 1, Integer.MAX_VALUE,
                Arrays.asList(predicates), image, null);
    }

    private static ImagePlus twoObjectImage() {
        ImagePlus image = blank("two", 24, 16, 8);
        fillCube(image, 3, 4, 2, 3, 200);
        fillCube(image, 15, 4, 2, 3, 200);
        return image;
    }

    private static ImagePlus sphereAndRodImage() {
        ImagePlus image = blank("sphere-rod", 64, 32, 16);
        fillSphere(image, 12, 16, 8, 5, 220);
        fillBox(image, 28, 55, 14, 16, 7, 8, 220);
        return image;
    }

    private static ImagePlus sphereAndSmallCubeImage() {
        ImagePlus image = blank("sphere-cube", 36, 24, 12);
        fillSphere(image, 10, 12, 6, 4, 220);
        fillCube(image, 26, 10, 5, 3, 220);
        return image;
    }

    private static ImagePlus cubeImage(int edge, int intensity) {
        ImagePlus image = blank("cube", 16, 16, 8);
        fillCube(image, 6, 6, 3, edge, intensity);
        return image;
    }

    private static ImagePlus blank(String title, int width, int height, int depth) {
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            stack.addSlice(new ByteProcessor(width, height));
        }
        ImagePlus image = new ImagePlus(title, stack);
        image.setDimensions(1, depth, 1);
        return image;
    }

    private static void fillSphere(ImagePlus image, int cx, int cy, int cz, int radius, int value) {
        int r2 = radius * radius;
        for (int z = 0; z < image.getStackSize(); z++) {
            ImageProcessor ip = image.getStack().getProcessor(z + 1);
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int dx = x - cx;
                    int dy = y - cy;
                    int dz = z - cz;
                    if (dx * dx + dy * dy + dz * dz <= r2) {
                        ip.set(x, y, value);
                    }
                }
            }
        }
    }

    private static void fillCube(ImagePlus image, int x0, int y0, int z0, int edge, int value) {
        fillBox(image, x0, x0 + edge - 1, y0, y0 + edge - 1, z0, z0 + edge - 1, value);
    }

    private static void fillBox(ImagePlus image,
                                int x0, int x1,
                                int y0, int y1,
                                int z0, int z1,
                                int value) {
        for (int z = Math.max(0, z0); z <= Math.min(image.getStackSize() - 1, z1); z++) {
            ImageProcessor ip = image.getStack().getProcessor(z + 1);
            for (int y = Math.max(0, y0); y <= Math.min(image.getHeight() - 1, y1); y++) {
                for (int x = Math.max(0, x0); x <= Math.min(image.getWidth() - 1, x1); x++) {
                    ip.set(x, y, value);
                }
            }
        }
    }

    private static int countLabels(ImagePlus image) {
        if (image == null || image.getStack() == null) return 0;
        Set<Integer> labels = new HashSet<Integer>();
        for (int z = 1; z <= image.getStackSize(); z++) {
            ImageProcessor ip = image.getStack().getProcessor(z);
            for (int i = 0; i < ip.getPixelCount(); i++) {
                int label = Math.round(ip.getf(i));
                if (label > 0) labels.add(Integer.valueOf(label));
            }
        }
        return labels.size();
    }

    private static void assertSamePixels(ImagePlus expected, ImagePlus actual) {
        assertNotNull(expected);
        assertNotNull(actual);
        assertEquals(expected.getWidth(), actual.getWidth());
        assertEquals(expected.getHeight(), actual.getHeight());
        assertEquals(expected.getStackSize(), actual.getStackSize());
        for (int z = 1; z <= expected.getStackSize(); z++) {
            ImageProcessor e = expected.getStack().getProcessor(z);
            ImageProcessor a = actual.getStack().getProcessor(z);
            for (int i = 0; i < e.getPixelCount(); i++) {
                assertEquals(e.getf(i), a.getf(i), 0.0);
            }
        }
    }

    private static boolean hasHeading(ResultsTable table, String heading) {
        String[] headings = table.getHeadings();
        if (headings == null) return false;
        for (int i = 0; i < headings.length; i++) {
            if (heading.equals(headings[i])) return true;
        }
        return false;
    }
}
