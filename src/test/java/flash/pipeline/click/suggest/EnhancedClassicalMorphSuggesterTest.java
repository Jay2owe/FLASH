package flash.pipeline.click.suggest;

import flash.pipeline.click.ClickStore;
import flash.pipeline.objects.ObjectsCounter3DWrapper;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class EnhancedClassicalMorphSuggesterTest {

    @Before
    public void requireMcib3d() {
        Assume.assumeTrue(ObjectsCounter3DWrapper.isMcib3dAvailable());
    }

    @Test
    public void suggestsSphericityWhenBadClicksAreElongated() {
        Fixture f = sphereAndRod();

        EnhancedClassicalMorphSuggester.MorphSuggestion suggestion = suggestMorph(f,
                negatives(negative(2, 40, 16, 8)),
                positives(positive(1, 12, 16, 8)));

        assertNotNull(suggestion);
        assertEquals(EnhancedClassicalMorphSuggester.FEATURE_SPHERICITY,
                suggestion.featureName);
        assertEquals(">=", suggestion.operator.symbol());
    }

    @Test
    public void suggestsHighestMarginFeatureWhenMultipleSeparate() {
        Fixture f = equalCubesWithIntensities(
                new double[]{10, 11, 20, 20},
                new double[]{12, 13, 80, 90});

        EnhancedClassicalMorphSuggester.MorphSuggestion suggestion = suggestMorph(f,
                negatives(negative(1, 3, 3, 3), negative(2, 10, 3, 3)),
                positives(positive(3, 17, 3, 3), positive(4, 24, 3, 3)));

        assertNotNull(suggestion);
        assertEquals(EnhancedClassicalMorphSuggester.FEATURE_MAX_INTENSITY,
                suggestion.featureName);
    }

    @Test
    public void returnsNullWhenNoSinglePredicateSeparates() {
        Fixture f = equalCubesWithIntensities(
                new double[]{10, 30, 20, 40},
                new double[]{10, 30, 20, 40});

        EnhancedClassicalMorphSuggester.MorphSuggestion suggestion = suggestMorph(f,
                negatives(negative(1, 3, 3, 3), negative(2, 10, 3, 3)),
                positives(positive(3, 17, 3, 3), positive(4, 24, 3, 3)));

        assertNull(suggestion);
    }

    @Test
    public void respectsPositiveClicksAsDoNotEliminate() {
        Fixture f = twoRodsAndSphere();

        EnhancedClassicalMorphSuggester.MorphSuggestion suggestion = suggestMorph(f,
                negatives(negative(1, 10, 6, 5)),
                positives(positive(2, 10, 16, 5), positive(3, 30, 12, 6)));

        assertNull(suggestion);
    }

    @Test
    public void combinesWithExistingClassicalSuggestion() {
        Fixture f = threeBadRodsAndGoodSphere();

        EnhancedClassicalMorphSuggester.EnhancedClassicalSuggestion suggestion =
                new EnhancedClassicalMorphSuggester().suggest(context(f,
                        negatives(
                                negative(1, 8, 5, 5),
                                negative(2, 8, 13, 5),
                                negative(3, 8, 21, 5)),
                        positives(positive(4, 34, 13, 7))));

        assertNotNull(suggestion);
        assertTrue(suggestion.hasParameterSuggestion());
        assertTrue(suggestion.hasMorphSuggestion());
        assertNotNull(suggestion.parameterSuggestion.thresholdLow);
    }

    private static EnhancedClassicalMorphSuggester.MorphSuggestion suggestMorph(
            Fixture fixture,
            List<ClickStore.Click> negative,
            List<ClickStore.Click> positive) {
        return new EnhancedClassicalMorphSuggester().suggestMorph(
                context(fixture, negative, positive));
    }

    private static SuggestionContext context(Fixture fixture,
                                             List<ClickStore.Click> negative,
                                             List<ClickStore.Click> positive) {
        return new SuggestionContext(
                fixture.raw,
                fixture.labels,
                null,
                negative,
                positive,
                Collections.<String, Double>emptyMap());
    }

    private static List<ClickStore.Click> negatives(ClickStore.Click... clicks) {
        return Arrays.asList(clicks);
    }

    private static List<ClickStore.Click> positives(ClickStore.Click... clicks) {
        return Arrays.asList(clicks);
    }

    private static ClickStore.Click negative(int label, int x, int y, int zeroBasedZ) {
        return click(label, x, y, zeroBasedZ, ClickStore.Verdict.NEGATIVE);
    }

    private static ClickStore.Click positive(int label, int x, int y, int zeroBasedZ) {
        return click(label, x, y, zeroBasedZ, ClickStore.Verdict.POSITIVE);
    }

    private static ClickStore.Click click(int label,
                                          int x,
                                          int y,
                                          int zeroBasedZ,
                                          ClickStore.Verdict verdict) {
        return new ClickStore.Click("img", 1, label, zeroBasedZ + 1, x, y,
                verdict, 1L);
    }

    private static Fixture sphereAndRod() {
        Fixture f = blank("sphere-rod", 64, 32, 16);
        fillSphere(f, 12, 16, 8, 5, 1, 200);
        fillBox(f, 28, 52, 14, 17, 7, 9, 2, 40);
        return f;
    }

    private static Fixture twoRodsAndSphere() {
        Fixture f = blank("two-rods-sphere", 48, 24, 12);
        fillBox(f, 4, 20, 5, 7, 4, 6, 1, 40);
        fillBox(f, 4, 20, 15, 17, 4, 6, 2, 40);
        fillSphere(f, 32, 12, 6, 4, 3, 200);
        return f;
    }

    private static Fixture threeBadRodsAndGoodSphere() {
        Fixture f = blank("three-rods-sphere", 48, 28, 14);
        fillBox(f, 3, 15, 4, 6, 4, 6, 1, 20);
        fillBox(f, 3, 15, 12, 14, 4, 6, 2, 22);
        fillBox(f, 3, 15, 20, 22, 4, 6, 3, 24);
        fillSphere(f, 34, 13, 7, 4, 4, 200);
        return f;
    }

    private static Fixture equalCubesWithIntensities(double[] baseIntensity,
                                                     double[] maxIntensity) {
        Fixture f = blank("equal-cubes", 32, 8, 8);
        for (int i = 0; i < baseIntensity.length; i++) {
            int x0 = 2 + i * 7;
            fillCube(f, x0, 2, 2, 3, i + 1, baseIntensity[i]);
            setRaw(f, x0 + 1, 3, 3, maxIntensity[i]);
        }
        return f;
    }

    private static Fixture blank(String title, int width, int height, int depth) {
        ImageStack labels = new ImageStack(width, height);
        ImageStack raw = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            labels.addSlice(new ShortProcessor(width, height));
            raw.addSlice(new FloatProcessor(width, height));
        }
        ImagePlus labelImage = new ImagePlus(title + "-labels", labels);
        labelImage.setDimensions(1, depth, 1);
        ImagePlus rawImage = new ImagePlus(title + "-raw", raw);
        rawImage.setDimensions(1, depth, 1);
        return new Fixture(labelImage, rawImage);
    }

    private static void fillSphere(Fixture f,
                                   int cx,
                                   int cy,
                                   int cz,
                                   int radius,
                                   int label,
                                   double intensity) {
        int r2 = radius * radius;
        for (int z = 0; z < f.labels.getStackSize(); z++) {
            ImageProcessor labels = f.labels.getStack().getProcessor(z + 1);
            ImageProcessor raw = f.raw.getStack().getProcessor(z + 1);
            for (int y = 0; y < f.labels.getHeight(); y++) {
                for (int x = 0; x < f.labels.getWidth(); x++) {
                    int dx = x - cx;
                    int dy = y - cy;
                    int dz = z - cz;
                    if (dx * dx + dy * dy + dz * dz <= r2) {
                        labels.set(x, y, label);
                        raw.setf(x, y, (float) intensity);
                    }
                }
            }
        }
    }

    private static void fillCube(Fixture f,
                                 int x0,
                                 int y0,
                                 int z0,
                                 int edge,
                                 int label,
                                 double intensity) {
        fillBox(f, x0, x0 + edge - 1, y0, y0 + edge - 1,
                z0, z0 + edge - 1, label, intensity);
    }

    private static void fillBox(Fixture f,
                                int x0,
                                int x1,
                                int y0,
                                int y1,
                                int z0,
                                int z1,
                                int label,
                                double intensity) {
        for (int z = Math.max(0, z0); z <= Math.min(f.labels.getStackSize() - 1, z1); z++) {
            ImageProcessor labels = f.labels.getStack().getProcessor(z + 1);
            ImageProcessor raw = f.raw.getStack().getProcessor(z + 1);
            for (int y = Math.max(0, y0); y <= Math.min(f.labels.getHeight() - 1, y1); y++) {
                for (int x = Math.max(0, x0); x <= Math.min(f.labels.getWidth() - 1, x1); x++) {
                    labels.set(x, y, label);
                    raw.setf(x, y, (float) intensity);
                }
            }
        }
    }

    private static void setRaw(Fixture f, int x, int y, int z, double intensity) {
        f.raw.getStack().getProcessor(z + 1).setf(x, y, (float) intensity);
    }

    private static final class Fixture {
        final ImagePlus labels;
        final ImagePlus raw;

        Fixture(ImagePlus labels, ImagePlus raw) {
            this.labels = labels;
            this.raw = raw;
        }
    }
}
